# syntax=docker/dockerfile:1

# Stage 1: build the fat jar
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy Gradle wrapper and build scripts first for better layer caching
COPY gradle/wrapper/ gradle/wrapper/
COPY gradlew .
COPY settings.gradle.kts .
COPY build.gradle.kts .
COPY gradle/libs.versions.toml gradle/libs.versions.toml

# Download dependencies and plugins before copying source
RUN ./gradlew --no-daemon --version

# Copy source and build the fat jar
COPY src src
RUN ./gradlew buildFatJar --no-daemon

# Stage 2: runtime image (non-distroless so the Litestream entrypoint script can run later)
FROM litestream/litestream:0.3.13 AS litestream

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=litestream /usr/local/bin/litestream /usr/local/bin/litestream
COPY --from=builder /build/build/libs/androidskills-api-all.jar androidskills-api.jar
COPY litestream.yml /etc/litestream.yml
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run as a non-root user. The host volume mounted at /data must be writable by
# UID/GID 1000 (or update the USER line to match the host volume ownership).
RUN groupadd -r app --gid 1000 && useradd -r -g app --uid 1000 app \
    && chown -R app:app /app /etc/litestream.yml

USER app

ENTRYPOINT ["/app/entrypoint.sh"]
