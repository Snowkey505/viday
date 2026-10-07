# Лабораторная работа №2 — тестирование сервиса vidayapi (и мобильного клиента viday)

> Документ описывает, что сделано по ЛР2: тестовый стенд, integration-тесты,
> E2E-тест, запуск всех тестов в Docker-контейнере из CI/CD (GitHub Actions),
> имитацию E2E-действий curl'ом с захватом трафика (tcpdump), откат хранилища,
> повторяемость прогонов и тесты мобильного приложения на устройстве.
>
> Продолжение отчётности ЛР1 (`docs/lr1.md`): здесь добавлены **новые** слои
> тестирования, при этом тесты ЛР1 (`./gradlew test`, `@Tag("offline")` и др.)
> **не изменялись** и включены в общий конвейер.
>
> Теория (что такое unit/integration/e2e на примере vidayapi) и пошаговый гайд по
> организации CI/CD для нового пустого проекта на GitHub — в
> [`docs/testing-theory.md`](testing-theory.md).

---

## 1. Состав работ (кратко)

| Что | Где | Статус |
|---|---|---|
| Тестовый стенд (отдельные инстансы PostgreSQL/Redis/MinIO) | [`docker/docker-compose.test.yml`](docker/docker-compose.test.yml) | ✅ развёрнут и проверен |
| Integration-тесты доступа к данным + бизнес-логики (11 шт.) | [`infrastructure/src/test/kotlin/com/vidayapi/integration/`](../infrastructure/src/test/kotlin/com/vidayapi/integration/) | ✅ 11/11 green |
| E2E-тест MVP-сценария поверх реального HTTP | [`presentation/src/test/kotlin/com/vidayapi/e2e/DemoMvpE2ETest.kt`](../presentation/src/test/kotlin/com/vidayapi/e2e/DemoMvpE2ETest.kt) | ✅ PASSED |
| Конвейер unit → integration → e2e + отчёты Allure | [`docker/test-runner/pipeline.sh`](docker/test-runner/pipeline.sh) | ✅ `PIPELINE: OK` |
| Контейнер запуска тестов | [`docker/test-runner/Dockerfile`](docker/test-runner/Dockerfile) | ✅ образ собирается, этапы проходят |
| CI/CD (GitHub Actions) | [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) | ✅ 8 джоб `build → unit → integration → e2e → device → report`; образы собираются один раз в ghcr.io; JUnit-аннотации в PR |
| Откат хранилища после integration/e2e (в т.ч. при сбое) | [`scripts/reset-test-db.sh`](scripts/reset-test-db.sh) | ✅ работает |
| Повторяемость: integration ×2 без изменения результатов | этап `repeat` в `pipeline.sh` | ✅ `repeat: OK` |
| Имитация E2E средством отправки запросов (curl) | [`scripts/e2e-curl.sh`](scripts/e2e-curl.sh) | ✅ |
| Захват трафика (tcpdump) | [`scripts/traffic-capture.sh`](scripts/traffic-capture.sh) | ✅ + пример [`traffic/examples/viday-e2e-sample.pcap`](traffic/examples/viday-e2e-sample.pcap) |
| Мобильное приложение: тесты на устройстве | [`../viday/app/src/androidTest/java/com/snowkey/viday/AndroidApiFlowTest.kt`](../../viday/app/src/androidTest/java/com/snowkey/viday/AndroidApiFlowTest.kt) | ✅ (в CI — эмулятор с API из bootJar-артефакта) |
| Отчёт Allure с трендами между запусками | `build/reports/allure-report-ci/index.html` | ✅ генерируется всегда |

---

## 2. Карта соответствия требованиям и заданиям

### Задания

