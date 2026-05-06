#!/usr/bin/env bash
# tests/firebase/test_thinker_up_uses_host_network.sh
#
# §6.G Anti-Bluff guard for thinker.local boot.
#
# The 2.0.11 → 2.0.12 thinker.local boot script used a podman bridge
# network for the lava-api-go container. /health succeeded via bridge
# port-mapping, but JmDNS / mDNS broadcasts were confined to the bridge
# subnet — Android clients on the LAN could NOT auto-discover the API
# via `_lava-api._tcp`. Earlier "API booted" reports were green-on-
# /health but the LAN-discovery flow had silently broken.
#
# 2.0.13 fixed this with `--network host` (matching docker-compose.yml's
# `network_mode: host`). This regression test asserts the fix stays in
# place: any future change that drops `--network host` from the api-go
# container or replaces it with a bridge network MUST fail this gate.
#
# Falsifiability rehearsal:
#   1. In deployment/thinker/thinker-up.sh, replace `--network host` on
#      the lava-api-go-thinker run with `--network "$LAVA_THINKER_NETWORK"`
#      (the bridge).
#   2. Run this test.
#   3. Expected failure: "FAIL §6.G: lava-api-go container in
#      deployment/thinker/thinker-up.sh MUST use `--network host`...".
#   4. Revert; test re-passes.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BOOT_SH="$REPO_ROOT/deployment/thinker/thinker-up.sh"

if [[ ! -f "$BOOT_SH" ]]; then
    echo "FAIL: deployment/thinker/thinker-up.sh missing at $BOOT_SH"
    exit 1
fi

# Find the `podman run` block that starts the api-go container. The
# block uses LAVA_THINKER_API_NAME as --name; we extract everything
# from there to the next blank line and assert --network host appears.
api_block=$(awk '
    /podman run -d/ { in_block=1; capture=""; next }
    in_block && /^[[:space:]]*$/ { in_block=0; if (capture ~ /LAVA_THINKER_API_NAME/) print capture; capture="" ; next }
    in_block { capture = capture "\n" $0 }
    END { if (in_block && capture ~ /LAVA_THINKER_API_NAME/) print capture }
' "$BOOT_SH")

if [[ -z "$api_block" ]]; then
    echo "FAIL §6.G: could not locate the api-go `podman run` block in deployment/thinker/thinker-up.sh."
    echo "       The block must contain LAVA_THINKER_API_NAME and a --network flag."
    exit 1
fi

if ! grep -qE -- '--network[[:space:]]+host' <<< "$api_block"; then
    echo "FAIL §6.G: lava-api-go container in deployment/thinker/thinker-up.sh MUST use \`--network host\`."
    echo "       Reason: JmDNS / mDNS broadcasts (lava-api advertises _lava-api._tcp on port 8443)"
    echo "       are confined to the bridge subnet when the container is on a podman bridge"
    echo "       network. Android clients on the LAN cannot auto-discover the API. The"
    echo "       2.0.11 → 2.0.12 distribute had this regression. 2.0.13 fixed it."
    echo "       See .lava-ci-evidence/distribute-changelog/container-registry/2.0.13-2013.md"
    echo "       and the §6.G binding in root CLAUDE.md."
    echo ""
    echo "       Found api-go run block:"
    echo "$api_block" | sed 's/^/         /'
    exit 1
fi

echo "[firebase] OK: deployment/thinker/thinker-up.sh keeps lava-api-go on \`--network host\` (LAN mDNS reach preserved)."
exit 0
