package com.vidayapi.benchmark.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Locale

/** CSS самодостаточного HTML-отчёта (без `$`, чтобы не конфликтовать с интерполяцией). */
private val HTML_CSS = """
    body { font-family: 'DejaVu Sans', 'Segoe UI', Arial, sans-serif; margin: 2rem auto; max-width: 1100px; padding: 0 1rem; color: #222; line-height: 1.5; }
    h1 { border-bottom: 3px solid #1f77b4; padding-bottom: .4rem; }
    h2 { margin-top: 2rem; border-bottom: 1px solid #ddd; padding-bottom: .3rem; }
    h3 { margin-top: 1.6rem; color: #1f4e79; }
    table { border-collapse: collapse; margin: 1rem 0; width: 100%; font-size: 14px; }
    th, td { border: 1px solid #ccc; padding: .35rem .6rem; text-align: left; vertical-align: top; }
    th { background: #f0f4f8; }
    tr:nth-child(even) td { background: #fafbfc; }
    code { background: #f4f4f4; padding: .1em .3em; border-radius: 3px; font-size: 13px; }
    .chart { margin: .4rem 0 1rem; border: 1px solid #e2e2e2; border-radius: 6px; padding: .5rem; background: #fff; overflow-x: auto; }
    .chart figcaption { font-size: 13px; color: #555; margin-bottom: .4rem; }
    svg { max-width: 100%; height: auto; }
    .meta { color: #777; font-size: 14px; }
    .compare-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; align-items: start; margin: 1rem 0; }
    .chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; margin: 1.2rem 0; }
    .chart-row h4 { grid-column: 1 / -1; margin: 0 0 .3rem; color: #333; border-bottom: 1px dashed #ccc; padding-bottom: .2rem; }
    .card { border: 1px solid #e2e2e2; border-radius: 8px; padding: .7rem .9rem; background: #fdfefe; }
    .card h4 { margin: 0 0 .5rem; border-bottom: 2px solid #bbb; padding-bottom: .3rem; }
    .card.jackson h4 { border-color: #1f77b4; color: #1f77b4; }
    .card.kotlinx h4 { border-color: #2ca02c; color: #2ca02c; }
    .delta-good { color: #2ca02c; font-weight: bold; }
    .delta-bad { color: #d62728; font-weight: bold; }
    @media (max-width: 920px) { .compare-grid, .chart-row { grid-template-columns: 1fr; } }
""".trimIndent()

/**
 * Формирует отчётность ЛР3 (Требование 8):
 *   • report.json — машиночитаемые результаты (все перцентили, CDF, гистограмма, таймсерия);
 *   • report.html — самодостаточный отчёт со ВСЕМИ таблицами и ВСЕМИ графиками (SVG встроены);
 *   • report.md   — читаемый отчёт с таблицами и встроенными изображениями графиков;
 *   • charts — SVG-графики (a) временной ряд, (b) CDF/перцентили, (c) гистограмма;
 *   • raw — CSV с «сырыми» сэмплами для внешних инструментов.
 * Плюс консольная сводка с парами сравнения.
 */
class Reporter(private val outDir: File, private val config: RunConfig) {

    private val chartsDir = File(outDir, "charts")
    private val rawDir = File(outDir, "raw")

    fun write(
        environment: Map<String, String>,
        overheadSingleNs: Double,
        overheadConcurrentNs: Double,
        results: List<CaseResult>,
    ): ReportDoc {
        outDir.mkdirs()
        chartsDir.mkdirs()
        rawDir.mkdirs()

        val doc = buildDoc(environment, overheadSingleNs, overheadConcurrentNs, results)

        File(outDir, "report.json").writeText(
            Json { prettyPrint = true; encodeDefaults = true }.encodeToString(doc)
        )

        // SVG-графики генерируем ОДИН раз: пишем файлами и встраиваем в HTML-отчёт.
        val charts: Map<String, Map<String, String>> = if (config.withCharts) {
            results.associate { it.name to it.chartSvgs() }
        } else {
            emptyMap()
        }
        val compareCharts: Map<String, String> = if (config.withCharts) {
            buildComparisonCharts(results)
        } else {
            emptyMap()
        }
        writeChartFiles(charts, compareCharts)
        results.forEach { it.writeRawCsv() }

        File(outDir, "report.md").writeText(markdown(doc, compareCharts))
        File(outDir, "report.html").writeText(html(doc, charts, compareCharts))

        return doc
    }

    // ------------------------------------------------------------------ документ

    private fun buildDoc(
        environment: Map<String, String>,
        overheadSingleNs: Double,
        overheadConcurrentNs: Double,
        results: List<CaseResult>,
    ): ReportDoc = ReportDoc(
        title = "ЛР3: микробенчмарк — Jackson 3 vs kotlinx.serialization (vidayapi)",
        generatedAt = Instant.now().toString(),
        environment = environment,
        config = ConfigDoc(
            samples = config.samples,
            warmupSamples = config.warmupSamples,
            settleSamples = config.settleSamples,
            dropSamples = config.dropSamples,
            teardownSamples = config.teardownSamples,
            batch = config.batch,
            mixedPairs = config.mixedPairs,
        ),
        overhead = OverheadDoc(
            singleNsPerOp = overheadSingleNs,
            concurrentNsPerOp = overheadConcurrentNs,
        ),
        cases = results.map { it.toDoc(overheadSingleNs, overheadConcurrentNs) },
        comparisons = comparisons(results, overheadSingleNs, overheadConcurrentNs),
    )