| № | Задание | Как выполнено |
|---|---|---|
| 1 | Развернуть тестовое окружение (стенд) на машине студента | [`docker/docker-compose.test.yml`](docker/docker-compose.test.yml) — `make stand-up` поднимает **отдельный** стенд (порты 55432/56380/59002-3), не пересекаясь с dev-стеком и работающими сервисами других проектов |
| 2 | Integration-тесты для компонентов доступа к данным и бизнес-логики | 10 файлов-сценариев доступа к данным (`UserSaveFindIT`, `PlaylistAddContentIT`, …) + [`BusinessFlowIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/BusinessFlowIT.kt) — бизнес-логика; все на реальном PostgreSQL |
| 3 | E2E-тест демонстрационного сценария (MVP при защите) | [`DemoMvpE2ETest.kt`](../presentation/src/test/kotlin/com/vidayapi/e2e/DemoMvpE2ETest.kt) — весь сценарий «креатор → видео → плейлист → подписчик → лента» через реальный HTTP |
| 4 | Запуск тестов ЛР1 + новых в CI/CD | [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml): джобы `build → unit → integration → e2e → device → report`; ЛР1-тесты входят в джобу `unit`; в `build` образы (test-runner/postgres) собираются **один раз** и публикуются в GitHub Container Registry (ghcr.io) |
| 5 | Проимитировать E2E-действия средством отправки запросов + снять лог средством захвата трафика | [`scripts/e2e-curl.sh`](scripts/e2e-curl.sh) (curl) + [`scripts/traffic-capture.sh`](scripts/traffic-capture.sh) (tcpdump внутри контейнера приложения); готовый capture для Wireshark — [`traffic/examples/viday-e2e-sample.pcap`](traffic/examples/viday-e2e-sample.pcap); в одной docker-сети — `make ci-e2e-curl` (контейнеры API + postgres + e2e-runner) |

### Требования

| № | Требование | Как выполнено |
|---|---|---|
| 1 | Запуск тестов в виде отдельного docker-контейнера, вызываемого из CI/CD | [`docker/test-runner/Dockerfile`](docker/test-runner/Dockerfile) + точка входа [`pipeline.sh`](docker/test-runner/pipeline.sh); в GitHub Actions каждая джоба — `docker run` этого образа (образ собирается **один раз** в джобе `build` и пушится в ghcr.io, остальные джобы — `docker pull`); тестируемое окружение тоже в контейнерах ([`docker-compose.ci.yml`](docker/docker-compose.ci.yml), включая postgres со схемой и API) |
| 2 | Integration-тесты взаимодействуют с хранилищем данных | Все IT выполняют SQL на реальном PostgreSQL стенда (HikariCP + JdbcTemplate), часть сценария — MinIO (файловое хранилище) и Redis (кэш) в E2E |
| 3 | Для тестов инициализируется отдельный инстанс хранилища | `postgres-test` (55432), `redis-test` (56380), `minio-test` (59002/59003) — отдельные от dev-стека контейнеры |
| 4 | Хранилище поднимается последовательным запуском скриптов; откат состояния до прогона | Init-скрипты [`docker/postgres/init/01..05`](docker/postgres/init) выполняются PostgreSQL последовательно (таблицы → констрейнты → роли/RLS → триггеры → индексы); в CI/на устройстве используется **образ** [`docker/postgres/Dockerfile`](docker/postgres/Dockerfile) со встроенной схемой (идентичен при каждом запуске); откат — [`scripts/reset-test-db.sh`](scripts/reset-test-db.sh) (`TRUNCATE ... RESTART IDENTITY CASCADE`) |
| 5 | Порядок запуска в CI/CD: unit → integration → e2e | Джобы `.github/workflows/ci.yml`: `build → unit → integration → e2e → device → report` (порядок тестовых джоб — unit → integration → e2e через `needs`; `build` — сборка образов/jar, `device` — мобильные тесты, `report` — отчёт); то же в `pipeline.sh` |
| 6 | Тесты запускаются на окружении, развёрнутом в рамках ЛР | Тесты подключаются к стенду ЛР2 по переменным `VIDAY_TEST_*`; в CI стенд поднимается тем же compose-файлом (`docker-compose.ci.yml`) |
| 7 | Мобильное приложение: продемонстрировать запуск тестов на устройстве | [`AndroidApiFlowTest.kt`](../../viday/app/src/androidTest/java/com/snowkey/viday/AndroidApiFlowTest.kt) — инструментированный тест на эмуляторе/устройстве (adb reverse к API стенда); в CI — джоба `device`: эмулятор на KVM, API поднимается `java -jar` из bootJar-артефакта `build-vidayapi`, БД/Redis/MinIO — [`docker-compose.device.yml`](docker/docker-compose.device.yml); если jar недоступен — тест SKIPPED через `assumeTrue` |
| 8 | Падение этапа → последующие не запускаются, но отчёт генерируется; невыполненные помечаются skipped/ignored | `pipeline.sh` останавливается при ошибке (`set -uo pipefail`), но `step_report` выполняется всегда; в GitHub Actions падение джобы скипает зависимые по `needs`, а `report` запускается **всегда** (`if: !cancelled()`) и НЕ перезапускает тесты — Allure генерируется из артефактов предыдущих джоб; пропущенные/упавшие видны как skipped/failed в аннотациях JUnit |
| 9 | Тренды поведения тестов между запусками | История Allure копируется до/после генерации отчёта (`ALLURE_HISTORY_DIR`, `--history-limit 20`); в GitHub Actions история кэшируется между прогонами (`actions/cache`: `vidayapi/allure-history/`) |
| 10 | Принудительный откат хранилища после integration/e2e (в т.ч. при сбое) | `reset-test-db.sh` вызывается **и при успехе, и при ошибке** (см. `step_integration`/`step_e2e` в [`pipeline.sh`](docker/test-runner/pipeline.sh)); приложение-тесты тоже чистят состояние в `@BeforeEach`/`@AfterEach` |
| 11 | Вычитывание очередей после тестов (service bus / message broker) | **N/A** — в проекте нет message broker / шины / очередей (проверено по кодовой базе: только PostgreSQL, Redis как кэш, MinIO как файловое хранилище) |
| 12 | Завершение активных сессий пользователей после тестов | **N/A** — в проекте нет session-store: аутентификация stateless по JWT (токен не хранится на сервере, см. [`JwtTokenProvider.kt`](../infrastructure/src/main/kotlin/com/vidayapi/security/JwtTokenProvider.kt)); «сессии» в Redis хранят только кэш ленты с ключом по userId и автоматически устаревают |
| 13 | GUI не требуется в E2E | E2E выполняется на уровне HTTP API (`java.net.http.HttpClient`), GUI-слой не используется |
| 14 | Integration-тесты запускаются несколько раз подряд с одинаковым результатом | Этап `repeat` прогоняет `:infrastructure:testIntegration` **дважды** с откатом между прогонами — `repeat: OK (identical results, stand intact)`; также стенд переживает повторные прогоны без сбоев |
| 15 | Несколько разработчиков локально одновременно без влияния друг на друга | Стенд параметризуется: `TEST_POSTGRES_PORT` / `TEST_REDIS_PORT` / `TEST_MINIO_PORT` / `-p viday-test-<имя>` — каждый разработчик поднимает изолированный стенд со своими портами и именем проекта (`docker compose -p viday-test-alice ... up -d`) |
| 16 | Тесты должны проходить успешно | ✅ все этапы зелёные (см. раздел 13 «Реальные результаты прогонов») |
| 17 | Рекомендации по структуре файлов тестов | **Один файл = один тест-сценарий** из нескольких шагов act/assert (17a): 10 IT-файлов (`UserSaveFindIT`, `UserDuplicateUsernameIT`, `UserUnknownFindIT`, `UserFollowUnfollowIT`, `UserDeleteIT`, `PlaylistSaveFindIT`, `PlaylistExistsByNameIT`, `PlaylistAddContentIT`, `PlaylistDeleteForbiddenIT`, `PlaylistFindPublicIT`) + `BusinessFlowIT`; arrange один раз в начале/`@BeforeEach` базового класса [`StandPostgresIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/StandPostgresIT.kt) (17b); общие фикстуры — [`IntegrationFixtures.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/IntegrationFixtures.kt); суффиксы: integration — `*IT` (аналог `ITCase` для Kotlin/JUnit5), unit — `*Test`/`*LondonTest`, e2e — `DemoMvpE2ETest` |

---

## 3. Тестовый стенд (Задание 1, Требования 3–4, 15)

### Поднятие стенда

```bash
cd vidayapi
make stand-up            # docker compose -f docker/docker-compose.test.yml up -d
make stand-ps            # проверить healthcheck'и
```

Ожидаемый вывод `make stand-ps`:

```
NAME                  IMAGE                                             SERVICE         STATUS
viday_postgres_test   viday-postgres-test:local (postgres:15-alpine + init)  postgres-test  Up 2 hours (healthy)
viday_redis_test      redis:7-alpine                                    redis-test      Up 2 hours (healthy)
viday_minio_test      bitnamilegacy/minio:2025.7.23-debian-12-r5        minio-test      Up 2 hours (healthy)
```

### Особенности

- **Отдельные инстансы**: порты 55432 (PostgreSQL), 56380 (Redis), 59002/59003 (MinIO) **не пересекаются** с dev-стеком (5432/6379/9000).
- **Имя БД обязательно `viday`**: скрипт `docker/postgres/init/03_roles.sql` выдаёт `GRANT CONNECT ON DATABASE viday` — на другой БД init упадёт.
- **Последовательная инициализация** (Требование 4): образ `postgres:15-alpine` выполняет монтируемые в `/docker-entrypoint-initdb.d` скрипты строго по порядку имён:
  - `01_tables.sql` — генерация таблиц;
  - `02_constraints.sql` — констрейнты/индексы уникальности;
  - `03_roles.sql` — роли приложения + RLS-политики;
  - `04_triggers.sql` — триггеры (обновление `updated_at` и т.п.);
  - `05_indexes.sql` — индексы для типовых запросов.
- **Параллельные разработчики** (Требование 15): любой порт переопределяется переменной, имя проекта — `-p`
  (у файла нет фиксированного `name:`/container_name, поэтому `-p` реально изолирует контейнеры, сети и тома):

  ```bash
  TEST_POSTGRES_PORT=55433 TEST_REDIS_PORT=56381 \
    docker compose -p viday-test-alice -f docker/docker-compose.test.yml up -d
  # затем тесты против своего стенда:
  VIDAY_TEST_JDBC_URL=jdbc:postgresql://localhost:55433/viday ./gradlew :infrastructure:testIntegration
  ```

- Остановка стенда с удалением данных: `make stand-down` (`down -v`).

---

## 4. Integration-тесты (Задание 2, Требования 2, 6, 17)

Все integration-тесты находятся в [`infrastructure/src/test/kotlin/com/vidayapi/integration/`](../infrastructure/src/test/kotlin/com/vidayapi/integration/)
и помечены `@Tag("integration")`. Они подключаются к **реальному PostgreSQL стенда**
(HikariDataSource + JdbcTemplate, пользователь `viday_admin_user` — суперпользователь
стенда, единственный, кому доступен откат `TRUNCATE ... RESTART IDENTITY`).

| Файл | Что проверяет (один файл = один сценарий, Требование 17a) |
|---|---|
| [`StandPostgresIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/StandPostgresIT.kt) | Базовый класс: пул соединений к стенду, `@BeforeEach` — очистка всех бизнес-таблиц (`RESET_SQL`), хелперы `valueOrThrow`/`valueOrNull` |
| [`IntegrationFixtures.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/IntegrationFixtures.kt) | Общие фикстуры: `PlainPasswordEncoder`, комплект репозиториев + хелперы `owner()`/`saveVideo()` |
| [`UserSaveFindIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserSaveFindIT.kt) | save → findByUsername возвращает пользователя с ролью |
| [`UserDuplicateUsernameIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserDuplicateUsernameIT.kt) | Дубликат username → `Error.AlreadyExists` (уникальный констрейнт) |
| [`UserUnknownFindIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserUnknownFindIT.kt) | findByUsername неизвестного → null (не исключение) |
| [`UserFollowUnfollowIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserFollowUnfollowIT.kt) | Подписка/отписка round-trip; повторная подписка → `UserAlreadyFollowed`, отписка без подписки → `NotFound` |
| [`UserDeleteIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserDeleteIT.kt) | deleteById удаляет; повторное удаление → `NotFound` |
| [`PlaylistSaveFindIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistSaveFindIT.kt) | create playlist → findById (INSERT RETURNING id + JOIN access_type) |
| [`PlaylistExistsByNameIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistExistsByNameIT.kt) | existsByNameAndOwnerId; тот же name у другого владельца — не конфликт |
| [`PlaylistAddContentIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistAddContentIT.kt) | addContentToPlaylist → count растёт; PK `(content_id, playlist_id)` → дубликат `PlaylistPositionConflict`; remove работает |
| [`PlaylistDeleteForbiddenIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistDeleteForbiddenIT.kt) | Удаление чужого плейлиста → `Forbidden`, плейлист остаётся |
| [`PlaylistFindPublicIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistFindPublicIT.kt) | findPublicPlaylists возвращает только PUBLIC |
| [`BusinessFlowIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/BusinessFlowIT.kt) | 1 сквозной тест бизнес-логики: регистрация → публикация → плейлист → контент → подписка → доступные плейлисты + инварианты (дубликат имени, чужие права, повторная подписка) |

