#!/usr/bin/env bash
# deployment/nezha/nezha-up.sh — bring up the whole Lava System on nezha.local.
#
# nezha is a DEDICATED heavy-testing node (i7, 64 GB, NVMe, /dev/kvm live,
# rootless Podman 5.7.1). It runs BOTH the prod and dev lava-api-go stacks
# plus observability + Cloudflare-mitigation, so heavy tests can exercise
# REAL production services (real Postgres, real auth/TLS, real trackers) and
# the KVM-accelerated Android emulator matrix can point at the live API.
#
# Idempotent: force-recreates each container if it already exists.
#
# Run ON nezha (the repo is at $HOME/Projects/Lava):
#   ssh nezha.local 'cd ~/Projects/Lava && bash deployment/nezha/nezha-up.sh'
#
# Prerequisites (one-time, done by the operator/agent during enablement):
#   - $HOME/Projects/Lava synced to the target commit (git reset --hard origin/master
#     + the 17 Go submodules initialised: git submodule update --init <paths>).
#   - $HOME/lava/nezha-secrets.env present (chmod 600) carrying LAVA_PG_PASSWORD,
#     LAVA_AUTH_*, and tracker creds — filtered from the operator's gitignored .env.
#     (Never committed; §6.H.)
#   - lava-api-go static binaries built natively: see the BUILD section below.
#
# Constitutional bindings:
#   §6.J Anti-Bluff — set -euo pipefail; real /health gating, not `podman ps`.
#   §6.B Container "Up" != healthy — every API stack waits on a real HTTP /health.
#   §6.H — no secrets in this file; all come from $HOME/lava/nezha-secrets.env.
#   §6.M — rootless Podman; no host power-management commands.
#   §6.R — ports/hosts live in deployment/nezha/nezha.local.env (the config source).

set -euo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$HOME/Projects/Lava}"
cd "$REPO_ROOT"

ENV_FILE="${LAVA_NEZHA_ENV_FILE:-$REPO_ROOT/deployment/nezha/nezha.local.env}"
SECRETS_FILE="${LAVA_NEZHA_SECRETS_FILE:-$HOME/lava/nezha-secrets.env}"
[[ -f "$ENV_FILE" ]] && { set -a; source "$ENV_FILE"; set +a; }
[[ -f "$SECRETS_FILE" ]] || { echo "FATAL: $SECRETS_FILE missing (§6.H secrets)"; exit 2; }
set -a; source "$SECRETS_FILE"; set +a

TLS_DIR="${LAVA_NEZHA_TLS_DIR:-$HOME/lava/tls}"
IMAGE="${LAVA_NEZHA_IMAGE:-localhost/lava-api-go:nezha}"
NET="${LAVA_NEZHA_NETWORK:-lava-nezha}"
MIGRATE="$HOME/go/bin/migrate"; command -v migrate >/dev/null 2>&1 && MIGRATE=migrate

# ----------------------------------------------------------------------------
# BUILD — static binaries (CGO_ENABLED=0 is MANDATORY: distroless/static has no
# glibc/ld-linux, so a dynamically-linked binary fails at exec with
# "No such file or directory". Forensic: nezha enablement 2026-06-16.) + image.
# ----------------------------------------------------------------------------
build_artifacts() {
  echo "==> Building lava-api-go static binaries (CGO_ENABLED=0)"
  ( cd lava-api-go && mkdir -p bin
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o bin/lava-api-go-linux ./cmd/lava-api-go
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o bin/healthprobe-linux ./cmd/healthprobe )
  echo "==> Building runtime image $IMAGE"
  podman build -f lava-api-go/docker/Dockerfile.host-built -t "$IMAGE" .
}

