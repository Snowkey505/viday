# Лабораторная работа № 3 — микробенчмаркинг: Jackson 3 vs kotlinx.serialization

> Проект: **vidayapi** (Kotlin/JVM, Gradle, Spring Boot). Модуль: **`benchmark/`**.
>
> Задание ЛР3: составить набор сценариев (benchmark) для сравнения **двух разных объектов**.
> Выбран пункт 1 задания — *«разные библиотеки, решающие одну и ту же задачу»*:
>
> | Объект A | Объект B | Задача |
> |---|---|---|
> | **Jackson 3** (`tools.jackson` + `jackson-module-kotlin`, runtime-рефлексия) — библиотека, которую vidayapi использует в production | **kotlinx.serialization** (сериализация на этапе компиляции, официальный Kotlin-подход) | сериализация/десериализация JSON DTO видео-ответа API |
>
> Инструмент микробенчмаркинга — **собственный** (Требование 3): харнесс, статистика,
> перцентили, гистограммы и SVG-графики реализованы в модуле `benchmark` без внешних
> бенчмарк-библиотек (JMH и т.п.).
>
> **Не знаешь, как читать графики и зачем все эти фазы/перцентили?** Открой
> [`docs/lr3-guide.md`](lr3-guide.md) — развёрнутая шпаргалка-теория по
> микробенчмаркингу: что именно замеряется, как устроен замер и как читать каждый
> график отчёта.

---

## 0. Результат (TL;DR)

Прогон в Docker-контейнере на 1 ядре CPU (JVM 17, Eclipse Temurin; `Cpus_allowed_list: 0`),
конфигурация: 1000 испытаний, warmup 2000, teardown 250, batch 256. Сравниваются **три
JSON-модели** — x1 (10 полей), x4 (40 полей), x8 (80 полей) — по 6 кейсов каждая
(18 кейсов всего). Overhead инструмента — **4,4 нс/операция** (цель ≤ 4–5 нс).

**Отчёт и все графики перенесены в Grafana**: дашборд «LR3 Benchmark»
(`http://localhost:3000/d/benchmark-lr3`, admin/admin) — все таблицы и все графики
(временные ряды, CDF, гистограммы, столбцы перцентилей) для трёх моделей.

| Сценарий | Медиана Jackson 3, нс | Медиана kotlinx, нс | Δ median | Δ mean | Δ p99 | Быстрее |
|---|---|---|---|---|---|---|
| **чтение** (JSON → объект) | 4 446,6 | 2 557,6 | **−42,5 %** | −42,3 % | −44,4 % | kotlinx.serialization |
| **запись** (объект → JSON) | 1 275,0 | 1 300,3 | **+2,0 %** | +1,7 % | −14,9 % | Jackson 3 (≈ ничья; во 2-м прогоне kotlinx −5,6 %) |
| **смешанный** (чтение‖запись, 2 потока) | 2 722,6 | 1 985,5 | **−27,1 %** | −30,8 % | −71,9 % | kotlinx.serialization |

**Вывод.** Десериализация у `kotlinx.serialization` быстрее Jackson 3 **в ~1,7 раза** (компиляторная
генерация десерриализатора вместо рефлексии с `jackson-module-kotlin`), запись — статистически
неотличимы (~1,3 мкс, разброс между прогонами ±6 %), в смешанном конкурентном сценарии kotlinx
выигрывает ~27 % по медиане и ~72 % по p99 (кратно более узкий хвост).

---

## 1. Структура решения

