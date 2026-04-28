#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Lava Container Start Script
# ------------------------------------------------------------------------------
# Delegates to the Lava-domain CLI (tools/lava-containers) to bring up the
# Lava proxy service with full LAN discoverability via mDNS. The Lava CLI is
# thin glue; generic container-runtime concerns live in the upstream
# vasic-digital/Containers submodule (Submodules/Containers/, pinned).
#
# SP-2 Phase 12 / Task 12.1 added the profile-flag forwarding wiring:
#
#     ./start.sh                                  # default → --profile=api-go
#     ./start.sh --legacy                         # legacy Ktor proxy only
#     ./start.sh --both                           # api-go + legacy side by side
#     ./start.sh --with-observability             # +observability profile
#     ./start.sh --dev-docs                       # +dev-docs profile (Swagger UI)
#     ./start.sh --both --with-observability      # combine
#
# Multiple flags compose. The lava-containers binary is the source of truth
# for which profiles each switch maps to; this shell script merely parses
# CLI flags and forwards them.
# ------------------------------------------------------------------------------

CONTAINERS_BIN="$SCRIPT_DIR/tools/lava-containers/bin/lava-containers"

# Default profile.
PROFILE="api-go"
WITH_OBSERVABILITY=false
WITH_DEV_DOCS=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --legacy)
            PROFILE="legacy"
            shift
            ;;
        --both)
            PROFILE="both"
            shift
            ;;
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
Usage: $0 [--legacy|--both|--api-go] [--with-observability] [--dev-docs]

Default profile is api-go (the Go service). Combine flags freely:
  $0                                  # api-go only
  $0 --legacy                         # legacy Ktor proxy only
  $0 --both                           # api-go + legacy
  $0 --both --with-observability      # +Prometheus/Loki/Promtail/Tempo/Grafana
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
# Skipped for the legacy-only profile because the Ktor proxy doesn't need it.
if [ "$PROFILE" != "legacy" ]; then
    if [ ! -s "$SCRIPT_DIR/lava-api-go/docker/tls/server.crt" ] || [ ! -s "$SCRIPT_DIR/lava-api-go/docker/tls/server.key" ]; then
        bash "$SCRIPT_DIR/lava-api-go/scripts/gen-cert.sh"
    fi
fi

# LAVA_PG_PASSWORD is required by the api-go / both profiles' compose stanzas
# (see docker-compose.yml). Provide a deterministic LAN-only default if the
# operator hasn't set one in .env. Anything more sensitive than a LAN
# deployment should override via .env.
if [ "$PROFILE" != "legacy" ]; then
    if [ -f "$SCRIPT_DIR/.env" ] && ! grep -qE '^LAVA_PG_PASSWORD=' "$SCRIPT_DIR/.env"; then
        echo "LAVA_PG_PASSWORD=l@vAfl0wZ-pg" >> "$SCRIPT_DIR/.env"
        echo "[start.sh] appended default LAVA_PG_PASSWORD to .env (LAN-deployment use)"
    fi
fi

echo "========================================"
echo "  Lava Container Boot"
echo "  profile=$PROFILE observability=$WITH_OBSERVABILITY dev-docs=$WITH_DEV_DOCS"
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
