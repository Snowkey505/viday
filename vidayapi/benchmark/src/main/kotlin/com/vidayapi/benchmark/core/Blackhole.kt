package com.vidayapi.benchmark.core

/**
 * Blackhole - приёмник результатов измеряемой операции.
 *
 * Зачем: JIT-компилятор удаляет «мёртвые» вычисления, результаты которых нигде
 * не используются (dead-code elimination). Чтобы этого не происходило, результат
 * каждой операции «потребляется» здесь — сумма identity-hash кодов утекает
 * в поле [sink].
 *
 * Стоимость вызова ~3–6 нс (identityHashCode + volatile-обновление). Эта
 * стоимость входит в измеряемый overhead инструмента и вычитается из «сырых»
 * результатов (см. BenchmarkRunner.measureOverhead*), поэтому на честность
 * сравнения двух библиотек она не влияет.
 */
class Blackhole {
    // @Volatile: JIT не может полностью убрать запись; реально неатомарные
    // гонки в смешанном сценарии допустимы — значение не проверяется на
    // корректность, оно нужно только чтобы «оживить» вычисления.
    @Volatile
    private var sink: Long = 0L

    fun consume(value: Any?) {
        if (value != null) sink += System.identityHashCode(value).toLong()
    }

    fun peek(): Long = sink
}
