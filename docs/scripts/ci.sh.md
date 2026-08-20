# `scripts/ci.sh` — User Guide

**Last verified:** 2026-05-16 (Phase 6a + 6b §11.4.29 snake_case migration: scripts/ci.sh path-references updated from `Submodules/` to `submodules/`)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/ci.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/ci.sh — local-only CI gate for Lava.

Per the Local-Only CI/CD constitutional rule, this script IS the
project's CI/CD apparatus. The same script runs in three modes:

  --changed-only   Fast subset for the pre-push hook (Spotless,
                   unit tests of changed modules, constitutional
                   doc parser, forbidden-files check). No
                   real-device tests; no mutation tests.

  --full           All gates — unit tests across every module,
                   parity gate, mutation tests where wired,
                   fixture freshness, Compose UI Challenge Tests
                   (requires a connected Android device or
                   emulator). Used at tag time.

  (default)        Same as --full.

Per Sixth Law clause 5: passing CI is necessary, NOT sufficient for
a release. The operator real-device verification per Task 5.22 of
SP-3a is the load-bearing acceptance gate; this script certifies the
codebase is shippable, not that the user-visible feature is shipped.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Pipeline steps (current order)

1. Hosted-CI forbidden-files check (Local-Only CI/CD rule)
2. Host-power forbidden-command regex check (Host Stability rule)
3. Spotless (`./gradlew spotlessCheck`)
4. Unit tests across changed modules
5. Constitutional doc parser (`scripts/check-constitution.sh`)
6. **5a1 (added 2026-05-14):** §6.AC non-fatal-coverage scan (STRICT default; closes §6.AC-debt + `CM-NONFATAL-COVERAGE` gate)
7. **5a2 (added 2026-05-15):** §6.AB Challenge-discrimination scan (STRICT default; closes §6.AB-debt + `CM-CHALLENGE-DISCRIMINATION` gate)
8. **5a3 (added 2026-05-15, 31st §6.L):** §6.AE per-feature Challenge coverage scan (STRICT default after per-feature backfill drained the queue 2026-05-15; `CM-CHALLENGE-COVERAGE` gate; `LAVA_CHALLENGE_COVERAGE_STRICT=0` to revert to advisory)
9. **5a4 (added 2026-05-15, constitution-compliance plan Phase 1):** §11.4.32 verify-all-constitution-rules sweep (STRICT default; the §11.4.32 mandated enforcement engine for every other constitution rule; wraps every individual gate above + every hermetic test suite into one invocation; produces per-run attestation JSON at `.lava-ci-evidence/verify-all/<UTC-timestamp>.json`; `CM-VERIFY-ALL-CONSTITUTION-RULES` gate)
9. 5b. Hermetic bash test suites (under `tests/`)
10. (Full-mode only) Parity, mutation, fixture-freshness, Compose UI Challenge Tests on connected device

## Forbidden-files check scoped to `git ls-files` — added 2026-08-20

Fixes a real false-positive in Pipeline step 1: the check previously used a raw
`find .` over the working directory, which also walked (a) other worktrees
checked out under `.claude/worktrees/agent-*/` — each is a DIFFERENT branch's
file tree, physically nested under this repo's working directory by the
harness but irrelevant to what the CURRENT branch is about to push — and (b)
vendored third-party content inside nested submodules-of-submodules (e.g.
HelixQA's `tools/opensource/*`, which legitimately ships upstream
`.github/workflows/*` files as that nested repo's own concern). Both classes
produced spurious `FORBIDDEN HOSTED-CI FILES` rejections on a push whose own
tracked tree contained zero such files. Fixed by scoping the scan to
`git ls-files` (this branch's actually-tracked files only) — submodules
naturally excluded as gitlinks (not descended into without
`--recurse-submodules`, which is deliberately not passed; submodule-internal
governance is scoped separately per §6.F), other worktrees naturally excluded
since their files aren't part of this branch's tree at all.

Bluff-Audit: scripts/ci.sh Hosted-CI forbidden-files check
  Mutation: `git add -f` a real `.github/workflows/mutation-test.yml` fixture
    into the tracked tree, confirming the NEW `git ls-files`-scoped check
    still correctly DETECTS a genuine violation (true-positive preserved).
  Observed-Failure: N/A for this direction (positive detection confirmed);
    the OLD `find .`-based check was separately confirmed to false-positive
    on real, pre-existing, untracked content under `.claude/worktrees/` and
    `submodules/helixqa/tools/opensource/*` during an actual `git push`
    attempt against this exact commit range — that rejection is the
    falsifiability evidence for the bug this fix closes.
  Reverted: yes — the mutation fixture was `git reset` + `rm -rf`'d
    immediately after the positive-detection check passed; confirmed via
    `git status --short` showing a clean tree before this commit.

## §6.AE matrix gate (separate entry point)

For §6.AE.2 gate-mode runs producing per-AVD attestation: `bash scripts/run-challenge-matrix.sh`. The runner correctly REFUSES to claim success on hosts lacking KVM (darwin/arm64) — exits 2 with a host-gap diagnostic. Real attestations require a Linux x86_64 + KVM gate-host.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/ci.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