# ----------------------------------------------------------------------------
# TLS — per-host self-signed cert. 0644 so the distroless `nonroot` (UID 65532)
# container user can read it under rootless podman's user-namespace mapping.
# (Regenerable test cert on a dedicated test host — not a §6.H production key.)
# ----------------------------------------------------------------------------
ensure_tls() {
  mkdir -p "$TLS_DIR"
  if [[ ! -f "$TLS_DIR/server.crt" ]]; then
    echo "==> Generating per-host TLS for nezha.local"
    openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
      -keyout "$TLS_DIR/server.key" -out "$TLS_DIR/server.crt" \
      -subj "/CN=nezha.local" \
      -addext "subjectAltName=DNS:nezha.local,DNS:localhost,IP:127.0.0.1"
  fi
  chmod 644 "$TLS_DIR/server.crt" "$TLS_DIR/server.key"
}

wait_health() { # $1=url $2=label
  for i in $(seq 1 30); do
    curl -fsSk "$1" >/dev/null 2>&1 && { echo "    $2 /health OK (${i}s)"; return 0; }
    sleep 1
  done
  echo "    FATAL: $2 /health did not respond in 30s" >&2
  return 1
}

boot_api_stack() { # $1=name-suffix $2=pg-host-port $3=api-port $4=metrics-port $5=cpus $6=mem
  local sfx="$1" pgport="$2" apiport="$3" metport="$4" cpus="$5" mem="$6"
  local pgname="lava-postgres-nezha${sfx}" apiname="lava-api-go-nezha${sfx}"
  local pgurl="postgres://lava:${LAVA_PG_PASSWORD}@127.0.0.1:${pgport}/lava_api?sslmode=disable"
  echo "==> [${sfx:-prod}] postgres :$pgport"
  podman rm -f "$pgname" "$apiname" >/dev/null 2>&1 || true
  podman run -d --name "$pgname" --network "$NET" --restart unless-stopped --cpus "$cpus" --memory "$mem" \
    -p "127.0.0.1:${pgport}:5432" -e POSTGRES_USER=lava -e POSTGRES_PASSWORD="$LAVA_PG_PASSWORD" \
    -e POSTGRES_DB=lava_api docker.io/library/postgres:16-alpine >/dev/null
  for i in $(seq 1 30); do podman exec "$pgname" pg_isready -U lava -d lava_api >/dev/null 2>&1 && break; sleep 1; done
  echo "==> [${sfx:-prod}] migrations"
  "$MIGRATE" -path lava-api-go/migrations -database "$pgurl" up
  echo "==> [${sfx:-prod}] api-go :$apiport (host net)"
  podman run -d --name "$apiname" --network host --restart unless-stopped --cpus "$cpus" --memory "$mem" \
    -e LAVA_API_PG_URL="$pgurl" -e LAVA_API_LISTEN=":${apiport}" -e LAVA_API_METRICS_LISTEN=":${metport}" \
    -e LAVA_API_TLS_CERT=/etc/lava-api-go/tls/server.crt -e LAVA_API_TLS_KEY=/etc/lava-api-go/tls/server.key \
    -e LAVA_AUTH_FIELD_NAME="$LAVA_AUTH_FIELD_NAME" -e LAVA_AUTH_HMAC_SECRET="$LAVA_AUTH_HMAC_SECRET" \
    -e LAVA_AUTH_ACTIVE_CLIENTS="$LAVA_AUTH_ACTIVE_CLIENTS" -e LAVA_AUTH_RETIRED_CLIENTS="${LAVA_AUTH_RETIRED_CLIENTS:-}" \
    -e LAVA_AUTH_TRUSTED_PROXIES="${LAVA_AUTH_TRUSTED_PROXIES:-}" \
    -e LAVA_AUTH_MIN_SUPPORTED_VERSION_NAME="${LAVA_AUTH_MIN_SUPPORTED_VERSION_NAME:-1.2.6}" \
    -e LAVA_AUTH_MIN_SUPPORTED_VERSION_CODE="${LAVA_AUTH_MIN_SUPPORTED_VERSION_CODE:-1026}" \
    -v "$TLS_DIR:/etc/lava-api-go/tls:ro,Z" "$IMAGE" >/dev/null
  wait_health "https://127.0.0.1:${apiport}/health" "[${sfx:-prod}]"
}

