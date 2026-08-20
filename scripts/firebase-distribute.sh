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
# §6.P "Stream-D" app selector: --app client|api-app (default: client).
# client  → the main Android user app  (:app, digital.vasic.lava.client)
# api-app → the on-device API server app (:api-app, digital.vasic.lava.api)
SELECTED_APP="client"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug-only) MODE="debug"; shift ;;
        --release-only) MODE="release"; shift ;;
        --debug-and-release|--both) MODE="both"; shift ;;
        --release-notes) RELEASE_NOTES_OVERRIDE="$2"; shift 2 ;;
        --app) SELECTED_APP="$2"; shift 2 ;;
        *) shift ;;
    esac
done

# ----------------------------------------------------------------
# 0. Resolve per-app configuration (set just after arg parsing so all
#    subsequent logic works with the resolved vars regardless of --app).
#
# For each artifact we set:
#   GRADLE_VERSION_FILE — source of versionName / versionCode
#   RELEASE_BASE        — directory root where APKs live after build
#   CHANGELOG_DIR       — .lava-ci-evidence channel directory
#   DEBUG_APP_ID_VAR    — name of the env-var holding the debug Firebase app-id
#   RELEASE_APP_ID_VAR  — name of the env-var holding the release Firebase app-id
#   CHANGELOG_PATTERN   — regex used by Gate 2 to match the CHANGELOG.md entry
#   SKIP_PHASE1_AUTH    — "yes" when Phase-1 Gates 4+5 are not applicable
#
# §6.R: app-ids come from .env-exported vars (via firebase-env.sh's
# LAVA_FIREBASE_* wildcard export), never as literals here.
# §6.H: we reference the VAR NAME here and dereference only when needed.
# ----------------------------------------------------------------
case "$SELECTED_APP" in
    client)
        GRADLE_VERSION_FILE="app/build.gradle.kts"
        RELEASE_BASE_TMPL="releases/APP_VERSION"   # APP_VERSION injected after parse
        CHANGELOG_CHANNEL="firebase-app-distribution"
        DEBUG_APP_ID_VAR="LAVA_FIREBASE_ANDROID_DEV_APP_ID"
        RELEASE_APP_ID_VAR="LAVA_FIREBASE_ANDROID_APP_ID"
        # Gate 2 pattern: accepts both dash-form and paren-form used in CHANGELOG.md
        CHANGELOG_PATTERN_TMPL='Lava-Android-?APP_VERSION-?APP_VERSION_CODE|Lava-Android APP_VERSION \(APP_VERSION_CODE\)'
        SKIP_PHASE1_AUTH="no"
        APP_DISPLAY="Lava Android"
        ;;
    api-app)
        GRADLE_VERSION_FILE="api-app/build.gradle.kts"
        RELEASE_BASE_TMPL="releases/api-app/APP_VERSION"
        CHANGELOG_CHANNEL="firebase-app-distribution-api-app"
        DEBUG_APP_ID_VAR="LAVA_FIREBASE_API_APP_DEV_APP_ID"
        RELEASE_APP_ID_VAR="LAVA_FIREBASE_API_APP_ID"
        CHANGELOG_PATTERN_TMPL='Lava-API-App-?APP_VERSION-?APP_VERSION_CODE|Lava-API-App APP_VERSION \(APP_VERSION_CODE\)'
        SKIP_PHASE1_AUTH="yes"
        APP_DISPLAY="Lava API App"
        ;;
    *)
        echo "FATAL: --app must be 'client' or 'api-app' (got '$SELECTED_APP')" >&2
        exit 1
        ;;
esac

# ----------------------------------------------------------------
# 1. Resolve current Android version + build number from the
#    per-app gradle file (client: app/build.gradle.kts;
#    api-app: api-app/build.gradle.kts).
# ----------------------------------------------------------------
APP_VERSION="$(grep -E '^\s+versionName\s*=' "$GRADLE_VERSION_FILE" \
    | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"
APP_VERSION_CODE="$(grep -E '^\s+versionCode\s*=' "$GRADLE_VERSION_FILE" \
    | head -1 | sed 's/.*= \([0-9]*\).*/\1/')"

