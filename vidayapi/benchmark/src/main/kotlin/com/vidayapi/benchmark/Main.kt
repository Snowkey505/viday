package com.vidayapi.benchmark

import com.vidayapi.benchmark.cases.JsonCases
import com.vidayapi.benchmark.core.BenchmarkCase
import com.vidayapi.benchmark.core.BenchmarkRunner
import com.vidayapi.benchmark.core.CaseResult
import com.vidayapi.benchmark.core.ConcurrentBenchmarkCase
import com.vidayapi.benchmark.core.Environment
import com.vidayapi.benchmark.core.Reporter
import com.vidayapi.benchmark.core.RunConfig
import java.io.File
import java.util.Locale


fun main(args: Array<String>) {
    val cfg = try {
        RunConfig.fromArgs(args).also { it.validate() }
    } catch (e: Exception) {
        System.err.println("Ошибка конфигурации: ${e.message}")
        kotlin.system.exitProcess(2)
    }

    val outDir = File(cfg.out)
    println("=".repeat(100))
    println("ЛР3: микробенчмарк — Jackson 3 vs kotlinx.serialization (vidayapi)")
    println("Модели: x1 (10 полей) / x4 (40 полей) / x8 (80 полей) — 18 кейсов")
    println("Конфигурация: samples=${cfg.samples} warmup=${cfg.warmupSamples} settle=${cfg.settleSamples} drop=${cfg.dropSamples} teardown=${cfg.teardownSamples}")
    println("              batch=${cfg.batch} mixedPairs=${cfg.mixedPairs} case=${cfg.case ?: "all"}")
    println("Выход: ${outDir.absolutePath}")
    println("=".repeat(100))

    val environment = Environment.snapshot()
    environment.forEach { (k, v) -> println("  $k = $v") }
    println("-".repeat(100))

    val runner = BenchmarkRunner(cfg)

    // Прогрев таймингового кода до первых замеров (JIT): усиленный, чтобы
    // компиляция/инициализация «улегла» ДО любых измерений (Требование 2).
    repeat(32) { runner.warmupTick() }

    val results = runCases(runner, cfg)
    val overheadSingle = runner.measureOverheadSingle()
    val overheadConcurrent = runner.measureOverheadConcurrent()
    println("-".repeat(100))
    println("Overhead инструмента (Требование 7, после прогрева):")
    println("  простой сценарий:     ${"%.3f".format(Locale.US, overheadSingle)} нс/операция  (цель ≤ 4–5 нс)")
    println("  смешанный сценарий:   ${"%.3f".format(Locale.US, overheadConcurrent)} нс/операция  (цель ≤ 4–5 нс)")
    println("-".repeat(100))

    val reporter = Reporter(outDir, cfg)
    val doc = reporter.write(environment, overheadSingle, overheadConcurrent, results)
    println("-".repeat(100))
    println("Отчёт сохранён:")
    println("  ${File(outDir, "report.json")}")
    println("  ${File(outDir, "report.html")}  (все таблицы и все графики обоих методов)")
    println("  ${File(outDir, "report.md")}")
    if (cfg.withCharts) {
        println("  $outDir/charts/*.svg (временной ряд, CDF, гистограмма)")
    }
    println("  $outDir/raw/*.csv (сырые сэмплы)")
    println("=".repeat(100))
    println(consoleSummary(doc))
}

/** Прогон выбранных кейсов (все или один через --case). */
private fun runCases(runner: BenchmarkRunner, cfg: RunConfig): List<CaseResult> {
    val cases = JsonCases.all().filter {
        cfg.case == null || it.name == cfg.case
    }
    require(cases.isNotEmpty()) { "Не найдено кейсов для case=${cfg.case}" }
    return cases.map {
        val r = when (it) {
            is BenchmarkCase -> runner.run(it)
            is ConcurrentBenchmarkCase -> runner.runConcurrent(it)
            else -> error("Неизвестный тип кейса: $it")
        }
        println(consoleLine(r))
        r
    }
}

private fun consoleLine(r: CaseResult): String {
    val s = r.stats
    return String.format(
        Locale.US,
        "%-14s %-8s samples=%-5d median=%9.1f ns  p90=%9.1f  p95=%9.1f  p99=%9.1f  mean=%9.1f  ops=%d  dur=%6.1f ms",
        r.name,
        r.library,
        s.count,
        s.p50Ns, s.p90Ns, s.p95Ns, s.p99Ns, s.meanNs,
        r.totalOps,
        r.measure.durationMs,
    )
}

private fun consoleSummary(doc: com.vidayapi.benchmark.core.ReportDoc): String = buildString {
    appendLine("ИТОГОВОЕ СРАВНЕНИЕ (Δ = (kotlinx − Jackson)/Jackson × 100%):")
    appendLine("Модель  Сценарий    Median Jackson, ns  Median kotlinx, ns   Δ median, %    Δ mean, %    Δ p99, %    Быстрее")
    doc.comparisons.forEach { c ->
        appendLine(
            String.format(
                Locale.US,
                "%-4s %-10s %18.1f %18.1f %12.1f %12.1f %12.1f    %s",
                c.model, c.direction, c.medianANs, c.medianBNs, c.deltaMedianPct, c.deltaMeanPct, c.deltaP99Pct, c.fasterLibrary,
            )
        )
    }
}