    // ------------------------------------------------------------------ markdown

    private fun markdown(doc: ReportDoc, compareCharts: Map<String, String>): String = buildString {
        appendLine("# ${doc.title}")
        appendLine()
        appendLine("> Сгенерировано автоматически: `${doc.generatedAt}`.")
        appendLine()

        appendLine("## 1. Окружение прогона (воспроизводимость, Требование 6)")
        appendLine()
        appendLine("| Параметр | Значение |")
        appendLine("|---|---|")
        doc.environment.entries.sortedBy { it.key }.forEach { (k, v) ->
            appendLine("| `$k` | `$v` |")
        }
        appendLine()

        appendLine("## 2. Конфигурация прогона (Требования 1, 2)")
        appendLine()
        appendLine(
            "| Параметр | Значение |"
        )
        appendLine("|---|---|")
        appendLine("| испытаний (samples) | ${doc.config.samples} (≥100 — Требование 1) |")
        appendLine("| warmup-сэмплов | ${doc.config.warmupSamples} (Требование 2) |")
        appendLine("| settle-сэмплов (стабилизация; отбрасываются) | ${doc.config.settleSamples} |")
        appendLine("| первых сэмплов measure удалено (drop; фиксируется в отчёте) | ${doc.config.dropSamples} |")
        appendLine("| teardown-сэмплов | ${doc.config.teardownSamples} (Требование 2) |")
        appendLine("| операций в сэмпле (batch) | ${doc.config.batch} |")
        appendLine("| пар read+write в сэмпле mixed | ${doc.config.mixedPairs} |")
        appendLine()

        appendLine("## 3. Overhead инструмента (Требование 7)")
        appendLine()
        appendLine(
            "| Режим | Overhead, нс/операция |"
        )
        appendLine("|---|---|")
        appendLine("| простой сценарий (2×nanoTime + цикл + Blackhole) / batch | `${"%.3f".format(Locale.US, doc.overhead.singleNsPerOp)}` |")
        appendLine("| смешанный сценарий (2 потока + диспетчеризация) / 2×pairs | `${"%.3f".format(Locale.US, doc.overhead.concurrentNsPerOp)}` |")
        appendLine()
        appendLine(
            "> Целевой ориентир задания — не более 4–5 нс; батч-измерение даёт sub-ns overhead. " +
                "«Сырые» значения в таблицах включают overhead; столбец «net median» = median − overhead."
        )
        appendLine()

        appendLine("## 4. Сравнение по сценариям: таблицы и графики обоих методов")
        appendLine()
        appendLine("> Для каждого сценария: сводная таблица «метрика × метод», детальные таблицы")
        appendLine("> и графики обоих методов. Δ = (kotlinx − Jackson) / Jackson × 100%; отрицательная — kotlinx быстрее.")
        appendLine("> Фаза **settle** (между warmup и measure) отбрасывается: первые сэмплы после прогрева")
        appendLine("> (ленивая инициализация, JIT, GC-паузы) не входят в статистику и графики.")
        appendLine("> Дополнительно из фазы measure удалено **${doc.config.dropSamples}** первых сэмплов")
        appendLine("> («стартовый переходный процесс»); это справочно отражено в конфигурации.")
        appendLine()
        val models = doc.cases.map { it.model }.distinct().sorted()
        models.forEach { model ->
            val modelCases = doc.cases.filter { it.model == model }
            val fieldCount = modelCases.firstOrNull()?.fieldCount ?: 0
            appendLine("### Модель ${model} (${fieldCount} полей)")
            appendLine()
            listOf("read", "write", "mixed").forEach { direction ->
                val cs = modelCases.filter { it.direction == direction }
                if (cs.isEmpty()) return@forEach
                val jackson = cs.firstOrNull { it.library == "jackson" }
                val kotlinx = cs.firstOrNull { it.library == "kotlinx" }
                val scenarioLabel = (jackson ?: kotlinx)?.scenario ?: direction
                appendLine("#### ${direction} — ${scenarioLabel}")
                appendLine()

            if (jackson != null && kotlinx != null) {
                appendLine("**Все метрики обоих методов (латентность, ns/операция):**")
                appendLine()
                appendLine("| Метрика | Jackson 3 | kotlinx.serialization | Δ, % |")
                appendLine("|---|---|---|---|")
                metricRow("min", jackson.stats.minNs, kotlinx.stats.minNs)
                metricRow("**p50 (медиана)**", jackson.stats.p50Ns, kotlinx.stats.p50Ns, strong = true)
                metricRow("p75", jackson.stats.p75Ns, kotlinx.stats.p75Ns)
                metricRow("p90", jackson.stats.p90Ns, kotlinx.stats.p90Ns)
                metricRow("p95", jackson.stats.p95Ns, kotlinx.stats.p95Ns)
                metricRow("p99", jackson.stats.p99Ns, kotlinx.stats.p99Ns)
                metricRow("max", jackson.stats.maxNs, kotlinx.stats.maxNs)
                metricRow("mean", jackson.stats.meanNs, kotlinx.stats.meanNs)
                metricRow("stdev", jackson.stats.stdevNs, kotlinx.stats.stdevNs)
                metricRow("net median (минус overhead)", jackson.netMedianNs, kotlinx.netMedianNs)
                appendLine(
                    "| throughput, ops/s | `${"%,.0f".format(Locale.US, jackson.throughputOpsPerSec)}` | " +
                        "`${"%,.0f".format(Locale.US, kotlinx.throughputOpsPerSec)}` | |"
                )
                appendLine()
            }

            listOf(jackson to "**Jackson 3**", kotlinx to "**kotlinx.serialization**").forEach { (c, label) ->
                if (c == null) return@forEach
                appendLine("#### $label — ${c.name}")
                appendLine()
                appendLine("**Фазы (8e, 8f):**")
                appendLine()
                appendLine("| Фаза | Сэмплов | Операций в сэмпле | Операций всего | Время, мс |")
                appendLine("|---|---|---|---|---|")
                listOf(c.warmup, c.settle, c.measure, c.teardown).forEach { p ->
                    appendLine("| ${p.label} | ${p.samples} | ${p.opsPerSample} | ${p.ops} | `${"%.1f".format(Locale.US, p.durationMs)}` |")
                }
                appendLine("| **итого** | | | **${c.totalOps}** | |")
                appendLine()
                appendLine(
                    "**Статистика (8d), ns/операция; throughput ≈ " +
                        "`${"%,.0f".format(Locale.US, c.throughputOpsPerSec)}` ops/s:**"
                )
                appendLine()
                appendLine("| Метрика | Значение |")
                appendLine("|---|---|")
                appendLine("| сэмплов | ${c.stats.count} |")
                appendLine("| mean | `${"%.1f".format(Locale.US, c.stats.meanNs)}` ns |")
                appendLine("| stdev | `${"%.1f".format(Locale.US, c.stats.stdevNs)}` ns |")
                appendLine("| min | `${"%.1f".format(Locale.US, c.stats.minNs)}` ns |")
                appendLine("| max | `${"%.1f".format(Locale.US, c.stats.maxNs)}` ns |")
                appendLine("| **p50 (медиана)** | **`${"%.1f".format(Locale.US, c.stats.p50Ns)}` ns** (net: `${"%.1f".format(Locale.US, c.netMedianNs)}` ns) |")
                appendLine("| p75 | `${"%.1f".format(Locale.US, c.stats.p75Ns)}` ns |")
                appendLine("| p90 | `${"%.1f".format(Locale.US, c.stats.p90Ns)}` ns |")
                appendLine("| p95 | `${"%.1f".format(Locale.US, c.stats.p95Ns)}` ns |")
                appendLine("| p99 | `${"%.1f".format(Locale.US, c.stats.p99Ns)}` ns |")
                appendLine()
                appendLine("**Перцентили (8b/8d):**")
                appendLine()
                appendLine("| Перцентиль | Латентность, ns |")
                appendLine("|---|---|")
                c.stats.percentiles.forEach { p ->
                    appendLine("| p${"%.3f".format(Locale.US, p.percentile)} | `${"%.1f".format(Locale.US, p.ns)}` |")
                }
                appendLine()
                if (config.withCharts) {
                    appendLine("**Графики:**")
                    appendLine()
                    appendLine("![Временной ряд](charts/${c.name}-timeseries.svg)")
                    appendLine("![CDF/перцентили](charts/${c.name}-cdf.svg)")
                    appendLine("![Гистограмма](charts/${c.name}-histogram.svg)")
                    appendLine()
                }
            }

                if (config.withCharts) {
                    appendLine("**Оба метода на одном графике (наложение):**")
                    appendLine()
                    appendLine("![Временной ряд](charts/${model}-${direction}-overlay-timeseries.svg)")
                    appendLine("![CDF](charts/${model}-${direction}-overlay-cdf.svg)")
                    appendLine("![Гистограмма](charts/${model}-${direction}-overlay-histogram.svg)")
                    appendLine()
                }
            }
        }

        appendLine("## 5. Сводная таблица сравнения (Jackson 3 vs kotlinx.serialization)")
        appendLine()
        appendLine("| Модель | Сценарий | Медиана Jackson, ns | Медиана kotlinx, ns | Δ median | Δ mean | Δ p99 | Быстрее |")
        appendLine("|---|---|---|---|---|---|---|---|")
        doc.comparisons.forEach { cmp ->
            appendLine(
                "| ${cmp.model} | ${cmp.direction} | `${"%.1f".format(Locale.US, cmp.medianANs)}` | `${"%.1f".format(Locale.US, cmp.medianBNs)}` | " +
                    "`${"%.1f".format(Locale.US, cmp.deltaMedianPct)}%` | `${"%.1f".format(Locale.US, cmp.deltaMeanPct)}%` | " +
                    "`${"%.1f".format(Locale.US, cmp.deltaP99Pct)}%` | **${cmp.fasterLibrary}** |"
            )
        }
        appendLine()
        appendLine("> Δ = (kotlinx − Jackson) / Jackson × 100%. Отрицательное значение — kotlinx быстрее.")
        appendLine()

        if (config.withCharts) {
            appendLine("## 5.1 Перцентили p50 и p99: столбчатое сравнение библиотек")
            appendLine()
            appendLine("> Девять групп столбцов — 3 модели (x1/x4/x8) × 3 сценария (read/write/mixed); в каждой два столбца: Jackson (синий) и kotlinx (зелёный).")
            appendLine()
            appendLine("![p50 (медиана)](charts/compare-p50-bars.svg)")
            appendLine()
            appendLine("![p99](charts/compare-p99-bars.svg)")
            appendLine()
        }

        appendLine("## 6. «Сырые» сэмплы")
        appendLine()
        appendLine("Каждый кейс: `raw/<case>.csv` (колонки `sample,ns_per_op`). Полные данные — `report.json`.")
        appendLine()
    }

