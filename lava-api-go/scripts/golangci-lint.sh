#!/usr/bin/env bash
#
# scripts/golangci-lint.sh — run golangci-lint over lava-api-go.
#
# golangci-lint needs NO auth/token. Per CONSTITUTION.md §6.U (no sudo/su) and
# the Containers-first posture, this prefers the official container image via
# rootless podman/docker so no host install is required. If neither runtime can
# run the image (offline / VM unreachable), it falls back to a locally-installed
# `golangci-lint` binary. If neither is available, it exits 3 (tool-unavailable)
# so callers can distinguish "tool missing" from "lint found problems".
#
# Usage:
#   scripts/golangci-lint.sh [extra golangci-lint args...]
#
# Env:
#   GOLANGCI_LINT_IMAGE   override the container image (default below)
#   LAVA_GOLANGCI_NO_CONTAINER=1   skip the container path, use host binary only
#
# Exit codes:
#   0  clean
#   1  lint findings
#   3  tool unavailable (no container runtime AND no host binary)
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Default to :latest because the module targets a recent Go version (go.mod
# pins 1.26); pinned older images (e.g. v2.1.6, built with Go 1.24) refuse to
# load the config with "Go language version used to build golangci-lint is
# lower than the targeted Go version". Override via GOLANGCI_LINT_IMAGE.
IMAGE="${GOLANGCI_LINT_IMAGE:-golangci/golangci-lint:latest}"
ARGS=("run" "./...")
if [[ $# -gt 0 ]]; then
  ARGS=("$@")
fi

log()  { printf '\033[1;36m[golangci]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[golangci:warn]\033[0m %s\n' "$*" >&2; }

# 1. Prefer the official container image (no host install; reproducible).
if [[ "${LAVA_GOLANGCI_NO_CONTAINER:-0}" != "1" ]]; then
  RUNTIME=""
  if command -v podman >/dev/null 2>&1; then
    RUNTIME="podman"
  elif command -v docker >/dev/null 2>&1; then
    RUNTIME="docker"
  fi

  if [[ -n "$RUNTIME" ]]; then
    # The module's go.mod uses `replace` directives pointing at
    # ../submodules/* (the vasic-digital submodules). For the in-container
    # typecheck to resolve those, the MOUNT must include the parent dir that
    # holds both lava-api-go/ and submodules/ — mounting just the module would
    # make the replacement directories "not exist" inside the container.
    PARENT="$(cd "$ROOT/.." && pwd)"
    MODULE_NAME="$(basename "$ROOT")"
    if [[ -d "$PARENT/submodules" ]]; then
      MOUNT_SRC="$PARENT"; WORKDIR="/app/$MODULE_NAME"; CACHE="/app/$MODULE_NAME/.golangci-cache"
    else
      MOUNT_SRC="$ROOT"; WORKDIR="/app"; CACHE="/app/.golangci-cache"
    fi
    log "running via $RUNTIME image $IMAGE (mount $MOUNT_SRC -> /app, wd $WORKDIR)"
    # Mount read-write (golangci-lint writes a cache, redirected into the mount
    # so nothing leaks onto the host). No sudo.
    if "$RUNTIME" run --rm \
        -v "$MOUNT_SRC":/app:Z \
        -w "$WORKDIR" \
        -e GOLANGCI_LINT_CACHE="$CACHE" \
        -e GOFLAGS=-mod=mod \
        "$IMAGE" golangci-lint "${ARGS[@]}"; then
      exit 0
    else
      rc=$?
      # rc==1 => lint findings (a real result). Any other non-zero from the
      # runtime (image pull failure, VM unreachable) => fall through to host.
      if [[ $rc -eq 1 ]]; then
        exit 1
      fi
      warn "$RUNTIME path failed (rc=$rc); falling back to host binary if present"
    fi
  else
    warn "no podman/docker runtime found; trying host binary"
  fi
fi

# 2. Host binary fallback.
if command -v golangci-lint >/dev/null 2>&1; then
  log "running host golangci-lint $(golangci-lint version 2>/dev/null | head -1)"
  exec golangci-lint "${ARGS[@]}"
fi

warn "golangci-lint unavailable: no working container runtime and no host binary"
exit 3
