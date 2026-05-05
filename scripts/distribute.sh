#!/usr/bin/env bash
# scripts/distribute.sh — umbrella distribution script.
#
# Rebuilds Android (debug + release) + lava-api-go + Ktor proxy, then
# uploads Android APKs to Firebase App Distribution and the testers
# loaded from .env are notified.
#
# Usage:
#   ./scripts/distribute.sh                 # rebuild + distribute everything
#   ./scripts/distribute.sh --no-rebuild    # skip rebuild, distribute existing artifacts
#   ./scripts/distribute.sh --android-only  # rebuild + distribute Android only
#   ./scripts/distribute.sh --release-only  # only release APK (skip debug)
#
# This is the operator's one-command distribute flow. Internally:
#   1. ./build_and_release.sh           → produce all artifacts in releases/
#   2. ./scripts/firebase-distribute.sh → upload Android APKs to App Dist.
#
# (lava-api-go + proxy are not yet auto-distributed; their distribution
# story is documented in docs/FIREBASE.md and uses container-registry
# pushes, not Firebase App Distribution which is mobile-app-specific.)
#
# Constitutional bindings:
#   §6.J Anti-Bluff — propagates failures via set -euo pipefail
#   §6.K Builds-Inside-Containers — build_and_release.sh routes through
#         the container path for release-tagged builds
#   §6.H Credential Security — all Firebase tokens loaded from .env

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAVA_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$LAVA_REPO_ROOT"

REBUILD=true
ANDROID_ONLY=false
DISTRIBUTE_FLAGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-rebuild) REBUILD=false; shift ;;
        --android-only) ANDROID_ONLY=true; shift ;;
        --release-only) DISTRIBUTE_FLAGS+=("--release-only"); shift ;;
        --debug-only) DISTRIBUTE_FLAGS+=("--debug-only"); shift ;;
        *) shift ;;
    esac
done

# ----------------------------------------------------------------
# 1. Rebuild artifacts via build_and_release.sh
# ----------------------------------------------------------------
if [[ "$REBUILD" == "true" ]]; then
    echo "==> Rebuilding artifacts via build_and_release.sh"
    if [[ "$ANDROID_ONLY" == "true" ]]; then
        # build_and_release.sh always builds everything; --android-only is a
        # downstream-distribute filter only.
        ./build_and_release.sh
    else
        ./build_and_release.sh
    fi
fi

# ----------------------------------------------------------------
# 2. Upload Android APKs to Firebase App Distribution
# ----------------------------------------------------------------
echo "==> Distributing Android artifacts via Firebase App Distribution"
"$SCRIPT_DIR/firebase-distribute.sh" "${DISTRIBUTE_FLAGS[@]}"

# ----------------------------------------------------------------
# 3. Print stats dashboard URLs for the operator
# ----------------------------------------------------------------
echo ""
"$SCRIPT_DIR/firebase-stats.sh"

echo ""
echo "==> Distribution complete."
echo "    Testers will receive an email invite from Firebase."
