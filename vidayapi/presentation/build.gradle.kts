plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

group = "com.snowkey"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation(kotlin("reflect"))
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // ЛР4: метрики JVM/HTTP/HikariCP для Prometheus во время нагрузочных прогонов.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework:spring-test")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    implementation("io.arrow-kt:arrow-core:1.2.4")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.ninja-squad:springmockk:4.0.2")

    // ЛР5: BDD (Cucumber-JVM) для E2E-сценариев аутентификации.
    // Версия 7.34.x собрана против Spring Framework 7, что соответствует
    // Spring Boot 4; allure-cucumber7-jvm пишет результаты в общий Allure-отчёт.
    testImplementation("io.cucumber:cucumber-java:7.34.9")
    testImplementation("io.cucumber:cucumber-spring:7.34.9")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.34.9")
    testImplementation("io.qameta.allure:allure-cucumber7-jvm:2.29.1")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
tasks.jar {
    enabled = false
}

tasks.bootJar {
    mainClass.set("com.vidayapi.VidayApplicationKt")
    archiveBaseName.set("viday-application")
    archiveVersion.set("1.0.0")

    isEnabled = true
}
