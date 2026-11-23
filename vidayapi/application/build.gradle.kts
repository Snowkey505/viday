import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

group = "com.snowkey"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":domain"))
    implementation("io.arrow-kt:arrow-core:1.2.4")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.jsonwebtoken:jjwt-api:0.11.5")
    implementation("io.jsonwebtoken:jjwt-impl:0.11.5")
    implementation("io.jsonwebtoken:jjwt-jackson:0.11.5")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    testImplementation("io.mockk:mockk:1.13.8")

    testImplementation("org.testcontainers:testcontainers:1.19.3")
    testImplementation("org.testcontainers:postgresql:1.19.3")
    testImplementation("org.testcontainers:junit-jupiter:1.19.3")

    testImplementation("org.postgresql:postgresql:42.7.1")
    testImplementation("com.zaxxer:HikariCP:5.0.1")
    testImplementation("org.springframework:spring-jdbc")

    testImplementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("io.qameta.allure:allure-junit5:2.29.1")
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    enabled = true
    archiveBaseName.set("viday-application")
    archiveVersion.set("1.0.0")
}

tasks.test {
    useJUnitPlatform()
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}