#!/usr/bin/env bash
# scripts/firebase-distribute.sh — upload built artifacts to Firebase App
# Distribution and invite testers loaded from .env.
#
# Replaces the local releases/ delivery flow as the canonical operator
# distribution channel (operator directive 2026-05-05).
#
# Usage:
#   ./scripts/firebase-distribute.sh                    # debug + release APKs
#   ./scripts/firebase-distribute.sh --debug-only       # only debug APK
#   ./scripts/firebase-distribute.sh --release-only     # only release APK
#   ./scripts/firebase-distribute.sh --release-notes "<text>"   # custom notes
#
# Inputs:
#   .env  (gitignored) — LAVA_FIREBASE_TOKEN, project + app IDs, tester emails
#   releases/<version>/android-debug/*.apk
#   releases/<version>/android-release/*.apk
#
# Outputs:
#   App Distribution release at the Firebase Console under
#     project $LAVA_FIREBASE_PROJECT_ID, app $LAVA_FIREBASE_ANDROID_APP_ID.
#   3 testers receive an email invite (per .env LAVA_FIREBASE_TESTERS_*).
#
# Constitutional bindings:
#   §6.H Credential Security — tokens read from .env, never echoed
#   §6.J Anti-Bluff — propagates real failures via set -euo pipefail; no WARN swallow
#   §6.G End-to-end provider operational verification — distribute step is the
#         hand-off the operator's manual real-device pass exercises against.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/firebase-env.sh
source "$SCRIPT_DIR/firebase-env.sh"
cd "$LAVA_REPO_ROOT"

MODE="${1:-both}"
RELEASE_NOTES_OVERRIDE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug-only) MODE="debug"; shift ;;
        --release-only) MODE="release"; shift ;;
        --release-notes) RELEASE_NOTES_OVERRIDE="$2"; shift 2 ;;
        *) shift ;;
    esac
done

# ----------------------------------------------------------------
# 1. Resolve current Android version + build number from app gradle
# ----------------------------------------------------------------
APP_VERSION="$(grep -E '^\s+versionName\s*=' app/build.gradle.kts \
    | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
APP_VERSION_CODE="$(grep -E '^\s+versionCode\s*=' app/build.gradle.kts \
    | head -1 | sed 's/.*= \([0-9]*\).*/\1/')"

if [[ -z "$APP_VERSION" || -z "$APP_VERSION_CODE" ]]; then
    echo "FATAL: could not parse versionName/versionCode from app/build.gradle.kts" >&2
    exit 1
fi
echo "==> Distributing Lava Android $APP_VERSION ($APP_VERSION_CODE)"

# ----------------------------------------------------------------
# 2. Resolve git SHA + branch for the release notes
# ----------------------------------------------------------------
GIT_SHA="$(git rev-parse --short HEAD)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
TIMESTAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"

if [[ -n "$RELEASE_NOTES_OVERRIDE" ]]; then
    RELEASE_NOTES="$RELEASE_NOTES_OVERRIDE"
else
    RELEASE_NOTES="Lava Android $APP_VERSION ($APP_VERSION_CODE)
branch=$GIT_BRANCH sha=$GIT_SHA built=$TIMESTAMP"
fi

# ----------------------------------------------------------------
# 3. Locate built APKs
# ----------------------------------------------------------------
RELEASE_DIR="releases/$APP_VERSION"
DEBUG_APK="$(find "$RELEASE_DIR/android-debug" -maxdepth 1 -name '*.apk' 2>/dev/null | head -1 || true)"
RELEASE_APK="$(find "$RELEASE_DIR/android-release" -maxdepth 1 -name '*.apk' 2>/dev/null | head -1 || true)"

if [[ "$MODE" == "debug" || "$MODE" == "both" ]]; then
    if [[ -z "$DEBUG_APK" || ! -f "$DEBUG_APK" ]]; then
        echo "FATAL: debug APK not found under $RELEASE_DIR/android-debug/" >&2
        echo "       Run ./build_and_release.sh first." >&2
        exit 1
    fi
fi
if [[ "$MODE" == "release" || "$MODE" == "both" ]]; then
    if [[ -z "$RELEASE_APK" || ! -f "$RELEASE_APK" ]]; then
        echo "FATAL: release APK not found under $RELEASE_DIR/android-release/" >&2
        echo "       Run ./build_and_release.sh first." >&2
        exit 1
    fi
fi

# ----------------------------------------------------------------
# 4. Upload to Firebase App Distribution
# ----------------------------------------------------------------
distribute_apk() {
    local apk="$1"
    local label="$2"
    local app_id="$3"
    echo "==> Uploading $label APK: $(basename "$apk")"
    FIREBASE_TOKEN="$LAVA_FIREBASE_TOKEN" firebase appdistribution:distribute \
        "$apk" \
        --app "$app_id" \
        --project "$LAVA_FIREBASE_PROJECT_ID" \
        --testers "$LAVA_FIREBASE_TESTERS" \
        --release-notes "$RELEASE_NOTES ($label)"
    echo "    $label APK distributed."
}

if [[ "$MODE" == "debug" || "$MODE" == "both" ]]; then
    # Debug APK uses applicationIdSuffix .dev → registered as a separate
    # Firebase Android app (LAVA_FIREBASE_ANDROID_DEV_APP_ID).
    distribute_apk "$DEBUG_APK" "debug" "$LAVA_FIREBASE_ANDROID_DEV_APP_ID"
fi
if [[ "$MODE" == "release" || "$MODE" == "both" ]]; then
    distribute_apk "$RELEASE_APK" "release" "$LAVA_FIREBASE_ANDROID_APP_ID"
fi

# ----------------------------------------------------------------
# 5. Local distribution log (gitignored per .gitignore firebase-distribute-*.log)
# ----------------------------------------------------------------
LOG="firebase-distribute-${APP_VERSION}-${APP_VERSION_CODE}-${TIMESTAMP}.log"
{
    echo "timestamp=$TIMESTAMP"
    echo "version=$APP_VERSION ($APP_VERSION_CODE)"
    echo "branch=$GIT_BRANCH sha=$GIT_SHA"
    echo "mode=$MODE"
    echo "tester_count=3"
    echo "project=$LAVA_FIREBASE_PROJECT_ID"
} > "$LOG"
echo "==> Distribute log: $LOG (gitignored)"

echo "==> Firebase distribute complete."
echo "    Console: https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/appdistribution"
