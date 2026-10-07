# =============================================================================
# Контейнер запуска тестов (Требование 1 ЛР2).
# В образ НЕ копируется исходный код: его монтируют в /workspace (CI и compose).
# Node — официальный бинарник, без apt npm (тот тянет webpack и минуты сборки).
# =============================================================================
FROM gradle:8.14-jdk17

ARG NODE_VERSION=22.22.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client curl jq xz-utils \
    && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.xz" \
        | tar -xJ -C /usr/local --strip-components=1 \
    && rm -rf /var/lib/apt/lists/* \
    && node -v \
    && rm -f /usr/local/lib/node_modules/npm/bin/npm-cli.js

WORKDIR /workspace
COPY docker/test-runner/pipeline.sh /workspace/docker/test-runner/pipeline.sh
COPY scripts /workspace/scripts
RUN chmod +x /workspace/docker/test-runner/pipeline.sh /workspace/scripts/*.sh

ENTRYPOINT ["/workspace/docker/test-runner/pipeline.sh"]
