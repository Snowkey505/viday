plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("io.spring.dependency-management")
}

group = "com.snowkey"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

// =============================================================================
// ЛР3 — микробенчмаркинг.
//
// Сравниваются ДВА объекта (Задание): библиотеки JSON-сериализации, решающие
// одну и ту же задачу:
//   * Jackson 3 (tools.jackson.*, версии из BOM Spring Boot 4.0.5) — библиотека,
//     которую проект vidayapi использует в production (jackson-module-kotlin);
//   * kotlinx.serialization (сериализация на этапе компиляции, официальный
//     Kotlin-подход, в проекте пока не используется).
//
// Инструмент бенчмаркинга — собственный (Требование 3): весь код харнесса
// лежит в src/main/kotlin/com/vidayapi/benchmark/core и не зависит от BOM/JMH.
// =============================================================================
dependencies {
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.vidayapi.benchmark.MainKt")
    // Фиксированные параметры JVM — воспроизводимость замеров (Требования 4, 6):
    // один и тот же размер кучи, один GC без параллельных потоков.
    applicationDefaultJvmArgs = listOf(
        "-Xms256m",
        "-Xmx256m",
        "-XX:+UseSerialGC",
        "-Dfile.encoding=UTF-8",
    )
}