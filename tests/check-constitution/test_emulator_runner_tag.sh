#!/usr/bin/env bash
# tests/check-constitution/test_emulator_runner_tag.sh
#
# Paired falsifiability proof (§1.1 / §6.A discipline) for the §6.X
# emulator-runner-tag gate implemented in scripts/check-emulator-runner-tag.sh.
#
# Mutation rehearsal:
#   - A fixture evidence file that records emulator execution but carries a
#     non-containers (or absent) runner tag → the gate MUST FAIL (exit 1).
#   - The same file with `runner: containers-submodule` → the gate MUST PASS.
# If the gate passed on the non-containers fixture, it would be a bluff by
# construction; this test makes that impossible to ship green.
#
# The gate's hermetic-test hook is LAVA_EMULATOR_EVIDENCE_FILES — a newline-
# separated explicit file list that bypasses git-new-file detection so we can
# drive synthetic fixtures deterministically.
#
# Classification: universal

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-emulator-runner-tag.sh"

fail_count=0

# run_on <file> -> echoes exit code of the scanner scoped to just <file>
run_on() {
  local file="$1"
  LAVA_EMULATOR_EVIDENCE_FILES="$file" bash "$SCANNER" >/dev/null 2>&1
  echo $?
}

# Test 1: emulator-execution evidence with NO runner tag → FAIL (the mutation).
test_no_runner_tag_fails() {
  local f; f=$(mktemp)
  cat > "$f" <<'EOF'
{
  "all_passed": true,
  "rows": [
    { "avd": "Pixel_8", "api_level": 35, "boot_seconds": 12.3,
      "test_class": "lava.app.challenges.Challenge00CrashSurvivalTest",
      "test_passed": true,
      "diag": { "adb_devices_state": "emulator-5556 device" } }
  ]
}
EOF
  local rc; rc=$(run_on "$f")
  if [[ "$rc" -eq 1 ]]; then
    echo "PASS test_no_runner_tag_fails (gate FAILED on missing runner tag, exit=$rc)"
  else
    echo "FAIL test_no_runner_tag_fails: expected exit 1, got $rc"
    fail_count=$((fail_count + 1))
  fi
  rm -f "$f"
}

# Test 2: emulator-execution evidence with a host-direct runner tag → FAIL.
test_host_direct_runner_fails() {
  local f; f=$(mktemp)
  cat > "$f" <<'EOF'
{
  "runner": "host-direct",
  "rows": [
    { "avd": "Pixel_8", "boot_seconds": 12.3, "test_passed": true,
      "diag": { "adb_devices_state": "emulator-5556 device" } }
  ]
}
EOF
  local rc; rc=$(run_on "$f")
  if [[ "$rc" -eq 1 ]]; then
    echo "PASS test_host_direct_runner_fails (gate FAILED on host-direct runner, exit=$rc)"
  else
    echo "FAIL test_host_direct_runner_fails: expected exit 1, got $rc"
    fail_count=$((fail_count + 1))
  fi
  rm -f "$f"
}

# Test 3: same evidence WITH `runner: containers-submodule` → PASS (the revert).
test_containers_runner_passes() {
  local f; f=$(mktemp)
  cat > "$f" <<'EOF'
{
  "runner": "containers-submodule",
  "runtime": "podman",
  "rows": [
    { "avd": "Pixel_8", "api_level": 35, "boot_seconds": 12.3,
      "test_class": "lava.app.challenges.Challenge00CrashSurvivalTest",
      "test_passed": true,
      "diag": { "adb_devices_state": "emulator-5556 device" } }
  ]
}
EOF
  local rc; rc=$(run_on "$f")
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS test_containers_runner_passes (gate PASSED with containers-submodule runner, exit=$rc)"
  else
    echo "FAIL test_containers_runner_passes: expected exit 0, got $rc"
    fail_count=$((fail_count + 1))
  fi
  rm -f "$f"
}

# Test 4: runner=containers-submodule (equals form, no quotes) → PASS.
test_equals_form_passes() {
  local f; f=$(mktemp)
  cat > "$f" <<'EOF'
runner=containers-submodule
AVD Pixel_8 boot_seconds=12.3 connectedAndroidTest BUILD SUCCESSFUL
EOF
  local rc; rc=$(run_on "$f")
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS test_equals_form_passes (gate PASSED with runner=containers-submodule, exit=$rc)"
  else
    echo "FAIL test_equals_form_passes: expected exit 0, got $rc"
    fail_count=$((fail_count + 1))
  fi
  rm -f "$f"
}

# Test 5: a NON-emulator evidence file (no markers) → PASS (no false positive).
test_non_emulator_file_passes() {
  local f; f=$(mktemp)
  cat > "$f" <<'EOF'
{ "kind": "host-stability-incident", "class": "III",
  "note": "uptime continuous; no logind transitions." }
EOF
  local rc; rc=$(run_on "$f")
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS test_non_emulator_file_passes (no markers → not flagged, exit=$rc)"
  else
    echo "FAIL test_non_emulator_file_passes: expected exit 0, got $rc"
    fail_count=$((fail_count + 1))
  fi
  rm -f "$f"
}

# Test 6: advisory mode downgrades the FAIL to exit 0.
test_advisory_mode_passes() {
  local f; f=$(mktemp)
  cat > "$f" <<'EOF'
{ "rows": [ { "boot_seconds": 12.3, "diag": { "adb_devices_state": "emulator-5556 device" } } ] }
EOF
  LAVA_EMULATOR_EVIDENCE_FILES="$f" LAVA_EMULATOR_RUNNER_STRICT=0 bash "$SCANNER" >/dev/null 2>&1
  local rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS test_advisory_mode_passes (advisory mode → exit=$rc despite missing tag)"
  else
    echo "FAIL test_advisory_mode_passes: expected exit 0 in advisory mode, got $rc"
    fail_count=$((fail_count + 1))
  fi
  rm -f "$f"
}

# Test 7: real Lava tree (going-forward scope, no injection) → PASS.
test_real_tree_passes() {
  cd "$REPO_ROOT"
  bash "$SCANNER" >/dev/null 2>&1
  local rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS test_real_tree_passes (real tree exit=$rc)"
  else
    echo "FAIL test_real_tree_passes: expected exit 0, got $rc"
    fail_count=$((fail_count + 1))
  fi
}

echo "=== check-emulator-runner-tag.sh hermetic test ==="
test_no_runner_tag_fails
test_host_direct_runner_fails
test_containers_runner_passes
test_equals_form_passes
test_non_emulator_file_passes
test_advisory_mode_passes
test_real_tree_passes

if [[ "$fail_count" -gt 0 ]]; then
  echo "=== $fail_count test(s) failed ==="
  exit 1
fi
echo "all emulator-runner-tag tests passed"
