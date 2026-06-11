#!/usr/bin/env bash
# challenges/helixqa-sessions/dynamic-provider-discovery.sh
#
# Lava-domain HelixQA QA-session scenario — Dynamic Provider Discovery.
#
# Vision-guided QA session (Observation -> Decide -> Decision) that drives the
# Lava Android client end-to-end:
#   launch -> onboarding -> choose an API instance -> the provider list is
#   populated FROM the API -> pick a provider -> run a search -> see results.
#
# Plan: docs/superpowers/plans/2026-06-11-dynamic-provider-discovery.md (Task 6.3)
# Spec: docs/superpowers/specs/2026-06-11-dynamic-provider-discovery-design.md (§4, §6)
# Bank: challenges/helixqa-sessions/banks/dynamic_provider_discovery.bank.yaml
#
# WHY THIS LIVES IN THE LAVA TREE (not submodules/helixqa/):
#   The scenario is project-specific (Lava onboarding + Lava API selection + the
#   Lava API-backed provider catalogue). CONST-051(B) / the Decoupled Reusable
#   Architecture rule forbids injecting project-specific context into the
#   vasic-digital/HelixQA submodule. The HelixQA *engine* is consumed via its
#   binary; the *scenario* is Lava glue, so it is wired into the Lava-side
#   runner scripts/run-helixqa-challenges.sh.
#
# EXIT CODE CONTRACT (matches scripts/run-helixqa-challenges.sh header):
#   0 = PASS              real device completed the flow; positive evidence captured
#   1 = FAIL             real product defect surfaced (a user-visible step failed)
#   2 = SKIP             intentionally skipped (reserved; not used as a default here)
#   3 = PRECONDITION_UNMET  emulator/toolchain/app not available — honestly blocked,
#                           NEVER a faked PASS (§6.J / §11.4 anti-bluff)
#   4 = ERROR            harness error (the QA session could not be evaluated)
#
# §6.AG/§6.AH: the Android device MUST be a VM/container-backed emulator driven by
# the Containers submodule (boot it via scripts/run-genymotion-challenges.sh
# --start, or the §6.X containerized matrix). This scenario CONSUMES an already-
# running adb serial; it never host-direct-launches an emulator itself.
#
# §6.R no-hardcoding: package id is overridable (LAVA_APP_ID); no IP/host:port/UUID
# literals — the API base URL is chosen IN-APP via mDNS during onboarding, which is
# exactly the feature under test.

set -uo pipefail

# ----------------------------------------------------------------------------
# Resolve repo root, paths, config (all overridable; nothing hardcoded that the
# §6.R scanner forbids).
# ----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
BANK="$SCRIPT_DIR/banks/dynamic_provider_discovery.bank.yaml"

PKG="${LAVA_APP_ID:-digital.vasic.lava.client.dev}"
RUN_TIMEOUT_SEC="${LDPD_TIMEOUT_SEC:-1800}"   # helixqa session cap (30 min default)

# Evidence directory: the runner exports HELIXQA_SCENARIO_EVIDENCE_DIR; standalone
# runs fall back to a dated dir under .lava-ci-evidence.
TS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
EVIDENCE_DIR="${HELIXQA_SCENARIO_EVIDENCE_DIR:-${LAVA_HELIXQA_EVIDENCE_DIR:-$REPO_ROOT/.lava-ci-evidence/helixqa-challenges/$TS/dynamic-provider-discovery}}"
mkdir -p "$EVIDENCE_DIR" 2>/dev/null || true
EVIDENCE_DIR="$(cd "$EVIDENCE_DIR" 2>/dev/null && pwd || echo "$EVIDENCE_DIR")"

echo "=== Lava HelixQA QA session: Dynamic Provider Discovery ==="
echo "  repo:        $REPO_ROOT"
echo "  package:     $PKG"
echo "  bank:        $BANK"
echo "  evidence:    $EVIDENCE_DIR"

