#!/usr/bin/env bash
# scripts/firebase-env.sh — load Firebase config from .env (constitutional 6.H).
#
# Usage:
#   # shellcheck source=scripts/firebase-env.sh
#   source "$(dirname "$0")/firebase-env.sh"
#
# Exits non-zero if .env is missing or required keys are absent. Does NOT
# echo any value (constitutional 6.H, 6.M): the values are sensitive and
# this helper is sourced into the caller's environment, never logged.

set -euo pipefail

LAVA_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAVA_ENV_FILE="${LAVA_REPO_ROOT}/.env"

if [[ ! -f "$LAVA_ENV_FILE" ]]; then
    echo "FATAL: .env not found at $LAVA_ENV_FILE" >&2
    echo "       See .env.example for required keys." >&2
    exit 1
fi

# Load LAVA_FIREBASE_* + RUTRACKER_* keys without exporting other tokens
# the file may contain. Each key is loaded under `set -u` so a missing
# value fails loudly later rather than silently emptying.
while IFS='=' read -r key value; do
    [[ -z "$key" || "$key" =~ ^# ]] && continue
    [[ "$key" =~ ^(LAVA_FIREBASE_|RUTRACKER_|KEYSTORE_|KINOZAL_|NNMCLUB_) ]] || continue
    # shellcheck disable=SC2086,SC2163
    export "$key=$value"
done < "$LAVA_ENV_FILE"

required=(
    LAVA_FIREBASE_TOKEN
    LAVA_FIREBASE_PROJECT_ID
    LAVA_FIREBASE_ANDROID_APP_ID
    LAVA_FIREBASE_ANDROID_DEV_APP_ID
    LAVA_FIREBASE_API_GO_APP_ID
    LAVA_FIREBASE_TESTERS_OWNER
    LAVA_FIREBASE_TESTERS_DEVELOPER
    LAVA_FIREBASE_TESTERS_TESTER
)
missing=()
for k in "${required[@]}"; do
    if [[ -z "${!k:-}" ]]; then
        missing+=("$k")
    fi
done
if (( ${#missing[@]} > 0 )); then
    echo "FATAL: missing required Firebase keys in .env:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    echo "See .env.example for the full set." >&2
    exit 1
fi

# Tester emails — the canonical list is .env-driven (operator directive
# 2026-05-05). Concatenated comma-separated for `firebase appdistribution:distribute`.
LAVA_FIREBASE_TESTERS="${LAVA_FIREBASE_TESTERS_OWNER},${LAVA_FIREBASE_TESTERS_DEVELOPER},${LAVA_FIREBASE_TESTERS_TESTER}"
export LAVA_FIREBASE_TESTERS
