# =============================================================================
# ЛР4 — образ целевого приложения vidayapi для нагрузочного стенда.
#
# На каждый прогон scripts/run-loadtest.sh собирает ОТДЕЛЬНЫЙ образ с уникальным
# тегом (LT_APP_IMAGE=viday-loadtest-app:<run-id>). Dockerfile и все параметры
# сборки при этом не меняются, поэтому условия прогонов сопоставимы.
#
# Отличия от docker/Dockerfile.app (демонстрационного):
#   • gradle той же версии, что в gradle-wrapper (9.4.1), без скачивания wrapper;
#   • только JRE + curl (healthcheck), без tcpdump/ffmpeg/jq;
#   • ограничение памяти сборки, чтобы стенд не «съел» весь хост;
#   • фиксированные JVM-параметры задаются в docker-compose.loadtest.yml.
# =============================================================================

# ---- стадия сборки: bootJar приложения ----
FROM gradle:9.4.1-jdk17 AS build
WORKDIR /src
COPY --chown=gradle:gradle . .
# -x test: тесты не нужны для нагрузочного образа; сборка заметно быстрее.
RUN GRADLE_OPTS="-Xmx1536m -Dorg.gradle.daemon=false" \
    gradle :presentation:bootJar --no-daemon --console=plain --max-workers=2 -x test

# ---- стадия запуска: JRE 17 + curl для Docker healthcheck ----
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/presentation/build/libs/viday-application-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