Всего **11 integration-тестов**, каждый — отдельный файл-сценарий с шагами act/assert
(Требование 17a), arrange — один раз в начале сценария / `@BeforeEach` (17b).

### Запуск

```bash
cd vidayapi
make stand-up
make test-integration          # ./gradlew :infrastructure:testIntegration
```

Ожидаемый вывод (фрагмент):

```
> Task :infrastructure:testIntegration
UserSaveFindIT > save then findByUsername returns persisted user with role() PASSED
...
PlaylistAddContentIT > addContentToPlaylist persists item and increments count() PASSED
BusinessFlowIT > creator publishes to public playlist and follower sees it() PASSED
11 tests completed, 0 failed, 0 skipped
BUILD SUCCESSFUL
```

Переменные окружения (по умолчанию указывают на локальный стенд):

| Переменная | По умолчанию |
|---|---|
| `VIDAY_TEST_JDBC_URL` | `jdbc:postgresql://localhost:55432/viday` |
| `VIDAY_TEST_DB_USER` | `viday_admin_user` |
| `VIDAY_TEST_DB_PASSWORD` | `admin_password_change_me` |
| `VIDAY_TEST_REDIS_HOST/PORT` | `localhost` / `56380` |
| `VIDAY_TEST_MINIO_ENDPOINT` | `http://localhost:59002` |

