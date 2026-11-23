package com.vidayapi.loadtest.core

import java.time.Instant

class FailureDetector(
    private val enabled: Boolean,
    private val minRunSeconds: Long,
    private val windowSeconds: Long,
    private val failureThreshold: Double,
    private val consecutiveWindows: Int,
    private val minRequestsPerWindow: Int,
) {
    private data class Window(val startSecond: Long, var requests: Int = 0, var failures: Int = 0)

    private val windows = mutableMapOf<Long, Window>()

    @Synchronized
    fun record(failed: Boolean, at: Instant = Instant.now()) {
        if (!enabled) return
        val second = at.epochSecond / windowSeconds * windowSeconds
        val window = windows.getOrPut(second) { Window(second) }
        window.requests++
        if (failed) window.failures++
        windows.keys.removeAll { it < second - (consecutiveWindows + 1L) * windowSeconds }
    }

    @Synchronized
    fun failureReason(elapsedSeconds: Long, at: Instant = Instant.now()): String? {
        if (!enabled || elapsedSeconds < minRunSeconds) return null
        val currentSecond = at.epochSecond / windowSeconds * windowSeconds
        val complete = windows.values
            .filter { it.startSecond < currentSecond }
            .sortedBy { it.startSecond }
            .takeLast(consecutiveWindows)
        if (complete.size < consecutiveWindows) return null
        val contiguous = complete.zipWithNext().all { (left, right) ->
            right.startSecond == left.startSecond + windowSeconds
        }
        if (!contiguous) return null
        val failed = complete.all { window ->
            window.requests >= minRequestsPerWindow &&
                window.failures.toDouble() / window.requests >= failureThreshold
        }
        return if (failed) {
            val ratios = complete.joinToString(",") { "%.1f%%".format(it.failures * 100.0 / it.requests) }
            "unexpected failures >= ${failureThreshold * 100}% for $consecutiveWindows consecutive windows ($ratios)"
        } else {
            null
        }
    }
}
