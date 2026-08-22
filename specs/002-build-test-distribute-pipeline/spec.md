# Feature Specification: Local Build-Test-Distribute Pipeline

**Feature Branch**: `002-build-test-distribute-pipeline`
**Created**: 2026-08-21
**Status**: Draft
**Input**: User description: "Create build -> test -> distribute locally executed pipeline which will build all apps and services and distribute them all using Firebase Distribution (or any other additional means) and install and boot them all up (bash scripts - install bash script using and triggering systemctl --user space). Testing MUST run all existing tests with creation of new ones (all supported test types by the constitution) and all of them MUST produce machine evidence which will be validated and verified with applying of strong anti-bluff policy! After everything is installed, all infrastructure booted up, all tests executed including the LIVE apps testing and all prooved woring with rock-solid machine evidence, zero false or faulty results and zero bluff of any kind, distribution of all apps and all apps build variants (both release and debug ALWAYS) will be executed. All documentation of the project MUST BE fully updated and extended and all materials we have - FAQs, Schemes, Diagrams, and other. After everything is done and distributed commit and push all work - all Submodules fully recursively and main repo to all upstreams. Git status MUST return clean for every single one of them!"

## Clarifications

### Session 2026-08-21

- Q: Should the pipeline preserve the existing human gate between debug and release distribution (§6.AA two-stage rule + Seventh Law clause 3 real-device attestation), or run through both variants unattended? → A: Fully unattended — the pipeline distributes debug AND release back-to-back with no pause, every run. This requires this feature to carry a formal amendment to root `CLAUDE.md`'s §6.AA (Two-Stage Distribute Mandate) and to the Seventh Law clause 3 (Pre-Tag Real-Device Attestation) as part of its implementation, replacing the human confirmation those clauses currently require with the pipeline's own machine-evidence-and-anti-bluff-validation gate (see FR-011a and the Constitutional Impact note below).
- Q: Does "commit and push all work — all Submodules fully recursively" mean pushing only local modifications at each submodule's current pin, or also advancing every submodule's pin to its own upstream latest? → A: Also advance every submodule pin — the pipeline fetches each submodule's own upstream latest, advances it, and updates the main repository's recorded pin to match, before pushing everything. This requires this feature to carry a formal amendment to root `CLAUDE.md`'s Decoupled Reusable Architecture rule ("submodule fetch/pull is an EXPLICIT operator action, never automatic") as part of its implementation, replacing the deliberate-PR-per-pin-bump requirement with this pipeline's own automated advance-and-verify step for every submodule on every run (see FR-015a and the Constitutional Impact note below).
- Q: Should the pipeline require running from a specific branch/state, or can it run from any branch/state? → A: Refuse to start unless the working tree is on `master` (Track 1) with a clean git status at invocation time.
- Q: Does the full pipeline run need to complete within a fixed wall-clock ceiling, or is there no fixed time limit? → A: No fixed time ceiling — the pipeline may take as long as it needs; correctness, not speed, is the success criterion, and the pipeline MUST NOT fail or abort solely because a phase is taking a long time.
- Q: If a pipeline run is interrupted partway through, should the next invocation resume from the last completed phase, or always restart fully from scratch? → A: Always restart fully from scratch — any partial/interrupted prior run is discarded entirely; every invocation rebuilds and retests everything rather than trusting evidence from an earlier, possibly-stale run.
- Q: Should the pipeline produce one consolidated, machine-readable run report aggregating every phase's outcome, or is the set of individual per-test evidence records sufficient on its own? → A: Produce a consolidated run report aggregating every phase's outcome and referencing the individual evidence records, so the overall result can be read in one place.

### Constitutional Impact (carried forward to `/speckit-plan`)

Both resolutions above deliberately relax existing hard rules in root `CLAUDE.md` rather than merely filling in an unspecified detail. Per this project's own Governance amendment procedure (`.specify/memory/constitution.md` → Governance → Amendment procedure), any change that relaxes an existing principle requires a documented forensic anchor, a migration plan, and — per the Versioning policy — a MAJOR version bump of `.specify/memory/constitution.md`, plus the matching change landing in root `CLAUDE.md` itself (which is the actually-authoritative document per that file's own "Canonical Precedence" section). The planning phase for this feature MUST treat the following as in-scope prerequisite work, not just as pipeline behavior:

