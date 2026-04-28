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
# ------------------------------------------------------------------------------

CONTAINERS_BIN="$SCRIPT_DIR/tools/lava-containers/bin/lava-containers"

if [ ! -f "$CONTAINERS_BIN" ]; then
    echo "[lava-containers] Building Lava containers CLI..."
    mkdir -p "$SCRIPT_DIR/tools/lava-containers/bin"
    cd "$SCRIPT_DIR/tools/lava-containers"
    go build -o "$CONTAINERS_BIN" ./cmd/lava-containers
    cd "$SCRIPT_DIR"
fi

echo "========================================"
echo "  Lava Container Boot"
echo "========================================"

# Delegate to Go module
exec "$CONTAINERS_BIN" -cmd=start -project-dir="$SCRIPT_DIR"
