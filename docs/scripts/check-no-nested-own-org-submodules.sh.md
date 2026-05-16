# `scripts/check-no-nested-own-org-submodules.sh` — User Guide

**Last verified:** 2026-05-15 (constitution-compliance plan Phase 5)
**Inheritance:** HelixConstitution §11.4.28 (Submodules-As-Equal-Codebase) + §11.4.18 (script docs)

## Overview

Enforces §11.4.28: an owned-by-us submodule MUST NOT itself contain another own-org submodule as a nested entry. Chains like `vasic-digital/Lava → vasic-digital/Challenges → vasic-digital/Panoptic` are forbidden — they create transitive-ownership opacity, frustrate independent peer development, and silently couple component lifecycles that §11.4.28 demands be peers.

## Forbidden orgs

The OWN-ORG set (any of these orgs as a nested submodule's host org triggers a violation):

- `vasic-digital`
- `HelixDevelopment`
- `red-elf`
- `ATMOSphere1234321`
- `Bear-Suite`
- `BoatOS123456`
- `Helix-Flow`
- `Helix-Track`
- `Server-Factory`

Both `github.com:` and `gitlab.com:` URL forms are matched.

## Modes

| Flag | Behavior |
|---|---|
| `--strict` (default) | Exit 1 on any violation |
| `--advisory` | Exit 0 even on violation |
| `LAVA_NESTED_OWN_ORG_STRICT=0` env | Same as --advisory |

## Resolution paths

When a violation is reported, two resolution paths exist:

1. **Refactor (preferred):** Extract the nested submodule to be a peer of the container. Example: move `submodules/challenges/Panoptic` to `submodules/Panoptic` (parent-level peer of `submodules/challenges`). Both submodules then load independently from the parent project's `.gitmodules`.

2. **Waiver:** Add an entry to the `WAIVERS=()` array at the top of the scanner. Each waiver MUST cite:
   - WHY the nesting exists (legacy coupling, hard infrastructure dependency, etc.)
   - Tracking issue or commit referencing the planned refactor
   - Target removal date or "permanent" if structurally required

## Usage

```bash
bash scripts/check-no-nested-own-org-submodules.sh                # strict (default)
bash scripts/check-no-nested-own-org-submodules.sh --advisory     # exit 0 even on violation
LAVA_NESTED_OWN_ORG_STRICT=0 bash scripts/check-no-nested-own-org-submodules.sh   # env-driven
```

## Hermetic test

`tests/check-constitution/test_nested_own_org_submodules.sh` — 4 fixtures:

- `test_clean_fixture_passes` — synthetic submodules/foo/ pointing to nlohmann/json (third-party org) → exits 0
- `test_vasic_digital_chain_rejected` — synthetic vasic-digital chain → exits 1 with violation
- `test_helix_dev_gitlab_chain_rejected` — HelixDevelopment via gitlab → exits 1 with violation
- `test_advisory_mode_returns_zero` — advisory mode swallows exit code

## Known violations (2026-05-15 audit)

| Container | Nested path | Forbidden URL | Status |
|---|---|---|---|
| `submodules/challenges` | `Panoptic` | `git@github.com:vasic-digital/Panoptic.git` | OPEN — refactor owed |

The Challenges/Panoptic chain pre-dates §11.4.28 (the clause was added 2026-05-15). Resolution is owed in a follow-up cycle: extract Panoptic to be `submodules/Panoptic` (parent-level peer of Challenges), update both projects' `.gitmodules`, propagate the rename through any references in the Challenges submodule's code.

## Cross-references

- HelixConstitution `Constitution.md` §11.4.28 (the mandate)
- `docs/plans/2026-05-15-constitution-compliance.md` Phase 5
- `docs/helix-constitution-gates.md` `CM-NO-NESTED-OWN-ORG-SUBMODULES` row
- `scripts/verify-all-constitution-rules.sh` (wraps this scanner per §11.4.32)
