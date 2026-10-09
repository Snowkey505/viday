#!/usr/bin/env bash
# =============================================================================
# Принудительный откат тестового хранилища в исходное (пустое) состояние.
#
# Покрывает требования:
#   * Требование 4  — состояние БД восстанавливается откатом (TRUNCATE + RESTART
#                     IDENTITY) до состояния, бывшего до прогона тестов;
#   * Требование 10 — принудительный откат после integration/e2e (в т.ч. при сбое);
#   * Требование 14 — повторяемость: каждый прогон начинает с одного и того же
#                     пустого состояния, результаты не меняются.
#
# Параметры подключения берутся из окружения (те же, что у интеграционных тестов):
#   VIDAY_TEST_JDBC_URL       (по умолчанию jdbc:postgresql://localhost:55432/viday)
#   VIDAY_TEST_DB_USER        (по умолчанию viday_app_user)
#   VIDAY_TEST_DB_PASSWORD    (по умолчанию app_password_change_me)
# =============================================================================
set -euo pipefail

JDBC_URL="${VIDAY_TEST_JDBC_URL:-jdbc:postgresql://localhost:55432/viday}"
# viday_admin_user — суперпользователь стенда (владелец таблиц/последовательностей),
# единственный, кто может выполнять TRUNCATE ... RESTART IDENTITY (откат, Треб. 4/10/14).
USER="${VIDAY_TEST_DB_USER:-viday_admin_user}"
PASSWORD="${VIDAY_TEST_DB_PASSWORD:-admin_password_change_me}"

# Разбор jdbc:postgresql://host:port/dbname
URL="${JDBC_URL#jdbc:postgresql://}"
HOST="${URL%%:*}"
REST="${URL#*:}"
PORT="${REST%%/*}"
DB="${REST#*/}"
PORT="${PORT:-5432}"

echo "reset test storage: host=$HOST port=$PORT db=$DB user=$USER"

export PGPASSWORD="$PASSWORD"
psql -h "$HOST" -p "$PORT" -U "$USER" -d "$DB" -v ON_ERROR_STOP=1 <<'SQL'
TRUNCATE TABLE
    viday.user_follows,
    viday.content_to_playlist,
    viday.user_to_playlist,
    viday.media_variant,
    viday.video,
    viday.stream,
    viday.content,
    viday.playlist,
    viday."user",
    viday.content_view_stats
RESTART IDENTITY CASCADE;
SQL

echo "OK: business tables truncated, sequences restarted (state == before tests)"