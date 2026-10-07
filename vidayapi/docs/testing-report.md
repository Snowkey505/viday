# Отчёт по тестированию viday-api

> Детское описание стратегии тестирования: **8 классических (Detroit)** и **14 лондонских (London)**
> тестов на сложные сценарии домена, подмена JDBC-соединения вместо реальной PostgreSQL,
> JaCoCo + SonarQube (древовидный отчёт по покрытию) и Allure (отчёты о прогонах).

---

## 1. Цели и результат

| Требование из `task.txt` | Статус |
|---|---|
| Убрать интеграционный тест и лишние тесты | ✅ Удалены `JdbcUserRepositoryClassicIT`, `CreatePlaylistUseCaseIntegrationTest`, старые unit-тесты сервисов/контроллеров. Осталось ровно 8 классических + 14 лондонских тестов |
| По 5 классических и лондонских тестов на сложные сценарии | ✅ 5 базовых пар (классический + лондонский) + доп. сценарии валидации и проброса ошибок для `RegisterUserUseCase`, `CreatePlaylistUseCase`, `FollowUserUseCase` — см. раздел 2 |
| Классический тест — не «замаскированный» лондонский (никаких FakeRepository) | ✅ Классические тесты гоняют **реальные** `JdbcUserRepository`, `JdbcPlaylistRepository`, `JdbcContentRepository` со строками SQL |
| Реальные репозитории, но данные не пишутся в настоящую БД | ✅ Подмена соединения на уровне JDBC: `InMemoryPostgresDataSource` (раздел 3) |
| Не переписывать на ORM (есть RLS) | ✅ SQL остаётся как в production; RLS-слой не трогается |
| Лондонские тесты — моки репозиториев | ✅ mockk-моки портов (`UserRepository`, `PlaylistRepository`, `ContentRepository`, …) |
| SonarQube с древовидным наглядным отчётом по покрытию | ✅ Плагин `org.sonarqube 7.5.0.8588`, агрегация JaCoCo по модулям, docker-compose для сервера (раздел 5). Проверено: анализ выполнен, покрытие видно по файлам/строкам |
| Allure собирается успешно | ✅ `./gradlew allureReportAggregate` → `build/reports/allure-report/index.html` (раздел 6) |
| Отчёт в `docs/` | ✅ Этот файл |

---

## 2. Сценарии тестирования (8 классических + 14 лондонских)

Выбраны четыре ключевых бизнес-сценария из задания (загрузка видео, создание плейлиста,
добавление в плейлист, подписка на пользователя) плюс регистрация как базовый сценарий
учётных записей, от которого зависят остальные. Для **каждого** сценария написаны два теста:
классический и лондонский. Дополнительно для `RegisterUserUseCase`, `CreatePlaylistUseCase`
и `FollowUserUseCase` добавлены сценарии валидации (пустые входные данные — ошибка без обращения
к БД/портам) и проброса `Left` от репозитория; за счёт этого их покрытие доведено до 100%
строк и веток (см. раздел 5.3).

