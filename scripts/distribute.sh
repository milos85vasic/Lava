#!/usr/bin/env bash
# scripts/distribute.sh — operator's single-command distribute flow.
#
# Rebuilds every artifact (Android debug + release APKs, Ktor proxy fat
# JAR, lava-api-go static binary, lava-api-go OCI image), then:
#
#   * Uploads Android APKs to Firebase App Distribution + invites testers.
#   * Distributes the Go API to the configured remote host (default:
#     thinker.local) via the Containers-submodule-driven remote pipeline
#     and tears down the local API stack so the workstation no longer
#     runs Postgres + the Go API.
#
# Usage:
#   ./scripts/distribute.sh                  # rebuild + distribute everything
#   ./scripts/distribute.sh --android-only   # rebuild + distribute Android only
#   ./scripts/distribute.sh --api-only       # rebuild + distribute Go API only
#   ./scripts/distribute.sh --no-rebuild     # skip rebuild, distribute existing artifacts
#   ./scripts/distribute.sh --release-only   # only release APK (skip debug)
#   ./scripts/distribute.sh --debug-only     # only debug APK
#   ./scripts/distribute.sh --no-teardown    # leave local API containers running
#
# Architecture (per Decoupled Reusable Architecture rule):
#
#   * Generic remote-host SSH + container-orchestration primitives live in
#     `Submodules/Containers/pkg/remote/` (the vasic-digital/Containers
#     submodule). Lava's distribute scripts are thin glue that:
#       1. Build artifacts via build_and_release.sh (which itself routes
#          through the Containers build path).
#       2. Invoke `scripts/distribute-api-remote.sh` for the SSH+scp+
#          podman-load+boot dance on the remote.
#       3. Drive Firebase App Distribution for the Android side.
#
#   * The Containers submodule's `bin/boot` CLI is built on demand (so
#     pinned hash failures show up early, before any remote operation).
#
# Constitutional bindings:
#   §6.J Anti-Bluff — propagates failures via set -euo pipefail
#   §6.K Builds-Inside-Containers — build_and_release.sh routes through
#         the container path for release-tagged builds
#   §6.H Credential Security — all Firebase tokens + remote SSH config
#         loaded from .env (gitignored)
#   §6.P Distribution Versioning + Changelog Mandate — both the Firebase
#         and the remote-distribute scripts gate on §6.P.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAVA_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$LAVA_REPO_ROOT"

REBUILD=true
ANDROID_ONLY=false
API_ONLY=false
TEARDOWN_LOCAL=true
DISTRIBUTE_FLAGS=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-rebuild) REBUILD=false; shift ;;
        --android-only) ANDROID_ONLY=true; shift ;;
        --api-only) API_ONLY=true; shift ;;
        --no-teardown) TEARDOWN_LOCAL=false; shift ;;
        --release-only) DISTRIBUTE_FLAGS+=("--release-only"); shift ;;
        --debug-only) DISTRIBUTE_FLAGS+=("--debug-only"); shift ;;
        *) shift ;;
    esac
done

# ----------------------------------------------------------------
# 1. Ensure the Containers-submodule `boot` CLI is built (best-effort).
#    The remote-distribute pipeline does NOT directly invoke this binary
#    today; building it here is a fast-fail so a broken submodule pin
#    surfaces BEFORE we ship anything to the remote host.
# ----------------------------------------------------------------
if [[ "$ANDROID_ONLY" != "true" ]]; then
    CONTAINERS_BIN_BOOT="$LAVA_REPO_ROOT/Submodules/Containers/bin/boot"
    if [[ ! -x "$CONTAINERS_BIN_BOOT" ]]; then
        echo "==> Submodules/Containers/bin/boot missing — building."
        if [[ -f "$LAVA_REPO_ROOT/Submodules/Containers/Makefile" ]]; then
            (cd "$LAVA_REPO_ROOT/Submodules/Containers" && make build-boot 2>/dev/null) || \
                (cd "$LAVA_REPO_ROOT/Submodules/Containers" && go build -o bin/boot ./cmd/boot)
        else
            (cd "$LAVA_REPO_ROOT/Submodules/Containers" && go build -o bin/boot ./cmd/boot)
        fi
    fi
    if [[ -x "$CONTAINERS_BIN_BOOT" ]]; then
        echo "    Containers boot CLI: $($CONTAINERS_BIN_BOOT --help 2>&1 | head -1)"
    fi
fi

# ----------------------------------------------------------------
# 2. Rebuild artifacts (idempotent if up to date).
# ----------------------------------------------------------------
if [[ "$REBUILD" == "true" ]]; then
    echo "==> Rebuilding artifacts via build_and_release.sh"
    ./build_and_release.sh
fi

# ----------------------------------------------------------------
# 3. Distribute the Android side via Firebase App Distribution.
# ----------------------------------------------------------------
if [[ "$API_ONLY" != "true" ]]; then
    echo "==> Distributing Android artifacts via Firebase App Distribution"
    "$SCRIPT_DIR/firebase-distribute.sh" "${DISTRIBUTE_FLAGS[@]}"
fi

# ----------------------------------------------------------------
# 4. Distribute the Go API to the configured remote host. Containers'
#    SSH primitives back the operations; the Lava-domain wrapper is
#    scripts/distribute-api-remote.sh.
# ----------------------------------------------------------------
if [[ "$ANDROID_ONLY" != "true" ]]; then
    echo "==> Distributing Go API to remote host"
    "$SCRIPT_DIR/distribute-api-remote.sh"
fi

# ----------------------------------------------------------------
# 5. Tear down the local API containers + image (the API now runs on
#    the remote host; the workstation only builds going forward).
# ----------------------------------------------------------------
if [[ "$ANDROID_ONLY" != "true" && "$TEARDOWN_LOCAL" == "true" ]]; then
    echo "==> Tearing down local API containers + image"
    if [[ -x "$LAVA_REPO_ROOT/stop.sh" ]]; then
        "$LAVA_REPO_ROOT/stop.sh" 2>&1 | tail -3 || true
    fi
    for img in localhost/lava_lava-api-go:latest localhost/lava-api-go:dev; do
        if podman image exists "$img" 2>/dev/null; then
            podman rmi -f "$img" >/dev/null 2>&1 && echo "    removed local image $img" || true
        fi
    done
    echo "    Local API containers + images removed. Remote host is the new home."
fi

# ----------------------------------------------------------------
# 6. Print stats dashboard URLs for the operator.
# ----------------------------------------------------------------
if [[ "$API_ONLY" != "true" ]]; then
    echo ""
    "$SCRIPT_DIR/firebase-stats.sh"
fi

echo ""
echo "==> Distribution complete."
if [[ "$ANDROID_ONLY" != "true" ]]; then
    echo "    Go API booted on the configured remote host."
fi
if [[ "$API_ONLY" != "true" ]]; then
    echo "    Android testers will receive an email invite from Firebase."
fi
