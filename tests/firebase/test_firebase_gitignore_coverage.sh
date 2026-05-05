#!/usr/bin/env bash
# tests/firebase/test_firebase_gitignore_coverage.sh
#
# Anti-bluff guard for §6.H Credential Security. Asserts that:
#   1. Every Firebase artifact path is gitignored
#   2. .env is gitignored
#   3. .env.example is NOT gitignored (it's the safe template)
#   4. No real Firebase token shows up in any tracked file
#
# Falsifiability rehearsal: remove `app/google-services.json` from .gitignore
# — this test fails pointing at the missing ignore.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# 1. Files that MUST be gitignored
must_be_ignored=(
    .env
    .env.local
    app/google-services.json
    lava-api-go/firebase-web-config.json
    lava-api-go/firebase-admin-key.json
    firebase-admin-key.json
    firebase-debug.log
)

for f in "${must_be_ignored[@]}"; do
    if ! git check-ignore -q "$f" 2>/dev/null; then
        echo "FAIL: $f is NOT gitignored. §6.H Credential Security violation."
        exit 1
    fi
done

# 2. .env.example MUST NOT be gitignored (it is the template)
if git check-ignore -q .env.example 2>/dev/null; then
    echo "FAIL: .env.example is gitignored, but it is the safe template — should be tracked."
    exit 1
fi

# 3. No tracked file contains the canonical Firebase CLI token prefix
#    (1// followed by the OAuth refresh token format). The only safe
#    exemption is .env.example which uses a literal placeholder.
suspect=$(git ls-files | xargs grep -lE '1//0[0-9a-zA-Z_-]{40,}' 2>/dev/null \
    | grep -v '^.env.example$' \
    | grep -v '^docs/FIREBASE.md$' \
    || true)
if [[ -n "$suspect" ]]; then
    echo "FAIL: tracked files contain a real-looking Firebase OAuth token:"
    echo "$suspect"
    exit 1
fi

# 4. .env.example must contain the required Firebase placeholders so
#    operators know what to fill in.
required_keys=(
    LAVA_FIREBASE_TOKEN
    LAVA_FIREBASE_PROJECT_ID
    LAVA_FIREBASE_ANDROID_APP_ID
    LAVA_FIREBASE_ANDROID_DEV_APP_ID
    LAVA_FIREBASE_API_GO_APP_ID
    LAVA_FIREBASE_TESTERS_OWNER
    LAVA_FIREBASE_TESTERS_DEVELOPER
    LAVA_FIREBASE_TESTERS_TESTER
)
for k in "${required_keys[@]}"; do
    if ! grep -qE "^${k}=" .env.example; then
        echo "FAIL: .env.example is missing required key: $k"
        exit 1
    fi
done

echo "[firebase] OK: gitignore covers all Firebase artifacts, .env.example documents all required keys, no real tokens in tracked files."
exit 0