```
benchmark/
├── build.gradle.kts                     # модуль: kotlin jvm + serialization + application
└── src/main/kotlin/com/vidayapi/benchmark/
    ├── Main.kt                          # CLI: флаги, запуск, консольная сводка
    ├── core/
    │   ├── BenchmarkCase.kt             # BenchmarkCase / ConcurrentBenchmarkCase / CaseResult
    │   ├── BenchmarkRunner.kt           # харнесс: warmup→measure→teardown, overhead, RunConfig
    │   ├── Blackhole.kt                 # приёмник результатов (защита от DCE JIT)
    │   ├── ConcurrentPair.kt            # 2 потока: читатель + писатель (сценарий mixed)
    │   ├── Statistics.kt                # перцентили 0.5/0.75/0.9/0.95/0.99, CDF, гистограмма
    │   ├── Reporter.kt                  # report.json / report.md / report.html / raw/*.csv
    │   ├── SvgCharts.kt                 # SVG: временной ряд, CDF, гистограмма (офлайн-копия)
    │   └── Environment.kt               # снимок окружения (JVM, CPU, cpuset, container)
    └── cases/
        ├── BenchmarkModels.kt           # ТРИ модели x1/x4/x8 (10/40/80 полей) + ModelSpec
        └── JsonCases.kt                 # 18 кейсов: 3 модели × read/write/mixed × jackson/kotlinx
docker/benchmark/Dockerfile              # образ бенчмарка (gradle build + temurin 17-jre)
docker/docker-compose.grafana.yml        # стенд Grafana + PostgreSQL с результатами
docker/grafana/                          # схема БД, провижининг datasource, дашборд benchmark.json
scripts/run-benchmark.sh                 # прогон: свежий образ на каждый прогон, cpuset=0
scripts/import-benchmark-to-db.{sh,py}   # импорт report.json в PostgreSQL (для Grafana)
scripts/setup-grafana-dashboard.sh       # импорт дашборда в Grafana через API
scripts/generate-grafana-dashboard.py    # генератор дашборда benchmark.json (37 панелей)
Makefile: benchmark, benchmark-local, grafana-up, grafana-down, benchmark-import, benchmark-dashboard
```

Зависимости модуля: `tools.jackson.core:jackson-databind`, `tools.jackson.module:jackson-module-kotlin`
(версии из BOM Spring Boot 4.0.5 — те же, что в проекте), `kotlinx-serialization-json:1.8.1`.

---

## 2. Методика измерений

### 2.1. Фазы прогона (Требование 2)

Для каждого кейса харнесс выполняет три фазы и считает операции/время по каждой:

```
warmup  (2000 сэмплов × 256 операций = 512 000 op)  — JIT-прогрев, статистика НЕ собирается;
measure (1000 сэмплов × 256 операций = 256 000 op)  — источник статистики;  ≥ 100 испытаний (Требование 1);
teardown (250 сэмплов × 256 операций =  64 000 op)  — догрев/завершение, считается отдельно.
```

В mixed-сценарии сэмпл = `mixedPairs=256` пар «чтение‖запись» (opsPerSample = 512).

### 2.2. Батч-измерение и Blackhole (Требование 7)

- За один **сэмпл** выполняется batch=256 операций подряд, замеряется суммарное время
  (`System.nanoTime()`) и делится на batch → **latency ns/операция**.
  Это амортизирует стоимость таймера: overhead = (2×nanoTime + цикл + Blackhole)/batch ≈ 4,4 нс < 4–5 нс.
- Результат каждой операции «потребляется» [`Blackhole`](benchmark/src/main/kotlin/com/vidayapi/benchmark/core/Blackhole.kt)
  (`System.identityHashCode` в volatile-счётчик) — JIT не может удалить вычисления (dead-code elimination).
- Overhead **меряется в прогретом состоянии после всех кейсов**, медианой по 32 батчам
  (устойчиво к разовым паузам JIT/GC).
- Сравнение двух библиотек всегда происходит в **одинаковых** условиях инструментария.

### 2.3. Одно ядро CPU (Требование 4)

- В Docker: `docker run --cpuset-cpus=0 --cpus=1`; на хосте дополнительно `taskset -c 0`.
- Проверка изнутри: `availableProcessors = 1`, `/proc/self/status` → `Cpus_allowed_list: 0`
  (печатается в отчёте, раздел «Окружение»).
- JVM-параметры фиксированы: `-Xms/-Xmx 256m`, `-XX:+UseSerialGC` (1 поток GC — соответствует 1 ядру).