if [[ -z "$APP_VERSION" || -z "$APP_VERSION_CODE" ]]; then
    echo "FATAL: could not parse versionName/versionCode from $GRADLE_VERSION_FILE" >&2
    exit 1
fi

# Resolve the release-base and changelog-pattern now that APP_VERSION is known.
RELEASE_BASE="${RELEASE_BASE_TMPL/APP_VERSION/$APP_VERSION}"
# Substitute APP_VERSION_CODE FIRST (longer token, avoids the APP_VERSION
# prefix from clobbering the "APP_VERSION_CODE" substring during the
# APP_VERSION pass).
CHANGELOG_PATTERN="${CHANGELOG_PATTERN_TMPL//APP_VERSION_CODE/$APP_VERSION_CODE}"
CHANGELOG_PATTERN="${CHANGELOG_PATTERN//APP_VERSION/$APP_VERSION}"

echo "==> Distributing $APP_DISPLAY $APP_VERSION ($APP_VERSION_CODE)"

# ----------------------------------------------------------------
# 1a. §6.P (Distribution Versioning + Changelog Mandate) gates.
# Refuses to operate when:
#   - current versionCode <= last distributed versionCode for this channel
#   - CHANGELOG.md lacks entry for this version
#   - per-version snapshot file is missing
# ----------------------------------------------------------------
CHANGELOG_DIR="$LAVA_REPO_ROOT/.lava-ci-evidence/distribute-changelog/$CHANGELOG_CHANNEL"
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

# Gate 2: CHANGELOG.md entry (pattern is per-app, resolved in §0 above)
if ! grep -qE "$CHANGELOG_PATTERN" "$LAVA_REPO_ROOT/CHANGELOG.md"; then
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
#
#  NOTE: Gates 4+5 apply ONLY to the client app (SELECTED_APP=client).
#  The api-app artifact is the SERVER side of the auth scheme — it does
#  not embed the client AuthInterceptor obfuscation pepper and the
#  android-<ver> client-name concept is inapplicable to it.
# ----------------------------------------------------------------
ENV_FILE="$LAVA_REPO_ROOT/.env"
PEPPER_HISTORY="$CHANGELOG_DIR/pepper-history.sha256"
touch "$PEPPER_HISTORY"

if [[ "$SKIP_PHASE1_AUTH" == "yes" ]]; then
    echo "    Phase 1 Gates 4+5 skipped for api-app (server artifact, no client pepper)."
