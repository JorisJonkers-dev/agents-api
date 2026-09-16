#!/bin/sh
# Container start for the agents image.
#
# Runs as `api` — never as root. Agent Sessions are started later, by the API,
# through /usr/local/lib/agents-api/run-as-agent, which is the only thing in the
# container that changes uid and the only reason the pod needs SETUID/SETGID
# (ADR 0003).
set -eu

# --- OpenTelemetry resource attributes -------------------------------------
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

# --- Secrets ---------------------------------------------------------------
# Vault renders its files into VAULT_SECRETS_DIR on a volume the `agent` user
# can also reach. Copy each one that is actually there into API_SECRETS_DIR,
# which the image created 0700 `api`, and leave the copy 0400. We write the
# copies ourselves rather than chown an injected file, so the container never
# needs CAP_CHOWN — SETUID and SETGID stay the whole capability set.
#
# Every file is optional. A key Vault did not render is a feature the API turns
# off, and a file this user cannot read is reported and skipped; neither is a
# reason not to start.
VAULT_SECRETS_DIR="${VAULT_SECRETS_DIR:-/vault/secrets}"
API_SECRETS_DIR="${API_SECRETS_DIR:-/etc/agents-api/secrets}"

if [ -d "${VAULT_SECRETS_DIR}" ] && [ -w "${API_SECRETS_DIR}" ]; then
  for src in "${VAULT_SECRETS_DIR}"/*; do
    # An unmatched glob stays literal, so test the path rather than the loop.
    [ -f "${src}" ] || continue
    name="$(basename "${src}")"
    dst="${API_SECRETS_DIR}/${name}"
    # A previous render left this 0400, which the redirection below cannot
    # truncate. Remove it first so a re-run refreshes rather than skips.
    rm -f "${dst}"
    if (umask 077 && cat < "${src}" > "${dst}") 2>/dev/null; then
      chmod 0400 "${dst}"
      echo "entrypoint: rendered secret ${name}"
    else
      rm -f "${dst}"
      echo "entrypoint: skipped unreadable secret ${name}" >&2
    fi
  done

  # Copying does not remove the originals, and the injector's directory is
  # world-readable by default — so `agent` could read at the source what it
  # cannot read at the destination. Close it if it is ours to close, and say so
  # loudly if it is not, because nothing else in the container will notice.
  if ! chmod 0700 "${VAULT_SECRETS_DIR}" 2>/dev/null; then
    echo "entrypoint: WARNING ${VAULT_SECRETS_DIR} is not ours to restrict —" \
         "the injected secrets stay readable to every user in this container." \
         "Set vault.hashicorp.com/agent-run-as-user to 10001 on the pod." >&2
  fi
fi

# Env-style secret files become environment for the JVM. `set -a` exports every
# assignment the file makes; nothing here is echoed, so a value never reaches
# the log.
for env_file in "${API_SECRETS_DIR}"/*.env; do
  [ -f "${env_file}" ] || continue
  set -a
  # shellcheck source=/dev/null
  . "${env_file}"
  set +a
done

# --- Hand over to the JVM --------------------------------------------------
# The CRaC JDK is deliberately off PATH: Agent Sessions get the tooling
# registry's temurin-21-jdk, and only this line names the API's own JVM.
exec "${JAVA:-/opt/java/crac/bin/java}" "$@"
