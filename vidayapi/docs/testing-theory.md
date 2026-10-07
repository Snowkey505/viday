# Теория тестирования и организация CI/CD — на примере vidayapi

> Отдельный материал по запросу: что такое integration-тест и E2E-тест (с разбором
> на реальном примере проекта vidayapi), а также как настроить CI/CD для нового
> пустого проекта на GitHub.

---

## 1. Уровни тестирования: зачем их различать

Любой нетривиальный сервис состоит из слоёв:

```
┌─────────────────────────────────────────────────────────────┐
│  HTTP (контроллеры/API)          ← самый "внешний" слой     │
├─────────────────────────────────────────────────────────────┤
│  Бизнес-логика (use cases / сервисы)                        │
├─────────────────────────────────────────────────────────────┤
│  Доступ к данным (репозитории, SQL)                         │
├─────────────────────────────────────────────────────────────┤
│  Хранилища: PostgreSQL, Redis, MinIO, ...   ← самый "низкий"│
└─────────────────────────────────────────────────────────────┘
```

Тесты отличаются тем, **какую часть этого «слоёного пирога» они проверяют целиком**,
а какую заменяют заглушками. Чем ниже уровень — тем больше проверяется «взаправду»
(настоящая база, настоящий HTTP), тем медленнее и хрупче тесты, но тем выше
уверенность, что система работает как надо в реальных условиях.

| Уровень | Что заменяем заглушками | Что проверяем по-настоящему | Скорость | Уверенность |
|---|---|---|---|---|
| **Unit** | всё, кроме одного класса/функции | один модуль (use case, viewmodel…) | мгновенно | низкая (но точечная) |
| **Integration** | только «верх» (HTTP) | код + реальное хранилище данных | секунды–минуты | средняя |
| **E2E** | ничего (или только внешние недетерминированные вещи, напр. ffprobe) | вся система целиком через её настоящий интерфейс | минуты | максимальная |

---

## 2. Unit-тест (зачем нужен и как устроен)

**Определение.** Тест одного модуля (класса/функции) в изоляции: все соседи
заменяются моками/фейками. Проверяется чистая логика: вычисления, ветвления,
обработка ошибок, правила.

**На примере vidayapi.** В [`UploadVideoLondonTest.kt`](../application/src/test/kotlin/com/vidayapi/usecase/UploadVideoLondonTest.kt)
тестируется use case `UploadVideoUseCase` в одиночку:

- репозитории `UserRepository`, `ContentRepository` — **моки** (MockK);
- файловое хранилище `FileStoragePort` — **фейк** ([`RecordingFileStorage`](../infrastructure/src/test/kotlin/com/vidayapi/classic/ClassicSupport.kt));
- анализатор видео `VideoProcessor` — фейк с фиксированными метаданными;
- кэш `CachePort` — фейк.

Тест проверяет: «если все коллабораторы работают правильно, use case соберёт
запись video/content/variant и сохранит её; при отказе репозитория — вернёт ошибку».
Никакой базы нет, поэтому тест мгновенный и детерминированный.

**Ключевая мысль.** Unit-тест отвечает на вопрос *«правильно ли написан этот кусок
кода сам по себе?»*, но **не** отвечает на вопрос *«работает ли он с настоящей
базой/настоящим SQL?»* — потому что базы в нём нет.

---

## 3. Integration-тест

### 3.1. Определение

**Integration-тест проверяет взаимодействие нескольких компонентов системы между
собой — и, что важно для этой лабораторной, с настоящим хранилищем данных.**
Заглушки заменяются реальными зависимостями: настоящий PostgreSQL, Redis, файловое
хранилище и т.д.

Самые типичные цели integration-тестов:

1. **доступ к данным** — репозиторий ↔ настоящая БД: SQL-запросы, маппинг строк в
   объекты, ограничения (PK/FK/unique), транзакции, сортировка, пагинация;
