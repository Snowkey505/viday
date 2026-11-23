package com.vidayapi.loadtest.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoadDispatcherTest {
    @Test
    fun `uses configured named thread pool`() {
        LoadDispatcher(2).use { dispatcher ->
            val latch = CountDownLatch(4)
            val names = mutableSetOf<String>()
            repeat(4) {
                dispatcher.executor.execute {
                    synchronized(names) { names.add(Thread.currentThread().name) }
                    latch.countDown()
                }
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(2, names.size)
            assertTrue(names.all { it.startsWith("lr4-dispatcher-") })
        }
    }
}
