package com.vidayapi.loadtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LoadConfigTest {
    @Test
    fun `uses strict failure criterion by default`() {
        val config = LoadConfig.fromEnv(emptyMap())
        assertEquals("auth", config.workload)
        assertEquals(0.95, config.failureThreshold)
        assertEquals(5, config.failureWindow.seconds)
        assertEquals(3, config.failureWindows)
        assertEquals(20, config.failureMinRequests)
        assertEquals(30, config.earlyStopAfter.seconds)
    }

    @Test
    fun `public workload does not perform injected authentication errors`() {
        val config = LoadConfig.fromEnv(mapOf("WORKLOAD" to "public"))
        assertEquals("public", config.workload)
        assertThrows(IllegalArgumentException::class.java) {
            LoadConfig.fromEnv(mapOf("WORKLOAD" to "public", "ERROR_RATE" to "0.1"))
        }
    }
}