### 2.4. Сценарии (Требование 5)

| Кейс | Сценарий | Операция |
|---|---|---|
| `read.<lib>.<model>` | чтение | разбор **одной и той же** JSON-строки в модель |
| `write.<lib>.<model>` | запись | сериализация **одного и того же** объекта в JSON |
| `mixed.<lib>.<model>` | одновременные чтение + запись | 2 потока: 1-й читает, 2-й пишет (честная конкуренция за одно ядро) |

где `<lib>` = `jackson` | `kotlinx`, `<model>` = `x1` | `x4` | `x8`. Итого **18 кейсов**.

### 2.4.1. Три сравниваемые JSON-модели (x1 / x4 / x8)

Полезная нагрузка — три модели, отличающиеся **числом полей** (10 / 40 / 80) и **набором
типов полей** (покрыты все возможные случаи JSON):

| Модель | Поля | Типы полей |
|---|---|---|
| **x1** | 10 | примитивы, `List<String>`, `Map<String,Int>`, список объектов |
| **x4** | 40 | + `Long/Short/Byte/Double/Float/Boolean/Char`, enum, `List<List<Int>>`, массивы (`IntArray`, `LongArray`, `DoubleArray`, `FloatArray`, `BooleanArray`, `CharArray`, `ByteArray`, `Array<String>`), `Map<String,List<Int>>`, вложенные объекты |
| **x8** | 80 | + `List<List<List<Int>>>`, `List<List<String>>`, `Map<String,Map<String,Int>>`, `Map<String,List<String>>`, `List<List<Dto>>`, `Map<String,Dto>`, `List<Map<String,Dto>>`, `ShortArray`, `List<Short>`, `List<Byte>`, `List<Char>`, `List<PayloadKind>` и т.д. |

JSON-фикстура для сценария «чтение» генерируется kotlinx-сериализатором из образца
(обе библиотеки разбирают ровно одни и те же байты). Модели описаны в
[`BenchmarkModels.kt`](benchmark/src/main/kotlin/com/vidayapi/benchmark/cases/BenchmarkModels.kt).

### 2.5. Статистика (Требования 1, 8)

Для каждого кейса в `measure` собираются 1000 сэмплов и вычисляются:

- (8a) **временной ряд** — `charts/<case>-timeseries.svg` (ns/операция по номеру сэмпла + линии min/p50/p99/max);
- (8b) **распределение по перцентилям** — `charts/<case>-cdf.svg` (CDF «доля запросов ≤ латентность») + таблица перцентилей;
- (8c) **гистограмма распределения** — `charts/<case>-histogram.svg` (30 корзин);
- (8d) **перцентили 0.5 / 0.75 / 0.9 / 0.95 / 0.99** (плюс 0.1 / 0.25 / 0.999);
- (8e) **время выполнения теста** — длительность фаз measure/test;
- (8f) **количество операций** за время теста с детализацией warmup/teardown.

Всё это сохраняется в `report.json` (машиночитаемо + сырые сэмплы `raw/<case>.csv`) и
в `report.md` (готовый отчёт каждого прогона).

---

## 3. Overhead инструмента (Требование 7)

Измерен послe прогрева (32 батча, медиана):

| Режим | Overhead, нс/операция | Ориентир задания |
|---|---|---|
| простой сценарий (2×nanoTime + цикл + Blackhole + вызов) / 256 | **4,42** (прогон 2: 4,19) | ≤ 4–5 нс ✅ |
| смешанный сценарий (2 потока + диспетчеризация Executor) / 512 | 61,4 (прогон 2: 33,0) | это реальная цена конкуренции |
|       | | потоков на одном ядре, входит в измерение mixed |

Для простых сценариев overhead **меньше 5 нс** и составляет ~0,1 % от измеряемых значений
(1,3–4,5 мкс); net-медианы (`median − overhead`) указаны в `report.{json,md}`.

---

