package com.vidayapi.benchmark.core

/**
 * Тип сценария (Требование 5 ЛР3): чтение / запись / одновременные чтение и запись.
 */
enum class Scenario(val label: String) {
    READ("чтение (deserialize)"),
    WRITE("запись (serialize)"),
    MIXED("одновременные чтение + запись (2 потока)"),
}

/**
 * Общий «паспорт» кейса — позволяет единообразно фильтровать/именовать
 * и простые, и смешанные сценарии (см. JsonCases.all()).
 */
interface NamedBenchmarkCase {
    val name: String
    val description: String
    val scenario: Scenario
    val direction: String
    val library: String
    /** Сравниваемая модель полезной нагрузки: x1 / x4 / x8. */
    val model: String
    /** Число полей модели (x1=10, x4=40, x8=80). */
    val fieldCount: Int
}

/**
 * Простой микро-сценарий: ровно одна измеряемая операция за «тик».
 * Используется для сценариев чтения и записи.
 * Общий «паспорт» (name/description/scenario/direction/library) — в
 * [NamedBenchmarkCase].
 */
interface BenchmarkCase : NamedBenchmarkCase {
    fun setUp() {}

    fun tearDown() {}

    /** Ровно одна операция; результат отдаётся в Blackhole. */
    fun operation(): Any?
}

/**
 * Смешанный сценарий: два потока работают «одновременно» —
 * один выполняет чтение, второй запись (на одном ядре это честное
 * разделение времени выполнения, см. отчёт ЛР3).
 */
interface ConcurrentBenchmarkCase : NamedBenchmarkCase {
    fun setUp() {}

    fun tearDown() {}

    fun readerOperation(): Any?
    fun writerOperation(): Any?
}

/**
 * Результат одной фазы прогона (warmup / measurement / teardown):
 * сколько сэмплов выполнено, сколько операций внутри, сколько времени заняло.
 * Позволяет отчитаться по требованию 8e/8f (время теста, число операций
 * с детализацией по фазам). Времена отдельных сэмплов сохраняются только
 * у фазы measurement (для графиков и статистики).
 */
data class PhaseRecord(
    val label: String,
    val samples: Int,
    val opsPerSample: Int,
    val durationNs: Long,
    /** Время каждого сэмпла (нс) — заполнено только для measurement. */
    val sampleTimesNs: DoubleArray = DoubleArray(0),
) {
    val totalOps: Long get() = samples.toLong() * opsPerSample
    val durationMs: Double get() = durationNs / 1e6
}

/**
 * Итог прогона одного кейса: три фазы + статистика фазы measurement.
 */
data class CaseResult(
    val name: String,
    val description: String,
    val scenario: Scenario,
    val direction: String,
    val library: String,
    val model: String,
    val fieldCount: Int,
    val opsPerSample: Int,
    val warmup: PhaseRecord,
    /** Стабилизация между warmup и measure: выполняется, но результаты ОТБРАСЫВАЮТСЯ. */
    val settle: PhaseRecord,
    val measure: PhaseRecord,
    val teardown: PhaseRecord,
    val stats: Statistics,
) {
    val totalOps: Long get() = warmup.totalOps + settle.totalOps + measure.totalOps + teardown.totalOps
}