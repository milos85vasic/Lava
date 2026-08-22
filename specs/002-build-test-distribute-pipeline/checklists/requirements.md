# Specification Quality Checklist: Local Build-Test-Distribute Pipeline

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- **Re-validated 2026-08-21 after `/speckit-clarify`**: 16/16 → 16/16 items passing (no regressions,
  no newly-passing items — all 16 already passed after `/speckit-specify` and remain true against
  the updated spec). Four additional clarifications were integrated this pass (starting-state
  precondition, no fixed time ceiling, always-restart-from-scratch on interruption, consolidated
  run report) — see spec.md's "Clarifications" section, Session 2026-08-21, questions 3–6.
- Both `[NEEDS CLARIFICATION]` markers raised during drafting were resolved via an explicit
  question to the operator (2026-08-21), not by an assumed default — see spec.md's
  "Clarifications" section. Both resolutions ("fully unattended debug+release distribution"
  and "advance every submodule pin automatically") deliberately relax existing hard rules in
  root `CLAUDE.md` (§6.AA Two-Stage Distribute Mandate, Seventh Law clause 3 Pre-Tag
  Real-Device Attestation, and the Decoupled Reusable Architecture's submodule-pin-freeze
  rule). This is flagged as a load-bearing prerequisite in spec.md's "Constitutional Impact"
  note — `/speckit-plan`'s Constitution Check gate MUST treat the required amendments as
  in-scope planning work, not silently proceed as if the current constitution already permits
  this behavior.
- A few FR/entity items reference "the anti-bluff policy" and "machine evidence" as
  domain concepts inherited directly from this project's own governing documents rather than
  as implementation detail — this is treated as acceptable domain vocabulary for this
  particular project rather than a content-quality violation, since the feature's entire
  purpose is to operationalize that existing policy.
- Deferred to planning (low-impact, not asked as clarification questions): the exact identity/
  uniqueness scheme for a Pipeline Run when re-invoked against the same commit, Firebase-specific
  external-failure-mode handling (rate limits, auth expiry), and data-volume assumptions — none
  of these change this feature's architecture or acceptance criteria at the spec level.
- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`.

### Implementation status (recorded 2026-08-21, task T063)

**This records progress, not completion. The feature is NOT complete, and reporting it as
complete would be exactly the false-status failure this project's Anti-Bluff Pact (§6.J/§6.L)
exists to prevent.**

- **48 of 63 tasks complete** — counted mechanically from `tasks.md` (48 `[x]`, 15 `[ ]`),
  not estimated. **CORRECTED 2026-08-21 during the design-doc reconciliation pass**: this
  section recorded 43/63 (43 `[x]`, 20 `[ ]`), which was accurate when written and had since
  drifted. Two of the five newly-closed tasks are named below.
- **Phase 1 (Setup) and Phase 2 (Foundational): COMPLETE** — zero open tasks in either.
- **User Story 1 (build + test): complete except T031**, the `quickstart.md` Scenario 2/3
  end-to-end run. **CORRECTED 2026-08-21**: this used to add that T028 (the
  constitutional-gate-sweep wrapper) "is in flight in a parallel work stream; its deliberate
  falsifiability mutation is live in the working tree at the time of writing, so a
  `scan-no-hardcoded-ipv4.sh` violation in `Upstreams/GitHub.sh` is expected during that
  window and is not a defect." T028 has since closed, the mutation was reverted, and that
  scanner violation should **no longer** be treated as expected — if it reappears it is a
  real §6.R finding, not a rehearsal in progress.
- **User Story 2 (live services): complete.** **CORRECTED 2026-08-21**: this used to read
  "complete except T037 ... in progress in a parallel work stream. Until it lands, a green
  `live_verify` phase means 'the Go API is live and serving', **not** 'both live surfaces
  are proven'." T037 has landed as `scripts/pipeline/phase-04-live-verify-api-app.sh`, and
  both halves are wired: the orchestrator's `live_verify` phase owns an ordered pair of
  scripts and halts at the first failure, so a green `live_verify` now does mean both live
  surfaces were proven. The api-app half establishes §6.AH by checked fact rather than by
  log-line assertion — a poller samples the emulator container's running state throughout
  the run, and a run in which no running emulator container was ever observed is a FAIL —
  and it requires Gradle's own host-side JUnit XML and the Containers attestation row to
  agree, treating disagreement between them as a failure in itself. One honest caveat
  remains: that half runs ONE AVD (API 34 phone), the only emulator image cached on this
  host, so it is a live verification and **not** the §6.AE.2 release gate matrix.
- **User Story 3 (distribute + docs): the non-gated parts (T042, T044, T045) are complete;
  the distribute step is BLOCKED.** T043 (`phase-05-distribute.sh`), T046 (wiring) and T047
  (falsifiability run) all sit behind T040/T041 — unapproved constitutional amendments to
  `CLAUDE.md` §6.AA (Two-Stage Distribute Mandate) and the Seventh Law clause 3 (Pre-Tag
  Real-Device Attestation), plus the matching MAJOR bump of `.specify/memory/constitution.md`.
  The orchestrator therefore cannot distribute anything by construction: asking for a phase
  that is not wired (e.g. `--until distribute`) is a usage error (exit 2), never a silent
  no-op.
- **User Story 4 (repository closure): the TDD cycle T050–T053 is complete against disposable
  fixture repositories only** (36/36 assertions green in `tests/pipeline/test_advance_all_submodules.sh`).
  T054 — the mandatory human review gate (`plan.md` Human Checkpoint #2, the highest
  blast-radius component in this feature) — and everything downstream (T055, T056) are
  BLOCKED. Nothing in US4 has been run against a real submodule upstream. T048/T049 (the
  Decoupled Reusable Architecture carve-out) also remain unapproved.
- **Phase 7 (Polish): not started**, apart from the documentation tasks closed in this pass
  (T059, T060, T063) and the T038 orchestrator wiring. T062 — the full end-to-end run on a
  disposable branch (`plan.md` Human Checkpoint #3) — remains open, and this pipeline has
  deliberately never been run against real `master`. T031 (the `quickstart.md` Scenario 2/3
  end-to-end run), T057, T058 and T061 also remain open.

### Design-document reconciliation (recorded 2026-08-21)

`contracts/cli-contract.md`, `data-model.md`, `quickstart.md`, `research.md` and `plan.md`
have each been reconciled against the shipped implementations. Where a document and the code
disagreed, the code won and the document was corrected in place with the correction called
out; `research.md` and `spec.md` are historical design rationale and received dated notes
appended beneath the original analysis rather than rewrites. Three findings from that pass
are worth surfacing at checklist level, because each is a case of a **stated rule that could
not fail on the input it existed to catch**:

- `report.json`'s `evidence_summary.rejected_by_anti_bluff` — the counter the run-level
  anti-bluff rule reads — was seeded to zero and never written by anything, so a run whose
  Evidence Records had been REJECTED could still finalize `PASS`.
- `phase-02-test.sh` reported PASS on **zero** Evidence Records: no wrapper failed, no record
  said FAIL, none was REJECTED, because there were none.
- The evidence validator's `raw_output_ref` rule ("must exist and be non-empty") was satisfied
  by a **directory**: an absent or empty reference resolved to the record's own directory,
  which exists and whose inode has a non-zero size, so a record that asserted nothing and
  pointed at nothing was stamped `validated`.

All three are fixed and locked down by hermetic suites under `tests/pipeline/`. Recorded here
because the checklist's Requirement Completeness items ("requirements are testable",
"success criteria are measurable") were all marked passing while these three rules were, in
practice, unfalsifiable — a reminder that a requirement being *stated* testably is not the
same as its implementation being *able to fail*.

**Evidence directories**

- `.lava-ci-evidence/pipeline-runs/<UTC-run-id>/` — one fresh timestamped directory per
  invocation, each holding `report.json` (schema:
  `contracts/pipeline-run-report.schema.json`), a `phase-NN/` subdirectory per phase, and
  that phase's Evidence Records. Gitignored (`.gitignore` line 59, task T003) so the
  pipeline's own output cannot dirty the tree it is about to test and trip its own FR-000
  clean-tree precondition. Per `research.md` R-010 a later run never reads a prior run's
  directory as input (FR-018).
- `tests/pipeline/` — the hermetic suites behind the phase library, the evidence aggregator
  and the orchestrator, each built RED-then-GREEN.
- `docs/scripts/pipeline-build-test-distribute.sh.md` — the orchestrator's user guide; its
  "Verification record" section lists the disposable-fixture runs that were actually
  executed, and its wired/not-wired table is the authoritative statement of current
  capability.
- `specs/002-build-test-distribute-pipeline/tasks.md` — the authoritative task ledger the
  counts above are derived from.
