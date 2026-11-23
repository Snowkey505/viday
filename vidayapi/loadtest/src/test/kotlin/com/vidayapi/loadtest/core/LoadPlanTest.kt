package com.vidayapi.loadtest.core

import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoadPlanTest {
    @Test
    fun `ramps and respects coroutine limit`() {
        val plan = LoadPlan.ramp(Duration.ofSeconds(10), Duration.ofSeconds(20), 100)
        assertEquals(PlanState("ramp", 0), plan.stateAt(0, 50))
        assertEquals(PlanState("ramp", 10), plan.stateAt(10, 50))
        assertEquals(50, plan.stateAt(60, 50).vus)
        assertTrue(plan.stateAt(70, 50).done)
    }

    @Test
    fun `stress switches phases`() {
        val plan = LoadPlan.stress(
            10,
            100,
            Duration.ofSeconds(30),
            Duration.ofSeconds(40),
            Duration.ofSeconds(50),
        )
        assertEquals("baseline", plan.stateAt(0, 100).phase)
        assertEquals("overload", plan.stateAt(30, 100).phase)
        assertEquals("recovery", plan.stateAt(70, 100).phase)
        assertTrue(plan.stateAt(120, 100).done)
    }
}
