# test-and-debug

Монорепозиторий лабораторной работы №2: backend-API **vidayapi** (Spring Boot 4,
PostgreSQL + RLS, Redis, MinIO) и мобильный клиент **viday** (Android, Kotlin +
Compose). CI/CD — **GitHub Actions** ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)).

## Состав

| Проект | Каталог | Что в CI |
|--------|---------|----------|
| Backend API | [`vidayapi/`](vidayapi/) | build → unit → integration → e2e → bdd (ЛР5) → Allure-отчёт |
| Android-приложение | [`viday/`](viday/) | build (assembleDebug) → unit (JVM) → device (эмулятор, KVM) |

Лабораторная отчётность: [`vidayapi/docs/lr2.md`](vidayapi/docs/lr2.md),
[`vidayapi/docs/lr5.md`](vidayapi/docs/lr5.md) (BDD-аутентификация, 2FA,
лимит попыток, восстановление, плановая смена пароля).

---

## CI/CD (GitHub Actions)

Единый workflow — [`ci.yml`](.github/workflows/ci.yml). Порядок стадий:
`build → unit → integration → e2e → bdd → device → report` (через `needs`).

```
build-vidayapi ─► unit-vidayapi ─► integration-vidayapi ─► e2e-vidayapi ─┐
   │                                (unit → integration → e2e)          ├─► report-vidayapi
   │                                                                     │
   └─ build-vidayapi-jar ─────────────────────► bdd-vidayapi (ЛР5) ──────┘
   └─ build-viday ─► unit-viday ─► device-viday (эмулятор + API из jar) ── параллельно backend
```

Ключевые решения:

- **`build-vidayapi`** собирает образы `test-runner` и `postgres-test` **один раз**,
  пушит их в GitHub Container Registry (ghcr.io) и собирает bootJar backend'а
  (артефакт `vidayapi-jar`). Остальные джобы только `docker pull`.
- **`report-vidayapi`** выполняется **всегда** (`if: !cancelled()`) и НЕ
  перезапускает тесты: Allure генерируется из `allure-results*`, пришедших
  артефактами из unit/integration/e2e/bdd (Требование 8).
- **`bdd-vidayapi`** (ЛР5) прогоняет Cucumber-сценарии аутентификации
  (`@lr5`). Пароль технического пользователя берётся из секрета
  `VIDAY_LR5_TECH_PASSWORD`, JWT-ключ — из `JWT_SECRET`; при отсутствии
  секретов значения генерируются эфемерно.
- JUnit-отчёты публикуются аннотациями через `dorny/test-reporter`; история
  Allure (тренды, Требование 9) кэшируется через `actions/cache`.
- **`device-viday`**: эмулятор Android (API 30) через
  `reactivecircus/android-emulator-runner` (нужен KVM); API стенда поднимается
  на хосте из jar-артефакта (`java -jar`, порт 8080), стенд (postgres со схемой,
  redis, minio) — [`docker-compose.device.yml`](vidayapi/docker/docker-compose.device.yml)
  с портами 55432/56380/59002; `adb reverse tcp:8080 tcp:8080`. Если jar
  недоступен — тест помечается SKIPPED через `assumeTrue`.

### Как запустить

1. Включите **GitHub Container Registry** (Settings → Packages and registries) —
   для пуша образов используется автоматический `GITHUB_TOKEN` с
   `packages: write` (уже прописан в workflow).
2. По умолчанию джобы идут на `ubuntu-latest` (Docker есть, KVM для эмулятора
   включён). Для **собственного раннера** замените `runs-on` на
   `[self-hosted, linux, x64]` и убедитесь, что на нём установлены Docker и
   доступен `/dev/kvm`:
   ```bash
   ls -l /dev/kvm && egrep -c '(vmx|svm)' /proc/cpuinfo   # > 0
   ```
3. Запустите первый прогон (push / pull request / Actions → Run workflow):
   `build-vidayapi` соберёт и запушит образы, дальше джобы только тянут их.

### Триггеры и фильтры

- `push` в `main`/`master`, `pull_request`, `workflow_dispatch` (ручной запуск).
- Изменены только `viday/**` → выполняются только Android-джобы;
  только `vidayapi/**` → только backend-джобы; прочее (README и т.п.) → workflow пропущен.

---

## Проверка локально (без CI)

```bash
# vidayapi — стенд + тесты (unit/integration/e2e/bdd/report/repeat в одном контейнере)
cd vidayapi && make stand-up && make test-integration && make test-e2e
make test-bdd                        # ЛР5: Cucumber auth-сценарии
make ci-runner                       # весь конвейер в docker-контейнере + Allure
make stand-down

# Если стенд создавался до ЛР5, в нём нет таблиц user_auth/auth_challenge:
make stand-reset                     # down -v + up -d (init 01..06)

# Задание 5: curl-имитация + захват трафика (Wireshark)
make stand-up
make app-up                          # контейнер приложения
make traffic-demo                    # e2e-curl.sh + tcpdump -> traffic/*.pcap
make app-down && make stand-down

# viday — unit и тесты на устройстве (эмулятор/телефон с adb)
cd ../viday
make test-unit
make device-tests                    # ждёт AVD + adb reverse внутри
```

> Безопасность: не коммитьте токены/пароли. Пароли стенда здесь — только
> тестовые значения для локального окружения ЛР2 (`admin_password_change_me`,
> `minioadmin`). Пароль технического пользователя ЛР5 и `JWT_SECRET`
> задаются через секреты CI (GitHub Actions / GitLab CI / Vault), а локально
> генерируются эфемерно.
