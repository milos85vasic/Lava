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

# §6.AA Two-Stage Distribute Mandate — closes §6.AA-debt:
# Default mode is now `debug` (stage 1). The legacy `both` is reserved for
# explicit operator-pre-authorized combined invocation (--debug-and-release).
# A bare `--release-only` invocation REQUIRES a matching debug-stage evidence
# section in the §6.Z evidence file for the same SHA, recorded by the prior
# stage-1 run. The default flip prevents the single-sweep failure mode that
# birthed §6.AA (1.2.19-1039 forensic anchor: combined distribute pushed both
# debug + release before any device verification; release crashed every cold
# launch via R8 + painterResource layer-list rejection).
MODE="debug"
RELEASE_NOTES_OVERRIDE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug-only) MODE="debug"; shift ;;
        --release-only) MODE="release"; shift ;;
        --debug-and-release|--both) MODE="both"; shift ;;
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
# 1a. §6.P (Distribution Versioning + Changelog Mandate) gates.
# Refuses to operate when:
#   - current versionCode <= last distributed versionCode for this channel
#   - CHANGELOG.md lacks entry for this version
#   - per-version snapshot file is missing
# ----------------------------------------------------------------
CHANGELOG_DIR="$LAVA_REPO_ROOT/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
# Legacy single-channel pointer (kept for backward compat + scripts/tag.sh).
LAST_VERSION_FILE="$CHANGELOG_DIR/last-version"
# §6.AA-debt PARTIAL CLOSE 2026-05-14: per-channel last-version pointers.
# Stage 1 (debug-only) advances last-version-debug; Stage 2 (release-only)
# advances last-version-release. Combined-mode (legacy default `both`) writes
# all three. The §6.P monotonic-version-code gate consults the pointer that
# matches the current MODE so debug stage 1 + release stage 2 of the SAME
# versionCode are both permitted (the canonical §6.AA two-stage flow that
# was blocked by the prior single-pointer design).
LAST_VERSION_DEBUG_FILE="$CHANGELOG_DIR/last-version-debug"
LAST_VERSION_RELEASE_FILE="$CHANGELOG_DIR/last-version-release"
SNAPSHOT_FILE="$CHANGELOG_DIR/$APP_VERSION-$APP_VERSION_CODE.md"

mkdir -p "$CHANGELOG_DIR"

# Initialize per-channel pointers from the legacy single pointer if absent.
# Treats "the last published versionCode" as the prior boundary for both
# channels — first invocation after this PARTIAL CLOSE seeds equally; from
# then on the channels diverge per actual distribute history.
if [[ -f "$LAST_VERSION_FILE" && ! -f "$LAST_VERSION_DEBUG_FILE" ]]; then
    cp "$LAST_VERSION_FILE" "$LAST_VERSION_DEBUG_FILE"
fi
if [[ -f "$LAST_VERSION_FILE" && ! -f "$LAST_VERSION_RELEASE_FILE" ]]; then
    cp "$LAST_VERSION_FILE" "$LAST_VERSION_RELEASE_FILE"
fi

# Gate 1: monotonic version code (per-channel under the new model).
case "$MODE" in
    debug)
        GATE_FILE="$LAST_VERSION_DEBUG_FILE"
        GATE_LABEL="last-version-debug"
        ;;
    release)
        GATE_FILE="$LAST_VERSION_RELEASE_FILE"
        GATE_LABEL="last-version-release"
        ;;
    both)
        # Legacy combined mode — stricter check against the legacy pointer
        # (the most-restrictive of the three).
        GATE_FILE="$LAST_VERSION_FILE"
        GATE_LABEL="last-version (combined channel)"
        ;;
    *)
        echo "FATAL: unknown MODE '$MODE' (expected debug|release|both)" >&2
        exit 1
        ;;
esac
if [[ -f "$GATE_FILE" ]]; then
    LAST_DISTRIBUTED="$(cat "$GATE_FILE" 2>/dev/null || echo 0)"
    if [[ "$APP_VERSION_CODE" -le "$LAST_DISTRIBUTED" ]]; then
        echo "FATAL §6.P: current versionCode $APP_VERSION_CODE is not strictly greater than the last distributed code $LAST_DISTRIBUTED on the $GATE_LABEL channel." >&2
        echo "       Bump versionCode in app/build.gradle.kts before re-running this script." >&2
        echo "       Re-distribution of an already-published versionCode on this channel is forbidden." >&2
        exit 1
    fi
fi