| # | Сценарий | Классический тест (инфраструктура) | Лондонский тест (application) |
|---|---|---|---|
| 1 | Регистрация пользователя | [`RegisterUserClassicTest`](../infrastructure/src/test/kotlin/com/vidayapi/classic/RegisterUserClassicTest.kt) | [`RegisterUserLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/RegisterUserLondonTest.kt) |
| 2 | Загрузка видео | [`UploadVideoClassicTest`](../infrastructure/src/test/kotlin/com/vidayapi/classic/UploadVideoClassicTest.kt) | [`UploadVideoLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/UploadVideoLondonTest.kt) |
| 3 | Создание плейлиста | [`CreatePlaylistClassicTest`](../infrastructure/src/test/kotlin/com/vidayapi/classic/CreatePlaylistClassicTest.kt) | [`CreatePlaylistLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/CreatePlaylistLondonTest.kt) |
| 4 | Добавление контента в плейлист | [`AddContentToPlaylistClassicTest`](../infrastructure/src/test/kotlin/com/vidayapi/classic/AddContentToPlaylistClassicTest.kt) | [`AddContentToPlaylistLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/AddContentToPlaylistLondonTest.kt) |
| 5 | Подписка на пользователя | [`FollowUserClassicTest`](../infrastructure/src/test/kotlin/com/vidayapi/classic/FollowUserClassicTest.kt) | [`FollowUserLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/FollowUserLondonTest.kt) |

### 2.1 Что проверяется в каждом сценарии

1. **Регистрация**
   - классический: уникальный username проходит через реальный INSERT, пароль хешируется BCrypt,
     повторная регистрация того же имени возвращает `Error.AlreadyExists` и не пишет вторую строку;
   - лондонский: проверяется последовательность `existsByUsername → encode → save` через моки,
     и что при занятом имени пароль не кодируется и `save` не вызывается;
   - дополнительно: пустые username/password → `Error.ValidationFailed` без обращения к портам/БД;
     `Left` от `existsByUsername`/`save` пробрасывается наружу без кодирования пароля.

2. **Загрузка видео**
   - классический: полный пайплайн `UploadVideoUseCase` — валидация формата/размера, INSERT
     `content` с `RETURNING id`, запись в storage (in-memory), INSERT `video`
     (`ON CONFLICT ... DO UPDATE`) и INSERT `media_variant`, инвалидация кэша;
   - лондонский: моки `ContentRepository` / `FileStoragePort` / `VideoProcessor` / `CachePort`,
     проверяется порядок вызовов `save → uploadFile → analyze → saveVideo → saveMediaVariant`.

3. **Создание плейлиста**
   - классический: реальный `JdbcPlaylistRepository`, дубликат имени у того же владельца отклоняется
     через `COUNT(*)`, повторный INSERT не выполняется;
   - лондонский: `existsByNameAndOwnerId → save`, дубликат не вызывает `save`;
   - дополнительно: пустое имя → `Error.ValidationFailed` без обращения к репозиторию;
     `Left` от `existsByNameAndOwnerId`/`save` пробрасывается наружу.

4. **Добавление в плейлист**
   - классический: автопозиция через `COUNT(*)` (`position = count + 1`), добавление чужим
     пользователем отклоняется `Error.Forbidden` и не пишет вторую связь;
   - лондонский: `findById → addContentToPlaylist` для владельца, для постороннего — `Forbidden`
     без обращения к `addContentToPlaylist`.

5. **Подписка**
   - классический: реальный INSERT в `user_follows`, повторная подписка мапится из SQLState
     `23505` (duplicate key) в `Error.UserAlreadyFollowed`, подписка на себя — `Error.CannotFollowSelf`;
   - лондонский: `findById(создатель) → follow`, self-follow вообще не ходит в репозиторий;
   - дополнительно: несуществующий создатель → `Error.NotFound` без записи в `user_follows`;
     `Left` от `findById`/`follow` пробрасывается наружу.

---

## 3. Классические тесты: подмена соединения PostgreSQL (главный момент)

**Проблема.** Производственные репозитории — это `JdbcTemplate` + SQL прямо в коде
([`JdbcUserRepository`](../infrastructure/src/main/kotlin/com/vidayapi/repository/JdbcUserRepository.kt),
[`JdbcPlaylistRepository`](../infrastructure/src/main/kotlin/com/vidayapi/repository/JdbcPlaylistRepository.kt),
[`JdbcContentRepository`](../infrastructure/src/main/kotlin/com/vidayapi/repository/JdbcContentRepository.kt)).
Классический стиль требует **реальный** репозиторий, а не fake. Но гонять реальный
`JdbcTemplate` против настоящего PostgreSQL нельзя: тесты должны быть офлайн и не писать в БД.
Переписывать на ORM нельзя — RLS (`viday.*` policies) завязан на SQL-схему.

**Решение: JDBC-уровневая подмена соединения** — [`InMemoryPostgresDataSource`](../infrastructure/src/test/kotlin/com/vidayapi/jdbc/InMemoryPostgresDataSource.kt).

### 3.1 Как это устроено

```
JdbcTemplate (production)
        │  getConnection()
        ▼
InMemoryPostgresDataSource  (extends org.springframework.jdbc.datasource.AbstractDataSource)
        │
        ▼
InMemoryConnection  — JDK dynamic proxy над java.sql.Connection (prepareStatement / createStatement / close / getMetaData …)
        │
        ▼
InMemoryPreparedStatement — «честный» PreparedStatement:
        │   setString/setInt/setLong/setBoolean/setNull/setTimestamp/setBytes…
        │   executeQuery / executeUpdate / execute
        ▼
InMemoryCatalog        — «база данных» в памяти: таблицы (List<Map<String, Any?>>),
        последовательности id, справочники (role, access_type, content_type, codec)
```

Ключевые решения:

- **Никакого TCP-соединения с PostgreSQL.** `AbstractDataSource.getConnection()` возвращает
  динамический прокси, который имитирует поведение драйвера, но весь SQL исполняется
  внутри процесса против `InMemoryCatalog`.
- **SQL остаётся настоящим.** `InMemoryCatalog.execute(sql, params)` нормализует SQL
  (нижний регистр, удаление кавычек, схлопывание пробелов) и разбирает знакомые конструкции:
  `select ... from viday."user" where u.id = ?`, `insert into viday.playlist (...) select ..., ? ... returning id`,
  `count(*) from viday.content_to_playlist where playlist_id = ?` и т.д. Любой неподдерживаемый
  SQL падает с понятной ошибкой `Unsupported SELECT: ...` — это «страховка», которая не даёт
  классическому тесту тихо превратиться в лондонский.
- **Ограничения воспроизводятся честно.** Уникальность (`username`, `user_follows`,
  `content_to_playlist.position`) кидает `SQLException` с SQLState `23505` — тот самый код,
  который production-репозитории ловят как `DuplicateKeyException`. За счёт этого тесты
  проверяют **реальное** отображение DB-ошибок в доменные (`Error.AlreadyExists`,
  `Error.UserAlreadyFollowed`, `Error.PlaylistPositionConflict`).
- **Spring JDBC-обвязка работает без изменений.** `StatementCreatorUtils` (Spring 7) при
  передаче `null`-аргументов вызывает `ps.getConnection().getMetaData()` и
  `ps.getParameterMetaData().getParameterType(i)`. Поэтому прокси обязан вернуть:
  - не-`null` `Connection` из `PreparedStatement.getConnection()`;
  - `DatabaseMetaData` с `getURL()`, `supportsBatchUpdates()` и прочими стандартными ответами;
  - `ParameterMetaData`, где все параметры объявлены как `Types.VARCHAR`
    (см. [`InMemoryParameterMetaData`](../infrastructure/src/test/kotlin/com/vidayapi/jdbc/InMemoryPostgresDataSource.kt)).
  Без этих трёх вещей Spring NPE'шится ещё до выполнения SQL — это было найдено и исправлено
  в процессе настройки.
- **Сброс между тестами.** `ClassicJdbc.reset()` → `catalog.clearBusinessData()` очищает
  бизнес-таблицы и сбрасывает последовательности, сохраняя справочники
  (`role`, `access_type`, `content_type`, `codec`) — ровно как в `03_roles.sql` в Docker.
  Сам вызов `reset()` вынесен из тестов в переиспользуемые фикстуры — см. раздел 3.3.

### 3.2 Почему это классический (Detroit), а не лондонский стиль

- Тестируется **поведение**, а не взаимодействие: после `useCase.execute(...)` проверяются
  фактические строки в каталоге (`db.dataSource.catalog.rows("playlist")`) и их количество.
- Ни одного мока доменных портов: `JdbcUserRepository`, `JdbcPlaylistRepository`,
  `JdbcContentRepository` — **production-классы**, те же бины, что поднимает Spring.
- Подменяется только «транспорт» (JDBC-соединение), а не доменная логика. Это точный аналог
  того, как классический тест на Hibernate работает с in-memory H2 вместо внешней БД.

### 3.3 Фикстуры очистки репозиториев

Ручной `@BeforeEach fun reset() = db.reset()` в каждом классическом тесте вынесен
в переиспользуемые фикстуры — [`ClassicFixtures.kt`](../infrastructure/src/test/kotlin/com/vidayapi/classic/ClassicFixtures.kt).
Реализованы **обе** стратегии из задания:

**Стратегия А — «фикстура на каждый тест»: [`PerTestJdbcFixture`](../infrastructure/src/test/kotlin/com/vidayapi/classic/ClassicFixtures.kt)**
(интерфейс с default-методами JUnit):

- `@BeforeEach` — перед каждым тестом затирает все репозитории (`db.reset()`);
- `@AfterEach` — после теста затирает их ещё раз и **проверяет чистоту**
  (`db.assertRepositoriesEmpty()`): если тест оставил строки, фикстура падает —
  «затирание» доказывается, а не предполагается.

Классический тест подключает её одной строкой — `override val db = ClassicJdbc()`.
На неё переведены все 8 классических тестов из раздела 2.

**Стратегия Б — «фикстура на весь класс»: [`SuiteJdbcFixture`](../infrastructure/src/test/kotlin/com/vidayapi/classic/ClassicFixtures.kt)**:

- `@BeforeAll` — один раз в начале всех тестов класса затирает репозитории;
- тесты прогоняются на общем состоянии (сквозные сценарии в несколько шагов);
- `@AfterAll` — после всех тестов затирает всё и проверяет чистоту.

Требует `@TestInstance(PER_CLASS)` (аннотация стоит на самой фикстуре и продублирована
в демо-классе), чтобы `db` был одним и тем же экземпляром во всех фазах жизненного цикла.

**Демонстрация — [`ClassicFixturesDemoTest.kt`](../infrastructure/src/test/kotlin/com/vidayapi/classic/ClassicFixturesDemoTest.kt)**:

- `PerTestJdbcFixtureDemo` — два теста: первый пишет строку в `user`, второй видит пустой
  каталог (доказательство затирания между тестами);
- `SuiteJdbcFixtureDemo` — два упорядоченных теста (`@TestMethodOrder(OrderAnnotation)`):
  первый создаёт пользователя, второй видит его (общее состояние на класс),
  `@AfterAll` зачищает всё после прогона.

Демо-классы помечены `@Tag("fixtures-demo")` и **исключены из обычного прогона**
(`tasks.test` отфильтровывает тег, поэтому число 22 теста не меняется; в SonarQube/Allure
они тоже не попадают). Отдельный запуск:

```bash
make test-fixtures-demo          # = ./gradlew testFixturesDemo
```

---

## 4. Лондонские тесты

Лондонский стиль — чёрная коробка для use case с **моками** всех коллабораторов
(`mockk`), проверкой взаимодействий (`verify`, `verifyOrder`) и без какого-либо состояния БД:

- [`RegisterUserLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/RegisterUserLondonTest.kt)
- [`UploadVideoLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/UploadVideoLondonTest.kt)
- [`CreatePlaylistLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/CreatePlaylistLondonTest.kt)
- [`AddContentToPlaylistLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/AddContentToPlaylistLondonTest.kt)
- [`FollowUserLondonTest`](../application/src/test/kotlin/com/vidayapi/usecase/FollowUserLondonTest.kt)

Общие соглашения:

- класс помечен `@Tag("offline")`, чтобы он выполнялся в обычном `test` и в `testOffline`;
- Allure-аннотации: `@Epic("London")`, `@Feature`, `@Story`, `@Description`;
- вспомогательные ассерты для `Either` — [`ResultAssertions.kt`](../application/src/test/kotlin/com/vidayapi/support/ResultAssertions.kt)
  (`assertRight()`, `assertLeftType<E>()`).

---

## 5. SonarQube — древовидный отчёт по покрытию

### 5.1 Что подключено

1. **Gradle-плагин** `org.sonarqube:7.5.0.8588` в корневом [`build.gradle.kts`](../build.gradle.kts).
2. **Корневые свойства проекта** (`sonar.projectKey=vidayapi`, `sonar.projectName=viday-api`,
   `sonar.host.url` по умолчанию `http://localhost:9002`, токен из `-PsonarToken=…`,
   `sonar.sourceEncoding=UTF-8`, `sonar.qualitygate.wait=true`).
