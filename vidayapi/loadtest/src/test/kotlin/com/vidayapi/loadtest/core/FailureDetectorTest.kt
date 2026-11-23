package com.vidayapi.loadtest.core

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FailureDetectorTest {
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `stops only after consecutive failing windows`() {
        val detector = FailureDetector(true, 30, 10, 0.8, 3, 10)
        for (window in 0 until 3) {
            repeat(10) { detector.record(true, start.plusSeconds((window * 10).toLong())) }
        }
        assertNull(detector.failureReason(25, start.plusSeconds(25)))
        assertNotNull(detector.failureReason(35, start.plusSeconds(35)))
    }

    @Test
    fun `healthy window interrupts failure sequence`() {
        val detector = FailureDetector(true, 0, 10, 0.8, 2, 10)
        repeat(10) { detector.record(true, start) }
        repeat(10) { detector.record(false, start.plusSeconds(10)) }
        repeat(10) { detector.record(true, start.plusSeconds(20)) }
        assertNull(detector.failureReason(30, start.plusSeconds(30)))
    }

    @Test
    fun `missing window interrupts failure sequence`() {
        val detector = FailureDetector(true, 0, 5, 0.95, 3, 20)
        for (window in listOf(0, 1, 3)) {
            repeat(20) { detector.record(true, start.plusSeconds((window * 5).toLong())) }
        }
        assertNull(detector.failureReason(21, start.plusSeconds(21)))
    }

    @Test
    fun `late response at window boundary does not break sequence`() {
        val detector = FailureDetector(true, 0, 10, 0.8, 3, 10)
        for (window in 0 until 2) {
            repeat(10) { detector.record(true, start.plusSeconds((window * 10).toLong() + 5)) }
        }
        repeat(9) { detector.record(true, start.plusSeconds(25)) }
        detector.record(true, start.plusSeconds(30))
        detector.record(true, start.plusSeconds(29))
        assertNotNull(detector.failureReason(31, start.plusSeconds(31)))
    }
}
