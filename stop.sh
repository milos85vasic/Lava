#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Lava Container Stop Script
# ------------------------------------------------------------------------------
# Delegates to the Containers Go module to cleanly shut down the Lava proxy.
# ------------------------------------------------------------------------------

CONTAINERS_BIN="$SCRIPT_DIR/containers/bin/lava-containers"

if [ ! -f "$CONTAINERS_BIN" ]; then
    echo "Error: Containers tool not found. Run ./start.sh first or build it manually:"
    echo "  cd containers && go build -o bin/lava-containers ./cmd/lava-containers"
    exit 1
fi

exec "$CONTAINERS_BIN" -cmd=stop -project-dir="$SCRIPT_DIR"
