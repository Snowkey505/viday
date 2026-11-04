package com.vidayapi.benchmark.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Самодостаточная генерация SVG-графиков (без внешних библиотек/браузера):
 *   • timeseries — график измеряемой величины во времени (Требование 8a);
 *   • cdf        — распределение величины по перцентилям (Требование 8b);
 *   • histogram  — гистограмма распределения (Требование 8c).
 *
 * Качество отображения:
 *   • шкалы охватывают ПОЛНЫЙ диапазон данных (min…max) — ничего не обрезается;
 *   • заливка фона под ВСЕМИ подписями — текст никогда не наслаивается;
 *   • единая палитра для обоих методов: Jackson = синий, kotlinx = зелёный.
 */
object SvgCharts {

    private const val W = 960.0
    private const val H = 400.0
    private const val ML = 92.0
    private const val MR = 36.0
    private const val MT = 60.0
    private const val MB = 62.0
    private const val PW = W - ML - MR
    private const val PH = H - MT - MB

    /** Стабильная палитра методов — одинаковая на всех графиках отчёта. */
    const val COLOR_JACKSON = "#1f77b4"
    const val COLOR_KOTLINX = "#2ca02c"
    private const val COLOR_ACCENT = "#ff7f0e"
    private const val COLOR_GRID = "#e5e5e5"
    private const val COLOR_TEXT = "#444444"

    // ------------------------------------------------------------------ timeseries

    /**
     * (Требование 8a) Латентность ns/операция по номеру сэмпла.
     * [refs] — опорные линии (например min/median/max).
     * Шкала Y охватывает ПОЛНЫЙ диапазон min…max — данные не обрезаются.
     */
    fun timeSeries(
        title: String,
        yLabel: String,
        ys: DoubleArray,
        refs: List<Pair<String, Double>>,
        color: String = COLOR_JACKSON,
    ): String {
        if (ys.isEmpty()) return emptySvg(title)
        val sorted = ys.sortedArray()
        val dataMin = sorted.first()
        val dataMax = sorted.last()
        val pad = ((dataMax - dataMin) * 0.06).coerceAtLeast(1.0)
        val yMin = (dataMin - pad).coerceAtLeast(0.0)
        val yMax = dataMax + pad
        val yTicks = ticks(yMin, yMax, 6)

        val sb = StringBuilder()
        open(title, sb)
        gridH(sb, yTicks, yMin, yMax) { v -> fmt(v) }

        val range = (yMax - yMin).coerceAtLeast(1e-9)
        val path = buildString {
            val n = ys.size
            for (i in 0 until n) {
                val x = ML + (if (n <= 1) 0.0 else i.toDouble() / (n - 1) * PW)
                val y = MT + PH - ((ys[i] - yMin) / range) * PH
                append(if (i == 0) "M" else "L").append(f1(x)).append(' ').append(f1(y))
            }
        }
        stroke(sb, path, color, 1.3, 0.85)

        labelRefs(sb, refs, yMin, yMax)

        axes(sb, xTicks(ys.size), yTicks, yMin, yMax, "номер сэмпла", yLabel)
        close(sb)
        return sb.toString()
    }

    /** Опорные линии с подписями, которые не пересекаются между собой. */
    private fun labelRefs(
        sb: StringBuilder,
        refs: List<Pair<String, Double>>,
        yMin: Double,
        yMax: Double,
    ) {
        if (refs.isEmpty()) return
        val range = (yMax - yMin).coerceAtLeast(1e-9)
        val used = mutableListOf<Double>()

        refs.sortedBy { it.second }.forEach { (label, v) ->
            val lineY = if (v > yMax) MT + 4.0 else MT + PH - ((v - yMin) / range) * PH
            val clampedY = lineY.coerceIn(MT + 2.0, MT + PH - 2.0)
            dash(sb, ML, clampedY, ML + PW, clampedY, "#a0a0a0", 0.9, "4 3")

            // Подбираем позицию подписи: сначала над линией, при коллизии — ниже.
            var labelY = clampedY - 7.0
            var below = false
            var step = 0
            while (step < 8 && used.any { abs(it - labelY) < 16.0 }) {
                below = !below
                step++
                labelY = if (below) clampedY + 7.0 + step * 14.0 else clampedY - 7.0 - step * 14.0
            }
            labelY = labelY.coerceIn(MT - 8.0, MT + PH + 12.0)
            used.add(labelY)
            textWithBg(sb, ML + PW, labelY, "$label ${fmt(v)} ns", "end", "#666666", 11)
        }
    }

