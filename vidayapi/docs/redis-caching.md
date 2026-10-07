# Redis-кеш и пагинация в Viday API

## 1. Назначение Redis

Redis используется как **in-memory СУБД** (кеш-слой) поверх **PostgreSQL** (реляционная СУБ, источник истины).

Паттерн: **cache-aside** (ленивое кеширование).

```
Чтение:  Redis → (miss) → PostgreSQL → запись в Redis → ответ
Запись:  PostgreSQL → инвалидация ключей Redis
```

PostgreSQL хранит нормализованные сущности и связи. Redis хранит **готовый JSON** ответа API (`PageResponse`, `VideoResponse`), чтобы не повторять JOIN и сборку DTO при каждом запросе.

---

## 2. Обоснование кешируемых данных

Кешировать имеет смысл только то, что одновременно:

1. **Читается часто** (много одинаковых GET от разных клиентов).
2. **Имеет мало ключей** или ключ предсказуем (`page` + `size`).
3. **Редко меняется** относительно чтений (или допустима короткая задержка актуальности).

### 2.1. Публичная лента видео

| | |
|---|---|
| **Ключ** | `cache:videos:public:{page}:{size}` |
| **TTL** | 5 минут |
| **Эндпоинт** | `GET /api/videos?page=&size=` (без `userId`, `playlistId`) |

**Почему:** главная страница платформы; один запрос обслуживает всех гостей. Без кеша каждый просмотр ленты — тяжёлый `SELECT` с JOIN (`content`, `video`, `media_variant`) и нагрузка на пул JDBC.

**Почему с пагинацией:** при 10 000+ видео нельзя класть всю ленту в один ключ Redis (память, сериализация, время ответа). Кешируется **страница** фиксированного размера (по умолчанию 20 записей).

### 2.2. Карточка одного видео

| | |
|---|---|
| **Ключ** | `cache:video:{id}` |
| **TTL** | 10 минут |
| **Эндпоинт** | `GET /api/videos/{id}` |

**Почему:** повторные открытия одного ролика; стабильный ключ; метаданные меняются редко.

### 2.3. Список live-трансляций

| | |
|---|---|
| **Ключ** | `cache:streams:live:{page}:{size}` |
| **TTL** | 30 секунд |
| **Эндпоинт** | `GET /api/streams/live` |

**Почему:** общий список «кто в эфире»; высокая частота опроса. Короткий TTL — статус меняется быстро (`LIVE` / `ENDED`).

### 2.4. Зарезервировано (ключи в коде, пока не подключено)

| Ключ | Назначение |
|------|------------|
| `cache:user:{id}` | Профиль автора (редкие изменения) |
| `cache:playlist:{id}` | Карточка плейлиста |
| `cache:follow:{a}:{b}` | Проверка подписки без JOIN в БД |

---

## 3. Что не кешируется

| Запрос | Причина |
|--------|---------|
| `GET /api/videos?userId=` | Персональная выборка, низкий hit rate |
| `GET /api/videos?playlistId=` | Зависит от состава плейлиста |
| `GET /api/playlists` | Пагинация идёт в PostgreSQL (плейлистов обычно меньше, чем видео) |
| Любая запись (POST, DELETE, upload) | Только PostgreSQL |

---

## 4. Алгоритм доступа к данным

### 4.1. Чтение списка (с пагинацией и кешем)

Реализация: `ListVideosUseCase`, `ListLiveStreamsUseCase`.

```
1. Нормализовать page (≥0), size (1..100)
2. cachePort.get("...:{page}:{size}")
3. если JSON найден → десериализация PageResponse → HTTP 200
4. иначе:
     a. COUNT(*) + SELECT ... LIMIT size OFFSET page*size  → PostgreSQL
     b. сборка PageResponse
     c. cachePort.set(key, json, TTL)
     d. HTTP 200
```

### 4.2. Чтение одного видео