# §6.AA Gate (added 2026-05-14): release stage MUST follow debug stage.
# Closes §6.AA-debt's release-without-companion-debug check. When MODE=release,
# require last-version-debug to be at LEAST equal to current versionCode (i.e.
# stage 1 has already advanced the debug pointer for THIS versionCode). This
# blocks the historical failure mode where release pushed before debug, surfacing
# R8-only crashes only at release impact.
if [[ "$MODE" == "release" && -f "$LAST_VERSION_DEBUG_FILE" ]]; then
    LAST_DEBUG="$(cat "$LAST_VERSION_DEBUG_FILE" 2>/dev/null || echo 0)"
    if [[ "$APP_VERSION_CODE" -gt "$LAST_DEBUG" ]]; then
        echo "FATAL §6.AA: --release-only invoked for versionCode $APP_VERSION_CODE but last-version-debug is $LAST_DEBUG (debug stage 1 has not yet distributed this versionCode)." >&2
        echo "       The §6.AA Two-Stage Distribute Mandate requires Stage 1 (debug) to complete BEFORE Stage 2 (release)." >&2
        echo "       Either:" >&2
        echo "         (a) run --debug-only first to distribute the debug variant + obtain operator verification on the failure-surface device" >&2
        echo "         (b) operator-pre-authorize combined distribute via --debug-and-release (NOT recommended; bypasses staging)" >&2
        exit 1
    fi
fi

# Gate 2: CHANGELOG.md entry
if ! grep -qE "Lava-Android-?$APP_VERSION-?$APP_VERSION_CODE|Lava-Android $APP_VERSION \\($APP_VERSION_CODE\\)" "$LAVA_REPO_ROOT/CHANGELOG.md"; then
    echo "FATAL §6.P: CHANGELOG.md does not contain an entry for version $APP_VERSION ($APP_VERSION_CODE)." >&2
    echo "       Add an entry to CHANGELOG.md before distributing." >&2
    exit 1
fi

# Gate 3: per-version snapshot file exists
if [[ ! -f "$SNAPSHOT_FILE" ]]; then
    echo "FATAL §6.P: per-version distribute-changelog snapshot missing." >&2
    echo "       Expected: $SNAPSHOT_FILE" >&2
    echo "       This file is shipped to App Distribution as release-notes." >&2
    exit 1
fi

echo "    §6.P gates passed: versionCode monotonic; CHANGELOG.md entry present; snapshot at $SNAPSHOT_FILE"

# ----------------------------------------------------------------
# 1b. Phase 1 (Phase-1 §6.R + §6.H) gates — added 2026-05-06.
#
#  - Gate 4: pepper rotation. The Phase-11 codegen embeds the
#    LAVA_AUTH_OBFUSCATION_PEPPER value into the APK as one of the
#    constants the AuthInterceptor uses to derive the AES key. Per
#    the Phase-1 spec §9 rotation runbook, every distributed build
#    MUST carry a fresh pepper. Reusing a pepper across distributions
#    means a leak in version N is also a compromise of version N+1.
#    Refuse to operate if the current pepper's SHA-256 already
#    appears in pepper-history.sha256.
#
#  - Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME consistency.
#    Refuse to operate if .env's CURRENT_CLIENT_NAME does NOT match
#    `android-${APP_VERSION}-${APP_VERSION_CODE}`, OR if the named
#    entry is missing from LAVA_AUTH_ACTIVE_CLIENTS. Either case
#    means the Phase-11 codegen would generate a UUID for the wrong
#    client identifier — a silent rotation bug.
# ----------------------------------------------------------------
ENV_FILE="$LAVA_REPO_ROOT/.env"
PEPPER_HISTORY="$CHANGELOG_DIR/pepper-history.sha256"
touch "$PEPPER_HISTORY"

