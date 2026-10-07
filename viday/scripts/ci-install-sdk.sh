#!/usr/bin/env bash
# =============================================================================
# Установка Android SDK в CI (GitHub Actions, джобы build/unit приложения viday).
#
# Образ eclipse-temurin (JDK) НЕ содержит Android SDK, а :app:testDebugUnitTest
# и :data:testDebugUnitTest требуют android.jar (platforms;android-36) и
# build-tools. Скрипт идемпотентен: если cmdline-tools уже установлены —
# повторная загрузка не выполняется (ускоряет прогоны, SDK кэшируется через
# actions/cache: viday/.android-sdk/).
#
# Переменные (значения по умолчанию соответствуют .github/workflows/ci.yml):
#   ANDROID_HOME        — каталог SDK (по умолчанию $HOME/.android-sdk);
#   ANDROID_PLATFORM    — платформа (по умолчанию android-36);
#   ANDROID_BUILD_TOOLS — build-tools (по умолчанию 36.0.0).
# =============================================================================
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/.android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

PLATFORM="${ANDROID_PLATFORM:-android-36}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-36.0.0}"
CMDLINE_VERSION="11076708" # commandlinetools-linux-11076708_latest.zip

# curl/unzip могут отсутствовать в минимальном JDK-образе
command -v curl >/dev/null 2>&1 || { apt-get update && apt-get install -y --no-install-recommends curl unzip; }
command -v unzip >/dev/null 2>&1 || { apt-get update && apt-get install -y --no-install-recommends unzip; }

mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Downloading Android cmdline-tools (${CMDLINE_VERSION})..."
  curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VERSION}_latest.zip" \
    -o /tmp/android-cmdline-tools.zip
  unzip -q /tmp/android-cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -f /tmp/android-cmdline-tools.zip
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "==> Accepting SDK licenses..."
yes | "$SDKMANAGER" --licenses >/dev/null || true

echo "==> Installing platform-tools, platforms;${PLATFORM}, build-tools;${BUILD_TOOLS}..."
"$SDKMANAGER" --install "platform-tools" "platforms;${PLATFORM}" "build-tools;${BUILD_TOOLS}"

echo "==> Android SDK ready at $ANDROID_HOME"