# =============================================================================
# ЛР3 — контейнер микробенчмарка vidayapi.
#
# Требование 6: «испытаний будет много, надо добиться равных условий на каждую
# попытку, то есть следует использовать отдельный docker-образ на каждый прогон,
# но с одной и той же конфигурацией». Скрипт scripts/run-benchmark.sh для КАЖДОГО
# прогона собирает образ из этого Dockerfile (кэш слоёв Gradle делает пересборку
# дешёвой, конфигурация одна и та же) и запускает его с закреплением на 1 ядре
# (--cpuset-cpus=0 — Требование 4) и фиксированными параметрами JVM.
#
# Сборка:   docker build -f docker/benchmark/Dockerfile -t viday-bench:latest .
# Запуск:   docker run --rm --cpuset-cpus=0 -v $PWD/out:/out viday-bench:latest --out /out
# =============================================================================

# ---- стадия сборки: собираем runnable-дистрибутив модуля :benchmark ----
# Используем gradle-образ ТОЙ ЖЕ версии, что и gradle-wrapper проекта (9.4.1),
# и вызываем `gradle` напрямую (НЕ ./gradlew): wrapper в контейнере пытается
# скачать дистрибутив с services.gradle.org, что недоступно в офлайн-сборке.
FROM gradle:9.4.1-jdk17 AS build
WORKDIR /src
COPY --chown=gradle:gradle . .
RUN gradle :benchmark:installDist --no-daemon --console=plain

# ---- стадия запуска: тот же JRE, что и production-контейнер проекта ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/benchmark/build/install/benchmark /app/benchmark
# Фиксированная конфигурация JVM (куча + SerialGC) — одинаковая для каждого прогона.
ENV JAVA_OPTS="-Xms256m -Xmx256m -XX:+UseSerialGC -Dfile.encoding=UTF-8"
ENTRYPOINT ["/app/benchmark/bin/benchmark"]