package com.vidayapi.benchmark.core

/**
 * Конфигурация прогона. Значения по умолчанию удовлетворяют Требованию 1
 * (не менее 100 испытаний) и Требованию 2 (фазы warmup/teardown).
 * Порядок фаз: warmup → settle → measure → teardown.
 *   • warmup — прогрев (результаты отбрасываются);
 *   • settle — стабилизация после прогрева: ленивая инициализация, JIT-компиляция,
 *     GC-паузы «укладываются» ДО measurement (результаты отбрасываются);
 *   • dropSamples — дополнительно N ПЕРВЫХ сэмплов фазы measurement удаляются
 *     из статистики/графиков/raw CSV как «стартовый переходный процесс»
 *     (распространённый приём микробенчмаркинга; фиксируется в отчёте);
 *   • measure/teardown — измерение и «остывание».
 * Можно переопределить через CLI-флаги или переменные окружения BENCH_*.
 */
data class RunConfig(
    val samples: Int = 1000,
    val warmupSamples: Int = 5000,
    val settleSamples: Int = 200,
    val dropSamples: Int = 10,
    val teardownSamples: Int = 250,
    val batch: Int = 256,
    val mixedPairs: Int = 256,
    val out: String = "build/reports/benchmark",
    val case: String? = null,
    val withCharts: Boolean = true,
) {
    fun validate() {
        require(samples >= 100) {
            "samples=$samples < 100 — Требование 1 ЛР3: нужно не менее 100 испытаний"
        }
        require(warmupSamples >= 0) { "warmup не может быть отрицательным" }
        require(settleSamples >= 0) { "settle не может быть отрицательным" }
        require(dropSamples >= 0) { "drop не может быть отрицательным" }
        require(dropSamples < samples) { "dropSamples=$dropSamples должно быть меньше samples=$samples" }
        require(teardownSamples >= 0) { "teardown не может быть отрицательным" }
        require(batch >= 1) { "batch должен быть >= 1" }
        require(mixedPairs >= 1) { "mixed-pairs должен быть >= 1" }
    }

    companion object {
        private val USAGE = """
            Использование: benchmark [опции]
              --samples N       число испытаний (сэмплов) фазы measurement (по умолчанию 1000, минимум 100 — Требование 1)
              --warmup N        число сэмплов фазы warmup (по умолчанию 5000; чтобы JIT/инициализация «улеглись»)
              --settle N        число сэмплов фазы стабилизации между warmup и measure (по умолчанию 200; отбрасываются)
              --drop-samples N  удалить N ПЕРВЫХ сэмплов measurement из статистики/графиков (по умолчанию 10;
                                «стартовый переходный процесс» — справочно фиксируется в отчёте; 0 = не удалять)
              --teardown N      число сэмплов фазы teardown (по умолчанию 250)
              --batch N         операций в одном сэмпле (по умолчанию 256; overhead ~2*nanoTime/N)
              --mixed-pairs N   пар чтение-запись в сэмпле смешанного сценария (по умолчанию 256)
              --out DIR         каталог результатов (по умолчанию build/reports/benchmark)
              --case NAME       прогнать только один кейс (по умолчанию все)
              --no-charts       не генерировать SVG-графики
              --help            эта справка
            Переменные окружения: BENCH_SAMPLES, BENCH_WARMUP, BENCH_SETTLE,
            BENCH_DROP, BENCH_TEARDOWN, BENCH_BATCH, BENCH_MIXED_PAIRS, BENCH_OUT, BENCH_CASE.
        """.trimIndent()

        fun fromArgs(args: Array<String>): RunConfig {
            var samples = 1000
            var warmup = 5000
            var settle = 200
            var drop = 10
            var teardown = 250
            var batch = 256
            var mixed = 256
            var out = "build/reports/benchmark"
            var case: String? = null
            var charts = true

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "run", "--console" -> { /* токены-режимы, игнорируются */ }
                    "--samples" -> samples = args[++i].toInt()
                    "--warmup" -> warmup = args[++i].toInt()
                    "--settle" -> settle = args[++i].toInt()
                    "--drop-samples" -> drop = args[++i].toInt()
                    "--teardown" -> teardown = args[++i].toInt()
                    "--batch" -> batch = args[++i].toInt()
                    "--mixed-pairs" -> mixed = args[++i].toInt()
                    "--out" -> out = args[++i]
                    "--case" -> case = args[++i]
                    "--no-charts" -> charts = false
                    "--help", "-h" -> {
                        println(USAGE)
                        kotlin.system.exitProcess(0)
                    }
                    else -> if (args[i].startsWith("--")) {
                        throw IllegalArgumentException("неизвестный флаг: ${args[i]}\n$USAGE")
                    }
                }
                i++
            }

            // Переменные окружения имеют приоритет над значениями по умолчанию.
            samples = env("BENCH_SAMPLES") ?: samples
            warmup = env("BENCH_WARMUP") ?: warmup
            settle = env("BENCH_SETTLE") ?: settle
            drop = env("BENCH_DROP") ?: drop
            teardown = env("BENCH_TEARDOWN") ?: teardown
            batch = env("BENCH_BATCH") ?: batch
            mixed = env("BENCH_MIXED_PAIRS") ?: mixed
            out = System.getenv("BENCH_OUT") ?: out
            case = System.getenv("BENCH_CASE") ?: case

            return RunConfig(
                samples = samples,
                warmupSamples = warmup,
                settleSamples = settle,
                dropSamples = drop,
                teardownSamples = teardown,
                batch = batch,
                mixedPairs = mixed,
                out = out,
                case = case,
                withCharts = charts,
            )
        }

        private fun env(name: String): Int? = System.getenv(name)?.toIntOrNull()
    }
}

