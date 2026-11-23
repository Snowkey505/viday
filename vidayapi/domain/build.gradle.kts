plugins {
    kotlin("jvm")
}

group = "com.snowkey"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.arrow-kt:arrow-core:1.2.4")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    enabled = true
    archiveBaseName.set("viday-domain")
    archiveVersion.set("1.0.0")
}

tasks.test {
    useJUnitPlatform()
}