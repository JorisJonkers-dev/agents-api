# syntax=docker/dockerfile:1

# Pin of the agent-kit commit whose generated container setup script this image
# installs its Agent Session tooling from. agent-kit renders the script from
# registry/estate-tooling.yaml, so bumping this pin is how tool versions move.
ARG AGENT_KIT_REF=019932f422f23f92d3280f248cf2217760016658
ARG AGENT_KIT_SETUP_SHA256=2ad88c7b2ee9ec9059582cd3d796c1a8861a1637876153d7292dce5b3af31337

# BellSoft Liberica CRaC JDK for the API's own JVM. The runtime stage is Debian
# because the setup script installs the Agent Session tooling with apt, and the
# bellsoft/liberica-runtime-container images are Alpaquita (no apt, glibc 2.43 —
# newer than any Debian release, so the JDK cannot be copied out of them either).
ARG CRAC_JDK_VERSION=21.0.12.1+2
ARG CRAC_JDK_SHA256_AMD64=287138f4077f051c46275b3932455ea7e3657795856261334aae8837531a6f98
ARG CRAC_JDK_SHA256_ARM64=d89f3835ea86c95090892cebc6e7169dd567f740b6bd6ffe8cb9ce3c6c19e64a

# The Gradle build produces an architecture-independent jar, so it runs on the
# builder's native platform. Without this the whole Kotlin build would run under
# QEMU for the arm64 image, taking far longer for a byte-identical artifact.
FROM --platform=$BUILDPLATFORM gradle:9.7.0-jdk21-alpine AS build
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
# variant has curl out of the box and is small.
# Only downloads a jar, so likewise native.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jre-alpine AS otel
RUN apk add --no-cache curl && \
    curl -fsSL -o /otel-javaagent.jar \
    "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.26.1/opentelemetry-javaagent.jar"

