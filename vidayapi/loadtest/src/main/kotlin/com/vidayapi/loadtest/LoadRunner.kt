package com.vidayapi.loadtest

import com.vidayapi.loadtest.core.FailureDetector
import com.vidayapi.loadtest.core.LoadDispatcher
import com.vidayapi.loadtest.core.PlanState
import com.vidayapi.loadtest.core.PointWriter
import com.vidayapi.loadtest.http.LoadHttpClient
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class RunResult(
    val startedAt: Instant,
    val finishedAt: Instant,
    val earlyStopped: Boolean,
    val terminationReason: String,
)

class LoadRunner(
    private val config: LoadConfig,
    private val dispatcher: LoadDispatcher,
    private val points: PointWriter,
    private val failures: FailureDetector,
    private val http: LoadHttpClient,
    private val seed: SeedData,
) {
    suspend fun run(): RunResult = supervisorScope {
        val plan = config.plan()
        val state = AtomicReference(PlanState("starting", 0))
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()
        val session = UserSession(config, seed, http)
        var stopReason: String? = null

        val workers = (0 until config.coroutines).map { workerId ->
            launch(dispatcher) {
                var iteration = 0L
                val random = Random(workerId * 1_000_003 + 17)
                while (true) {
                    val current = state.get()
                    if (current.done) break
                    if (workerId >= current.vus) {
                        delay(50)
                        continue
                    }
                    session.execute(workerId, iteration++, current.phase)
                    delay(random.nextLong(config.thinkMinMs, config.thinkMaxMs + 1))
                }
            }
        }

        val scheduler = launch(dispatcher) {
            var lastVusSecond = -1L
            while (true) {
                val elapsed = (System.nanoTime() - startedNanos) / 1_000_000_000L
                val failureReason = failures.failureReason(elapsed)
                val planned = plan.stateAt(elapsed, config.coroutines)
                if (failureReason != null || planned.done) {
                    stopReason = failureReason
                    state.set(PlanState(planned.phase, 0, true))
                    break
                }
                state.set(planned)
                if (elapsed != lastVusSecond) {
                    points.vus(planned.vus)
                    lastVusSecond = elapsed
                }
                delay(200)
            }
        }
        scheduler.join()
        workers.joinAll()
        val finishedAt = Instant.now()
        RunResult(startedAt, finishedAt, stopReason != null, stopReason ?: "planned duration reached")
    }
}
