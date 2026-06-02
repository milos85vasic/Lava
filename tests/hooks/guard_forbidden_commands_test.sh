#!/usr/bin/env bash
# tests/hooks/guard_forbidden_commands_test.sh
#
# Hermetic test for scripts/hooks/guard-forbidden-commands.sh — the §1.1 paired
# falsifiability proof that the PreToolUse guard ACTUALLY catches the violation
# classes it claims to (and does NOT block legitimate commands).
#
# Each case feeds a synthetic PreToolUse JSON payload on stdin and asserts the
# guard's exit code: 2 = blocked, 0 = allowed (incl. allow-marked warnings).
# A guard that exits 0 on a forbidden command would be a bluff by construction;
# this test makes that bluff impossible to ship green.
#
# Classification: universal

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="$REPO_ROOT/scripts/hooks/guard-forbidden-commands.sh"

fail_count=0
pass_count=0

# assert_exit <expected-exit> <name> <command-string>
# Wraps <command-string> in a PreToolUse Bash payload and runs the guard.
assert_exit() {
  local expected="$1" name="$2" cmd="$3"
  local payload err rc
  # Build the JSON payload with the command embedded as a JSON string. We escape
  # backslash and double-quote so arbitrary command text is well-formed JSON.
  local esc="${cmd//\\/\\\\}"
  esc="${esc//\"/\\\"}"
  payload="{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"${esc}\"}}"
  err="$(printf '%s' "$payload" | bash "$GUARD" 2>&1 >/dev/null)"
  rc=$?
  if [[ "$rc" -eq "$expected" ]]; then
    echo "PASS [$name] exit=$rc"
    pass_count=$((pass_count + 1))
  else
    echo "FAIL [$name] expected exit=$expected got=$rc"
    echo "      stderr: $err"
    fail_count=$((fail_count + 1))
  fi
}

echo "=== guard-forbidden-commands.sh hermetic test ==="

# ---- BLOCKED: emulator / device gate (§6.X / §6.V / §6.AG) ----
assert_exit 2 "raw adb install"                  'adb install app-debug.apk'
assert_exit 2 "adb -s serial install"            'adb -s emulator-5554 install app-debug.apk'
assert_exit 2 "raw emulator -avd"                'emulator -avd Pixel_8'
assert_exit 2 "ANDROID_HOME emulator launch"     '$ANDROID_HOME/emulator/emulator -avd Pixel_8'
assert_exit 2 "am instrument"                    'am instrument -w lava.app.test/androidx.test.runner.AndroidJUnitRunner'

# ---- ALLOWED: the sanctioned gate path + ordinary dev commands ----
assert_exit 0 "challenge matrix runner"          'scripts/run-challenge-matrix.sh --avds Pixel_8 --runner=containerized'
assert_exit 0 "gradlew test"                      './gradlew test'
assert_exit 0 "adb logcat (read-only, not install)" 'adb logcat -d'
assert_exit 0 "git status"                        'git status'

# ---- BLOCKED: force-push / verification-bypass (§6.T.3) ----
assert_exit 2 "git push --force"                  'git push --force origin master'
assert_exit 2 "git push -f"                       'git push -f origin HEAD'
assert_exit 2 "git push --force-with-lease"       'git push --force-with-lease origin master'
assert_exit 2 "git commit --no-verify"            'git commit --no-verify -m "x"'
assert_exit 2 "git commit --no-gpg-sign"          'git commit --no-gpg-sign -m "x"'
assert_exit 0 "ordinary git push"                 'git push origin feat/branch'

# ---- BLOCKED: sudo / su (§6.U) ----
assert_exit 2 "sudo"                              'sudo apt-get install foo'
assert_exit 2 "su -"                              'su - root'
assert_exit 2 "bare su"                           'su'
assert_exit 0 "subl (word containing su)"         'subl somefile.txt'
assert_exit 0 "gradle --summary (contains su)"    './gradlew build --console=plain'

# ---- BLOCKED: host power (Host Machine Stability Directive) ----
assert_exit 2 "systemctl suspend"                 'systemctl suspend'
assert_exit 2 "loginctl hibernate"                'loginctl hibernate'
assert_exit 2 "pm-suspend"                        'pm-suspend'
assert_exit 2 "shutdown now"                      'shutdown -h now'

# ---- ESCAPE HATCH: documented exception downgrades block → warn (exit 0) ----
assert_exit 0 "emulator allow-marked"             'emulator -avd Pixel_8  # guardrails:allow local dev iteration, not gate evidence'
assert_exit 0 "force-push allow-marked"           'git push --force origin master  # guardrails:allow operator approved mirror reconcile 2026-06-02'

# ---- ESCAPE HATCH does NOT apply to host-power (no-override) ----
assert_exit 2 "systemctl suspend allow-marked still blocked" 'systemctl suspend  # guardrails:allow this must NOT work'

# ---- Non-Bash tool passes through untouched ----
NON_BASH='{"tool_name":"Read","tool_input":{"file_path":"/etc/sudoers"}}'
non_bash_rc=0
printf '%s' "$NON_BASH" | bash "$GUARD" >/dev/null 2>&1 || non_bash_rc=$?
if [[ "$non_bash_rc" -eq 0 ]]; then
  echo "PASS [non-Bash tool passes through] exit=0"
  pass_count=$((pass_count + 1))
else
  echo "FAIL [non-Bash tool passes through] expected 0 got $non_bash_rc"
  fail_count=$((fail_count + 1))
fi

echo "=== $pass_count passed, $fail_count failed ==="
if [[ "$fail_count" -gt 0 ]]; then
  exit 1
fi
echo "all guard-forbidden-commands tests passed"
