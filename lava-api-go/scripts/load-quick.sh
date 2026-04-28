#!/usr/bin/env bash
#
# load-quick.sh — invoke k6 against a running lava-api-go.
#
# Used by `lava-api-go/scripts/ci.sh` (Phase 13 wires the call) and the
# Phase 14 acceptance run. Standalone invocation is also fine for an
# operator who wants to spot-check a local stack.
#
# Behaviour:
#   - If k6 is NOT on PATH: print a notice and exit 0 (skip cleanly), so
#     the wider CI gate doesn't fail on a developer machine that hasn't
#     installed k6 yet. Set LAVA_LOAD_REQUIRE=1 to flip that into a
#     hard error (the Phase 14 acceptance host is expected to set this).
#   - If k6 IS on PATH: invoke `k6 run` against tests/load/k6-quick.js
#     with LAVA_API_BASE_URL forwarded as a -e env var. k6's exit code
#     becomes our exit code; threshold violations exit non-zero, which
#     is exactly the signal scripts/ci.sh wants.
#
# Constitutional notes:
#   - This script does NOT install k6, does NOT background, does NOT
#     touch host power management. Per root CLAUDE.md "Host Machine
#     Stability Directive". k6 itself is a foreground userspace process.
#   - This script does NOT run the soak test (tests/load/k6-soak.js).
#     Soak is operator-only by design (see header comment in that file).

set -euo pipefail

BASE_URL="${LAVA_API_BASE_URL:-https://localhost:8443}"

if ! command -v k6 >/dev/null 2>&1; then
    echo "k6 not installed; skipping load test (set LAVA_LOAD_REQUIRE=1 to require)"
    if [ "${LAVA_LOAD_REQUIRE:-0}" = "1" ]; then
        echo "LAVA_LOAD_REQUIRE=1 set but k6 not on PATH — failing." >&2
        exit 1
    fi
    exit 0
fi

cd "$(dirname "$0")/.."
exec k6 run \
    -e LAVA_API_BASE_URL="$BASE_URL" \
    tests/load/k6-quick.js
