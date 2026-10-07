import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.library)
    id("jacoco")
}

android {
    namespace = "com.snowkey.viday.data"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
    // Зависимость по живому списку Test-тасков: покрывает любое имя unit-таска
    // (testDebugUnitTest в AGP 8, переименованные таски в AGP 9).
    dependsOn(tasks.withType<Test>())
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
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.firebase.crashlytics.buildtools)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)
}
