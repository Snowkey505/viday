import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("io.qameta.allure") version "4.0.2" apply false
    id("org.sonarqube") version "7.5.0.8588" apply false
}

// SonarQube analysis is applied to the root project (aggregates module coverage).
apply(plugin = "org.sonarqube")

val sonarHostUrl = providers.gradleProperty("sonarHostUrl").orElse("http://localhost:9002").get()
val sonarToken = providers.gradleProperty("sonarToken").orElse("").get()

extensions.configure<org.sonarqube.gradle.SonarExtension> {
    properties {
        property("sonar.projectKey", "vidayapi")
        property("sonar.projectName", "viday-api")
        property("sonar.projectVersion", version)
        property("sonar.host.url", sonarHostUrl)
        // sonar.token is the canonical property; sonar.login is the deprecated alias and
        // setting both makes the scanner warn that only sonar.token is used.
        property("sonar.token", sonarToken)
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.scm.disabled", true)
        property("sonar.qualitygate.wait", true)
    }
}

group = "com.snowkey"
version = "0.0.1-SNAPSHOT"

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    pluginManager.withPlugin("io.spring.dependency-management") {
        extensions.configure<DependencyManagementExtension> {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.5")
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "jacoco")
        apply(plugin = "io.qameta.allure")
        apply(plugin = "org.sonarqube")

        // Per-module SonarQube inputs: sources, compiled classes, JUnit XML and JaCoCo XML.
        extensions.configure<org.sonarqube.gradle.SonarExtension> {
            properties {
                property("sonar.sources", "src/main/kotlin")
                // Модуль без src/test/kotlin (напр. domain) роняет скан с
                // "The folder 'src/test/kotlin' does not exist" — задаём путь только
                // если каталог реально есть (исходники статичны, проверка валидна).
                val sonarTestDir = layout.projectDirectory.dir("src/test/kotlin")
                if (sonarTestDir.asFile.exists()) {
                    property("sonar.tests", sonarTestDir.asFile.absolutePath)
                }
                property("sonar.java.binaries", layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath)
                property("sonar.java.libraries", configurations.named("runtimeClasspath").get().asPath)
                property(
                    "sonar.junit.reportPaths",
                    listOf("test-results/test", "test-results/testIntegration", "test-results/testE2E")
                        .joinToString(",") { layout.buildDirectory.dir(it).get().asFile.absolutePath },
                )
                property(
                    "sonar.coverage.jacoco.xmlReportPaths",
                    layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.absolutePath,
                )
            }
        }

        dependencies {
            add("testImplementation", "io.qameta.allure:allure-junit5:2.29.1")
            add("testImplementation", "org.junit.jupiter:junit-jupiter")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }

        // Allure 4.x resolves its bundled Node.js runtime through a broken Maven->nodejs.org
        // mapping: the ".pom" request is answered with the tarball, Gradle caches it as a
        // "*.pom" file and the DownloadNode task rejects it ("Unsupported archive format").
        // Workaround: drop the remote org.nodejs dependency and feed the local archive instead.
        val allureNodeConfiguration = project.configurations.findByName("allureNodeDistribution")
        if (allureNodeConfiguration != null) {
            allureNodeConfiguration.exclude(group = "org.nodejs")
            val nodeArchive = rootProject.layout.projectDirectory
                .file("gradle/node/node-v22.22.0-linux-x64.tar.gz")
            if (!nodeArchive.asFile.exists()) {
                // Архив (~120 МБ) исключён из git (см. .gitignore: gradle/node/),
                // поэтому скачиваем его один раз при первом запуске сборки.
                // Это нужно и на свежем клоне, и внутри docker-контекста CI.
                logger.lifecycle("Allure: downloading Node.js archive {} ...", nodeArchive.asFile)
                nodeArchive.asFile.parentFile.mkdirs()
                ant.invokeMethod(
                    "get",
                    mapOf(
                        "src" to "https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-x64.tar.gz",
                        "dest" to nodeArchive.asFile,
                        "usetimestamp" to "true",
                    ),
                )
            }
            require(nodeArchive.asFile.exists()) {
                "Node.js archive for Allure report is missing: ${nodeArchive.asFile}. " +
                    "Download it manually: curl -L " +
                    "https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-x64.tar.gz " +
                    "-o gradle/node/node-v22.22.0-linux-x64.tar.gz"
            }
            project.dependencies.add("allureNodeDistribution", project.files(nodeArchive.asFile))
        }

        // The Allure plugin registers its own `allureServe` task in every module, but it is
        // broken: it runs `allure open <absolute report path>` from the module directory, the
        // CLI re-resolves the absolute path against the working directory (path gets doubled)
        // and fails with "No test results directories found". The working serving task lives
        // on the root project (`:allureServe`); disable the per-module copies so that
        // `./gradlew allureServe` / `make allure-serve` cannot pick them up.
        tasks.matching { it.name == "allureServe" }.configureEach { enabled = false }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Один JVM-процесс на весь test-task модуля (не на каждый тест/класс).
            maxParallelForks = 1
            val seed = System.getProperty("junit.jupiter.execution.order.random.seed")
                ?: System.currentTimeMillis().toString()
            systemProperty("junit.jupiter.execution.order.random.seed", seed)
            // ЛР2: у каждого test-task СВОЙ каталог allure-results (unit / it / e2e),
            // чтобы повторные прогоны не затирали чужие результаты и агрегация
            // отчёта (collectAllureResultsCi / allureCiReport) была детерминированной.
            val allureResultsDirName = when (name) {
                "testIntegration" -> "allure-results-it"
                "testE2E" -> "allure-results-e2e"
                else -> "allure-results"
            }
            systemProperty(
                "allure.results.directory",
                layout.buildDirectory.dir(allureResultsDirName).get().asFile.absolutePath,
            )
            // ВАЖНО: плагин io.qameta.allure 4.x сам конфигурирует каталог результатов
            // (build/allure-results) и ПЕРЕБИВАЕТ systemProperty выше — integration/e2e
            // результаты оказывались в общем каталоге, а allure-results-it/-e2e оставались
            // пустыми (в CI артефакты it/e2e загружались без allure-результатов).
            // Поэтому stage-каталог выделяется перемещением файлов ПОСЛЕ прогона:
            // doFirst запоминает содержимое build/allure-results до старта тестовой JVM,
            // doLast переносит только НОВЫЕ файлы в build/allure-results-it | -e2e.
            // Локальный full сохраняет unit-результаты нетронутыми, в CI каждая стадия
            // отдаёт в upload-артефакт свой чистый каталог.
            if (allureResultsDirName != "allure-results") {
                val defaultResultsDir = layout.buildDirectory.dir("allure-results").get().asFile
                val stageResultsDir = layout.buildDirectory.dir(allureResultsDirName).get().asFile
                val preExisting = mutableSetOf<String>()
                doFirst {
                    preExisting.clear()
                    defaultResultsDir.listFiles()?.forEach { preExisting.add(it.name) }
                }
                doLast {
                    stageResultsDir.deleteRecursively()
                    stageResultsDir.mkdirs()
                    defaultResultsDir.listFiles()
                        ?.filter { it.name !in preExisting }
                        ?.forEach { it.renameTo(java.io.File(stageResultsDir, it.name)) }
                }
            }
            doFirst {
                logger.lifecycle("JUnit random order seed: $seed")
            }
            testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }

        tasks.named<Test>("test").configure {
            useJUnitPlatform {
                // Демо-фикстур отдельно: обычный прогон остаётся ровно 22 теста
                // (8 классических + 14 лондонских), demo-классы запускаются
                // только через :<module>:testFixturesDemo.
                // ЛР2: integration и e2e тоже не входят в обычный прогон unit-тестов
                // (запускаются отдельными тасками testIntegration / testE2E).
                excludeTags("integration", "fixtures-demo", "e2e")
            }
            finalizedBy("jacocoTestReport")
        }

        tasks.register<Test>("testOffline") {
            group = "verification"
            description = "Unit tests tagged offline (mocks + in-memory fakes), no Docker"
            // Wire the custom task to the default test source set explicitly.
            // A manually registered Test task does NOT automatically depend on the
            // test compilation nor point at the compiled test classes.
            val testSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("test")
            dependsOn(tasks.named("testClasses"))
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform {
                includeTags("offline")
                excludeTags("integration")
            }
            maxParallelForks = 1
        }

        // Демонстрация фикстур: @Tag("fixtures-demo") исключён из обычного `test`,
        // поэтому число тестов (22) не меняется. Запуск только demo-классов:
        //   ./gradlew :infrastructure:testFixturesDemo    (или make test-fixtures-demo)
        tasks.register<Test>("testFixturesDemo") {
            group = "verification"
            description = "Run the fixture demo tests (@Tag(\"fixtures-demo\")) — excluded from `test`"
            val testSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("test")
            dependsOn(tasks.named("testClasses"))
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform {
                includeTags("fixtures-demo")
                excludeTags("integration")
            }
            maxParallelForks = 1
            // Модули без demo-тестов не должны падать с "no tests found".
            filter {
                isFailOnNoMatchingTests = false
            }
        }

        tasks.register<Test>("testIntegration") {
            group = "verification"
            description = "Integration tests (@Tag(\"integration\")) on the real test stand (docker-compose.test.yml)"
            val testSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("test")
            dependsOn(tasks.named("testClasses"))
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform {
                includeTags("integration")
            }
            maxParallelForks = 1
            // Testcontainers 1.19.x bundles docker-java, which negotiates Docker API v1.32 by
            // default. Modern Docker daemons (>= 25, min API 1.44) reject that handshake with
            // "client version 1.32 is too old". Pin the API version the client advertises.
            systemProperty("api.version", "1.44")
            // Интеграционные тесты читают параметры стенда из окружения
            // (VIDAY_TEST_JDBC_URL и т.д.), поэтому прокидываем их в test JVM.
            System.getenv("VIDAY_TEST_JDBC_URL")?.let { systemProperty("VIDAY_TEST_JDBC_URL", it) }
            System.getenv("VIDAY_TEST_DB_USER")?.let { systemProperty("VIDAY_TEST_DB_USER", it) }
            System.getenv("VIDAY_TEST_DB_PASSWORD")?.let { systemProperty("VIDAY_TEST_DB_PASSWORD", it) }
        }

        // ЛР2: E2E-тесты живут в presentation (поднимают приложение через @SpringBootTest
        // поверх стенда) и помечены @Tag("e2e").
        if (project.name == "presentation") {
            tasks.register<Test>("testE2E") {
                group = "verification"
                description = "E2E tests (@Tag(\"e2e\")) — real HTTP scenario on the deployed test stand"
                val testSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("test")
                dependsOn(tasks.named("testClasses"))
                testClassesDirs = testSourceSet.output.classesDirs
                classpath = testSourceSet.runtimeClasspath
                useJUnitPlatform {
                    includeTags("e2e")
                }
                maxParallelForks = 1
                // Пробрасываем параметры стенда из окружения в test JVM
                // (в противном случае тесты используют локальные дефолты).
                System.getenv("VIDAY_TEST_JDBC_URL")?.let { systemProperty("VIDAY_TEST_JDBC_URL", it) }
                System.getenv("VIDAY_TEST_DB_USER")?.let { systemProperty("VIDAY_TEST_DB_USER", it) }
                System.getenv("VIDAY_TEST_DB_PASSWORD")?.let { systemProperty("VIDAY_TEST_DB_PASSWORD", it) }
                System.getenv("VIDAY_TEST_REDIS_HOST")?.let { systemProperty("VIDAY_TEST_REDIS_HOST", it) }
                System.getenv("VIDAY_TEST_REDIS_PORT")?.let { systemProperty("VIDAY_TEST_REDIS_PORT", it) }
                System.getenv("VIDAY_TEST_MINIO_ENDPOINT")?.let { systemProperty("VIDAY_TEST_MINIO_ENDPOINT", it) }
            }
        }

        // JaCoCo-отчёт модуля с ЯВНЫМИ путями: именно их ожидают CI-артефакты,
        // sonar.coverage.jacoco.xmlReportPaths, coverageSummary и attachCoverageToAllure
        // (дефолтные пути отчёта зависят от версии Gradle и не совпадают с ними).
        tasks.named<JacocoReport>("jacocoTestReport") {
            // В данные включаем exec-файлы ВСЕХ тестовых задач модуля:
            // test (unit) + testIntegration + testE2E — итоговое покрытие
            // отражает весь конвейер, а не только unit-прогон. JaCoCo-агент
            // подключён ко всем Test-задачам плагином jacoco, но дефолтный
            // jacocoTestReport собирает только test.exec.
            executionData(
                fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") },
            )
            // Gradle 9: exec-файлы — выходы Test-задач, поэтому при совместном
            // запуске (например, `testIntegration jacocoTestReport`) возникает
            // ошибка валидации "implicit dependency". Объявляем порядок через
            // mustRunAfter (не dependsOn — отчёт НЕ должен сам запускать тесты,
            // он конвертирует уже существующие *.exec).
            mustRunAfter(tasks.matching { it.name in setOf("test", "testIntegration", "testE2E") })
            reports {
                xml.required.set(true)
                xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
                html.required.set(true)
                html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/test/html"))
                csv.required.set(true)
                csv.outputLocation.set(layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.csv"))
            }
        }
    }
}