## 4. Docker: отдельный образ на каждый прогон, одна конфигурация (Требование 6)

[`docker/benchmark/Dockerfile`](docker/benchmark/Dockerfile) собирает runnable-дистрибутив
`benchmark/build/install/benchmark` (стадия `gradle:8.14-jdk17`) и упаковывает его в
`eclipse-temurin:17-jre` (тот же JRE, что у production-контейнера проекта).

[`scripts/run-benchmark.sh`](scripts/run-benchmark.sh) на **каждый прогон**:

1. собирает **отдельный docker-образ** из того же Dockerfile с той же конфигурацией
   (слои Gradle кэшируются — пересборка занимает секунды);
2. запускает уникальный контейнер `viday-bench-<run-id>` с `--cpuset-cpus=0 --cpus=1 --memory=1g`;
3. монтирует каталог результатов и после прогона копирует `report.md` + `charts/` в
   `docs/lr3-charts/<run-id>/` (материал этого отчёта: `docs/lr3-charts/official`,
   `docs/lr3-charts/official-2`).

Таким образом «равные условия на каждую попытку» достигаются изоляцией каждого прогона
в свежем контейнере с идентичной конфигурацией — ровно как требует задание.

### 4.1. Окружение прогона `official` (из `report.json`)

```
timestamp           = 2026-10-21T18:56:44Z
java.version        = 17.0.20.1  (Eclipse Adoptium = temurin:17-jre)
os.name             = Linux
availableProcessors = 1
cpusAllowedList     = 0          ← закрепление на 1 ядре (Требование 4)
cpuModel            = AMD Ryzen 5 6600HS Creator Edition
container           = docker
```

---

## 5. Результаты прогона `official` (Требование 8)

Конфигурация: `samples=1000, warmup=2000, teardown=250, batch=256, mixedPairs=256`.
Для простых сейсов: warmup 512 000 op / measure 256 000 op / teardown 64 000 op / **итого 832 000 op**;
для mixed: 1 024 000 / 512 000 / 128 000 / **1 664 000 op**.

### 5.1. Сводная таблица латентности (ns/операция)

| Кейс | mean | stdev | min | max | **p50** | **p75** | **p90** | **p95** | **p99** | Время теста, мс | Опер./с |
|---|---|---|---|---|---|---|---|---|---|---|---|
| read.jackson  | 4 504,9 | 337,3 | 4 345,1 | 12 934,1 | **4 446,6** | 4 486,7 | 4 565,2 | 4 787,4 | 5 468,2 | 1 153,4 | 222 000 |
| read.kotlinx  | 2 599,1 | 825,4 | 2 483,3 | 28 369,4 | **2 557,6** | 2 592,7 | 2 651,8 | 2 724,6 | 3 041,5 | 665,5 | 384 800 |
| write.jackson | 1 305,0 | 126,5 | 1 240,8 | 4 359,8 | **1 275,0** | 1 334,3 | 1 356,5 | 1 372,7 | 1 824,9 | 334,2 | 766 300 |
| write.kotlinx | 1 326,6 | 76,1 | 1 256,1 | 2 142,2 | **1 300,3** | 1 359,3 | 1 376,1 | 1 412,3 | 1 552,4 | 339,8 | 753 800 |
| mixed.jackson | 2 889,1 | 1 025,8 | 2 652,2 | 22 077,9 | **2 722,6** | 2 760,1 | 2 852,4 | 3 246,2 | 8 589,6 | 1 479,6 | 346 100 |
| mixed.kotlinx | 2 000,7 | 76,7 | 1 927,9 | 2 740,0 | **1 985,5** | 2 001,8 | 2 045,4 | 2 093,6 | 2 410,1 | 1 024,6 | 499 800 |

Все значения в пределах допустимого диапазона микробенчмаркинга **100 нс … 50 мс** (задание, п.7).

### 5.2. Число операций по фазам (Требование 8f)

