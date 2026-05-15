# `scripts/check-helix-deps-manifest.sh` — User Guide

**Last verified:** 2026-05-15 (constitution-compliance plan Phase 3)
**Inheritance:** HelixConstitution §11.4.31 (Submodule-Dependency-Manifest Mandate) + §11.4.18 (script docs)

## Overview

Verifies that the parent project root + every owned-by-us submodule ships a `helix-deps.yaml` (or `.json` / `.toml` variant — single canonical file per submodule) declaring its inter-submodule dependencies per HelixConstitution §11.4.31.

The manifest is what an incorporator script reads to bootstrap a fresh project's submodule graph from scratch — eliminating the §11.4.28-forbidden nested own-org submodule chains by making each dependency declaratively flat-layoutable.

## Manifest schema (per §11.4.31)

```yaml
schema_version: 1

deps:
  - name: <CanonicalName>
    ssh_url: git@github.com:<org>/<name>.git
    ref: main          # branch or pinned tag
    why: "<human-readable rationale>"
    layout: flat       # 'flat' = <root>/<name>/; 'grouped' = <root>/submodules/<name>/

transitive_handling:
  recursive: true
  conflict_resolution: operator-required

language_specific_subtree: false   # true = inner subtree exempt from §11.4.29 snake_case
```

## Validation passes per manifest

1. File exists at canonical path (`helix-deps.yaml` preferred; `.json` / `.toml` accepted)
2. `schema_version: 1` declared
3. `deps:` top-level array present
4. `transitive_handling:` block present

(Deeper schema validation — per-dep `name`/`ssh_url`/`ref`/`why`/`layout` field-level checks — is owed to a follow-up enhancement; current scanner uses a structural grep-based fallback that catches the most common drift class without requiring `yq` / `python-yaml` toolchain.)

## Modes

| Flag | Behavior |
|---|---|
| `--strict` (default) | Exit 1 on any violation |
| `--advisory` | Exit 0 even on violation |
| `LAVA_HELIX_DEPS_STRICT=0` env | Same as --advisory |

## Usage

```bash
bash scripts/check-helix-deps-manifest.sh                # strict (default)
bash scripts/check-helix-deps-manifest.sh --advisory     # exit 0 even on violation
LAVA_HELIX_DEPS_STRICT=0 bash scripts/check-helix-deps-manifest.sh   # env-driven
```

## Hermetic test

`tests/check-constitution/test_helix_deps_manifest.sh` — 6 fixtures:

1. `test_compliant_fixture_passes` — parent + per-submodule manifests both present + well-formed
2. `test_missing_parent_manifest_rejected` — repo root lacks helix-deps.yaml
3. `test_missing_submodule_manifest_rejected` — owned submodule lacks helix-deps.yaml
4. `test_wrong_schema_version_rejected` — schema_version: 999 (must be 1)
5. `test_advisory_mode_returns_zero` — advisory swallows exit code
6. `test_json_variant_accepted` — discrimination test: helix-deps.json is recognized as a valid alternative extension

## Known violations (2026-05-15 audit)

Parent manifest: ✅ `helix-deps.yaml` (well-formed, declares 17 deps: 16 vasic-digital submodules + constitution).

Per-submodule manifests: 16 of 16 owned submodules MISSING helix-deps.yaml.

| Submodule | Status |
|---|---|
| Auth, Cache, Challenges, Concurrency, Config, Containers, Database, Discovery, HTTP3, Mdns, Middleware, Observability, RateLimiter, Recovery, Security, Tracker-SDK | ✗ MISSING — owed in per-submodule follow-up commits |

Resolution path: per-submodule cycle (write helix-deps.yaml → commit to submodule → push to GitHub + GitLab per §6.W → bump pin in parent Lava). The parent commit can batch the 16 pin bumps once each submodule has its own manifest. Then sweep wrapper flips from advisory → strict.

## Cross-references

- HelixConstitution `Constitution.md` §11.4.31 (the mandate)
- `docs/plans/2026-05-15-constitution-compliance.md` Phase 3
- `docs/helix-constitution-gates.md` `CM-HELIX-DEPS-MANIFEST` row
- `scripts/verify-all-constitution-rules.sh` (wraps this scanner per §11.4.32)
- Lava parent's reference manifest: `helix-deps.yaml` (declares all 16 submodule deps + constitution)
