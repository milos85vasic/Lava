# Governance / Constitution Debt Status Audit

**Date:** 2026-06-14
**Scope:** Read-only verification of every `§6.*-debt` entry + `CM-*` paper-only/owed gate in root `CLAUDE.md`, cross-checked against the actual code/scripts/hooks at HEAD `357f867a`.
**Method:** Each row's "actual-status" is verified against a real artifact (file existence, hook line, verify-all wiring), not against the CLAUDE.md prose. Where CLAUDE.md prose disagrees with the artifact, the row is flagged STALE.
**Anti-bluff (§11.4.6):** Every claim is cited to a file/line or "absent". No guessed status.

---

## Debt-item status table

| debt-id | requires | actual-status | evidence (file:line / absent) | est-effort | blocking? |
|---|---|---|---|---|---|
| §6.X-debt | Emulator PID inside podman/docker container managed by `pkg/runtime`; `--runner=containerized` for gate runs on every OS | **PARTIAL** | `submodules/containers/pkg/emulator/containerized.go` exists with `kvmDevicePath`/`kvmAvailable` logic (`containerized.go:14-35`); but the gate path on macOS still resolves `RESOLVED_RUNNER="host-direct"` (`scripts/run-challenge-matrix.sh:198,215`). Linux x86_64+KVM path works; macOS gate-host BLOCKED. Superseded in practice by §6.AH-debt. | L | YES (blocks §6.AE/§6.Z gate runs on macOS) |
| §6.Y-debt | Pre-push hook rejects code-touching commit after a distribute that did not bump versionCode | **CLOSED** | `.githooks/pre-push:128-157` Check 6 implements §6.Y bump-first ordering against `last-version-debug`. CLAUDE.md still lists it OWED → STALE. | — | no |
| §6.Z-debt | firebase-distribute Phase-1 Gate 6 (evidence file: matching SHA + ≤24h + BUILD SUCCESSFUL) + pre-push check + `tests/firebase/` hermetic test | **PARTIAL** | Pre-push Check 7 enforces evidence-file presence on pointer advance (`.githooks/pre-push:159-193`). But `scripts/firebase-distribute.sh` has NO runtime Gate 6 that validates SHA/timestamp/BUILD-SUCCESSFUL — only a comment reference (`firebase-distribute.sh:40-41`). `tests/firebase/` exists but tests version-monotonicity/pepper, not the §6.Z evidence gate (`tests/firebase/`). | M | partial (release-distribute risk) |
| §6.AA-debt | Default `--debug-only`; reject `--release-only` without companion debug evidence; split `last-version-{debug,release}`; `tests/firebase/` hermetic test | **CLOSED** | `firebase-distribute.sh:46` `MODE="debug"` default; `:199-210` rejects `--release-only` when `last-version-debug < current vc`; split pointers `:150,171`. CLAUDE.md still lists PARTIAL → STALE. | — | no |
| §6.AB-debt | Detekt/go-vet rule for per-feature anti-bluff completeness (rendering/state-machine/gating) | **OPEN** | No Detekt rule; `config/detekt/` contains only `detekt.yml` (`config/detekt/` listing). The related `scripts/check-challenge-discrimination.sh` exists+STRICT but covers Challenge falsifiability markers, not the §6.AB completeness checklist. | M | no |
| §6.AC-debt | Detekt rule `config/detekt/lava-non-fatal-required.kts` + go-vet `lava-api-go/scripts/check-non-fatal-coverage.sh` + pre-push + `tests/non-fatal-coverage/` | **OPEN** | `config/detekt/lava-non-fatal-required.kts` ABSENT; `lava-api-go/scripts/check-non-fatal-coverage.sh` ABSENT; `tests/non-fatal-coverage/` ABSENT. A bash scanner `scripts/check-non-fatal-coverage.sh` exists (Kotlin-only, STRICT) but the Go vet + Detekt custom rule + hermetic tests are not built. | M | no |
| §6.AD-debt (1) per-submodule pointer propagation | `## INHERITED FROM` block in all per-scope CLAUDE.md/AGENTS.md/CONSTITUTION.md | **CLOSED** | `scripts/check-constitution.sh` 6.AD(4) enforces 54 in-scope files (`docs/helix-constitution-gates.md:56`). | — | no |
| §6.AD-debt (2) `CM-COMMIT-DOCS-EXISTS` | verify referenced docs in commit messages resolve | **CLOSED** | `scripts/check-commit-docs-exists.sh` exists + wired in verify-all (`scripts/verify-all-constitution-rules.sh:190`). CLAUDE.md lists OWED → STALE. | — | no |
| §6.AD-debt (2) `CM-SCRIPT-DOCS-SYNC` | script ↔ docs/scripts/*.md sync gate | **CLOSED** | `scripts/check-script-docs-sync.sh` exists + verify-all `:187`; AND pre-push Check 9 (`.githooks/pre-push:195-212`). CLAUDE.md lists OWED → STALE. | — | no |
| §6.AD-debt (2) `CM-SUBAGENT-DELEGATION-AUDIT` | subagent delegation audit trail | **CLOSED (scanner)** | `scripts/check-subagent-delegation-audit.sh` exists + verify-all `:193`. Gate-doc + CLAUDE.md call it paper-only → STALE. | — | no |
| §6.AD-debt (2) `CM-COMMIT-DOCS-EXISTS`/others equivalence-mapped | `CM-ITEM-STATUS/TYPE-TRACKING`, `CM-FIXED-COLUMN-ALIGNMENT`, operator-blocked gates | **CLOSED-BY-EQUIVALENCE** | §6.AD.3 Path B maps these to `.lava-ci-evidence/` ledgers (`docs/helix-constitution-gates.md:14,16,19-22,36-37`). Intentional; no scanner owed. | — | no |
| §6.AD-debt (5) build-resource stats tracker | `CM-BUILD-RESOURCE-STATS-TRACKER` | **CLOSED** | `scripts/build-stats-sample.sh` + `scripts/build-stats-report.sh` + registry exist (`docs/helix-constitution-gates.md:18`). CLAUDE.md §6.AD-debt-5 still lists OWED → STALE. | — | no |
| §6.AE-debt | `check-challenge-coverage.sh` strict-mode flip + per-feature backfill | **CLOSED (strict)** | `scripts/check-challenge-coverage.sh:39` `STRICT="${LAVA_CHALLENGE_COVERAGE_STRICT:-1}"` (STRICT default); verify-all invokes it (`:163`). Gate-doc says "advisory" → STALE. Runner host-gap is §6.X/§6.AH, not §6.AE. | — | no |
| §6.AF-debt (2) chaos/stress §11.4.85 (LVA-7) | runnable chaos/stress beyond phase-1 lava-api-go | **PARTIAL** | `scripts/run-chaos-stress.sh` exists (63 lines, scaffold); `docs/chaos-stress/DESIGN.md` + `EVIDENCE-phase1.md` exist. NOT wired into verify-all (no `chaos` gate in `scripts/verify-all-constitution-rules.sh`). Phase-2+ owed. | M | no |
| §6.AF-debt (LVA-6) codegraph own-org submodule inclusion | §11.4.79 — index own-org submodule SOURCE | **CLOSED** | `.codegraph/config.json:3` `_policy` records LVA-6 CLOSED 2026-05-31 (1,842 submodule files indexed; cross-submodule probe resolves); blanket `submodules/**` exclude removed. CLAUDE.md §6.AF lists OWED → STALE. | — | no |
| §6.AF-debt (4) per-clause `CM-COVENANT-114-*-PROPAGATION` gates | literal-anchor scan gates for §11.4.79–106 | **OPEN** | Only `CM-COVENANT-114-16-PROPAGATION` row present (`docs/helix-constitution-gates.md:14`); no per-clause propagation scanner for the new 79–106 anchors. | S | no |
| §6.AF-debt (5) §11.4.80 codegraph sync automation | auto `git submodule init` before index | **OPEN** | Precondition documented in `.codegraph/config.json:3` but no automation script that runs `git submodule init` before `codegraph index`. | S | no |
| §6.AG (no separate -debt) | Containers-driven emulators, no live ADB device | **PARTIAL** | Policy is in CLAUDE.md §6.AG; runner enforced via `run-challenge-matrix.sh`; but the no-host-direct requirement is superseded/tightened by §6.AH and blocked by §6.AH-debt. | — | inherits §6.AH |
| §6.AH-debt | Container/VM emulator path that BOOTS on macOS (no-KVM TCG mode + macOS /proc-reap fix in Containers submodule); no host-direct fallback | **OPEN** | `containerized.go` hard-requires `/dev/kvm` for acceleration (`containerized.go:19-21,34`); no TCG/no-KVM software-emulation mode; `run-challenge-matrix.sh:215` still resolves macOS→`host-direct`. Incident recorded `.lava-ci-evidence/sixth-law-incidents/2026-06-03-emulator-boot-offline.json` (present). §6.Z release canary + client gate honestly BLOCKED on this macOS host. | L | YES (blocks all macOS gate/release-canary runs) |
| §6.AI-debt (1) §11.4.128 device recorder | background Genymotion/emulator recorder w/ deterministic layout | **PARTIAL/CLOSED?** | `scripts/record-device-session.sh` exists; `tests/device-recording/` exists. Whether it implements the full deterministic `YYYY-MM-DD/<state-hash>/...` layout + gitignore/codegraph-exclude was not fully verified — marked PARTIAL pending deeper check. | S | no |
| §6.AI-debt (2) §11.4.140 LAYER 2 action-prefix hook | `UserPromptSubmit` hook + generated slash commands | **CLOSED (hook)** | `scripts/hooks/action-prefix-expand.sh` exists + wired in `.claude/settings.json:14-22` (`UserPromptSubmit`); `constitution/actions/registry.yaml` present. Generated per-agent slash commands not verified. CLAUDE.md lists OWED → STALE for the hook portion. | S | no |
| §6.AI-debt (3) §11.4.141 thin-index restructure + token harness | de-dup 90K-token CLAUDE.md to index lines | **OPEN** | No evidence of a thin-index restructure; root `CLAUDE.md` remains a full-body document. | L | no |
| §6.AI-debt (4) per-clause `CM-COVENANT-114-{128..141}-PROPAGATION` gates | literal-anchor gates for §11.4.128–141 | **OPEN** | Not present in `docs/helix-constitution-gates.md` gate inventory. | S | no |
| `CM-COVERAGE-LEDGER` | §11.4.25 — strict-flip after baseline (Phase 7-debt) | **CLOSED (strict)** | `scripts/check-coverage-ledger.sh:41` `STRICT=${LAVA_COVERAGE_LEDGER_STRICT:-1}`; verify-all `:156` invokes `--strict`. Gate-doc + CLAUDE.md mark it "advisory / STRICT flip OWED" → STALE. | — | no |
| `CM-LVA-TICKETS-SYNC` → `CM-WORKABLE-ITEMS-SYNC` | canonical workable-items binary; strict-flip | **PARTIAL** | `scripts/check-workable-items.sh` exists + verify-all `:202`. Gate-doc still names the retired `CM-LVA-TICKETS-SYNC`/`scripts/check-lva-tickets.sh` (`docs/helix-constitution-gates.md:25`) → STALE; CLAUDE.md §6.AF notes migration to canonical done. Upstream `update`/`reopen`/`block` + export pipeline OWED (non-blocking). | S | no |
| `CM-COVENANT-114-16-PROPAGATION` + item-tracking paper-only gates | §11.4.15/16/19/21 trackers | **CLOSED-BY-EQUIVALENCE** | Intentional Path-B mapping (`docs/helix-constitution-gates.md:14,19-24`). Not true debt. | — | no |

---

## STALE-claim findings (CLAUDE.md/gate-doc says OPEN but artifact is CLOSED)

These are documentation drift, not real work. A master plan should NOT schedule work for them — only doc corrections:

1. **§6.Y-debt** — pre-push Check 6 is live (`.githooks/pre-push:128`). CLAUDE.md lists OWED.
2. **§6.AA-debt** — default flipped to `--debug-only`; release-only companion check live (`firebase-distribute.sh:46,199`). CLAUDE.md lists PARTIAL.
3. **§6.AD-debt items 2** — `check-commit-docs-exists.sh`, `check-script-docs-sync.sh`, `check-subagent-delegation-audit.sh` all exist + verify-all-wired + pre-push Check 9. Gate-doc marks several ⚠️ paper-only.
4. **§6.AD-debt item 5** — build-stats tracker shipped (`build-stats-sample.sh`/`build-stats-report.sh`). CLAUDE.md still lists OWED.
5. **§6.AE-debt** — `check-challenge-coverage.sh` is STRICT-default (`:39`). Gate-doc says advisory.
6. **`CM-COVERAGE-LEDGER`** — STRICT-default (`check-coverage-ledger.sh:41`, verify-all `--strict`). CLAUDE.md + gate-doc say advisory / Phase-7-debt OWED.
7. **LVA-6 (codegraph submodules)** — CLOSED per `.codegraph/config.json:3`. CLAUDE.md §6.AF lists OWED.
8. **§6.AI LAYER 2 action hook** — wired in `.claude/settings.json:14`. CLAUDE.md lists OWED.
9. **`docs/helix-constitution-gates.md` itself is stale** — "Last updated 2026-05-16"; predates §6.AF/AG/AH/AI (May 31 – Jun 9). Several rows describe pre-strict-flip state.

---

## Truly-open debts (real work to schedule)

| debt-id | one-line | effort | blocking |
|---|---|---|---|
| **§6.AH-debt** | macOS container/VM (TCG no-KVM) emulator path that actually boots; no host-direct fallback | L | YES |
| **§6.X-debt** | container-bound emulator gate path on macOS (folds into §6.AH) | L | YES |
| **§6.AC-debt** | Detekt `lava-non-fatal-required.kts` + Go-vet `check-non-fatal-coverage.sh` + `tests/non-fatal-coverage/` | M | no |
| **§6.AB-debt** | Detekt/go-vet per-feature anti-bluff completeness rule | M | no |
| **§6.Z-debt (residual)** | runtime Gate 6 inside `firebase-distribute.sh` (SHA/24h/BUILD-SUCCESSFUL) + hermetic test | M | partial |
| **§6.AF-debt chaos (LVA-7)** | chaos/stress §11.4.85 beyond phase-1; wire into verify-all | M | no |
| **§6.AI-debt §11.4.141** | thin-index restructure of CLAUDE.md + token-reduction harness | L | no |
| **§6.AF/AI per-clause `CM-COVENANT-*-PROPAGATION` gates** | literal-anchor scanners for §11.4.79–141 | S | no |
| **§6.AF-debt §11.4.80** | auto `git submodule init` before codegraph index | S | no |
| **§6.AI-debt §11.4.128** | verify/complete deterministic device-recorder layout | S | no |

---

## Prioritized recommendations

1. **§6.AH-debt + §6.X-debt (TOP — both BLOCKING).** Single coordinated effort in the Containers submodule: add a no-KVM TCG software-emulation runner + the macOS `/proc`-reap fix so the container/VM emulator path boots on darwin/arm64. Until this lands, EVERY §6.AE matrix gate, §6.Z release canary, and tag-time attestation on the operator's macOS host is honestly BLOCKED — this is the single highest-leverage debt because it unblocks the entire release pipeline.
2. **§6.Z-debt residual (firebase Gate 6).** Add the runtime evidence gate inside `firebase-distribute.sh` (the pre-push Check 7 only fires on the commit that advances the pointer, not at distribute time). Cheaper than #1 and closes a real distribute-faulty-version vector.
3. **§6.AC-debt (non-fatal telemetry lint).** Build the Detekt custom rule + Go-vet check + `tests/non-fatal-coverage/`. Currently only a Kotlin bash scanner exists; the Go side has zero enforcement. Pairs naturally with §6.AB-debt (same Detekt-rule-authoring effort).

**Doc-hygiene action (not scheduled work, but should precede the plan):** correct the 9 STALE claims above in `CLAUDE.md` + regenerate `docs/helix-constitution-gates.md` (stale since 2026-05-16) so the master plan is built on accurate status. Several debts are already done.

**Open vs closed count:** Of ~27 distinct debt/gate items examined: **10 truly-open** (2 BLOCKING, 1 partial-blocking, 7 non-blocking), **3 PARTIAL**, **~14 CLOSED or closed-by-equivalence** (9 of which are STALE-marked-open in CLAUDE.md/gate-doc).
