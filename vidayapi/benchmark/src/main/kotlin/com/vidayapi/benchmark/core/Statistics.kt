package com.vidayapi.benchmark.core

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Гистограмма распределения (Требование 8c).
 */
data class Histogram(
    val buckets: List<Bucket>,
    /** true — последний бин «открытый»: в нём собраны все значения ≥ верхней границы (хвост). */
    val clippedHigh: Boolean = false,
) {
    data class Bucket(val fromNs: Double, val toNs: Double, val count: Int)
    val total: Int get() = buckets.sumOf { it.count }
}

/**
 * Гистограммы ДВУХ методов на ОБЩИХ бинах — для наложения на один график.
 * Бины строятся по объединённым данным (робастный диапазон по IQR),
 * поэтому столбцы методов стоят вплотную и сравнение корректно.
 */
data class OverlayHistogram(
    val buckets: List<Histogram.Bucket>,
    val countsA: IntArray,
    val countsB: IntArray,
    val clippedHigh: Boolean = false,
)

/**
 * Полная статистика фазы measurement.
 *
 * Все значения времени — наносекунды на ОДНУ операцию (время батча делится
 * на число операций в батче: это стандартный приём микробенчмаркинга, который
 * амортизирует стоимость System.nanoTime(), см. overhead в отчёте ЛР3).
 */
data class Statistics(
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
    /** Таблица «перцентиль → латентность» (Требование 8b/8d). */
    val percentiles: List<PercentilePoint>,
    val histogram: Histogram,
    /** CDF: доля запросов, уложившихся в латентность (Требование 8b). */
    val cdf: List<CdfPoint>,
    /** График величины во времени (Требование 8a). */
    val timeSeriesNs: DoubleArray,
) {
    data class PercentilePoint(val percentile: Double, val ns: Double)
    data class CdfPoint(val latencyNs: Double, val fraction: Double)

    val throughputOpsPerSec: Double get() = 1e9 / meanNs
}

object StatisticsCalculator {
    /** Обязательные перцентили из Требования 8d. */
    val REQUIRED_PERCENTILES = listOf(0.50, 0.75, 0.90, 0.95, 0.99)

    private val REPORT_PERCENTILES =
        (listOf(0.10, 0.25) + REQUIRED_PERCENTILES + listOf(0.999)).distinct().sorted()

    fun compute(sampleTimesNs: DoubleArray, opsPerSample: Int): Statistics {
        val perOp = sampleTimesNs.map { it / opsPerSample }.toDoubleArray()
        val sorted = perOp.sortedArray()

        fun percentile(p: Double): Double {
            if (sorted.isEmpty()) return 0.0
            // nearest-rank: индекс ceil(p*N) − 1
            val idx = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[idx]
        }

        val mean = perOp.average()
        val variance = perOp.sumOf { val d = it - mean; d * d } / (perOp.size - 1).coerceAtLeast(1)
        val stdev = sqrt(variance)
        val min = perOp.min()
        val max = perOp.max()

        val percentiles: List<Statistics.PercentilePoint> =
            REPORT_PERCENTILES.map { Statistics.PercentilePoint(it, percentile(it)) }

        return Statistics(
            count = perOp.size,
            meanNs = mean,
            stdevNs = stdev,
            minNs = min,
            maxNs = max,
            p50Ns = percentile(0.50),
            p75Ns = percentile(0.75),
            p90Ns = percentile(0.90),
            p95Ns = percentile(0.95),
            p99Ns = percentile(0.99),
            percentiles = percentiles,
            histogram = buildHistogram(sorted),
            cdf = buildCdf(sorted),
            timeSeriesNs = perOp,
        )
    }

    /**
     * Гистограмма по ПОЛНОМУ диапазону данных min…max — выбросы не обрезаются
     * и не «склеиваются» (Требование 8c). Число бинов 20.
     */
    private fun buildHistogram(sorted: DoubleArray): Histogram {
        val n = sorted.size
        if (n == 0) return Histogram(emptyList())
        val lo = sorted.first()
        val hi = sorted.last()
        if (hi <= lo) return Histogram(listOf(Histogram.Bucket(lo, hi, n)))

        val bins = 20
        val width = (hi - lo) / bins
        val counts = IntArray(bins)
        for (v in sorted) {
            val idx = ((v - lo) / width).toInt().coerceIn(0, bins - 1)
            counts[idx]++
        }
        return Histogram(
            counts.mapIndexed { i, c -> Histogram.Bucket(lo + i * width, lo + (i + 1) * width, c) },
        )
    }

    /**
     * Общие бины для наложения двух гистограмм: полный диапазон объединённых
     * данных min…max, каждая серия разносится по бинам независимо (выбросы
     * не обрезаются).
     */
    fun overlayHistograms(a: DoubleArray, b: DoubleArray, bins: Int = 20): OverlayHistogram {
        if (a.isEmpty() || b.isEmpty()) return OverlayHistogram(emptyList(), IntArray(0), IntArray(0))
        val sa = a.sortedArray()
        val sb = b.sortedArray()
        val lo = minOf(sa.first(), sb.first())
        val hi = maxOf(sa.last(), sb.last())
        if (hi <= lo) {
            return OverlayHistogram(
                listOf(Histogram.Bucket(lo, hi, 0)),
                intArrayOf(sa.size),
                intArrayOf(sb.size),
            )
        }
        val width = (hi - lo) / bins

        fun count(sorted: DoubleArray): IntArray {
            val c = IntArray(bins)
            for (v in sorted) {
                val idx = ((v - lo) / width).toInt().coerceIn(0, bins - 1)
                c[idx]++
            }
            return c
        }

        val buckets = (0 until bins).map { i ->
            Histogram.Bucket(lo + i * width, lo + (i + 1) * width, 0)
        }
        return OverlayHistogram(buckets, count(sa), count(sb))
    }

    private fun buildCdf(sorted: DoubleArray): List<Statistics.CdfPoint> {
        val n = sorted.size
        if (n == 0) return emptyList()
        // Децимируем до ~200 точек, чтобы SVG-график не разрастался.
        val step = ceil(n / 200.0).toInt().coerceAtLeast(1)
        val pts = mutableListOf<Statistics.CdfPoint>()
        var i = 0
        while (i < n) {
            pts.add(Statistics.CdfPoint(sorted[i], (i + 1).toDouble() / n))
            i += step
        }
        if (pts.last().latencyNs != sorted.last()) {
            pts.add(Statistics.CdfPoint(sorted.last(), 1.0))
        }
        return pts
    }
}