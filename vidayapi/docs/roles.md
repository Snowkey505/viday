# Ролевая модель Viday API

Диаграмма use case: `[usecase.puml](usecase.puml)`.

В системе два уровня ролей — их важно не путать.

## 1. Роли приложения (JWT / таблица `viday.user`)

Используются REST API и JWT. Хранятся в `viday.role` + поле `role_id` у пользователя.


| Роль               | Код       | Как получить                                                                                                                            |
| ------------------ | --------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| **Гость**          | `GUEST`   | Не регистрируется в БД. Любой запрос без токена (публичные GET).                                                                        |
| **Пользователь**   | `USER`    | `POST /api/auth/register` → роль `USER` по умолчанию.                                                                                   |
| **Контент-мейкер** | `CREATOR` | `USER` вызывает `POST /api/users/me/channel/activate`, затем `POST /api/users/me/token/refresh`.                                        |
| **Аналитик**       | `ANALYST` | Только назначение **админом**: `POST /api/admin/users/{username}/promote-analyst`. Самостоятельной регистрации нет.                     |
| **Админ**          | `ADMIN`   | Только назначение **другим админом**: `POST /api/admin/users/{username}/promote-admin`. Первого админа создают вручную в БД (см. ниже). |


### Иерархия возможностей (по use case)

```
GUEST ⊂ USER ⊂ CREATOR
GUEST ⊂ ANALYST
CREATOR ⊂ ADMIN (наследует права мейкера + админские)
```

- **USER**: плейлисты, подписки.
- **CREATOR**: загрузка видео, стримы, удаление **своего** контента.
- **ANALYST**: статистика просмотров (`/api/analytics/`**), без загрузки контента.
- **ADMIN**: удаление любого пользователя/контента/плейлиста, назначение ADMIN/ANALYST.

`ANALYST` и `CREATOR` — **разные ветки**: аналитик не проходит через активацию канала; при назначении `ANALYST` прежняя роль `USER`/`CREATOR` заменяется.

---

## 2. Роли PostgreSQL (уровень СУБД)

Описаны в `docker/postgres/init/03_roles.sql`. Нужны для прямого доступа к БД (psql, отчёты), **не** попадают в JWT автоматически.


| Роль PG         | Логин                | Назначение                                              |
| --------------- | -------------------- | ------------------------------------------------------- |
| `viday_guest`   | `viday_guest_user`   | Только SELECT публичных данных                          |
| `viday_user`    | `viday_user_user`    | Пользовательские CRUD                                   |
| `viday_creator` | `viday_creator_user` | Расширение user + stream                                |
| `viday_admin`   | `viday_admin_user`   | Полный доступ к схеме `viday` (миграции, init-скрипты) |
| `viday_app`     | `viday_app_user`     | Runtime-подключение приложения; `SET ROLE` по JWT      |
| `viday_analyst` | `viday_analyst_user` | SELECT `content_view_stats`, ограниченный SELECT `user` |


Приложение подключается как `viday_app_user` (см. `application.yml`) и на каждый HTTP-запрос выставляет PostgreSQL-роль через `SET ROLE` + `app.current_user_id` (см. `RlsContextFilter`). Учётная запись `viday_admin_user` остаётся для миграций и ручного администрирования БД.

---

## 3. Сценарии получения ролей

### Гость → Пользователь

```http
POST /api/auth/register
{ "username": "alice", "password": "secret" }
```

Результат: запись в `viday.user` с `role_id` → `USER`, ответ без обязательного логина (можно сразу `POST /api/auth/login`).

### Пользователь → Контент-мейкер

```http
POST /api/users/me/channel/activate
Authorization: Bearer <token USER>
```

Условия: текущая роль в JWT — `USER`.  
После успеха — обновить токен (в нём останется старая роль):

```http
POST /api/users/me/token/refresh
Authorization: Bearer <старый token>
```

Ответ: новый JWT с ролью `CREATOR`. Без refresh загрузка видео вернёт `CreatorRequired`.

### Первый администратор (ручной шаг)

В API **нет** публичной регистрации в ADMIN. Первого админа задают в PostgreSQL:

```sql
UPDATE viday."user"
SET rolyfe_id = (SELECT id FROM viday.role WHERE name = 'ADMIN')
WHERE username = 'alice';
```

Либо при создании пользователя сразу с ролью ADMIN (лабораторная среда).

После этого `alice` логинится и может назначать других:

```http
POST /api/admin/users/bob/promote-admin
Authorization: Bearer <token ADMIN>
```

### Назначение аналитика

Только ADMIN:

```http
POST /api/admin/users/carol/promote-analyst
Authorization: Bearer <token ADMIN>
```

`carol` входит в систему — в JWT роль `ANALYST`, доступны `GET /api/analytics/views`, `/views/top`, `/views/{contentId}`.

Альтернатива для отчётов напрямую в БД: подключение как `viday_analyst_user` / пароль `analyst` (см. `03_roles.sql`).

---

## 4. API по ролям (кратко)


| Метод  | Путь                                          | Роль                 |
| ------ | --------------------------------------------- | -------------------- |
| POST   | `/api/users/me/channel/activate`              | USER                 |
| POST   | `/api/users/me/token/refresh`                 | любой авторизованный |
| DELETE | `/api/admin/users/{id}`                       | ADMIN                |
| DELETE | `/api/admin/contents/{id}`                    | ADMIN                |
| DELETE | `/api/admin/playlists/{id}`                   | ADMIN                |
| POST   | `/api/admin/users/{username}/promote-admin`   | ADMIN                |
| POST   | `/api/admin/users/{username}/promote-analyst` | ADMIN                |
| GET    | `/api/analytics/views`                        | ANALYST, ADMIN       |
| GET    | `/api/analytics/views/top`                    | ANALYST, ADMIN       |
| GET    | `/api/analytics/views/{contentId}`            | ANALYST, ADMIN       |


---``

## 5. Статистика просмотров

Счётчик увеличивается триггером `trg_increment_view_on_favorite` при добавлении записи в `user_to_playlist` (контент в «избранное» плейлиста пользователя). Аналитик читает агрегат `content_view_stats` без доступа к паролям и приватным полям пользователей (на уровне PG — политика `SELECT (id, username, created_at)`).