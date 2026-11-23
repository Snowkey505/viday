# Лабораторная работа № 1 — Unit-тесты, покрытие и тестовая документация

Проект: **vidayapi** (Kotlin/JVM, Gradle, Spring Boot).
Стек тестирования: **JUnit 5 (Jupiter)**, **MockK** (mock), **AssertJ** (assertions),
**JaCoCo** (покрытие), **Allure** (отчёты), **Testcontainers** (классические интеграционные тесты).

> **Важно (финальная итерация).** Тестовый набор был сознательно сокращён:
> из ~188 тестов оставлено **17 показательных** — **7 «лондонских»** (mockk) и
> **10 классических** (8 на фейках + 2 интеграционных на Testcontainers), остальные
> удалены как избыточные. Критерий отбора: каждый оставшийся тест демонстрирует
> отдельную технику подготовки данных или отдельное требование лабораторной работы
> (подробнее в §2 и §7).

---

## 1. Структура проекта и карта оставшихся тестов

Проект разбит на Gradle-модули:

| Модуль | Содержимое | Оставшиеся тесты |
|---|---|---|
| `domain/` | Модели (`User`, `Content`, `Video`, `Stream`, `Page`, ...) и порты | `model/PageRequestTest.kt` |
| `application/` | **Бизнес-логика**: сервисы (`AuthService`, `JwtService`, `RoleAuthorization`) и use-case'ы (`RegisterUserUseCase`, `UploadVideoUseCase`, `GetViewStatsUseCase`, `StartStreamUseCase`) | `service/*`, `usecase/*`, `support/*` |
| `infrastructure/` | **Доступ к данным**: `JdbcUserRepository`; интеграционные сценарии | `repository/JdbcUserRepositoryLondonTest.kt`, `repository/JdbcUserRepositoryClassicIT.kt`, `usecase/CreatePlaylistUseCaseIntegrationTest.kt` |
| `presentation/` | REST-контроллеры | — (тестов нет, `NO-SOURCE`) |

### Карта тестовых файлов

**application (`@Tag("offline")`):**

```
application/src/test/kotlin/com/vidayapi/
├── service/
│   ├── AuthServiceTest.kt          — login: 2 London-теста (mockk) + 1 классический (фейки)
│   ├── JwtServiceTest.kt           — ожидаемое исключение WeakKeyException
│   └── RoleAuthorizationTest.kt    — requireAdmin: матрица ролей ADMIN/USER
├── usecase/
│   ├── RegisterUserUseCaseTest.kt  — London: занятый username, encode+save; классика на фейках
│   ├── UploadVideoUseCaseTest.kt   — граница maxFileSizeMb (значение из VideoBusinessConfig)
│   ├── GetViewStatsUseCaseTest.kt  — граница limit=0→1; Object Mother + fixture seeded()
│   └── StartStreamUseCaseTest.kt   — переход состояний SCHEDULED→LIVE
└── support/
    ├── Fakes.kt                    — in-memory фейки портов (FakeUserRepository и др.)
    ├── TestDataBuilders.kt         — Data Builder (UserBuilder, ...) + Object Mother (TestObjectMother)
    └── ResultAssertions.kt         — assertRight / assertLeftType<E> для Either<Error, T>
```

**domain (`@Tag("offline")`):**

```
domain/src/test/kotlin/com/vidayapi/model/PageRequestTest.kt — нормализация пагинации (граничные значения)
```

**infrastructure (`@Tag("offline")` — London на mockk; `@Tag("integration")` — классика на Testcontainers):**

```
infrastructure/src/test/kotlin/com/vidayapi/
├── repository/
│   ├── JdbcUserRepositoryLondonTest.kt — London: mock JdbcTemplate, маппинг исключений JDBC
│   └── JdbcUserRepositoryClassicIT.kt  — классика (Detroit): реальный PostgreSQL в Testcontainers
└── usecase/CreatePlaylistUseCaseIntegrationTest.kt — end-to-end: регистрация → создание плейлиста
```