elif [[ -f "$ENV_FILE" ]]; then
    PEPPER_VALUE="$(grep -E '^LAVA_AUTH_OBFUSCATION_PEPPER=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
    if [[ -n "$PEPPER_VALUE" ]]; then
        PEPPER_SHA="$(printf '%s' "$PEPPER_VALUE" | sha256sum | awk '{print $1}')"
        # §6.AA pepper-reuse semantics: debug + release of the SAME versionCode
        # legitimately share ONE pepper (one release identity → identical embedded
        # auth key in both variants). Gate 4's purpose is to prevent reuse ACROSS
        # releases ("a leak in version N must not also compromise version N+1"), so
        # it rejects the SHA only when it appears in history for a DIFFERENT
        # release identity. A prior occurrence for THIS exact "$APP_VERSION-$APP_VERSION_CODE"
        # (the debug stage of this same build) is allowed — the two-stage §6.AA
        # release of one versionCode does not require a second rotation.
        PEPPER_PRIOR_OTHER="$(grep -F "$PEPPER_SHA" "$PEPPER_HISTORY" \
            | grep -vF "# $APP_VERSION-$APP_VERSION_CODE " || true)"
        if [[ -n "$PEPPER_PRIOR_OTHER" ]]; then
            echo "FATAL Phase 1 Gate 4: pepper SHA $PEPPER_SHA already used for a DIFFERENT release:" >&2
            echo "$PEPPER_PRIOR_OTHER" | sed 's/^/         /' >&2
            echo "       Rotate LAVA_AUTH_OBFUSCATION_PEPPER in .env before re-running this script." >&2
            echo "       Run: openssl rand -base64 32  → set as LAVA_AUTH_OBFUSCATION_PEPPER" >&2
            echo "       (Reuse WITHIN the same versionCode — debug→release of $APP_VERSION-$APP_VERSION_CODE — is allowed.)" >&2
            exit 1
        fi
    fi

    CURRENT_NAME="$(grep -E '^LAVA_AUTH_CURRENT_CLIENT_NAME=' "$ENV_FILE" | head -1 | cut -d= -f2-)"
    if [[ -n "$CURRENT_NAME" ]]; then
        EXPECTED_NAME="android-$APP_VERSION-$APP_VERSION_CODE"
        if [[ "$CURRENT_NAME" != "$EXPECTED_NAME" ]]; then
            echo "FATAL Phase 1 Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME=$CURRENT_NAME does not match expected $EXPECTED_NAME." >&2
            echo "       The expected name is derived from $GRADLE_VERSION_FILE versionName/versionCode." >&2
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
# 1c. §6.AK Phase-1 Gate 7 — cycle-coverage (claims × executed device Challenges).
# Closes the firebase-distribute portion of §6.AK-debt. Refuses to distribute
# unless EVERY CHANGELOG-claimed user-visible fix for this version has an
# EXECUTED+PASSED covering device Challenge in the §6.Z evidence file for the
# SAME commit SHA (spec §5.3). This is the gate that would have CAUGHT the 1076
# incident (commit 627a0d58): a C00-only device gate while the CHANGELOG claimed
# search / provider-selection / onboarding fixes. Verified by
# tests/cycle-coverage/test_wiring.sh (5/5, mutation-rehearsal proven).
# The gate's --evidence-dir is pinned to $CHANGELOG_DIR (the app-resolved §6.Z
# evidence dir) and it also subsumes the §6.Z-debt runtime checks (evidence
# presence + commit-SHA match + ≤24h freshness → exit 2 / exit 1).
# ----------------------------------------------------------------
case "$MODE" in
    release) AK_CHANNEL="release" ;;
    *)       AK_CHANNEL="debug"   ;;
esac
echo "    Phase 1 Gate 7 (§6.AK): cycle-coverage — CHANGELOG claims × executed device Challenges"
ak_rc=0
"$SCRIPT_DIR/check-cycle-coverage.sh" \
    --version="$APP_VERSION-$APP_VERSION_CODE" \
    --channel="$AK_CHANNEL" \
    --evidence-dir="$CHANGELOG_DIR" \
    --strict || ak_rc=$?
case "$ak_rc" in
    0) echo "    §6.AK gate PASS — all CHANGELOG claims covered by executed device Challenges" ;;
    1) echo "FATAL §6.AK: CHANGELOG claim(s) lack a covering executed+PASSED device Challenge for $APP_VERSION-$APP_VERSION_CODE." >&2
       echo "       Run the missing device Challenge(s) on the gate, OR strike the unverified claim(s) from CHANGELOG.md (§6.AK clause 6)." >&2
       exit 1 ;;
    2) echo "FATAL §6.AK: §6.Z evidence or cycle-coverage-map missing / stale / wrong-SHA for $APP_VERSION-$APP_VERSION_CODE." >&2
       echo "       Re-run the device gate on THIS commit and write the cycle-coverage-map before distributing." >&2
       exit 1 ;;
    *) echo "FATAL §6.AK: internal error in cycle-coverage scanner (rc=$ak_rc)." >&2
       exit 1 ;;
esac