3. **По-модульные входные данные** (в `subprojects { pluginManager.withPlugin("org.jetbrains.kotlin.jvm") }`):
   - `sonar.sources` = `src/main/kotlin`, `sonar.tests` = `src/test/kotlin`;
   - `sonar.java.binaries` = `build/classes/kotlin/main` (скомпилированные классы);
   - `sonar.java.libraries` = `runtimeClasspath`;
   - `sonar.junit.reportPaths` = JUnit XML из `build/test-results/test`;
   - `sonar.coverage.jacoco.xmlReportPaths` = `build/reports/jacoco/test/jacocoTestReport.xml`
     (JaCoCo XML уже включён для каждого модуля).
4. **Задача `:sonar` зависит от `jacocoTestReport` всех модулей** — анализ всегда идёт после
   прогона тестов, поэтому покрытие в SonarQube свежее.
5. **Сервер** — [`docker/docker-compose.sonar.yml`](../docker/docker-compose.sonar.yml):
   SonarQube Community Build (`sonarqube:lts-community`, поддержка Kotlin бесплатна) + своя
   PostgreSQL 16. Доступ на `http://localhost:9002` (хостовый порт 9002, т.к. 9000/9001
   заняты MinIO в [`docker-compose.yml`](../docker/docker-compose.yml)).

