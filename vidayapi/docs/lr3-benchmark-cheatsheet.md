# ЛР3. Шпаргалка для повторения материала микробенчмаркинга

> Документ для быстрого повторения: где в проекте используется сериализация,
> как устроено Docker-окружение с 1 CPU, как организован сам микробенчмарк
> (прогрев, почему первые сэмплы не идут в статистику), как читать графики
> и почему бывают выбросы. Везде — ссылки на исходные файлы и ключевые
> фрагменты кода.

---

## 1. Что сравнивали и зачем

Сравнивались **два объекта** (по заданию ЛР3) — две библиотеки JSON-сериализации,
решающие одну и ту же задачу:

| | Jackson 3 (`tools.jackson.*`) | kotlinx.serialization |
|---|---|---|
| Механизм | runtime-рефлексия + кэширование `ObjectMapper` | сериализация на этапе компиляции (`@Serializable` → сгенерированный класс) |
| Статус в проекте | **используется в production** (jackson-module-kotlin) | в проекте пока не используется |
| Сценарии (Требование 5) | `read` (JSON → объект), `write` (объект → JSON), `mixed` (чтение+запись в 2 потоках) | то же самое |

Зависимости и обоснование — в [`benchmark/build.gradle.kts`](../benchmark/build.gradle.kts:15):

```kotlin
// ЛР3 — микробенчмаркинг.
// Сравниваются ДВА объекта (Задание): библиотеки JSON-сериализации, решающие
// одну и ту же задачу:
//   * Jackson 3 (tools.jackson.*, версии из BOM Spring Boot 4.0.5) — библиотека,
//     которую проект vidayapi использует в production (jackson-module-kotlin);
//   * kotlinx.serialization (сериализация на этапе компиляции, официальный
//     Kotlin-подход, в проекте пока не используется).
dependencies {
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
```

---

## 2. Где в основном проекте используется сериализация

Jackson в `vidayapi` используется в **REST-слое и кэшировании**: DTO сериализуются
в JSON для ответов API и для записи в Redis-кэш, и десериализуются обратно.

### 2.1 Контроллер (HTTP-слой)

[`presentation/src/main/kotlin/com/vidayapi/controller/VideoController.kt`](../presentation/src/main/kotlin/com/vidayapi/controller/VideoController.kt:113) —
чтение из кэша и запись в кэш:

```kotlin
cachePort.get(cacheKey)?.let { cached ->
    return ResponseEntity.ok(objectMapper.readValue(cached, VideoResponse::class.java))
}
// ...
val response = videoResponseMapper.toResponse(video)
cachePort.set(cacheKey, objectMapper.writeValueAsString(response), CacheTtl.VIDEO_SEC)
ResponseEntity.ok(response)
```

### 2.2 Use-case слой (кэширование списков)

[`application/src/main/kotlin/com/vidayapi/usecase/ListVideosUseCase.kt`](../application/src/main/kotlin/com/vidayapi/usecase/ListVideosUseCase.kt:54) и
[`ListLiveStreamsUseCase.kt`](../application/src/main/kotlin/com/vidayapi/usecase/ListLiveStreamsUseCase.kt:31):

```kotlin
cachePort.set(cacheKey, objectMapper.writeValueAsString(response), CacheTtl.PUBLIC_VIDEOS_SEC)
// ...
return objectMapper.readValue(json, type)
```

### 2.3 Конфигурация бина `ObjectMapper`

[`presentation/src/main/kotlin/com/vidayapi/config/PresentationUseCaseConfig.kt`](../presentation/src/main/kotlin/com/vidayapi/config/PresentationUseCaseConfig.kt:20) —
`ObjectMapper` (Jackson) инжектится в use-case'ы как Spring-бин.

### 2.4 DTO

Модели запросов/ответов — [`application/src/main/kotlin/com/vidayapi/dto/requests/*.kt`](../application/src/main/kotlin/com/vidayapi/dto/requests/LoginRequest.kt:1)
и `responses/*.kt`; их сериализует Jackson при обработке HTTP.

