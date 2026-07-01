#!/usr/bin/env bash
# scripts/autonomous-qa/lib-emulator.sh
# ---------------------------------------------------------------------------
# Thin Lava glue replicating submodules/containers/pkg/emulator/containerized.go
# to boot the §6.X containerized KVM Android emulator image and wire host adb.
#
# §6.AH/§6.AG/§6.X: the emulator process runs INSIDE a podman container (image
# built from the submodule Containerfile), NEVER host-direct, NEVER a live
# device. The image bundles the AVD "default", an adb keypair, and a socat
# bridge (container 0.0.0.0:5575 -> 127.0.0.1:5555 adbd).
#
# Functions: emu_pick_port emu_boot emu_authorize_adb emu_connect
#            emu_wait_boot emu_teardown
# State file: scripts/autonomous-qa/.emu-state (CONTAINER / ADB_PORT / CONSOLE_PORT)
# ---------------------------------------------------------------------------
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMU_IMAGE="${EMU_IMAGE:-ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64}"
EMU_STATE="$QA_DIR/.emu-state"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"

# Pick a free TCP port on loopback.
emu_pick_port() {
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(('127.0.0.1', 0))
print(s.getsockname()[1])
s.close()
PY
}

# emu_boot [avd] -> prints container name; persists state.
emu_boot() {
  local avd="${1:-default}"
  local adb_port console_port ts name
  adb_port="$(emu_pick_port)"
  console_port="$(emu_pick_port)"
  ts="$(date +%s%3N)"
  name="lava-emu-$(printf '%s' "$avd" | tr -cs 'A-Za-z0-9' '-')-$ts"
  echo "[emu] boot avd=$avd image=$EMU_IMAGE adb_host_port=$adb_port" >&2
  podman run -d --rm \
    --userns=keep-id \
    --device /dev/kvm \
    -p "127.0.0.1:${adb_port}:5575/tcp" \
    -p "127.0.0.1:${console_port}:5554/tcp" \
    -e ANDROID_AVD_NAME="$avd" \
    -e ANDROID_COLD_BOOT=true \
    --name "$name" \
    "$EMU_IMAGE" >/dev/null
  printf 'CONTAINER=%s\nADB_PORT=%s\nCONSOLE_PORT=%s\n' \
    "$name" "$adb_port" "$console_port" > "$EMU_STATE"
  echo "$name"
}

# emu_authorize_adb <container>: copy the image's baked adb private key out and
# point the host adb client at it (matches the AVD's authorized pubkey).
emu_authorize_adb() {
  local container="$1"
  local keydir
  keydir="$(mktemp -d)"
  podman cp "${container}:/home/emulator/.android/adbkey" "$keydir/adbkey"
  podman cp "${container}:/home/emulator/.android/adbkey.pub" "$keydir/adbkey.pub"
  chmod 600 "$keydir/adbkey"
  export ADB_VENDOR_KEYS="$keydir/adbkey"
  "$ADB" kill-server >/dev/null 2>&1 || true
  "$ADB" start-server >/dev/null 2>&1 || true
  echo "[emu] adb authorized via baked key" >&2
}

# emu_connect <adb_port> -> prints the adb serial.
emu_connect() {
  local adb_port="$1"
  "$ADB" connect "127.0.0.1:${adb_port}" >/dev/null 2>&1 || true
  echo "127.0.0.1:${adb_port}"
}

# emu_wait_boot <serial> [timeout_secs]: poll sys.boot_completed + pm ready.
emu_wait_boot() {
  local serial="$1" timeout="${2:-360}" waited=0 bc
  echo "[emu] waiting for boot serial=$serial timeout=${timeout}s" >&2
  while (( waited < timeout )); do
    "$ADB" connect "${serial}" >/dev/null 2>&1 || true
    bc="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$bc" == "1" ]] && "$ADB" -s "$serial" shell pm path android >/dev/null 2>&1; then
      echo "[emu] boot complete after ${waited}s" >&2
      return 0
    fi
    sleep 3; waited=$((waited+3))
  done
  echo "[emu] ERROR boot timeout after ${timeout}s. Diagnostics:" >&2
  "$ADB" devices >&2 || true
  local c
  c="$(grep '^CONTAINER=' "$EMU_STATE" 2>/dev/null | cut -d= -f2 || true)"
  [[ -n "$c" ]] && podman logs "$c" 2>&1 | tail -40 >&2 || true
  return 1
}

