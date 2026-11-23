package com.vidayapi.loadtest.core

import java.time.Duration

data class PlanState(val phase: String, val vus: Int, val done: Boolean = false)

data class PhaseSpec(
    val phase: String,
    val duration: Duration,
    val startVus: Int,
    val endVus: Int,
)

class LoadPlan(private val phases: List<PhaseSpec>) {
    private val totalSeconds = phases.sumOf { it.duration.toSeconds() }

    fun stateAt(elapsedSeconds: Long, coroutineLimit: Int): PlanState {
        if (elapsedSeconds >= totalSeconds) return PlanState(phases.lastOrNull()?.phase ?: "done", 0, true)
        var cursor = 0L
        for (spec in phases) {
            val duration = spec.duration.toSeconds().coerceAtLeast(1)
            if (elapsedSeconds < cursor + duration) {
                val progress = (elapsedSeconds - cursor).toDouble() / duration
                val target = spec.startVus + ((spec.endVus - spec.startVus) * progress).toInt()
                return PlanState(spec.phase, target.coerceIn(0, coroutineLimit))
            }
            cursor += duration
        }
        return PlanState(phases.last().phase, 0, true)
    }

    companion object {
        fun ramp(stepDuration: Duration, plateauDuration: Duration, maxVus: Int): LoadPlan {
            val targets = listOf(10, 25, 50, 75, 100, 150, 200, 275, 350, maxVus)
                .filter { it <= maxVus }
                .plus(maxVus)
                .distinct()
            var previous = 0
            val phases = targets.map { target ->
                PhaseSpec("ramp", stepDuration, previous, target).also { previous = target }
            } + PhaseSpec("ramp", plateauDuration, maxVus, maxVus)
            return LoadPlan(phases)
        }

        fun constant(phase: String, vus: Int, duration: Duration): LoadPlan =
            LoadPlan(listOf(PhaseSpec(phase, duration, vus, vus)))

        fun stress(
            baselineVus: Int,
            overloadVus: Int,
            baselineDuration: Duration,
            overloadDuration: Duration,
            recoveryDuration: Duration,
        ): LoadPlan = LoadPlan(
            listOf(
                PhaseSpec("baseline", baselineDuration, baselineVus, baselineVus),
                PhaseSpec("overload", overloadDuration, overloadVus, overloadVus),
                PhaseSpec("recovery", recoveryDuration, baselineVus, baselineVus),
            ),
        )
    }
}