Итого: **17 тестов** = 15 offline + 2 integration; из них **7 London** и **10 Classic**.

---

## 2. Оставшиеся тесты поимённо

### 2.1 «Лондонские» тесты (mockk) — 7

| Класс | Тест | Что проверяет | Техника данных |
|---|---|---|---|
| [`AuthServiceTest.login unknown user`](application/src/test/kotlin/com/vidayapi/service/AuthServiceTest.kt:30) | `mockk<UserRepository>` возвращает `null` → `Error.Unauthorized` | классы эквивалентности (несуществующий пользователь) |
| [`AuthServiceTest.login bad password`](application/src/test/kotlin/com/vidayapi/service/AuthServiceTest.kt:39) | `mockk<PasswordEncoder>.matches=false` → `Error.Unauthorized` | классы эквивалентности (неверный пароль) |
| [`RegisterUserUseCaseTest.execute rejects existing username`](application/src/test/kotlin/com/vidayapi/usecase/RegisterUserUseCaseTest.kt:24) | `existsByUsername=true` → `Error.AlreadyExists` | негативный сценарий |
| [`RegisterUserUseCaseTest.execute saves encoded password`](application/src/test/kotlin/com/vidayapi/usecase/RegisterUserUseCaseTest.kt:35) | `encode("secret")="hashed"`, `save(match { hash })` → успех | Data Builder / Object Mother (`TestObjectMother.user()`) |
| [`JdbcUserRepositoryLondonTest.findById`](infrastructure/src/test/kotlin/com/vidayapi/repository/JdbcUserRepositoryLondonTest.kt:29) | RowMapper маппит строку; `EmptyResultDataAccessException` → `null` (не исключение) | обработка исключений Spring JDBC |
| [`JdbcUserRepositoryLondonTest.findById storage failure`](infrastructure/src/test/kotlin/com/vidayapi/repository/JdbcUserRepositoryLondonTest.kt:41) | `DataRetrievalFailureException` → `Error.StorageFailure` | маппинг исключений |
| [`JdbcUserRepositoryLondonTest.save`](infrastructure/src/test/kotlin/com/vidayapi/repository/JdbcUserRepositoryLondonTest.kt:49) | id из keyholder; `DuplicateKeyException` → `Error.AlreadyExists` | маппинг исключений |

### 2.2 Классические тесты (без mock) — 10 (8 offline + 2 integration)

| Класс | Тест | Что проверяет | Техника данных |
|---|---|---|---|
| [`AuthServiceTest.login success`](application/src/test/kotlin/com/vidayapi/service/AuthServiceTest.kt:50) | `FakeUserRepository` + `UserBuilder`; выданный JWT валиден | in-memory фейк, Data Builder |
| [`RegisterUserUseCaseTest.classic execute registers user in fake store`](application/src/test/kotlin/com/vidayapi/usecase/RegisterUserUseCaseTest.kt:50) | регистрация в `FakeUserRepository` без mocks, пароль `encoded:matrix` | in-memory фейки |
| [`JwtServiceTest.short secret throws WeakKeyException`](application/src/test/kotlin/com/vidayapi/service/JwtServiceTest.kt:14) | HMAC-ключ короче 256 бит → `WeakKeyException` (**ожидаемый результат — Exception**) | исключение как результат |
| [`PageRequestTest.normalized clamps page and size`](domain/src/test/kotlin/com/vidayapi/model/PageRequestTest.kt:13) | `page=-5`, `size=0`, `size=10_000` → коэрция; `offset` | граничные значения |
| [`UploadVideoUseCaseTest.execute rejects oversized file`](application/src/test/kotlin/com/vidayapi/usecase/UploadVideoUseCaseTest.kt:39) | файл `2*1024*1024` байт > `maxFileSizeMb=1` → `InvalidInput` | граничные значения **от конфигурации** |
| [`GetViewStatsUseCaseTest.getTop coerces zero limit`](application/src/test/kotlin/com/vidayapi/usecase/GetViewStatsUseCaseTest.kt:30) | `limit=0` → приводится к 1; топ сортируется по просмотрам | граничные значения + Object Mother + fixture `seeded()` |
| [`StartStreamUseCaseTest.execute starts scheduled stream`](application/src/test/kotlin/com/vidayapi/usecase/StartStreamUseCaseTest.kt:18) | переход состояний `SCHEDULED → LIVE`, `streamKey` непустой | переходы состояний |
| [`RoleAuthorizationTest.requireAdmin`](application/src/test/kotlin/com/vidayapi/service/RoleAuthorizationTest.kt:16) | `ADMIN` → ok, `USER` → `Forbidden` | комбинаторное (матрица ролей) |
| [`JdbcUserRepositoryClassicIT.save and findByUsername`](infrastructure/src/test/kotlin/com/vidayapi/repository/JdbcUserRepositoryClassicIT.kt:94) | реальный PostgreSQL (Testcontainers): `save` + `findByUsername` | классика Detroit, реальная БД |
| [`CreatePlaylistUseCaseIntegrationTest.should create playlist successfully...`](infrastructure/src/test/kotlin/com/vidayapi/usecase/CreatePlaylistUseCaseIntegrationTest.kt:135) | end-to-end: `registerUserUseCase` → `createPlaylistUseCase` → проверка записи в БД | интеграционный сценарий |

