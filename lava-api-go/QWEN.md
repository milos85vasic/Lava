# QWEN.md — Qwen Code context for this module

This file is read by Qwen Code as its module-context file. It is the Qwen Code
counterpart of CLAUDE.md and AGENTS.md for this module, and it is a pointer:
there is one canonical agent-instruction file per scope.

## Read CLAUDE.md — it is mandatory

This module's canonical agent-instruction file is CLAUDE.md in this directory.
Before doing any work in this module, open and read CLAUDE.md and this module's
CONSTITUTION.md in full. Every rule there binds Qwen Code exactly as it binds
Claude Code.

This file is a plain-text pointer and deliberately uses no auto-import
directive. Qwen Code's memory-import processor resolves import-prefixed tokens
recursively, and the instruction files reference tokens that are not files. To
stay compatible with Qwen Code this file contains no such tokens — read
CLAUDE.md directly.

## INHERITED FROM constitution/CLAUDE.md

This module's CLAUDE.md inherits, unconditionally, every rule in
constitution/CLAUDE.md and the constitution/Constitution.md it references — the
HelixConstitution submodule mounted at the parent project's constitution/
directory (resolve the path with constitution/find_constitution.sh from the
parent project root). Qwen Code MUST NOT weaken any inherited rule.

## Anti-Bluff — read first

Tests and Challenges exist for exactly one purpose: to confirm a feature
genuinely works for a real end user, end-to-end. A test that passes while the
feature is broken is a bluff test and is forbidden. CI green is necessary,
never sufficient. See this module's CLAUDE.md, AGENTS.md, and CONSTITUTION.md
for the full Sixth/Seventh Law and section 6.J / 6.L mandate.

## §11.4.78 — CodeGraph code-intelligence mandate

Inherited by §11.4.78 ID reference from `constitution/Constitution.md` §11.4.78 (this module's `CLAUDE.md` and `CONSTITUTION.md` carry the full anchor with the package name and install commands). In brief: every project worked on by AI coding agents MUST install, initialize, and use CodeGraph — a local semantic code-knowledge-graph exposed to agents over MCP — wired into every CLI agent the developers use, covered by an anti-bluff verification suite. See this module's `CLAUDE.md` and `CONSTITUTION.md`, and the constitution submodule `Constitution.md` §11.4.78, for the full mandate.