    // ------------------------------------------------------------------ html

    /**
     * Самодостаточный HTML-отчёт: все таблицы прогона + все графики каждого
     * кейса (для обоих сравниваемых методов), SVG встроены прямо в страницу —
     * файл не зависит от каталога charts и открывается где угодно.
     */
    private fun html(doc: ReportDoc, charts: Map<String, Map<String, String>>, compareCharts: Map<String, String>): String =
        buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"ru\">")
            appendLine("<head>")
            appendLine("<meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("<title>${escHtml(doc.title)}</title>")
            appendLine("<style>")
            appendLine(HTML_CSS)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<h1>${escHtml(doc.title)}</h1>")
            appendLine("<p class=\"meta\">Сгенерировано автоматически: <code>${escHtml(doc.generatedAt)}</code></p>")

            // 1. Окружение
            appendLine("<h2>1. Окружение прогона (воспроизводимость, Требование 6)</h2>")
            appendTable(
                listOf("Параметр", "Значение"),
                doc.environment.entries.sortedBy { it.key }.map { (k, v) -> listOf(code(k), code(v)) },
            )

            // 2. Конфигурация
            appendLine("<h2>2. Конфигурация прогона (Требования 1, 2)</h2>")
            appendTable(
                listOf("Параметр", "Значение"),
                listOf(
                    listOf("испытаний (samples)", "${doc.config.samples} (≥100 — Требование 1)"),
                    listOf("warmup-сэмплов", "${doc.config.warmupSamples} (Требование 2)"),
                    listOf("settle-сэмплов (стабилизация; отбрасываются)", doc.config.settleSamples.toString()),
                    listOf("первых сэмплов measure удалено (drop)", doc.config.dropSamples.toString()),
                    listOf("teardown-сэмплов", "${doc.config.teardownSamples} (Требование 2)"),
                    listOf("операций в сэмпле (batch)", doc.config.batch.toString()),
                    listOf("пар read+write в сэмпле mixed", doc.config.mixedPairs.toString()),
                ),
            )

            // 3. Overhead
            appendLine("<h2>3. Overhead инструмента (Требование 7)</h2>")
            appendTable(
                listOf("Режим", "Overhead, нс/операция"),
                listOf(
                    listOf("простой сценарий (2×nanoTime + цикл + Blackhole) / batch", monoFmt(doc.overhead.singleNsPerOp)),
                    listOf("смешанный сценарий (2 потока + диспетчеризация) / 2×pairs", monoFmt(doc.overhead.concurrentNsPerOp)),
                ),
            )

            // 4. Сценарии: таблицы и графики обоих методов РЯДОМ (противопоставление)
            val scenarioOrder = listOf("read", "write", "mixed")
            appendLine("<h2>4. Сравнение по сценариям: графики и таблицы обоих методов</h2>")
            appendLine(
                "<p class=\"meta\">Для каждой модели (x1/x4/x8) и сценария: сводная таблица «метрика × метод», карточки с деталями " +
                    "и графики одного типа вплотную друг к другу. Δ = (kotlinx − Jackson) / Jackson × 100%; " +
                    "отрицательная — kotlinx быстрее. Фаза <strong>settle</strong> (между warmup и measure) отбрасывается — " +
                    "первые сэмплы после прогрева (ленивая инициализация, JIT, GC-паузы) не входят в статистику и графики; " +
                    "из measure дополнительно удалено <strong>${doc.config.dropSamples}</strong> первых сэмплов.</p>"
            )
            val models = doc.cases.map { it.model }.distinct().sorted()
            models.forEach { model ->
                val modelCases = doc.cases.filter { it.model == model }
                val fieldCount = modelCases.firstOrNull()?.fieldCount ?: 0
                appendLine("<h3 id=\"model-${escHtml(model)}\">Модель ${escHtml(model)} (${fieldCount} полей)</h3>")
                scenarioOrder.forEach { direction ->
                    val cs = modelCases.filter { it.direction == direction }
                    if (cs.isEmpty()) return@forEach
                    val jackson = cs.firstOrNull { it.library == "jackson" }
                    val kotlinx = cs.firstOrNull { it.library == "kotlinx" }
                    val scenarioLabel = (jackson ?: kotlinx)?.scenario ?: direction
                    appendLine("<h4 id=\"scenario-${escHtml(model)}-${escHtml(direction)}\">Сценарий «${escHtml(direction)}» — ${escHtml(scenarioLabel)}</h4>")

                if (jackson != null && kotlinx != null) {
                    appendLine("<p><strong>Все метрики обоих методов (латентность, ns/операция):</strong></p>")
                    appendTable(
                        listOf("Метрика", "Jackson 3", "kotlinx.serialization", "Δ, %"),
                        listOf(
                            metricRowHtml("min", jackson.stats.minNs, kotlinx.stats.minNs),
                            metricRowHtml("<strong>p50 (медиана)</strong>", jackson.stats.p50Ns, kotlinx.stats.p50Ns, strong = true),
                            metricRowHtml("p75", jackson.stats.p75Ns, kotlinx.stats.p75Ns),
                            metricRowHtml("p90", jackson.stats.p90Ns, kotlinx.stats.p90Ns),
                            metricRowHtml("p95", jackson.stats.p95Ns, kotlinx.stats.p95Ns),
                            metricRowHtml("p99", jackson.stats.p99Ns, kotlinx.stats.p99Ns),
                            metricRowHtml("max", jackson.stats.maxNs, kotlinx.stats.maxNs),
                            metricRowHtml("mean", jackson.stats.meanNs, kotlinx.stats.meanNs),
                            metricRowHtml("stdev", jackson.stats.stdevNs, kotlinx.stats.stdevNs),
                            metricRowHtml("net median (минус overhead)", jackson.netMedianNs, kotlinx.netMedianNs),
                            listOf("throughput, ops/s", fmtThousand(jackson.throughputOpsPerSec), fmtThousand(kotlinx.throughputOpsPerSec), ""),
                        ),
                    )
                }

                // 4.x Детальные карточки методов (фазы, статистика, перцентили)
                appendLine("<div class=\"compare-grid\">")
                listOf(
                    Triple(jackson, "Jackson 3", "jackson"),
                    Triple(kotlinx, "kotlinx.serialization", "kotlinx"),
                ).forEach { (c, label, cssCls) ->
                    if (c == null) return@forEach
                    appendLine("<div class=\"card $cssCls\">")
                    appendLine("<h4>${escHtml(label)} — ${escHtml(c.name)}</h4>")
                    appendLine("<p><strong>Фазы (8e/8f):</strong></p>")
                    appendTable(
                        listOf("Фаза", "Сэмплов", "Оп/сэмпл", "Операций", "Время, мс"),
                        listOf(
                            phaseRow(c.warmup),
                            phaseRow(c.settle),
                            phaseRow(c.measure),
                            phaseRow(c.teardown),
                            listOf("<strong>итого</strong>", "", "", "<strong>${c.totalOps}</strong>", ""),
                        ),
                    )
                    appendLine(
                        "<p><strong>Статистика (8d), ns/операция; throughput ≈ " +
                            "<code>${fmtThousand(c.throughputOpsPerSec)}</code> ops/s:</strong></p>"
                    )
                    appendTable(
                        listOf("Метрика", "Значение"),
                        listOf(
                            listOf("сэмплов", c.stats.count.toString()),
                            listOf("mean", monoFmt(c.stats.meanNs)),
                            listOf("stdev", monoFmt(c.stats.stdevNs)),
                            listOf("min", monoFmt(c.stats.minNs)),
                            listOf("max", monoFmt(c.stats.maxNs)),
                            listOf("<strong>p50 (медиана)</strong>", "<strong>${monoFmt(c.stats.p50Ns)}</strong> (net: ${monoFmt(c.netMedianNs)})"),
                            listOf("p75", monoFmt(c.stats.p75Ns)),
                            listOf("p90", monoFmt(c.stats.p90Ns)),
                            listOf("p95", monoFmt(c.stats.p95Ns)),
                            listOf("p99", monoFmt(c.stats.p99Ns)),
                        ),
                    )
                    appendLine("<p><strong>Перцентили (8b/8d):</strong></p>")
                    appendTable(
                        listOf("Перцентиль", "Латентность, ns"),
                        c.stats.percentiles.map { p -> listOf("p${"%.3f".format(Locale.US, p.percentile)}", monoFmt(p.ns)) },
                    )
                    appendLine("</div>")
                }
                appendLine("</div>")

                // 4.y Графики одного типа рядом (Jackson слева, kotlinx справа)
                listOf(
                    "timeseries" to "Временной ряд — латентность по сэмплам, ns/операция",
                    "cdf" to "CDF — доля запросов ≤ латентность",
                    "histogram" to "Гистограмма распределения латентности",
                ).forEach { (kind, caption) ->
                    val aSvg = jackson?.let { charts[it.name]?.get(kind) }
                    val bSvg = kotlinx?.let { charts[it.name]?.get(kind) }
                    if (aSvg != null && bSvg != null) {
                        appendLine("<div class=\"chart-row\">")
                        appendLine("<h4>$caption</h4>")
                        appendLine("<figure class=\"chart\"><figcaption>Jackson 3 — ${escHtml(jackson!!.name)}</figcaption>")
                        append(aSvg)
                        appendLine("</figure>")
                        appendLine("<figure class=\"chart\"><figcaption>kotlinx.serialization — ${escHtml(kotlinx!!.name)}</figcaption>")
                        append(bSvg)
                        appendLine("</figure>")
                        appendLine("</div>")
                    }
                }

                // 4.z Наложение обоих методов на один график
                    if (config.withCharts) {
                        appendLine("<h4>Оба метода на одном графике (наложение):</h4>")
                        listOf(
                            "timeseries" to "Временной ряд — оба метода на одной оси",
                            "cdf" to "CDF — оба метода на одной оси",
                            "histogram" to "Гистограмма — оба метода на одной оси (общие бины)",
                        ).forEach { (kind, caption) ->
                            val svg = compareCharts["$model-$direction-overlay-$kind"]
                            if (svg != null) {
                                appendLine("<figure class=\"chart\"><figcaption>$caption</figcaption>")
                                append(svg)
                                appendLine("</figure>")
                            }
                        }
                    }
                }
            }

            // 5. Перцентили p50/p99: столбчатые графики сравнения
            appendLine("<h2>5. Перцентили p50 и p99: столбчатое сравнение библиотек</h2>")
            appendLine("<p class=\"meta\">Девять групп столбцов — 3 модели (x1/x4/x8) × 3 сценария (read/write/mixed); в каждой два столбца: Jackson (синий) и kotlinx.serialization (зелёный).</p>")
            appendLine("<div class=\"chart-row\">")
            appendLine("<figure class=\"chart\"><figcaption>p50 (медиана), ns — сравнение по сценариям</figcaption>")
            compareCharts["compare-p50-bars"]?.let { append(it) }
            appendLine("</figure>")
            appendLine("<figure class=\"chart\"><figcaption>p99, ns — сравнение по сценариям</figcaption>")
            compareCharts["compare-p99-bars"]?.let { append(it) }
            appendLine("</figure>")
            appendLine("</div>")

            // 6. Сравнение Jackson vs kotlinx: сводная таблица
            appendLine("<h2>6. Сводная таблица: Jackson 3 vs kotlinx.serialization</h2>")
            appendTable(
                listOf("Модель", "Сценарий", "Медиана Jackson, ns", "Медиана kotlinx, ns", "Δ median", "Δ mean", "Δ p99", "Быстрее"),
                doc.comparisons.map { c ->
                    listOf(
                        escHtml(c.model),
                        escHtml(c.direction),
                        monoFmt(c.medianANs),
                        monoFmt(c.medianBNs),
                        monoFmt(c.deltaMedianPct) + "%",
                        monoFmt(c.deltaMeanPct) + "%",
                        monoFmt(c.deltaP99Pct) + "%",
                        "<strong>" + escHtml(c.fasterLibrary) + "</strong>",
                    )
                },
            )
            appendLine("<p class=\"meta\">Δ = (kotlinx − Jackson) / Jackson × 100%. Отрицательное значение — kotlinx быстрее.</p>")

            // 7. Сырые сэмплы
            appendLine("<h2>7. «Сырые» сэмплы</h2>")
            appendLine(
                "<p>Каждый кейс: <code>raw/" + escHtml("<case>") +
                    ".csv</code> (колонки <code>sample,ns_per_op</code>). Полные данные — <code>report.json</code>.</p>"
            )

            appendLine("</body>")
            appendLine("</html>")
        }

    private fun StringBuilder.appendTable(headers: List<String>, rows: List<List<String>>) {
        append("<table><thead><tr>")
        headers.forEach { append("<th>${escHtml(it)}</th>") }
        append("</tr></thead><tbody>")
        rows.forEach { row ->
            append("<tr>")
            row.forEach { cell -> append("<td>$cell</td>") }
            append("</tr>")
        }
        append("</tbody></table>")
    }

    private fun code(v: String): String = "<code>${escHtml(v)}</code>"

    private fun monoFmt(v: Double): String = "<code>${"%.1f".format(Locale.US, v)}</code>"

    private fun fmtThousand(v: Double): String = "%,.0f".format(Locale.US, v)

    /** Строка MD-таблицы «метрика × оба метода» с Δ. */
    private fun StringBuilder.metricRow(label: String, a: Double, b: Double, strong: Boolean = false) {
        val fmtA = if (strong) "**`${"%.1f".format(Locale.US, a)}`**" else "`${"%.1f".format(Locale.US, a)}`"
        val fmtB = if (strong) "**`${"%.1f".format(Locale.US, b)}`**" else "`${"%.1f".format(Locale.US, b)}`"
        appendLine("| $label | $fmtA | $fmtB | `${"%.1f".format(Locale.US, deltaPct(a, b))}%` |")
    }

    /** Строка HTML-таблицы «метрика × оба метода» с цветной Δ. */
    private fun metricRowHtml(label: String, a: Double, b: Double, strong: Boolean = false): List<String> {
        val bj = if (strong) "<strong>${monoFmt(a)}</strong>" else monoFmt(a)
        val bx = if (strong) "<strong>${monoFmt(b)}</strong>" else monoFmt(b)
        return listOf(label, bj, bx, deltaCell(a, b))
    }

    private fun phaseRow(p: PhaseDoc): List<String> =
        listOf(escHtml(p.label), p.samples.toString(), p.opsPerSample.toString(), p.ops.toString(), monoFmt(p.durationMs))

    private fun deltaCell(a: Double, b: Double): String {
        val p = deltaPct(a, b)
        val cls = if (p < 0) "delta-good" else "delta-bad"
        return "<span class=\"$cls\">${"%.1f".format(Locale.US, p)}%</span>"
    }

    private fun escHtml(s: String): String = buildString {
        for (ch in s) when (ch) {
            '&' -> append('&').append("amp;")
            '<' -> append('&').append("lt;")
            '>' -> append('&').append("gt;")
            '"' -> append('&').append("quot;")
            else -> append(ch)
        }
    }

    // ------------------------------------------------------------------ сравнение

    private fun comparisons(
        results: List<CaseResult>,
        overheadSingleNs: Double,
        overheadConcurrentNs: Double,
    ): List<ComparisonDoc> {
        // Пары «модель × сценарий»: в каждой ровно один Jackson- и один kotlinx-кейс.
        val byKey = results.groupBy { it.model + "\u0000" + it.direction }
        return byKey.keys.sorted().mapNotNull { key ->
            val model = key.substringBefore("\u0000")
            val direction = key.substringAfter("\u0000")
            val a = byKey[key]?.firstOrNull { it.library == "jackson" } ?: return@mapNotNull null
            val b = byKey[key]?.firstOrNull { it.library == "kotlinx" } ?: return@mapNotNull null
            val ovh = if (direction == "mixed") overheadConcurrentNs else overheadSingleNs
            ComparisonDoc(
                model = model,
                direction = direction,
                scenario = a.scenario.label,
                libraryA = a.library,
                libraryB = b.library,
                medianANs = a.stats.p50Ns,
                medianBNs = b.stats.p50Ns,
                meanANs = a.stats.meanNs,
                meanBNs = b.stats.meanNs,
                p99ANs = a.stats.p99Ns,
                p99BNs = b.stats.p99Ns,
                deltaMedianPct = deltaPct(a.stats.p50Ns, b.stats.p50Ns),
                deltaMeanPct = deltaPct(a.stats.meanNs, b.stats.meanNs),
                deltaP99Pct = deltaPct(a.stats.p99Ns, b.stats.p99Ns),
                fasterLibrary = if (b.stats.p50Ns < a.stats.p50Ns) "kotlinx.serialization" else "Jackson 3",
                overheadNsPerOp = ovh,
            )
        }
    }

    private fun deltaPct(a: Double, b: Double): Double = if (a == 0.0) 0.0 else (b - a) / a * 100.0

    // ------------------------------------------------------------------ DTO

    private fun CaseResult.toDoc(overheadSingleNs: Double, overheadConcurrentNs: Double): CaseDoc {
        val ovh = if (direction == "mixed") overheadConcurrentNs else overheadSingleNs
        return CaseDoc(
            name = name,
            model = model,
            fieldCount = fieldCount,
            description = description,
            scenario = scenario.label,
            direction = direction,
            library = library,
            opsPerSample = opsPerSample,
            totalOps = totalOps,
            warmup = warmup.toDoc(),
            settle = settle.toDoc(),
            measure = measure.toDoc(),
            teardown = teardown.toDoc(),
            stats = stats.toDoc(),
            throughputOpsPerSec = stats.throughputOpsPerSec,
            netMedianNs = (stats.p50Ns - ovh).coerceAtLeast(0.0),
        )
    }

    private fun PhaseRecord.toDoc() = PhaseDoc(label, samples, opsPerSample, totalOps, durationMs)

    private fun Statistics.toDoc() = StatsDoc(
        count = count,
        meanNs = meanNs,
        stdevNs = stdevNs,
        minNs = minNs,
        maxNs = maxNs,
        p50Ns = p50Ns,
        p75Ns = p75Ns,
        p90Ns = p90Ns,
        p95Ns = p95Ns,
        p99Ns = p99Ns,
        percentiles = percentiles.map { PercentileDoc(it.percentile, it.ns) },
        histogram = histogram.buckets.map { HistogramBucketDoc(it.fromNs, it.toNs, it.count) },
        cdf = cdf.map { CdfDoc(it.latencyNs, it.fraction) },
        timeSeriesNs = timeSeriesNs.toList(),
    )

    // ------------------------------------------------------------------ файлы

    private fun CaseResult.writeRawCsv() {
        File(rawDir, "$name.csv").writeText(buildString {
            appendLine("sample,ns_per_op")
            measure.sampleTimesNs.forEachIndexed { i, t ->
                appendLine("$i,${"%.2f".format(Locale.US, t / opsPerSample)}")
            }
        })
    }

    /** Три SVG-графика кейса: временной ряд, CDF, гистограмма (для файлов и HTML). */
    private fun CaseResult.chartSvgs(): Map<String, String> {
        val required = StatisticsCalculator.REQUIRED_PERCENTILES
        val marks = stats.percentiles.filter { it.percentile in required }
        val color = if (library == "jackson") SvgCharts.COLOR_JACKSON else SvgCharts.COLOR_KOTLINX
        return linkedMapOf(
            "timeseries" to SvgCharts.timeSeries(
                title = "$name — латентность во времени (ns/операция)",
                yLabel = "ns/операция",
                ys = stats.timeSeriesNs,
                refs = listOf(
                    "min" to stats.minNs,
                    "p50" to stats.p50Ns,
                    "p99" to stats.p99Ns,
                    "max" to stats.maxNs,
                ),
                color = color,
            ),
            "cdf" to SvgCharts.cdf(
                title = "$name — CDF: доля запросов ≤ латентность",
                points = stats.cdf,
                marks = marks,
                color = color,
            ),
            "histogram" to SvgCharts.histogram(
                title = "$name — гистограмма распределения латентности",
                hist = stats.histogram,
                color = color,
            ),
        )
    }

    /**
     * Дополнительные графики сравнения:
     *   • overlay — оба метода на одном графике (временной ряд / CDF / гистограмма) по сценарию;
     *   • столбцы перцентилей p50/p99: три сценария × два метода.
     */
    private fun buildComparisonCharts(results: List<CaseResult>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val models = results.map { it.model }.distinct().sorted()
        models.forEach { model ->
            listOf("read", "write", "mixed").forEach { direction ->
                val a = results.firstOrNull { it.model == model && it.direction == direction && it.library == "jackson" } ?: return@forEach
                val b = results.firstOrNull { it.model == model && it.direction == direction && it.library == "kotlinx" } ?: return@forEach
                val key = "$model-$direction"
                out["$key-overlay-timeseries"] = SvgCharts.timeSeriesOverlay(
                    title = "$model/$direction: Jackson vs kotlinx на одном графике — временной ряд",
                    yLabel = "ns/операция",
                    a = SvgCharts.Series("Jackson 3", SvgCharts.COLOR_JACKSON, a.stats.timeSeriesNs),
                    b = SvgCharts.Series("kotlinx.serialization", SvgCharts.COLOR_KOTLINX, b.stats.timeSeriesNs),
                )
                out["$key-overlay-cdf"] = SvgCharts.cdfOverlay(
                    title = "$model/$direction: Jackson vs kotlinx на одном графике — CDF",
                    nameA = "Jackson 3",
                    colorA = SvgCharts.COLOR_JACKSON,
                    pointsA = a.stats.cdf,
                    nameB = "kotlinx.serialization",
                    colorB = SvgCharts.COLOR_KOTLINX,
                    pointsB = b.stats.cdf,
                )
                out["$key-overlay-histogram"] = SvgCharts.histogramOverlay(
                    title = "$model/$direction: Jackson vs kotlinx на одном графике — гистограмма",
                    oh = StatisticsCalculator.overlayHistograms(a.stats.timeSeriesNs, b.stats.timeSeriesNs),
                    colorA = SvgCharts.COLOR_JACKSON,
                    colorB = SvgCharts.COLOR_KOTLINX,
                )
            }
        }
        out["compare-p50-bars"] = SvgCharts.percentileBars(
            title = "p50 (медиана): ns на операцию — Jackson 3 vs kotlinx.serialization (3 модели × 3 сценария)",
            groups = percentileGroups(results) { it.stats.p50Ns },
            colorA = SvgCharts.COLOR_JACKSON,
            colorB = SvgCharts.COLOR_KOTLINX,
        )
        out["compare-p99-bars"] = SvgCharts.percentileBars(
            title = "p99: ns на операцию — Jackson 3 vs kotlinx.serialization (3 модели × 3 сценария)",
            groups = percentileGroups(results) { it.stats.p99Ns },
            colorA = SvgCharts.COLOR_JACKSON,
            colorB = SvgCharts.COLOR_KOTLINX,
        )
        return out
    }

    /** Группы «модель·сценарий → (Jackson, kotlinx)» для столбчатых графиков перцентилей. */
    private fun percentileGroups(
        results: List<CaseResult>,
        value: (CaseResult) -> Double,
    ): List<SvgCharts.PercentileGroup> {
        val models = results.map { it.model }.distinct().sorted()
        return models.flatMap { model ->
            listOf("read", "write", "mixed").mapNotNull { direction ->
                val a = results.firstOrNull { it.model == model && it.direction == direction && it.library == "jackson" } ?: return@mapNotNull null
                val b = results.firstOrNull { it.model == model && it.direction == direction && it.library == "kotlinx" } ?: return@mapNotNull null
                SvgCharts.PercentileGroup("${model}·${direction}", value(a), value(b))
            }
        }
    }

    private fun writeChartFiles(charts: Map<String, Map<String, String>>, compareCharts: Map<String, String>) {
        charts.forEach { (name, svgs) ->
            svgs.forEach { (kind, svg) ->
                File(chartsDir, "$name-$kind.svg").writeText(svg)
            }
        }
        compareCharts.forEach { (name, svg) ->
            File(chartsDir, "$name.svg").writeText(svg)
        }
    }
}

