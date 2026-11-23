package com.vidayapi.loadtest.core

import java.io.BufferedWriter
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import com.vidayapi.loadtest.LiveMetrics
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class PointWriter(path: Path) : Closeable {
    private val writer: BufferedWriter = Files.newBufferedWriter(path)

    @Synchronized
    fun request(
        durationMs: Double,
        scenario: String,
        phase: String,
        operation: String,
        status: Int,
        injectedError: Boolean,
        at: Instant = Instant.now(),
    ) {
        write(
            "http_req_duration",
            durationMs,
            at,
        ) {
            put("scenario", scenario)
            put("phase", phase)
            put("operation", operation)
            put("status", status.toString())
            if (injectedError) put("injected_error", "true")
        }
        LiveMetrics.record(durationMs, phase, operation, status, injectedError)
    }

    @Synchronized
    fun vus(value: Int, at: Instant = Instant.now()) {
        write("vus", value.toDouble(), at) {}
        LiveMetrics.vus(value)
    }

    private fun write(metric: String, value: Double, at: Instant, tags: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val point = buildJsonObject {
            put("type", "Point")
            put("metric", metric)
            putJsonObject("data") {
                put("time", at.toString())
                put("value", value)
                putJsonObject("tags", tags)
            }
        }
        writer.write(point.toString())
        writer.newLine()
        writer.flush()
    }

    override fun close() = writer.close()
}