---

## 5. E2E-тест MVP-сценария (Задание 3, Требование 13)

Файл: [`presentation/src/test/kotlin/com/vidayapi/e2e/DemoMvpE2ETest.kt`](../presentation/src/test/kotlin/com/vidayapi/e2e/DemoMvpE2ETest.kt),
`@Tag("e2e")`, `@SpringBootTest(webEnvironment = RANDOM_PORT)`.

Сценарий (тот самый, что демонстрируется на защите MVP):

1. регистрация креатора (`POST /api/auth/register`);
2. логин → JWT (`POST /api/auth/login`);
3. активация канала (роль `USER → CREATOR`);
4. загрузка видео (multipart) — реальный `UploadVideoUseCase`, но анализатор
   метаданных заменён на `FixedMetadataVideoProcessor` (без внешнего ffprobe);
5. создание публичного плейлиста;
6. добавление контента в плейлист;
7. регистрация подписчика, логин, подписка на креатора;
8. проверка: публичная лента содержит загруженное видео, `GET /api/playlists/available`
   отдаёт плейлист подписчику;
9. проверка безопасности: аноним на защищённый эндпоинт получает 403.

Особенности реализации:

- HTTP-клиент — `java.net.http.HttpClient` (в Spring Boot 4 нет `TestRestTemplate`);
- JSON — Jackson 3 (`tools.jackson`, `at("/field")`/`stringValue()`/`longValue()`);
- состояние БД очищается **до и после** теста через выделенный admin-пул
  (`resetDataSource`), чтобы сценарий был воспроизводим и не зависел от порядка;
- RLS продолжает работать даже под суперпользователем, т.к. фильтры выполняют
  `SET ROLE` для конкретного пользователя.

### Запуск

```bash
make stand-up
make test-e2e                  # ./gradlew :presentation:testE2E
```