1. Amend `CLAUDE.md` §6.AA (Two-Stage Distribute Mandate) to define the specific machine-evidence conditions (build, test, live-verification, anti-bluff validation) that substitute for the human debug-then-release confirmation it currently mandates.
2. Amend `CLAUDE.md`'s Seventh Law clause 3 (Pre-Tag Real-Device Attestation) to define how the pipeline's own live-verification evidence satisfies (or formally replaces) the human-executed, human-recorded attestation it currently requires with "no exception."
3. Amend `CLAUDE.md`'s Decoupled Reusable Architecture rule to carve out this pipeline's automated submodule-pin-advance step as an explicit, documented exception to "submodule fetch/pull is an EXPLICIT operator action, never automatic."
4. Update `.specify/memory/constitution.md` to match (MAJOR version bump, per its own Versioning policy, since this is a relaxation of an existing principle) in the same change.

> **Implementation note (2026-08-21) — none of the four amendments have landed, and the specification is unchanged; this note records status, not a revision.** This spec is the feature's historical statement of intent and is left as written. What is true today:
>
> - **Items 1-4 are all still open.** The amendment text exists only as a proposal at `constitutional-amendments-proposal.md`; tasks T040/T041 (§6.AA + Seventh Law clause 3) and T048/T049 (the Decoupled Reusable Architecture carve-out) are unapproved, and `.specify/memory/constitution.md` has not been bumped.
> - **The pipeline honors that honestly rather than proceeding as if they had landed**, which is what the note above demanded. There is no `phase-05-distribute.sh` and no `phase-07-closure.sh`; the orchestrator's phase registry ends at `live_verify`, and asking for an unwired phase is a usage error (exit 2), never a silent no-op. FR-010 through FR-017 therefore have no implementation to exercise.
> - **Item 1's scope was found insufficient during implementation**, independently of whether it is approved. The amendment as researched (research.md R-001) reasons about whether the pipeline's evidence is *sufficient*, and never about **which artifact and channel that evidence covers**. Combined with the decision to invoke `firebase-distribute.sh --debug-and-release`, that script sets `MODE=both`, which makes its §6.AA staging gate (guarded on `MODE == "release"`) never evaluate and resolves its §6.AK cycle-coverage evidence channel to `debug` — while the R8-minified release APK is uploaded in the same run. All three facts were read directly from the script. An amendment drafted on the current analysis would authorize exactly the failure shape §6.AA was written after. See research.md's Implementation note under R-001 for the detail and the two candidate resolutions; that question must be answered in the amendment text before T040/T041 can responsibly be approved.

## User Scenarios & Testing *(mandatory)*

<!--
  IMPORTANT: User stories should be PRIORITIZED as user journeys ordered by importance.
  Each user story/journey must be INDEPENDENTLY TESTABLE - meaning if you implement just ONE of them,
  you should still have a viable MVP (Minimum Viable Product) that delivers value.
-->

### User Story 1 - Build every artifact and prove every test is real (Priority: P1)

An operator runs a single local command. The pipeline builds every distributable artifact this project produces (the Android client's debug and release variants, the on-device API Android app's debug and release variants, and the standalone Go API server), then executes every supported category of test this project's constitution recognizes (unit, integration/UseCase-Repository, contract, real-stack/real-device Challenge, hermetic script-level, and any stress/chaos suite already wired), and for every single test produces a machine-readable evidence record that can be independently inspected and re-verified — never a bare "it compiled" or "the pipeline exited 0" claim.

**Why this priority**: Without a trustworthy build+test+evidence phase, nothing downstream (installing services, distributing builds, closing out git state) can be trusted either. This is the foundation the rest of the pipeline depends on, and it is valuable on its own: an operator can run just this phase to get an honest, evidence-backed answer to "does everything actually still work?" before deciding to do anything else.

**Independent Test**: Can be fully tested by invoking only the build+test phase of the pipeline and inspecting the evidence directory it produces — every test category the constitution requires has at least one evidence record, every record shows a real (non-bluffed) assertion outcome, and the phase reports failure (not silent success) when any artifact fails to build or any test genuinely fails.

