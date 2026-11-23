package com.vidayapi.benchmark.core

import java.io.File
import java.time.Instant

/**
 * Снимок окружения прогона — для воспроизводимости (Требование 6):
 * JVM, ОС, CPU, список разрешённых ядер (Cpus_allowed_list доказывает
 * закрепление на 1 ядро в Docker), признак контейнера.
 */
object Environment {

    fun snapshot(): Map<String, String> = linkedMapOf(
        "timestamp" to Instant.now().toString(),
        "java.version" to System.getProperty("java.version"),
        "java.vendor" to System.getProperty("java.vendor"),
        "jvm.name" to System.getProperty("java.vm.name"),
        "os.name" to System.getProperty("os.name"),
        "os.version" to System.getProperty("os.version"),
        "os.arch" to System.getProperty("os.arch"),
        "availableProcessors" to Runtime.getRuntime().availableProcessors().toString(),
        "cpusAllowedList" to readCpusAllowedList(),
        "cpuModel" to readCpuModel(),
        "container" to if (inContainer()) "docker" else "host",
    )

    /** Список CPU, разрешённых процессу (из /proc/self/status). */
    private fun readCpusAllowedList(): String =
        readFileOr("/proc/self/status", "n/a (не Linux)")
            .lines()
            .firstOrNull { it.startsWith("Cpus_allowed_list") }
            ?.substringAfter(":")
            ?.trim()
            ?: "n/a"

    private fun readCpuModel(): String =
        readFileOr("/proc/cpuinfo", "n/a")
            .lines()
            .firstOrNull { it.startsWith("model name") }
            ?.substringAfter(":")
            ?.trim()
            ?: "n/a"

    private fun inContainer(): Boolean = File("/.dockerenv").exists()

    private fun readFileOr(path: String, fallback: String): String =
        runCatching { File(path).readText() }.getOrDefault(fallback)
}