> **Вывод для защиты**: production-путь сериализации в vidayapi — это Jackson
> (`jackson-module-kotlin`), поэтому бенчмарк сравнивает «то, что реально
> работает в проекте» (Jackson) с альтернативой (kotlinx.serialization).

---

## 3. Docker-окружение: 1 CPU на микробенчмарк

### 3.1 Образ

[`docker/benchmark/Dockerfile`](../docker/benchmark/Dockerfile:1) — двухстадийная сборка:
собирается runnable-дистрибутив `:benchmark:installDist`, запускается на том же JRE,
что и production-контейнер, с **фиксированными JVM-параметрами**:

```dockerfile
# ---- стадия запуска: тот же JRE, что и production-контейнер проекта ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/benchmark/build/install/benchmark /app/benchmark
# Фиксированная конфигурация JVM (куча + SerialGC) — одинаковая для каждого прогона.
ENV JAVA_OPTS="-Xms256m -Xmx256m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
ENTRYPOINT ["/app/benchmark/bin/benchmark"]
```

Те же флаги продублированы в [`benchmark/build.gradle.kts`](../benchmark/build.gradle.kts:42)
(`applicationDefaultJvmArgs`), чтобы прогон через Gradle и через Docker был одинаковым.

### 3.2 Запуск с закреплением на 1 ядре

[`scripts/run-benchmark.sh`](../scripts/run-benchmark.sh:1) — на каждый прогон собирает
**отдельный docker-образ** (Требование 6: «равные условия на каждую попытку»,
кэш слоёв Gradle делает пересборку дешёвой) и запускает контейнер с
`--cpuset-cpus` (Требование 4 — 1 ядро), лимитом памяти и переменными `BENCH_*`:

```bash
docker run --rm \
    --name "viday-bench-$RUN_ID" \
    --cpuset-cpus="$CPUSET" \      # закрепление на 1 ядре (по умолчанию 0)
    --cpus=1 \
    --memory=1g \
    -e "BENCH_SAMPLES=$SAMPLES" \
    -e "BENCH_WARMUP=$WARMUP" \
    -e "BENCH_SETTLE=$SETTLE" \
    -e "BENCH_DROP=$DROP" \
    -e "BENCH_TEARDOWN=$TEARDOWN" \
    -e "BENCH_BATCH=$BATCH" \
    -e "BENCH_MIXED_PAIRS=$MIXED_PAIRS" \
    -v "$PWD/$OUT_DIR:/out" \
    "$IMAGE" --out /out
```

### 3.3 Доказательство «1 ядро» в отчёте

[`core/Environment.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Environment.kt:13)
снимает окружение, включая `Cpus_allowed_list` из `/proc/self/status` — в отчёте
видно, что процессу разрешено ровно одно ядро:

```kotlin
fun snapshot(): Map<String, String> = linkedMapOf(
    "timestamp" to Instant.now().toString(),
    "java.version" to System.getProperty("java.version"),
    // ...
    "availableProcessors" to Runtime.getRuntime().availableProcessors().toString(),
    "cpusAllowedList" to readCpusAllowedList(),   // например "0" — одно ядро
    "cpuModel" to readCpuModel(),
    "container" to if (inContainer()) "docker" else "host",
)
```

---

## 4. Организация микробенчмаркинга

### 4.1 Фазы прогона: `warmup → settle → measure → teardown`

[`core/BenchmarkRunner.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/BenchmarkRunner.kt:136):

```kotlin
fun run(case: BenchmarkCase): CaseResult {
    case.setUp()
    try {
        val warmup = phase("warmup", config.warmupSamples, config.batch, case::operation)
        // settle: стабилизация после прогрева (JIT/инициализация/GC-паузы),
        // результаты ОТБРАСЫВАЮТСЯ — первые сэмплы не искажают статистику.
        val settle = phase("settle", config.settleSamples, config.batch, case::operation)
        val measureFull = phase("measure", config.samples, config.batch, case::operation)
        val measure = trimMeasure(measureFull)   // drop первых N сэмплов
        val teardown = phase("teardown", config.teardownSamples, config.batch, case::operation)
        val stats = StatisticsCalculator.compute(measure.sampleTimesNs, config.batch)
        return CaseResult(/* ... */)
    } finally {
        case.tearDown()
    }
}
```