Ожидаемый вывод:

```
DemoMvpE2ETest > mvp demo scenario works end-to-end over real HTTP against the test stand() PASSED
BUILD SUCCESSFUL
```

---

## 6. Docker-контейнер запуска тестов (Требование 1)

### Образ

[`docker/test-runner/Dockerfile`](docker/test-runner/Dockerfile):

- база `gradle:8.14-jdk17` (JDK + Gradle уже внутри);
- `apt`: только `postgresql-client` (откат хранилища), `curl jq` (E2E-демо),
  `xz-utils`;
- **Node.js — официальный бинарник** (`tar.xz` → `/usr/local`, без `npm`):
  раньше `apt-get install nodejs npm` тянул webpack/jest/babel (~168 c на джобу)
  и ставился в каждом джобе заново;
- исходный код в образ НЕ копируется (`.dockerignore`): CI и compose монтируют
  его в `/workspace`; в образ попадают только `pipeline.sh` и `scripts/`;
- точка входа — [`docker/test-runner/pipeline.sh`](docker/test-runner/pipeline.sh).

Режимы `pipeline.sh`:

```
full          # весь конвейер (по умолчанию; repeat только здесь)
unit | integration | e2e | report | assemble | repeat
```

- `assemble` — компиляция и bootJar **без тестов** (использует build-стадия CI);
- `report` — Allure из уже лежащих `allure-results*`, стенд и тесты НЕ нужны.

### Пайплайн внутри контейнера

```
pipeline.sh [full|unit|integration|e2e|report|repeat]
```

- Порядок `unit → integration → e2e` (Требование 5);
- падение этапа останавливает последующие (Требование 8), но `step_report`
  выполняется **всегда** (отчёт генерируется даже при сбое);
- после integration/e2e — принудительный откат хранилища `reset-test-db.sh`,
  в т.ч. в ветке ошибки (Требование 10);
- перед генерацией отчёта подкладывается история предыдущего прогона
  (`ALLURE_HISTORY_DIR`, по умолчанию `/workspace/allure-history`) → тренды (Требование 9);
- `repeat` — два подряд прогона integration с откатами (Требование 14).

### Локальный запуск контейнера

```bash
cd vidayapi

# только unit (стенд не нужен)
docker build -f docker/test-runner/Dockerfile -t viday-test-runner:local .
docker run --rm viday-test-runner:local unit

# весь конвейер вместе со стендом в одной docker-сети:
make ci-runner     # docker compose -f docker/docker-compose.ci.yml run --rm test-runner full
```

`docker-compose.ci.yml` поднимает в одной сети **отдельный** стенд
(postgres-test/redis-test/minio-test с healthcheck'ами) и сервис `test-runner`
с переменными окружения, указывающими на эти сервисы по именам.

Реальный вывод `make ci-runner` (зафиксирован 2026-10-01):

```
### [unit] unit-тесты (ЛР1) (21:02:58)
unit: OK
### [integration] реальный PostgreSQL (стенд ЛР2) (21:03:44)
integration: OK
### [e2e] полный HTTP-сценарий поверх стенда (21:03:56)
e2e: OK
### [report] агрегированный Allure-отчёт (21:04:19)
report: OK
### [repeat] повторяемость: integration x2 (Требование 14) (21:05:36)
repeat: OK (identical results, stand intact)
PIPELINE: OK
```

---

## 7. CI/CD (Задание 4, Требования 5, 8, 9)

Рабочий файл — **[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)**
(GitHub Actions, единый workflow монорепозитория). Джобы могут выполняться на
`ubuntu-latest` (Docker есть, KVM включён) или на своём раннере
(`runs-on: [self-hosted, linux, x64]` + `/dev/kvm`).

### Порядок джоб

```
build -> unit -> integration -> e2e -> device -> report     (через needs)
```

- **build**:
  - `build-vidayapi` **один раз** собирает и публикует в **ghcr.io** образы
    `test-runner` и `postgres-test` (схема init 01..05 вшита в образ —
    Требование 4), затем собирает bootJar backend'а (`pipeline.sh assemble`,
    без тестов) как артефакт для device-джобы;
  - `build-viday` — ранняя компиляция Android (`:app:assembleDebug`).
  > Раньше `docker build` выполнялся в **каждой** джобе (~168 c только на
  > apt/Node + полный контекст), что и давало ~час прогона. Теперь образы
  > собираются один раз, остальные джобы только `docker pull`.
- **unit** — `unit-vidayapi` (ЛР1 в контейнере test-runner, стенд не нужен) и
  `unit-viday` (JVM-тесты Android) параллельно.
- **integration** — `docker compose up -d postgres-test redis-test minio-test`
  (стенд из `docker-compose.ci.yml`) + `docker compose run test-runner integration`;
  после джобы — `down -v` и откат БД внутри `pipeline.sh` (Требование 10).
- **e2e** — то же окружение + `test-runner e2e` (`@SpringBootTest(RANDOM_PORT)`,
  реальный HTTP, GUI не используется — Требование 13).
