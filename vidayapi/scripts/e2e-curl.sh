#!/usr/bin/env bash
# =============================================================================
# Имитация действий E2E-сценария средством отправки запросов (curl) — Задание 5 ЛР2.
#
# Сценарий повторяет JUnit E2E (presentation/src/test/.../DemoMvpE2ETest.kt):
#   регистрация креатора -> логин (JWT) -> активация канала -> загрузка видео ->
#   создание плейлиста -> добавление контента -> регистрация подписчика ->
#   подписка на креатора -> проверка ленты и доступных плейлистов.
#
# Требует поднятый стенд и приложение:
#   docker compose -f docker/docker-compose.test.yml up -d
#   docker compose -f docker/docker-compose.app.yml up -d --build
#   (или запущенное локально приложение: make run)
#
# Зависимости: curl, jq.
# Результаты запросов сохраняются в traffic/e2e-curl-<ts>.json
# =============================================================================
set -euo pipefail

BASE="${VIDAY_API_BASE:-http://localhost:8080}"
OUT_DIR="traffic"
mkdir -p "$OUT_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"
CREATOR="creator_lr2_$STAMP"
FOLLOWER="follower_lr2_$STAMP"
PASSWORD="Secret${STAMP}!"

OUT="$OUT_DIR/e2e-curl-$STAMP.json"
: > "$OUT"

say()  { echo "[$(date +%T)] $*"; }
dump() { # dump <имя шага> <файл с body>
  if [ -s "$1" ]; then
    jq -c . "$1" 2>/dev/null || cat "$1"
  fi
}

# --- 1. Регистрация креатора ------------------------------------------------
say "1. register creator: $CREATOR"
REG_CREATOR="$(curl -sS -w '\n%{http_code}' -X POST "$BASE/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CREATOR\",\"password\":\"$PASSWORD\"}")"
CREATOR_HTTP="${REG_CREATOR##*$'\n'}"
CREATOR_ID="$(echo "${REG_CREATOR%$'\n'*}" | jq -r .id)"
[ "$CREATOR_HTTP" = "201" ] && [ -n "$CREATOR_ID" ] || { echo "FAIL register creator ($CREATOR_HTTP)"; exit 1; }
echo "  -> id=$CREATOR_ID" | tee -a "$OUT"

# --- 2. Логин креатора ------------------------------------------------------
say "2. login creator -> JWT"
LOGIN="$(curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$CREATOR\",\"password\":\"$PASSWORD\"}")"
CREATOR_TOKEN="$(echo "$LOGIN" | jq -r .token)"
[ -n "$CREATOR_TOKEN" ] && [ "$CREATOR_TOKEN" != "null" ] || { echo "FAIL login"; exit 1; }
echo "$LOGIN" >> "$OUT"

# --- 3. Активация канала ----------------------------------------------------
say "3. activate channel (USER -> CREATOR)"
ACT="$(curl -sS -X POST "$BASE/api/users/me/channel/activate" -H "Authorization: Bearer $CREATOR_TOKEN")"
echo "$ACT" >> "$OUT"
echo "$ACT" | jq -e '.user.role == "CREATOR"' >/dev/null || { echo "FAIL activate channel: $ACT"; exit 1; }
echo "  -> role CREATOR" | tee -a "$OUT"

# --- 4. Загрузка видео (multipart) ------------------------------------------
say "4. upload video (multipart, реальный ffprobe из образа)"
VIDEO_FILE="$OUT_DIR/clip-$STAMP.mp4"
PREVIEW_FILE="$OUT_DIR/preview-$STAMP.jpg"
# Генерируем настоящий мини-mp4 (ffmpeg есть в образе приложения и на хосте)
if command -v ffmpeg >/dev/null 2>&1; then
  ffmpeg -y -v error -f lavfi -i testsrc=duration=1:size=128x72:rate=10 \
    -pix_fmt yuv420p -c:v libx264 "$VIDEO_FILE" 2>/dev/null
fi
[ -s "$VIDEO_FILE" ] || { echo "SKIP upload: ffmpeg недоступен для генерации видео"; VIDEO_FILE=""; }

