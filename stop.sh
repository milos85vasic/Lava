#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Lava Container Stop Script
# ------------------------------------------------------------------------------
# Delegates to the Containers Go module to cleanly shut down the Lava proxy.
#
# NOTE: stops every running profile. SP-2 Phase 11/12: lava-containers'
# `-cmd=stop` invokes `compose down` against the project, which tears down
# all services regardless of which --profile was originally used to bring
# them up. There is no per-profile stop variant by design — stopping is
# always total.
# ------------------------------------------------------------------------------

CONTAINERS_BIN="$SCRIPT_DIR/tools/lava-containers/bin/lava-containers"

if [ ! -f "$CONTAINERS_BIN" ]; then
    echo "Error: Lava containers CLI not found. Run ./start.sh first or build it manually:"
    echo "  cd tools/lava-containers && go build -o bin/lava-containers ./cmd/lava-containers"
    exit 1
fi

exec "$CONTAINERS_BIN" -cmd=stop -project-dir="$SCRIPT_DIR"
