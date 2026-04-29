#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Lava Container Stop Script
# ------------------------------------------------------------------------------
# Tears down EVERY running container regardless of which profile or
# combination of profiles was used to bring them up.
#
# Why this is broader than `lava-containers -cmd=stop`: the lava-containers
# CLI scopes its compose-down to the profile flags it was given. A previous
# `./start.sh --both --with-observability --dev-docs` leaves containers
# from 4 profiles running, but `./stop.sh` (no args) tearing down only
# api-go would leave the legacy/observability/dev-docs containers behind.
# This script enumerates every profile the docker-compose.yml defines and
# passes them all to `compose down`, so a single ./stop.sh always returns
# the system to a clean (no-containers) state.
# ------------------------------------------------------------------------------

# Pick the runtime that's actually installed. Prefer podman per project
# convention, fall back to docker.
if command -v podman >/dev/null 2>&1; then
    RUNTIME=(podman compose)
elif command -v docker >/dev/null 2>&1; then
    RUNTIME=(docker compose)
else
    echo "Error: neither podman nor docker is installed; nothing to do." >&2
    exit 1
fi

PROFILES=(--profile api-go --profile legacy --profile both --profile observability --profile dev-docs)

echo "[stop.sh] tearing down all profiles via ${RUNTIME[*]} ${PROFILES[*]}"

# `compose down` is idempotent: it succeeds when there's nothing to tear
# down. Errors from individual containers being mid-stop are noise; the
# final `podman ps` check below is authoritative.
"${RUNTIME[@]}" -f "$SCRIPT_DIR/docker-compose.yml" "${PROFILES[@]}" down --remove-orphans 2>&1 | tail -10 || true

# Authoritative state check: no lava-* containers may remain.
if "${RUNTIME[0]}" ps -a --format '{{.Names}}' | grep -qE '^lava-'; then
    echo "[stop.sh:warn] some lava-* containers are still present:" >&2
    "${RUNTIME[0]}" ps -a --format '{{.Names}}\t{{.Status}}' | grep -E '^lava-' >&2
    echo "[stop.sh:warn] forcing removal..." >&2
    "${RUNTIME[0]}" ps -a --format '{{.Names}}' | grep -E '^lava-' | xargs -r "${RUNTIME[0]}" rm -f
fi

echo "[stop.sh] done"
