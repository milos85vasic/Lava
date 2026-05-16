# `scripts/check-canonical-root-and-upstreams.sh` — User Guide

**Last verified:** 2026-05-15 (constitution-compliance plan Phase 8)
**Inheritance:** HelixConstitution §11.4.35 (Canonical-Root Inheritance Clarity) + §11.4.36 (Mandatory install_upstreams) + §11.4.18 (script docs)

## Overview

Combined verifier for two related HelixConstitution clauses:

### §11.4.35 — Canonical-Root Inheritance Clarity

Three sub-checks:

- **(a)** Consumer's root `CLAUDE.md` and `AGENTS.md` MUST open with an inheritance pointer (within the first 40 lines): either `## INHERITED FROM constitution/<file>` heading OR Claude Code's `@constitution/<file>` import syntax.
- **(b)** The constitution submodule's three canonical files MUST exist: `constitution/CLAUDE.md`, `constitution/AGENTS.md`, `constitution/Constitution.md`.
- **(c)** The constitution submodule's own `CLAUDE.md` and `AGENTS.md` MUST NOT carry `## INHERITED FROM` at top level — they ARE the canonical root and must not appear to inherit from elsewhere. **Pattern is allowed inside fenced ```` ``` ```` code blocks** (where it's documentation showing consumers the inheritance pattern).

### §11.4.36 — Mandatory install_upstreams script

Every owned-by-us submodule MUST ship an `install_upstreams` script at one of these locations:

- `<submodule>/install_upstreams.sh`
- `<submodule>/install_upstreams`
- `<submodule>/scripts/install_upstreams.sh`
- `<submodule>/scripts/install_upstreams`
- `<submodule>/Upstreams/install.sh`
- `<submodule>/upstreams/install.sh`

The script is what a freshly-cloned submodule uses to self-provision its own remote topology (per §6.W: github + gitlab minimum for vasic-digital submodules; 4 mirrors for HelixDevelopment-owned submodules).

Per-submodule waivers are supported (`INSTALL_UPSTREAMS_WAIVERS=()` in the scanner) with mandatory rationale. No active waivers in 2026-05-15 baseline.

## Modes

| Flag | Behavior |
|---|---|
| `--strict` (default) | Exit 1 on any violation |
| `--advisory` | Exit 0 even on violation |
| `LAVA_CANONICAL_ROOT_STRICT=0` env | Same as --advisory |

## Usage

```bash
bash scripts/check-canonical-root-and-upstreams.sh                # strict (default)
bash scripts/check-canonical-root-and-upstreams.sh --advisory     # exit 0 even on violation
LAVA_CANONICAL_ROOT_STRICT=0 bash scripts/check-canonical-root-and-upstreams.sh   # env-driven
```

## Hermetic test

`tests/check-constitution/test_canonical_root_and_upstreams.sh` — 6 fixtures:

1. `test_compliant_fixture_passes` — baseline compliant fixture
2. `test_missing_root_pointer_rejected` — root CLAUDE.md without inheritance pointer
3. `test_canonical_self_inheritance_rejected` — canonical root carrying its own `## INHERITED FROM` outside any code block
4. `test_missing_install_upstreams_rejected` — submodule lacking install_upstreams
5. `test_advisory_mode_returns_zero` — advisory swallows exit code
6. `test_canonical_fenced_code_block_pointer_passes` — `## INHERITED FROM` INSIDE a fenced ```` ```markdown ```` block is documentation, not violation (discrimination test for false-positive prevention)

## Known violations (2026-05-15 audit)

§11.4.35 sub-checks all PASS in current Lava tree (after the Phase 8 false-positive fix in §11.4.35.c — the constitution submodule's documentation of the pointer pattern inside a fenced code block is correctly recognized as non-violating).

§11.4.36 — 10 of 16 owned submodules MISSING install_upstreams:

| Submodule | Status |
|---|---|
| Auth | ✓ has install_upstreams |
| Cache | ✓ has install_upstreams |
| Challenges | ✗ MISSING — owed in follow-up |
| Concurrency | ✓ has install_upstreams |
| Config | ✗ MISSING — owed in follow-up |
| Containers | ✗ MISSING — owed in follow-up |
| Database | ✓ has install_upstreams |
| Discovery | ✗ MISSING — owed in follow-up |
| HTTP3 | ✗ MISSING — owed in follow-up |
| Mdns | ✗ MISSING — owed in follow-up |
| Middleware | ✗ MISSING — owed in follow-up |
| Observability | ✓ has install_upstreams |
| RateLimiter | ✗ MISSING — owed in follow-up |
| Recovery | ✗ MISSING — owed in follow-up |
| Security | ✓ has install_upstreams |
| Tracker-SDK | ✗ MISSING — owed in follow-up |

Resolution path: each submodule gains its own install_upstreams.sh in a per-submodule commit. Per-submodule cycle: write script → commit to submodule → push to GitHub + GitLab (per §6.W) → bump pin in parent Lava repo. The parent commit can batch the 10 pin bumps once each submodule has its own install_upstreams.

## Cross-references

- HelixConstitution `Constitution.md` §11.4.35 + §11.4.36 (the mandates)
- `docs/plans/2026-05-15-constitution-compliance.md` Phase 8
- `docs/helix-constitution-gates.md` `CM-CANONICAL-ROOT-CLARITY` + `CM-INSTALL-UPSTREAMS-RAN` rows
- `scripts/verify-all-constitution-rules.sh` (wraps this scanner per §11.4.32)

## 2026-05-15 update — HelixQA waiver

Phase 4 of the constitution-compliance plan adopted `submodules/helixqa` (HelixDevelopment-owned QA orchestration framework) at upstream HEAD `403603db`. HelixQA's CLAUDE.md / AGENTS.md / CONSTITUTION.md follow the canonical-root `## INHERITED FROM Helix Constitution` pointer pattern (HelixDevelopment-authored) rather than Lava's heading-anchored §6.R / §6.S / §6.X / §6.AD pointer-block format. HelixQA also lacks `helix-deps.yaml` + `install_upstreams.sh` wrapper script.

Resolution: `HELIX_DEV_OWNED=("HelixQA")` waiver list + `is_helix_dev_owned()` helper skip HelixQA in every per-Submodule loop in this scanner. Waiver entries cite Phase 4-debt: PR to `HelixDevelopment/HelixQA` upstream owed to add the missing files. Once upstream merges + Lava's pin advances to include them, HelixQA can be removed from the waiver list.

## 2026-05-16 update — HelixQA waiver RESOLVED

The Phase 4-debt PR to `HelixDevelopment/HelixQA` upstream landed as commit `b13ba7c` (`feat(gov): add helix-deps.yaml + install_upstreams.sh wrapper`), adding both missing files at HelixQA's repo root. Lava's pin advanced to `b13ba7c` in the same parent commit that removed HelixQA from `INSTALL_UPSTREAMS_WAIVERS`. The waiver list is now empty — the per-Submodule loop in this scanner treats HelixQA on equal terms with the other 16 owned submodules; all 17 must ship a recognizable `install_upstreams.sh` wrapper invoking `Upstreams/*.sh` (or `upstreams/*.sh`) recipes. The `INSTALL_UPSTREAMS_WAIVERS=()` empty array is intentionally retained as a forensic anchor. The §11.4.35 canonical-root inheritance pointer audit for HelixQA's CLAUDE.md/AGENTS.md/CONSTITUTION.md remains unaffected by this closure — HelixQA's HelixDevelopment-authored `## INHERITED FROM Helix Constitution` pointer pattern continues to satisfy the constitution-submodule's own inheritance discipline, and the §11.4.35 (a) check passes for HelixQA without needing waivers (its CLAUDE.md opens with the inheritance pointer, just sourced from HelixDevelopment's pattern rather than Lava's §6.AD block — both are valid).