    // ------------------------------------------------------------------ cdf

    /**
     * (Требование 8b) CDF: доля запросов (вертикальная ось 0..1), уложившихся
     * в латентность (горизонтальная ось). [marks] — перцентили 0.5/0.75/0.9/0.95/0.99.
     */
    fun cdf(
        title: String,
        points: List<Statistics.CdfPoint>,
        marks: List<Statistics.PercentilePoint>,
        color: String = COLOR_JACKSON,
    ): String {
        if (points.isEmpty()) return emptySvg(title)
        val xMin = points.first().latencyNs
        val xMax = points.last().latencyNs
        val xPad = if (xMax > xMin) (xMax - xMin) * 0.04 else 1.0
        val from = xMin - xPad
        val to = xMax + xPad
        val xTicks = ticks(from, to, 8)
        val yTicks = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

        val sb = StringBuilder()
        open(title, sb)
        gridH(sb, yTicks, 0.0, 1.0) { v -> "%.0f%%".format(Locale.US, v * 100) }

        val path = buildString {
            points.forEachIndexed { i, p ->
                val x = ML + ((p.latencyNs - from) / (to - from)) * PW
                val y = MT + PH - p.fraction * PH
                append(if (i == 0) "M" else "L").append(f1(x)).append(' ').append(f1(y))
            }
        }
        stroke(sb, path, color, 2.0, 1.0)

        // Подписи перцентилей в два ряда, чтобы не накладывались друг на друга.
        var lastX = Double.NEGATIVE_INFINITY
        marks.forEach { m ->
            val x = ML + ((m.ns - from) / (to - from)) * PW
            dash(sb, x, MT, x, MT + PH, "#888888", 0.8, "3 3")
            val row = if (abs(x - lastX) < 90.0) 1 else 0
            lastX = x
            textWithBg(sb, x, MT + 16 + row * 22, "p${fmtPct(m.percentile)} ${fmt(m.ns)} ns", "middle", "#555555", 10)
        }

        axes(sb, xTicks, yTicks, from, to, "латентность, ns/операция", "доля запросов")
        close(sb)
        return sb.toString()
    }

    // ------------------------------------------------------------------ histogram

    /**
     * (Требование 8c) Гистограмма распределения латентностей.
     * Бины покрывают ПОЛНЫЙ диапазон данных min…max — ничего не обрезается.
     */
    fun histogram(title: String, hist: Histogram, color: String = COLOR_JACKSON): String {
        if (hist.buckets.isEmpty()) return emptySvg(title)
        val maxCount = hist.buckets.maxOf { it.count }.coerceAtLeast(1)
        val xMin = hist.buckets.first().fromNs
        val xMax = hist.buckets.last().toNs
        val yTicks = ticks(0.0, maxCount.toDouble(), 5)

        val sb = StringBuilder()
        open(title, sb)
        gridH(sb, yTicks, 0.0, maxCount.toDouble()) { v -> v.toLong().toString() }

        hist.buckets.forEachIndexed { i, b ->
            val fromPx = ML + ((b.fromNs - xMin) / (xMax - xMin).coerceAtLeast(1e-9)) * PW
            val toPx = if (i == hist.buckets.lastIndex) ML + PW - 1.0 else ML + ((b.toNs - xMin) / (xMax - xMin).coerceAtLeast(1e-9)) * PW
            val barW = (toPx - fromPx).coerceAtLeast(1.5)
            val h = if (maxCount > 0) b.count.toDouble() / maxCount * PH else 0.0
            val y = MT + PH - h
            rect(sb, fromPx, y, barW, h, color, 0.8)
        }

        axes(sb, ticks(xMin, xMax, 8), yTicks, xMin, xMax, "латентность, ns/операция", "число сэмплов")

        close(sb)
        return sb.toString()
    }

    // ------------------------------------------------------------------ overlay (оба метода на одном графике)

    /** Одна серия для оверлея: название, цвет и значения. */
    data class Series(val name: String, val color: String, val ys: DoubleArray)

