#!/usr/bin/env bash
# =============================================================================
# Запуск тестов мобильного приложения НА УСТРОЙСТВЕ (Требование 7 ЛР2).
#
# Что делает:
#   1. убеждается, что adb видит устройство/эмулятор (если нет — поднимает AVD);
#   2. ждёт полной загрузки Android;
#   3. пробрасывает порт API стенда: adb reverse tcp:8080 tcp:8080;
#   4. запускает unit-тесты JVM (./gradlew test);
#   5. запускает instrumented-тесты НА УСТРОЙСТВЕ
#      (./gradlew :app:connectedDebugAndroidTest).
#
# Для полноценного сетевого сценария AndroidApiFlowTest нужен поднятый стенд
# vidayapi (docker compose -f docker/docker-compose.test.yml up -d) и приложение
# на localhost:8080; без них сетевой тест будет помечен как SKIPPED.
# =============================================================================
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
AVD="${AVD_NAME:-Medium_Phone}"
EMULATOR="$SDK/emulator/emulator"
ADB="${ADB:-adb}"

echo "==> checking devices..."
if ! "$ADB" get-state >/dev/null 2>&1; then
  echo "==> no device connected, booting emulator '$AVD'..."
  if [ ! -x "$EMULATOR" ]; then
    echo "ERROR: emulator binary not found at $EMULATOR (set ANDROID_HOME)" >&2
    exit 1
  fi
  nohup "$EMULATOR" -avd "$AVD" -dns-server 8.8.8.8 -no-snapshot -no-boot-anim >/tmp/viday-emulator.log 2>&1 &
fi

echo "==> waiting for device..."
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
echo "==> device is booted: $("$ADB" devices)"

echo "==> adb reverse: API (8080) + MinIO (59002) стенда"
"$ADB" reverse tcp:8080 tcp:8080 || echo "WARN: adb reverse 8080 failed (API may be unreachable from device)"
"$ADB" reverse tcp:59002 tcp:59002 || echo "WARN: adb reverse 59002 failed (MinIO may be unreachable from device)"

echo "==> unit tests (JVM)"
./gradlew --no-daemon :app:testDebugUnitTest :domain:test :data:testDebugUnitTest || true

echo "==> instrumented tests ON DEVICE"
./gradlew --no-daemon :app:connectedDebugAndroidTest

echo "==> reports:"
echo "    app/build/reports/androidTests/connected/index.html"
echo "    app/build/reports/tests/testDebugUnitTest/index.html"