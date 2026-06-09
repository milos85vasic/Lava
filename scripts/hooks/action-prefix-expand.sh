#!/usr/bin/env bash
# scripts/hooks/action-prefix-expand.sh
#
# Claude Code UserPromptSubmit hook — LAYER 2 of the universal "ACTION_NAME ::"
# prompt-prefix system (HelixConstitution §11.4.140 / Lava §6.AI + §6.AJ).
#
# ── PURPOSE ──────────────────────────────────────────────────────────────────
# Thin Lava-side glue that delegates to the CANONICAL action-prefix expander
# shipped by the pinned constitution submodule at
#   constitution/scripts/hooks/action_prefix_expand.sh
# which reads the action registry at constitution/actions/registry.yaml (or the
# $HELIX_ACTION_REGISTRY override) and emits the UserPromptSubmit
# additionalContext JSON for a registered action prefix. We do NOT reimplement
# the expander — per §6.AD inheritance the constitution submodule is the source
# of truth; per the Decoupled Reusable Architecture rule this wrapper is the
# Lava-domain thin glue tying the submodule into Lava's .claude/ wiring. Per
# §6.AD the submodule is pinned + its tracked files are never edited Lava-side.
#
# ── CONTRACT (Claude Code UserPromptSubmit hook) ─────────────────────────────
#   - Receives the event JSON on stdin (the user's prompt at .prompt).
#   - Exit 0 + JSON body
#       {"hookSpecificOutput":{"hookEventName":"UserPromptSubmit",
#                              "additionalContext":"<text>"}}
#     ADDS <text> to the conversation context (the action expansion).
#   - Exit 0 + EMPTY stdout → no-op (prompt passes through unchanged).
#   - The delegate FAILS-OPEN: any internal error degrades to pass-through, never
#     blocks the prompt. This wrapper preserves that (missing delegate → no-op).
#
# ── BEHAVIOUR (delegated verbatim to the constitution expander) ──────────────
#   - registered action ("BACKGROUND :: x", "/BACKGROUND x", namespaced forms)
#                                 → inject the registered expansion + rules.
#   - unknown grammar-shaped token → inject a §11.4.66/§11.4.105 clarify note
#                                    (never invent an expansion, §11.4.6).
#   - normal prompt / escaped \PREFIX → empty stdout (no-op).
#
# ── INPUTS ───────────────────────────────────────────────────────────────────
#   stdin  : UserPromptSubmit event JSON
#   env CLAUDE_PROJECT_DIR (set by Claude Code) : repo root; the constitution
#            submodule lives at $CLAUDE_PROJECT_DIR/constitution/.
#   env HELIX_ACTION_REGISTRY (optional) : registry path override (passed through).
#   env LAVA_ACTION_PREFIX_EXPANDER (optional) : delegate path override, used by
#            the hermetic test to point at the canonical expander without relying
#            on CLAUDE_PROJECT_DIR.
#
# ── OUTPUTS ──────────────────────────────────────────────────────────────────
#   stdout : the delegate's hookSpecificOutput JSON, or empty (no-op).
#
# ── SIDE EFFECTS ─────────────────────────────────────────────────────────────
#   None. Read-only (reads the registry, reads stdin). Fail-open by design.
#
# ── DEPENDENCIES ─────────────────────────────────────────────────────────────
#   constitution/scripts/hooks/action_prefix_expand.sh (+ its sibling
#   constitution/scripts/action_prefix_lib.sh). jq preferred, awk fallback (the
#   delegate handles both).
#
# ── CROSS-REFERENCES ─────────────────────────────────────────────────────────
#   Lava §6.AI (HelixConstitution §11.4.128–141 adoption), §6.AI-debt item (1)
#   (this wiring), §6.AJ (LAYER-1 recognition text). HelixConstitution §11.4.140
#   (action-prefix mandate), §11.4.109 (anti-forgetting), §11.4.35 (canonical
#   root), §11.4.28 (decoupling — referenced, never copied).
#
# Classification: project-specific (the .claude/ wiring is Lava's; the expander
# it delegates to is universal, inherited from the constitution submodule).

set -euo pipefail

# Resolve the canonical constitution expander. Prefer an explicit override (used
# by the hermetic test), then CLAUDE_PROJECT_DIR (set by Claude Code at runtime),
# then this script's own location (scripts/hooks/ → repo root → constitution/).
apx_resolve_delegate() {
  if [ -n "${LAVA_ACTION_PREFIX_EXPANDER:-}" ]; then
    printf '%s' "$LAVA_ACTION_PREFIX_EXPANDER"
    return 0
  fi
  if [ -n "${CLAUDE_PROJECT_DIR:-}" ]; then
    printf '%s' "${CLAUDE_PROJECT_DIR%/}/constitution/scripts/hooks/action_prefix_expand.sh"
    return 0
  fi
  local here repo_root
  here="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" >/dev/null 2>&1 && pwd)"
  repo_root="$(cd "$here/../.." >/dev/null 2>&1 && pwd)"
  printf '%s' "$repo_root/constitution/scripts/hooks/action_prefix_expand.sh"
}

apx_wrapper_main() {
  local delegate
  delegate="$(apx_resolve_delegate)"
  if [ ! -x "$delegate" ]; then
    # Fail-open: the prefix system must never block a prompt. A missing/
    # non-executable delegate (e.g. submodule not checked out) degrades to a
    # clean pass-through with a one-line stderr note for the operator.
    echo "action-prefix-expand: delegate not found/executable: $delegate (pass-through)" >&2
    return 0
  fi
  # Stream stdin straight through to the canonical expander; its stdout (the
  # hookSpecificOutput JSON, or empty for a no-op) is our stdout verbatim.
  exec "$delegate"
}

apx_wrapper_main
