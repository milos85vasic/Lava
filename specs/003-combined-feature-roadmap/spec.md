# Feature Specification: Combined Feature Roadmap (001 + 002)

**Feature Branch**: `003-combined-feature-roadmap`
**Created**: 2026-09-18
**Status**: Umbrella / Index — not a replacement for its member specs
**Input**: Operator request via `/speckit-superspec-execute` (no spec-number given): "Both of it, do merge all specs into single one." Clarified: merge = concatenate under one directory without renumbering, preserving each source spec's own task IDs and evidence-chain traceability.

## Purpose

This directory is an **umbrella index**, not a rewrite. It exists so a single
`/speckit-superspec-execute 003-combined-feature-roadmap` invocation has one
place to start that accounts for all outstanding work across the project's
two active feature specs, **without** disturbing either spec's own
task numbering, checkboxes, or evidence chain.

**This directory is never the source of truth for task state.** Checkbox
updates, `progress.yml` evidence, and all `[REVIEW]`/`[TDD]`/`[SUBAGENT]`
execution happen in each member spec's own directory, exactly as before this
umbrella was created. See `tasks.md` in this directory for why: spec
002's task IDs (T040, T054, T055, …) are cited verbatim by
`constitutional-amendments-proposal.md`, `progress.yml`, and prior commit
messages. Renumbering or duplicating them as a second source of truth would
break that traceability — a real cost, not a cosmetic one — which is why the
operator's clarified choice was "don't renumber."

## Member Specs

### [001-multi-provider-extension](../001-multi-provider-extension/spec.md)

Extend the Lava system (Go API + Android client) to support multiple content
providers beyond RuTracker/RuTor: NNMClub, Kinozal (torrent trackers),
Internet Archive, Project Gutenberg (HTTP-based). Unified search/forums,
credentials management, Material Design 3 UI, full anti-bluff test coverage.

- **Status**: Draft, **0 of 241 tasks** started (Phase 1 Setup not yet begun).
- **Priority shape**: 7 user stories, all P1 except User Story 6 (P2,
  content download).
- **Scope**: Android + Go API domain feature work. Independent of 002 —
  no shared code paths, no ordering dependency between the two specs'
  implementation work.

### [002-build-test-distribute-pipeline](../002-build-test-distribute-pipeline/spec.md)

Local build → test → distribute pipeline: builds every artifact, runs every
test type with anti-bluff machine evidence, distributes debug + release via
Firebase, then closes out every repository (main + all submodules,
recursively) to a clean, pushed state — including automated per-submodule
upstream pin advancement under a bounded, reviewed exception to the
Decoupled Reusable Architecture rule's "explicit operator action" default.

- **Status**: Draft, **59 of 63 tasks done**. 4 remaining, all in the final
  two phases (User Story 4 — repository closure; Polish — trust-building
  end-to-end run), and all gated:
  - `T054` `[REVIEW]` — mandatory review before any invocation against a
    real submodule upstream (highest blast-radius component: 16+ submodule
    upstreams, automatic pin advancement).
  - `T055` `[US4]` — implement `phase-07-closure.sh` (depends on T054's
    review landing first).
  - `T056` `[US4]` — run `quickstart.md` Scenario 4 (Step A pin-advance
    is runnable now; Step B closure is blocked on T055).
  - `T062` `[REVIEW]` — full end-to-end `quickstart.md` Scenario 2 run on a
    disposable branch, before this pipeline is trusted against `master`,
    per `plan.md` Human Checkpoint #3.
- **Scope**: Repo tooling / CI-equivalent infra. This feature has already
  amended root `CLAUDE.md` §6.AA and the Decoupled Reusable Architecture
  rule (see `002/progress.yml`'s `clause_8_amendment_2026_08_26` entry and
  `002/constitutional-amendments-proposal.md`); those amendments are
  binding project-wide already, independent of whether 002's own remaining
  4 tasks land.

## Relationship Between the Two Specs

There is **no functional dependency** between 001 and 002 — one ships an
Android/API product feature, the other ships repo-local build/test/distribute
tooling. They were only ever separate because they were speckit'd
separately; this umbrella exists purely so both are reachable from one
execute entry point, per the operator's request. Execution order is a
sequencing choice (see `plan.md`), not a technical requirement.

## Success Criteria

- Both member specs remain independently readable, executable, and
  git-history-traceable exactly as before (no task ID changed, no
  evidence file moved).
- A reader of `specs/003-combined-feature-roadmap/` can see, in one place,
  the full outstanding work across the project without opening both
  directories first.
- `/speckit-superspec-execute 003-combined-feature-roadmap` (or by number,
  `003`) resolves unambiguously to "finish 002's remaining 4 gated tasks,
  then begin 001 from Phase 1," per `tasks.md` and `plan.md` in this
  directory.

`Classification:` project-specific (this is a spec-kit bookkeeping artifact
for the Lava repo's own two in-flight specs; the underlying spec-kit tooling
convention — index directory pointing at existing specs rather than
renumbering them — is reusable elsewhere).