# run-as-agent is the only way the API crosses from `api` to `agent`, so it is
# compiled here and copied in as a binary: the runtime image never carries a
# compiler toolchain for it, nor libcap-dev. Built for the target platform, not
# the builder's — it is native code.
FROM debian:bookworm-slim AS helper
RUN set -eu; \
    apt-get update; \
    apt-get install -y --no-install-recommends gcc libc6-dev libcap-dev; \
    rm -rf /var/lib/apt/lists/*
COPY container/run-as-agent.c /run-as-agent.c
RUN gcc -O2 -Wall -Wextra -Werror -o /run-as-agent /run-as-agent.c -lcap

# Training-capable image. See auth-api/Dockerfile for the full rationale — runs
# CracTrainingRunner against local sidecar Postgres/Valkey/RabbitMQ and dumps a
# JVM checkpoint to /opt/crac/checkpoint. Used only by the crac-train CI
# workflow, which never needs the Agent Session tooling, so it stays on the
# upstream CRaC image rather than paying for the full runtime stage.
FROM bellsoft/liberica-runtime-container:jdk-21.0.12_11-crac-slim-glibc AS train
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

# ---------------------------------------------------------------------------
# Runtime: the agents image. It runs agents-api and holds every tool an Agent
# Session needs (ADR 0001). No secret material is baked into any layer — the
# entrypoint renders whatever Vault mounted at container start.
# ---------------------------------------------------------------------------
FROM debian:bookworm-slim

# Agent Session tooling, at the registry's pins. Root, build time, no secrets.
ARG AGENT_KIT_REF
ARG AGENT_KIT_SETUP_SHA256
ADD --checksum=sha256:${AGENT_KIT_SETUP_SHA256} --chmod=755 \
    https://raw.githubusercontent.com/JorisJonkers-dev/agent-kit/${AGENT_KIT_REF}/installer/setup-container.sh \
    /usr/local/sbin/setup-container.sh
# Installing the tools, and the script's own --version checks, leave per-user
# state behind in /root: Hermes logs, a gh device id, mise and go telemetry, a
# node compile cache. Delete it in THIS instruction. A later `rm` would only
# white it out of the merged filesystem — the layer still ships the files, and
# "inspecting the image layers" is exactly how that is found.
RUN set -eu; \
    /usr/local/sbin/setup-container.sh; \
    rm -rf /root/.hermes /root/.codex /root/.serena /root/.config /root/.local \
           /root/.cache /root/.npm /tmp/* /var/tmp/*

# setcap is ours, not the registry's. libcap-dev stays in the helper stage, so
# the runtime keeps only the shared library and the tool. tini comes from the
# registry today, but this image's ENTRYPOINT is what breaks if a future
# AGENT_KIT_REF drops it — and it breaks at start, not at build — so name it
# here too. Both installs are idempotent.
RUN set -eu; \
    apt-get update; \
    apt-get install -y --no-install-recommends libcap2-bin tini; \
    rm -rf /var/lib/apt/lists/*

# The API's JVM. Deliberately off PATH: Agent Sessions get the registry's
# temurin-21-jdk, and only the entrypoint names this one.
ARG CRAC_JDK_VERSION
ARG CRAC_JDK_SHA256_AMD64
ARG CRAC_JDK_SHA256_ARM64
ARG TARGETARCH
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) jdk_arch=amd64;  jdk_sha="${CRAC_JDK_SHA256_AMD64}" ;; \
      arm64) jdk_arch=aarch64; jdk_sha="${CRAC_JDK_SHA256_ARM64}" ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/jdk-crac.tar.gz \
      "https://github.com/bell-sw/Liberica/releases/download/${CRAC_JDK_VERSION}/bellsoft-jdk${CRAC_JDK_VERSION}-linux-${jdk_arch}-crac.tar.gz"; \
    printf '%s  %s\n' "${jdk_sha}" /tmp/jdk-crac.tar.gz | sha256sum -c -; \
    mkdir -p /opt/java/crac; \
    tar -xzf /tmp/jdk-crac.tar.gz -C /opt/java/crac --strip-components=1; \
    rm -f /tmp/jdk-crac.tar.gz; \
    /opt/java/crac/bin/java -version; \
    rm -rf /tmp/hsperfdata_*

# Two users share the container (ADR 0003). `api` runs the JVM and owns every
# secret; `agent` runs Agent Sessions and must not be able to read them.
#   /etc/agents-api/secrets  api:api 0700  — rendered at start, never in a layer
#   /workspaces              agent:agent   — Workspace volume mount point
#   /home/agent              agent:agent   — home volume mount point (Agent Login)
RUN set -eu; \
    groupadd --gid 10001 api; \
    useradd --uid 10001 --gid api --no-create-home --home-dir /app --shell /usr/sbin/nologin api; \
    groupadd --gid 10002 agent; \
    useradd --uid 10002 --gid agent --create-home --home-dir /home/agent --shell /bin/bash agent; \
    install -d -o api -g api -m 0700 /etc/agents-api/secrets; \
    install -d -o agent -g agent -m 0755 /workspaces; \
    chmod 0755 /home/agent

# The JVM runs as `api`, so it cannot setuid on its own. run-as-agent carries
# cap_setuid/cap_setgid as file capabilities and is executable only by the `api`
# group, which is what turns the pod's SETUID/SETGID grant into "the API may
# start an Agent Session as `agent`" and nothing wider.
COPY --from=helper /run-as-agent /usr/local/lib/agents-api/run-as-agent
RUN set -eu; \
    chown root:api /usr/local/lib/agents-api/run-as-agent; \
    chmod 0050 /usr/local/lib/agents-api/run-as-agent; \
    setcap cap_setuid,cap_setgid+ep /usr/local/lib/agents-api/run-as-agent

# git's credential-helper lookup is "git-credential-<name>" resolved off
# PATH, and run-as-agent rebuilds PATH to exactly
# /usr/local/bin:/usr/bin:/bin (#63) — so `-c credential.helper=agents-api`
# only resolves for an Agent Session if this lives here. Plain shell, not
# a setuid binary: it only round-trips bytes over a socket it can already
# read, so it carries no capability of its own.
COPY container/git-credential-agents-api /usr/local/bin/git-credential-agents-api
RUN chmod 0755 /usr/local/bin/git-credential-agents-api

WORKDIR /app
# Build-time identity surfaced in logs and tracing metadata.
ARG GIT_SHA=unknown
ENV SERVICE_VERSION=${GIT_SHA}
COPY --from=build /app/api/build/libs/*.jar app.jar
COPY --from=otel /otel-javaagent.jar otel-javaagent.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod 0755 /app/entrypoint.sh

EXPOSE 8082
# Nothing in this container runs as root. `api` renders its own secrets and
# reaches `agent` only through run-as-agent, so SETUID and SETGID are the whole
# capability set the pod has to grant.
USER api
# tini reaps the processes Agent Sessions leave behind; the API container is now
# a process tree, not a single JVM. `-g` signals the whole process group, but
# tini runs as `api` and an Agent Session's processes run as `agent`, so the
# kernel refuses those signals without CAP_KILL. Stopping Agent Sessions on a
# redeploy is the API's job through run-as-agent, not tini's.
ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/app/entrypoint.sh", \
    "-javaagent:otel-javaagent.jar", "-jar", "app.jar"]
