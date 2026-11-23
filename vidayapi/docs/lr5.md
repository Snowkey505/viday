# ЛР5. E2E-тесты аутентификации и плановой смены пароля (BDD)

Документ описывает реализацию лабораторной работы № 5 в `vidayapi`:
двухфакторную аутентификацию по email, ограничение попыток ввода пароля,
восстановление после блокировки, плановую смену пароля и BDD-сценарии
Cucumber-JVM, интегрированные в CI/CD без хранения паролей в репозитории.

## 1. Соответствие требованиям

| Требование ЛР5 | Где реализовано | Чем проверяется |
|---|---|---|
| BDD-фреймворк для E2E | [`presentation/build.gradle.kts`](../presentation/build.gradle.kts) — Cucumber-JVM 7 + `cucumber-spring` | [`features/`](../presentation/src/test/resources/features/) + [`bdd/`](../presentation/src/test/kotlin/com/vidayapi/bdd/) |
| Двухфакторная аутентификация (email) | [`AuthenticationService.kt`](../application/src/main/kotlin/com/vidayapi/service/AuthenticationService.kt), [`JdbcEmailOutboxSender.kt`](../infrastructure/src/main/kotlin/com/vidayapi/email/JdbcEmailOutboxSender.kt) | [`authentication.feature`](../presentation/src/test/resources/features/authentication.feature) |
| Ограниченное число попыток ввода | `AuthenticationService.authenticate` + `JdbcAuthRepository.recordFailedLogin` | [`login_attempts.feature`](../presentation/src/test/resources/features/login_attempts.feature) |
| Восстановление после блокировки | `requestRecovery` / `confirmRecovery`, таблица `auth_challenge` | [`recovery.feature`](../presentation/src/test/resources/features/recovery.feature) |
| Технический пользователь: создание до теста и утилизация после | [`TechnicalUserHooks.kt`](../presentation/src/test/kotlin/com/vidayapi/bdd/TechnicalUserHooks.kt) | [`technical_user.feature`](../presentation/src/test/resources/features/technical_user.feature) |
| Плановая смена пароля | `password_changed_at`, `viday.auth.password-rotation-days` (90 дней) | [`password_rotation.feature`](../presentation/src/test/resources/features/password_rotation.feature) |
| Пароли не хранятся в явном виде | `VIDAY_LR5_TECH_PASSWORD` / `JWT_SECRET` из секретов CI; локально — эфемерная генерация | `TechnicalUserHooks.resolvePassword`, [`ci.yml`](../../.github/workflows/ci.yml) |

## 2. Как устроена аутентификация

### 2.1. Схема БД (миграция `06_auth_lr5.sql`)

- `viday.user_auth` — email, флаг 2FA, счётчик неудачных попыток,
  `locked_until`, `password_changed_at`.
- `viday.auth_challenge` — одноразовые коды для `LOGIN_2FA` и
  `PASSWORD_RECOVERY`; хранится только BCrypt-хеш кода.
- `viday.auth_email_outbox` — «почтовый транспорт» тестового стенда:
  приложение кладёт письмо с кодом, E2E-тест читает его оттуда.
  В production адаптер `EmailSenderPort` заменяется на SMTP-провайдера.
- PostgreSQL-роль `viday_auth` — минимальные права для анонимных
  `/api/auth/**`; административные права стенда им не выдаются.

### 2.2. Эндпоинты

| Метод | Путь | Назначение |
|---|---|---|
| `POST` | `/api/auth/register` | регистрация; при наличии `email` включается 2FA |
| `POST` | `/api/auth/login` | пароль; `202` + `challengeId`, `200` + JWT, `200` + `passwordChangeToken`, `401` + `attemptsRemaining`, `423` + `lockedUntil` |
| `POST` | `/api/auth/2fa/verify` | проверка кода из письма |
| `POST` | `/api/auth/password/change` | смена пароля по ограниченному токену (`purpose=password_change`) |
| `POST` | `/api/auth/recovery/request` | запрос кода восстановления (всегда `202`, чтобы не раскрывать аккаунт) |
| `POST` | `/api/auth/recovery/confirm` | код + новый пароль; снимает блокировку и счётчик попыток |

Ограниченный токен смены пароля не принимается остальным API: JWT-фильтр
проверяет claim `purpose=access`.

### 2.3. Политика (переопределяется переменными окружения)

```yaml
viday:
  auth:
    max-login-attempts: 5
    lock-duration-minutes: 15
    password-rotation-days: 90
    two-factor-code-ttl-minutes: 5
    two-factor-max-attempts: 5
    password-change-token-ttl-minutes: 10
```

## 3. BDD-сценарии