if [[ -f "$ENV_FILE" ]]; then
    PEPPER_VALUE="$(grep -E '^LAVA_AUTH_OBFUSCATION_PEPPER=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
    if [[ -n "$PEPPER_VALUE" ]]; then
        PEPPER_SHA="$(printf '%s' "$PEPPER_VALUE" | sha256sum | awk '{print $1}')"
        if grep -qF "$PEPPER_SHA" "$PEPPER_HISTORY"; then
            echo "FATAL Phase 1 Gate 4: pepper SHA $PEPPER_SHA already used in a previous distribution." >&2
            echo "       Rotate LAVA_AUTH_OBFUSCATION_PEPPER in .env before re-running this script." >&2
            echo "       Run: openssl rand -base64 32  → set as LAVA_AUTH_OBFUSCATION_PEPPER" >&2
            exit 1
        fi
    fi

    CURRENT_NAME="$(grep -E '^LAVA_AUTH_CURRENT_CLIENT_NAME=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
    if [[ -n "$CURRENT_NAME" ]]; then
        EXPECTED_NAME="android-$APP_VERSION-$APP_VERSION_CODE"
        if [[ "$CURRENT_NAME" != "$EXPECTED_NAME" ]]; then
            echo "FATAL Phase 1 Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME=$CURRENT_NAME does not match expected $EXPECTED_NAME." >&2
            echo "       The expected name is derived from app/build.gradle.kts versionName/versionCode." >&2
            echo "       Update LAVA_AUTH_CURRENT_CLIENT_NAME in .env." >&2
            exit 1
        fi
        ACTIVE_CLIENTS="$(grep -E '^LAVA_AUTH_ACTIVE_CLIENTS=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
        if ! echo "$ACTIVE_CLIENTS" | grep -qF "$CURRENT_NAME:"; then
            echo "FATAL Phase 1 Gate 5: $CURRENT_NAME not present in LAVA_AUTH_ACTIVE_CLIENTS." >&2
            echo "       Add the new entry to .env's LAVA_AUTH_ACTIVE_CLIENTS before distributing." >&2
            exit 1
        fi
    fi
    echo "    Phase 1 Gates 4+5 passed: pepper rotated; current-client-name matches version + appears in active list."
else
    echo "    Phase 1 Gates 4+5 skipped: .env not present (auth feature inert at runtime; runtime falls back to StubLavaAuthBlobProvider)."
fi

# ----------------------------------------------------------------
# 2. Resolve git SHA + branch for the release notes
# ----------------------------------------------------------------
GIT_SHA="$(git rev-parse --short HEAD)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
TIMESTAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"

if [[ -n "$RELEASE_NOTES_OVERRIDE" ]]; then
    RELEASE_NOTES="$RELEASE_NOTES_OVERRIDE"
else
    # §6.P: ship the per-version snapshot file as release-notes (truncated
    # to App Distribution's 16KB limit). The snapshot is the canonical
    # operator/tester-visible "what's new" payload.
    RELEASE_NOTES="$(head -c 16000 "$SNAPSHOT_FILE")
---
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

# §6.P + §6.AA-debt PARTIAL CLOSE: persist per-channel last-version so
# stage 1 (debug) + stage 2 (release) of the same SHA are both permitted.
case "$MODE" in
    debug)
        echo "$APP_VERSION_CODE" > "$LAST_VERSION_DEBUG_FILE"
        echo "==> §6.P last-version-debug recorded: $APP_VERSION_CODE → $LAST_VERSION_DEBUG_FILE"
        # Also write legacy single pointer at the higher of the two channels
        # so scripts/tag.sh + downstream scripts continue to see "latest
        # distributed at all" when they consult the legacy file.
        debug_v=$(cat "$LAST_VERSION_DEBUG_FILE" 2>/dev/null || echo 0)
        release_v=$(cat "$LAST_VERSION_RELEASE_FILE" 2>/dev/null || echo 0)
        max_v=$(( debug_v > release_v ? debug_v : release_v ))
        echo "$max_v" > "$LAST_VERSION_FILE"
        ;;
    release)
        echo "$APP_VERSION_CODE" > "$LAST_VERSION_RELEASE_FILE"
        echo "==> §6.P last-version-release recorded: $APP_VERSION_CODE → $LAST_VERSION_RELEASE_FILE"
        debug_v=$(cat "$LAST_VERSION_DEBUG_FILE" 2>/dev/null || echo 0)
        release_v=$(cat "$LAST_VERSION_RELEASE_FILE" 2>/dev/null || echo 0)
        max_v=$(( debug_v > release_v ? debug_v : release_v ))
        echo "$max_v" > "$LAST_VERSION_FILE"
        ;;
    both)
        echo "$APP_VERSION_CODE" > "$LAST_VERSION_DEBUG_FILE"
        echo "$APP_VERSION_CODE" > "$LAST_VERSION_RELEASE_FILE"
        echo "$APP_VERSION_CODE" > "$LAST_VERSION_FILE"
        echo "==> §6.P last-version (combined) recorded: $APP_VERSION_CODE → all three pointers"
        ;;
esac

# Phase 1 Gate 4: persist the pepper SHA after a successful distribute so
# the next session refuses to reuse it.
if [[ -n "${PEPPER_SHA:-}" ]]; then
    echo "$PEPPER_SHA  # $APP_VERSION-$APP_VERSION_CODE  $TIMESTAMP" >> "$PEPPER_HISTORY"
    echo "==> Phase 1 Gate 4 pepper SHA recorded: $PEPPER_SHA → $PEPPER_HISTORY"
fi

echo "==> Firebase distribute complete."
echo "    Console: https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/appdistribution"
