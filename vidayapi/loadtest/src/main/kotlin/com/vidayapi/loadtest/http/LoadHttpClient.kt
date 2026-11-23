package com.vidayapi.loadtest.http

import com.vidayapi.loadtest.LoadConfig
import com.vidayapi.loadtest.core.FailureDetector
import com.vidayapi.loadtest.core.LoadDispatcher
import com.vidayapi.loadtest.core.PointWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

data class LoadResponse(val status: Int, val body: String)

class LoadHttpClient(
    private val config: LoadConfig,
    dispatcher: LoadDispatcher,
    private val points: PointWriter,
    private val failures: FailureDetector,
) {
    private val client = HttpClient.newBuilder()
        .executor(dispatcher.executor)
        .connectTimeout(config.requestTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun request(
        method: String,
        path: String,
        operation: String,
        phase: String,
        token: String? = null,
        body: String? = null,
        injectedError: Boolean = false,
        record: Boolean = true,
    ): LoadResponse {
        val builder = HttpRequest.newBuilder(URI.create(config.baseUrl + path))
            .timeout(config.requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "viday-lr4-coroutines/1.0")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())

        val started = System.nanoTime()
        val response = try {
            val result = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
            LoadResponse(result.statusCode(), result.body())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LoadResponse(0, "")
        }
        val durationMs = (System.nanoTime() - started) / 1_000_000.0
        if (record) {
            val at = Instant.now()
            points.request(durationMs, config.scenario, phase, operation, response.status, injectedError, at)
            failures.record(response.status == 0 || response.status >= 500, at)
        }
        return response
    }
}

private suspend fun <T> CompletableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    whenComplete { result, error ->
        if (error == null) {
            continuation.resume(result)
        } else {
            val cause = (error as? CompletionException)?.cause ?: error
            continuation.resumeWithException(cause)
        }
    }
    continuation.invokeOnCancellation { cancel(true) }
}