/**
 * Запускающий харнесс: выполняет фазы warmup → measurement → teardown
 * (Требование 2), измеряет overhead инструмента (Требование 7) и собирает
 * статистику (Требование 1).
 *
 * Измерение построено на «батчах»: за один сэмпл выполняется [RunConfig.batch]
 * операций подряд и замеряется суммарное время — это амортизирует стоимость
 * System.nanoTime() и делает overhead инструмента порядка 1/batch наносекунды
 * на операцию (см. measureOverheadSingle / measureOverheadConcurrent).
 */
class BenchmarkRunner(private val config: RunConfig) {

    private val blackhole = Blackhole()

    /**
     * «Тик прогрева» таймингового кода до первых замеров (JIT-компиляция
     * sample/consume и т.п.), результат отбрасывается.
     */
    fun warmupTick() {
        repeat(config.batch) { blackhole.consume(Unit) }
    }

    // ---- простые сценарии (чтение/запись) ----

    fun run(case: BenchmarkCase): CaseResult {
        case.setUp()
        try {
            val warmup = phase("warmup", config.warmupSamples, config.batch, case::operation)
            // settle: стабилизация после прогрева (JIT/инициализация/GC-паузы),
            // результаты ОТБРАСЫВАЮТСЯ — первые сэмплы не искажают статистику.
            val settle = phase("settle", config.settleSamples, config.batch, case::operation)
            val measureFull = phase("measure", config.samples, config.batch, case::operation)
            val measure = trimMeasure(measureFull)
            val teardown = phase("teardown", config.teardownSamples, config.batch, case::operation)
            val stats = StatisticsCalculator.compute(measure.sampleTimesNs, config.batch)
            return CaseResult(
                name = case.name,
                description = case.description,
                scenario = case.scenario,
                direction = case.direction,
                library = case.library,
                model = case.model,
                fieldCount = case.fieldCount,
                opsPerSample = config.batch,
                warmup = warmup,
                settle = settle,
                measure = measure,
                teardown = teardown,
                stats = stats,
            )
        } finally {
            case.tearDown()
        }
    }

    // ---- смешанный сценарий (одновременные чтение и запись) ----

    fun runConcurrent(case: ConcurrentBenchmarkCase): CaseResult {
        case.setUp()
        try {
            ConcurrentPair(case::readerOperation, case::writerOperation).use { pair ->
                val opsPerSample = 2 * config.mixedPairs
                val warmup = concurrentPhase("warmup", config.warmupSamples, pair, opsPerSample)
                val settle = concurrentPhase("settle", config.settleSamples, pair, opsPerSample)
                val measureFull = concurrentPhase("measure", config.samples, pair, opsPerSample)
                val measure = trimMeasure(measureFull)
                val teardown = concurrentPhase("teardown", config.teardownSamples, pair, opsPerSample)
                val stats = StatisticsCalculator.compute(measure.sampleTimesNs, opsPerSample)
                return CaseResult(
                    name = case.name,
                    description = case.description,
                    scenario = case.scenario,
                    direction = case.direction,
                    library = case.library,
                    model = case.model,
                    fieldCount = case.fieldCount,
                    opsPerSample = opsPerSample,
                    warmup = warmup,
                    settle = settle,
                    measure = measure,
                    teardown = teardown,
                    stats = stats,
                )
            }
        } finally {
            case.tearDown()
        }
    }