**Acceptance Scenarios**:

1. **Given** a clean checkout with no prior build artifacts, **When** the operator starts the pipeline's build+test phase, **Then** every app/service artifact is produced and every applicable test category executes, each with its own machine-readable evidence record.
2. **Given** a test that is deliberately made to fail against a real defect, **When** the build+test phase runs, **Then** the phase reports that test as failed with the real failure detail captured in its evidence record, and the phase's overall result is failure — not a false pass.
3. **Given** a completed, all-passing build+test phase, **When** an operator (or an automated auditor) inspects the produced evidence, **Then** they can confirm for each test that its evidence shows a genuine assertion outcome and not merely "process completed" or "mock was invoked."

---

### User Story 2 - Stand up the running services and prove them live (Priority: P2)

Once User Story 1's evidence shows everything genuinely passing, the operator can advance the pipeline into installing and starting the project's backend services as user-space background services on the local machine, then re-verify the built artifacts against those now-running services with real requests (not just pre-recorded fixtures) — proving the software works as a live system, not only as a passing test suite.

**Why this priority**: A green test suite is necessary but, per this project's own hard-won experience, not sufficient — features have shipped "tested" while broken for real users against real running infrastructure. This phase is the load-bearing proof that closes that gap, and it is independently valuable: an operator can run it against an existing build+test result to get a live-system health check without re-running the whole pipeline from scratch.

**Independent Test**: Can be fully tested by installing and starting the backend services, confirming each reports healthy through the same check the pipeline itself uses, then running the live-verification tests against them and observing real, evidence-backed pass/fail results distinct from the build-time test evidence.

**Acceptance Scenarios**:

1. **Given** a successful build+test phase, **When** the operator advances to the install/boot phase, **Then** every backend service starts as a background service the operator's own session manages, and each one reports healthy via a real functional check (not merely "the process is running").
2. **Given** the services are running, **When** the live-verification tests execute, **Then** each test exercises the real running service over its real interface and produces its own evidence record distinct from any build-time test evidence.
3. **Given** a service that fails to start or fails its health check, **When** the install/boot phase runs, **Then** the phase halts and reports the specific failure rather than proceeding as if the service were live.
4. **Given** a prior pipeline run left services running from an earlier attempt, **When** the pipeline runs again, **Then** it detects the existing services, cleanly stops them, and starts fresh ones rather than colliding with or silently reusing stale state.

---

### User Story 3 - Distribute every proven-good build variant and refresh project documentation (Priority: P3)

Once the build, test, and live-verification evidence all show a rock-solid, non-bluffed result, the pipeline distributes every build variant it produced — both the debug and the release variant of every app/service, back-to-back in the same run, with no human pause between them — through Firebase Distribution, and refreshes the project's documentation and supporting materials (changelog, FAQs, diagrams/schemes) to reflect what was just distributed. This fully-unattended behavior replaces the human confirmation this project's distribution rules have previously required; per the Clarifications above, making that replacement legitimate rather than a silent bypass is itself part of what this feature must deliver.

**Why this priority**: Distribution and documentation are the externally visible outcome of the whole pipeline, but they are only trustworthy once everything upstream (Stories 1 and 2) is proven — this story is deliberately last because distributing on top of unproven evidence is the exact failure mode the rest of the pipeline exists to prevent, and it is precisely because no human re-checks the release variant that the upstream evidence must be airtight.

**Independent Test**: Can be fully tested by feeding this phase a known-good evidence set from Stories 1 and 2 and confirming it produces one distribution per app/service/variant (debug and release, unattended) with a version identifier newer than the last successfully distributed one, plus documentation changes that reference the new version and describe what changed.

**Acceptance Scenarios**:

1. **Given** fully passing build, test, and live-verification evidence, **When** the distribution phase runs, **Then** the debug variant and the release variant of every app/service are distributed in the same run with no operator pause between them.
2. **Given** any of the upstream evidence (build, test, or live-verification) shows a failure or an unresolved anti-bluff finding, **When** the pipeline reaches the distribution phase, **Then** it refuses to distribute anything — debug or release — and reports exactly which evidence blocked it.
3. **Given** a successful distribution, **When** the documentation-refresh step runs, **Then** the changelog gains an entry for the new version, and the FAQ/diagram/scheme materials affected by the change are updated to match — no stale reference to the prior version remains in materials the pipeline is responsible for.

