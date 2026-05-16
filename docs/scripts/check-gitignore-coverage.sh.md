# `scripts/check-gitignore-coverage.sh` — User Guide

**Last verified:** 2026-05-15 (constitution-compliance plan Phase 2)
**Inheritance:** HelixConstitution §11.4.30 (.gitignore + No-Versioned-Build-Artifacts Mandate) + §11.4.18 (script docs)

## Overview

Enforces §11.4.30: every project module / owned-by-us submodule / service / application MUST ship a proper `.gitignore`. Plus: NO tracked files may match the forbidden-pattern set (a tracked `*.log` despite the ignore-line is a violation of equal severity).

## Modes

| Flag | Behavior |
|---|---|
| `--strict` (default) | Exit 1 on any violation |
| `--advisory` | Exit 0 even on violation |
| `LAVA_GITIGNORE_STRICT=0` env | Same as --advisory |

## Module-detection rules

- `app`, `buildSrc`, `lava-api-go` are required if present
- `core/<X>/` and `feature/<X>/` are required if they have `build.gradle.kts` (i.e., are actual Gradle modules — pure parent dirs like `core/network/` are skipped)
- `core/<X>/<Y>/` sub-leaf modules same rule
- All `submodules/<X>/` are required (every owned-by-us submodule)

## Forbidden-pattern detection

Tracked files matching ANY of these patterns trigger a violation:
- `.env`, `.env.<lc-letters>` (except `.env.example`)
- `*.pem`, `*.key`, `*.crt`
- `id_rsa*`, `id_ed25519*`
- `node_modules/`, `__pycache__/`, `.gradle/`
- `/build/`, `/dist/`, `/out/`
- `.DS_Store`, `Thumbs.db`, `*.swp`

## Allowlist

The scanner has an explicit allowlist for files that match the forbidden-pattern regex but ARE legitimately tracked (e.g., deployment-recipe configs that happen to use the .env extension). Each allowlist entry MUST cite its rationale inline. See the `ALLOWLIST=()` array at the top of the script.

Current allowlist:
- `deployment/thinker/thinker.local.env` — deployment-recipe doc, not a secret container; rename to clearer extension owed to Phase 6 (lowercase snake_case).

## Usage

```bash
bash scripts/check-gitignore-coverage.sh                # strict (default)
bash scripts/check-gitignore-coverage.sh --advisory     # exit 0 even on violation
LAVA_GITIGNORE_STRICT=0 bash scripts/check-gitignore-coverage.sh   # env-driven
```

## Hermetic test

`tests/check-constitution/test_gitignore_coverage.sh` — 3 fixtures:
- `test_real_tree_passes` — real Lava tree exits 0
- `test_module_without_gitignore_rejected` — synthetic feature/foo/ with build.gradle.kts but no .gitignore → flagged
- `test_clean_fixture_passes` — synthetic minimal repo with .gitignore → passes

## Cross-references

- HelixConstitution `Constitution.md` §11.4.30 (the mandate)
- `docs/plans/2026-05-15-constitution-compliance.md` Phase 2
- `docs/helix-constitution-gates.md` `CM-GITIGNORE-PRECOMMIT-AUDIT` row
- `scripts/verify-all-constitution-rules.sh` (wraps this scanner per §11.4.32)