    /** Группа столбцов для графика перцентилей: сценарий, значения двух методов. */
    data class PercentileGroup(val label: String, val aNs: Double, val bNs: Double)

    /** Временные ряды двух методов на ОДНОМ графике (общая шкала, полный диапазон). */
    fun timeSeriesOverlay(
        title: String,
        yLabel: String,
        a: Series,
        b: Series,
    ): String {
        if (a.ys.isEmpty() && b.ys.isEmpty()) return emptySvg(title)
        val all = a.ys + b.ys
        val dataMin = all.minOrNull() ?: 0.0
        val dataMax = all.maxOrNull() ?: 1.0
        val pad = ((dataMax - dataMin) * 0.06).coerceAtLeast(1.0)
        val yMin = (dataMin - pad).coerceAtLeast(0.0)
        val yMax = dataMax + pad
        val yTicks = ticks(yMin, yMax, 6)

        val sb = StringBuilder()
        open(title, sb)
        gridH(sb, yTicks, yMin, yMax) { v -> fmt(v) }

        seriesPath(sb, a.ys, yMin, yMax, a.color, 1.3, 0.85)
        seriesPath(sb, b.ys, yMin, yMax, b.color, 1.3, 0.85, "6 3")

        legend(sb, listOf(Triple(a.name, a.color, false), Triple(b.name, b.color, true)))

        axes(sb, xTicks(maxOf(a.ys.size, b.ys.size)), yTicks, yMin, yMax, "номер сэмпла", yLabel)
        close(sb)
        return sb.toString()
    }

    /** CDF двух методов на ОДНОМ графике (общая шкала X). */
    fun cdfOverlay(
        title: String,
        nameA: String,
        colorA: String,
        pointsA: List<Statistics.CdfPoint>,
        nameB: String,
        colorB: String,
        pointsB: List<Statistics.CdfPoint>,
    ): String {
        if (pointsA.isEmpty() && pointsB.isEmpty()) return emptySvg(title)
        val all = pointsA + pointsB
        val xMin = all.minOf { it.latencyNs }
        val xMax = all.maxOf { it.latencyNs }
        val xPad = if (xMax > xMin) (xMax - xMin) * 0.04 else 1.0
        val from = xMin - xPad
        val to = xMax + xPad
        val xTicks = ticks(from, to, 8)
        val yTicks = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

        val sb = StringBuilder()
        open(title, sb)
        gridH(sb, yTicks, 0.0, 1.0) { v -> "%.0f%%".format(Locale.US, v * 100) }

        cdfPath(sb, pointsA, from, to, colorA, 2.0, 1.0, "")
        cdfPath(sb, pointsB, from, to, colorB, 2.0, 1.0, "6 3")

        legend(sb, listOf(Triple(nameA, colorA, false), Triple(nameB, colorB, true)))

        axes(sb, xTicks, yTicks, from, to, "латентность, ns/операция", "доля запросов")
        close(sb)
        return sb.toString()
    }

    /**
     * Гистограммы двух методов на ОДНОЙ оси: столбцы методов стоят рядом
     * внутри каждого общего бина (общие бины строятся в [StatisticsCalculator.overlayHistograms]).
     */
    fun histogramOverlay(
        title: String,
        oh: OverlayHistogram,
        colorA: String,
        colorB: String,
    ): String {
        if (oh.buckets.isEmpty()) return emptySvg(title)
        val maxCount = max(oh.countsA.maxOrNull() ?: 0, oh.countsB.maxOrNull() ?: 0).coerceAtLeast(1)
        val xMin = oh.buckets.first().fromNs
        val xMax = oh.buckets.last().toNs
        val yTicks = ticks(0.0, maxCount.toDouble(), 5)

        val sb = StringBuilder()
        open(title, sb)
        gridH(sb, yTicks, 0.0, maxCount.toDouble()) { v -> v.toLong().toString() }

        val span = (xMax - xMin).coerceAtLeast(1e-9)
        oh.buckets.forEachIndexed { i, bkt ->
            val fromPx = ML + (bkt.fromNs - xMin) / span * PW
            val toPx = if (i == oh.buckets.lastIndex) ML + PW - 1.0 else ML + (bkt.toNs - xMin) / span * PW
            val binW = toPx - fromPx
            val barW = (binW / 2 - 1.0).coerceAtLeast(1.0)
            val maxBar = (binW / 2).coerceAtLeast(1.5)
            val hA = oh.countsA[i].toDouble() / maxCount * PH
            val hB = oh.countsB[i].toDouble() / maxCount * PH
            if (hA > 0) rect(sb, fromPx + 1.0, MT + PH - hA, (barW).coerceAtMost(maxBar), hA, colorA, 0.8)
            if (hB > 0) rect(sb, fromPx + binW / 2 + 1.0, MT + PH - hB, (barW).coerceAtMost(maxBar), hB, colorB, 0.8)
        }

        legend(sb, listOf(Triple("Jackson 3", colorA, false), Triple("kotlinx.serialization", colorB, false)))

        axes(sb, ticks(xMin, xMax, 8), yTicks, xMin, xMax, "латентность, ns/операция", "число сэмплов")

        close(sb)
        return sb.toString()
    }