### 5.2 Как пользоваться

```bash
# 1) Поднять сервер (первый запуск: скачает образы, ~1–2 мин до статуса UP)
make sonar-up

# 2) В браузере http://localhost:9002 — войти admin/admin (пароль попросят сменить),
#    My Account → Security → Generate Token (например, имя "gradle")

# 3) Запустить анализ (тесты + JaCoCo + отправка в SonarQube)
make sonar SONAR_TOKEN=<токен>
# или напрямую:
./gradlew sonar -PsonarToken=<токен>

# 4) Открыть проект viday-api → вкладка "Measures" → "Coverage" —
#    древовидное представление: модуль → пакет → файл → строка
```

Использование своей инсталляции: `./gradlew sonar -PsonarToken=… -PsonarHostUrl=https://sonar.example.org`.

### 5.3 Что видно в отчёте

- **Дерево кода** с покрытием по строкам и веткам на каждый файл (включая `.kt`-файлы,
  т.к. Community Build анализирует Kotlin).
- Агрегированное покрытие по всему проекту (сумма по `domain`, `application`,
  `infrastructure`, `presentation`).
- Связка с JaCoCo: одна и та же метрика `LINE/BRANCH`, что и в `make coverage`
  (см. вывод `coverageSummary`).

Проверено на реальном прогоне (`./gradlew sonar -PsonarToken=<token>` после `make sonar-up`):

