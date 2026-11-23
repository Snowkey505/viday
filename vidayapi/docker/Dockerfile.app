# =============================================================================
# Контейнер приложения vidayapi на тестовом стенде (для демонстрации E2E-сценария
# средствами отправки запросов и захвата трафика — Задание 5 ЛР2).
#
# Сборка:  docker compose -f docker/docker-compose.app.yml up -d --build
# =============================================================================

# ---- стадия сборки: собираем bootJar (все модули, кроме тестов) ----
FROM gradle:8.14-jdk17 AS build
WORKDIR /src
COPY --chown=gradle:gradle . .
RUN ./gradlew :presentation:bootJar --no-daemon --console=plain

# ---- стадия запуска: JRE + tcpdump/curl/jq для захвата трафика ----
FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends tcpdump curl jq ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/presentation/build/libs/viday-application-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]