2. **бизнес-логика поверх хранилища** — use case + настоящие репозитории
   (логика та же, что в unit-тестах, но уже с реальным состоянием БД).

Интеграционные тесты **не** поднимают HTTP-сервер: они вызывают классы напрямую.

### 3.2. На примере vidayapi

Все integration-тесты лежат в
[`infrastructure/src/test/kotlin/com/vidayapi/integration/`](../infrastructure/src/test/kotlin/com/vidayapi/integration/)
и помечены `@Tag("integration")`.

- [`StandPostgresIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/StandPostgresIT.kt) —
  базовый класс: HikariCP-пул к **реальному PostgreSQL тестового стенда**
  (`jdbc:postgresql://localhost:55432/viday`), перед каждым тестом чистит все
  бизнес-таблицы (`TRUNCATE ... RESTART IDENTITY`) — это и есть **arrange
  один раз в начале** (требование 17b).
- [`IntegrationFixtures.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/IntegrationFixtures.kt) —
  общие фикстуры: `PlainPasswordEncoder`, комплект репозиториев, хелперы
  `owner()`/`saveVideo()`.
- 10 файлов-сценариев **доступа к данным** по одному на каждый тест
  (требование 17a): [`UserSaveFindIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserSaveFindIT.kt)
  (`save → findByUsername`), [`UserDuplicateUsernameIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserDuplicateUsernameIT.kt)
  (дубликат username → ошибка), [`UserUnknownFindIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserUnknownFindIT.kt),
  [`UserFollowUnfollowIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserFollowUnfollowIT.kt),
  [`UserDeleteIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/UserDeleteIT.kt),
  [`PlaylistSaveFindIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistSaveFindIT.kt),
  [`PlaylistExistsByNameIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistExistsByNameIT.kt),
  [`PlaylistAddContentIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistAddContentIT.kt)
  (конфликт дубля из-за PK `(content_id, playlist_id)`),
  [`PlaylistDeleteForbiddenIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistDeleteForbiddenIT.kt),
  [`PlaylistFindPublicIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/PlaylistFindPublicIT.kt).
  Проверяется настоящий SQL из
  [`JdbcUserRepository`](../infrastructure/src/main/kotlin/com/vidayapi/repository/JdbcUserRepository.kt)
  и [`JdbcPlaylistRepository`](../infrastructure/src/main/kotlin/com/vidayapi/repository/JdbcPlaylistRepository.kt).
- [`BusinessFlowIT.kt`](../infrastructure/src/test/kotlin/com/vidayapi/integration/BusinessFlowIT.kt) —
  **бизнес-логика**: цепочка use case'ов (регистрация → активация канала →
  создание плейлиста → добавление контента → подписка → выборка) поверх настоящих
  репозиториев и настоящей БД.

Почему эти тесты ловят то, что unit не ловят:

- реальный SQL (диалект PostgreSQL, схемы `viday.*`, кавычки, `RETURNING id`);
- ограничения БД (unique на username, PK на пары, FK);
- RLS-политики и роли (`SET ROLE` в фильтре подключения);
- последовательности и автоинкременты.

### 3.3. Требования к окружению для IT

Раз integration-тесты работают с настоящей базой, нужно:

1. **Отдельный инстанс хранилища** (не тот, что крутится в dev) — требование 3:
   [`docker-compose.test.yml`](docker/docker-compose.test.yml) поднимает
   `postgres-test` на порту **55432** (плюс `redis-test` 56380, `minio-test` 59002).
2. **Инициализация схемой** (требование 4): init-скрипты `01..05` выполняются
   последовательно при первом старте контейнера.
3. **Откат состояния** (требования 4, 10): перед каждым тестом — `TRUNCATE`;
   после прогона — [`scripts/reset-test-db.sh`](scripts/reset-test-db.sh).

---

## 4. E2E-тест

### 4.1. Определение

**E2E (end-to-end) тест проверяет систему «от края до края»:** поднимается всё
приложение целиком (со всеми хранилищами), и сценарий выполняется через **тот же
интерфейс, через который пользуется реальный пользователь** — HTTP API, GUI,
мобильное приложение и т.п.

E2E отвечает на вопрос: *«работает ли вся связка вместе — контроллеры, безопасность,
JWT, бизнес-логика, RLS, SQL, кэш, файлы — когда пользователь делает реальные
запросы?»*. Никакие внутренние классы при этом не вызываются напрямую — только
настоящие HTTP-запросы (в нашем случае без GUI, что допускается требованием 13).

### 4.2. На примере vidayapi

[`DemoMvpE2ETest.kt`](../presentation/src/test/kotlin/com/vidayapi/e2e/DemoMvpE2ETest.kt)
— `@SpringBootTest(RANDOM_PORT)` поднимает **всё приложение** (Tomcat на случайном
порту, настоящие контроллеры, фильтры безопасности, JWT, RLS, репозитории,
PostgreSQL/Redis/MinIO стенда) и гоняет MVP-сценарий через `java.net.http.HttpClient`:

1. `POST /api/auth/register` — регистрация креатора;
2. `POST /api/auth/login` — получаем JWT;
3. `POST /api/users/me/channel/activate` — активация канала;
4. `POST /api/videos/upload` (multipart) — загрузка видео (анализатор заменён на
   фейк только чтобы не звать внешний `ffprobe` — единственное допущение);
5. `POST /api/playlists` — создание публичного плейлиста;
6. `POST /api/playlists/{id}/contents` — добавление контента;
7. регистрация/логин подписчика + `POST /api/users/{id}/follow`;
8. проверка: `GET /api/videos` содержит видео, `GET /api/playlists/available`
   отдаёт плейлист подписчику;
9. аноним на защищённый эндпоинт → **403**.

Это ровно тот сценарий, который показывается на защите MVP.

### 4.3. Чем E2E принципиально отличается от IT

| | Integration | E2E |
|---|---|---|
| Интерфейс | прямой вызов классов | HTTP (то, что видит пользователь) |
| Приложение | не поднимается | поднимается целиком |
| Безопасность/JWT/фильтры | нет | есть (403 у анонима — отдельный шаг) |
| Скорость | секунды | минуты |
| Ломается от | реального SQL/БД | любой ошибки в любом слое |

---

## 5. Пирамида тестирования и что из этого вышло в vidayapi

| Слой | Файлы | Количество |
|---|---|---|
| Unit (London) | `application/src/test/.../usecase/*LondonTest.kt` | 14 |
| Unit (classic, in-memory fake БД) | `infrastructure/src/test/.../classic/*ClassicTest.kt` | 9 |
| **Integration** | `infrastructure/src/test/.../integration/*IT.kt` | **11** |
| **E2E** | `presentation/src/test/.../e2e/DemoMvpE2ETest.kt` | **1** |
| Итого в Allure-отчёте | | **35** |

Суффиксы (требование 17): unit — `*Test`/`*LondonTest`; integration — `*IT`
(аналог `ITCase` для Kotlin/JUnit5); e2e — `DemoMvpE2ETest`.

---

## 6. CI/CD: что это и как это устроено в проекте

### 6.1. Определения

- **CI (Continuous Integration)** — автоматический прогон проверок (сборка,
  тесты, линтеры) при каждом изменении кода (push / merge request). Цель — как
  можно раньше поймать поломку.
- **CD (Continuous Delivery/Deployment)** — автоматическая доставка/выкладка
  артефакта (jar, apk) после успешных проверок.
- **Pipeline** — последовательность этапов (**stages**) из **джоб (jobs)**,
  выполняемая CI-сервером.
- **Runner** — исполнитель, который физически выполняет джобы (в GitHub Actions это
  облачные `ubuntu-latest`/`windows-latest`/`macos-latest` либо self-hosted runners
  на вашей машине/сервере).
- **Артефакты** — файлы, которые сохраняются после джобы (отчёты, jar, apk).
- **Кэш** — переиспользуемые данные между прогонами (gradle-зависимости, история
  Allure).

### 6.2. Порядок стадий — почему именно unit → integration → e2e

Это иерархия «стоимости»: unit — дёшево и быстро, e2e — дорого и медленно.

1. **unit** — мгновенные проверки изолированной логики. Если они красные —
   дальше можно не идти (скорее всего код сломан в принципе).
2. **integration** — код + настоящее хранилище. Ловит проблемы SQL/схемы/RLS.
   Требует поднятый стенд.
3. **e2e** — вся система. Дороже всего, поэтому последний.
4. **report** — собирает Allure-отчёт по всем этапам (всегда, даже при падении —
   требование 8).

Если unit упал — integration/e2e не запускаются (экономим время и ресурсы),
отмечаются как **skipped**, но отчёт всё равно генерируется.

### 6.3. Как это реализовано в vidayapi

- **Контейнер тестов** (требование 1): [`docker/test-runner/Dockerfile`](docker/test-runner/Dockerfile)
  содержит весь код + JDK/Gradle + psql/curl/jq/nodejs; точка входа —
  [`pipeline.sh`](docker/test-runner/pipeline.sh). CI вызывает этот контейнер.
- **Стенд в контейнерах** (требование 1/3/4): [`docker-compose.ci.yml`](docker/docker-compose.ci.yml)
  поднимает postgres/redis/minio-test в одной сети с test-runner.
- **GitHub Actions**: [`../../.github/workflows/ci.yml`](../../.github/workflows/ci.yml) —
  джобы `build → unit → integration → e2e → device → report`; каждая джоба запускает
  образ test-runner (собирается один раз в `build` и пушится в ghcr.io); история
  Allure кэшируется (`actions/cache`) и кладётся в артефакты.
- **Откат хранилища** (требование 10): `reset-test-db.sh` вызывается после
  integration/e2e и в успешном, и в ошибочном случае.
- **Повторяемость** (требование 14): этап `repeat` прогоняет integration дважды.

---

## 7. Пошаговый гайд: CI/CD для НОВОГО пустого проекта на GitHub

Допустим, вы создали пустой репозиторий `my-project` на GitHub, локально есть код
(vidayapi или любой другой). Что делать по порядку.

### Шаг 0. Убедиться, что проект — git-репозиторий

```bash
cd vidayapi
git init                     # если ещё не репозиторий
git remote add origin git@github.com:<username>/my-project.git
git add .
git commit -m "init"
```

### Шаг 1. Добавить workflow `.github/workflows/ci.yml`

Минимальный рабочий пример для Kotlin/Gradle:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: vidayapi
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - run: ./gradlew test
      - name: Publish JUnit report
        if: success() || failure()
        uses: dorny/test-reporter@v1
        with:
          name: Tests (JUnit)
          path: '**/build/test-results/**/*.xml'
          reporter: java-junit
```

В проекте уже лежит **полноценный**
[`../../.github/workflows/ci.yml`](../../.github/workflows/ci.yml) — просто
запушить его.

### Шаг 2. Запушить и убедиться, что workflow появился

```bash
git push -u origin main
```

После пуша GitHub автоматически запустит Actions (вкладка **Actions** репозитория).

### Шаг 3. Разобраться с раннерами (это главный практический вопрос)

**Вариант A — GitHub-hosted (самый простой).**
По умолчанию джобы бегут в облаке на `ubuntu-latest`: Docker уже установлен, KVM
для Android-эмулятора включён — отдельно ничего настраивать не нужно.

**Вариант B — self-hosted runner (своя машина/сервер, например ваш стенд).**
Если хотите гонять джобы на своей машине (там уже есть Docker, стенд, кэш):

1. GitHub: **Settings → Actions → Runners → New self-hosted runner**.
2. Выполните команды установки (скачайте runner, `./config.sh`, затем `./run.sh`
   как сервис `sudo ./svc.sh install && sudo ./svc.sh start`).
3. В workflow замените `runs-on: ubuntu-latest` на
   `runs-on: [self-hosted, linux, x64]`.

### Шаг 4. Docker внутри джоб

На GitHub-hosted раннерах docker-демон уже работает — `docker build` /
`docker compose` доступны сразу, **docker-in-docker не нужен**. На self-hosted
достаточно, чтобы у пользователя runner'а был доступ к Docker (группа `docker`).

### Шаг 5. Кэш и артефакты (чтобы прогоны были быстрыми)

- **Кэш** ускоряет повторные прогоны (экшен `actions/cache`):

  ```yaml
  - uses: actions/cache@v4
    with:
      path: vidayapi/.gradle-ci
      key: gradle-${{ hashFiles('vidayapi/build.gradle.kts') }}
  ```

- **Артефакты** сохраняют отчёты; `if: always()` гарантирует сохранение даже при
  падении джобы (требование 8 — отчёт должен быть всегда):

  ```yaml
  - name: Upload Allure report
    if: always()
    uses: actions/upload-artifact@v4
    with:
      name: allure-report
      path: vidayapi/build/reports/allure-report-ci/
  ```

- Артефакты можно скачать со страницы **Actions → <прогон> → Artifacts**.

### Шаг 6. Защита веток и pull requests (хорошая практика)

- **Settings → Branches → Add rule**: запретить прямой push в `main` (только через PR);
- **Settings → General → Pull request**: включить «Require status checks to pass» —
  пока workflow не зелёный, мёржить нельзя;
- В workflow добавить `concurrency` (отмена лишних параллельных прогонов) — уже
  есть в [`ci.yml`](../../.github/workflows/ci.yml).

### Шаг 7. Проверить, что workflow реально зелёный

Первый прогон часто падает из-за мелочей — читайте логи джобы во вкладке
**Actions → <джоба>**. Типичные проблемы и решения:

| Проблема | Решение |
|---|---|
| `Cannot connect to the Docker daemon` | На self-hosted дать пользователю доступ к docker (группа `docker`) |
| `permission denied` на gradlew | `git update-index --chmod=+x gradlew` (или `chmod +x` в джобе) |
| Порты заняты / БД не поднялась | Использовать отдельные порты стенда (55432 и т.д.) и healthcheck'и |
| `ghcr.io` pull/push denied | Включить Container Registry в настройках и `permissions: packages: write` |
| Runner не берёт джобу | Проверить метки `runs-on` и статус runner в Settings → Actions |

---

## 8. Чек-лист «CI/CD запущен и работает»

- [ ] `.github/workflows/ci.yml` в репозитории
- [ ] После `git push` появился workflow (вкладка Actions)
- [ ] Раннер (hosted или свой) виден как Online
- [ ] Джобы идут по порядку: unit → integration → e2e (→ report)
- [ ] При падении этапа отчёт Allure всё равно собирается (`report` с `if: !cancelled()`)
- [ ] История Allure кэшируется между прогонами (тренды)
- [ ] Хранилище откатывается после integration/e2e
- [ ] Повторный прогон даёт тот же результат

---

## 9. Быстрый ответ на «как мне вообще организовать CI/CD, я создал пустой проект»

1. Положите код в репозиторий и добавьте remote: `git remote add origin <url>`.
2. Скопируйте в корень готовый [`../../.github/workflows/ci.yml`](../../.github/workflows/ci.yml)
   из vidayapi (или минимальный из Шага 1) — в нём уже описаны джобы, стенд,
   контейнер тестов.
3. `git push -u origin main` — Actions запустится сам.
4. Выберите runner: сначала попробуйте `ubuntu-latest` (ничего делать не надо);
   если нужен свой — добавьте self-hosted runner по Шагу 3.
5. Смотрите результат во вкладке **Actions**; логи падений — в джобах; отчёты
   Allure — в артефактах джобы `report` (и в `make allure-serve` локально).