Плюс до всех фаз в [`Main.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/Main.kt:37)
выполняется 32 «тика» прогрева таймингового кода (JIT-компиляция sample/consume).

### 4.2 Батчи: почему измеряем «пачками»

Один сэмпл = `batch` операций подряд, замеряется суммарное время одним
`System.nanoTime()` ([`BenchmarkRunner.sample()`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/BenchmarkRunner.kt:245)):

```kotlin
private fun sample(ops: Int, op: () -> Any?): Double {
    val t0 = System.nanoTime()
    var i = 0
    while (i < ops) {
        blackhole.consume(op())
        i++
    }
    return (System.nanoTime() - t0).toDouble()
}
```

Зачем: стоимость самого `System.nanoTime()` (~20–40 нс) делится на `batch`
(256) → overhead инструмента ~0.1–0.5 нс/операция, а не 20–40 нс. Это делает
замеры честными для операций, которые сами стоят единицы микросекунд.

### 4.3 Почему первые сэмплы НЕ идут в статистику

Три уровня защиты от «стартового переходного процесса»:

1. **warmup** (по умолчанию 5000 сэмплов) — JIT компилирует горячий путь,
   ленивая инициализация (кэши сериализаторов Jackson) отрабатывает; результаты
   отбрасываются.
2. **settle** (по умолчанию 200 сэмплов) — отдельная фаза между warmup и measure:
   GC-пауза после аллокаций прогрева, установка скомпилированного JIT-кода на
   safepoint, первые page faults «укладываются» ДО измерения; результаты
   отбрасываются.
3. **drop** ([`trimMeasure()`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/BenchmarkRunner.kt:232),
   по умолчанию 10) — первые N сэмплов фазы measure удаляются из статистики,
   графиков и raw CSV:

```kotlin
private fun trimMeasure(measure: PhaseRecord): PhaseRecord {
    val drop = config.dropSamples
    if (drop <= 0 || measure.sampleTimesNs.isEmpty()) return measure
    val kept = measure.sampleTimesNs.copyOfRange(drop, measure.sampleTimesNs.size)
    return PhaseRecord("measure", kept.size, measure.opsPerSample, measure.durationNs, kept)
}
```

**Почему это честно**: удаление первых сэмплов — стандартный приём
микробенчмаркинга («startup transient removal», так делает и JMH), и оно
**прозрачно фиксируется в отчёте** (строка конфигурации «первых сэмплов measure
удалено (drop)» и преамбула раздела 4). Увеличивать прогрев «до бесконечности»
бессмысленно: всплеск на стыке фаз вызывают одноразовые события окружения,
а не инициализация сериализатора.

### 4.4 Overhead инструмента (Требование 7)

[`BenchmarkRunner.measureOverheadSingle()`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/BenchmarkRunner.kt:232)
и `measureOverheadConcurrent()` меряют стоимость самого харнесса (2×nanoTime +
цикл + Blackhole + no-op операция) на no-op операции, **после** прогрева.
В отчёте «net median» = median − overhead — «чистая» стоимость операции.

### 4.5 Blackhole — защита от dead-code elimination

[`core/Blackhole.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Blackhole.kt:16):

```kotlin
class Blackhole {
    @Volatile
    private var sink: Long = 0L

    fun consume(value: Any?) {
        if (value != null) sink += System.identityHashCode(value).toLong()
    }
}
```

JIT удаляет «мёртвые» вычисления, результат которых нигде не используется.
Blackhole «потребляет» результат каждой операции (сумма identity-hash кодов),
поэтому JIT не может выбросить измеряемый код. Стоимость Blackhole (~3–6 нс)
входит в overhead и вычитается — на сравнение библиотек не влияет.

### 4.6 Смешанный сценарий (2 потока на 1 ядре)

[`core/ConcurrentPair.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/ConcurrentPair.kt:19) —
фиксированный пул из 2 потоков (читатель/писатель), на каждый батч оба потока
выполняют по `pairsPerBatch` операций, замеряется суммарное время батча;
латентность на операцию = время батча / (2×pairs). На одном закреплённом ядре
потоки реально конкурируют за CPU — это и есть честная «одновременность».

### 4.7 Статистика и отчёт

- [`core/Statistics.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Statistics.kt:1):
  перцентили (nearest-rank), mean/stdev, CDF, гистограмма (полный диапазон
  min…max, 20 бинов), таймсерия.