Один и тот же сценарий показан в обоих стилях (Требование 3):
`login` (London: unknown user / bad password; Classic: success) и
`register` (London: `execute saves encoded password`; Classic: `classic execute registers user in fake store`).

---

## 3. Покрытие тестами (JaCoCo)

**Инструмент:** JaCoCo, подключён ко всем модулям в корневом [`build.gradle.kts`](build.gradle.kts:32).
Покрытие считается по offline-прогону (`./gradlew test`); integration-тесты выполняются
отдельным таском и в JaCoCo-отчёт не входят; у `presentation` тестов нет.

Что измеряется:

- **exec loc / all loc** — исполненные строки / все строки (счётчик `LINE`);
- **ветвления** — счётчик `BRANCH` (каждое условие `if`/`when`/`?:` даёт 2 ветки);
- **инструкции** — счётчик `INSTRUCTION` (байткод JVM), для справки.

### 3.1 Покрытие по модулям и общее (после сокращения)

```text
$ ./gradlew test coverageSummary
=== application coverage ===
  instructions (exec / all): 13.91% (460 / 3306)
  lines (exec loc / all loc): 16.04% (116 / 723)
  branches: 10.19% (22 / 216)
=== domain coverage ===
  instructions (exec / all): 4.79% (48 / 1002)
  lines (exec loc / all loc): 5.76% (8 / 139)
  branches: 0.00% (0 / 8)
=== infrastructure coverage ===
  instructions (exec / all): 2.23% (106 / 4748)
  lines (exec loc / all loc): 2.70% (26 / 962)
  branches: 0.49% (1 / 205)
```

Сводная таблица:

| Модуль | Инструкции (exec / all) | Строки (exec loc / all loc) | Ветвления |
|---|---|---|---|
| application | 13.91% (460 / 3306) | 16.04% (116 / 723) | 10.19% (22 / 216) |
| domain | 4.79% (48 / 1002) | 5.76% (8 / 139) | 0.00% (0 / 8) |
| infrastructure | 2.23% (106 / 4748) | 2.70% (26 / 962) | 0.49% (1 / 205) |
| **ИТОГО по проекту** | **6.78% (614 / 9056)** | **8.22% (150 / 1824)** | **5.36% (23 / 429)** |

> Общее покрытие = сумма счётчиков трёх модулей (строка, ветка, инструкция) /
> сумма всех их счётчиков. `presentation` в расчёт не входит (нет тестов).

### 3.2 Как смотреть покрытие