# ----------------------------------------------------------------
# 1d. LVA-019 — per-release coverage-ledger snapshot (§11.4.25).
# Freezes docs/coverage-ledger.yaml at this exact distribute moment, mirroring
# the §6.AK cycle-coverage-map per-release snapshot pattern above. Advisory —
# does not block distribute on failure (the coverage ledger's own STRICT gate
# already runs elsewhere in CI; this is a historical-record snapshot, not a
# release gate).
# ----------------------------------------------------------------
echo "    LVA-019: snapshotting coverage ledger for $APP_VERSION-$APP_VERSION_CODE"
bash "$SCRIPT_DIR/snapshot-coverage-ledger.sh" "$APP_VERSION-$APP_VERSION_CODE" || \
    echo "    WARNING: coverage-ledger snapshot failed (non-fatal, distribute continues)"

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
#    RELEASE_BASE is per-app (client: releases/$APP_VERSION;
#    api-app: releases/api-app/$APP_VERSION), resolved in §0.
# ----------------------------------------------------------------
RELEASE_DIR="$RELEASE_BASE"
# §6.J/§6.Z wrong-binary-bluff guard (2026-06-23): select the APK by the EXACT
# version code being distributed; NEVER silent `find | head -1`. A dir holding
# APKs for >1 version code (a re-spin's 1068 + 1069) made the old `head -1`
# upload the lexically-FIRST (stale) APK while every §6.P/§6.Z gate passed —
# the wrong-binary bluff that shipped 1.3.11-1068 twice (Firebase releases
# 3r986p5gnfujo + 5h2a747aj9jko). Prefer the version-coded name; if absent,
# require exactly ONE apk; refuse (FATAL) on ambiguity rather than guess.
_pick_apk_by_version() {  # $1=dir  $2=buildtype(debug|release)
    local dir="$1" bt="$2" coded all n
    coded="$(find "$dir" -maxdepth 1 -name "*-${APP_VERSION_CODE}-${bt}.apk" 2>/dev/null | head -1 || true)"
    if [[ -n "$coded" ]]; then printf '%s' "$coded"; return 0; fi
    all="$(find "$dir" -maxdepth 1 -name '*.apk' 2>/dev/null || true)"
    n="$(printf '%s\n' "$all" | grep -c . || true)"
    if [[ "$n" -gt 1 ]]; then
        echo "FATAL §6.Z: $n APKs in $dir but none named *-${APP_VERSION_CODE}-${bt}.apk —" >&2
        echo "       refusing to guess which to distribute (the wrong-binary bluff guard)." >&2
        echo "       Remove stale APKs or name the artifact by version code." >&2
        exit 1
    fi
    printf '%s' "$(printf '%s\n' "$all" | head -1)"   # 0 or 1 apk: the not-found check below handles 0
}
DEBUG_APK="$(_pick_apk_by_version "$RELEASE_DIR/android-debug" debug)"
RELEASE_APK="$(_pick_apk_by_version "$RELEASE_DIR/android-release" release)"

