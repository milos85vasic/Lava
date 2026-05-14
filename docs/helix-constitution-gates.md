# HelixConstitution Gates — Lava Implementation Status

**Last updated:** 2026-05-14 (1.2.23 cycle, 29th §6.L invocation)
**Inheritance:** Per Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance Mandate); enumerated per §6.AD-debt item 8.

## Purpose

HelixConstitution defines a set of `CM-*` mechanical gates (see `constitution/Constitution.md` §11.4 sub-clauses). Each gate has a status: ✅ wired, ⚠️ paper-only, or ❌ blocked. This document is the operator-readable index — the source of truth for "what's actually enforced versus what's documented."

## Gate Inventory

| Gate ID | HelixConstitution clause | Lava implementation | Status |
|---|---|---|---|
| `CM-COVENANT-114-16-PROPAGATION` | §11.4.16 (Item-Type tracking) | n/a (Lava uses `.lava-ci-evidence/crashlytics-resolved/` + `.lava-ci-evidence/sixth-law-incidents/` rather than Issues/Issues_Summary trackers; equivalence documented in §6.AD.3) | ⚠️ paper-only |
| `CM-COMMIT-DOCS-EXISTS` | §11.4.22 (lightweight commit path) | `scripts/commit_all.sh` exists + executable; verified by `scripts/check-constitution.sh` 6.AD(5) | ✅ wired |
| `CM-FIXED-COLUMN-ALIGNMENT` | §11.4.19 (Fixed-document column alignment) | n/a (Lava does not maintain Fixed.md / Fixed_Summary.md tracker structure; functional equivalent: per-issue closure logs in `.lava-ci-evidence/crashlytics-resolved/`) | ⚠️ paper-only |
| `CM-SCRIPT-DOCS-SYNC` | §11.4.18 (script documentation mandate) | `docs/scripts/commit_all.sh.md` + `docs/scripts/inject-helix-inheritance-block.sh.md` exist; sync gate not yet wired in pre-push (would check that touching `scripts/X.sh` requires touching `docs/scripts/X.sh.md` in same commit) | ⚠️ paper-only |
| `CM-BUILD-RESOURCE-STATS-TRACKER` | §11.4.24 (build-resource stats tracking) | not implemented; sampler + TSV registry + Stats.{md,html,pdf} triple owed via §6.AD-debt item 5 | ❌ blocked |
| `CM-ITEM-STATUS-TRACKING` | §11.4.15 (item-status tracking) | n/a (Lava uses CONTINUATION.md + closure logs; mapping documented in §6.AD.3) | ⚠️ paper-only |
| `CM-ITEM-TYPE-TRACKING` | §11.4.16 (item-type tracking) | n/a (same as above) | ⚠️ paper-only |
| `CM-ITEM-OPERATOR-BLOCKED-DETAILS` | §11.4.21 (operator-blocked status + self-resolution audit) | n/a (Lava uses `.lava-ci-evidence/sixth-law-incidents/<date>.json` for blocker tracking; the `Operator-blocked` status vocabulary is not in use) | ⚠️ paper-only |
| `CM-OPERATOR-BLOCKED-SELF-RESOLUTION-AUDIT` | §11.4.21 | n/a (same as above) | ⚠️ paper-only |
| `CM-UNIVERSAL-VS-PROJECT-CLASSIFICATION` | §11.4.17 (universal-vs-project classification) | `.githooks/pre-push` Check 8 — fires when `##### 6.X` clause added to CLAUDE.md without `Classification:` line in commit body | ✅ wired |
| `CM-SUBAGENT-DELEGATION-AUDIT` | §11.4.20 (subagent-driven-by-default mandate) | n/a (no automated audit of subagent vs. foreground delegation in Lava; would require either Claude Code telemetry integration or manual operator review) | ⚠️ paper-only |
| `CM-NONFATAL-COVERAGE` | §6.AC + HelixConstitution telemetry discipline | `scripts/check-non-fatal-coverage.sh` (bash scanner; default WARN-only; `LAVA_NONFATAL_STRICT=1` to fail) | ✅ wired (PARTIAL: WARN-only; strict pending queue drain) |

## Lava-side anti-bluff gates (NOT HelixConstitution `CM-*` but related)

For completeness — these gates predate or extend HelixConstitution's `CM-*` set:

| Gate name | Lava clause | Implementation | Status |
|---|---|---|---|
| Hosted-CI config rejection | Local-Only CI/CD rule | `.githooks/pre-push` Check 1 | ✅ wired |
| Test-commit Bluff-Audit stamp | Seventh Law clause 1 | `.githooks/pre-push` Check 2 | ✅ wired |
| Mock-the-SUT pattern rejection | Seventh Law clause 4 | `.githooks/pre-push` Check 3 | ✅ wired |
| Gate-shaping file Bluff-Audit stamp + path-match | §6.N.1.2 | `.githooks/pre-push` Check 4 (with extended `.githooks/pre-push` regex) | ✅ wired |
| Attestation falsifiability rehearsal | §6.N.1.3 | `.githooks/pre-push` Check 5 | ✅ wired |
| §6.Y bump-first ordering | §6.Y | `.githooks/pre-push` Check 6 | ✅ wired |
| §6.Z evidence-file presence on pointer advance | §6.Z | `.githooks/pre-push` Check 7 + `firebase-distribute.sh` Phase-1 Gates | ✅ wired |
| §6.AA Two-Stage default + release-requires-debug-companion | §6.AA | `firebase-distribute.sh` (default flipped to `MODE=debug`; `--release-only` requires `last-version-debug >= current vc`) | ✅ wired |
| §6.W mirror-host boundary | §6.W + §6.AD.1 | `scripts/check-constitution.sh` 6.AD §6.W block | ✅ wired |
| §6.AD per-scope inheritance pointer-block presence | §6.AD.4 | `scripts/check-constitution.sh` 6.AD(4); 54 in-scope files enforced | ✅ wired |
| §6.AD constitution submodule presence + structure | §6.AD.2 | `scripts/check-constitution.sh` 6.AD(2) | ✅ wired |
| §6.AD `commit_all.sh` + `inject-helix-inheritance-block.sh` presence | §6.AD.5 + §6.AD.6 | `scripts/check-constitution.sh` 6.AD(5)+(6) | ✅ wired |
| §11.4.6 no-guessing vocabulary | §6.AD.6 + HelixConstitution §11.4.6 | `scripts/check-constitution.sh` final block | ✅ wired |
| §6.X Container-Submodule Emulator wiring | §6.X | `scripts/check-constitution.sh` 6.X(4)+(5) (with PARTIAL CLOSE — Linux x86_64 gate-host still owed) | ✅ wired (PARTIAL) |
| §6.K Containers extension presence | §6.K | `scripts/check-constitution.sh` clause 6.K block | ✅ wired |

## Why some gates are ⚠️ paper-only

The `CM-*` gates marked ⚠️ paper-only typically depend on infrastructure Lava does not have today:

- **Issues / Issues_Summary / Fixed / Fixed_Summary trackers** (§11.4.15 / §11.4.16 / §11.4.19 / §11.4.21): HelixConstitution prescribes a project-wide work-tracker file structure. Lava's equivalent is `docs/CONTINUATION.md` (single-file source-of-truth handoff per §6.S) plus per-issue closure logs in `.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md` (per §6.O). The substance is equivalent (status + type + history); the file structure differs. Lava does not plan to migrate to the HelixConstitution file structure unless / until the operator commissions that work; the equivalence is documented in §6.AD.3.

- **Build-resource stats tracker** (§11.4.24 / `CM-BUILD-RESOURCE-STATS-TRACKER`): Requires a host-side sampler that captures memory / CPU / IO at fixed interval, computes per-metric min/max/mean/p95, appends to a TSV registry, derives Stats.{md,html,pdf} via the §11.4.22 lightweight commit path. Substantial implementation work (~200+ lines plus a viewer). Tracked under §6.AD-debt item 5 for a future cycle.

- **Subagent-delegation audit** (§11.4.20 / `CM-SUBAGENT-DELEGATION-AUDIT`): Requires either Claude Code telemetry integration (not yet exposed by the platform) or operator-driven self-audit. Tracked under §6.AD-debt for a future cycle when telemetry surfaces become available.

- **Script documentation sync** (§11.4.18 / `CM-SCRIPT-DOCS-SYNC`): The doc files exist (`docs/scripts/commit_all.sh.md`, `docs/scripts/inject-helix-inheritance-block.sh.md`) but no pre-push gate yet enforces "modifying `scripts/X.sh` requires modifying `docs/scripts/X.sh.md` in the same commit". The pre-push gate is straightforward to add when the doc-set is more complete. Tracked under §6.AD-debt.

## How to read this document

- ✅ wired: a script enforces the rule; pre-push or similar gate fires on violation; can demonstrate by running the falsifiability rehearsal cited in the original closure commit
- ⚠️ paper-only: the rule is documented in CLAUDE.md / AGENTS.md / Constitution.md but no mechanical gate enforces it; operator + reviewer manually verify
- ❌ blocked: the rule cannot be enforced today due to missing infrastructure; closure work is tracked under §6.AD-debt

## How to wire a new gate

1. Read the corresponding `Constitution.md` §11.4.X clause for the rule's intent
2. Pick the enforcement surface: pre-push hook (`.githooks/pre-push`), constitution check (`scripts/check-constitution.sh`), distribute script (`scripts/firebase-distribute.sh`), tag script (`scripts/tag.sh`)
3. Add the gate logic; include `|| true` on every `grep -oE` pipe to avoid `set -e` killing the gate on zero matches
4. Add a falsifiability rehearsal: deliberately break a target file, re-run the gate, confirm the violation message includes the target path
5. Add a hermetic test under `tests/<area>/` exercising both the positive and negative paths
6. Update this document — flip ⚠️ to ✅ for the gate's row
7. Commit + push via `scripts/commit_all.sh` with proper Bluff-Audit stamp + Classification line

## Cross-references

- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance Mandate) + §6.AD-debt
- HelixConstitution `Constitution.md` §11.4.1 through §11.4.24
- HelixConstitution `CLAUDE.md` (universal CLAUDE.md inherited by every consuming project)