---

### User Story 4 - Close out every repository to a clean, pushed state (Priority: P4)

After distribution and documentation are complete, the pipeline advances into its final phase: for every submodule, fetching and advancing to that submodule's own upstream latest commit, committing and pushing any of the submodule's own local modifications to its configured upstream(s), and updating the main repository's recorded pin to match the newly-advanced commit; then committing and pushing all outstanding work (including the updated pins) in the main project repository — until every one of these repositories reports a clean working tree with its local branch matching its upstream(s). Per the Clarifications above, this automated pin-advance replaces the "explicit operator action only" rule this project's submodule policy has previously required.

**Why this priority**: This is the pipeline's closing guarantee — that a completed run leaves nothing stranded locally and every submodule is genuinely current — but it depends entirely on Stories 1–3 having produced something worth committing; it is listed last because it is the pipeline's bookkeeping step, not its value-delivering one.

**Independent Test**: Can be fully tested by seeding uncommitted changes in the main repository and in one or more submodules (with those submodules' own upstreams also having newer commits available), running only this phase, and confirming every affected submodule advances to its own upstream latest, the main repository's pins are updated to match, and every repository ends with a clean `git status` and its local branch matching every configured upstream at the same commit.

**Acceptance Scenarios**:

1. **Given** the main repository has uncommitted changes produced by earlier pipeline phases, **When** the closure phase runs, **Then** those changes are committed and pushed to every upstream configured for the main repository.
2. **Given** one or more submodules have a newer commit available on their own upstream, **When** the closure phase runs, **Then** each such submodule is advanced to that upstream's latest commit, any of the submodule's own local modifications are committed and pushed to its upstream(s), and the main repository's recorded pin for that submodule is updated to match.
3. **Given** a repository (main or submodule) has diverged from its upstream in a way that cannot be pushed without overwriting remote history, **When** the closure phase encounters it, **Then** the phase stops and reports the conflict rather than forcing the push.
4. **Given** the closure phase completes without error, **When** the operator checks `git status` in the main repository and in every submodule, **Then** every one reports a clean working tree.

---

### Edge Cases

- What happens when the pipeline is invoked from a non-`master` branch, or from `master` with uncommitted changes already present? The pipeline MUST refuse to start and report the specific precondition that failed, before touching any build, test, or distribution step.
- What happens when a required host precondition for live-service or device testing is genuinely absent (e.g., a container/emulator acceleration feature the host does not support)? The pipeline MUST report this as an explicit, honest block with the specific missing precondition named — it MUST NOT silently skip the affected tests or report them as passed.
- What happens when a test that previously passed now fails against a real, non-mutated code path? The pipeline MUST halt before any distribution step and report the genuine failure; it MUST NOT proceed to distribute on a red result.
- What happens when the candidate build's version identifier is not strictly newer than the last version already distributed on a given channel? The distribution phase MUST refuse that specific distribution and report why, rather than silently skipping it or overwriting the existing published version.
- What happens when local disk space or another host resource is exhausted mid-run? The pipeline MUST fail fast with a clear resource-exhaustion report rather than hang indefinitely or produce partial/corrupted evidence that could be mistaken for a complete result.
- What happens when a pipeline run is interrupted partway through (host issue, network drop, operator cancellation)? The next invocation MUST discard any evidence, running services, or partial state from the interrupted run and restart the entire pipeline from scratch rather than resuming from wherever it stopped — a resumed phase would be trusting evidence that was never (re-)verified against the current state.
- What happens when a submodule's local changes, or its upstream-advance, conflict with its own upstream at push time? The closure phase MUST stop and report that specific submodule's conflict; it MUST NOT force-push over it and MUST NOT silently skip it while reporting overall success.
- What happens when a submodule's own upstream has no newer commit available? The closure phase MUST treat this as a no-op for that submodule (pin unchanged) rather than an error.
- What happens when advancing a submodule's pin to its upstream latest would itself require a code or contract change in the main repository (e.g., a breaking API change) that the pipeline cannot resolve automatically? The pipeline MUST stop and report the specific incompatibility rather than advancing the pin over a build or test failure it just introduced.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-000**: The pipeline MUST refuse to start unless the working tree is on the `master` branch (Track 1) with a clean `git status` at the moment of invocation, and MUST report this precondition failure rather than proceeding on a feature branch or a dirty tree.
- **FR-001**: The pipeline MUST build every distributable artifact this project produces: the Android client's debug variant, the Android client's release variant, the on-device API Android app's debug variant, the on-device API Android app's release variant, and the standalone Go API server binary.
- **FR-002**: The pipeline MUST execute every test category this project's constitution recognizes as a supported test type (unit, integration/real-UseCase-and-Repository, real-binary contract, real-device/real-stack Challenge, hermetic script-level, and any stress/chaos suite already established), including test types newly created by this feature to close any gap in that coverage. **(Implementation note 2026-08-21: implemented. All 8 categories are wired as 7 wrapper scripts — one covers both Go categories. No new test *category* was needed; every category already had real tooling. The feature did create a substantial new body of tests, the hermetic suites under `tests/pipeline/`, several written to lock down real defects found while building the wrappers and the run-report writer.)**
- **FR-003**: Every executed test, new or existing, MUST produce a machine-readable evidence record capturing what was checked and the actual observed outcome, sufficient for independent re-verification without re-running the test.
- **FR-004**: The pipeline MUST validate every produced evidence record against this project's anti-bluff policy before treating the corresponding test as genuinely passed — a record whose only signal is "did not crash," "process completed," or "mock was invoked" MUST NOT be accepted as a pass.
- **FR-005**: The pipeline MUST install and start every backend service required for live verification as a background service under the operator's own user session (not requiring elevated/root privileges), using scripts the operator can also run individually outside the full pipeline.
- **FR-006**: The pipeline MUST verify each installed service reports healthy via a real functional check against that service's actual interface before treating it as ready for live verification.
- **FR-007**: The pipeline MUST detect and cleanly stop any services left running by a prior incomplete run before starting fresh ones, rather than colliding with stale state.
- **FR-008**: Once services are live, the pipeline MUST run a live-verification test pass that exercises the real running services over their real interfaces, distinct from and in addition to the build-time test evidence from FR-002–FR-004.
- **FR-009**: The pipeline MUST refuse to proceed to distribution if any required evidence (build-time tests or live-verification tests) shows a failure or an unresolved anti-bluff finding, and MUST report exactly which evidence blocked it.
- **FR-010**: When all required evidence passes, the pipeline MUST automatically distribute the debug variant of every app/service via Firebase Distribution (or an equivalent distribution channel already established for that artifact).
- **FR-011**: Immediately after a successful debug distribution, the pipeline MUST automatically distribute the release variant of every app/service in the same run, with no operator pause or confirmation step between the two. This machine-driven progression substitutes for, and requires a corresponding amendment to, the human debug-then-release confirmation this project's distribution rules have previously mandated (see Constitutional Impact note above) — the substitution is only valid because FR-002–FR-009 establish evidence and anti-bluff validation strong enough to stand in for that human check. **(Implementation note 2026-08-21: unimplemented — no distribute phase is wired. And the substitution needs one more condition than this sentence states: evidence being strong enough is necessary but not sufficient if it covers a different artifact than the one shipped. See the Constitutional Impact note above and research.md R-001.)**
- **FR-012**: Every distribution the pipeline performs MUST carry a version identifier strictly newer than the last version already distributed on that same channel, and MUST refuse any distribution that would not satisfy this.
- **FR-013**: Following a successful distribution, the pipeline MUST update the project's documentation and supporting materials (including the changelog, FAQs, and diagrams/schemes) to reflect the new version and what changed, without leaving materials it is responsible for referencing a now-stale version.
- **FR-014**: After distribution and documentation are complete, the pipeline MUST commit and push all outstanding changes in the main project repository (including any submodule pin updates from FR-015) to every upstream configured for it.
- **FR-015**: For every submodule, the pipeline MUST fetch that submodule's own configured upstream(s), advance the submodule's local checkout to the latest commit found there, commit and push any of the submodule's own local modifications to its upstream(s), and update the main repository's recorded pin for that submodule to the newly-advanced commit. This automated advance substitutes for, and requires a corresponding amendment to, the "submodule fetch/pull is an EXPLICIT operator action, never automatic" rule this project's Decoupled Reusable Architecture principle has previously mandated (see Constitutional Impact note above).
- **FR-016**: If any repository (main or submodule) cannot be pushed without overwriting diverged remote history, the pipeline MUST stop and report that specific conflict rather than forcing the push or silently skipping it.
- **FR-017**: A pipeline run is only considered complete when every repository it touched — the main project and every submodule — reports a clean working tree matching its upstream(s), with every submodule pin in the main repository matching that submodule's own upstream HEAD at the time of the run.
- **FR-018**: The pipeline MUST be re-runnable end-to-end from a clean checkout without any manual cleanup or confirmation step between runs, and MUST always restart fully from scratch rather than resuming a prior interrupted or partial run — no evidence, running service, or other state from an earlier invocation may be reused without re-verification.
- **FR-019**: The pipeline MUST produce one consolidated, machine-readable run report per invocation, aggregating the outcome of every phase and referencing every Evidence Record, Distribution Record, and Submodule Advance Record it produced, so the run's overall result is auditable from a single artifact.

### Key Entities

- **Pipeline Run**: A single end-to-end execution of the build-test-distribute pipeline, identified by a timestamp/commit pair, tracking which phase it reached.
- **Build Artifact**: One produced, distributable output of the build phase (e.g., the Android client's debug or release APK, the on-device API app's debug or release APK, the standalone Go API server binary), associated with a version identifier and the commit it was built from.
- **Evidence Record**: A machine-readable outcome for one executed test, capturing what was checked, the real observed result, and enough detail to be independently re-verified — the unit the anti-bluff validation step operates on.
- **Infrastructure Service**: A backend service instance installed and managed as a background service under the operator's user session for the duration of live verification, with an associated health status.
- **Distribution Record**: One completed distribution of a specific Build Artifact variant to a specific channel, carrying the version identifier distributed and the evidence that authorized it (in place of a human attestation).
- **Submodule Advance Record**: One completed advance of a submodule from its prior pinned commit to a newer commit found on its own upstream, carrying the old and new commit identifiers and confirmation that the main repository's pin was updated to match.
- **Pipeline Run Report**: A single consolidated, machine-readable record produced once per Pipeline Run, aggregating the outcome of every phase (build, test, install/boot, live-verification, distribution, documentation refresh, closure), referencing every Evidence Record, Distribution Record, and Submodule Advance Record it produced, so the run's overall result can be read and audited in one place without inspecting each individual record separately.

## Success Criteria *(mandatory)*

> **Implementation note (2026-08-21) — measured status, not a revision. The criteria below are left exactly as written; this records which are met.** Counted against the wired implementation, not estimated.
>
> | | Status |
> |---|---|
> | **SC-001** (one invocation carries everything through to full repository closure, zero manual steps) | **NOT MET.** Five phases are wired — precondition, build, test, install_boot, live_verify. Distribution, documentation refresh and closure have no phase to run. |
> | **SC-002** (100% of executed tests produce a real, specific checked outcome; zero rely on a "no crash" or "exited 0" signal) | **MET for the wired categories, and mechanically enforced** rather than asserted: every Evidence Record is checked by an independent validator that rejects generic assertion summaries and any record whose captured-output reference is not a real, non-empty regular file. Both of those rules had to be corrected during implementation before they could actually fail on the input they exist to catch. |
> | **SC-003** (zero distributions when evidence shows a failure or unresolved anti-bluff finding) | **Vacuously true, and therefore not yet demonstrated.** Zero distributions occur under any condition, because no distribute phase exists. The verification this criterion asks for — deliberately breaking a real test and confirming refusal — is task T031, still open. The nearest real proof available today is that a run whose phases all exit 0 still finalizes `FAIL`, and exits non-zero, when any Evidence Record was rejected by anti-bluff validation. |
> | **SC-004** (every repository clean and matching upstream, every pin at its upstream HEAD) | **NOT MET.** `scripts/advance-all-submodules.sh` exists but has never been run against a real submodule upstream, and by design it stages pins without committing — so a successful run leaves the parent tree deliberately not clean. |
> | **SC-005** (every distributed variant strictly newer on its channel) | **NOT EXERCISED** — nothing is distributed. The rule itself is `firebase-distribute.sh`'s existing §6.P gate and is not reimplemented here. |
> | **SC-006** (documentation refreshed within the same run) | **NOT MET.** `phase-06-docs.sh` is built and works standalone, but is not wired into the orchestrator. |
> | **SC-007** (two back-to-back full runs, both unattended, no stale state) | **NOT MET** as stated, since there is no full run. The no-stale-state property it depends on does hold: everything a run produces is gitignored, so a run's own output cannot block its successor's clean-tree precondition. |
> | **SC-008** (complete outcome readable from a single consolidated report) | **MET for the wired phases**, with one caveat a reader must know: `outcome` is authoritative over the individual phase results. A run can have every phase `PASS` and still be `FAIL`, because an Evidence Record was rejected. That combination is the single most important signal this pipeline produces, and for a period it could not occur at all — the counter it depends on was never written. See `data-model.md`'s Pipeline Run Report Validation rule. |

### Measurable Outcomes

- **SC-001**: A single pipeline invocation, from a clean checkout, carries every app/service through build, full test execution, live-service verification, distribution (both variants), documentation refresh, and full repository closure with zero manual steps of any kind, regardless of how long the run takes — no phase is permitted to fail or abort solely on elapsed-time grounds.
- **SC-002**: 100% of tests executed by the pipeline — across every supported test category — produce an evidence record that shows a real, specific checked outcome; zero evidence records rely solely on a "no crash" or "process exited 0" signal.
- **SC-003**: Zero distributions occur across all pipeline runs when any upstream evidence shows a failure or an unresolved anti-bluff finding — this is verified by deliberately breaking a real test at least once and confirming the pipeline refuses to distribute either variant.
- **SC-004**: After a successful pipeline run reaches its final phase, 100% of the repositories it touched (main project plus every submodule) report a clean working tree matching their configured upstream(s), with every submodule pin in the main repository matching that submodule's own upstream HEAD at run time.
- **SC-005**: Every distributed build variant carries a version identifier strictly newer than the previously distributed version on the same channel, with zero exceptions across all runs.
- **SC-006**: Within the same pipeline run that produces a successful distribution, the project's changelog, FAQ, and diagram/scheme materials are updated to reference the new version — zero manual documentation follow-up is required afterward for the materials the pipeline owns.
- **SC-007**: Re-running the full pipeline back-to-back from a clean state twice in succession completes both times fully unattended, demonstrating the pipeline leaves no stale state that blocks its own next run.
- **SC-008**: For every pipeline run, an operator (or auditor) can determine the run's complete outcome — pass/fail per phase, which artifacts were distributed, which submodules advanced — by reading a single consolidated run report, without needing to locate and cross-reference individual evidence files.

## Assumptions

- Firebase Distribution is the required distribution channel for this feature; other channels (e.g., an app-store listing) are out of scope unless a future amendment adds them, matching the user's "or any other additional means" as permissive rather than mandatory.
- "Install and boot up via `systemctl --user`" applies to the standalone Go API server (the only artifact this pipeline produces that is a systemd-manageable background process); both Android apps (the client and the on-device API app) are installed to a real device or emulator by the pipeline's existing device/emulator tooling rather than via systemd, since an Android application is not a systemd-manageable process. The on-device API app's own live-verification therefore happens on-device (emulator/VM), not via a systemd unit.
- "All supported test types by the constitution" refers to the test categories already recognized in this project's governing documents (unit, integration, real-binary contract, real-device/real-stack Challenge, hermetic script-level, and stress/chaos suites where established) plus any new test category this feature must introduce to close a gap in that coverage — it does not imply inventing test categories unrelated to this project's existing testing taxonomy.
- Per the Clarifications above, this feature is understood to require, as prerequisite or concurrent work, the constitutional amendments listed in the Constitutional Impact note — the pipeline's behavior as specified here is only legitimate once those amendments land, not a silent bypass of the rules as they stand today.
