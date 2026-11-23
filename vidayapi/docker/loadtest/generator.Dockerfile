FROM gradle:9.4.1-jdk17 AS build
WORKDIR /src
COPY --chown=gradle:gradle . .
RUN GRADLE_OPTS="-Xmx1024m -Dorg.gradle.daemon=false" \
    gradle :loadtest:installDist --no-daemon --console=plain --max-workers=2 -x test

FROM eclipse-temurin:17-jre
ARG RUN_ID=local
LABEL org.opencontainers.image.title="viday-lr4-coroutines" \
      com.viday.loadtest.run_id="${RUN_ID}"
WORKDIR /app
COPY --from=build /src/loadtest/build/install/loadtest /app/generator
RUN useradd --system --uid 12345 --user-group loadtest
USER 12345:12345
ENTRYPOINT ["/app/generator/bin/loadtest"]