tasks.register("coverageSummary") {
    group = "verification"
    description = "Print JaCoCo LINE (exec loc / all loc) and BRANCH coverage for all modules"
    dependsOn(subprojects.map { it.tasks.named("jacocoTestReport") })
    doLast {
        subprojects.forEach { sub ->
            val xml = sub.layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile
            if (!xml.exists()) {
                logger.lifecycle("${sub.name}: jacoco XML not found")
                return@forEach
            }
            val counters = Regex("""<counter type="(LINE|BRANCH|INSTRUCTION)" missed="(\d+)" covered="(\d+)"/>""")
                .findAll(xml.readText())
                .map { it.groupValues[1] to (it.groupValues[2].toInt() to it.groupValues[3].toInt()) }
                .toList()
            fun last(type: String): Pair<Int, Int>? = counters.lastOrNull { it.first == type }?.second
            fun pct(missed: Int, covered: Int): String {
                val all = missed + covered
                if (all == 0) return "n/a"
                return "%.2f%% (%d / %d)".format(100.0 * covered / all, covered, all)
            }
            val line = last("LINE")
            val branch = last("BRANCH")
            val insn = last("INSTRUCTION")
            logger.lifecycle("=== ${sub.name} coverage ===")
            if (insn != null) logger.lifecycle("  instructions (exec / all): ${pct(insn.first, insn.second)}")
            if (line != null) logger.lifecycle("  lines (exec loc / all loc): ${pct(line.first, line.second)}")
            if (branch != null) logger.lifecycle("  branches: ${pct(branch.first, branch.second)}")
        }
    }
}

