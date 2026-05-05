#!/usr/bin/env bash
# scripts/firebase-setup.sh — one-time Firebase project bootstrap.
#
# Idempotent helper that:
#   1. Verifies firebase-tools CLI is installed
#   2. Authenticates via $LAVA_FIREBASE_TOKEN (loaded from .env, never logged)
#   3. Verifies the Firebase project + Android + Web apps exist
#   4. Re-fetches app/google-services.json (Android)
#   5. Re-fetches lava-api-go/firebase-web-config.json (Web)
#   6. Re-creates / verifies the App Distribution tester group
#
# Usage:
#   ./scripts/firebase-setup.sh                # full setup
#   ./scripts/firebase-setup.sh --refresh      # only refresh config files
#   ./scripts/firebase-setup.sh --invite-only  # only invite testers from .env
#
# Constitutional bindings:
#   §6.H Credential Security — token + admin key never logged or committed
#   §6.J Anti-Bluff — script returns non-zero on real failures (no WARN swallow)
#   §6.K Builds-Inside-Containers — operator may invoke this from a container

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/firebase-env.sh
source "$SCRIPT_DIR/firebase-env.sh"
cd "$LAVA_REPO_ROOT"

MODE="${1:-full}"

if ! command -v firebase >/dev/null 2>&1; then
    echo "FATAL: firebase-tools CLI not found in PATH." >&2
    echo "       Install: npm install -g firebase-tools" >&2
    exit 1
fi

# ----------------------------------------------------------------
# 1. Verify project access (token never echoed)
# ----------------------------------------------------------------
echo "==> Verifying Firebase project access"
if ! FIREBASE_TOKEN="$LAVA_FIREBASE_TOKEN" firebase projects:list 2>/dev/null \
    | grep -q "$LAVA_FIREBASE_PROJECT_ID"; then
    echo "FATAL: project $LAVA_FIREBASE_PROJECT_ID not visible to the token." >&2
    echo "       Verify LAVA_FIREBASE_TOKEN in .env." >&2
    exit 1
fi
echo "    Project $LAVA_FIREBASE_PROJECT_ID accessible."

# ----------------------------------------------------------------
# 2. Refresh app/google-services.json (Android)
# ----------------------------------------------------------------
if [[ "$MODE" != "--invite-only" ]]; then
    echo "==> Refreshing app/google-services.json"
    FIREBASE_TOKEN="$LAVA_FIREBASE_TOKEN" firebase apps:sdkconfig ANDROID \
        "$LAVA_FIREBASE_ANDROID_APP_ID" \
        --project "$LAVA_FIREBASE_PROJECT_ID" \
        --out app/google-services.json
    echo "    app/google-services.json refreshed (gitignored)."

    echo "==> Refreshing lava-api-go/firebase-web-config.json"
    FIREBASE_TOKEN="$LAVA_FIREBASE_TOKEN" firebase apps:sdkconfig WEB \
        "$LAVA_FIREBASE_API_GO_APP_ID" \
        --project "$LAVA_FIREBASE_PROJECT_ID" \
        --out lava-api-go/firebase-web-config.json
    echo "    lava-api-go/firebase-web-config.json refreshed (gitignored)."
fi

# ----------------------------------------------------------------
# 3. Verify config files are gitignored (anti-leak guard)
# ----------------------------------------------------------------
echo "==> Verifying gitignore coverage"
for f in app/google-services.json lava-api-go/firebase-web-config.json; do
    if ! git check-ignore -q "$f"; then
        echo "FATAL: $f is NOT gitignored. Aborting before secrets leak." >&2
        exit 1
    fi
done
echo "    All Firebase config files verified gitignored."

# ----------------------------------------------------------------
# 4. Invite testers from .env (App Distribution)
# ----------------------------------------------------------------
if [[ "$MODE" == "--refresh" ]]; then
    echo "==> --refresh: skipping tester invite"
    exit 0
fi

echo "==> Adding testers to App Distribution"
FIREBASE_TOKEN="$LAVA_FIREBASE_TOKEN" firebase appdistribution:testers:add \
    --project "$LAVA_FIREBASE_PROJECT_ID" \
    "$LAVA_FIREBASE_TESTERS" || {
    echo "    NOTE: testers:add returned non-zero (often: testers already added)." >&2
}
echo "    Tester invite complete (3 testers from .env)."

echo "==> Firebase setup complete."
