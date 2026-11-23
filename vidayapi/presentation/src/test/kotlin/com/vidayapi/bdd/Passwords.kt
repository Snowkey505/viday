package com.vidayapi.bdd

import java.security.SecureRandom

/** Генерация паролей и кодов только в памяти теста. */
object Passwords {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /**
     * Пароль удовлетворяет правилам ЛР5: не короче 8 символов, содержит
     * букву и цифру. Значение никогда не печатается и не попадает в отчёт.
     */
    fun generate(): String = buildString {
        append("Lr5")
        repeat(12) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        append('9')
    }

    /** Гарантированно неверный шестизначный код. */
    fun wrongCode(actual: String): String = if (actual == "000000") "000001" else "000000"
}
