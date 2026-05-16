# `scripts/check-constitution.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
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