```bash
./gradlew test coverageSummary   # CLI: строки (exec/all) и ветвления по модулям
```

HTML-отчёты (с детализацией по ветвлениям и классам):

- `application/build/reports/jacoco/test/html/index.html`
- `domain/build/reports/jacoco/test/html/index.html`
- `infrastructure/build/reports/jacoco/test/html/index.html`

XML (читает `coverageSummary`): `*/build/reports/jacoco/test/jacocoTestReport.xml`.

### 3.3 Почему покрытие такое

Покрытие ниже исходного (было: application — 87% строк, infrastructure — 51%) —
это прямое следствие сознательного сокращения до 17 показательных тестов (§7):
теперь покрываются ключевые пути `AuthService`, `JwtService`, `RegisterUserUseCase`,
`UploadVideoUseCase` (валидация размера), `GetViewStatsUseCase`, `StartStreamUseCase`,
`RoleAuthorization`, `Page`/`PageRequest` и `JdbcUserRepository`.

- **domain (5.76% строк)** — почти весь код — Kotlin data class'ы: их аксессоры/`copy`/`equals`
  генерируются компилятором, и JaCoCo считает их «строками». Оставшийся тест покрывает
  нормализацию `PageRequest` и `offset`.
- **infrastructure (2.70% строк)** — из репозиториев покрыт только `JdbcUserRepository`
  (London-тесты остальных четырёх репозиториев удалены как избыточные). Непокрытыми
  остаются тонкие адаптеры (`RedisCacheAdapter`, `MinioFileStorageAdapter`,
  `FFmpegVideoProcessor`, `JwtTokenProvider`, RLS/Security), что соответствует фокусу
  работы — показательные тесты компонентов доступа к данным и бизнес-логики.

---

## 4. Команды (шпаргалка)

```bash
./gradlew test                  # offline unit-тесты (без integration) + JaCoCo
./gradlew testOffline           # только offline-тесты (mocks + in-memory фейки, без сети/Docker)
./gradlew testIntegration       # классические тесты на Testcontainers (нужен Docker)
./gradlew test coverageSummary  # тесты + покрытие (строки и ветвления) в консоль
./gradlew test allureReports    # тесты + Allure HTML-отчёт
make test                       # сокращение: ./gradlew test coverageSummary
make allure                     # ./gradlew test allureReports
```

> **Для генерации Allure-отчёта** нужен локальный архив Node.js:
> `gradle/node/node-v22.22.0-linux-x64.tar.gz`. Если его нет — скачайте:
>
> ```bash
> mkdir -p gradle/node
> curl -L https://nodejs.org/dist/v22.22.0/node-v22.22.0-linux-x64.tar.gz \
>      -o gradle/node/node-v22.22.0-linux-x64.tar.gz
> ```

Отчёты:

- JaCoCo HTML: `*/build/reports/jacoco/test/html/index.html`
- JaCoCo XML (для coverageSummary): `*/build/reports/jacoco/test/jacocoTestReport.xml`
- Allure: `*/build/reports/allure-report/allureReport/index.html`
- JUnit HTML: `*/build/reports/tests/test/index.html`

Запуск из командной строки настроен на локальной копии репозитория (Требование 8):
достаточно склонировать проект и выполнить `./gradlew test coverageSummary`.

---

## 5. Соответствие требованиям лабораторной работы

### Требование 1 — test suite на класс компонентов

**Выполнено частично (после сознательного сокращения).** Изначально каждый `public`-метод
каждого класса бизнес-логики и доступа к данным был покрыт позитивным и негативным
сценарием (~188 тестов). В финальной итерации оставлены только показательные тесты —
по 7 «лондонских» и 10 классических (критерии отбора в §7). Если требуется вернуть
полное покрытие «2 теста на метод», прежние тесты доступны в истории Git.

### Требование 2 — тесты на обработку исключений (ожидаемый результат — Exception)

**Выполнено.**

