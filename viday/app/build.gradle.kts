import org.gradle.kotlin.dsl.testImplementation
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("jacoco")
}

android {
    namespace = "com.snowkey.viday"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.snowkey.viday"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Классы без location (Kotlin/coroutines) и JDK-внутренности: без этого
// JaCoCo-агент роняет тест-процесс (NoClassDefFoundError:
// jdk.internal.reflect.GeneratedSerializationConstructorAccessor).
// Сеттеры вызываем явно: Kotlin-свойства в Gradle 9.1 (K2) синтезируются
// из приватных полей и не компилируются. withType<Test> вместо жёсткого имени:
// в AGP 9 unit-таски создаются лениво.
tasks.withType<Test>().configureEach {
    extensions.findByType(JacocoTaskExtension::class.java)?.apply {
        setIncludeNoLocationClasses(true)
        setExcludes(listOf("jdk.internal.*"))
    }
}

// JaCoCo-отчёт по JVM unit-тестам (:testDebugUnitTest) — XML/HTML/CSV для CI.
tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "JaCoCo coverage report for JVM unit tests"
    // Живая выборка (не создаётся на этапе конфигурации, резолвится к запуску):
    // только debug unit-тесты, без release-вариантов.
    dependsOn(tasks.matching { it.name == "testDebugUnitTest" })
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
    val fileFilter = listOf(
        "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*Test*.*", "**/AutoValue_*.*", "android/**/*.*",
    )
    val mainSrc = "src/main/java"
    // AGP 9: Kotlin-классы (встроенный kotlinc); пути ниже — для совместимости с AGP 8.
    val kotlinTree = fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) { exclude(fileFilter) }
    val debugTree = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(fileFilter) }
    val javaTree = fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) { exclude(fileFilter) }
    sourceDirectories.setFrom(files(mainSrc))
    classDirectories.setFrom(files(kotlinTree, debugTree, javaTree))
    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        include("jacoco/testDebugUnitTest.exec")
    })
}

dependencies {
    implementation(project(":data"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Для AndroidApiFlowTest: реальный HTTP-клиент к API стенда прямо с устройства
    androidTestImplementation(libs.retrofit)
    androidTestImplementation(libs.converter.gson)
    androidTestImplementation(libs.logging.interceptor)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.kotlin.test)
}