    /**
     * Столбчатый график перцентиля: три группы (read/write/mixed), в каждой
     * два столбца — Jackson и kotlinx. Сравнение «бок о бок» одним взглядом.
     */
    fun percentileBars(
        title: String,
        groups: List<PercentileGroup>,
        colorA: String,
        colorB: String,
    ): String {
        if (groups.isEmpty()) return emptySvg(title)
        val maxV = (groups.flatMap { listOf(it.aNs, it.bNs) }.maxOrNull() ?: 1.0) * 1.12
        val yTicks = ticks(0.0, maxV, 5)

        val sb = StringBuilder()
        open(title, sb)
        gridH(sb, yTicks, 0.0, maxV) { v -> fmt(v) }

        val groupW = PW / groups.size
        val barW = (groupW * 0.26).coerceAtLeast(6.0)
        groups.forEachIndexed { gi, g ->
            val cx = ML + groupW * gi + groupW / 2
            val hA = g.aNs / maxV * PH
            val hB = g.bNs / maxV * PH
            val xA = cx - barW - 2.0
            val xB = cx + 2.0
            rect(sb, xA, MT + PH - hA, barW, hA, colorA, 0.9)
            rect(sb, xB, MT + PH - hB, barW, hB, colorB, 0.9)
            if (hA > 0) textWithBg(sb, xA + barW / 2, MT + PH - hA - 5, fmt(g.aNs), "middle", "#1f4e79", 10)
            if (hB > 0) textWithBg(sb, xB + barW / 2, MT + PH - hB - 5, fmt(g.bNs), "middle", "#1a5e1a", 10)
            textWithBg(sb, cx, MT + PH + 19, g.label, "middle", "#444444", 12, "bold")
        }

        legend(sb, listOf(Triple("Jackson 3", colorA, false), Triple("kotlinx.serialization", colorB, false)))

        // Категорийная ось X: рамка + подписи групп + подписи осей.
        rect(sb, ML, MT, PW, PH, "none", 1.0, "#999999")
        textWithBg(sb, ML + PW / 2, H - 10, "сценарий (read / write / mixed)", "middle", "#333333", 13)
        sb.append(
            """<text x="18" y="${MT + PH / 2}" transform="rotate(-90 18 ${MT + PH / 2})" text-anchor="middle" font-size="13" fill="#333333">латентность, ns/операция</text>"""
        )
        close(sb)
        return sb.toString()
    }

    private fun seriesPath(
        sb: StringBuilder,
        ys: DoubleArray,
        yMin: Double,
        yMax: Double,
        color: String,
        width: Double,
        opacity: Double,
        dash: String = "",
    ) {
        val range = (yMax - yMin).coerceAtLeast(1e-9)
        val path = buildString {
            val n = ys.size
            for (i in 0 until n) {
                val x = ML + (if (n <= 1) 0.0 else i.toDouble() / (n - 1) * PW)
                val y = (MT + PH - ((ys[i] - yMin) / range) * PH).coerceIn(MT.toDouble(), MT + PH)
                append(if (i == 0) "M" else "L").append(f1(x)).append(' ').append(f1(y))
            }
        }
        val da = if (dash.isBlank()) "" else """ stroke-dasharray="$dash""""
        sb.append(
            """<path d="$path" fill="none" stroke="$color" stroke-width="$width" stroke-opacity="$opacity"$da stroke-linejoin="round"/>"""
        )
    }

