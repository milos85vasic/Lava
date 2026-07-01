#!/usr/bin/env bash
# scripts/autonomous-qa/lib-backend.sh
# ---------------------------------------------------------------------------
# Bring exactly ONE backend up at a time (operator: never run both APIs
# concurrently). A lock file (.backend-active) enforces mutual exclusion.
#
#   Go backend  (lava-api-go)  : podman compose, :8443, /health; client reaches
#                                it from the emulator via `adb reverse` (the
#                                topology-independent path for a containerized
#                                emulator).
#   Android backend (:api-app) : on-device embed (loopback) — client + API both
#                                on the emulator, no adb reverse needed.
#
# Functions: backend_up_goapi backend_target_goapi backend_down_goapi
#            backend_up_apiapp backend_target_apiapp backend_down_apiapp
# ---------------------------------------------------------------------------
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../.." && pwd)"
LOCK="$QA_DIR/.backend-active"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
GOAPI_HEALTH="https://127.0.0.1:8443/health"
APIAPP_PKG="digital.vasic.lava.api.dev"
# Explicit launch component for the on-device API app (namespace lava.api.app,
# activity .MainActivity → fully-qualified lava.api.app.MainActivity). Only a
# launch carrying EXTRA_START_API=true drives ApiControlViewModel.onStartRequested
# → ApiEngineController.start(), which binds the embed's HTTPS listener; a plain
# LAUNCHER launch leaves the engine Stopped (api-app MainActivity.handleIntent).
APIAPP_ACTIVITY="lava.api.app.MainActivity"
APIAPP_START_EXTRA="lava.applink.START_API"   # AppLinkContract.EXTRA_START_API
# The embed's production listener port = lava.apiengine.ApiConfig's default
# (ApiEngineController constructs the engine with the default port; ApiEngine.kt
# `val port: Int = 8443`). Same value the host Go backend uses above — only ONE
# backend runs at a time (the .backend-active lock), so there is no clash.
APIAPP_PORT=8443

_backend_assert_free() {
  if [[ -f "$LOCK" ]]; then
    echo "[backend] ERROR backend '$(cat "$LOCK")' already active — bring it down first (operator: never run both APIs)." >&2
    return 1
  fi
}

backend_up_goapi() {
  _backend_assert_free || return 1
  # Use the project's blessed bring-up: ./start.sh builds the lava-containers
  # Go CLI, generates TLS via gen-cert.sh, sets BUILDAH_FORMAT=docker, and
  # delegates to the Containers submodule's compose orchestrator. Raw
  # `podman compose` (podman-compose) CANNOT handle the `network_mode: host`
  # services + the lava-net network (it creates the net but no containers); the
  # Go orchestrator parses the compose correctly. Never use raw compose here.
  echo "[backend] bringing up Go backend via ./start.sh (lava-containers, api-go profile)" >&2
  ( cd "$REPO_ROOT" && BUILDAH_FORMAT=docker ./start.sh ) || { echo "[backend] ERROR ./start.sh failed" >&2; return 1; }
  local waited=0
  while (( waited < 120 )); do
    if curl -ksf "$GOAPI_HEALTH" >/dev/null 2>&1; then
      echo "goapi" > "$LOCK"
      echo "[backend] goapi healthy after ${waited}s" >&2
      return 0
    fi
    sleep 3; waited=$((waited+3))
  done
  echo "[backend] ERROR goapi /health never passed after ${waited}s. Status + logs:" >&2
  ( cd "$REPO_ROOT" && tools/lava-containers/bin/lava-containers -cmd=status -project-dir="$REPO_ROOT" -profile=api-go ) >&2 2>&1 || true
  podman logs lava-api-go 2>&1 | tail -40 >&2 || true
  return 1
}

# Print the URL the on-device client should use; sets up adb reverse so the
# emulator's 127.0.0.1:8443 tunnels to the host Go API over the adb transport.
backend_target_goapi() {
  local serial="$1"
  "$ADB" -s "$serial" reverse tcp:8443 tcp:8443 >/dev/null
  echo "https://127.0.0.1:8443"
}

backend_down_goapi() {
  ( cd "$REPO_ROOT" && tools/lava-containers/bin/lava-containers -cmd=stop -project-dir="$REPO_ROOT" -profile=api-go ) >/dev/null 2>&1 \
    || ( cd "$REPO_ROOT" && ./stop.sh ) >/dev/null 2>&1 || true
  rm -f "$LOCK"
  echo "[backend] goapi down" >&2
}