- [`JwtServiceTest.short secret throws WeakKeyException`](application/src/test/kotlin/com/vidayapi/service/JwtServiceTest.kt:14) —
  `assertThatThrownBy { JwtService("short", 1000) }.isInstanceOf(WeakKeyException::class.java)`;
- London-тесты `JdbcUserRepository` проверяют обработку исключений Spring JDBC:
  `EmptyResultDataAccessException`, `DuplicateKeyException`, `DataRetrievalFailureException` —
  каждая ветка `catch` отображается на ожидаемый `Error.*`.

### Требование 3 — классические и «Лондонские» тесты

**Выполнено, один и тот же сценарий показан в обоих стилях:**

| Сценарий | Классика (Detroit) | London (mockk) |
|---|---|---|
| `AuthService.login` | `login success` на `FakeUserRepository` | `login unknown user`, `login bad password` на `mockk<UserRepository>()` |
| `RegisterUserUseCase` | `classic execute registers user in fake store` | `execute saves encoded password` |
| `JdbcUserRepository` | `JdbcUserRepositoryClassicIT` (Testcontainers Postgres) | `JdbcUserRepositoryLondonTest` (mock `JdbcTemplate`) |

### Требование 4 — структура Arrange–Act–Assert с fixture/хелперами

**Выполнено.** Во всех оставшихся тестах соблюдены три секции; Act — ровно один вызов
тестируемого public-метода (Требование 5). Хелперы: `support/Fakes.kt`,
`support/TestDataBuilders.kt`, `support/ResultAssertions.kt`, локальные fixture-функции
(`seeded()` в `GetViewStatsUseCaseTest`, `videoFile()`/`request()` в `UploadVideoUseCaseTest`).

Пример из [`GetViewStatsUseCaseTest.getTop coerces zero limit`](application/src/test/kotlin/com/vidayapi/usecase/GetViewStatsUseCaseTest.kt:31):

```kotlin
@Test
fun `getTop coerces zero limit`() {
    val (users, stats) = seeded()                 // Arrange (fixture-хелпер + Object Mother)
    val top = GetViewStatsUseCase(users, stats).getTop(8, 0).assertRight()  // Act
    assertThat(top).hasSize(1)                    // Assert
    assertThat(top.first().contentId).isEqualTo(2)
}
```

### Требование 6 — без тестов на private/protected

**Выполнено.** Тестируются только публичные API; приватные RowMapper'ы и хелперы
проверяются косвенно через public-методы.

### Требование 7 — паттерны Data Builder и Object Mother (Fabric)

**Выполнено**, файл [`application/src/test/kotlin/com/vidayapi/support/TestDataBuilders.kt`](application/src/test/kotlin/com/vidayapi/support/TestDataBuilders.kt:1):

- **Data Builder**: `UserBuilder(...).withXxx(...).build()` (используется в `AuthServiceTest.login success`);
- **Object Mother**: `TestObjectMother.user()`, `.analyst(8)`, `.scheduledStream(ownerId, contentId)`
  (используется в `RegisterUserUseCaseTest`, `GetViewStatsUseCaseTest`, `StartStreamUseCaseTest`).

### Требования 8–11 — запуск из CLI, отчёты, случайный порядок, offline

- **8 (CLI):** команды в §4 (`./gradlew test`, `testOffline`, `testIntegration`).
- **9 (Allure):** плагин `io.qameta.allure` в корневом [`build.gradle.kts`](build.gradle.kts:11),
  листенер `allure-junit5`, аннотации `@Description` в тестах; генерация — `./gradlew allureReports`.
- **10 (случайный порядок):** в [`build.gradle.kts`](build.gradle.kts:60) каждой тестовой JVM
  задаётся `junit.jupiter.execution.order.random.seed` (случайное зерно на прогон);
  в модулях лежит `src/test/resources/junit-platform.properties` с
  `ClassOrderer$Random` / `MethodOrderer$Random`.
