package com.vidayapi.loadtest.core

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

class LoadDispatcher(val threads: Int) : CoroutineDispatcher(), Closeable {
    private val threadIds = AtomicInteger()
    private val threadFactory = ThreadFactory { task ->
        Thread(task, "lr4-dispatcher-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }

    val executor: ExecutorService = Executors.newFixedThreadPool(threads, threadFactory)

    init {
        require(threads > 0) { "DISPATCHER_THREADS must be positive" }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute(block)
    }

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
        }
    }
}
