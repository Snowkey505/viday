import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("java-library")
    id("jacoco")
    kotlin("plugin.serialization") version "1.9.0"
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

// JaCoCo-отчёт о покрытии unit-тестов (XML/HTML/CSV) для CI-артефактов.
// В Gradle 9 jacocoTestReport НЕ зависит от test автоматически — добавляем явно.
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.core)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
}