boot_observability() {
  local OBS="$REPO_ROOT/lava-api-go/docker/observability"
  # Shared-host: host ports may differ from container ports to dodge co-tenant
  # collisions (llmsverifier_prometheus holds :9090). See nezha.local.env.
  local PROM_PORT="${LAVA_NEZHA_PROMETHEUS_PORT:-9190}"
  echo "==> observability (prometheus :$PROM_PORT / loki / tempo / grafana)"
  podman rm -f lava-prometheus lava-loki lava-tempo lava-grafana >/dev/null 2>&1 || true
  podman run -d --name lava-prometheus --network "$NET" --restart unless-stopped -p "127.0.0.1:${PROM_PORT}:9090" \
    -v "$OBS/prometheus.yml:/etc/prometheus/prometheus.yml:ro,Z" docker.io/prom/prometheus:v2.51.0 >/dev/null
  podman run -d --name lava-loki --network "$NET" --restart unless-stopped -p 127.0.0.1:3100:3100 \
    -v "$OBS/loki-config.yaml:/etc/loki/local-config.yaml:ro,Z" docker.io/grafana/loki:2.9.6 >/dev/null
  podman run -d --name lava-tempo --network "$NET" --restart unless-stopped -p 127.0.0.1:3200:3200 -p 127.0.0.1:4318:4318 \
    -v "$OBS/tempo.yaml:/etc/tempo.yaml:ro,Z" docker.io/grafana/tempo:2.4.0 -config.file=/etc/tempo.yaml >/dev/null
  podman run -d --name lava-grafana --network "$NET" --restart unless-stopped -p 127.0.0.1:3000:3000 \
    -e GF_AUTH_ANONYMOUS_ENABLED=true docker.io/grafana/grafana:10.4.2 >/dev/null
}

boot_cf_mitigation() {
  # NOTE: flaresolverr is only required for IPTorrents (Cloudflare). RuTracker /
  # RuTor / NNMClub / native providers do NOT need it. Jackett indexer
  # provisioning (api_key + per-indexer creds) is an operator step.
  echo "==> CF-mitigation (jackett + flaresolverr)"
  # Shared-host: jackett host port dodges boba-jackett's :9117. See nezha.local.env.
  local JACKETT_PORT="${LAVA_NEZHA_JACKETT_PORT:-9217}"
  mkdir -p "$HOME/lava/.jackett-config"
  podman rm -f lava-jackett lava-flaresolverr >/dev/null 2>&1 || true
  podman run -d --name lava-jackett --network "$NET" --restart unless-stopped -p "127.0.0.1:${JACKETT_PORT}:9117" \
    -e PUID=1000 -e PGID=1000 -e TZ=Etc/UTC -e AUTO_UPDATE=false \
    -v "$HOME/lava/.jackett-config:/config:Z" lscr.io/linuxserver/jackett:latest >/dev/null
  podman run -d --name lava-flaresolverr --network "$NET" --restart unless-stopped -p 127.0.0.1:8191:8191 \
    -e LOG_LEVEL=info -e HOST=0.0.0.0 -e PORT=8191 -e HEADLESS=true ghcr.io/flaresolverr/flaresolverr:latest >/dev/null
}

# ----------------------------------------------------------------------------
main() {
  podman network exists "$NET" || podman network create "$NET" >/dev/null
  [[ "${SKIP_BUILD:-0}" == "1" ]] || build_artifacts
  ensure_tls
  boot_api_stack ""     8432 8443 9091 4 4g     # prod
  boot_api_stack "-dev" 8433 8543 9092 2 2g     # dev
  [[ "${SKIP_OBSERVABILITY:-0}" == "1" ]] || boot_observability
  [[ "${SKIP_CF:-0}" == "1" ]] || boot_cf_mitigation
  echo "==> Done. Lava System on nezha.local:"
  podman ps --filter name=lava --format 'table {{.Names}}\t{{.Status}}' | sort
}
main "$@"