# emu_fix_network <serial>: ensure the guest has an active default network.
# On some hosts (observed: podman 4.9.3 on thinker.local) the emulator boots
# with eth0 DOWN and Android never registers it as a framework network — and
# because Android routes per-network via netd mark-based tables, a raw `ip route`
# is ignored, so apps see "Network unreachable". This post-boot dance (root via
# `su 0`) brings eth0 up and adds it to the default physical network (netId 100)
# through ndc, so ConnectivityService reports an active network. The keystone's
# guest->backend hop is adb-reverse LOOPBACK (the backend, not the guest, has
# real internet), so this only needs an active default network, not guest WAN.
#
# Self-gating + idempotent: if the gateway 10.0.2.2 already pings (healthy guest,
# e.g. local podman 5.7.1), it no-ops. Pre-existing-network / already-present-
# route errors from ndc are non-fatal. Never fails the run — the iteration still
# records the REAL outcome (anti-bluff: a stalled onboarding shows as SKIP/FAIL).
#
# §6.R NOTE — the 10.0.2.x literals below are NOT hardcoded deployment addresses.
# They are platform-FIXED Android-emulator QEMU user-mode (slirp) constants,
# hardwired by the emulator and non-configurable (cannot drift) — the emulator
# equivalent of 127.0.0.1:
#   10.0.2.2  = host-loopback alias (slirp gateway to the host)
#   10.0.2.3  = the emulator's built-in DNS server
#   10.0.2.15 = the guest NIC's own address
# (Google: developer.android.com/studio/run/emulator-networking — "Emulator
# networking / Network address space".) The §6.R IPv4 scanner carries a narrow
# path+range exemption for the 10.0.2.0/24 slirp range scoped to this autonomous-qa
# emulator helper; see scripts/scan-no-hardcoded-ipv4.sh. A real deployment IP
# here would still be flagged.
emu_fix_network() {
  local serial="$1"
  if "$ADB" -s "$serial" shell "ping -c1 -W2 10.0.2.2" >/dev/null 2>&1; then
    echo "[emu] guest network already healthy (gateway 10.0.2.2 reachable)" >&2
    return 0
  fi
  echo "[emu] guest network DOWN — applying eth0 + ndc default-network fix (retry loop)" >&2
  # Boot-timing race: Android's netId 100 and eth0's carrier may not be ready on
  # the first pass (interface-add / default-set silently no-op), so retry the whole
  # dance until the slirp gateway 10.0.2.2 actually pings. Deterministic active
  # default network is required for the app's HTTP (even the adb-reverse loopback
  # hop is gated by ConnectivityService reporting a network).
  local attempt
  for attempt in 1 2 3 4 5 6 7 8; do
    "$ADB" -s "$serial" shell su 0 ip link set eth0 up               >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell su 0 ip addr add 10.0.2.15/24 dev eth0 >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell su 0 ndc network interface add 100 eth0 >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell su 0 ndc network default set 100       >/dev/null 2>&1 || true
    # default route AFTER eth0 up + in net (first pass can race "Network unreachable").
    "$ADB" -s "$serial" shell su 0 ndc network route add 100 eth0 0.0.0.0/0 10.0.2.2 >/dev/null 2>&1 || true
    "$ADB" -s "$serial" shell su 0 setprop net.dns1 10.0.2.3         >/dev/null 2>&1 || true
    sleep 2
    if "$ADB" -s "$serial" shell "ping -c2 -W2 10.0.2.2" >/dev/null 2>&1; then
      echo "[emu] network fix OK on attempt $attempt — gateway 10.0.2.2 reachable, default network active" >&2
      return 0
    fi
    echo "[emu] network fix attempt $attempt: gateway not yet reachable; retrying" >&2
    sleep 2
  done
  echo "[emu] WARN network fix did not restore gateway reachability after 8 attempts (run proceeds; outcome recorded)" >&2
  return 0
}

# emu_cleanup_orphans: remove any leftover lava-emu-* containers left running
# by an interrupted run (the --rm only fires on clean stop, so a killed parent
# leaves the emulator running + holding a KVM slot). Call before booting.
emu_cleanup_orphans() {
  local ids
  ids="$(podman ps -aq --filter 'name=lava-emu-' 2>/dev/null || true)"
  if [[ -n "$ids" ]]; then
    echo "[emu] removing orphaned emulator containers" >&2
    # shellcheck disable=SC2086
    podman rm -f $ids >/dev/null 2>&1 || true
  fi
}

# emu_teardown [container]: remove the container + state file.
emu_teardown() {
  local container="${1:-}"
  if [[ -z "$container" && -f "$EMU_STATE" ]]; then
    container="$(grep '^CONTAINER=' "$EMU_STATE" | cut -d= -f2 || true)"
  fi
  [[ -n "$container" ]] && podman rm -f "$container" >/dev/null 2>&1 || true
  rm -f "$EMU_STATE"
  echo "[emu] torn down ${container:-<none>}" >&2
}
