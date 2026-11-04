package com.vidayapi.benchmark.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Исполнитель смешанного сценария (Требование 5: одновременные чтение и запись).
 *
 * Два потока (ровно два — читатель и писатель) живут в фиксированном пуле и
 * на каждый «батч» получают по [pairsPerBatch] операций: первый поток выполняет
 * серию чтений, второй — серию записей, и замеряется суммарное время батча.
 * latencу на операцию получаем делением времени батча на 2*n (n чтений + n записей).
 *
 * На одном закреплённом ядре потоки реально конкурируют за CPU — это и есть
 * честная «одновременность» в условиях Требования 4 (достаточно 1 ядра).
 * Стоимость диспетчеризации задач включается в измерение — это часть
 * реалистичной цены конкурентного сценария.
 */
class ConcurrentPair(
    private val reader: () -> Any?,
    private val writer: () -> Any?,
) : AutoCloseable {

    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "bench-mixed-worker").apply { isDaemon = true }
    }

    /**
     * Выполняет [pairsPerBatch] пар «чтение-запись» одновременно и возвращает
     * полное время батча в наносекундах.
     */
    fun runBatch(pairsPerBatch: Int, blackhole: Blackhole): Long {
        val t0 = System.nanoTime()
        val f1 = pool.submit {
            repeat(pairsPerBatch) { blackhole.consume(reader()) }
        }
        val f2 = pool.submit {
            repeat(pairsPerBatch) { blackhole.consume(writer()) }
        }
        f1.get()
        f2.get()
        return System.nanoTime() - t0
    }

    override fun close() {
        pool.shutdownNow()
        pool.awaitTermination(1, TimeUnit.SECONDS)
    }
}