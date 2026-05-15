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
| `CM-SCRIPT-DOCS-SYNC` | §11.4.18 (script documentation mandate) | `.githooks/pre-push` Check 9 — fires when `scripts/X.sh` is modified without companion `docs/scripts/X.sh.md` in the same commit (only when the doc already exists at this commit); rehearsed in synthetic fixture | ✅ wired |
| `CM-BUILD-RESOURCE-STATS-TRACKER` | §11.4.24 (build-resource stats tracking) | `scripts/build-stats-sample.sh` (per-sample collector, BSD+GNU portable, wraps a build via `wrap "<name>" -- <cmd>`) + `.lava-ci-evidence/build-stats/registry.tsv` (per-build summary registry) + `scripts/build-stats-report.sh` (derives `docs/build-stats/Stats.md` + `Stats.html` via pandoc + `Stats.pdf` via wkhtmltopdf-or-weasyprint; HTML/PDF skip silently if exporters absent) | ✅ wired |
| `CM-ITEM-STATUS-TRACKING` | §11.4.15 (item-status tracking) | n/a (Lava uses CONTINUATION.md + closure logs; mapping documented in §6.AD.3) | ⚠️ paper-only |
| `CM-ITEM-TYPE-TRACKING` | §11.4.16 (item-type tracking) | n/a (same as above) | ⚠️ paper-only |
| `CM-ITEM-OPERATOR-BLOCKED-DETAILS` | §11.4.21 (operator-blocked status + self-resolution audit) | n/a (Lava uses `.lava-ci-evidence/sixth-law-incidents/<date>.json` for blocker tracking; the `Operator-blocked` status vocabulary is not in use) | ⚠️ paper-only |
| `CM-OPERATOR-BLOCKED-SELF-RESOLUTION-AUDIT` | §11.4.21 | n/a (same as above) | ⚠️ paper-only |
| `CM-UNIVERSAL-VS-PROJECT-CLASSIFICATION` | §11.4.17 (universal-vs-project classification) | `.githooks/pre-push` Check 8 — fires when `##### 6.X` clause added to CLAUDE.md without `Classification:` line in commit body | ✅ wired |
| `CM-SUBAGENT-DELEGATION-AUDIT` | §11.4.20 (subagent-driven-by-default mandate) | n/a (no automated audit of subagent vs. foreground delegation in Lava; would require either Claude Code telemetry integration or manual operator review) | ⚠️ paper-only |
| `CM-NONFATAL-COVERAGE` | §6.AC + HelixConstitution telemetry discipline | `scripts/check-non-fatal-coverage.sh` (bash scanner; default STRICT-mode after queue drained 2026-05-14; `LAVA_NONFATAL_STRICT=0` to revert to advisory mode) | ✅ wired |
| `CM-CHALLENGE-DISCRIMINATION` | §6.AB Anti-Bluff Test-Suite Reinforcement | `scripts/check-challenge-discrimination.sh` (bash scanner; STRICT-default; verifies every Challenge*Test.kt carries a FALSIFIABILITY REHEARSAL marker in KDoc OR a companion .lava-ci-evidence/sp3a-challenges/<name>.json; `LAVA_CHALLENGE_DISCRIMINATION_STRICT=0` to revert to advisory) | ✅ wired |
| `CM-CHALLENGE-COVERAGE` | §6.AE Comprehensive Challenge Coverage + Container/QEMU Matrix Mandate | `scripts/check-challenge-coverage.sh` (bash scanner; STRICT-default after queue drained 2026-05-15; verifies every feature/* module has at least one Challenge that targets it via import / `// covers-feature:` marker / heuristic / `// AE-exempt:` ledger entry; `LAVA_CHALLENGE_COVERAGE_STRICT=0` to revert to advisory) + `scripts/run-challenge-matrix.sh` (matrix-runner glue; delegates to `Submodules/Containers/cmd/emulator-matrix --runner=containerized`; pre-bakes the §6.AE.2 minimum API/form-factor matrix; refuses to claim success on hosts lacking KVM with a clear §6.X-debt host-gap diagnostic). Hermetic test: `tests/check-constitution/test_challenge_coverage.sh` (4 falsifiability fixtures, all PASS) | ✅ wired (PARTIAL: scanner STRICT-and-green; runner still BLOCKED on darwin/arm64 per standing §6.X-debt) |
| `CM-VERIFY-ALL-CONSTITUTION-RULES` | HelixConstitution §11.4.32 Post-Constitution-Pull Validation Mandate | `scripts/verify-all-constitution-rules.sh` (master sweep; STRICT default; wraps every individual gate above + every hermetic test suite into a single enforcement engine; emits per-run attestation JSON at `.lava-ci-evidence/verify-all/<UTC-timestamp>.json` with per-gate pass/fail/duration); wired into `scripts/ci.sh` step 5a4. Hermetic test: `tests/check-constitution/test_verify_all_rules.sh` (4 falsifiability fixtures including the §11.4.32-mandated paired-mutation that plants a known violation + asserts sweep reports FAIL). PER §11.4.32 ITSELF: this gate is "the enforcement engine for every other §11.4.x and CONST-NNN rule" — without it, new rules cascade as anchors but never get enforced in the codebase | ✅ wired (Phase 1 of constitution-compliance plan, commit `4def2da7`) |
| `CM-GITIGNORE-PRECOMMIT-AUDIT` | HelixConstitution §11.4.30 .gitignore + No-Versioned-Build-Artifacts Mandate | `scripts/check-gitignore-coverage.sh` (STRICT default; verifies every actual Gradle module + every owned-by-us submodule has .gitignore; verifies no tracked files match the forbidden-pattern set with explicit allowlist for documented-non-sensitive deployment configs); wrapped by verify-all sweep. Hermetic test: `tests/check-constitution/test_gitignore_coverage.sh` (3 fixtures: real-tree-passes, module-without-gitignore-rejected, clean-fixture-passes) | ✅ wired (Phase 2 of constitution-compliance plan) |
| `CM-NO-NESTED-OWN-ORG-SUBMODULES` | HelixConstitution §11.4.28 Submodules-As-Equal-Codebase Mandate | `scripts/check-no-nested-own-org-submodules.sh` (STRICT default; walks every owned submodule's `.gitmodules`; rejects entries pointing to vasic-digital / HelixDevelopment / red-elf / ATMOSphere1234321 / Bear-Suite / BoatOS123456 / Helix-Flow / Helix-Track / Server-Factory orgs on github.com or gitlab.com; per-entry waiver mechanism with mandatory rationale + tracking-issue + target-removal-date); wrapped by verify-all sweep in STRICT mode. Hermetic test: `tests/check-constitution/test_nested_own_org_submodules.sh` (4 fixtures: clean-fixture-passes, vasic-digital-chain-rejected, HelixDevelopment-gitlab-chain-rejected, advisory-mode-returns-zero). Phase 5-debt closed 2026-05-15: github cascade merge removed Challenges/.gitmodules (CONST-051(C) flat-layout enforcement); Panoptic no longer nested; scanner reports 0 violations in STRICT mode | ✅ wired + STRICT (Phase 5 of constitution-compliance plan) |
| `CM-CANONICAL-ROOT-CLARITY` + `CM-INSTALL-UPSTREAMS-RAN` | HelixConstitution §11.4.35 Canonical-Root Inheritance Clarity + §11.4.36 Mandatory install_upstreams | `scripts/check-canonical-root-and-upstreams.sh` (STRICT default; verifies (a) root CLAUDE.md + AGENTS.md open with `## INHERITED FROM constitution/<file>` or `@constitution/<file>` pointer in first 40 lines, (b) constitution submodule's three canonical files exist, (c) constitution submodule's own CLAUDE.md/AGENTS.md do NOT carry `## INHERITED FROM` at top level — fenced ` ```markdown ` code blocks correctly recognized as documentation; verifies every owned submodule has install_upstreams script at one of 6 supported locations; per-submodule waiver mechanism with mandatory rationale); wrapped by verify-all sweep in STRICT mode. Hermetic test: `tests/check-constitution/test_canonical_root_and_upstreams.sh` (6 fixtures including discrimination test for fenced-code-block false-positive prevention). Phase 8-debt closed 2026-05-15: 10 install_upstreams scripts landed (Challenges, Config, Containers, Discovery, HTTP3, Mdns, Middleware, RateLimiter, Recovery, Tracker-SDK each gained install_upstreams.sh + Upstreams/GitHub.sh + Upstreams/GitLab.sh per §6.W); scanner reports 16/16 install_upstreams present + all §11.4.35 sub-checks passing in STRICT mode | ✅ wired + STRICT (Phase 8 of constitution-compliance plan) |
| `CM-HELIX-DEPS-MANIFEST` | HelixConstitution §11.4.31 Submodule-Dependency-Manifest Mandate | `scripts/check-helix-deps-manifest.sh` (STRICT default; verifies (a) parent project root has `helix-deps.{yaml,json,toml}` with `schema_version: 1` + `deps:` + `transitive_handling:` blocks, (b) every owned-by-us submodule has its own `helix-deps.{yaml,json,toml}`; per-submodule waiver mechanism with mandatory rationale); wrapped by verify-all sweep in STRICT mode. Hermetic test: `tests/check-constitution/test_helix_deps_manifest.sh` (6 fixtures: compliant-fixture-passes, missing-parent-rejected, missing-submodule-rejected, wrong-schema-version-rejected, advisory-mode-returns-zero, json-variant-accepted). Phase 3-debt closed 2026-05-15: 16/16 per-submodule helix-deps.yaml manifests landed (Auth, Cache, Challenges, Concurrency, Config, Containers, Database, Discovery, HTTP3, Mdns, Middleware, Observability, RateLimiter, Recovery, Security, Tracker-SDK — each authored with honest per-submodule deps after analysis: 15 leaf modules with empty deps + Challenges declaring Panoptic with layout: flat); parent helix-deps.yaml declares 17 deps (16 vasic-digital + constitution); scanner reports 16/16 present in STRICT mode | ✅ wired + STRICT (Phase 3 of constitution-compliance plan) |
| `CM-CLOSURE-STATUS-VOCAB-COMPLIANCE` | HelixConstitution §11.4.33 Type-aware closure-status vocabulary | EQUIVALENCE-MAPPED per §6.AD.3 (Phase 9 Path B): Lava's `.lava-ci-evidence/sixth-law-incidents/<date>-<slug>.json` schema includes candidate-causes, ELIMINATED entries, and `PENDING_FORENSICS:` markers (per §11.4.6) that satisfy the type-aware closure-status semantics §11.4.33 prescribes. No standalone scanner — the §6.AD.3 mapping IS the binding equivalence per Path B. | ✅ equivalence-mapped (Phase 9 Path B of constitution-compliance plan) |
| `CM-REOPENED-SOURCE-ATTRIBUTION` | HelixConstitution §11.4.34 Reopened-source attribution | EQUIVALENCE-MAPPED per §6.AD.3 (Phase 9 Path B): Lava's `.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md` closure-log schema includes root-cause + fix-commit-SHA + validation-test references — providing WHY + WHO + WHEN + WHICH-INCIDENT lineage for each closed item. Re-opens are tracked as new closure-log entries citing the prior log. No standalone scanner — the §6.AD.3 mapping IS the binding equivalence per Path B. | ✅ equivalence-mapped (Phase 9 Path B of constitution-compliance plan) |

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