// ---------------------------------------------------------------------------
// Сериализуемая модель отчёта (report.json)
// ---------------------------------------------------------------------------

@Serializable
data class ReportDoc(
    val title: String,
    val generatedAt: String,
    val environment: Map<String, String>,
    val config: ConfigDoc,
    val overhead: OverheadDoc,
    val cases: List<CaseDoc>,
    val comparisons: List<ComparisonDoc>,
)

@Serializable
data class ConfigDoc(
    val samples: Int,
    val warmupSamples: Int,
    val settleSamples: Int,
    val dropSamples: Int,
    val teardownSamples: Int,
    val batch: Int,
    val mixedPairs: Int,
)

@Serializable
data class OverheadDoc(val singleNsPerOp: Double, val concurrentNsPerOp: Double)

@Serializable
data class CaseDoc(
    val name: String,
    val model: String,
    val fieldCount: Int,
    val description: String,
    val scenario: String,
    val direction: String,
    val library: String,
    val opsPerSample: Int,
    val totalOps: Long,
    val warmup: PhaseDoc,
    val settle: PhaseDoc,
    val measure: PhaseDoc,
    val teardown: PhaseDoc,
    val stats: StatsDoc,
    val throughputOpsPerSec: Double,
    val netMedianNs: Double,
)

@Serializable
data class PhaseDoc(val label: String, val samples: Int, val opsPerSample: Int, val ops: Long, val durationMs: Double)