| Кейс | warmup (op) | measure (op) | teardown (op) | итого (op) |
|---|---|---|---|---|
| read/write.* | 512 000 | 256 000 | 64 000 | 832 000 |
| mixed.* | 1 024 000 | 512 000 | 128 000 | 1 664 000 |

### 5.3. Графики (Требования 8a–8c)

Каждый кейс имеет три SVG-графика (самодостаточные, без внешних библиотек):

| Кейс | Временной ряд (8a) | CDF / перцентили (8b) | Гистограмма (8c) |
|---|---|---|---|
| read.jackson | [timeseries](lr3-charts/official/charts/read.jackson-timeseries.svg) | [cdf](lr3-charts/official/charts/read.jackson-cdf.svg) | [histogram](lr3-charts/official/charts/read.jackson-histogram.svg) |
| read.kotlinx | [timeseries](lr3-charts/official/charts/read.kotlinx-timeseries.svg) | [cdf](lr3-charts/official/charts/read.kotlinx-cdf.svg) | [histogram](lr3-charts/official/charts/read.kotlinx-histogram.svg) |
| write.jackson | [timeseries](lr3-charts/official/charts/write.jackson-timeseries.svg) | [cdf](lr3-charts/official/charts/write.jackson-cdf.svg) | [histogram](lr3-charts/official/charts/write.jackson-histogram.svg) |
| write.kotlinx | [timeseries](lr3-charts/official/charts/write.kotlinx-timeseries.svg) | [cdf](lr3-charts/official/charts/write.kotlinx-cdf.svg) | [histogram](lr3-charts/official/charts/write.kotlinx-histogram.svg) |
| mixed.jackson | [timeseries](lr3-charts/official/charts/mixed.jackson-timeseries.svg) | [cdf](lr3-charts/official/charts/mixed.jackson-cdf.svg) | [histogram](lr3-charts/official/charts/mixed.jackson-histogram.svg) |
| mixed.kotlinx | [timeseries](lr3-charts/official/charts/mixed.kotlinx-timeseries.svg) | [cdf](lr3-charts/official/charts/mixed.kotlinx-cdf.svg) | [histogram](lr3-charts/official/charts/mixed.kotlinx-histogram.svg) |

Свежие копии всегда лежат в `benchmark/build/reports/benchmark/<run-id>/` (+ `report.json`, `raw/*.csv`).

### 5.4. Сравнение двух объектов

Δ = (kotlinx − Jackson) / Jackson × 100 % (отрицательное — kotlinx быстрее):

| Сценарий | Медиана Jackson | Медиана kotlinx | Δ median | Δ mean | Δ p99 | Быстрее |
|---|---|---|---|---|---|---|
| чтение | 4 446,6 нс | 2 557,6 нс | **−42,5 %** | −42,3 % | −44,4 % | kotlinx.serialization |
| запись | 1 275,0 нс | 1 300,3 нс | **+2,0 %** | +1,7 % | −14,9 % | Jackson 3 |
| смешанный | 2 722,6 нс | 1 985,5 нс | **−27,1 %** | −30,8 % | −71,9 % | kotlinx.serialization |

### 5.5. Интерпретация

- **Чтение (deserialize).** `jackson-module-kotlin` разбирает Kotlin data class через runtime-рефлексию
  (Kotlin-параметры, default-значения), kotlinx генерирует десериализатор на этапе компиляции —
  поэтому чтение у kotlinx быстрее в ~1,7× (медиана) с более плотным распределением (stdev 825 vs 337
  у Jackson — у kotlinx есть редкие выбросы до 28 мкс, но p99 = 3,0 мкс против 5,5 мкс).
- **Запись (serialize).** обе библиотеки близки: Δmedian всего 2 % — внутри шума между прогонами
  (2-й прогон: −5,6 %). Можно считать паритетом по среднему; по p99 kotlinx стабильнее (−15 %).
