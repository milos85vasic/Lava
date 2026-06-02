#!/usr/bin/env bash
#
# build-aar.sh — build the Android .aar bundle for the lava-api-go embedded
# server surface (internal/mobile) via gomobile bind.
#
# External documentation: docs/scripts/build-aar.sh.md
#
# This is Phase B2 of the Lava API Android app plan. It produces
# lava-api-go/build/lavaapi.aar — an Android library archive exposing the
# internal/mobile Start/Stop/Status surface to the Android client through JNI.
#
# Constitutional notes:
#   - §6.T.2 resource limits: the gomobile bind compile is prefixed with
#     GOMAXPROCS=2 nice -n19 so it does not starve the host.
#   - §6.U no-sudo: gomobile + NDK are installed user-locally; npm/go installs
#     write to user-writable prefixes. No sudo anywhere.
#   - §6.R no-hardcoding: NDK paths come from $ANDROID_NDK_HOME / $ANDROID_HOME
#     env vars (with documented discovery fallbacks); the javapkg + target are
#     build identity, not config secrets.
#
# Usage:
#   ./scripts/build-aar.sh
#
# Environment (optional overrides):
#   ANDROID_HOME       — Android SDK root (default: ~/Library/Android/sdk)
#   ANDROID_NDK_HOME   — NDK root (default: discovered under $ANDROID_HOME/ndk)
#   LAVAAPI_JAVAPKG    — Java package for generated bindings
#                        (default: digital.vasic.lava.apigo)
#   LAVAAPI_ANDROIDAPI — minSdk for the bind (default: 28)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

JAVAPKG="${LAVAAPI_JAVAPKG:-digital.vasic.lava.apigo}"
ANDROIDAPI="${LAVAAPI_ANDROIDAPI:-28}"
OUT_DIR="${MODULE_DIR}/build"
OUT_AAR="${OUT_DIR}/lavaapi.aar"

echo "==> build-aar.sh: lava-api-go Android .aar"
echo "    module:     ${MODULE_DIR}"
echo "    javapkg:    ${JAVAPKG}"
echo "    androidapi: ${ANDROIDAPI}"
echo "    output:     ${OUT_AAR}"

# --- Android SDK / NDK discovery (no hardcoded host paths; env-first) -------
ANDROID_HOME="${ANDROID_HOME:-${HOME}/Library/Android/sdk}"
export ANDROID_HOME

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  # Prefer NDK 25.x, then 21.x, then any installed NDK.
  for candidate in \
    "${ANDROID_HOME}/ndk/25.1.8937393" \
    "${ANDROID_HOME}/ndk/21.4.7075529"; do
    if [[ -d "${candidate}" ]]; then
      ANDROID_NDK_HOME="${candidate}"
      break
    fi
  done
  if [[ -z "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_HOME}/ndk" ]]; then
    ANDROID_NDK_HOME="$(find "${ANDROID_HOME}/ndk" -maxdepth 1 -mindepth 1 -type d | sort | tail -1)"
  fi
fi
export ANDROID_NDK_HOME

echo "    ANDROID_HOME=${ANDROID_HOME}"
echo "    ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-<unset>}"

if [[ ! -d "${ANDROID_HOME}" ]]; then
  echo "ERROR: ANDROID_HOME does not exist: ${ANDROID_HOME}" >&2
  echo "       Set ANDROID_HOME to your Android SDK root." >&2
  exit 2
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "${ANDROID_NDK_HOME}" ]]; then
  echo "ERROR: no Android NDK found. Set ANDROID_NDK_HOME to an NDK root." >&2
  exit 2
fi

# --- Ensure gomobile + gobind are installed (user-local; no sudo) -----------
GOBIN="$(go env GOBIN)"
if [[ -z "${GOBIN}" ]]; then
  GOBIN="$(go env GOPATH)/bin"
fi
export PATH="${GOBIN}:${PATH}"

if ! command -v gomobile >/dev/null 2>&1; then
  echo "==> installing gomobile + gobind (user-local)"
  GOMAXPROCS=2 nice -n19 go install golang.org/x/mobile/cmd/gomobile@latest
  GOMAXPROCS=2 nice -n19 go install golang.org/x/mobile/cmd/gobind@latest
fi

echo "==> gomobile init (ANDROID_NDK_HOME=${ANDROID_NDK_HOME})"
GOMAXPROCS=2 nice -n19 gomobile init

# --- Build the .aar ---------------------------------------------------------
mkdir -p "${OUT_DIR}"
cd "${MODULE_DIR}"

echo "==> gomobile bind"
GOMAXPROCS=2 nice -n19 gomobile bind \
  -target=android \
  -androidapi "${ANDROIDAPI}" \
  -javapkg "${JAVAPKG}" \
  -o "${OUT_AAR}" \
  ./internal/mobile

# --- Report -----------------------------------------------------------------
if [[ ! -f "${OUT_AAR}" ]]; then
  echo "ERROR: gomobile bind reported success but ${OUT_AAR} is missing" >&2
  exit 1
fi

SIZE_BYTES="$(wc -c < "${OUT_AAR}" | tr -d ' ')"
if command -v shasum >/dev/null 2>&1; then
  SHA="$(shasum -a 256 "${OUT_AAR}" | awk '{print $1}')"
else
  SHA="$(sha256sum "${OUT_AAR}" | awk '{print $1}')"
fi

echo "==> DONE"
echo "    aar:    ${OUT_AAR}"
echo "    size:   ${SIZE_BYTES} bytes"
echo "    sha256: ${SHA}"