    // ---- фазы ----

    private fun phase(label: String, samples: Int, ops: Int, op: () -> Any?): PhaseRecord {
        val times = DoubleArray(samples)
        val t0 = System.nanoTime()
        var i = 0
        while (i < samples) {
            times[i] = sample(ops, op)
            i++
        }
        val duration = System.nanoTime() - t0
        return PhaseRecord(label, samples, ops, duration, times)
    }

    private fun concurrentPhase(
        label: String,
        samples: Int,
        pair: ConcurrentPair,
        opsPerSample: Int,
    ): PhaseRecord {
        val times = DoubleArray(samples)
        val t0 = System.nanoTime()
        var i = 0
        while (i < samples) {
            times[i] = pair.runBatch(config.mixedPairs, blackhole).toDouble()
            i++
        }
        val duration = System.nanoTime() - t0
        return PhaseRecord(label, samples, opsPerSample, duration, times)
    }

    /**
     * Удаляет N ПЕРВЫХ сэмплов фазы measurement (dropSamples) — «стартовый
     * переходный процесс» (safepoint/JIT-стык/GC после settle не влияют на
     * статистику, графики и raw CSV). Количество удалённых фиксируется в отчёте.
     */
    private fun trimMeasure(measure: PhaseRecord): PhaseRecord {
        val drop = config.dropSamples
        if (drop <= 0 || measure.sampleTimesNs.isEmpty()) return measure
        val kept = measure.sampleTimesNs.copyOfRange(drop, measure.sampleTimesNs.size)
        return PhaseRecord("measure", kept.size, measure.opsPerSample, measure.durationNs, kept)
    }

    /** Один сэмпл: время [ops] последовательных операций (в нс). */
    private fun sample(ops: Int, op: () -> Any?): Double {
        val t0 = System.nanoTime()
        var i = 0
        while (i < ops) {
            blackhole.consume(op())
            i++
        }
        return (System.nanoTime() - t0).toDouble()
    }

    // ---- overhead инструмента (Требование 7) ----

    /**
     * Стоимость инструментария на ОДНУ операцию для простого сценария:
     * (2 × System.nanoTime + цикл + Blackhole.consume + вызов no-op операции) / batch.
     * No-op операция возвращает Unit — та же сигнатура, что у реальных операций.
     *
     * Замеряется ПОСЛЕ прогрева (см. Main), и перед самим замером цикл
     * прогоняется ещё [WARMUP_BATCHES] раз — чтобы JIT не искажал цифру:
     * в прогретом состоянии стоимость инструмента ~2×nanoTime/batch ≈ 0.1–0.5 нс < 4–5 нс.
     */
    fun measureOverheadSingle(): Double {
        val op: () -> Any? = { Unit }
        var i = 0
        while (i < WARMUP_BATCHES) {
            sample(config.batch, op)
            i++
        }
        // Медиана по OVERHEAD_BATCHES батчам: устойчива к разовым паузам
        // (JIT-компиляция, GC) — это честная steady-state стоимость инструмента.
        val times = DoubleArray(OVERHEAD_BATCHES)
        i = 0
        while (i < OVERHEAD_BATCHES) {
            times[i] = sample(config.batch, op)
            i++
        }
        return times.sortedArray()[OVERHEAD_BATCHES / 2] / config.batch
    }

    /** То же для смешанного сценария (пара no-op операций в двух потоках). */
    fun measureOverheadConcurrent(): Double {
        ConcurrentPair({ Unit }, { Unit }).use { pair ->
            val warm = Blackhole()
            var i = 0
            while (i < WARMUP_BATCHES) {
                pair.runBatch(config.mixedPairs, warm)
                i++
            }
            val times = DoubleArray(OVERHEAD_BATCHES)
            i = 0
            while (i < OVERHEAD_BATCHES) {
                times[i] = pair.runBatch(config.mixedPairs, Blackhole()).toDouble()
                i++
            }
            return times.sortedArray()[OVERHEAD_BATCHES / 2] / (2.0 * config.mixedPairs)
        }
    }

    companion object {
        /** Прогрев перед замером overhead: 256 батчей no-op операций (достаточно для C2). */
        private const val WARMUP_BATCHES = 256
        /** Сколько батчей меряем для медианы overhead. */
        private const val OVERHEAD_BATCHES = 32
    }
}