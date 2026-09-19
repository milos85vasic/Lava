# Implementation Plan: Combined Feature Roadmap (001 + 002)

**Branch**: `003-combined-feature-roadmap` | **Date**: 2026-09-18 | **Spec**: [spec.md](./spec.md)
**Input**: Member plans — [001/plan.md](../001-multi-provider-extension/plan.md), [002/plan.md](../002-build-test-distribute-pipeline/plan.md)

## Summary

This is an **execution-order plan**, not a technical design — the technical
design for each unit of work already exists in its own member plan.md. This
file answers one question only: *in what order does
`/speckit-superspec-execute 003-combined-feature-roadmap` walk the remaining
work, and what must happen at each human checkpoint before proceeding.*

## Execution Order

### Phase A — Finish 002 (build-test-distribute-pipeline), 4 tasks remaining

002 is 59/63 done and its remaining tasks are the highest-blast-radius part
of the whole feature (automated multi-submodule pin advancement,
first-ever full pipeline run against `master`). Finishing it first is
correct sequencing regardless of the umbrella merge, for two reasons:
(1) it was already in flight before this session, and (2) its remaining
tasks are explicitly gated by `002/plan.md`'s Human Checkpoints, so pausing
between them is required either way — merging specs changes nothing about
that gating.

1. **T054** `[REVIEW]` — Mandatory review of T052 (`advance-all-submodules.sh`)
   before any invocation against a real submodule upstream. **STOP. Human
   Checkpoint #2 (per `002/plan.md`).** Do not proceed to T055 without
   explicit operator sign-off on this review.
2. **T055** `[US4]` — Implement `phase-07-closure.sh`, per the three
   operator decisions recorded 2026-08-26 in `002/tasks.md` (recursive
   scope via per-level invocation stopping at `constitution/**`; FR-017
   Step A/Step B split; declared-path-set `--files` reconciliation instead
   of `git add -A`).
3. **T056** `[US4]` — Run `quickstart.md` Scenario 4 against a **disposable
   clone**, never real `master` on first use (Human Checkpoint #2 still in
   force). Step A (pin advance, staged) is runnable once T052 exists; Step
   B (closure, clean+pushed) is blocked on T055 landing.
4. **T062** `[REVIEW]` — Full end-to-end `quickstart.md` Scenario 2 run (all
   phases, no shortcuts) on a **disposable branch first**. **STOP. Human
   Checkpoint #3.** This is the gate before the pipeline is trusted to run
   against `master` for real — do not run it against `master` in this task.

**Phase A exit criterion**: all 4 tasks checked off in
`specs/002-build-test-distribute-pipeline/tasks.md` (the original file —
never in this umbrella's `tasks.md` mirror), `002/progress.yml` updated with
the same evidence discipline as every prior entry in that file.

### Phase B — Begin 001 (multi-provider-extension), 241 tasks, none started

Independent of Phase A; sequenced after it purely because Phase A was
already in flight. Follows `/speckit-superspec-execute`'s standard
phase-by-phase walk with per-phase checkpoints, starting at:

1. **Phase 1: Setup (Shared Infrastructure)** — per `001/tasks.md`.
2. **Phase 2: Foundational (Blocking Prerequisites)** — 9 subsections
   (Go API provider abstraction, Android DB migration v7→v8, credentials
   core module, SDK extensions, 4 new provider implementations, a
   constitution gate). This phase blocks every user story after it.
3. **Phases 3–9: User Stories 1–7** (Credentials Management → Modern
   UI/UX), each independently testable per `001/spec.md`'s Independent
   Test criteria.
4. **Phase 10: Polish & Cross-Cutting Concerns** — Challenge Tests,
   anti-bluff verification, documentation, evidence & tag gate.

**Given 241 tasks spanning an entire Android+Go feature**, this plan does
**not** attempt to schedule Phase B's internal checkpoints in advance —
`/speckit-superspec-execute` re-reads `001/tasks.md`'s own phase structure
and dependency notes at the time Phase B actually starts, and pauses at
each phase boundary per the command's standing Human Checkpoint rule
("The agent MUST pause at every phase boundary and wait for explicit user
approval. Never skip a checkpoint.").

## Constitutional Notes Carried Forward

- Root `CLAUDE.md` §6.T.3 / §11.4.113: no force-push, no history rewrite,
  no hook bypass without explicit per-operation approval — binds every task
  in both phases identically to how it already bound each spec separately.
- §6.I / §6.AE / §6.AH: any Android device-testing task in Phase B (001's
  Tests-for-User-Story sections, Phase 10 Challenge Tests) runs on a
  container/VM-hosted emulator, never host-direct, never a live physical
  device — unchanged by this merge.
- 002's remaining tasks already carry their own constitutional prerequisite
  sections in `002/tasks.md` (the submodule-advance constitutional
  prerequisite gating Phase 6, the distribute constitutional prerequisite
  gating Phase 5) — this plan does not restate them; read `002/tasks.md`
  directly when Phase A begins.

## Non-Goals of This Plan

- Does **not** re-derive or re-approve either member spec's technical
  design — see the linked `plan.md` files for that.
- Does **not** change either member spec's task numbering, checkbox state,
  or evidence file locations.
- Does **not** imply 001 and 002 share code, a build, or a deploy surface —
  they don't. "Combined" here means "one execute entry point," not "one
  deliverable."

`Classification:` project-specific.