```text
Compute Engine task: SUCCESS
ncloc = 5190                    # Kotlin-код проанализирован
lines_to_cover = 2220
coverage = 12.3%                # импорт из JaCoCo XML по всем модулям
uncovered_lines = 1937

Файловый уровень (дерево Measures -> Coverage):
AddContentToPlaylistUseCase.kt  -> 100.0%
UploadVideoUseCase.kt           -> 100.0%
CreatePlaylistUseCase.kt        -> 100.0%
RegisterUserUseCase.kt          -> 100.0%
FollowUserUseCase.kt            -> 100.0%
```

После добавления сценариев валидации и проброса ошибок три файла с наименьшим покрытием
доведены до 100% строк и веток (проверено по `jacocoTestReport.xml`: `CreatePlaylistUseCase`
15/15 строк, `RegisterUserUseCase` 16/16 строк, `FollowUserUseCase` 9/9 строк).
JdbcContentRepository.kt        -> покрыт классическими тестами
...
```

Служебные свойства: `sonar.scm.disabled=true` (проект вне git — SCM-сенсор не нужен),
`sonar.qualitygate.wait=true` (задача ждёт вердикт Quality Gate).

---

## 6. Allure

Allure уже был настроен и теперь стабильно собирается:

```bash
# Прогнать тесты и собрать per-module Allure-отчёты
make allure            # = ./gradlew test allureReports

# Агрегированный отчёт по всем модулям в build/reports/allure-report/
./gradlew allureReportAggregate