# A timeout wrapper that degrades gracefully where neither timeout nor gtimeout
# exist (the session just runs uncapped rather than failing the harness).
TIMEOUT_BIN=""
if command -v timeout >/dev/null 2>&1; then TIMEOUT_BIN="timeout"
elif command -v gtimeout >/dev/null 2>&1; then TIMEOUT_BIN="gtimeout"; fi
run_capped() { # run_capped <secs> <cmd...>
  local secs="$1"; shift
  if [[ -n "$TIMEOUT_BIN" ]]; then "$TIMEOUT_BIN" "$secs" "$@"; else "$@"; fi
}

# preflight.json accumulator (written on EVERY exit path, including exit 3).
PREFLIGHT="$EVIDENCE_DIR/preflight.json"
write_preflight() { # write_preflight <verdict> <detail>
  local verdict="$1" detail="$2"
  {
    echo "{"
    echo "  \"scenario\": \"dynamic-provider-discovery\","
    echo "  \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"package\": \"$PKG\","
    echo "  \"adb_present\": $ADB_PRESENT,"
    echo "  \"device_serial\": \"${SERIAL:-}\","
    echo "  \"device_state\": \"${DEVICE_STATE:-none}\","
    echo "  \"app_installed\": $APP_INSTALLED,"
    echo "  \"helixqa_bin\": \"${QA_BIN:-}\","
    echo "  \"verdict\": \"$verdict\","
    echo "  \"detail\": \"$detail\""
    echo "}"
  } > "$PREFLIGHT"
}

# Defaults so write_preflight never references an unset var under `set -u`.
ADB_PRESENT=false; SERIAL=""; DEVICE_STATE="none"; APP_INSTALLED=false; QA_BIN=""

precondition_unmet() { # precondition_unmet <message>
  echo "PRECONDITION_UNMET: $1" >&2
  write_preflight "PRECONDITION_UNMET" "$1"
  echo "=== Dynamic Provider Discovery: PRECONDITION_UNMET (exit 3) — NOT a PASS ==="
  echo "  preflight evidence: $PREFLIGHT"
  exit 3
}

# ----------------------------------------------------------------------------
# Precondition 1 — adb on PATH.
# ----------------------------------------------------------------------------
if ! command -v adb >/dev/null 2>&1; then
  precondition_unmet "adb not on PATH — install Android platform-tools. The QA session needs adb to drive the device."
fi
ADB_PRESENT=true

# ----------------------------------------------------------------------------
# Precondition 2 — a connected, booted device/emulator. Resolve the serial.
# (§6.AH: boot the VM/container emulator via scripts/run-genymotion-challenges.sh
#  --start or the §6.X matrix first; this scenario consumes its serial.)
# ----------------------------------------------------------------------------
SERIAL="${LAVA_DEVICE_SERIAL:-${ANDROID_SERIAL:-}}"
if [[ -z "$SERIAL" ]]; then
  # First device in `device` state (skip the header + offline/unauthorized rows).
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
if [[ -z "$SERIAL" ]]; then
  adb devices > "$EVIDENCE_DIR/adb-devices.txt" 2>&1 || true
  precondition_unmet "no Android device in 'device' state. Boot a VM/container emulator (scripts/run-genymotion-challenges.sh --start --device <name>) per §6.AG/§6.AH, then re-run. See adb-devices.txt."
fi
DEVICE_STATE="$(adb -s "$SERIAL" get-state 2>/dev/null || echo unknown)"
if [[ "$DEVICE_STATE" != "device" ]]; then
  precondition_unmet "device '$SERIAL' is in state '$DEVICE_STATE', not 'device' (offline/unauthorized). Cannot drive the QA session honestly."
fi