- **device** — Android-эмулятор (KVM) через `reactivecircus/android-emulator-runner`:
  API стенда поднимается на хосте `java -jar` из артефакта `build-vidayapi` (8080),
  БД со схемой/Redis/MinIO — [`docker-compose.device.yml`](docker/docker-compose.device.yml)
  с портами 55432/56380/59002; `adb reverse tcp:8080 tcp:8080`;
  `:app:connectedDebugAndroidTest` (Требование 7). Если jar недоступен — тест
  SKIPPED через `assumeTrue`.
- **report** — Allure из **уже существующих** allure-results* (unit/integration/e2e
  приходят артефактами `actions/download-artifact`), без повторного прогона тестов.
  Джоба выполняется **всегда**: `if: !cancelled()` — даже если integration/e2e
  упали, отчёт генерируется (Требование 8), а невыполненные джобы помечаются
  **skipped** и видны в аннотациях JUnit / в отчёте.

### Отчёты

- JUnit-отчёты публикуются **аннотациями** в PR через `dorny/test-reporter`
  (pass/fail/skip, Требование 8).
- Allure: `allureCiReport` (без зависимостей на тесты!) собирает HTML из
  `build/allure-results{,-it,-e2e}`; **история** (тренды, Требование 9)
  кэшируется через `actions/cache` (`vidayapi/allure-history/`) и монтируется в
  контейнер (`-v "$PWD/allure-history:/workspace/allure-history"`).
- Пути артефактов совпадают с реальными каталогами: unit `**/build/allure-results`,
  IT `infrastructure/build/allure-results-it`, E2E `presentation/build/allure-results-e2e`.

### Ускорение (было ~1 час → оценка 15–30 мин)

| Причина часа | Исправление |
|---|---|
| Порядок задом наперёд (integration → e2e → device → unit) | Порядок `build → unit → integration → e2e → device → report` |
| `report` перезапускал unit+IT+E2E (зависимости `allureCiReport`) | `allureCiReport`/`collectAllureResultsCi` **без** `dependsOn` на тесты; report собирает HTML из артефактов |
| Сборка test-runner в каждой джобе | Один раз в `build` + ghcr.io, дальше `docker pull` |
| apt-get `nodejs npm` (тянет webpack/jest) | Официальный бинарник Node.js (tar.xz), без npm |
| Android: снова качается system-image API 30 | Системный образ кэшируется в `viday/.android-sdk/system-images/` |
| Gradle-зависимости качаются в каждом джобе | `actions/cache`: `vidayapi/.gradle-ci/` монтируется как `GRADLE_USER_HOME` |
| `minio/minio` (pull denied) | Везде `bitnamilegacy/minio:2025.7.23-debian-12-r5` |

---

## 8. Откат хранилища, повторяемость, параллельные разработчики

### Откат (Требования 4, 10)

[`scripts/reset-test-db.sh`](scripts/reset-test-db.sh) — парсит `VIDAY_TEST_JDBC_URL`
и выполняет от суперпользователя:

```sql
TRUNCATE TABLE viday.user_follows, viday.content_to_playlist,
    viday.user_to_playlist, viday.media_variant, viday.video, viday.stream,
    viday.content, viday.playlist, viday."user", viday.content_view_stats
RESTART IDENTITY CASCADE;
```

- `RESTART IDENTITY` сбрасывает последовательности → состояние идентично состоянию
  до прогона;
- `CASCADE` снимает FK-зависимости;
- выполняется только `viday_admin_user` (владелец последовательностей).

Запуск: `make reset-test-db` или из пайплайна после каждого этапа.

### Повторяемость (Требование 14)

```bash
make ci-runner   # внутри есть этап repeat: integration x2 с откатами
```

Либо вручную:

```bash
make test-integration && make reset-test-db && make test-integration
```

Результат одинаков в обоих прогонах (проверено: `repeat: OK`).

### Параллельные разработчики (Требование 15)

Стенд полностью изолирован по портам/имени проекта (см. раздел 3); интеграционные
тесты читают адрес из `VIDAY_TEST_*`, поэтому каждый разработчик указывает свой
стенд и не влияет на чужие прогоны.

---

## 9. Имитация E2E-действий и захват трафика (Задание 5)

### Средство отправки запросов — curl

[`scripts/e2e-curl.sh`](scripts/e2e-curl.sh) повторяет шаги JUnit E2E (регистрация
креатора → логин → активация канала → загрузка видео → плейлист → контент →
подписчик → подписка → проверка ленты/плейлистов) и складывает ответы в
`traffic/e2e-curl-<ts>.json`.

Требует поднятый стенд и приложение:

```bash
cd vidayapi
make stand-up
make app-up                     # docker compose -f docker/docker-compose.app.yml up -d --build
make e2e-curl                   # ./scripts/e2e-curl.sh
```

Вариант «разные Dockerfile» в одной docker-сети (API-контейнер [`Dockerfile.app`](docker/Dockerfile.app)
+ тонкий e2e-runner [`docker/e2e/Dockerfile`](docker/e2e/Dockerfile) с curl/ffmpeg,
postgres со схемой [`docker/postgres/Dockerfile`](docker/postgres/Dockerfile)):