    private fun cdfPath(
        sb: StringBuilder,
        points: List<Statistics.CdfPoint>,
        from: Double,
        to: Double,
        color: String,
        width: Double,
        opacity: Double,
        dash: String,
    ) {
        val span = (to - from).coerceAtLeast(1e-9)
        val path = buildString {
            points.forEachIndexed { i, p ->
                val x = ML + ((p.latencyNs - from) / span) * PW
                val y = MT + PH - p.fraction * PH
                append(if (i == 0) "M" else "L").append(f1(x)).append(' ').append(f1(y))
            }
        }
        val da = if (dash.isBlank()) "" else """ stroke-dasharray="$dash""""
        sb.append(
            """<path d="$path" fill="none" stroke="$color" stroke-width="$width" stroke-opacity="$opacity"$da stroke-linejoin="round"/>"""
        )
    }

    /** Легенда в верхней части области графика. */
    private fun legend(sb: StringBuilder, entries: List<Triple<String, String, Boolean>>) {
        var x = ML + 10.0
        val y = MT - 12.0
        entries.forEach { (name, color, dashed) ->
            val d = if (dashed) """ stroke-dasharray="6 3"""" else ""
            val w = name.length * 0.62 * 12 + 42
            sb.append("""<line x1="${f1(x)}" y1="${f1(y + 6)}" x2="${f1(x + 26)}" y2="${f1(y + 6)}" stroke="$color" stroke-width="2"$d/>""")
            text(sb, x + 32, y + 10, name, "start", "#333333", 12)
            x += 34 + w
        }
    }

    // ------------------------------------------------------------------ внутренние помощники

