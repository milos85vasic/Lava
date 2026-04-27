#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Lava Container Start Script
# ------------------------------------------------------------------------------
# Delegates to the Containers Go module (digital.vasic.containers) to bring
# up the Lava proxy service with full LAN discoverability via mDNS.
# ------------------------------------------------------------------------------

CONTAINERS_BIN="$SCRIPT_DIR/containers/bin/lava-containers"

# Build the containers Go tool if not present
if [ ! -f "$CONTAINERS_BIN" ]; then
    echo "[containers] Building containers management tool..."
    mkdir -p "$SCRIPT_DIR/containers/bin"
    cd "$SCRIPT_DIR/containers"
    go build -o "$CONTAINERS_BIN" ./cmd/lava-containers
    cd "$SCRIPT_DIR"
fi

echo "========================================"
echo "  Lava Container Boot"
echo "========================================"

# Delegate to Go module
exec "$CONTAINERS_BIN" -cmd=start -project-dir="$SCRIPT_DIR"