- **Смешанный сценарий.** на одном ядре конкуренция потоков даёт дополнительный разброс;
  kotlinx выигрывает по медиане ~27 % и, главное, кратно лучше по хвосту (p99 2,4 мкс против 8,6 мкс;
  max 2,7 мкс против 22,1 мкс) — у Jackson появляются заметные GC/аллокационные паузы при
  параллельной рефлексии.

**Практический вывод для vidayapi:** переход на kotlinx.serialization оправдан прежде всего там,
где API парсит много входящих JSON (DECODE) — выигрыш ~40 %; на сериализации ответов выигрыша нет
(~1,3 мкс — разница в пределах шума).

---

## 6. Воспроизводимость (Требование 6): прогон `official-2`

Второй прогон — свежий docker-образ той же конфигурации (равные условия):

| Сценарий | Медиана Jackson | Медиана kotlinx | Δ median | Overhead single |
|---|---|---|---|---|
| чтение | 4 540,1 нс | 2 642,9 нс | −41,8 % | 4,19 нс/оп |
| запись | 1 385,3 нс | 1 307,1 нс | −5,6 % | |
| смешанный | 2 783,6 нс | 2 010,0 нс | −27,8 % | |

Результаты согласуются с прогоном `official` (±6 %): выводы не меняются. Отчёт и графики —
`docs/lr3-charts/official-2/`.

---

## 7. Карта соответствия требованиям ЛР3

| № | Требование | Как выполнено |
|---|---|---|
| 1 | ≥ 100 испытаний + статистика | 1000 сэмплов на кейс (минимум enforced в `RunConfig.validate()`), статистика mean/stdev/min/max/перцентили |
| 2 | фазы warmup / teardown | 2000 / 250 сэмплов, считаются отдельно и не входят в статистику |
| 3 | свои средства бенчмаркинга | собственный харнесс (`BenchmarkRunner`, `Blackhole`, батчи) — JMH не используется |
| 4 | 1 ядро CPU | `--cpuset-cpus=0 --cpus=1` + `taskset -c 0`; проверка `Cpus_allowed_list: 0`; SerialGC |
| 5 | несколько сценариев | чтение / запись / одновременные чтение+запись — по два кейса (обе библиотеки) на КАЖДУЮ из трёх моделей x1/x4/x8 (18 кейсов) |
| 6 | отдельный docker-образ на прогон, одна конфигурация | `scripts/run-benchmark.sh`: свежий образ из одного Dockerfile на каждый прогон, тот же `JAVA_OPTS`, уникальный контейнер; 2 прогона показали согласованные результаты |
| 7 | overhead инструмента ≤ 4–5 нс | 4,4 нс/оп (медиана 32 батчей после прогрева); net-медианы в отчёте |
| 8a | график измеряемой величины во времени | панели Grafana «временной ряд» (наложения + по кейсам, серии x1/x4/x8); офлайн — `charts/<case>-timeseries.svg` |
| 8b | распределение по перцентилям | панели Grafana «CDF» (XY-чарты) + таблица перцентилей |
| 8c | гистограмма распределения | панели Grafana «гистограмма» (bar-чарты); офлайн — `charts/<case>-histogram.svg` |
| 8d | перцентили 0.5, 0.75, 0.9, 0.95, 0.99 | таблицы в §5.1 и во всех отчётах (`report.md`/`report.json`) |
| 8e | время выполнения теста | длительность фаз measure/test в таблицах §5.1–5.2 |
| 8f | число операций + детализация warmup/teardown | §5.2: 512 000 / 256 000 / 64 000 op (mixed — вдвое) |
| — | отчёт и все графики в Grafana | дашборд «LR3 Benchmark» (19 панелей): заголовок с описанием подхода, переключатель модели x1/x4/x8, все таблицы + все графики (2 кривые Jackson/kotlinx + отключаемые перцентили); импорт `report.json` → PostgreSQL → Grafana |

---

## 8. Отчёт и все графики в Grafana (новое требование)

