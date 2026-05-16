# `scripts/commit_all.sh` — User Guide

**Last verified:** 2026-05-14 (1.2.23 cycle)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + §11.4.22 (lightweight commit path) + Lava §6.AD (HelixConstitution Inheritance) + §6.W (GitHub + GitLab only mirror policy)

## Overview

Project-official commit + multi-mirror-push wrapper for the Lava parent repository. HelixConstitution mandates a project commit wrapper (CLAUDE.md "MANDATORY COMMIT & PUSH CONSTRAINTS"); Lava's wrapper is scoped to the §6.W 2-mirror set (GitHub + GitLab) and refuses destructive operations without explicit per-operation operator authorization per §9.

This is the **lightweight path** for routine commits. The wrapper:

1. Stages files (all-tracked or explicit `--files` list)
2. Commits with the supplied message
3. Pushes to every configured GitHub + GitLab named remote (`github`, `gitlab`)
4. Verifies `§6.C` convergence (every mirror's HEAD matches local HEAD)
5. Reports diagnostics on failure

## Prerequisites

- `git` ≥ 2.30 in PATH
- Local clone with at least one of: `github`, `gitlab` named remotes (use `git remote add github git@github.com:...`)
- `core.hooksPath` set to `.githooks` (one-time per clone): `git config core.hooksPath .githooks`
- For commits that touch test files / gate-shaping files: a `Bluff-Audit:` stamp in the commit message body per Seventh Law clause 1 + §6.N.1.2
- For commits that add a new constitutional clause (`##### 6.X` in CLAUDE.md): a `Classification:` line per HelixConstitution §11.4.17

## Usage

### Basic commit + push (all tracked changes)

```bash
scripts/commit_all.sh -m "fix(feature): one-line summary"
```

Stages every modified + new tracked file, commits, pushes to GitHub + GitLab.

### Commit specific files only

```bash
scripts/commit_all.sh -m "docs: update CONTINUATION" --files docs/CONTINUATION.md
```

Stages ONLY the listed files (no `git add -A` global add). Useful when:

- Multiple unrelated changes are in the working tree but only one belongs in this commit
- Submodules are nested-dirty but the parent change should not bump pins
- Large doc edits should not pull in stray test changes

### Commit without push

```bash
scripts/commit_all.sh -m "wip: experimenting" --no-push
```

Useful for staging WIP commits that are not yet ready to share.

### Diagnostic mode

```bash
scripts/commit_all.sh --status
```

Reports the repo path, branch, HEAD SHA, configured remotes, and a list of dirty files (first 20). Does NOT modify state.

### Amend (operator-authorized only)

```bash
scripts/commit_all.sh --amend
```

REJECTED with exit code 5. Per HelixConstitution §9 absolute-data-safety, destructive operations (history rewrite, force-push, branch deletion) require explicit per-operation operator authorization. The wrapper's standing rule: NO amend. If genuinely needed, the operator runs `git commit --amend` directly after backing up `.git`.

## Inputs

| Argument | Required? | Description |
|---|---|---|
| `-m`, `--message <msg>` | yes (unless `--status`) | Commit message body |
| `--files <f1> <f2> ...` | no | Stage only the listed paths (default: stage all tracked changes) |
| `--no-push` | no | Skip push; commit only |
| `--amend` | no | Rejected with exit 5 (operator must use `git commit --amend` directly) |
| `--status` | no | Diagnostic mode; reports state without modifying |
| `-h`, `--help` | no | Print usage and exit |

## Outputs

- Newly-created commit on local `HEAD`
- Push to every configured `github` + `gitlab` named remote
- §6.C convergence check report (✓ or ✗ per mirror)
- Exit code 0 on success

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Committed + pushed (or committed only with `--no-push`) |
| 1 | Usage error (missing required arg) |
| 2 | Git operation failed (commit failed) |
| 3 | Nothing to commit (informational, not error) |
| 4 | Push failed for one or more mirrors |
| 5 | Operator confirmation required for destructive flag (`--amend`) |

## Side-effects

- Modifies index, HEAD, refs/heads/master (the active branch)
- Pushes to remote refs (this is destructive in the sense that it advances remote state; per §6.W only github + gitlab are touched)
- Triggers the pre-push hook (`.githooks/pre-push`) which runs Layer 1 (anti-bluff checks) + Layer 2 (`scripts/ci.sh --changed-only`)

## Edge cases

### Multiple remotes named github but pointing to different upstreams

The wrapper pushes to every remote named exactly `github` or `gitlab`. If you have additional named remotes (`upstream`, `origin` with mixed push URLs, `helixdevelopment-fork`, etc.), they are NOT pushed by the wrapper. Per §6.W, only github + gitlab are mandated mirrors for the Lava parent.

### Submodules with nested-dirty state

If `submodules/X` shows `m` (lowercase, sub-submodule modified) in `git status`, the parent commit does NOT include the submodule unless explicitly listed in `--files`. This is intentional — pre-existing nested-dirty state should not silently advance the parent's pin.

### Commit body includes `--` token

Argument parsing stops `--files` consumption on the first token starting with `-` (single or double dash). Pass `-m` BEFORE `--files` to avoid the message being parsed as a file path.

## Internal behaviour

The wrapper is intentionally thin (~180 lines). It does not:

- Run tests directly (Layer 2 of pre-push hook does that via `scripts/ci.sh --changed-only`)
- Regenerate auto-generated docs (HelixConstitution §11.4.12 — TODO §6.AD-debt for the export-regeneration pipeline integration)
- Handle submodule pin bumps (use direct `git submodule add` / `git add submodules/<name>` for those)

## Related scripts

- `.githooks/pre-push` — invoked automatically on push; runs anti-bluff checks + ci.sh
- `scripts/ci.sh --changed-only` — Layer 2 pre-push gate (Spotless, changed-module unit tests, constitution parser, hermetic bash test suites)
- `scripts/check-constitution.sh` — invoked from `ci.sh`; verifies clauses 6.A through 6.AD + §6.W boundary + no-guessing vocabulary
- `scripts/firebase-distribute.sh` — release distribute (not invoked from commit_all)
- `scripts/inject-helix-inheritance-block.sh` — §6.AD-debt closure tool; idempotent inject of inheritance pointer-blocks

## Cross-references

- HelixConstitution `CLAUDE.md` "MANDATORY COMMIT & PUSH CONSTRAINTS"
- HelixConstitution `Constitution.md` §11.4.22 (lightweight commit path)
- HelixConstitution `Constitution.md` §11.4.18 (script documentation mandate — this very document satisfies §11.4.18 for `commit_all.sh`)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
- Lava `CLAUDE.md` §6.W (GitHub + GitLab only mirror mandate)
- Lava `CLAUDE.md` §6.T.3 (no-force-push)