- [`core/Reporter.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Reporter.kt:1):
  `report.json` (машиночитаемо), `report.html` (все таблицы + все графики,
  включая наложения и столбцы p50/p99), `report.md`, `raw/*.csv`.
- [`core/SvgCharts.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/SvgCharts.kt:1):
  SVG-графики без внешних библиотек.

### 4.8 Требования задания → где реализовано

| Требование | Где |
|---|---|
| 1. ≥100 испытаний | `RunConfig.samples` (default 1000), `validate()` |
| 2. warmup/teardown | фазы в `BenchmarkRunner.run()` |
| 3. свой инструмент | весь `core/` без JMH |
| 4. 1 ядро | `--cpuset-cpus` в `run-benchmark.sh` |
| 5. 3 сценария | `Scenario` + `JsonCases` |
| 6. воспроизводимость | отдельный docker-образ на прогон, `Environment.snapshot()` |
| 7. overhead | `measureOverheadSingle/Concurrent` |
| 8. отчётность | `Reporter` (json/html/md, графики, raw) |

---

## 5. Выводы по графикам и почему бывают выбросы

### 5.1 Как читать (кратко)

- **Временной ряд**: ось X — номер сэмпла, Y — нс/операция. Ровная линия =
  стабильно; иглы = внешние паузы; шкала полная (min…max), ничего не обрезается.
- **CDF**: левее/круче = быстрее и стабильнее; пологий хвост вправо = есть
  медленная подгруппа сэмплов.
- **Гистограмма**: колокол = один режим; две моды = быстрый и медленный пути;
  длинный хвост = редкие выбросы.
- **Столбцы p50/p99**: три группы (read/write/mixed) × два столбца (Jackson/kotlinx) —
  сравнение «бок о бок».
- Подробная шпаргалка по чтению: [`docs/lr3-charts-reading.md`](lr3-charts-reading.md:1).

### 5.2 Типичные результаты (из контрольных прогонов)

- **kotlinx.serialization быстрее во всех сценариях** (медиана и p99):
  `read` Δ ≈ −26…−42%, `write` Δ ≈ −5…−41%, `mixed` Δ ≈ −28…−60%.
- Причина: Jackson использует runtime-рефлексию и кэширование `ObjectMapper`
  (шире разброс, дороже первый вызов), kotlinx — кодогенерацию (узкий стабильный
  хвост). В `write` разница меньше — обе библиотеки пишут через буферы.
- В `mixed` обе деградируют из-за планировщика на 1 ядре, но kotlinx стабильнее.

### 5.3 Почему бывают частые выбросы (чек-лист)

1. **STW-паузы GC** — safepoint останавливает все потоки; на 1 ядре пауза
   выбивает 1–2 сэмпла в 3–10+ раз. (В контейнере `-XX:+UseSerialGC` — паузы
   предсказуемее, но не исчезают.)
2. **JIT-компиляция на лету** — C2 компилирует горячие методы в фоне; во время
   компиляции/деоптимизации замеры «плавают». Прогрев + settle снижают, но не
   исключают.
3. **Планировщик ОС / тики таймера** — на 1 ядре любая фоновая активность
   (irq, cron, соседний контейнер) попадает в замеры.
4. **Page faults / TLB** — первые обращения к страницам кучи после GC-перемещений.
5. **Конкуренция в `mixed`** — два потока на одном ядре делят время; разброс
   всегда шире.
6. **Стартовый переходный процесс** — одноразовый всплеск на первых сэмплах
   measure (GC после warmup, установка JIT-кода на safepoint); лечится
   `settle` + `drop`, а не «ещё большим прогревом».

### 5.4 Корректные и некорректные выводы

- ✅ «В этом прогоне на этой машине медиана чтения у kotlinx меньше на Δ%».
- ✅ «Разброс (p99−p50) у метода A заметно меньше — хвост стабильнее».
- ❌ «Библиотека A быстрее в N раз» на основании одного `max`.
- ❌ Перенос абсолютных значений на другую машину/ядро/версию JVM без повторного прогона.

---

## 6. Другие важные аспекты

### 6.1 Воспроизводимость

- Каждый прогон — **отдельный docker-образ** и уникальный `RUN_ID`
  (`YYYYMMDD-HHMMSS`); результаты в `benchmark/build/reports/benchmark/<RUN_ID>/`,
  копия отчёта и графиков — `docs/lr3-charts/<RUN_ID>/` и `docs/lr3-charts/latest/`.
- Фиксированные JVM-флаги (`-Xms/-Xmx 256m`, `-XX:+UseSerialGC`) — одинаковые
  для Gradle и Docker.
- Снимок окружения (`Environment.snapshot()`) попадает в отчёт: JVM, ОС, CPU,
  `cpusAllowedList` (доказательство 1 ядра), признак контейнера.

### 6.2 Честность сравнения

- Обе библиотеки работают с **одними и теми же данными**:
  [`cases/BenchmarkPayloads.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/cases/BenchmarkPayloads.kt:10)
  (`fixtureJson` для чтения, `sample()` для записи).
- Одинаковые фазы и число операций; overhead вычитается (net median).
- Кейсы описаны в [`cases/JsonCases.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/cases/JsonCases.kt:28):
  `read/write/mixed` × `jackson/kotlinx`.

### 6.3 Как запускать

```bash
# Локально через Gradle (из каталога vidayapi):
./gradlew :benchmark:run --args="--samples 1000 --warmup 5000 --settle 200 --drop-samples 10"

# «Правильный» прогон в Docker на 1 ядре:
./scripts/run-benchmark.sh          # переменные: RUN_ID, CPUSET, SAMPLES, WARMUP, SETTLE, DROP, ...
```

### 6.4 Ограничения метода

- 1 ядро + SerialGC + фиксированная куча — это **не** «максимальная
  производительность», а **стабильные условия для сравнения**.
- Абсолютные цифры зависят от машины; сравнивать методы можно только в рамках
  одного прогона.
- `mixed` измеряет не «параллельную скорость», а честную конкуренцию двух
  потоков на одном ядре.

### 6.5 Карта файлов

| Файл | Роль |
|---|---|
| [`benchmark/src/main/kotlin/com/vidayapi/benchmark/Main.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/Main.kt:1) | точка входа, порядок прогона, консольная сводка |
| [`core/BenchmarkRunner.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/BenchmarkRunner.kt:1) | конфигурация, фазы, батчи, overhead, drop |
| [`core/BenchmarkCase.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/BenchmarkCase.kt:1) | паспорт кейса, фазы, результат |
| [`cases/JsonCases.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/cases/JsonCases.kt:1) | 6 кейсов: read/write/mixed × jackson/kotlinx |
| [`cases/BenchmarkPayloads.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/cases/BenchmarkPayloads.kt:1) | общие данные для обеих библиотек |
| [`core/Statistics.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Statistics.kt:1) | перцентили, CDF, гистограмма, таймсерия |
| [`core/SvgCharts.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/SvgCharts.kt:1) | отрисовка SVG-графиков |
| [`core/Reporter.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Reporter.kt:1) | report.json/html/md, raw CSV, сравнение |
| [`core/Blackhole.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Blackhole.kt:1) | защита от dead-code elimination |
| [`core/ConcurrentPair.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/ConcurrentPair.kt:1) | 2 потока для mixed |
| [`core/Environment.kt`](../benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Environment.kt:1) | снимок окружения (1 ядро) |
| [`scripts/run-benchmark.sh`](../scripts/run-benchmark.sh:1) | docker-запуск на 1 ядре |
| [`docker/benchmark/Dockerfile`](../docker/benchmark/Dockerfile:1) | образ прогона, JVM-флаги |
| [`benchmark/build.gradle.kts`](../benchmark/build.gradle.kts:1) | зависимости и JVM-аргументы |
| [`docs/lr3-charts-reading.md`](lr3-charts-reading.md:1) | как читать графики (подробно) |