if [ -n "$VIDEO_FILE" ]; then
  printf '\xff\xd8\xff\xe0\x00\x10JFIF' > "$PREVIEW_FILE"
  UPLOAD="$(curl -sS -w '\n%{http_code}' -X POST "$BASE/api/videos/upload" \
    -H "Authorization: Bearer $CREATOR_TOKEN" \
    -F "file=@$VIDEO_FILE;type=video/mp4" \
    -F "preview=@$PREVIEW_FILE;type=image/jpeg" \
    -F "name=MVP clip $STAMP" \
    -F "description=e2e curl demo" \
    -F "accessType=PUBLIC")"
  UPLOAD_HTTP="${UPLOAD##*$'\n'}"
  VIDEO_JSON="${UPLOAD%$'\n'*}"
  CONTENT_ID="$(echo "$VIDEO_JSON" | jq -r .id)"
  [ "$UPLOAD_HTTP" = "200" ] && [ -n "$CONTENT_ID" ] && [ "$CONTENT_ID" != "null" ] \
    || { echo "FAIL upload ($UPLOAD_HTTP): $VIDEO_JSON"; exit 1; }
  echo "$VIDEO_JSON" >> "$OUT"
  echo "  -> contentId=$CONTENT_ID duration=$(echo "$VIDEO_JSON" | jq -r .durationSeconds)" | tee -a "$OUT"
else
  CONTENT_ID=""
fi

# --- 5. Создание плейлиста --------------------------------------------------
say "5. create public playlist"
PL_NAME="Demo playlist $STAMP"
PL="$(curl -sS -w '\n%{http_code}' -X POST "$BASE/api/playlists" \
  -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$PL_NAME\",\"accessType\":\"PUBLIC\"}")"
PL_HTTP="${PL##*$'\n'}"
PL_JSON="${PL%$'\n'*}"
PLAYLIST_ID="$(echo "$PL_JSON" | jq -r .id)"
[ "$PL_HTTP" = "201" ] && [ "$PLAYLIST_ID" != "null" ] || { echo "FAIL create playlist ($PL_HTTP): $PL_JSON"; exit 1; }
echo "$PL_JSON" >> "$OUT"
echo "  -> playlistId=$PLAYLIST_ID" | tee -a "$OUT"

# --- 6. Добавление контента в плейлист --------------------------------------
if [ -n "$CONTENT_ID" ]; then
  say "6. add content $CONTENT_ID to playlist $PLAYLIST_ID"
  ADD="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/playlists/$PLAYLIST_ID/contents" \
    -H "Authorization: Bearer $CREATOR_TOKEN" -H 'Content-Type: application/json' \
    -d "{\"contentId\":$CONTENT_ID,\"position\":1}")"
  [ "$ADD" = "200" ] || { echo "FAIL add content ($ADD)"; exit 1; }
  echo "  -> 200 OK" | tee -a "$OUT"
else
  say "6. SKIP add content (не было загрузки видео)"
fi

# --- 7. Подписчик: регистрация + логин + подписка ----------------------------
say "7. register follower + login + follow creator"
F_REG="$(curl -sS -w '\n%{http_code}' -X POST "$BASE/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$FOLLOWER\",\"password\":\"$PASSWORD\"}")"
F_HTTP="${F_REG##*$'\n'}"
[ "$F_HTTP" = "201" ] || { echo "FAIL register follower"; exit 1; }

F_LOGIN="$(curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$FOLLOWER\",\"password\":\"$PASSWORD\"}")"
F_TOKEN="$(echo "$F_LOGIN" | jq -r .token)"

FOLLOW="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$BASE/api/users/$CREATOR_ID/follow" \
  -H "Authorization: Bearer $F_TOKEN")"
[ "$FOLLOW" = "200" ] || { echo "FAIL follow ($FOLLOW)"; exit 1; }
echo "  -> subscribed to $CREATOR_ID" | tee -a "$OUT"

# --- 8. Проверки: лента видео и доступные плейлисты --------------------------
say "8. verify: public feed contains the uploaded video"
FEED="$(curl -sS "$BASE/api/videos?page=0&size=50")"
echo "$FEED" >> "$OUT"
if [ -n "$CONTENT_ID" ]; then
  echo "$FEED" | jq -e --argjson id "$CONTENT_ID" '.items[]? | select(.id == $id)' >/dev/null \
    || { echo "FAIL: видео $CONTENT_ID нет в публичной ленте"; exit 1; }
  echo "  -> video $CONTENT_ID in feed" | tee -a "$OUT"
fi

say "9. verify: follower sees the creator's public playlist"
AVAILABLE="$(curl -sS "$BASE/api/playlists/available" -H "Authorization: Bearer $F_TOKEN")"
echo "$AVAILABLE" >> "$OUT"
echo "$AVAILABLE" | jq -e --arg name "$PL_NAME" 'any(.[]; .name == $name)' >/dev/null \
  || { echo "FAIL: плейлист '$PL_NAME' не виден подписчику"; exit 1; }
echo "  -> playlist visible to follower" | tee -a "$OUT"

echo
echo "E2E-CURL DEMO: OK"
echo "responses saved to $OUT"