Feature-файлы лежат в `presentation/src/test/resources/features/`, шаги — в
`presentation/src/test/kotlin/com/vidayapi/bdd/`. Все сценарии помечены
тегом `@lr5` и запускаются отдельной Gradle-задачей `:presentation:testBdd`
(движок JUnit Platform + Cucumber, `maxParallelForks = 1`).

Покрытые сценарии:

1. **Двухфакторная аутентификация** — после верного пароля выдаётся
   `challengeId`, код уходит на замаскированный email, JWT появляется только
   после верного кода; неверные коды ограничены.
2. **Лимит попыток** — 4 неверных пароля показывают `attemptsRemaining=1`,
   пятый блокирует аккаунт на 15 минут; верный пароль во время блокировки
   тоже получает `423`.
3. **Восстановление после блокировки** — код из письма снимает блокировку,
   задаёт новый пароль; старый пароль отклоняется, новый проходит 2FA.
4. **Плановая смена пароля** — «просроченный» пароль (91 день) после 2FA
   выдаёт только `passwordChangeToken`; после смены старый пароль не работает,
   новый проходит полный вход.
5. **Подготовка данных** — технический пользователь создаётся через реальный
   API регистрации и удаляется после сценария вместе с каскадными
   auth-записями.

### 3.1. Технический пользователь

`TechnicalUserHooks`:

- `@Before` создаёт уникального пользователя `lr5_tech_<random>` с email
  `<username>@lr5.viday.test` через `POST /api/auth/register`;
- пароль берётся из `VIDAY_LR5_TECH_PASSWORD` (секрет CI) либо генерируется
  криптостойко в памяти процесса;
- `@After` удаляет пользователя из БД; `user_auth`, `auth_challenge` и
  `auth_email_outbox` удаляются каскадом;
- пароль никогда не пишется в feature-файлы, логи, Allure-отчёты или
  `toString()`.

## 4. CI/CD и секреты

В GitHub Actions добавлена отдельная джоба `bdd-vidayapi` (`.github/workflows/ci.yml`).
Она поднимает тестовый стенд (`postgres-test`, `redis-test`, `minio-test`),
запускает `test-runner bdd` и публикует:

- JUnit XML (`presentation/build/test-results/testBdd`);
- Allure-результаты (`presentation/build/allure-results-bdd`);
- JaCoCo exec (`presentation/build/jacoco/testBdd.exec`).

Эти артефакты затем попадают в общий Allure-отчёт и SonarQube (как и
unit/integration/e2e).

### 4.1. Настройка секретов

В настройках репозитория (GitHub → Settings → Secrets and variables → Actions)
нужно задать:

| Секрет | Назначение | Обязателен |
|---|---|---|
| `VIDAY_LR5_TECH_PASSWORD` | пароль технического пользователя для BDD-сценариев | нет; если не задан — генерируется эфемерно |
| `JWT_SECRET` | ключ подписи JWT (не короче 32 байт) | нет; если не задан — эфемерный ключ на процесс |

Для GitLab CI используются protected/masked CI variables с теми же именами,
для Vault — секреты `database`/`kv`, подставляемые в окружение контейнера.
Альтернатива для локальной разработки — системный keyring
(аналоги `keyring` для используемого языка).

В репозитории нет реальных паролей пользователей: в compose-файлах остаются
только пароли тестового PostgreSQL/Redis/MinIO, которые не являются
учётными данными пользователей приложения.

## 5. Запуск

### 5.1. Локально

```bash
cd vidayapi

# ВАЖНО: init-скрипты PostgreSQL (включая 06_auth_lr5.sql) выполняются
# только на пустом data-каталоге. Если стенд уже существовал до ЛР5:
make stand-reset          # down -v + up -d

# BDD-сценарии (пароль сгенерируется автоматически)
make test-bdd             # ./gradlew :presentation:testBdd

# Либо с секретом из локального окружения:
VIDAY_LR5_TECH_PASSWORD='<secret>' JWT_SECRET='<32+ bytes>' make test-bdd
```

### 5.2. В контейнере (как в CI)

```bash
cd vidayapi
docker compose -p viday-ci -f docker/docker-compose.ci.yml run --rm test-runner bdd
```

Полный конвейер (`unit → integration → e2e → bdd → report`):

```bash
make ci-runner
```

### 5.3. Отчёт

```bash
make allure-serve         # unit + integration + e2e + bdd в одном Allure-отчёте
```

## 6. Проверенный результат

На тестовом стенде `docker-compose.test.yml` (PostgreSQL 15, Redis 7, MinIO)
все 7 BDD-сценариев проходят:

```text
Двухфакторная аутентификация по email ... PASSED (2)
Ограничение попыток ввода пароля ... PASSED (1)
Плановая смена пароля ... PASSED (1)
Восстановление доступа после блокировки ... PASSED (2)
Подготовка тестовых данных ... PASSED (1)
```
