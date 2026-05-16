#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Lava Container Start Script
# ------------------------------------------------------------------------------
# Delegates to the Lava-domain CLI (tools/lava-containers) to bring up the
# lava-api-go service with full LAN discoverability via mDNS. The Lava CLI is
# thin glue; generic container-runtime concerns live in the upstream
# vasic-digital/Containers submodule (submodules/containers/, pinned).
#
# 2026-05-06: the legacy Ktor proxy was removed. start.sh now drives the
# api-go profile only.
#
#     ./start.sh                                  # default → --profile=api-go
#     ./start.sh --with-observability             # +observability profile
#     ./start.sh --dev-docs                       # +dev-docs profile (Swagger UI)
#     ./start.sh --with-observability --dev-docs  # combine
#
# Multiple flags compose. The lava-containers binary is the source of truth
# for which profiles each switch maps to; this shell script merely parses
# CLI flags and forwards them.
# ------------------------------------------------------------------------------

CONTAINERS_BIN="$SCRIPT_DIR/tools/lava-containers/bin/lava-containers"

PROFILE="api-go"
WITH_OBSERVABILITY=false
WITH_DEV_DOCS=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --api-go)
            PROFILE="api-go"
            shift
            ;;
        --with-observability)
            WITH_OBSERVABILITY=true
            shift
            ;;
        --dev-docs)
            WITH_DEV_DOCS=true
            shift
            ;;
        -h|--help)
            cat <<EOF
Usage: $0 [--with-observability] [--dev-docs]

Brings up lava-api-go (the Go service). Combine flags freely:
  $0                                  # api-go only
  $0 --with-observability             # +Prometheus/Loki/Promtail/Tempo/Grafana
  $0 --dev-docs                       # +Swagger UI

The flags map 1:1 onto lava-containers' --profile / --with-observability /
--dev-docs flags; see tools/lava-containers/cmd/lava-containers/main.go.
EOF
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            echo "Try $0 --help" >&2
            exit 1
            ;;
    esac
done

if [ ! -f "$CONTAINERS_BIN" ]; then
    echo "[lava-containers] Building Lava containers CLI..."
    mkdir -p "$SCRIPT_DIR/tools/lava-containers/bin"
    cd "$SCRIPT_DIR/tools/lava-containers"
    go build -o "$CONTAINERS_BIN" ./cmd/lava-containers
    cd "$SCRIPT_DIR"
fi

# Provision TLS material for lava-api-go's HTTPS/HTTP3 listener if absent.
if [ ! -s "$SCRIPT_DIR/lava-api-go/docker/tls/server.crt" ] || [ ! -s "$SCRIPT_DIR/lava-api-go/docker/tls/server.key" ]; then
    bash "$SCRIPT_DIR/lava-api-go/scripts/gen-cert.sh"
fi

# LAVA_PG_PASSWORD is required by the api-go profile's compose stanza
# (see docker-compose.yml). Provide a deterministic LAN-only default if the
# operator hasn't set one in .env.
if [ -f "$SCRIPT_DIR/.env" ] && ! grep -qE '^LAVA_PG_PASSWORD=' "$SCRIPT_DIR/.env"; then
    echo "LAVA_PG_PASSWORD=l@vAfl0wZ-pg" >> "$SCRIPT_DIR/.env"
    echo "[start.sh] appended default LAVA_PG_PASSWORD to .env (LAN-deployment use)"
fi

# ------------------------------------------------------------------------------
# Force buildah/podman to produce docker-format images for any container that
# carries a HEALTHCHECK directive in its Dockerfile.
#
# Forensic anchor (2026-04-29): podman defaults to the OCI image format, which
# does NOT support HEALTHCHECK in image config. The directive is silently
# dropped at build time with the warning
#   "HEALTHCHECK is not supported for OCI image format and will be ignored.
#    Must use `docker` format"
# emitted to stderr. The lava-api-go runtime image's HEALTHCHECK was being
# stripped by every `podman compose up` lazy-build, leaving the container
# with NO probe — so the orchestrator reported "running" indefinitely while
# the application could in fact be crash-looping. Containers running with
# `Healthcheck: null` masked a class of bug as serious as the original
# `--http3` flag drift.
#
# Setting BUILDAH_FORMAT=docker globally for this script's invocation makes
# every podman/buildah build inside the lava-containers pipeline produce
# docker-format images that persist HEALTHCHECK in their config. This is
# the structural fix; lava-api-go/tests/contract/healthcheck_contract_test.go
# enforces the flag-set contract, but only an image-level HEALTHCHECK that
# survives the build can carry it into the running container.
# ------------------------------------------------------------------------------
export BUILDAH_FORMAT=docker

echo "========================================"
echo "  Lava Container Boot"
echo "  profile=$PROFILE observability=$WITH_OBSERVABILITY dev-docs=$WITH_DEV_DOCS"
echo "  BUILDAH_FORMAT=docker (forces image-level HEALTHCHECK persistence)"
echo "========================================"

# Build the lava-containers args. Use an array so spaces / future flags
# stay safe.
declare -a CONT_ARGS=(
    -cmd=start
    -project-dir="$SCRIPT_DIR"
    -profile="$PROFILE"
)
if $WITH_OBSERVABILITY; then
    CONT_ARGS+=(-with-observability)
fi
if $WITH_DEV_DOCS; then
    CONT_ARGS+=(-dev-docs)
fi

# Delegate to Go module
exec "$CONTAINERS_BIN" "${CONT_ARGS[@]}"