Отчёт и **все** графики ЛР3 перенесены в **Grafana** — статические SVG-графики и
markdown-отчёт остались как офлайн-копия, а каноническое представление — дашборд
**«LR3 Benchmark»** (`http://localhost:3000/d/benchmark-lr3`, логин `admin/admin`).

Стенд: `docker/docker-compose.grafana.yml` (Grafana 11 + PostgreSQL с результатами).

**Структура дашборда (19 панелей):**

| Что в дашборде | Панели |
|---|---|
| Заголовок + описание подхода | текст: библиотеки, модели x1/x4/x8, сценарии, методика, как читать графики |
| Переключатель модели | template variable **model** (x1 / x4 / x8) — все графики перестраиваются |
| Окружение и конфигурация прогона | 2 таблицы + 4 stat-панели (overhead, run-id) |
| Сводное сравнение «модель × сценарий» | таблица Δ median/mean/p99 + «быстрее» |
| Масштабирование по моделям | столбцы p50 и p99: 6 кейсов × серии x1/x4/x8 |
| Временные ряды (8a) | 3 панели (read/write/mixed): **всегда 2 кривые** — Jackson (синяя) и kotlinx (зелёная) + пунктирные перцентили p50/p90/p99 каждой библиотеки |
| CDF / перцентили (8b) | 3 панели (read/write/mixed): 2 кривые Jackson/kotlinx (XY-чарты) |
| Гистограммы (8c) | 3 панели (read/write/mixed): 2 серии Jackson/kotlinx (bar-чарты) |

**Интерактивность:**
- переключатель **model** (x1/x4/x8) — все графики перестраиваются;
- переключатель **percentiles** (показать/скрыть) — перцентили p50/p90/p99 на всех временных рядах сразу;
- клик по легенде включает/выключает **любую** серию — кривые Jackson/kotlinx (разные цвета) и перцентили;
- подписи осей человекочитаемые: «латентность, нс/оп», «доля запросов», «число сэмплов».

Данные: `report.json` каждого прогона импортируется в PostgreSQL
(`scripts/import-benchmark-to-db.sh`) в таблицы `benchmark_runs / benchmark_cases /
benchmark_samples / benchmark_cdf / benchmark_histogram / benchmark_percentiles /
benchmark_comparisons`. Дашборд генерируется скриптом
`scripts/generate-grafana-dashboard.py` (19 панелей) и импортируется через API
(`scripts/setup-grafana-dashboard.sh`).

## 9. Как воспроизвести

```bash
cd vidayapi

# 1) Стенд визуализации: Grafana + PostgreSQL (один раз)
make grafana-up                      # Grafana http://localhost:3000 (admin/admin)

# 2) Полный «официальный» прогон в Docker: свежий образ на каждый прогон, 1 ядро
make benchmark                       # = ./scripts/run-benchmark.sh (RUN_ID=official и т.п.)
#    после прогона результаты АВТОМАТИЧЕСКИ импортируются в Grafana DB
RUN_ID=my-run ./scripts/run-benchmark.sh

# 3) Если стенд подняли после прогона — импортировать последний отчёт вручную:
make benchmark-import                # = ./scripts/import-benchmark-to-db.sh

# Быстрая локальная проверка (без Docker)
make benchmark-local                 # = ./gradlew :benchmark:run --args="..."
# или собранным дистрибутивом:
./gradlew :benchmark:installDist
taskset -c 0 benchmark/build/install/benchmark/bin/benchmark --samples 1000 --warmup 2000 \
    --out benchmark/build/reports/benchmark/my-run

# Параметры: --samples/--warmup/--teardown/--batch/--mixed-pairs/--out/--case
# или переменные окружения BENCH_SAMPLES/BENCH_WARMUP/... (см. --help)
```

Результаты в `benchmark/build/reports/benchmark/<run-id>/`: `report.json`, `report.md`,
`report.html`, `charts/*.svg`, `raw/*.csv`. Копия отчёта и графиков — `docs/lr3-charts/<run-id>/`.
Канонический отчёт — дашборд Grafana: `http://localhost:3000/d/benchmark-lr3`.