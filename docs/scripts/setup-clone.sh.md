# `scripts/setup-clone.sh` — User Guide

**Last verified:** 2026-05-14 (1.2.23 cycle)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + Lava §6.AD (HelixConstitution Inheritance) + Lava Local-Only CI/CD rule

## Overview

Run-once-per-clone setup script. Wires the pre-push hook (`core.hooksPath = .githooks`), verifies key scripts + submodule structure + mirror remotes per §6.W. Idempotent — safe to re-run.

## Forensic motivation

During the 1.2.23 cycle (2026-05-14), the developer's clone of Lava had `core.hooksPath` UNSET. The `.githooks/pre-push` Layer 1 (anti-bluff checks) + Layer 2 (`scripts/ci.sh --changed-only`) gates were silently bypassed for the entire session. Multiple commits landed on master without ever passing through the gate; the discovery came when Layer 2's Spotless drift was eventually surfaced by an explicit hook self-test. This script exists so the next clone never repeats that failure mode.

## Usage

```bash
bash scripts/setup-clone.sh
```

## What it does

1. Sets `core.hooksPath = .githooks` (so `git push` invokes `.githooks/pre-push`)
2. Verifies the pre-push hook exists + is executable
3. Verifies `scripts/commit_all.sh`, `scripts/check-constitution.sh`, `scripts/inject-helix-inheritance-block.sh` exist + are executable
4. Verifies all submodules are initialized (suggests `git submodule update --init --recursive` if any are missing)
5. Verifies the `constitution/` submodule's expected files (CLAUDE.md, AGENTS.md, Constitution.md)
6. Verifies the parent repo has both `github` + `gitlab` named remotes per §6.W

## Output

One line per check, ✓ or ✗ or ⚠ prefix. Non-fatal warnings (missing remote, uninitialized submodule) suggest the remediation command. Fatal errors (missing hook, missing core script) exit 1.

## Side-effects

- Modifies `.git/config` to set `core.hooksPath` (one-time per clone)
- Otherwise read-only: does NOT initialize submodules, does NOT add remotes (the operator must do those explicitly with the suggested commands)

## When to run

- Right after `git clone` of a fresh clone
- After deleting + re-creating `.git/config` (e.g. after `git init` in an empty workspace)
- Whenever `bash scripts/setup-clone.sh` is convenient to verify the local setup is current

## Cross-references

- Lava `CLAUDE.md` Local-Only CI/CD section (the pre-push hook IS the local CI gate)
- Lava `CLAUDE.md` §6.W (GitHub + GitLab only mirror policy)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
- `docs/scripts/commit_all.sh.md` (the wrapper that depends on the hook being wired)
- `.githooks/pre-push` (the hook itself)
