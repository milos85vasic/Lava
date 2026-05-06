#!/usr/bin/env bash
# deployment/thinker/thinker-up.sh — bring up the Lava Go API stack on thinker.local.
#
# Idempotent: stops + recreates lava-postgres-thinker + lava-api-go-thinker
# if they already exist. Reads its config from the .env file shipped to the
# remote alongside the image tarball.
#
# Run locally on thinker.local OR via:
#   ssh thinker.local bash -s < deployment/thinker/thinker-up.sh
#
# Constitutional bindings:
#   §6.J Anti-Bluff — set -euo pipefail propagates real failures
#   §6.M Host-Stability — rootless Podman; no host power-management
#   §6.B Container "Up" is not application-healthy — the script waits
#         for the /health endpoint to respond, not just for `podman ps`
#         to report Up.

set -euo pipefail

ENV_FILE="${LAVA_THINKER_ENV_FILE:-${HOME}/lava/thinker.local.env}"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
fi

: "${LAVA_THINKER_NETWORK:=lava-thinker}"
: "${LAVA_THINKER_API_PORT:=8443}"
: "${LAVA_THINKER_POSTGRES_PORT:=5432}"
: "${LAVA_THINKER_METRICS_PORT:=9091}"
: "${LAVA_THINKER_API_NAME:=lava-api-go-thinker}"
: "${LAVA_THINKER_POSTGRES_NAME:=lava-postgres-thinker}"
: "${LAVA_THINKER_API_CPUS:=2}"
: "${LAVA_THINKER_API_MEMORY:=2g}"
: "${LAVA_THINKER_POSTGRES_CPUS:=1}"
: "${LAVA_THINKER_POSTGRES_MEMORY:=1g}"
: "${LAVA_THINKER_POSTGRES_USER:=lava}"
: "${LAVA_THINKER_POSTGRES_PASSWORD:=lava-thinker-default}"
: "${LAVA_THINKER_POSTGRES_DB:=lava}"
: "${LAVA_THINKER_REMOTE_DIR:=$HOME/lava}"
: "${LAVA_THINKER_IMAGE_TAR:=/tmp/lava-api-go-thinker.image.tar}"
: "${LAVA_THINKER_TLS_CERT:=$HOME/lava/tls/server.crt}"
: "${LAVA_THINKER_TLS_KEY:=$HOME/lava/tls/server.key}"

API_IMAGE="${API_IMAGE:-localhost/lava-api-go:thinker}"

echo "==> Target host: $(hostname)"
echo "==> Remote dir:  $LAVA_THINKER_REMOTE_DIR"
echo "==> API image:   $API_IMAGE"

mkdir -p "$LAVA_THINKER_REMOTE_DIR"

# ----------------------------------------------------------------
# 1. Network
# ----------------------------------------------------------------
if ! podman network exists "$LAVA_THINKER_NETWORK"; then
    echo "==> Creating Podman network $LAVA_THINKER_NETWORK"
    podman network create "$LAVA_THINKER_NETWORK"
fi

# ----------------------------------------------------------------
# 2. Load image tarball
# ----------------------------------------------------------------
if [[ -f "$LAVA_THINKER_IMAGE_TAR" ]]; then
    echo "==> Loading image from $LAVA_THINKER_IMAGE_TAR"
    podman load -i "$LAVA_THINKER_IMAGE_TAR"
    # The tarball loads as `localhost/lava-api-go:dev`; re-tag for thinker.
    if podman image exists localhost/lava-api-go:dev; then
        podman tag localhost/lava-api-go:dev "$API_IMAGE" 2>/dev/null || true
    fi
fi

if ! podman image exists "$API_IMAGE"; then
    echo "FATAL: image $API_IMAGE not found on thinker after load." >&2
    exit 1
fi

# ----------------------------------------------------------------
# 3. Postgres
# ----------------------------------------------------------------
if podman container exists "$LAVA_THINKER_POSTGRES_NAME"; then
    echo "==> Removing existing $LAVA_THINKER_POSTGRES_NAME"
    podman rm -f "$LAVA_THINKER_POSTGRES_NAME" >/dev/null
fi
echo "==> Starting $LAVA_THINKER_POSTGRES_NAME"
podman run -d \
    --name "$LAVA_THINKER_POSTGRES_NAME" \
    --network "$LAVA_THINKER_NETWORK" \
    --restart unless-stopped \
    --cpus "$LAVA_THINKER_POSTGRES_CPUS" \
    --memory "$LAVA_THINKER_POSTGRES_MEMORY" \
    -p "127.0.0.1:${LAVA_THINKER_POSTGRES_PORT}:5432" \
    -e POSTGRES_USER="$LAVA_THINKER_POSTGRES_USER" \
    -e POSTGRES_PASSWORD="$LAVA_THINKER_POSTGRES_PASSWORD" \
    -e POSTGRES_DB="$LAVA_THINKER_POSTGRES_DB" \
    docker.io/library/postgres:16-alpine \
    >/dev/null