tasks.register("allureReports") {
    group = "verification"
    description = "Generate Allure HTML reports for modules with tests"
    dependsOn(":domain:allureReport", ":application:allureReport", ":infrastructure:allureReport")
}

// ---- SonarQube analysis (aggregated multi-module coverage in a tree view) ----
// CI contract:
//   1. unit/integration/e2e test jobs run the tests once;
//   2. they upload JaCoCo *.exec + JUnit XML;
//   3. sonar job downloads those artifacts;
//   4. jacocoTestReport ONLY converts the already existing *.exec files to XML/HTML;
//   5. sonar imports those reports.
// IMPORTANT: this task intentionally has NO dependency on any Test task.
tasks.named<org.sonarqube.gradle.SonarTask>("sonar") {
    group = "verification"
    description = "Run SonarQube analysis from previously generated test/JaCoCo artifacts (no test re-run)"
    dependsOn(subprojects.map { "${it.path}:jacocoTestReport" })
}

// ---- Aggregated Allure report + live server (equivalent of classic `allure serve`) ----
// The Allure Gradle plugin (4.x) assembles its own Allure 3 CLI runtime per module
// (see :domain:downloadAllure -> domain/build/allure/commandline/bin/allure).
// We reuse that runtime so no system-wide `allure` install is required.
val allureCli = rootProject.layout.projectDirectory
    .file("domain/build/allure/commandline/bin/allure")

