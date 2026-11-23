plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("io.spring.dependency-management")
}

group = "com.snowkey"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.vidayapi.loadtest.MainKt")
    applicationDefaultJvmArgs = listOf("-Xms128m", "-Xmx512m", "-Dfile.encoding=UTF-8")
}