- **11 (offline):** таск `testOffline` включает только классы с `@Tag("offline")` —
  исключительно MockK и in-memory фейки, без Docker и сети; integration-тесты в нём не участвуют.

### Требование 12 — сколько процессов запускается

В корневом [`build.gradle.kts`](build.gradle.kts:63) `tasks.withType<Test>().configureEach { maxParallelForks = 1 }` —
**один JVM-процесс на каждый test-task модуля** (при `./gradlew test` последовательно работают
3 воркера: `application`, `domain`, `infrastructure`; `presentation` — `NO-SOURCE`). Внутри процесса
Gradle переиспользует одну JVM для всех классов модуля (`forkEvery = 0`). Это конфигурируется
в Gradle (`maxParallelForks`, `forkEvery`); `junit.jupiter.execution.parallel.enabled=false`
дополнительно отключает параллельное выполнение внутри процесса.

### Требование 13 — тесты проходят успешно

**Выполнено.** `./gradlew test testOffline testIntegration coverageSummary` → `BUILD SUCCESSFUL`
(17/17 тестов зелёные; цифры в §3.1).

### Требования 14 и 16 — покрытие по строкам (exec loc / all loc) и ветвлениям из CLI

**Выполнено.** JaCoCo: `jacocoTestReport` привязан к `test` через `finalizedBy`, отчёты в
XML/HTML/CSV ([`build.gradle.kts`](build.gradle.kts:117)). Кастомный таск `coverageSummary`
читает XML и печатает в консоль процент по **инструкциям**, **строкам (exec/all)** и
**ветвлениям** — см. §3.

### Требование 15 — защита от регрессии, устойчивость к рефакторингу