val allureAggregateResults = layout.buildDirectory.dir("allure-results")
val allureAggregateReport = layout.buildDirectory.dir("reports/allure-report")

// Clean each module's allure-results right before its `test` task actually runs.
// This keeps the report free of stale results accumulated by earlier runs
// (otherwise the same test shows up again as a "retry" on every re-run).
// Placed in doFirst: when the task is UP-TO-DATE nothing is wiped.
subprojects.forEach { sub ->
    sub.tasks.matching { it.name == "test" }.configureEach {
        doFirst {
            val dir = sub.layout.buildDirectory.dir("allure-results").get().asFile
            if (dir.exists()) dir.deleteRecursively()
        }
    }
}

// Merge allure-results from every module into one directory so the report is aggregated.
tasks.register<Sync>("collectAllureResults") {
    group = "verification"
    description = "Merge allure-results from all modules into build/allure-results"
    // Re-run unit tests first so the merged results are fresh.
    dependsOn(subprojects.map { "${it.path}:test" })
    subprojects.forEach { sub ->
        from(sub.layout.buildDirectory.dir("allure-results"))
    }
    into(allureAggregateResults)
    // Several modules ship the same auxiliary files (executor.json, categories.json…).
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Generate ONE Allure HTML report out of all merged results.
tasks.register<Exec>("allureReportAggregate") {
    group = "verification"
    description = "Generate one aggregated Allure HTML report from all modules (build/reports/allure-report)"
    dependsOn("collectAllureResults", ":domain:downloadAllure")
    inputs.dir(allureAggregateResults)
    outputs.dir(allureAggregateReport)
    doFirst {
        require(allureCli.asFile.exists()) {
            "Allure CLI runtime missing at ${allureCli.asFile} — run ':domain:downloadAllure' first"
        }
        // `allure generate` silently produces an EMPTY report when the output
        // directory already contains one — always regenerate from scratch.
        delete(allureAggregateReport)
    }
    // Use the plugin-assembled Allure 3 CLI runtime (bundles Node 22) — same binary
    // the per-module :<module>:allureReport tasks use.
    commandLine(
        allureCli.asFile.absolutePath,
        "generate",
        "--output", allureAggregateReport.get().asFile.absolutePath,
        "--report-name", "viday-api Allure Report",
        "--history-limit", "20",
        allureAggregateResults.get().asFile.absolutePath,
    )
}

// Run ALL tests (unit + integration + e2e), build the aggregated LR2 report and serve it.
// Usage: ./gradlew allureServe            (default port 19999)
//        ./gradlew allureServe -PallurePort=8080
// Требуется поднятый тестовый стенд (make stand-up): в отчёт входят и IT, и E2E.
tasks.register<Exec>("allureServe") {
    group = "verification"
    description = "Run all tests (unit+integration+e2e), generate the aggregated Allure report and serve it (Ctrl+C to stop)"
    // Прогон тестов здесь, а не в allureCiReport: иначе стадия report в CI
    // заново поднимала бы стенд и гоняла unit+IT+e2e.
    dependsOn(
        subprojects.map { "${it.path}:test" },
        ":infrastructure:testIntegration",
        ":presentation:testE2E",
        "allureCiReport",
    )
    val port = providers.gradleProperty("allurePort").orElse("19999")
    // `allure open` resolves its positional argument against the working directory,
    // so pass a project-relative path (an absolute path gets doubled and fails).
    val reportRelative = rootProject.relativePath(allureCiReportDir.get().asFile)
    commandLine(
        allureCli.asFile.absolutePath,
        "open",
        "--port", port.get(),
        reportRelative,
    )
    // The server runs indefinitely; never treat the task as up-to-date.
    outputs.upToDateWhen { false }
    doFirst {
        logger.lifecycle("Serving Allure report (unit+integration+e2e): http://localhost:${port.get()}  (press Ctrl+C to stop)")
    }
}

// ---- ЛР2: агрегированный Allure-отчёт по ВСЕМ этапам (unit + integration + e2e) ----
// Генерируется в CI/CD всегда (даже если integration/e2e упали — Требование 8)
// и учитывает историю прошлых прогонов для трендов (Требование 9).
val allureCiInputResults = layout.buildDirectory.dir("allure-results-ci-input")
val allureCiResults = layout.buildDirectory.dir("allure-results-ci")
val allureCiReportDir = layout.buildDirectory.dir("reports/allure-report-ci")

tasks.register<Copy>("collectAllureResultsCi") {
    group = "verification"
    description = "Collect already produced Allure results for CI report (does not re-run tests)"

    // CI explicitly normalizes artifact contents into build/allure-results-ci-input.
    // Using that directory makes the report independent of GitHub artifact layout.
    from(allureCiInputResults)

    // Local/direct invocation is also supported: if the CI input directory is absent
    // or empty, collect the results produced by the actual test tasks.
    subprojects.forEach { sub ->
        from(sub.layout.buildDirectory.dir("allure-results"))
    }
    from(project(":infrastructure").layout.buildDirectory.dir("allure-results-it"))
    from(project(":presentation").layout.buildDirectory.dir("allure-results-e2e"))

    into(allureCiResults)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false

    // Never make this task depend on tests. It is a pure collector.
    doFirst {
        allureCiInputResults.get().asFile.mkdirs()
        listOf(
            project(":infrastructure").layout.buildDirectory.dir("allure-results-it").get().asFile,
            project(":presentation").layout.buildDirectory.dir("allure-results-e2e").get().asFile,
        ).forEach { dir -> dir.mkdirs() }
        subprojects.forEach { sub ->
            sub.layout.buildDirectory.dir("allure-results").get().asFile.mkdirs()
        }
    }
}

// ---- Покрытие (JaCoCo) внутри Allure-отчёта ----
// Создаёт синтетический allure-result «Coverage» и прикладывает HTML-отчёты JaCoCo
// каждого модуля как attachment (type=text/html), чтобы покрытие было видно
// прямо внутри агрегированного Allure-отчёта (не отдельным артефактом).
// Данные берутся из <module>/build/reports/jacoco/test/html (в CI приходят
// артефактом vidayapi-jacoco-unit). Если отчётов нет — задача ничего не делает.
tasks.register("attachCoverageToAllure") {
    group = "verification"
    description = "Attach per-module JaCoCo HTML coverage to the aggregated Allure report"
    mustRunAfter("collectAllureResultsCi")
    doLast {
        val targetDir = allureCiResults.get().asFile
        targetDir.mkdirs()
        val attachments = mutableListOf<String>()
        subprojects.forEach { sub ->
            val index = sub.layout.buildDirectory.file("reports/jacoco/test/html/index.html").get().asFile
            if (index.exists()) {
                // ВАЖНО: Allure 3 резолвит attachment ТОЛЬКО одним файлом из корня
                // каталога результатов (файлы в поддиректориях помечаются "missed").
                // Поэтому кладём index.html наверх с уникальным именем.
                val dest = File(targetDir, "coverage-${sub.name}.html")
                index.copyTo(dest, overwrite = true)
                attachments +=
                    """{"name": "${sub.name} (JaCoCo coverage)", "source": "coverage-${sub.name}.html", "type": "text/html"}"""
            }
        }
        if (attachments.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val json = buildString {
                appendLine("{")
                appendLine("  \"uuid\": \"coverage-summary\",")
                appendLine("  \"historyId\": \"coverage-summary\",")
                appendLine("  \"name\": \"Coverage (JaCoCo, unit tests)\",")
                appendLine("  \"status\": \"passed\",")
                appendLine("  \"stage\": \"finished\",")
                appendLine("  \"start\": $now,")
                appendLine("  \"stop\": ${now + 1},")
                appendLine("  \"labels\": [{\"name\": \"suite\", \"value\": \"Coverage\"}],")
                appendLine("  \"attachments\": [${attachments.joinToString(",")}]")
                appendLine("}")
            }
            File(targetDir, "coverage-summary-result.json").writeText(json)
            logger.lifecycle("attachCoverageToAllure: added ${attachments.size} coverage attachment(s)")
        } else {
            logger.lifecycle("attachCoverageToAllure: no JaCoCo HTML reports found, skipping")
        }
    }
}

tasks.register<Exec>("allureCiReport") {
    group = "verification"
    description = "Generate Allure HTML from existing allure-results* (no test re-run)"
    dependsOn("collectAllureResultsCi", "attachCoverageToAllure", ":domain:downloadAllure")
    inputs.dir(allureCiResults)
    outputs.dir(allureCiReportDir)
    doFirst {
        require(allureCli.asFile.exists()) {
            "Allure CLI runtime missing at ${allureCli.asFile} — run ':domain:downloadAllure' first"
        }
        // `allure generate` молча выдаёт ПУСТОЙ отчёт, если выходная директория уже
        // содержит отчёт — всегда генерируем с нуля.
        delete(allureCiReportDir)
    }
    commandLine(
        allureCli.asFile.absolutePath,
        "generate",
        "--output", allureCiReportDir.get().asFile.absolutePath,
        "--report-name", "viday-api Allure Report (LR2: unit+integration+e2e)",
        "--history-limit", "20",
        allureCiResults.get().asFile.absolutePath,
    )
}
