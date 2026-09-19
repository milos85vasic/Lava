# Tasks: Combined Feature Roadmap (001 + 002)

**Input**: [plan.md](./plan.md) in this directory
**Prerequisites**: [001/tasks.md](../001-multi-provider-extension/tasks.md), [002/tasks.md](../002-build-test-distribute-pipeline/tasks.md)

## This file is an index, not a second source of truth

**Checkbox state for every task below lives in its ORIGINAL file.** When a
task in Part A is completed, tick it in
`specs/002-build-test-distribute-pipeline/tasks.md` — the copy of its text
below exists for at-a-glance visibility only, because that file's task IDs
(T040, T054, …) are cited verbatim by tracked evidence
(`002/progress.yml`, `002/constitutional-amendments-proposal.md`, prior
commit messages), and duplicating them as a second authoritative list would
break that traceability the moment the two copies drifted. Part B is not
copied at all — 001 has 241 tasks across 10 phases and copying that much
content here would itself become the second-source-of-truth problem this
whole umbrella was created to avoid; the mirror-vs-drift tradeoff was
resolved this way by explicit operator decision when this umbrella was
created (see `spec.md`).

If you are about to execute a task from either part, **open the linked
original file and work from there.**

---

## Part A — 002-build-test-distribute-pipeline (59 of 63 done, 4 remaining)

Source of truth: [`../002-build-test-distribute-pipeline/tasks.md`](../002-build-test-distribute-pipeline/tasks.md)

All 4 remaining tasks sit in the feature's final two phases and are all
gated by Human Checkpoints per `002/plan.md`. Mirrored verbatim below
(unchanged since last read):

### From Phase 6: User Story 4 — Close out every repository to a clean, pushed state (P4)

