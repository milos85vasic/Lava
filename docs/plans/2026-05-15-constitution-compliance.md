# Constitution-Compliance Comprehensive Plan (2026-05-15)

**Trigger:** operator directive 2026-05-15: "fetch and pull constitution Submodule and process the whole project — check if we are respecting and following all mandatory rules and constraints from root (constitution Submodule) Constitution, AGENTS.MD and CLAUDE.MD. If we do not create comprehensive plan in phases for achieveing this!"

**Constitution pin advanced this cycle:** `cb27ed8c` → `464ada14` (12 new clauses: §11.4.25 through §11.4.36).

**Inheritance:** HelixConstitution Constitution.md / CLAUDE.md / AGENTS.md (canonical root per §11.4.35). Lava parent CLAUDE.md / AGENTS.md (consumer extensions).

**Anti-bluff posture:** every phase carries a falsifiability rehearsal contract per §11.4 + §1.1. Per-phase deliverable lists what would be a §11.4 spirit bluff to skip.

---

## Gap Inventory (audit result)

The 12 new constitution clauses introduce these binding rules. For each: status COMPLIANT / NON-COMPLIANT / EQUIVALENT (Lava has different but functionally-matching infrastructure per §6.AD.3) / PARTIAL.

| Clause | Subject | Lava status | Phase that closes |
|---|---|---|---|
| §11.4.25 | Full-Automation-Coverage Mandate (6 invariants per feature) | PARTIAL — 35 Challenges + unit tests + scanners exist; coverage ledger missing | Phase 7 |
| §11.4.26 | Constitution-Submodule Update Workflow | COMPLIANT for THIS pull (followed steps 1+2; steps 3+6+7 owed) | Phase 1 (validation) + Phase 0 (this cycle's pin bump) |
| §11.4.27 | No-Fakes-Beyond-Unit-Tests + 100%-Test-Type Coverage | NON-COMPLIANT — Challenges submodule present (✓), HelixQA NOT incorporated (✗); 14-test-type matrix gaps | Phase 4 |
| §11.4.28 | Submodules-As-Equal-Codebase + Decoupling + Dependency-Layout | PARTIAL — 16 vasic-digital subs are §6.W mirrored, but `Submodules/` capital-S layout violates §11.4.29; nested-own-org-chains audit owed | Phase 5 + Phase 6 |
| §11.4.29 | Lowercase-Snake_Case-Naming | NON-COMPLIANT — `Submodules/` (capital S) + 16 CamelCase submodule dirs; references throughout | Phase 6 |
| §11.4.30 | .gitignore + No-Versioned-Build-Artifacts | PARTIAL — root + most modules have .gitignore; `CM-GITIGNORE-PRECOMMIT-AUDIT` gate missing | Phase 2 |
| §11.4.31 | Submodule-Dependency-Manifest (helix-deps.yaml) | NON-COMPLIANT — none of the 16 vasic-digital submodules ship helix-deps.yaml | Phase 3 |
| §11.4.32 | Post-Constitution-Pull Validation (`scripts/verify-all-constitution-rules.sh`) | NON-COMPLIANT — CRITICAL: this is the enforcement engine; without it, every other §11.4.x rule cascades as anchor-only | Phase 1 (highest priority) |
| §11.4.33 | Type-aware closure-status vocabulary | EQUIVALENT — Lava uses CONTINUATION + closure-logs (§6.AD.3 mapping); needs explicit amendment to cover the new vocabulary | Phase 9 |
| §11.4.34 | Reopened-source attribution | EQUIVALENT — same as §11.4.33; sixth-law-incidents JSONs already capture similar fields | Phase 9 |
| §11.4.35 | Canonical-root inheritance clarity | LIKELY-COMPLIANT — root CLAUDE.md + AGENTS.md carry `## INHERITED FROM constitution/` pointer-block; `CM-CANONICAL-ROOT-CLARITY` gate missing | Phase 8 (verification) |
| §11.4.36 | Mandatory install_upstreams on clone/add | NON-COMPLIANT — `install_upstreams` is operator-host-side via `.bashrc`/`.zshrc`; no Lava-side mechanical verification that the operator ran it on each submodule clone | Phase 8 |

Prior gates wired (carried forward, NOT counted as gaps from this cycle):
- §6.AB Layer 1 marker + Layer 2 body assertion ✅
- §6.AC non-fatal coverage scanner (STRICT) ✅
- §6.AD HelixConstitution inheritance + per-scope pointer-block propagation ✅
- §6.AE per-feature Challenge coverage scanner (STRICT) ✅
- 41+ mechanical anti-bluff enforcement points ✅

---

## Phased Plan (in priority order)

Each phase: deliverables, falsifiability rehearsal contract, owner, dependency on prior phases, estimated scope.

### Phase 0 — Pin advance + plan land (THIS CYCLE)

**Status:** ✅ DONE (commit `ed16debd` advanced constitution pin; this commit lands the plan)

**Deliverable:** constitution submodule advanced to 464ada14 + this plan committed.

**Honest gap acknowledgment:** §11.4.32's pre-pull validation could not run because the validation script doesn't exist (gap ack'd in commit body of `ed16debd`).

---

### Phase 1 — §11.4.32 enforcement engine (HIGHEST PRIORITY)

**Why first:** §11.4.32 is "the enforcement engine for every other §11.4.x and CONST-NNN rule — without it, new rules cascade as anchors but never get enforced in the codebase". Every other phase depends on this.

**Deliverable:**
- `scripts/verify-all-constitution-rules.sh` — walks every constitution rule with a programmatic gate; runs each gate against current tree; produces structured report
- Hermetic test under `tests/check-constitution/test_verify_all_rules.sh` — paired mutation per rule (plant a known violation; assert sweep reports FAIL for the planted gate)
- Pre-push integration via `.githooks/pre-push` Check 10 (advisory initially)
- `docs/scripts/verify-all-constitution-rules.sh.md` per §11.4.18

**Falsifiability rehearsal contract (mandatory per §11.4.32 itself):**
- For each enforced gate, plant a known violation; assert sweep reports FAIL; revert; assert sweep reports PASS

**Estimated scope:** ~300-400 lines of bash; 8-10 hermetic test cases. Single commit.

**Anti-bluff posture:** the script's OWN existence + failing-on-mutation behavior IS the proof; a `verify-all` script that exits 0 without running every implementable gate is itself a §11.4.32 violation.

---

### Phase 2 — §11.4.30 .gitignore audit gate (`CM-GITIGNORE-PRECOMMIT-AUDIT`)

**Deliverable:**
- `scripts/check-gitignore-coverage.sh` — walks every module/submodule/service; verifies `.gitignore` is present; verifies it covers the 6 forbidden classes (build artefacts, cache files, temp files, sensitive-data files, generated reports/logs, OS/IDE personal state); verifies no tracked files match forbidden patterns
- Pre-push hook integration (Check 11)
- Hermetic test under `tests/pre-push/check11_gitignore_test.sh`

**Falsifiability rehearsal:** plant a `.env` file in tracked content; assert gate fires + commit rejected.

**Estimated scope:** ~200 lines of bash; 4 hermetic test cases (no-gitignore-rejected, missing-patterns-rejected, tracked-forbidden-file-rejected, clean-tree-accepted).

---

### Phase 3 — §11.4.31 helix-deps.yaml authorship

**Deliverable:**
- For each of the 16 vasic-digital submodules + the constitution submodule + (later) HelixQA: author `helix-deps.yaml` in the submodule's root listing its own-org dependencies
- Schema per §11.4.31: `schema_version`, `deps: [{name, ssh_url, ref, why, layout: flat|grouped}]`, `transitive_handling.recursive: true`, `transitive_handling.conflict_resolution: operator-required`, `language_specific_subtree: bool`
- Audit in main repo: `scripts/check-submodule-deps-manifest.sh` walks every submodule + verifies helix-deps.yaml presence + schema-validity
- Per submodule: separate commit + push to GitHub + GitLab
- Parent pin bumps in single consolidating commit (per §6.AD propagation pattern)

**Falsifiability rehearsal:** delete one submodule's helix-deps.yaml; assert audit fires + names the missing manifest.

**Estimated scope:** 17 submodule commits + 1 parent commit + 1 audit script. Significant scope; should be its own session-segment.

**Dependency on:** Phase 1 (enforcement engine) wires this audit.

---

### Phase 4 — §11.4.27 100% test-type coverage + HelixQA incorporation

**Two sub-phases:**

**4a — HelixQA submodule incorporation:**
- Add HelixQA via `git submodule add git@github.com:HelixDevelopment/HelixQA.git Submodules/HelixQA` (or `submodules/helix_qa` per §11.4.29)
- Pin frozen by default (per §6.AD)
- Update §6.AD inheritance + add §6.AF clause documenting HelixQA inheritance
- Per-scope inheritance pointer-blocks updated via `scripts/inject-helix-inheritance-block.sh`

**4b — 14-test-type matrix audit:**
- Inventory current test types in Lava (unit ✓ / integration ✓ / Challenges ✓ / hermetic-bash ✓ / parity ✓ / mutation ✗ / fuzz ✗ / load ✗ / security ✗ / DDoS ✗ / scaling ✗ / chaos ✗ / stress ✗ / performance ✗ / benchmarking ✗ / UI ✓ / UX ✗)
- Per missing type: write a "first instance" test + record in coverage ledger (Phase 7)
- HelixQA autonomous-session integration: configure HelixQA test-bank registration for Lava; run autonomous session; capture wire evidence

**Falsifiability rehearsal per type:** for each new test type, write at least one positive test + one negative test (deliberate-defect mutation that the test catches).

**Estimated scope:** MAJOR — 10+ new test types; could be a multi-session effort. Phase 4a is a single commit; Phase 4b is per-test-type incremental.

**Dependency on:** Phase 1 (enforcement engine), Phase 6 (naming — affects HelixQA placement path).

#### Phase 4 follow-ups (post-adoption integration)

**Phase 4 follow-up A (2026-05-16, commit `a61bd3d8`)** — Integration design doc covering 3 options. Lives at [`docs/plans/2026-05-16-helixqa-integration-design.md`](2026-05-16-helixqa-integration-design.md). Recommends Option 1 (shell-level wiring) first; Options 2 + 3 deferred.

**Phase 4 follow-up B (2026-05-16, commit `d94ade0d`)** — IMPLEMENTED Option 1: 11 HelixQA Challenge scripts wired into `scripts/run-challenge-matrix.sh` via shell glue + HELIX_DEV_OWNED waiver pattern for 4 existing scanners.

**Phase 4 follow-up C (2026-05-16)** — DESIGN-ONLY: Option 2 Go-package linking design. Lives at [`docs/plans/2026-05-16-helixqa-go-package-linking-design.md`](2026-05-16-helixqa-go-package-linking-design.md). Covers per-package public-API analysis, Lava-side adapter design under `lava-api-go/internal/qa/`, `go.mod` strategy (replace+pin vs tag), 4-phase rollout:

- **Phase 4-C-1:** `pkg/evidence` — smallest API surface; proves `go.mod` pattern + adapter wrapping
- **Phase 4-C-2:** `pkg/detector` — Android-adjacent (high Lava strategic value)
- **Phase 4-C-3:** `pkg/ticket` — REPLACES Lava's §6.O closure-log manual authoring with generated markdown
- **Phase 4-C-4:** `pkg/navigator` + `pkg/validator` — deepest integration; navigator MAY be SKIPPED if no Go-side consumer materializes

The design enumerates 10 operator-blocking open questions (Go version conflict, adapter naming, release-mode dep resolution, navigator skip-or-proceed, etc.) — operator approval required before Phase 4-C-1 implementation starts.

**Estimated scope for Phase 4-C:** 4 sessions (1 per sub-phase), assuming operator decisions land promptly + Go-version conflict resolved upfront.

---

### Phase 5 — §11.4.28 nested own-org submodule audit

**Deliverable:**
- `scripts/check-no-nested-own-org-submodules.sh` — walks every owned submodule's `.gitmodules`; rejects entries pointing to vasic-digital / HelixDevelopment / red-elf / ATMOSphere1234321 / Bear-Suite / BoatOS123456 / Helix-Flow / Helix-Track / Server-Factory orgs
- Pre-push hook integration (Check 12)
- Hermetic test under `tests/pre-push/check12_nested_submodules_test.sh`

**Audit data point owed:** check each of the 16 vasic-digital submodules' `.gitmodules` files; list any that violate. Operator action then closes via either (a) refactor the offending submodule to NOT nest, OR (b) explicit waiver per §11.4.28 carve-out.

**Falsifiability rehearsal:** add a nested own-org submodule entry to a synthetic test fixture; assert gate rejects.

**Estimated scope:** ~150 lines of bash; 3 hermetic test cases. Single commit.

---

### Phase 6 — §11.4.29 lowercase snake_case naming migration

**HUGE phase. Multi-step migration. The constitution itself (§11.4.29) mandates phased execution.**

**Phase 6 has its own dedicated sub-plan** at [`docs/plans/2026-05-16-snake_case-migration.md`](2026-05-16-snake_case-migration.md), prepared in the Phase 6.0 research+plan cycle (2026-05-16). Read that plan before executing any rename. Highlights:

- **Per-submodule target mapping table** — 17 owned-by-us submodules with their snake_case targets (`Auth` → `auth`, `RateLimiter` → `rate_limiter`, `Tracker-SDK` → `tracker_sdk`, `HelixQA` → `helix_qa`, etc.).
- **Audit tool** `scripts/audit-snake_case-references.sh` (read-only) + hermetic test `tests/check-constitution/test_audit_snake_case_references.sh` + companion doc `docs/scripts/audit-snake_case-references.sh.md` shipped in this commit. Run `bash scripts/audit-snake_case-references.sh` for the baseline reference count (currently 806 `Submodules/` refs across 124 files; highest-blast-radius: `Containers` 370 refs / 63 files).
- **LANG-spec exemption** explicitly documented: Kotlin/Android `app/`, `core/`, `feature/` subtrees, Gradle wrapper, dotfiles, `Dockerfile*` are EXEMPT from snake_case enforcement on their inner subtree.
- **HelixDevelopment-owned (HelixQA)** treated the same as vasic-digital — `helix_qa` snake_case is binding (HelixDevelopment is on the owned-org list).

**Sub-phases (summary; full detail in the sub-plan):**

**6.0 — Plan + audit tool** (THIS commit, 2026-05-16, research+plan only — ZERO renames executed).

**6a — `Submodules/` → `submodules/` rename + every reference updated** (top-level directory).

**6b — Per-submodule snake_case renames** (17 sub-cycles, risk-ascending; HIGH-RISK Containers + Tracker-SDK + RateLimiter last).

**6c — File-level renames** (Lava-side tracked files; most exempt under LANG-spec).

**6d — `Upstreams/` → `upstreams/` rename** (constitution submodule's `install_upstreams.sh` already supports dual-mode).

**6e — Mechanical enforcement gate** (`scripts/check-snake-case-naming.sh` + paired mutation).

**6f — Optional upstream Git repo renames** (defer — out of §11.4.29's local-path-only mandatory scope; operator decision).

**Falsifiability rehearsal (per cycle):** rename a directory back to CamelCase OR leave a single reference unrenamed; assert audit script / gate fires + names the offender.

**Estimated scope:** Phase 6.0 = 1 commit. Phases 6a + 6b/1-17 + 6c + 6d + 6e = at least 21 additional commits over multiple sessions. HIGH-RISK renames (Containers, Tracker-SDK) are full-session each.

**Dependency on:** All other phases that create new files MUST follow snake_case from inception.

**Risk:** Detailed in `docs/plans/2026-05-16-snake_case-migration.md` §6. Hardlinked `.git` backup required PER PHASE per §9.

---

### Phase 7 — §11.4.25 coverage ledger

**Deliverable:**
- `docs/coverage-ledger.md` — feature × platform × invariant-1..6 × status matrix
- Generator script: `scripts/build-coverage-ledger.sh` derives the ledger from existing data (Challenge tests + unit tests + scanner outputs)
- Per-cell evidence link (where possible)
- Maintained as part of release-gate sweeps (regenerated)
- Gaps tracked per §11.4.15

**Per §11.4.25, the 6 invariants are:**
1. Anti-bluff posture (captured runtime evidence per §7.1 + §11.4)
2. Proof of working capability end-to-end on the target topology (per §11.4.3)
3. Working implementation matching the documented promise
4. No open issues / bugs surfaced by the suite
5. Full documentation kept in sync per §11.4.12
6. Four-layer test floor per §1 (pre-build + post-build + runtime + paired mutation)

**Falsifiability rehearsal:** plant a feature row with all invariants ✓ but no actual underlying tests; assert ledger generator detects + flags the row.

**Estimated scope:** ~200 lines of bash + ~150 lines of generated markdown. Single session.

**Dependency on:** Phase 4b (test-type matrix) provides the data the ledger summarizes.

---

### Phase 8 — §11.4.35 + §11.4.36 verification gates

**§11.4.35 `CM-CANONICAL-ROOT-CLARITY`:**
- Verifies (a) consumer's CLAUDE.md opens with `@import` or `## INHERITED FROM constitution/CLAUDE.md`
- Verifies (b) the constitution submodule's three files are present
- Verifies (c) no `## INHERITED FROM` block in the constitution submodule's own files

**§11.4.36 `CM-INSTALL-UPSTREAMS-RAN`:**
- Verifies that for every owned-by-us submodule with `upstreams/` (or `Upstreams/`) populated, `install_upstreams` was run (evidence: per-submodule `git remote -v` shows multiple named remotes matching the recipe declarations)
- Operator-host-side dependency: the `install_upstreams` utility must be on `PATH`. The audit just verifies the RESULT of running it.

**Estimated scope:** ~150 lines bash + 4 hermetic tests. Single commit.

---

### Phase 9 — §11.4.33 + §11.4.34 — Type-aware closure-status + Reopened-source attribution

**Two paths to choose:**

**Path A — Migrate Lava to Issues.md / Fixed.md tracker structure**
- Substantial process change
- Requires building Issues.md / Issues_Summary.md / Fixed.md / Fixed_Summary.md generators
- Per-cycle maintenance burden
- Aligns Lava with HelixConstitution's prescribed tracker

**Path B — Formally amend §6.AD.3 to cover the new clauses' equivalence**
- Lava's existing CONTINUATION + closure-logs convention treated as equivalent
- §6.AD.3's mapping extended: Status vocabulary mapping (Bug→Fixed-equivalent, Feature→Implemented-equivalent, Task→Completed-equivalent); Reopened-source attribution mapped to closure-log fields
- §6.AD.3 entry in CLAUDE.md amended to explicitly cite §11.4.33 + §11.4.34 + §11.4.35 + §11.4.36

**Recommended:** Path B. Lower friction; preserves Lava's existing convention; the "equivalence is documented" pattern was operator-accepted in §6.AD.3 for the prior trackers (§11.4.15 + §11.4.16 + §11.4.19 + §11.4.21).

**Falsifiability rehearsal:** for whichever path, write a synthetic closure-record + verify the gate accepts it.

**Estimated scope:** Path B = single docs commit (~50 lines edit). Path A = multi-commit substantial work.

---

## Cross-cutting concerns

### Pin-bump cascade (§11.4.26 step 7)

Every phase that lands new clauses in this Lava CLAUDE.md MUST also propagate via §6.AD's pointer-block + scripts/inject-helix-inheritance-block.sh. The 54 per-scope docs already carry the pointer-block; new clauses inherit transitively.

### §11.4.32 sweep wiring

Phase 1 builds the sweep. Every subsequent phase MUST register its new gate(s) in the sweep so the master verifier runs them.

### §11.4.20 subagent delegation

Per §11.4.20: phases with multi-step scope (≥3 phases) should consider subagent delegation. Phases 4, 6 are highest priority for subagent partitioning.

### Prior-cycle wins carried forward

This plan does NOT regress the 32-cycle prior work. All 41+ mechanical anti-bluff gates remain. The Phase 1 sweep WRAPS them, doesn't replace them.

---

## Suggested execution order

**Immediate** (this session, after this commit):
- Phase 0 ✅ DONE
- Phase 1 — verify-all sweep (HIGHEST priority)
- Phase 2 — gitignore audit gate
- Phase 5 — nested-own-org audit gate
- Phase 8 — canonical-root + install_upstreams gates
- Phase 9 (Path B) — §6.AD.3 equivalence amendment

**Next session(s)** (operator-driven):
- Phase 3 — helix-deps.yaml on 16 submodules (per-submodule commits + pin bumps)
- Phase 4a — HelixQA submodule incorporation ✅ DONE (commit `aa0db6bd`)
- Phase 4 follow-up A — integration design ✅ DONE (commit `a61bd3d8`)
- Phase 4 follow-up B — Option 1 shell-level wiring ✅ DONE (commit `d94ade0d`)
- Phase 4 follow-up C — Option 2 Go-package linking design ✅ DONE (this commit)
- Phase 4-C-1..4 — Option 2 Go-package linking IMPLEMENTATION (4 sessions; operator-blocking on 10 open questions per design doc §G)
- Phase 4b — 14-test-type matrix gap-fill (long tail; multi-session)

**Major rename cycle** (operator-coordinated, dedicated session):
- Phase 6 — lowercase snake_case migration (HUGE; needs its own sub-plan + extensive testing)
- Phase 7 — coverage ledger (depends on Phase 4b for data)

---

## Honest scope statements

**What this plan IS:**
- Comprehensive gap inventory against the new 12 clauses
- Phased deliverables with anti-bluff falsifiability rehearsal contracts per phase
- Suggested execution order
- Cross-cutting-concerns inventory

**What this plan IS NOT:**
- An implementation
- A guarantee that any phase will land in any specific time window
- A claim that all gaps are equally addressable on this host (Phase 4b's UI/UX/security/etc tests may need infrastructure not yet on this host; per §6.X-debt the macOS-emulator-stall continues to block real-emulator UI execution)

**What blocks this plan:**
- §6.X-debt darwin/arm64 emulator gap continues to block actual UI Challenge execution (orthogonal to the plan; affects Phase 4b's test-type runs)
- 3 vasic-digital submodules (Challenges, Containers, Security) have UPSTREAM AHEAD of local pin — operator's call whether to pull (per §6.AD pins-frozen rule, deferred)

**Anti-bluff posture of this plan:**
This plan IS the deliverable per the operator's directive ("create comprehensive plan in phases for achieveing this!"). Per §6.J: every phase carries a falsifiability rehearsal contract. Per §11.4.32: Phase 1 is the enforcement engine without which subsequent phases would be anchor-only. Per §11.4.20: phases are subagent-partitionable.

**Classification:** project-specific (the plan's specific phases + Lava-specific paths are project-specific; the discipline of "phased plan with falsifiability rehearsal per phase" is universal per HelixConstitution §11.4 + the established Lava §6.J/§6.L pattern).