# Wait for Postgres to accept connections.
echo "==> Waiting for Postgres readiness"
for i in $(seq 1 30); do
    if podman exec "$LAVA_THINKER_POSTGRES_NAME" pg_isready -U "$LAVA_THINKER_POSTGRES_USER" -d "$LAVA_THINKER_POSTGRES_DB" >/dev/null 2>&1; then
        echo "    Postgres ready."
        break
    fi
    if [[ $i -eq 30 ]]; then
        echo "FATAL: Postgres not ready after 30s." >&2
        podman logs --tail 30 "$LAVA_THINKER_POSTGRES_NAME" >&2 || true
        exit 1
    fi
    sleep 1
done

# ----------------------------------------------------------------
# 4. lava-api-go
# ----------------------------------------------------------------
if podman container exists "$LAVA_THINKER_API_NAME"; then
    echo "==> Removing existing $LAVA_THINKER_API_NAME"
    podman rm -f "$LAVA_THINKER_API_NAME" >/dev/null
fi

# Mount TLS certs if present, otherwise the binary will fail at boot.
TLS_VOL_ARGS=()
if [[ -f "$LAVA_THINKER_TLS_CERT" && -f "$LAVA_THINKER_TLS_KEY" ]]; then
    TLS_VOL_ARGS+=("-v" "$(dirname "$LAVA_THINKER_TLS_CERT"):/etc/lava-api-go/tls:ro,Z")
fi

echo "==> Starting $LAVA_THINKER_API_NAME"
# network_mode=host so JmDNS / mDNS advertisements reach the LAN — Android
# clients running Lava discover this API via mDNS (_lava-api._tcp). Without
# host net, the container is on a bridge and mDNS frames never leave the
# bridge subnet. Postgres is published to 127.0.0.1:${POSTGRES_PORT} on
# the host, so api-go reaches it via 127.0.0.1 from inside host net.
PG_URL="postgres://${LAVA_THINKER_POSTGRES_USER}:${LAVA_THINKER_POSTGRES_PASSWORD}@127.0.0.1:${LAVA_THINKER_POSTGRES_PORT}/${LAVA_THINKER_POSTGRES_DB}?sslmode=disable"

# Phase 1 (2026-05-06): the new lava-api-go binary requires LAVA_AUTH_*
# at boot. Values come from the env-file sourced at the top of this
# script — distribute-api-remote.sh appends the operator's local .env
# auth/transport block before scp'ing. Pass each as `-e` if set.
AUTH_ENV_ARGS=()
for var in \
    LAVA_AUTH_FIELD_NAME \
    LAVA_AUTH_CURRENT_CLIENT_NAME \
    LAVA_AUTH_ACTIVE_CLIENTS \
    LAVA_AUTH_RETIRED_CLIENTS \
    LAVA_AUTH_HMAC_SECRET \
    LAVA_AUTH_BACKOFF_STEPS \
    LAVA_AUTH_TRUSTED_PROXIES \
    LAVA_AUTH_MIN_SUPPORTED_VERSION_NAME \
    LAVA_AUTH_MIN_SUPPORTED_VERSION_CODE \
    LAVA_API_HTTP3_ENABLED \
    LAVA_API_BROTLI_QUALITY \
    LAVA_API_BROTLI_RESPONSE_ENABLED \
    LAVA_API_BROTLI_REQUEST_DECODE_ENABLED \
    LAVA_API_PROTOCOL_METRIC_ENABLED \
; do
    if [[ -n "${!var:-}" ]]; then
        AUTH_ENV_ARGS+=( -e "$var=${!var}" )
    fi
done

podman run -d \
    --name "$LAVA_THINKER_API_NAME" \
    --network host \
    --restart unless-stopped \
    --cpus "$LAVA_THINKER_API_CPUS" \
    --memory "$LAVA_THINKER_API_MEMORY" \
    -e LAVA_API_PG_URL="$PG_URL" \
    -e LAVA_API_LISTEN=":${LAVA_THINKER_API_PORT}" \
    -e LAVA_API_TLS_CERT=/etc/lava-api-go/tls/server.crt \
    -e LAVA_API_TLS_KEY=/etc/lava-api-go/tls/server.key \
    "${AUTH_ENV_ARGS[@]}" \
    "${TLS_VOL_ARGS[@]}" \
    "$API_IMAGE" \
    >/dev/null

# Wait for /health to respond.
echo "==> Waiting for /health on https://localhost:${LAVA_THINKER_API_PORT}"
for i in $(seq 1 60); do
    if curl -fsSk "https://localhost:${LAVA_THINKER_API_PORT}/health" >/dev/null 2>&1; then
        echo "    /health alive."
        break
    fi
    if [[ $i -eq 60 ]]; then
        echo "FATAL: /health did not respond after 60s." >&2
        podman logs --tail 60 "$LAVA_THINKER_API_NAME" >&2 || true
        exit 1
    fi
    sleep 1
done

echo "==> Done. lava-api-go is up on thinker.local:${LAVA_THINKER_API_PORT}"
podman ps --filter "name=lava-" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
