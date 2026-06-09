#!/usr/bin/env bash
# tests/hooks/action_prefix_expand_test.sh
#
# Hermetic test for scripts/hooks/action-prefix-expand.sh — the Lava-side
# UserPromptSubmit glue that delegates to the constitution submodule's canonical
# action-prefix expander (HelixConstitution §11.4.140 / Lava §6.AI item 1 + §6.AJ).
#
# Each case feeds a synthetic UserPromptSubmit JSON payload on stdin and asserts
# on the hook's stdout + exit code:
#   - registered action ("BACKGROUND :: x", "/BACKGROUND x") → exit 0, stdout
#     contains the registered expansion (the §11.4.140 BACKGROUND text).
#   - unknown grammar-shaped token ("UNKNOWNTOKEN :: y") → exit 0, stdout is the
#     §11.4.66/§11.4.105 clarify note, NEVER an invented expansion.
#   - normal prompt → exit 0, EMPTY stdout (pass-through, no change).
#   - escaped "\BACKGROUND :: x" → exit 0, EMPTY stdout (literal, no expansion).
#
# A hook that expanded a normal prompt, or invented an expansion for an unknown
# token, or dropped the registered expansion, would be a bluff by construction;
# this test makes those bluffs impossible to ship green. It runs hermetically:
# CLAUDE_PROJECT_DIR is pointed at the repo root so the wrapper resolves the
# canonical constitution delegate exactly as Claude Code would at runtime.
#
# Classification: project-specific (the wrapper + .claude wiring are Lava's; the
# expander it proves is universal, inherited from the constitution submodule).

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HOOK="$REPO_ROOT/scripts/hooks/action-prefix-expand.sh"
EXPANSION_MARKER="MUST BE executed in background in parallel with all main work streams"

fail_count=0
pass_count=0

# run_hook <prompt-text> → echoes stdout; sets global RC to the exit code.
# Builds a UserPromptSubmit JSON payload with the prompt embedded as a JSON
# string (escaping backslash + double-quote so arbitrary text is valid JSON).
RC=0
run_hook() {
  local prompt="$1"
  local esc="${prompt//\\/\\\\}"
  esc="${esc//\"/\\\"}"
  local payload="{\"prompt\":\"${esc}\"}"
  local out
  out="$(printf '%s' "$payload" | CLAUDE_PROJECT_DIR="$REPO_ROOT" "$HOOK" 2>/dev/null)"
  RC=$?
  printf '%s' "$out"
}

# assert_expansion <name> <prompt> — expect exit 0 + expansion marker in stdout.
assert_expansion() {
  local name="$1" prompt="$2" out
  out="$(run_hook "$prompt")"
  if [ "$RC" -eq 0 ] && printf '%s' "$out" | grep -qF "$EXPANSION_MARKER"; then
    echo "PASS  $name"
    pass_count=$((pass_count + 1))
  else
    echo "FAIL  $name (rc=$RC; stdout=$out)"
    fail_count=$((fail_count + 1))
  fi
}

# assert_clarify <name> <prompt> — expect exit 0 + clarify note + NO expansion.
assert_clarify() {
  local name="$1" prompt="$2" out
  out="$(run_hook "$prompt")"
  if [ "$RC" -eq 0 ] \
    && printf '%s' "$out" | grep -qF "NOT a registered action" \
    && ! printf '%s' "$out" | grep -qF "$EXPANSION_MARKER"; then
    echo "PASS  $name"
    pass_count=$((pass_count + 1))
  else
    echo "FAIL  $name (rc=$RC; stdout=$out)"
    fail_count=$((fail_count + 1))
  fi
}

# assert_noop <name> <prompt> — expect exit 0 + EMPTY stdout (pass-through).
assert_noop() {
  local name="$1" prompt="$2" out
  out="$(run_hook "$prompt")"
  if [ "$RC" -eq 0 ] && [ -z "$out" ]; then
    echo "PASS  $name"
    pass_count=$((pass_count + 1))
  else
    echo "FAIL  $name (rc=$RC; expected empty stdout, got: $out)"
    fail_count=$((fail_count + 1))
  fi
}

echo "== action-prefix-expand hook hermetic test =="

# Registered action — both grammar forms must expand.
assert_expansion "BACKGROUND_colon_form_expands"  "BACKGROUND :: do the thing"
assert_expansion "BACKGROUND_slash_form_expands"  "/BACKGROUND do the thing"
assert_expansion "BACKGROUND_namespaced_expands"  "DEFAULT::BACKGROUND :: do the thing"

# Unknown grammar-shaped token — clarify, never invent (§11.4.66/§11.4.105/§11.4.6).
assert_clarify   "UNKNOWNTOKEN_asks_does_not_expand" "UNKNOWNTOKEN :: do Y"

# Pass-through cases — empty stdout, no change.
assert_noop      "normal_prompt_passes_through"   "please fix the bug in foo.kt"
assert_noop      "escaped_prefix_passes_through"  "\\BACKGROUND :: literal discussion"
assert_noop      "lowercase_not_an_action"        "background :: not uppercase"
assert_noop      "empty_prompt_noop"              ""

echo
echo "== summary: $pass_count passed, $fail_count failed =="
[ "$fail_count" -eq 0 ]
