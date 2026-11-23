package com.vidayapi.loadtest

import java.nio.file.Path
import java.time.Duration

data class LoadConfig(
    val baseUrl: String,
    val scenario: String,
    val workload: String,
    val profile: String,
    val runId: String,
    val outputDir: Path,
    val errorRate: Double,
    val coroutines: Int,
    val dispatcherThreads: Int,
    val requestTimeout: Duration,
    val thinkMinMs: Long,
    val thinkMaxMs: Long,
    val userCount: Int,
    val userPrefix: String,
    val password: String,
    val seedPlaylists: Int,
    val smokeVus: Int,
    val smokeDuration: Duration,
    val soakVus: Int,
    val soakDuration: Duration,
    val rampMaxVus: Int,
    val rampStepDuration: Duration,
    val rampPlateauDuration: Duration,
    val stressBaselineVus: Int,
    val stressOverloadVus: Int,
    val stressBaselineDuration: Duration,
    val stressOverloadDuration: Duration,
    val stressRecoveryDuration: Duration,
    val earlyStop: Boolean,
    val earlyStopAfter: Duration,
    val failureWindow: Duration,
    val failureThreshold: Double,
    val failureWindows: Int,
    val failureMinRequests: Int,
) {
    fun plan() = when (scenario) {
        // VU в этом генераторе = корутина: поднять больше COROUTINES нельзя.
        // Обрезаем план здесь, иначе RAMP_MAX_VUS=300 при COROUTINES=100
        // превращается в невидимое плато на 100 VU и вводит в заблуждение.
        "ramp" -> com.vidayapi.loadtest.core.LoadPlan.ramp(
            rampStepDuration,
            rampPlateauDuration,
            minOf(rampMaxVus, coroutines),
        )
        "stress" -> com.vidayapi.loadtest.core.LoadPlan.stress(
            stressBaselineVus,
            stressOverloadVus,
            stressBaselineDuration,
            stressOverloadDuration,
            stressRecoveryDuration,
        )
        "soak", "soak-errors" -> com.vidayapi.loadtest.core.LoadPlan.constant(
            if (errorRate > 0) "soak_${(errorRate * 100).toInt()}pct" else "soak",
            soakVus,
            soakDuration,
        )
        else -> com.vidayapi.loadtest.core.LoadPlan.constant("smoke", smokeVus, smokeDuration)
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): LoadConfig {
            fun value(name: String, default: String) = env[name]?.takeIf { it.isNotBlank() } ?: default
            fun int(name: String, default: Int) = value(name, default.toString()).toInt().also {
                require(it > 0) { "$name must be positive" }
            }
            fun duration(name: String, default: String) = parseDuration(value(name, default))
            val errorRate = value("ERROR_RATE", "0").toDouble()
            val workload = value("WORKLOAD", "auth")
            val threshold = value("FAILURE_THRESHOLD", "0.95").toDouble()
            require(errorRate in 0.0..1.0) { "ERROR_RATE must be between 0 and 1" }
            require(workload in setOf("auth", "public")) { "WORKLOAD must be auth or public" }
            require(workload != "public" || errorRate == 0.0) { "public workload does not support ERROR_RATE" }
            require(threshold in 0.0..1.0) { "FAILURE_THRESHOLD must be between 0 and 1" }
            return LoadConfig(
                baseUrl = value("BASE_URL", "http://localhost:8080").trimEnd('/'),
                scenario = value("SCENARIO", "smoke"),
                workload = workload,
                profile = value("PROFILE", value("SCENARIO", "smoke")),
                runId = value("RUN_ID", "local-${System.currentTimeMillis()}"),
                outputDir = Path.of(value("OUT_DIR", "/results")),
                errorRate = errorRate,
                coroutines = int("COROUTINES", 450),
                dispatcherThreads = int("DISPATCHER_THREADS", 4),
                requestTimeout = duration("REQUEST_TIMEOUT", "30s"),
                thinkMinMs = value("THINK_MIN_MS", "1000").toLong(),
                thinkMaxMs = value("THINK_MAX_MS", "2500").toLong(),
                userCount = int("USER_COUNT", 200),
                userPrefix = value("USER_PREFIX", "loaduser"),
                password = value("LOADTEST_PASSWORD", "VidayLoadTest!42"),
                seedPlaylists = int("SEED_PLAYLISTS", 100),
                smokeVus = int("SMOKE_VUS", 2),
                smokeDuration = duration("SMOKE_DURATION", "35s"),
                soakVus = int("SOAK_VUS", 60),
                soakDuration = duration("SOAK_DURATION", "20m"),
                rampMaxVus = int("RAMP_MAX_VUS", 450),
                rampStepDuration = duration("RAMP_STEP_DURATION", "1m"),
                rampPlateauDuration = duration("RAMP_PLATEAU_DURATION", "2m"),
                stressBaselineVus = int("STRESS_BASELINE_VUS", 50),
                stressOverloadVus = int("STRESS_OVERLOAD_VUS", 400),
                stressBaselineDuration = duration("STRESS_BASELINE_DURATION", "3m"),
                stressOverloadDuration = duration("STRESS_OVERLOAD_DURATION", "4m"),
                stressRecoveryDuration = duration("STRESS_RECOVERY_DURATION", "5m"),
                earlyStop = value("EARLY_STOP", "1") == "1",
                earlyStopAfter = duration("EARLY_STOP_AFTER", "30s"),
                failureWindow = duration("FAILURE_WINDOW", "5s"),
                failureThreshold = threshold,
                failureWindows = int("FAILURE_WINDOWS", 3),
                failureMinRequests = int("FAILURE_MIN_REQUESTS", 20),
            ).also {
                require(it.thinkMinMs >= 0 && it.thinkMaxMs >= it.thinkMinMs) { "invalid think time range" }
                require(it.requestTimeout > Duration.ZERO) { "REQUEST_TIMEOUT must be positive" }
            }
        }

        fun parseDuration(value: String): Duration {
            val amount = value.dropLast(1).toLong()
            require(amount >= 0) { "duration cannot be negative: $value" }
            return when (value.lastOrNull()) {
                's' -> Duration.ofSeconds(amount)
                'm' -> Duration.ofMinutes(amount)
                'h' -> Duration.ofHours(amount)
                else -> Duration.ofMillis(value.toLong())
            }
        }
    }
}