    private fun emptySvg(title: String): String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="$W" height="$H" viewBox="0 0 $W $H" font-family="DejaVu Sans, Arial, sans-serif">""" +
            """<rect x="0" y="0" width="$W" height="$H" fill="#ffffff"/>""" +
            """<text x="${W / 2.0}" y="30" text-anchor="middle" font-size="15" fill="#222222" font-weight="bold">${esc(title)}</text>""" +
            """<text x="${W / 2.0}" y="${H / 2.0}" text-anchor="middle" font-size="13" fill="#888888">нет данных</text></svg>"""

    private fun open(title: String, sb: StringBuilder) {
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="$W" height="$H" viewBox="0 0 $W $H" font-family="DejaVu Sans, Arial, sans-serif">"""
        )
            .append("<rect x=\"0\" y=\"0\" width=\"$W\" height=\"$H\" fill=\"#ffffff\"/>")
        text(sb, W / 2.0, 26.0, title, "middle", "#222222", 15, "bold")
    }

    private fun close(sb: StringBuilder) {
        sb.append("</svg>")
    }

    private fun gridH(sb: StringBuilder, ticks: List<Double>, min: Double, max: Double, fmt: (Double) -> String) {
        ticks.forEach { v ->
            val y = MT + PH - ((v - min) / (max - min).coerceAtLeast(1e-9)) * PH
            if (y > MT && y < MT + PH) {
                dash(sb, ML, y, ML + PW, y, COLOR_GRID, 1.0, "")
                textWithBg(sb, ML - 8, y + 4, fmt(v), "end", COLOR_TEXT, 11)
            }
        }
    }

    private fun axes(
        sb: StringBuilder,
        xTicks: List<Double>,
        yTicks: List<Double>,
        xMin: Double,
        xMax: Double,
        xLabel: String,
        yLabel: String,
    ) {
        rect(sb, ML, MT, PW, PH, "none", 1.0, "#999999")

        xTicks.forEach { v ->
            val x = ML + ((v - xMin) / (xMax - xMin).coerceAtLeast(1e-9)) * PW
            if (x > ML + 6.0 && x < ML + PW - 6.0) {
                textWithBg(sb, x, MT + PH + 18, fmt(v), "middle", COLOR_TEXT, 11)
            }
        }
        textWithBg(sb, ML + PW / 2, H - 10, xLabel, "middle", "#333333", 13)

        sb.append(
            """<text x="18" y="${MT + PH / 2}" transform="rotate(-90 18 ${MT + PH / 2})" text-anchor="middle" font-size="13" fill="#333333">$yLabel</text>"""
        )
    }

    private fun xTicks(n: Int): List<Double> {
        if (n <= 1) return listOf(0.0)
        val step = (n / 4).coerceAtLeast(1)
        return (0..n step step).map { it.toDouble() }
    }

    /** Подпись с белой подложкой — не наслаивается на линии и соседние подписи. */
    private fun textWithBg(
        sb: StringBuilder,
        x: Double,
        y: Double,
        label: String,
        anchor: String,
        fill: String,
        size: Int,
        weight: String = "normal",
    ) {
        val w = label.length * size * 0.62 + 8.0
        val h = size + 6.0
        val rx = when (anchor) {
            "end" -> x - w
            "middle" -> x - w / 2
            else -> x
        }
        sb.append(
            """<rect x="${f1(rx)}" y="${f1(y - size - 1.0)}" width="${f1(w)}" height="${f1(h)}" fill="#ffffff" fill-opacity="0.92" stroke="#e8e8e8" stroke-width="0.5"/>"""
        )
        text(sb, x, y, label, anchor, fill, size, weight)
    }

    private fun stroke(sb: StringBuilder, path: String, color: String, width: Double, opacity: Double) {
        sb.append(
            """<path d="$path" fill="none" stroke="$color" stroke-width="$width" stroke-opacity="$opacity" stroke-linejoin="round"/>"""
        )
    }

    private fun dash(sb: StringBuilder, x1: Double, y1: Double, x2: Double, y2: Double, color: String, width: Double, dashArray: String) {
        val da = if (dashArray.isBlank()) "" else """ stroke-dasharray="$dashArray""""
        sb.append("""<line x1="${f1(x1)}" y1="${f1(y1)}" x2="${f1(x2)}" y2="${f1(y2)}" stroke="$color" stroke-width="$width"$da/>""")
    }

    private fun rect(sb: StringBuilder, x: Double, y: Double, w: Double, h: Double, fill: String, opacity: Double, stroke: String = "none") {
        val f = if (fill == "none") """fill="none"""" else """fill="$fill" fill-opacity="$opacity""""
        sb.append("""<rect x="${f1(x)}" y="${f1(y)}" width="${f1(w)}" height="${f1(h)}" $f stroke="$stroke"/>""")
    }

    private fun text(
        sb: StringBuilder,
        x: Double,
        y: Double,
        label: String,
        anchor: String,
        fill: String,
        size: Int,
        weight: String = "normal",
    ) {
        sb.append(
            """<text x="${f1(x)}" y="${f1(y)}" text-anchor="$anchor" font-size="$size" fill="$fill" font-weight="$weight">${esc(label)}</text>"""
        )
    }

    /** Перцентиль отсортированного массива (nearest-rank, как в Statistics). */
    private fun pct(sorted: DoubleArray, p: Double): Double {
        val idx = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[idx]
    }

    private fun ticks(min: Double, max: Double, target: Int): List<Double> {
        val span = max - min
        if (span <= 0) return listOf(min)
        val raw = span / target
        val mag = 10.0.pow(floor(log10(raw)))
        val norm = raw / mag
        val step = when {
            norm < 1.5 -> 1.0
            norm < 3.5 -> 2.0
            norm < 7.5 -> 5.0
            else -> 10.0
        } * mag
        val first = ceil(min / step) * step
        return generateSequence(first) { it + step }
            .takeWhile { it <= max + step * 1e-9 }
            .toList()
    }

    private fun fmt(v: Double): String =
        when {
            v == 0.0 -> "0"
            abs(v) >= 1000 -> "%.0f".format(Locale.US, v)
            abs(v) >= 10 -> "%.1f".format(Locale.US, v)
            else -> "%.2f".format(Locale.US, v)
        }

    private fun fmtPct(p: Double): String = "%.2f".format(Locale.US, p)
    private fun f1(v: Double): String = "%.1f".format(Locale.US, v)

    private fun esc(s: String): String =
        s.replace("&", "\u0026amp;")
            .replace("<", "\u003c")
            .replace(">", "\u003e")
            .replace("\"", "\u0026quot;")
}