# QWEN.md — Qwen Code context for the Lava project

This file is read by Qwen Code as its project-context file. It is the Qwen Code
counterpart of CLAUDE.md (Claude Code) and AGENTS.md (generic agents). It is a
pointer: this repository keeps exactly one canonical agent-instruction file.

## Read CLAUDE.md — it is mandatory

Lava's canonical agent-instruction file is CLAUDE.md at the repository root.
Before doing any work in this repository, open and read CLAUDE.md in full. It
carries the complete project guidance — build system, module architecture, the
Anti-Bluff Testing Pact (sections 6.A through 6.AC plus the standing section
6.L mandate), the Local-Only CI/CD constraint, the Decoupled Reusable
Architecture rule, the section 6.W GitHub-and-GitLab-only remote rule, the
section 6.U no-sudo rule, and the Host Machine Stability Directive. Every rule
there binds Qwen Code exactly as it binds Claude Code.

This file is a plain-text pointer and deliberately uses no auto-import
directive. Qwen Code's memory-import processor resolves import-prefixed tokens
recursively, and the instruction files reference many tokens that are not files
(framework annotations, the constitution import line). To stay compatible with
Qwen Code this file contains no such tokens — read CLAUDE.md directly.

## INHERITED FROM constitution/CLAUDE.md

CLAUDE.md inherits, unconditionally, every rule in constitution/CLAUDE.md and
the constitution/Constitution.md it references — the HelixConstitution submodule
mounted at the constitution/ directory, the canonical root of the Constitution,
CLAUDE.md, AGENTS.md, and QWEN.md rule set. Qwen Code MUST NOT weaken any
inherited rule; project rules only ever extend them.

## Anti-Bluff — the one rule to internalize first

Tests and Challenges in this project exist for exactly one purpose: to confirm
a feature genuinely works for a real end user, end-to-end. A test that passes
while the feature is broken is a bluff test and is forbidden. CI green is
necessary, never sufficient. See CLAUDE.md sections 6.J and 6.L and the Sixth
and Seventh Laws before writing or running any test.

## Code intelligence — codegraph

This repository is indexed by codegraph (see docs/CODEGRAPH.md). Qwen Code has
the codegraph MCP server wired via .qwen/settings.json — prefer its
codegraph_search and codegraph_context tools over blind file scanning.

## Universal Action-Prefix Recognition (LAYER 1, §11.4.140)

When a user prompt's FIRST non-blank line starts with an uppercase action token
followed by `::` (grammar `^([A-Z][A-Z0-9_]*)\s*::\s`, or the namespaced
`PREFIX::ACTION ::` form): look the token up in `constitution/actions/registry.yaml`
(or `$HELIX_ACTION_REGISTRY`); if registered, REPLACE the `ACTION_NAME ::` prefix
with that action's `expansion` text, apply its `rules`, and execute the REMAINDER
of the prompt under the expanded instruction (expand-then-rescan). Built-in:
`BACKGROUND ::` → run the rest in the background, in parallel with all main work
streams, subagent-driven, with rock-solid anti-bluff captured evidence. Unknown
tokens are not actions — execute verbatim. The registry is the single source of
truth (add actions as rows, not by editing this block). See root `CLAUDE.md`
§6.AJ. Classification: universal.