```bash
cd vidayapi
make ci-e2e-curl                # up api -> run e2e-runner (curl-сценарий) -> down -v
```

### Захват трафика — tcpdump

[`scripts/traffic-capture.sh`](scripts/traffic-capture.sh):

1. внутри контейнера приложения `viday_api` (network_mode: host) стартует
   `tcpdump -i lo 'tcp port 8080'`;
2. параллельно выполняется `scripts/e2e-curl.sh`;
3. pcap сохраняется в `traffic/viday-e2e-<ts>.pcap` и печатается дайджест
   HTTP-запросов/ответов.

```bash
make traffic-demo               # ./scripts/traffic-capture.sh
```

Реальный вывод `make traffic-demo` (зафиксирован 2026-10-01):

```
start tcpdump in container viday_api (lo, tcp port 8080)
[00:31:14] 1. register creator: creator_lr2_20261002-003114
  -> id=8
[00:31:14] 2. login creator -> JWT
[00:31:14] 3. activate channel (USER -> CREATOR)
  -> role CREATOR
[00:31:14] 4. upload video (multipart, реальный ffprobe из образа)
  -> contentId=4 duration=1
[00:31:15] 5. create public playlist
  -> playlistId=4
[00:31:15] 6. add content 4 to playlist 4
  -> 200 OK
[00:31:15] 7. register follower + login + follow creator
  -> subscribed to 8
[00:31:15] 8. verify: public feed contains the uploaded video
  -> video 4 in feed
[00:31:15] 9. verify: follower sees the creator's public playlist
  -> playlist visible to follower

E2E-CURL DEMO: OK
responses saved to traffic/e2e-curl-20261002-003114.json

capture saved: traffic/viday-e2e-20261002-003114.pcap (32K)
--- HTTP digest (request lines / status lines) ---
HTTP: GET /api/playlists/available HTTP/1.1
HTTP: GET /api/videos?page=0&size=50 HTTP/1.1
HTTP: HTTP/1.1 200
HTTP: HTTP/1.1 201
HTTP: POST /api/auth/login HTTP/1.1
HTTP: POST /api/auth/register HTTP/1.1
HTTP: POST /api/playlists/4/contents HTTP/1.1
HTTP: POST /api/playlists HTTP/1.1
HTTP: POST /api/users/8/follow HTTP/1.1
HTTP: POST /api/users/me/channel/activate HTTP/1.1
HTTP: POST /api/videos/upload HTTP/1.1
--- total packets: 136 ---
```

> Примечание: трафик идёт по IPv6-loopback (`localhost` → `::1`); дайджест собирается
> из декодированных tcpdump'ом HTTP-сводок (`grep 'HTTP:'`), т.к. в `-A`-дампе
> полезной нагрузки строкам предшествуют бинарные байты TCP/IP-заголовков.

---

## 10. Мобильное приложение viday (Требование 7)

### Тесты на устройстве

[`AndroidApiFlowTest.kt`](../../viday/app/src/androidTest/java/com/snowkey/viday/AndroidApiFlowTest.kt) —
инструментированный тест (androidTest): регистрирует пользователя через API стенда,
создаёт плейлист и проверяет его через `ApiService` (Retrofit). Общается с API
через `adb reverse tcp:8080 tcp:8080`; если стенд недоступен — тест пропускается
(`assumeTrue`), поэтому CI остаётся зелёным, а локально сценарий прогоняется
полностью.

Запуск локально (нужен запущенный эмулятор/устройство + стенд vidayapi):

```bash
cd vidayapi && make stand-up
# (в другом терминале) приложение vidayapi: make run  или  docker compose -f docker/docker-compose.app.yml up -d --build

cd ../viday
make device-tests               # ждёт устройство (или поднимает AVD Medium_Phone),
                                # делает adb reverse tcp:8080 tcp:8080 и запускает
                                # :app:connectedDebugAndroidTest (см. Makefile)
```

### Unit-тесты приложения (JVM)

`VideosViewModelTest.kt` — 3 MockK-теста `VideosViewModel` (маппинг успешного ответа,
обработка сетевой ошибки, очистка состояния) с `UnconfinedTestDispatcher`:

```bash
cd viday
make test-unit                  # ./gradlew test (все JVM-модули)
```

Полный контур «unit + устройство» запускается одной командой
`./scripts/run-device-tests.sh` (ждёт/поднимает эмулятор, пробрасывает порт,
прогоняет JVM-юниты и instrumented-тесты на устройстве).

---

## 11. Allure-отчёт и тренды (Требования 8, 9)

Агрегированный отчёт собирается задачей `allureCiReport` (корневой
[`build.gradle.kts`](../build.gradle.kts)) из результатов unit+integration+e2e:

```bash
cd vidayapi
make stand-up                    # нужен для integration/e2e
make test-integration test-e2e   # собрать результаты
./gradlew allureCiReport
# отчёт: build/reports/allure-report-ci/index.html
```

- Отчёт включает историю прошлых прогонов (копирование `$HISTORY_DIR` в
  `allure-results-ci/history` до генерации и обратно после) → вкладка **Trends**
  показывает динамику поведения тестов между запусками (Требование 9).
