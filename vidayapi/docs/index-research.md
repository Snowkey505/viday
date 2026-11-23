# Исследование влияния индексов на время доступа к БД

## Цель

Сравнить время выполнения типовых запросов Viday при выборке видеопотоков **с индексами** и **без** (принудительный sequential scan).

## Подготовка

1. Поднять PostgreSQL: `cd docker && docker compose up -d postgres`
2. Убедиться, что применены `init/05_indexes.sql` (индексы) и справочники.

## Сценарий замеров

| Шаг | Скрипт | Назначение |
|-----|--------|------------|
| 1 | `research/01_index_performance_test.sql` | Генерация ~10k контента, EXPLAIN ANALYZE вручную |
| 2 | `research/03_benchmark_timing.sql` | Автозамер в `index_performance_results` + коэффициент ускорения |
| 3 | `research/02_generate_report.sql` | Markdown-отчёт по `pg_stat_*` |

Из корня `vidayapi/`:

```bash
make benchmark-db          # 01 → 03 → 02
make benchmark-db-copy     # CSV с хоста контейнера
```

Нужен запущенный контейнер `viday_postgres`. Init-скрипты в `docker/postgres/init/` применяются при первом старте контейнера.

## Методика

- **Без индекса:** `SET enable_indexscan = off; SET enable_bitmapscan = off;` (локально в функции `viday.benchmark_query`).
- **С индексом:** стандартный планировщик PostgreSQL.
- Метрика: wall-clock (`clock_timestamp`) в миллисекундах, число возвращённых строк.
- Дополнительно: `EXPLAIN (ANALYZE, BUFFERS)` из скрипта 01 для планов и `idx_scan` из `pg_stat_user_indexes`.

## Ожидаемые запросы курсовой

1. Лента публичного контента (`access_type_id`, `created_at`).
2. Видео автора (`owner_id`, `access_type_id`).
3. Поиск по названию (GIN `pg_trgm`).
4. Содержимое плейлиста (`playlist_id`, `position`).
5. Подбор качества (`media_variant` по `content_id`, `bitrate`).

## Многомерная оценка (объём БД → время запроса)

Для кусочно-линейных графиков выбраны **3 наиболее показательных** запроса:

| № | Запрос | Индекс | Зачем в графике |
|---|--------|--------|-----------------|
| Q1 | Публичная лента `ORDER BY created_at LIMIT 20` | `idx_content_access_created` | Главная страница, рост с числом строк `content` |
| Q2 | `name ILIKE '%Video Title 5%'` | `idx_content_name_trgm` (GIN) | Поиск: без индекса — seq scan, с GIN — заметное ускорение на больших N |
| Q3 | JOIN `content` + `content_to_playlist` по `playlist_id=100` | `idx_content_to_playlist_position` | Типичный сценарий плейлиста |

**Объёмы наполнения** (строк в `content`): 1 000 → 5 000 → 10 000 → 50 000 → 100 000 → 250 000 → 500 000 → 1 000 000.

**Таблицы результатов** (в схеме `viday`):

- `benchmark_q1_public_feed`
- `benchmark_q2_name_search`
- `benchmark_q3_playlist_join`

Представления для графиков (среднее по 3 прогонам): `benchmark_q1_public_feed_chart`, `benchmark_q2_name_search_chart`, `benchmark_q3_playlist_join_chart`.

### Запуск

```bash
cd vidayapi
# Полный прогон (1k…1M, может занять 20–40 мин)
make benchmark-multidim

# Одна точка для проверки
make benchmark-multidim-one N=10000

# CSV на хост (после прогона)
make benchmark-multidim-copy
```

Файлы для построения графиков:

- `benchmark_q1_public_feed.csv` — ось X: `content_rows`, Y: `avg_execution_time_ms`, две серии: `with_index` true/false
- `benchmark_q2_name_search.csv`
- `benchmark_q3_playlist_join.csv`

Сырые прогоны: `research_output/benchmark_*_raw.csv`.

### Построение графика (Python / Excel)

- X: `content_rows` (логарифмическая шкала удобна: 1e3 … 1e6).
- Y: `avg_execution_time_ms`.
- Две линии: `with_index = true` (с индексами), `with_index = false` (принудительный sequential scan).