- [x] T054 [REVIEW] Mandatory review of T052 before any invocation against a real submodule upstream, per `plan.md`'s Human Checkpoint #2 — highest blast-radius new component in this feature (16+ submodule upstreams, automatic pin advancement). **ACCEPTED 2026-09-18** — independently re-verified (634/634 assertions across 4 suites, zero reachable `git push`/`git commit`); one disclosed, non-blocking residual risk remains open (verify-step credential sandboxing). Full verdict in `002/tasks.md` and `002/progress.yml`'s `t054_closure_2026_09_18`.
- [x] T055 [US4] Implement `phase-07-closure.sh`: invoke `scripts/advance-all-submodules.sh` (depends on T054's review), then commit and push all outstanding main-repository changes (including updated pins) to every configured upstream (FR-014), refusing on any unpushable-without-force divergence (FR-016) rather than forcing. **IMPLEMENTED 2026-09-18** — `scripts/pipeline/phase-07-closure.sh`, all three recorded operator decisions implemented verbatim. Two hermetic suites written via parallel subagents (`test_phase_07_closure.sh` 49/49, `test_phase_07_closure_hardening.sh` 48/48 — 97/97 total, independently re-run twice by the orchestrating session). Both subagents independently found and fixed the same two real bugs in the first draft. Full verdict in `002/tasks.md` and `002/progress.yml`'s `t055_implementation_2026_09_18`. Honest scope note: not yet run against a real submodule tree — that's T056/T062.
- [x] T056 [US4] Run `quickstart.md` Scenario 4 against a disposable clone (never against real `master` on first use, per Human Checkpoint #2) — confirm every submodule with a newer upstream commit advances and every repository ends clean. **RESOLVED 2026-09-18.** First attempt found a real, pre-existing CRLF/eol defect in `submodules/helixqa/tools/opensource/docling` blocking `helixqa`'s advance (confirmed present in the real repo, not a dry-run artifact). Operator approved fixing it: 3 local-only commits (docling renormalize → re-pin inside helixqa → re-pin in the parent; none pushed anywhere real, independently reverified). Re-attempt then reached a genuine PASS: `helixqa` ADVANCED for real, committed+pushed to throwaway local mirrors, converged, FR-017 end state confirmed clean — independently verified by the orchestrating session against `report.json` and `git log` directly, not taken on either subagent's word. Real-remote safety (`git ls-remote` against both real upstreams) reconfirmed unchanged throughout the entire T056 arc. Two test-harness-only gaps found and fixed along the way (stale submodule URL config, constitution never initialized in the throwaway clone) — confirmed NOT defects in `phase-07-closure.sh`/`advance-all-submodules.sh` themselves. Full detail in `002/tasks.md` and `002/progress.yml`'s `t056_resolution_2026_09_18`.

### From Phase 7: Polish & Cross-Cutting Concerns

- [ ] T062 [REVIEW] Full end-to-end run of `quickstart.md`'s Scenario 2 (all phases, no shortcuts) on a disposable branch first, per `plan.md` Human Checkpoint #3, before this pipeline is trusted to run against `master` for real. **ATTEMPTED + FOLLOWED UP 2026-09-19, remains open.** First wired `closure` as the orchestrator's 9th phase (test-first, independently re-verified). Full pipeline run (`--skip distribute`) reached `outcome: FAIL` at `test` after `build` PASSED for the first time ever; 4196 real evidence records produced. Follow-up work: (1) a scoped `--skip test,distribute` re-run reached one phase further (`install_boot`), fixed a real `.env.example` bug found along the way (landed for real), then hit an unrelated process's port collision on this shared host; (2) `superpowers:systematic-debugging` root-caused the 112 `UnsatisfiedLinkError` failures to a genuine Robolectric-4.12.2/host incompatibility (decompiled Robolectric's own loader bytecode, ruled out known upstream issues), verified a fix (`sqliteMode=LEGACY`), and landed it across all 4 affected modules — **180 tests, 0 failures, 0 regressions**, operator-approved; (3) the port collision from (1) is now FIXED — implemented a race-free ephemeral-port helper upstream in `vasic-digital/Containers` (real push, independently verified against both remotes) and wired `lava-api-go` to consume it via a new `server.ResolveListen()`, which also fixes a genuinely-worse-than-believed mDNS/Alt-Svc-before-any-bind ordering bug found along the way; 11 new real tests + 3 independent falsifiability rehearsals (2 by the implementing subagent, 1 by the orchestrating session), full `lava-api-go` suite re-run independently (50 packages ok, 0 FAIL). Nothing committed/pushed on the Lava side per instruction. `closure` still hasn't been exercised; a full re-run with all three fixes applied is the natural next step. Full detail in `002/tasks.md` and `002/progress.yml`'s `t062_attempt_2026_09_19`, `t062_followup_2026_09_19`, and `port_collision_dynamic_fix_2026_09_19`.

**Execution order**: T054 → T055 → T056 → T062, strictly — T055 depends on
T054's review landing, T056 Step B depends on T055, and T062 is the final
gate before this feature is considered done. Do not reorder.

**Status**: T054, T055, T056 done. T062 attempted (honest partial, `outcome: FAIL` at `test` — see detail above), remains open.

---

## Part B — 001-multi-provider-extension (0 of 241 done, not started)

Source of truth: [`../001-multi-provider-extension/tasks.md`](../001-multi-provider-extension/tasks.md)

Table of contents only (not mirrored — see rationale above):

| Phase | Content | Status |
|---|---|---|
| Phase 1 | Setup (Shared Infrastructure) | Not started |
| Phase 2 | Foundational (Blocking Prerequisites) — Go API provider abstraction, Android DB migration v7→v8, credentials core module, SDK extensions, NNMClub/Kinozal/Internet Archive/Project Gutenberg provider implementations, constitution gate | Not started |
| Phase 3 | User Story 1 — Credentials Management (P1) | Not started |
| Phase 4 | User Story 2 — Provider Login and Anonymous Access (P1) | Not started |
| Phase 5 | User Story 3 — Unified Search Across All Providers (P1) | Not started |
| Phase 6 | User Story 4 — Unified Forums and Categories (P1) | Not started |
| Phase 7 | User Story 5 — Provider Configuration and Persistence (P1) | Not started |
| Phase 8 | User Story 6 — Content Download from Any Provider (P2) | Not started |
| Phase 9 | User Story 7 — Modern UI/UX with Comprehensive Error Handling (P1) | Not started |
| Phase 10 | Polish & Cross-Cutting Concerns — Challenge Tests, anti-bluff verification, documentation, evidence & tag gate | Not started |

**Execution order**: Phase 1 → Phase 2 (blocking) → Phases 3–9 (user
stories; see `001/tasks.md`'s own dependency notes for which, if any, can
run in parallel) → Phase 10. Phase 2 blocks everything after it.

---

## Combined Execution Order (this umbrella's actual answer)

1. **Part A, in order**: T054 → T055 → T056 → T062 (002 finishes).
2. **Part B, from Phase 1**: begin 001 per its own phase structure.

Per `/speckit-superspec-execute`'s standing rule, the agent pauses at every
phase boundary in both parts and waits for explicit approval — this
umbrella changes none of that; it only tells the agent where to resume.