- `--history-limit 20` — храним до 20 последних прогонов.
- Отчёт генерируется **всегда**, даже если какой-то этап упал (в `pipeline.sh`
  `step_report` вызывается безусловно; в GitHub Actions — джоба `report` с
  `if: !cancelled()` и артефактами `if: always()`).

**Просмотр — `make allure-serve ALLURE_PORT=8090`** (корневая задача
`:allureServe`). Она зависит от `allureCiReport`, поэтому прогоняет **все** этапы
(unit + integration + e2e) и открывает в браузере полный отчёт
`build/reports/allure-report-ci` — в нём видны и классические/London-тесты, и
интеграционные, и E2E (требуется поднятый стенд: `make stand-up`).

Технически это обеспечено тем, что у каждого test-task **свой каталог** результатов
Allure: `test` → `build/allure-results`, `testIntegration` →
`build/allure-results-it`, `testE2E` → `build/allure-results-e2e`, а
`collectAllureResultsCi` сливает все три. Раньше результаты IT/E2E писались в тот же
каталог, что и unit, и затирались при повторном прогоне `test` — поэтому в отчёте
были только старые тесты.

---

## 12. Примечания к требованиям N/A

- **Требование 11 (очереди)**: в проекте нет message broker / service bus / шины.
  Используются только PostgreSQL (OLTP + RLS), Redis (кэш лент/сессий-не-сессий) и
  MinIO (файлы). Читать очереди нечего — требование неприменимо.
- **Требование 12 (session store)**: сервер не хранит активные сессии —
  аутентификация по **stateless JWT** (см. `JwtTokenProvider`, `RlsBootstrapFilter`);
  Redis хранит лишь кэш с ключами по `userId` с TTL, который не является сессией и
  не требует «завершения» после тестов. Требование неприменимо.
- **Требование 13 (GUI)**: в E2E GUI не используется (чистый HTTP) — соответствует.

---

## 13. Реальные результаты прогонов

Зафиксировано во время выполнения ЛР2:

1. **Unit (ЛР1)**: `./gradlew test` — BUILD SUCCESSFUL (все модули, включая
   London/classic/offline тесты); в контейнере — `unit: OK`.
2. **Integration**: `:infrastructure:testIntegration` — **11/11** passed
   (10 файлов-сценариев: UserSaveFind, UserDuplicateUsername, UserUnknownFind,
   UserFollowUnfollow, UserDelete, PlaylistSaveFind, PlaylistExistsByName,
   PlaylistAddContent, PlaylistDeleteForbidden, PlaylistFindPublic + BusinessFlow).
3. **E2E**: `:presentation:testE2E` — `DemoMvpE2ETest` PASSED (в т.ч. внутри
   docker-контейнера поверх стенда CI-сети).
4. **Отчёт**: `allureCiReport` — `build/reports/allure-report-ci/index.html` создан.
5. **Docker-конвейер**: `docker compose -f docker/docker-compose.ci.yml run --rm
   test-runner full` → `unit: OK`, `integration: OK`, `e2e: OK`, `report: OK`,
   `repeat: OK`, **`PIPELINE: OK`** (2026-10-01, полный лог — `/tmp/ci-runner-full.log`).
6. **Мобильное**: `:app:testDebugUnitTest :domain:test` — BUILD SUCCESSFUL;
   `:app:compileDebugAndroidTestKotlin` — компилируется; сценарий устройства
   описан в разделе 10.
7. **Откат**: `scripts/reset-test-db.sh` — `OK: business tables truncated, sequences
   restarted`.
8. **Задание 5 (curl + tcpdump)**: `./scripts/e2e-curl.sh` — `E2E-CURL DEMO: OK`
   (9 шагов, реальная загрузка видео через ffprobe); `./scripts/traffic-capture.sh`
   — pcap 32K / 136 пакетов + HTTP-дайджест всех запросов/ответов сценария
   (см. раздел 9).

---

## 14. Как воспроизвести всё с нуля

```bash
# 1) Сервер: стенд + тесты
cd vidayapi
make stand-up                    # тестовый стенд
make test-integration            # 11 IT на реальном PostgreSQL (10 сценариев + бизнес-поток)
make test-e2e                    # E2E MVP-сценарий
make ci-runner                   # весь конвейер в docker-контейнере + отчёты (unit→IT→e2e→report→repeat)
make stand-down                  # снять стенд

# 2) Задание 5: curl-имитация + захват трафика
make stand-up
make app-up                      # приложение в контейнере (ffmpeg/tcpdump внутри)
make traffic-demo                # e2e-curl.sh + tcpdump -> traffic/*.pcap
make app-down && make stand-down
# вариант «разные Dockerfile» (API + postgres + e2e-runner в одной сети):
make ci-e2e-curl
# готовый capture для Wireshark (без окружения):
python3 scripts/make-sample-pcap.py -o traffic/examples/viday-e2e-sample.pcap

# 3) Мобильный клиент: unit + устройство (эмулятор/телефон с adb)
cd ../viday
make test-unit                   # JVM unit-тесты
make device-tests                # тесты на устройстве (ждёт AVD + adb reverse внутри)