# Capture device identity as forensic evidence (§11.4.69).
{
  echo "serial=$SERIAL"
  echo "model=$(adb -s "$SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  echo "android_release=$(adb -s "$SERIAL" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  echo "sdk=$(adb -s "$SERIAL" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
  echo "abi=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
  echo "characteristics=$(adb -s "$SERIAL" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r')"
  echo "qemu=$(adb -s "$SERIAL" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
} > "$EVIDENCE_DIR/device-identity.txt"
echo "  device:      $SERIAL ($(awk -F= '/^model=/{print $2}' "$EVIDENCE_DIR/device-identity.txt"))"

# ----------------------------------------------------------------------------
# Precondition 3 — the Lava client is installed on the device.
# ----------------------------------------------------------------------------
if adb -s "$SERIAL" shell pm path "$PKG" >/dev/null 2>&1; then
  APP_INSTALLED=true
else
  precondition_unmet "package '$PKG' not installed on $SERIAL. Build + install the debug APK (./gradlew :app:installDebug, or the matrix install step) before the QA session."
fi

# ----------------------------------------------------------------------------
# Precondition 4 — the HelixQA engine binary (vision-guided navigator).
# Resolved dynamically (no hardcoded arch/path) like the upstream challenges.
# ----------------------------------------------------------------------------
QA_BIN="${HELIXQA_BIN:-}"
if [[ -z "$QA_BIN" ]]; then
  for cand in \
    "$REPO_ROOT/submodules/helixqa/bin/helixqa" \
    "$REPO_ROOT/submodules/helixqa/helixqa"; do
    if [[ -x "$cand" ]]; then QA_BIN="$cand"; break; fi
  done
fi
if [[ -z "$QA_BIN" || ! -x "$QA_BIN" ]]; then
  precondition_unmet "HelixQA binary not found/executable (looked for HELIXQA_BIN, submodules/helixqa/bin/helixqa, submodules/helixqa/helixqa). Build it: 'cd submodules/helixqa && make build'. The vision-guided session needs it; refusing to fake a PASS without it."
fi
# A binary that cannot even print its version is not a usable engine.
if ! run_capped 30 "$QA_BIN" version >/dev/null 2>&1; then
  precondition_unmet "HelixQA binary '$QA_BIN' present but 'helixqa version' failed (likely wrong arch for this host). Rebuild for this platform."
fi

# All preconditions met.
write_preflight "PRECONDITIONS_MET" "adb+device+app+helixqa all present; proceeding to QA session"
echo "  preconditions: MET (adb + device '$SERIAL' + '$PKG' installed + helixqa engine)"

# ----------------------------------------------------------------------------
# Drive the QA session.
# ----------------------------------------------------------------------------
QA_OUT="$EVIDENCE_DIR/qa-session"
RUN_LOG="$EVIDENCE_DIR/helixqa-run.log"
mkdir -p "$QA_OUT"

# Cold-start hygiene so the session begins from onboarding, not a warm screen.
adb -s "$SERIAL" shell pm clear "$PKG" >/dev/null 2>&1 || true
adb -s "$SERIAL" logcat -c >/dev/null 2>&1 || true

echo "==> running HelixQA vision-guided session against $SERIAL"
RC=0
run_capped "$RUN_TIMEOUT_SEC" "$QA_BIN" run \
  -platform android \
  -device "$SERIAL" \
  -package "$PKG" \
  -banks "$BANK" \
  -output "$QA_OUT" \
  -report json,markdown \
  -record \
  -validate \
  -timeout "${RUN_TIMEOUT_SEC}s" \
  >"$RUN_LOG" 2>&1 || RC=$?

# Supplementary captured evidence (independent of the engine's own report).
adb -s "$SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/screen-final.png" 2>/dev/null || true
adb -s "$SERIAL" exec-out uiautomator dump /dev/tty 2>/dev/null > "$EVIDENCE_DIR/uiauto-final.xml" || true
adb -s "$SERIAL" logcat -d -b crash 2>/dev/null | grep -F "$PKG" > "$EVIDENCE_DIR/logcat-fatal.txt" || true

# ----------------------------------------------------------------------------
# Classify the verdict honestly from the process contract + produced artifacts.
# We use the engine's EXIT CODE + the presence of a non-empty report as the
# evidence — NOT a guessed JSON schema (§11.4.6 no-guessing).
# ----------------------------------------------------------------------------
REPORT_PRESENT=false
if find "$QA_OUT" -type f \( -name '*.json' -o -name '*.md' -o -name '*.html' \) 2>/dev/null | grep -q .; then
  REPORT_PRESENT=true
fi
CRASH_LEAK=false
if grep -qE 'panic:|goroutine [0-9]+ \[running\]:|runtime error:|fatal error:' "$RUN_LOG" 2>/dev/null; then
  CRASH_LEAK=true
fi
FATAL_IN_APP=false
if [[ -s "$EVIDENCE_DIR/logcat-fatal.txt" ]]; then FATAL_IN_APP=true; fi

write_verdict() { # write_verdict <verdict> <detail>
  {
    echo "{"
    echo "  \"scenario\": \"dynamic-provider-discovery\","
    echo "  \"timestamp\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"device_serial\": \"$SERIAL\","
    echo "  \"package\": \"$PKG\","
    echo "  \"bank\": \"$BANK\","
    echo "  \"helixqa_exit_code\": $RC,"
    echo "  \"report_present\": $REPORT_PRESENT,"
    echo "  \"engine_crash_leak\": $CRASH_LEAK,"
    echo "  \"app_fatal_in_logcat\": $FATAL_IN_APP,"
    echo "  \"verdict\": \"$1\","
    echo "  \"detail\": \"$2\","
    echo "  \"evidence\": {"
    echo "    \"run_log\": \"helixqa-run.log\","
    echo "    \"qa_session_dir\": \"qa-session/\","
    echo "    \"screen\": \"screen-final.png\","
    echo "    \"ui_hierarchy\": \"uiauto-final.xml\","
    echo "    \"logcat_crash\": \"logcat-fatal.txt\","
    echo "    \"device_identity\": \"device-identity.txt\""
    echo "  }"
    echo "}"
  } > "$EVIDENCE_DIR/verdict.json"
}

# ERROR (4): the engine itself crashed or produced no report — the session was
# not actually evaluated, so neither PASS nor FAIL can be claimed honestly.
if [[ "$CRASH_LEAK" == "true" ]] || [[ "$REPORT_PRESENT" != "true" ]]; then
  write_verdict "ERROR" "helixqa session did not produce an evaluable report (exit=$RC, report_present=$REPORT_PRESENT, crash_leak=$CRASH_LEAK)"
  echo "=== Dynamic Provider Discovery: ERROR (exit 4) — session not evaluable ==="
  echo "  verdict: $EVIDENCE_DIR/verdict.json"
  exit 4
fi

# FAIL (1): the engine ran the bank and reported a failure (non-zero exit), OR a
# FATAL for the app appeared in logcat — a real user-visible defect.
if [[ "$RC" -ne 0 ]] || [[ "$FATAL_IN_APP" == "true" ]]; then
  write_verdict "FAIL" "QA session reported a failure (helixqa exit=$RC, app_fatal=$FATAL_IN_APP). The dynamic-provider flow is broken for the user."
  echo "=== Dynamic Provider Discovery: FAIL (exit 1) — real defect ==="
  echo "  verdict: $EVIDENCE_DIR/verdict.json"
  exit 1
fi

# PASS (0): engine exited 0, produced a report, no crash, no app FATAL. A real
# user can complete launch -> onboarding -> API choice -> API-backed provider
# list -> pick provider -> search -> results on this device.
write_verdict "PASS" "QA session completed the dynamic-provider flow end-to-end with positive evidence."
echo "=== Dynamic Provider Discovery: PASS (exit 0) ==="
echo "  verdict:  $EVIDENCE_DIR/verdict.json"
echo "  evidence: $QA_OUT (helixqa report + recording), screen-final.png, uiauto-final.xml"
exit 0