@Serializable
data class StatsDoc(
    val count: Int,
    val meanNs: Double,
    val stdevNs: Double,
    val minNs: Double,
    val maxNs: Double,
    val p50Ns: Double,
    val p75Ns: Double,
    val p90Ns: Double,
    val p95Ns: Double,
    val p99Ns: Double,
    val percentiles: List<PercentileDoc>,
    val histogram: List<HistogramBucketDoc>,
    val cdf: List<CdfDoc>,
    val timeSeriesNs: List<Double>,
)

@Serializable
data class PercentileDoc(val percentile: Double, val ns: Double)

@Serializable
data class HistogramBucketDoc(val fromNs: Double, val toNs: Double, val count: Int)

@Serializable
data class CdfDoc(val latencyNs: Double, val fraction: Double)

@Serializable
data class ComparisonDoc(
    val model: String,
    val direction: String,
    val scenario: String,
    val libraryA: String,
    val libraryB: String,
    val medianANs: Double,
    val medianBNs: Double,
    val meanANs: Double,
    val meanBNs: Double,
    val p99ANs: Double,
    val p99BNs: Double,
    val deltaMedianPct: Double,
    val deltaMeanPct: Double,
    val deltaP99Pct: Double,
    val fasterLibrary: String,
    val overheadNsPerOp: Double,
)