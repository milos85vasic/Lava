# `scripts/check-constitution.sh` — User Guide

**Last verified:** 2026-07-02 (§6.N/O/P/Q propagation-check snake_case-glob + pointer-block fix)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/check-constitution.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/check-constitution.sh — verify constitutional clauses present.

Per the SP-3a plan Task 5.19. Asserts that the three SP-3a clauses
(6.D, 6.E, 6.F) are present in root CLAUDE.md and that the
submodules/tracker_sdk/CLAUDE.md exists. Run from scripts/ci.sh in
every mode.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Clauses verified (current set, growing list)

The script asserts presence of the following constitutional clauses + supporting infrastructure:

- §6.D + §6.E + §6.F (root CLAUDE.md)
- `submodules/tracker_sdk/CLAUDE.md` exists
- `core/CLAUDE.md` references §6.E
- `feature/CLAUDE.md` references Challenge Test requirement
- §6.H credential pattern absence (no plaintext credentials in tracked files)
- §6.K Containers extension presence
- §6.X Container-Submodule Emulator Wiring inheritance + runtime checks (a) + (b)
- **§6.AD HelixConstitution Inheritance** — clause + constitution submodule + 54 per-scope inheritance pointer-blocks present
- **§6.W remote-host boundary** — only github + gitlab named remotes on parent + Lava-owned submodules
- **§11.4.6 no-guessing vocabulary** — forbidden words in tracked status/closure files unless prefixed by UNCONFIRMED:/UNKNOWN:/PENDING_FORENSICS:
- **§6.AE Comprehensive Challenge Coverage + Container/QEMU Matrix Mandate** (added 2026-05-15) — clause + `scripts/check-challenge-coverage.sh` + `scripts/run-challenge-matrix.sh` exist + executable

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/check-constitution.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)

## 2026-05-15 update — HelixQA waiver

Phase 4 of the constitution-compliance plan adopted `submodules/helixqa` (HelixDevelopment-owned QA orchestration framework) at upstream HEAD `403603db`. HelixQA's CLAUDE.md / AGENTS.md / CONSTITUTION.md follow the canonical-root `## INHERITED FROM Helix Constitution` pointer pattern (HelixDevelopment-authored) rather than Lava's heading-anchored §6.R / §6.S / §6.X / §6.AD pointer-block format. HelixQA also lacks `helix-deps.yaml` + `install_upstreams.sh` wrapper script.

Resolution: `HELIX_DEV_OWNED=("HelixQA")` waiver list + `is_helix_dev_owned()` helper skip HelixQA in every per-Submodule loop in this scanner. Waiver entries cite Phase 4-debt: PR to `HelixDevelopment/HelixQA` upstream owed to add the missing files. Once upstream merges + Lava's pin advances to include them, HelixQA can be removed from the waiver list.

## 2026-05-16 update — HelixQA waiver RESOLVED

The Phase 4-debt PR to `HelixDevelopment/HelixQA` upstream landed as commit `b13ba7c` (`feat(gov): add helix-deps.yaml + install_upstreams.sh wrapper`), adding both missing files at HelixQA's repo root. Lava's pin advanced to `b13ba7c` in the same parent commit that removed HelixQA from the Lava-side waiver lists in `scripts/check-helix-deps-manifest.sh` + `scripts/check-canonical-root-and-upstreams.sh`. Both downstream scanners (`CM-HELIX-DEPS-MANIFEST` + `CM-CANONICAL-ROOT-CLARITY` + `CM-INSTALL-UPSTREAMS-RAN` gates) now treat HelixQA on equal terms with the other 16 owned submodules — 17/17 own-org submodules satisfy the constitutional surface; 0 waived. This scanner (`scripts/check-constitution.sh`) did not directly carry a HelixQA waiver itself — the waiver lived in the two downstream scanners it transitively invokes via `scripts/verify-all-constitution-rules.sh`. The waiver-state synchronization across the three docs is maintained per §11.4.18 (script-doc sync).

## 2026-07-02 update — §6.N/O/P/Q propagation-check fix (snake_case glob + pointer-block acceptance)

**Regression found:** the §6.N / §6.O / §6.P / §6.Q propagation blocks (9 / 9b / 9d / 9e) built their `propagation_targets` list from the **pre-migration CamelCase** submodule directory names (`Auth`, `Cache`, `Tracker-SDK`, … — the literal `submodules/<CamelCase>/CLAUDE.md` path form). After the §11.4.29 snake_case migration renamed every submodule dir to lowercase (`submodules/auth/`, `submodules/tracker_sdk/`, …), those old paths stopped resolving, so the per-target `[[ ! -f "$f" ]] && continue` guard **silently skipped every submodule** — turning all four propagation gates into no-ops for submodules. The drift went unnoticed because the parallel §6.AD pointer-block mechanism was doing the actual inheritance. Surfaced by the hermetic `tests/check-constitution/check_constitution_test.sh::test_missing_6n_from_submodule_fails`, which was failing (the checker passed a fixture with §6.N stripped from `submodules/auth/CLAUDE.md`).

**Fix (two parts, no submodule backfill):**
1. `propagation_targets` now enumerates submodule docs via a migration-proof `submodules/*/CLAUDE.md` glob instead of the CamelCase literal list.
2. Blocks 9 / 9b / 9d / 9e now use the existing `doc_inherits_clause "$f" "6.N"` helper (literal clause **OR** the `## INHERITED FROM constitution/` §6.AD pointer-block) — the *same* mechanism the §6.R / §6.S / §6.X propagation gates already accept. This is provably **not** a weakening: block `6.AD(4)` independently HARD-enforces the pointer-block's presence in every submodule doc, so accepting it here adds no pass-path that isn't already guaranteed and gated.

**Why not backfill legacy per-clause sections into 16 submodules:** every submodule already carries the §6.AD pointer-block (the canonical post-2026-05-14 inheritance mechanism), which transitively inherits §6.N/O/P/Q. The explicit `## Clause 6.N` sections that only http3/mdns/tracker_sdk carry are pre-§6.AD "Group A" legacy; duplicating them into every submodule would be redundant churn, not real coverage.

The companion test was updated to strip BOTH the literal §6.N and the pointer-block from `submodules/auth/CLAUDE.md`, so it faithfully exercises the block-9 rejection path (a submodule with NO §6.N inheritance mechanism).