# §6.Z content-versionCode guard (added 2026-06-23 after Lava-API-App 0.2.11-17's
# RELEASE channel shipped a STALE versionCode-16 binary: the rebuild FAILED mid-
# package (transient crashlytics-DNS) so the release output stayed at the prior
# cycle's 16 APK; the file was named *-17-release.apk but its binary manifest said
# 16, and the filename-only picker above could not see the mismatch). Assert the
# picked APK's ACTUAL versionCode (from its binary manifest via aapt2) == the
# expected APP_VERSION_CODE before upload. The picker matches the name; THIS gate
# matches the bytes.
_aapt2() {
    local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
    ls "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1
}
_assert_apk_versioncode() {  # $1=apk
    local apk="$1" aapt actual
    [[ -z "$apk" || ! -f "$apk" ]] && return 0
    aapt="$(_aapt2)"
    if [[ -z "$aapt" ]]; then
        echo "WARN §6.Z: aapt2 not found (no Android SDK build-tools at ANDROID_SDK_ROOT/ANDROID_HOME) —" >&2
        echo "          skipping the APK content-versionCode check for $(basename "$apk"). Install build-tools to enable this guard." >&2
        return 0
    fi
    actual="$("$aapt" dump badging "$apk" 2>/dev/null | grep -oE "versionCode='[0-9]+'" | head -1 | grep -oE '[0-9]+')"
    if [[ -n "$actual" && "$actual" != "$APP_VERSION_CODE" ]]; then
        echo "FATAL §6.Z: $(basename "$apk") has ACTUAL versionCode $actual but this distribute is for $APP_VERSION_CODE." >&2
        echo "       The filename says $APP_VERSION_CODE but the binary manifest says $actual — a stale/mis-built APK (the wrong-binary class)." >&2
        echo "       Rebuild the artifact cleanly (./gradlew :<module>:clean assembleDebug assembleRelease) and re-stage." >&2
        exit 1
    fi
    echo "    §6.Z content-check: $(basename "$apk") actual versionCode $actual == $APP_VERSION_CODE"
}
if [[ "$MODE" == "debug"   || "$MODE" == "both" ]]; then _assert_apk_versioncode "$DEBUG_APK"; fi
if [[ "$MODE" == "release" || "$MODE" == "both" ]]; then _assert_apk_versioncode "$RELEASE_APK"; fi

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
#
# Resolve the Firebase app-ids from environment at this point.
# §6.R: the app-id values come from .env (loaded by firebase-env.sh's
# LAVA_FIREBASE_* wildcard export); we dereference by var-name here,
# never embed literals.
#
# For api-app: validate the two IDs are present (firebase-env.sh does NOT
# put them in required[]; they are validated lazily here so the client-only
# flow is never broken when api-app IDs are absent from .env).
# ----------------------------------------------------------------
if [[ "$SELECTED_APP" == "api-app" ]]; then
    API_APP_DEBUG_ID="${!DEBUG_APP_ID_VAR:-}"
    API_APP_RELEASE_ID="${!RELEASE_APP_ID_VAR:-}"
    if [[ -z "$API_APP_DEBUG_ID" ]]; then
        echo "FATAL: $DEBUG_APP_ID_VAR is not set in .env." >&2
        echo "       Create the Firebase app first:" >&2
        echo "         firebase apps:create ANDROID \"Lava API (debug)\" \\" >&2
        echo "           --package-name digital.vasic.lava.api.dev \\" >&2
        echo "           --project \$LAVA_FIREBASE_PROJECT_ID" >&2
        echo "       Then set $DEBUG_APP_ID_VAR=<app-id> in .env." >&2
        exit 1
    fi
    if [[ -z "$API_APP_RELEASE_ID" ]]; then
        echo "FATAL: $RELEASE_APP_ID_VAR is not set in .env." >&2
        echo "       Create the Firebase app first:" >&2
        echo "         firebase apps:create ANDROID \"Lava API (release)\" \\" >&2
        echo "           --package-name digital.vasic.lava.api \\" >&2
        echo "           --project \$LAVA_FIREBASE_PROJECT_ID" >&2
        echo "       Then set $RELEASE_APP_ID_VAR=<app-id> in .env." >&2
        exit 1
    fi
    RESOLVED_DEBUG_APP_ID="$API_APP_DEBUG_ID"
    RESOLVED_RELEASE_APP_ID="$API_APP_RELEASE_ID"
else
    # client: the vars are guaranteed non-empty by firebase-env.sh required[]
    RESOLVED_DEBUG_APP_ID="${!DEBUG_APP_ID_VAR}"
    RESOLVED_RELEASE_APP_ID="${!RELEASE_APP_ID_VAR}"
fi

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
    # Firebase Android app (per-app: LAVA_FIREBASE_ANDROID_DEV_APP_ID for client,
    # LAVA_FIREBASE_API_APP_DEV_APP_ID for api-app).
    distribute_apk "$DEBUG_APK" "debug" "$RESOLVED_DEBUG_APP_ID"
fi
if [[ "$MODE" == "release" || "$MODE" == "both" ]]; then
    distribute_apk "$RELEASE_APK" "release" "$RESOLVED_RELEASE_APP_ID"
fi

# ----------------------------------------------------------------
# 5. Local distribution log (gitignored per .gitignore firebase-distribute-*.log)
# ----------------------------------------------------------------
LOG="firebase-distribute-${SELECTED_APP}-${APP_VERSION}-${APP_VERSION_CODE}-${TIMESTAMP}.log"
{
    echo "timestamp=$TIMESTAMP"
    echo "app=$SELECTED_APP"
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
# (Only applicable to the client app — api-app skips Gates 4+5 entirely.)
if [[ "$SKIP_PHASE1_AUTH" != "yes" && -n "${PEPPER_SHA:-}" ]]; then
    echo "$PEPPER_SHA  # $APP_VERSION-$APP_VERSION_CODE  $TIMESTAMP" >> "$PEPPER_HISTORY"
    echo "==> Phase 1 Gate 4 pepper SHA recorded: $PEPPER_SHA → $PEPPER_HISTORY"
fi

echo "==> Firebase distribute complete ($SELECTED_APP)."
echo "    Console: https://console.firebase.google.com/project/$LAVA_FIREBASE_PROJECT_ID/appdistribution"
