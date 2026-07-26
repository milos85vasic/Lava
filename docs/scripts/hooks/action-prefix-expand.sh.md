# `scripts/hooks/action-prefix-expand.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.140 (action-prefix mandate), §11.4.109 (anti-forgetting), §11.4.35 (canonical root), §11.4.28 (decoupling), §11.4.18 (script docs); Lava §6.AI / §6.AJ
**Classification:** project-specific (the `.claude/` wiring is Lava's; the expander it delegates to is universal, inherited from the constitution submodule)

## Overview

A Claude Code `UserPromptSubmit` hook — LAYER 2 of the universal
`ACTION_NAME ::` prompt-prefix system. It is **thin Lava-side glue**: it does
NOT reimplement the expander, it delegates to the canonical expander shipped by
the pinned constitution submodule at
`constitution/scripts/hooks/action_prefix_expand.sh`, which reads the action
registry at `constitution/actions/registry.yaml` (or the
`$HELIX_ACTION_REGISTRY` override) and emits the `additionalContext` JSON for a
registered action prefix.

Per the Decoupled Reusable Architecture rule this wrapper is the Lava-domain
glue tying the submodule into Lava's `.claude/` wiring; per §6.AD the submodule
is pinned and its tracked files are never edited Lava-side.

## Contract (Claude Code UserPromptSubmit hook)

- Receives the event JSON on **stdin** (the user's prompt at `.prompt`).
- **Exit 0 + JSON body**
  `{"hookSpecificOutput":{"hookEventName":"UserPromptSubmit","additionalContext":"<text>"}}`
  → adds `<text>` to the conversation context (the action expansion).
- **Exit 0 + EMPTY stdout** → no-op (prompt passes through unchanged).
- The delegate **fails open**: any internal error degrades to pass-through,
  never blocks the prompt. This wrapper preserves that — a missing or
  non-executable delegate (e.g. submodule not checked out) is a clean pass-
  through with a one-line stderr note.

## Behaviour (delegated verbatim to the constitution expander)

| Input shape | Result |
|---|---|
| Registered action (`BACKGROUND :: x`, `/BACKGROUND x`, namespaced forms) | Inject the registered expansion + rules |
| Unknown grammar-shaped token | Inject a §11.4.66/§11.4.105 clarify note (never invent an expansion, §11.4.6) |
| Normal prompt / escaped `\PREFIX` | Empty stdout (no-op) |

## Inputs

- `stdin` — UserPromptSubmit event JSON.
- `CLAUDE_PROJECT_DIR` (set by Claude Code) — repo root; the constitution
  submodule lives at `$CLAUDE_PROJECT_DIR/constitution/`.
- `HELIX_ACTION_REGISTRY` (optional) — registry path override, passed through.
- `LAVA_ACTION_PREFIX_EXPANDER` (optional) — delegate path override, used by the
  hermetic test to point at the canonical expander without relying on
  `CLAUDE_PROJECT_DIR`.

Delegate resolution order: `LAVA_ACTION_PREFIX_EXPANDER` →
`CLAUDE_PROJECT_DIR` → this script's own location
(`scripts/hooks/` → repo root → `constitution/`).

## Side effects

None. Read-only (reads the registry, reads stdin). Fail-open by design.

## Usage / manual testing

```bash
# Registered action → additionalContext JSON on stdout
printf '%s' '{"prompt":"BACKGROUND :: run the bluff hunt"}' \
  | bash scripts/hooks/action-prefix-expand.sh

# Normal prompt → empty stdout (pass-through)
printf '%s' '{"prompt":"hello"}' \
  | bash scripts/hooks/action-prefix-expand.sh
```

## Dependencies

`constitution/scripts/hooks/action_prefix_expand.sh` (+ its sibling
`constitution/scripts/action_prefix_lib.sh`). `jq` preferred, `awk` fallback
(the delegate handles both).

## Cross-references

- Lava `CLAUDE.md` §6.AI (HelixConstitution §11.4.128–141 adoption), §6.AI-debt
  item (1) (this wiring), §6.AJ (LAYER-1 recognition text)
- HelixConstitution §11.4.140 (action-prefix mandate), §11.4.109
  (anti-forgetting), §11.4.35 (canonical root), §11.4.28 (decoupling)
- `docs/scripts/hooks/guard-forbidden-commands.sh.md` — the sibling
  `PreToolUse` hook (the other half of the mechanical-guard floor)