# Install the api-app debug APK, drive the embed up on-device, and POLL its
# PUBLIC /health route over real TLS until 200 (§6.B — container/app "Up" is not
# "healthy"). On success writes the lock and echoes (stdout) the URL the
# on-device client uses; all diagnostics go to stderr. Returns non-zero (no
# lock) if the embed never serves, so the matrix fails honestly.
backend_up_apiapp() {
  _backend_assert_free || return 1
  local serial="$1"
  local apk
  apk="$(find "$REPO_ROOT/api-app/build/outputs/apk/debug" -name '*.apk' -type f 2>/dev/null | head -1 || true)"
  [[ -z "$apk" ]] && { echo "[backend] ERROR :api-app debug APK missing — run ./gradlew :api-app:assembleDebug" >&2; return 1; }

  echo "[backend] installing :api-app debug APK on $serial" >&2
  "$ADB" -s "$serial" install -r "$apk" >/dev/null \
    || { echo "[backend] ERROR adb install of $apk failed" >&2; return 1; }

  # Pre-grant POST_NOTIFICATIONS so the EXTRA_START_API auto-start path does NOT
  # stall on the runtime-permission dialog: MainActivity (API 33+) launches the
  # system dialog and only dispatches StartRequested from its result callback —
  # headless, nobody taps it, so the embed would never start. Pre-granting makes
  # checkSelfPermission == GRANTED so the start fires immediately. Harmless <33.
  "$ADB" -s "$serial" shell pm grant "$APIAPP_PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

  # The ONLY headless trigger that binds the embed's listener (see APIAPP_*
  # constants). A plain LAUNCHER launch leaves the engine Stopped.
  echo "[backend] launching $APIAPP_PKG/$APIAPP_ACTIVITY with $APIAPP_START_EXTRA=true" >&2
  "$ADB" -s "$serial" shell am start -n "$APIAPP_PKG/$APIAPP_ACTIVITY" \
    --ez "$APIAPP_START_EXTRA" true >/dev/null 2>&1 \
    || { echo "[backend] ERROR am start of api-app failed" >&2; return 1; }

  # READINESS: forward a host port to the device's embed listener, then poll the
  # public pre-auth /health over real TLS. The embed binds 0.0.0.0:<port> only
  # after ApiEngine.start() succeeds, so a 200 here proves the server actually
  # accepted a TLS connection and served — not merely that the app process is
  # alive. The forward is for THIS host-side probe only; the on-device client
  # reaches the embed over its own loopback and needs no forward/reverse.
  local fwd_port
  fwd_port="$("$ADB" -s "$serial" forward tcp:0 tcp:"${APIAPP_PORT}")" \
    || { echo "[backend] ERROR adb forward to device :${APIAPP_PORT} failed" >&2; return 1; }
  echo "[backend] probing on-device embed /health via host forward tcp:${fwd_port} -> device:${APIAPP_PORT}" >&2

  local waited=0
  while (( waited < 90 )); do
    if curl -ksf "https://127.0.0.1:${fwd_port}/health" >/dev/null 2>&1; then
      "$ADB" -s "$serial" forward --remove "tcp:${fwd_port}" >/dev/null 2>&1 || true
      echo "apiapp" > "$LOCK"
      echo "[backend] apiapp embed healthy after ${waited}s (on-device /health 200 over TLS)" >&2
      # stdout: the URL the on-device client uses (its OWN loopback to the embed).
      echo "https://127.0.0.1:${APIAPP_PORT}"
      return 0
    fi
    sleep 3; waited=$((waited+3))
  done

  "$ADB" -s "$serial" forward --remove "tcp:${fwd_port}" >/dev/null 2>&1 || true
  echo "[backend] ERROR apiapp /health never passed after ${waited}s (no lock written). Recent logcat:" >&2
  "$ADB" -s "$serial" logcat -d -t 200 2>/dev/null \
    | grep -iE 'lava|apiengine|ApiControl|ApiEngineService|AndroidRuntime|FATAL|NativeApi' | tail -40 >&2 || true
  return 1
}

# Print the URL the on-device client uses to reach the embed. Symmetry with
# backend_target_goapi; backend_up_apiapp already echoes this, but the matrix's
# external-backend mode (embed brought up out-of-band) calls this directly. The
# client + embed share the emulator, so it is plain loopback — no adb reverse.
backend_target_apiapp() {
  local _serial="${1:-}"   # accepted for call-signature symmetry; unused (loopback)
  echo "https://127.0.0.1:${APIAPP_PORT}"
}

backend_down_apiapp() {
  local serial="${1:-}"
  if [[ -n "$serial" ]]; then
    "$ADB" -s "$serial" shell am force-stop "$APIAPP_PKG" >/dev/null 2>&1 || true
    "$ADB" -s "$serial" uninstall "$APIAPP_PKG" >/dev/null 2>&1 || true
  fi
  rm -f "$LOCK"
  echo "[backend] apiapp down" >&2
}
