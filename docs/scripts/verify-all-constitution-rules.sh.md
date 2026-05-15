# `scripts/verify-all-constitution-rules.sh` — User Guide

**Last verified:** 2026-05-15 (constitution-compliance plan Phase 1)
**Inheritance:** HelixConstitution §11.4.32 (Post-Constitution-Pull Validation Mandate) + §11.4.18 (script documentation)

## Overview

Master enforcement-engine sweep that wraps every individual constitution-gate + every hermetic test suite into a single invocation. Per §11.4.32 itself: "this gate is the enforcement engine for every other §11.4.x and CONST-NNN rule — without it, new rules cascade as anchors but never get enforced in the codebase".

Mandatory invocation triggers (per §11.4.32):
- Whenever `git submodule update --remote constitution` produces any content change in the constitution submodule
- Operator-explicit manual invocation
- ci.sh step 5a4 (every CI run)

## Usage

```bash
bash scripts/verify-all-constitution-rules.sh                # STRICT (default) — exits 1 on any gate failure
bash scripts/verify-all-constitution-rules.sh --strict       # explicit strict
bash scripts/verify-all-constitution-rules.sh --advisory     # exits 0 even with failures
bash scripts/verify-all-constitution-rules.sh --json-only    # suppresses stdout summary; only emits JSON
```

## Inputs

| Arg | Description |
|---|---|
| `--strict` | Default: exit 1 on any gate failure |
| `--advisory` | Exit 0 even with failures (for incremental adoption) |
| `--json-only` | Suppress stdout per-gate summary; only write attestation JSON |
| `LAVA_REPO_ROOT` env | Override repo root for hermetic testing |

## Outputs

- **stdout:** per-gate pass/fail summary + final verdict (suppressed by `--json-only`)
- **`.lava-ci-evidence/verify-all/<UTC-timestamp>.json`:** structured attestation with sweep metadata + per-gate result list

Attestation JSON schema:
```json
{
  "sweep_timestamp": "<ISO timestamp>",
  "sweep_mode": "strict|advisory",
  "sweep_constitution_pin": "<git rev-parse HEAD inside constitution/>",
  "total_gates": <int>,
  "pass_count": <int>,
  "fail_count": <int>,
  "all_passed": <bool>,
  "gates": [
    {"name": "<gate-name>", "rule_ref": "<§clause-ref>", "result": "PASS|FAIL", "duration_seconds": <int>}
  ]
}
```

## Gates registry (current)

The sweep currently invokes 34 gates across 4 categories (count last revised in Phase 8 — §11.4.35 + §11.4.36 canonical-root + install_upstreams added in advisory mode):

1. **Constitution doc parser** (1 gate) — `scripts/check-constitution.sh` covering §6.A-§6.AE inheritance + §6.W boundary + §11.4.6 no-guessing
2. **Anti-bluff scanners** (7 gates) — non-fatal coverage / **gitignore coverage (Phase 2 — §11.4.30)** / **nested-own-org-submodules (Phase 5 — §11.4.28, advisory)** / **canonical-root-and-upstreams (Phase 8 — §11.4.35 + §11.4.36, advisory)** / Challenge discrimination Layer 1+2 / Challenge coverage / fixture freshness
3. **No-hardcoding scanners** (3 gates) — UUID / IPv4 / host:port literal scans
4. **Hermetic test suites** (~21 gates) — tests/firebase + tests/ci-sh + tests/compose-layout + tests/tag-helper + tests/vm-* + tests/pre-push/check{4,5,6,7,8,9} + tests/check-constitution/* (now includes `test_gitignore_coverage.sh` + `test_nested_own_org_submodules.sh` + `test_canonical_root_and_upstreams.sh`)

Per-gate STRICT/ADVISORY note: `nested-own-org-submodules` and `canonical-root-and-upstreams` both run in advisory mode inside the sweep until their respective open violations are closed (Submodules/Challenges/Panoptic refactor; 10 install_upstreams scripts). The scanners THEMSELVES are STRICT by default when invoked directly; the sweep wrapper passes `--advisory` so the sweep continues running other gates + reports the violations without short-circuiting. Once each open violation is closed, the corresponding sweep wrapper flips to STRICT.

The list grows as new constitution gates land. Adding a new gate to the registry requires updating both the sweep + the hermetic meta-test (`tests/check-constitution/test_verify_all_rules.sh`).

## Falsifiability rehearsal (mandated by §11.4.32 itself)

§11.4.32: "sweep's own meta-test (paired mutation §1.1) plants a known violation of each enforced gate and asserts sweep reports FAIL for the planted gate."

Implemented in `tests/check-constitution/test_verify_all_rules.sh` with 4 fixtures:
- `test_clean_tree_passes` — sweep passes against the real Lava tree
- `test_gate_failure_propagates` — synthetic minimal repo (no constitution clauses) causes sweep to report `all_passed: false` + exit 1
- `test_advisory_mode_returns_zero` — `--advisory` exits 0 even with failures
- `test_attestation_json_structure` — emitted JSON has all required fields

A sweep that exits PASS without running every implementable gate is itself a §11.4.32 violation.

## Side-effects

- Creates `.lava-ci-evidence/verify-all/` directory if missing
- Writes one attestation file per invocation (timestamps make file names unique)
- Does NOT modify any tracked files

## Cross-references

- HelixConstitution `Constitution.md` §11.4.32 (the mandate)
- `docs/plans/2026-05-15-constitution-compliance.md` (Phase 1 — this script)
- `docs/helix-constitution-gates.md` `CM-VERIFY-ALL-CONSTITUTION-RULES` row
- `tests/check-constitution/test_verify_all_rules.sh` (the hermetic meta-test)
- All individual scanners + hermetic suites the sweep wraps