Обеспечивается фиксацией **контрактов** (что возвращает метод, какие ошибки) вместо
внутренней реализации; переиспользуемыми хелперами/фейками; одно-сценарными тестами;
случайным порядком выполнения; изоляцией тестов (свежие фейки/mock'и в каждом тесте).

### Требования 17 и 18 — обоснование данных и документация

Обоснование выбора тестовых данных — §6; документ `lr1.md` в корне репозитория — этот файл.

---

## 6. Как подбирались тестовые данные (техники)

| Техника | Где используется (оставшиеся тесты) |
|---|---|
| **Граничные значения** | `PageRequestTest.normalized clamps page and size` (`page=-5`, `size=0`, `size=10_000`); `UploadVideoUseCaseTest.execute rejects oversized file` (файл `2*1024*1024` байт при `maxFileSizeMb=1` — граница строится от `VideoBusinessConfig`); `GetViewStatsUseCaseTest.getTop coerces zero limit` (`limit=0` → 1) |
| **Классы эквивалентности** | `AuthServiceTest`: существующий/несуществующий пользователь, верный/неверный пароль; `RegisterUserUseCaseTest`: свободный/занятый username |
| **Переходы состояний** | `StartStreamUseCaseTest.execute starts scheduled stream`: `SCHEDULED → LIVE`, проверяется целевое состояние и побочный эффект (`streamKey` непустой) |
| **Комбинаторное (попарное)** | `RoleAuthorizationTest.requireAdmin`: роль × операция (`ADMIN`/`USER` × `requireAdmin`) |
| **Ошибки/исключения** | `JwtServiceTest.short secret throws WeakKeyException`; маппинг исключений Spring JDBC в `JdbcUserRepositoryLondonTest` |
| **Данные через Builder/ObjectMother** | `TestObjectMother.user()` (Auth/Register), `.analyst(8)` + `.user(1)` (GetViewStats), `.scheduledStream(2, 200)` (StartStream), `UserBuilder` (AuthServiceTest) |

Как именно подбирали данные под сценарий:

- **Читали условие/инвариант в коде**: `maxFileSizeMb` берётся из `VideoBusinessConfig`,
  поэтому в тесте граница строится от конфигурации (файл чуть больше лимита), а «золотой»
  сценарий — строго в пределах лимитов.
- **Держали ровно одну границу в тесте**: чтобы падение однозначно указывало на причину.
- **Для переходов состояний брали валидное стартовое состояние** из Object Mother
  (`scheduledStream`), затем проверяли целевое состояние и побочные эффекты.
- **Для комбинаторики ограничивались попарным набором**: роль × операция, т.к. код
  ветвится по каждому предикату независимо.

---

## 7. Что изменено в финальной итерации (сокращение тестов)

1. **Сокращение с ~188 до 17 тестов.** Удалено 27 тестовых файлов (~170 тестовых методов):
   - `application`: `CacheKeysTest`, `ContentDeletionServiceTest`, `VideoResponseMapperTest` и
     use-case тесты (кроме `RegisterUser`, `UploadVideo`, `GetViewStats`, `StartStream`) —
     `ActivateChannel`, `AdminDelete*`, `Assign*Role`, `CreatePlaylist`/`CreateStream`,
     `Delete*`, `Follow/UnfollowUser`, `ListLiveStreams`, `ListVideos`, `StopStream`,
     `Update*`;
   - `infrastructure`: `NoOpCacheAdapterTest`, London-тесты репозиториев
     (`JdbcContentRepository`, `JdbcPlaylistRepository`, `JdbcStreamRepository`,
     `JdbcViewStatsRepository`) и `TestConfig.kt` (стал не нужен).
   - В оставшихся файлах убраны невыбранные методы (например, из `AuthServiceTest` —
     `validateToken`, `refreshToken`, role gates и др.).
2. **Критерии отбора «показательных» тестов** (по 5–10 на каждый стиль):
   - London: сценарии с mock-коллабораторами (`mockk<UserRepository>`,
     `mockk<PasswordEncoder>`, `mockk<JdbcTemplate>`) + маппинг исключений JDBC;
   - Classic: сценарии на in-memory фейках (без mocks), граничные значения, переходы
     состояний, матрица ролей, ожидаемое исключение и 2 интеграционных сценария на
     реальном PostgreSQL (Testcontainers);
   - каждый оставшийся тест должен демонстрировать отдельную технику данных (§6)
     или отдельное требование (исключения, оба стиля, AAA, Builder/ObjectMother).
3. **Починен запуск `testIntegration` на новых Docker.** Тесты падали на старте контейнеров:
   Testcontainers 1.19.3 (docker-java) договаривается об API v1.32, а Docker ≥ 25 отклоняет
   рукопожатие (`client version 1.32 is too old. Minimum supported API version is 1.44`).
   В корневом [`build.gradle.kts`](build.gradle.kts:114) для таска `testIntegration` задано
   системное свойство `api.version=1.44` — после чего оба интеграционных теста проходят.
4. Ранее исправленное (сохраняется): рабочие `testOffline`/`testIntegration`
   (явная привязка к test source set), `./gradlew allureReports` (локальный Node.js вместо
   битого Maven-маппинга `org.nodejs`), разделение multi-scenario тестов (один Act на тест).

---

## 8. Ограничения и замечания

- Интеграционные тесты (`JdbcUserRepositoryClassicIT`, `CreatePlaylistUseCaseIntegrationTest`)
  требуют Docker и помечены `@Tag("integration")`; они исключены из обычного и offline-прогонов.
  Для работы с Docker ≥ 25 в `build.gradle.kts` зафиксирована версия API клиента (см. §7, п.3).
- Покрытие после сокращения (см. §3) существенно ниже исходного — это ожидаемое следствие
  того, что оставлены только показательные тесты; полный набор был в истории Git.
- В London-тестах `JdbcUserRepository` есть ветки `?: Error.NotFound`, недостижимые через mock
  (Spring `queryForObject` бросает `EmptyResultDataAccessException`, а не возвращает `null`);
  поведение зафиксировано в соответствии с фактической реализацией.
- Количество процессов: один воркер-JVM на модуль; внутри — одна JVM на все классы модуля
  (Gradle default `forkEvery=0`).