```
1. GET cache:video:{id}
2. miss → findVideoByContentId → PostgreSQL
3. SET cache:video:{id} TTL 600s
```

### 4.3. Запись и инвалидация

```
1. INSERT/UPDATE/DELETE в PostgreSQL
2. deleteByPrefix("cache:videos:public:")   — все страницы ленты
3. delete("cache:video:{id}")              — при необходимости
4. deleteByPrefix("cache:streams:live:") — при изменении трансляций
```

Следующий GET после записи сделает cache miss и подтянет актуальные данные из PostgreSQL.

---

## 5. Пагинация: зачем и где включена

### 5.1. Целесообразность

| Список | Пагинация | Обоснование |
|--------|-----------|-------------|
| Публичные видео | **Да** | Основной каталог; рост O(n) без LIMIT недопустим |
| Видео автора | **Да** | У creator сотни роликов |
| Видео в плейлисте | **Да** | Длинные плейлисты |
| Live-трансляции | **Да** | Согласованность с лентой + меньший объём кеша |
| Публичные / свои плейлисты | **Да** | Единый API-контракт |
| Одно видео / один stream | Нет | Доступ по id |

Без пагинации кеш «всей ленты» раздувает память Redis и ухудшает latency при десериализации большого JSON.

### 5.2. Параметры API

| Параметр | По умолчанию | Ограничение |
|----------|--------------|-------------|
| `page` | `0` | ≥ 0 (нумерация с нуля) |
| `size` | `20` | 1 … 100 |

### 5.3. Формат ответа

```json
{
  "items": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 1523,
  "totalPages": 77,
  "hasNext": true,
  "hasPrevious": false
}
```

### 5.4. Примеры

```http
GET /api/videos?page=0&size=20
GET /api/videos?userId=5&page=1&size=10
GET /api/streams/live?page=0&size=20
GET /api/playlists?page=0&size=20
```

### 5.5. Реализация в БД

- Отдельный `COUNT(*)` и `SELECT ... ORDER BY ... LIMIT ? OFFSET ?`.
- Для видео — **один JOIN-запрос** на страницу (без N+1 `findVideoByContentId` в цикле).
- Индексы `idx_content_access_created`, `idx_content_owner_access` ускоряют сортировку и фильтрацию (см. `docker/postgres/init/05_indexes.sql`).

---

## 6. Что улучшается

| Метрика | Эффект |
|---------|--------|
| **Latency** | Hit в Redis — ответ из RAM, без диска и JOIN |
| **Нагрузка на PostgreSQL** | Повторные просмотры ленты/карточек не доходят до БД |
| **Память Redis** | Пагинация ограничивает размер значения (~20 объектов на ключ) |
| **Масштабирование read** | Горячие страницы обслуживаются кешем |

**Индексы PostgreSQL** и **Redis** решают разные задачи: индексы ускоряют miss и сложные фильтры; Redis убирает повторные одинаковые запросы целиком.

---

## 7. Конфигурация

```yaml
# presentation/src/main/resources/application.yml
viday:
  cache:
    enabled: true   # false → NoOpCacheAdapter (без Redis)

spring:
  data:
    redis:
      host: localhost
      port: 6379
```

Запуск: `cd docker && docker compose up -d postgres redis`

---

## 8. Связанные файлы

| Компонент | Путь |
|-----------|------|
| Ключи и TTL | `application/.../cache/CacheKeys.kt` |
| Список видео + кеш | `application/.../usecase/ListVideosUseCase.kt` |
| Live + кеш | `application/.../usecase/ListLiveStreamsUseCase.kt` |
| Модель страницы | `domain/.../model/Page.kt` |
| JDBC пагинация | `infrastructure/.../repository/JdbcContentRepository.kt` |
| Диаграмма PG ↔ Redis | `docs/diagrams/redis-postgres-interaction.puml` |
| Замеры индексов | `docs/index-research.md` |
