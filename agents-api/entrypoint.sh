#!/bin/sh
# Compose OTEL_RESOURCE_ATTRIBUTES from per-attribute env vars at
# container start. Kubernetes env-var interpolation (`value: "$(FOO)"`)
# only references other env vars declared *in the manifest*, not env
# vars baked into the image — so a Dockerfile `ENV SERVICE_VERSION=…`
# cannot reach `OTEL_RESOURCE_ATTRIBUTES` set in deploy.yml directly.
# This shim is the cheap way to keep the image-baked build SHA and let
# the manifest still set the environment label per-cluster.
#
# `SERVICE_VERSION` defaults to whatever the image bakes (the GIT_SHA
# from the build), but a K8s manifest can still override it for
# e.g. local-dev pods that aren't built through CI.
set -eu

attrs="service.version=${SERVICE_VERSION:-unknown}"
if [ -n "${DEPLOYMENT_ENVIRONMENT:-}" ]; then
  attrs="${attrs},deployment.environment=${DEPLOYMENT_ENVIRONMENT}"
fi

# If the manifest set OTEL_RESOURCE_ATTRIBUTES explicitly, append our
# attributes to it rather than overwriting — lets operators add ad-hoc
# attributes via the manifest without losing the image-baked version.
if [ -n "${OTEL_RESOURCE_ATTRIBUTES:-}" ]; then
  export OTEL_RESOURCE_ATTRIBUTES="${OTEL_RESOURCE_ATTRIBUTES},${attrs}"
else
  export OTEL_RESOURCE_ATTRIBUTES="${attrs}"
fi

exec java "$@"
