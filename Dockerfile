# syntax=docker/dockerfile:1

FROM gradle:9.5.1-jdk21-alpine AS build
WORKDIR /app

# Layer 1: Copy only build scripts for dependency caching
COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY gradle/ gradle/
COPY api/build.gradle.kts api/
COPY client-spec/build.gradle.kts client-spec/

# Resolve dependencies (cached unless build files change)
RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :api:dependencies --no-daemon || true

# Layer 2: Copy source code and build
COPY api/src/main/ api/src/main/
RUN --mount=type=secret,id=github_token \
    --mount=type=secret,id=github_actor \
    set -eu; \
    GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
    GITHUB_ACTOR="$(cat /run/secrets/github_actor)"; \
    export GITHUB_TOKEN GITHUB_ACTOR; \
    gradle :api:bootJar --no-daemon

# Eclipse Temurin only used here for the otel jar download — its alpine
# variant has curl out of the box and is small. The runtime stage has
# moved to BellSoft Liberica below.
FROM eclipse-temurin:25-jre-alpine AS otel
RUN apk add --no-cache curl && \
    curl -fsSL -o /otel-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar"

# Runtime base: BellSoft Liberica JDK 21 with CRaC patches on Debian slim
# (glibc). See auth-api/Dockerfile for the full rationale — this PR is
# the precondition for the build-time checkpoint flow in follow-up PRs.
# AppCDS is intentionally dropped (Liberica CRaC's CDS handling differs
# and CRaC will replace it anyway). Cold start temporarily regresses
# to the pre-AppCDS baseline; #254 extends the 300 s startupProbe to
# 600 s to cover it and must merge first.
# Training-capable image. See auth-api/Dockerfile for the full
# rationale — runs CracTrainingRunner against local sidecar
# Postgres/Valkey/RabbitMQ and dumps a JVM checkpoint to
# /opt/crac/checkpoint. Used only by the crac-train CI workflow.
FROM bellsoft/liberica-runtime-container:jdk-21-crac-slim-glibc AS train
WORKDIR /app
COPY --from=build /app/api/build/libs/*.jar app.jar
COPY --from=otel /otel-javaagent.jar otel-javaagent.jar
RUN mkdir -p /opt/crac/checkpoint
EXPOSE 8082
ENTRYPOINT ["java", \
    "-XX:CRaCCheckpointTo=/opt/crac/checkpoint", \
    "-XX:+UseZGC", \
    "-XX:MaxRAMPercentage=75", \
    "-Dspring.profiles.active=crac-train", \
    "-javaagent:otel-javaagent.jar", \
    "-jar", "app.jar"]

FROM bellsoft/liberica-runtime-container:jdk-21-crac-slim-glibc
WORKDIR /app
# Build-time identity surfaced in logs and tracing metadata.
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA}
COPY --from=build /app/api/build/libs/*.jar app.jar
COPY --from=otel /otel-javaagent.jar otel-javaagent.jar
COPY entrypoint.sh /app/entrypoint.sh
EXPOSE 8082
ENTRYPOINT ["/app/entrypoint.sh", "-javaagent:otel-javaagent.jar", "-jar", "app.jar"]
