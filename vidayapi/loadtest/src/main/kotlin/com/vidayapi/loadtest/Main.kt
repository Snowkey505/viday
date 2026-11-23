package com.vidayapi.loadtest

import com.vidayapi.loadtest.core.FailureDetector
import com.vidayapi.loadtest.core.LoadDispatcher
import com.vidayapi.loadtest.core.PointWriter
import com.vidayapi.loadtest.http.LoadHttpClient
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() = runBlocking {
    val config = LoadConfig.fromEnv()
    LiveMetrics.start(config)
    try {
        Files.createDirectories(config.outputDir)
        LoadDispatcher(config.dispatcherThreads).use { dispatcher ->
            PointWriter(config.outputDir.resolve("raw.json")).use { points ->
                val failures = FailureDetector(
                    config.earlyStop,
                    config.earlyStopAfter.toSeconds(),
                    config.failureWindow.toSeconds(),
                    config.failureThreshold,
                    config.failureWindows,
                    config.failureMinRequests,
                )
                val http = LoadHttpClient(config, dispatcher, points, failures)
                val seed = loadSeed(config, http)
                val result = LoadRunner(config, dispatcher, points, failures, http, seed).run()
                val state = buildJsonObject {
                    put("run_id", config.runId)
                    put("profile", config.profile)
                    put("workload", config.workload)
                    put("started_at", result.startedAt.toString())
                    put("finished_at", result.finishedAt.toString())
                    put("early_stopped", result.earlyStopped)
                    put("termination_reason", result.terminationReason)
                    put("coroutines", config.coroutines)
                    put("dispatcher_threads", config.dispatcherThreads)
                }
                Files.writeString(config.outputDir.resolve("run-state.json"), state.toString() + "\n")
                Files.writeString(
                    config.outputDir.resolve("summary.json"),
                    buildJsonObject {
                        put("generator", "kotlin-coroutines")
                        put("run_id", config.runId)
                        put("early_stopped", result.earlyStopped)
                        put("termination_reason", result.terminationReason)
                    }.toString() + "\n",
                )
                println("LR4 finished: early_stopped=${result.earlyStopped}, reason=${result.terminationReason}")
            }
        }
    } finally {
        LiveMetrics.stop()
    }
}
