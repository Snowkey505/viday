package com.vidayapi.loadtest

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder

/**
 * Live-метрики генератора для Prometheus (endpoint /metrics на порту METRICS_PORT).
 *
 * Нужны, чтобы Grafana показывала RPS, перцентили, ошибки и VU текущего прогона
 * до того, как report.json будет импортирован в PostgreSQL. Метки run_id и
 * workload позволяют держать на одном графике несколько конфигураций (например,
 * CPU=1 и CPU=2) и сразу видеть разницу.
 */
object LiveMetrics {
    private val bucketsSeconds = doubleArrayOf(
        0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0,
    )

    private class Series {
        val requests = LongAdder()
        val unexpected = LongAdder()
        val injected = LongAdder()
        val business = LongAdder()
        val durationSum = DoubleAdder()
        val bucketCounts = Array(bucketsSeconds.size) { LongAdder() }
    }

    private val series = ConcurrentHashMap<String, Series>()
    private val currentVus = AtomicLong(0)

    @Volatile private var runId = "unknown"
    @Volatile private var workload = "unknown"
    @Volatile private var profile = "unknown"
    @Volatile private var apiCpus = "-"
    @Volatile private var apiRamMb = "-"
    @Volatile private var coroutines = "-"
    @Volatile private var dispatcherThreads = "-"
    @Volatile private var server: HttpServer? = null

    fun start(config: LoadConfig) {
        runId = config.runId
        workload = config.workload
        profile = config.profile
        apiCpus = System.getenv("API_CPUS") ?: "-"
        apiRamMb = System.getenv("API_RAM_MB") ?: "-"
        coroutines = config.coroutines.toString()
        dispatcherThreads = config.dispatcherThreads.toString()

        val port = System.getenv("METRICS_PORT")?.toIntOrNull() ?: 9102
        try {
            val httpServer = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)
            server = httpServer
            httpServer.createContext("/metrics") { exchange ->
                val body = render().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            httpServer.createContext("/health") { exchange ->
                val body = "ok\n".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            httpServer.executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "loadtest-live-metrics").apply { isDaemon = true }
            }
            httpServer.start()
            println("LR4 live metrics: http://0.0.0.0:$port/metrics")
        } catch (exc: Exception) {
            // Метрики не должны ронять сам нагрузочный прогон.
            System.err.println("LR4 live metrics disabled: ${exc.message}")
        }
    }

    /** Останавливает HTTP-сервер, иначе JVM не завершается и docker run висит. */
    fun stop() {
        try {
            server?.stop(0)
        } catch (_: Exception) {
            // остановка метрик не должна влиять на результат прогона
        } finally {
            server = null
        }
    }

    fun record(
        durationMs: Double,
        phase: String,
        operation: String,
        status: Int,
        injectedError: Boolean,
    ) {
        val item = series.computeIfAbsent("$phase\u0000$operation") { Series() }
        item.requests.increment()
        when {
            status == 0 || status >= 500 -> item.unexpected.increment()
            injectedError -> item.injected.increment()
            status in 400..499 -> item.business.increment()
        }
        val seconds = durationMs / 1000.0
        item.durationSum.add(seconds)
        var bucket = bucketsSeconds.indexOfFirst { seconds <= it }
        if (bucket < 0) bucket = bucketsSeconds.size - 1
        item.bucketCounts[bucket].increment()
    }

    fun vus(value: Int) {
        currentVus.set(value.toLong())
    }

    private fun render(): String {
        val out = StringBuilder()
        out.appendLine("# HELP viday_loadtest_requests_total Total completed requests by run, phase and operation")
        out.appendLine("# TYPE viday_loadtest_requests_total counter")
        for ((key, item) in series.entries.sortedBy { it.key }) {
            val (phase, operation) = key.split('\u0000', limit = 2)
            val labels = labels(phase, operation)
            out.appendLine("viday_loadtest_requests_total{$labels} ${item.requests.sum()}")
        }

        out.appendLine("# HELP viday_loadtest_request_duration_seconds Request latency histogram")
        out.appendLine("# TYPE viday_loadtest_request_duration_seconds histogram")
        for ((key, item) in series.entries.sortedBy { it.key }) {
            val (phase, operation) = key.split('\u0000', limit = 2)
            val base = labels(phase, operation)
            var cumulative = 0L
            for (i in bucketsSeconds.indices) {
                cumulative += item.bucketCounts[i].sum()
                out.appendLine(
                    "viday_loadtest_request_duration_seconds_bucket{$base,le=\"${bucketsSeconds[i]}\"} $cumulative",
                )
            }
            val total = item.requests.sum()
            out.appendLine("viday_loadtest_request_duration_seconds_bucket{$base,le=\"+Inf\"} $total")
            out.appendLine("viday_loadtest_request_duration_seconds_sum{$base} ${item.durationSum.sum()}")
            out.appendLine("viday_loadtest_request_duration_seconds_count{$base} $total")
        }

        out.appendLine("# HELP viday_loadtest_errors_total Requests by error kind (unexpected = 5xx/timeout)")
        out.appendLine("# TYPE viday_loadtest_errors_total counter")
        for ((key, item) in series.entries.sortedBy { it.key }) {
            val (phase, operation) = key.split('\u0000', limit = 2)
            val base = labels(phase, operation)
            out.appendLine("viday_loadtest_errors_total{$base,kind=\"unexpected\"} ${item.unexpected.sum()}")
            out.appendLine("viday_loadtest_errors_total{$base,kind=\"injected\"} ${item.injected.sum()}")
            out.appendLine("viday_loadtest_errors_total{$base,kind=\"business\"} ${item.business.sum()}")
        }

        out.appendLine("# HELP viday_loadtest_vus Current planned virtual users")
        out.appendLine("# TYPE viday_loadtest_vus gauge")
        out.appendLine("viday_loadtest_vus{${infoLabels()}} ${currentVus.get()}")

        out.appendLine("# HELP viday_loadtest_info Run configuration labels")
        out.appendLine("# TYPE viday_loadtest_info gauge")
        out.appendLine("viday_loadtest_info{${infoLabels()}} 1")
        return out.toString()
    }

    private fun labels(phase: String, operation: String): String =
        "${infoLabels()},phase=\"${escape(phase)}\",operation=\"${escape(operation)}\""

    private fun infoLabels(): String =
        "run_id=\"${escape(runId)}\",workload=\"${escape(workload)}\",profile=\"${escape(profile)}\"," +
            "api_cpus=\"${escape(apiCpus)}\",api_ram_mb=\"${escape(apiRamMb)}\"," +
            "coroutines=\"${escape(coroutines)}\",dispatcher_threads=\"${escape(dispatcherThreads)}\""

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"")
}