# Собрать и открыть в браузере на http://localhost:19999
make allure-serve      # = ./gradlew allureServe [-PallurePort=...]
```

Особенности сборки (важно, чтобы всё «успешно собиралось»):

- Allure 4.x сам тянет Node.js через сломанный Maven-маппинг `org.nodejs` (на запрос `.pom`
  приходит tarball, и `DownloadNode` падает). В [`build.gradle.kts`](../build.gradle.kts)
  зависимость `org.nodejs` исключена, а в `allureNodeDistribution` подкладывается локальный
  архив `gradle/node/node-v22.22.0-linux-x64.tar.gz` (см. комментарий в корневом build-скрипте).
- Перед каждым `test` каталог `build/allure-results` модуля очищается (`doFirst`), чтобы
  повторные прогоны не плодили «ретраи» в отчёте.
- `collectAllureResults` сливает результаты всех модулей в `build/allure-results`, а
  `allureReportAggregate` генерирует один HTML через собранный плагином CLI
  (`domain/build/allure/commandline/bin/allure`).

Тесты помечены `@Epic("Classic (Detroit)")` / `@Epic("London")`, `@Feature`, `@Story`,
`@Description` — в Allure сценарии раскладываются по эпикам/фичам и читаются как документация.

---

## 7. Сборка и команды

```bash
make test              # ./gradlew test coverageSummary — 22 теста (8 classic + 14 london)
make test-offline      # ./gradlew testOffline — только @Tag("offline")
make test-fixtures-demo  # ./gradlew testFixturesDemo — только демо фикстур (@Tag("fixtures-demo")), вне основного прогона
make coverage          # тесты + печать покрытия по модулям (JaCoCo LINE/BRANCH)
make allure            # тесты + Allure-отчёты
make allure-serve      # тесты + агрегированный Allure + сервер :19999
make sonar-up          # поднять SonarQube + его PostgreSQL
make sonar SONAR_TOKEN=<token>   # тесты + JaCoCo + анализ в SonarQube
make build             # полная сборка (Docker Postgres + gradle build)
```

Пример вывода `coverageSummary` (после текущего прогона):

```
=== application coverage ===
  lines (exec loc / all loc): 18.36% (134 / 730)
=== domain coverage ===
  lines (exec loc / all loc): 5.76% (8 / 139)
=== infrastructure coverage ===
  lines (exec loc / all loc): 14.64% (141 / 963)
```

---

## 8. Основные моменты и выводы

1. **Классический ≠ лондонский.** В классических тестах нет ни одного мока портов: работает
   production `JdbcTemplate` + реальные репозитории, проверяется состояние in-memory каталога.
2. **Подмена соединения — единственная точка фальсификации.** Она заменяет транспорт
   (JDBC → процесс), но не доменную логику и не SQL. Репозитории видят полноценные
   `Connection/PreparedStatement/ResultSet`, включая метаданные, нужные Spring 7
   (`getMetaData`, `getParameterMetaData`, `unwrap`).
3. **DB-ошибки проверяются по-настоящему.** SQLState `23505` воспроизводится каталогом,
   поэтому маппинг «дубликат → доменная ошибка» тестируется, а не мокается.
4. **Никакого ORM.** Схема `viday.*` с RLS-политиками не трогается: тесты выполняют те же
   строки SQL, что и production.
5. **SonarQube** подключён на уровне Gradle (агрегированные JaCoCo/JUnit пути по модулям) и
   разворачивается одной командой; отчёт по покрытию — древовидный, по файлам и строкам.
6. **Allure** собирается агрегированно и стабильно (обход сломанного Node-маппинга Allure 4).
7. **Фикстуры вынесены и задокументированы.** Обе стратегии — «на каждый тест»
   (`PerTestJdbcFixture`) и «на весь класс» (`SuiteJdbcFixture`) — реализованы
   в [`ClassicFixtures.kt`](../infrastructure/src/test/kotlin/com/vidayapi/classic/ClassicFixtures.kt)
   и показаны в отдельном файле
   [`ClassicFixturesDemoTest.kt`](../infrastructure/src/test/kotlin/com/vidayapi/classic/ClassicFixturesDemoTest.kt)
   (`make test-fixtures-demo`). Демо помечено `@Tag("fixtures-demo")` и исключено из обычного
   прогона, поэтому число 22 теста не меняется.