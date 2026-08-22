# Implementation Plan: Local Build-Test-Distribute Pipeline

**Branch**: `002-build-test-distribute-pipeline` | **Date**: 2026-08-21 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/002-build-test-distribute-pipeline/spec.md`

## Summary

A single locally-executed orchestrator (`scripts/pipeline-build-test-distribute.sh`) that, from a clean `master` checkout, sequences seven phases — precondition check, build, test (every constitution-recognized test category with machine evidence), install/boot backend services under `systemctl --user`, live-verification against those running services, unattended debug-then-release distribution via Firebase, documentation refresh, and full repository closure (including automatic submodule-pin advancement) — producing one consolidated run report per invocation and always restarting fully from scratch on the next run. The orchestrator is thin glue over this project's existing gate scripts (`scripts/ci.sh`, `scripts/firebase-distribute.sh`, `scripts/run-release-canary.sh`, `scripts/run-chaos-stress.sh`, `scripts/sync-markdown-exports.sh`) plus a small number of genuinely new scripts this feature introduces (systemd-unit install/boot, submodule pin-advance, evidence aggregation, anti-bluff evidence validation). Because two of the spec's resolved Clarifications deliberately remove human checkpoints this project's constitution currently mandates (§6.AA two-stage distribute, Seventh Law clause 3 real-device attestation, and the submodule pin-freeze rule), this plan's Phase 0 treats amending `CLAUDE.md` (and `.specify/memory/constitution.md` in lockstep) as a hard prerequisite gate, not optional cleanup.

> **Reconciled against the implementations on 2026-08-21**, alongside `contracts/cli-contract.md`, `data-model.md` and `quickstart.md`. **Where this plan and the code disagreed, the code won and this plan was corrected**, with each correction called out inline. Three corrections to the paragraph immediately above:
>
> 1. **"sequences seven phases" — five are wired**: `precondition`, `build`, `test`, `install_boot`, `live_verify`. That is not a shortfall to be papered over; it is the prerequisite gate this same paragraph declared, holding. The amendments never landed, so the phases that depend on them were never built, and the orchestrator refuses `--until distribute` with a usage error rather than pretending. (The sentence also listed eight items while calling it seven, counting distribution and documentation refresh separately.)
> 2. **`scripts/ci.sh` is NOT one of the scripts this orchestrator is glue over.** No pipeline script invokes it, deliberately — see the corrected Source Code listing below.
> 3. **The "small number of genuinely new scripts" is the larger half of the work.** The phase scripts and their seven test-category wrappers are several hundred lines each, most of it spent on parsing a real tool's real output honestly enough not to report a pass it cannot back. Calling them thin glue understated both the effort and the risk: several real defects were found in them during implementation, each now locked down by a hermetic suite under `tests/pipeline/`.

## Technical Context

**Language/Version**: Kotlin 2.1.0 (`:app` client + `:api-app` on-device API app), Go 1.26 (`lava-api-go` standalone host server; `:api-app` embeds the same Go router in-process via `-buildmode=c-shared` + JNI, per `docs/ARCHITECTURE.md`'s "On-Device Lava API" section), GNU Bash 5.2 (orchestration scripts — matches the host's actual `bash --version`; no macOS/BSD-bash portability requirement since this project's Local-Only CI/CD runs on operator-controlled Linux hosts). Note: the top-level `CLAUDE.md` "## Project" section's mention of a Ktor `:proxy` module is STALE — `settings.gradle.kts` contains no `:proxy` include; that server was superseded by `lava-api-go` (SP-2) and is not one of this pipeline's build targets. This staleness is itself flagged as a documentation-refresh finding for FR-013.
**Primary Dependencies**: Gradle (Android build via `buildSrc` convention plugins), the `constitution/` submodule's `workable-items` + `verify-all-constitution-rules.sh` tooling, `submodules/containers` (Containers submodule — emulator-in-container/VM per §6.AH), Firebase CLI (`firebase-distribute.sh`'s existing wrapper), systemd 258 user-manager (`systemctl --user`)
**Storage**: SQLite (`docs/workable_items.db`, unchanged by this feature); flat-file JSON/Markdown evidence under `.lava-ci-evidence/` (existing convention, extended with a new `pipeline-runs/<timestamp>/` subtree for this feature's consolidated run reports)
**Testing**: JUnit4 4.13.2 + MockK 1.13.5 + Orbit-test (Kotlin unit), `go test` (Go unit/integration, real Postgres via podman per `-Pintegration=true`), Espresso 3.7.0 + Compose UI Challenge Tests on containerized/VM emulators (§6.AE/§6.AH), `scripts/run-release-canary.sh` (release-APK cold-start canary), `scripts/run-chaos-stress.sh` (stress/chaos, LVA-7 scaffold), hermetic bash suites under `tests/*/`
**Target Platform**: Linux x86_64 self-hosted runner (this project's Local-Only CI/CD apparatus; no hosted CI, matches Principle IV)
**Project Type**: Multi-artifact monorepo pipeline (2 Android apps `:app` + `:api-app`, each debug+release, plus the standalone `lava-api-go` Go server, orchestrated by a bash script — not a single "web/mobile/library" shape)
**Performance Goals**: None fixed — per spec Clarification (session 2026-08-21, Q4): correctness over speed, no wall-clock ceiling, no phase may fail solely for running long
**Constraints**: Must run entirely under the operator's own user session (no `sudo`/`su`, per §6.U); must refuse to start except from a clean `master` checkout (FR-000); must always restart fully from scratch, never resume (FR-018); must produce zero fabricated/bluffed evidence (Anti-Bluff Pact, Sixth/Seventh Laws) — every phase's pass/fail signal must be independently falsifiable

## Constitution Check

*GATE: Must pass before proceeding. Re-check after design phase.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Anti-Bluff Testing Pact (First–Fifth Laws) | PASS | FR-003/FR-004 require every evidence record to carry a real, specific checked outcome and be anti-bluff-validated before being accepted as a pass — directly operationalizes these laws rather than working around them. |
| II. Real User Verification (Sixth Law + 6.A–6.F) | **VIOLATION** (justified) | FR-011 makes the release variant distribute automatically immediately after debug, replacing the Sixth Law clause 5 requirement that "a human MUST have used the feature on a real device" before any release tag. See Complexity Tracking below — the spec's Constitutional Impact note requires a `CLAUDE.md` amendment to Seventh Law clause 3 as in-scope prerequisite work for this feature, not a silent bypass. |
| III. Anti-Bluff Enforcement (Seventh Law) | **VIOLATION** (justified) | Clause 3 (Pre-Tag Real-Device Attestation, "no exception") is exactly what FR-011 replaces with machine evidence. Same justification and prerequisite amendment as above. Clauses 1, 2, 4, 5, 6 are unaffected and remain fully honored (FR-003/FR-004 satisfy clause 1's Bluff-Audit-equivalent evidence discipline for the pipeline's own new test types). |
| IV. Local-Only CI/CD | PASS | The orchestrator is a `scripts/*.sh` entry point, runs on the operator's own host, produces no hosted-CI configuration, and is invocable identically by a human or by this pipeline — matches the rule's own "same script a developer runs locally" requirement. |
| V. Decoupled Reusable Architecture | **VIOLATION** (justified) | FR-015's automatic submodule pin-advance replaces "submodule fetch/pull is an EXPLICIT operator action, never automatic." See Complexity Tracking — requires a `CLAUDE.md` amendment carving out this pipeline's advance-and-verify step as a documented exception, per the spec's Constitutional Impact note. |
| VI. Multi-Provider Uniformity | PASS | Not touched by this feature — the pipeline builds/tests/distributes existing providers as-is; it does not add, remove, or special-case any tracker provider. |
| Host Machine Stability Directive | PASS | No phase of this pipeline issues any forbidden power-management command; `systemctl --user` unit management is explicitly session-scoped and distinct from the forbidden `systemctl {suspend,poweroff,...}` verbs. |
| §6.U No sudo/su Mandate | PASS | FR-005 explicitly requires user-space background services ("not requiring elevated/root privileges"); systemd units are installed under `~/.config/systemd/user/`, never `/etc/systemd/system/`. |
| §6.R No-Hardcoding Mandate | PASS (design constraint) | Any host/port/credential the new systemd units or scripts need MUST come from `.env` / generated config, never literals — carried into Phase 1's data-model and contracts as a hard requirement on the new scripts. |
| §6.P Distribution Versioning + Changelog Mandate | PASS | FR-012/FR-013 directly re-implement this principle's strictly-increasing-version-code and mandatory-changelog rules as pipeline requirements; no relaxation. |
| §6.W Two-Mirror Policy | PASS | FR-014/FR-015's push behavior targets exactly "every upstream configured" — for this project and its submodules, that is GitHub + GitLab only, unchanged. |

**Overall gate result at Phase 0 entry: 3 justified violations, all traceable to the spec's own resolved Clarifications and Constitutional Impact note.** Per this project's Governance amendment procedure, Phase 0 treats landing the three named `CLAUDE.md` amendments (plus the matching `.specify/memory/constitution.md` MAJOR bump) as prerequisite research/design output — not implementation work to defer past this plan.

## Project Structure

### Documentation (this feature)

```text
specs/002-build-test-distribute-pipeline/
├── spec.md              # Feature specification (done)
├── plan.md              # This file
├── research.md           # Phase 0 output
├── data-model.md          # Phase 1 output
├── quickstart.md          # Phase 1 output
├── contracts/             # Phase 1 output
├── tasks.md              # /speckit.tasks output (not yet generated)
└── checklists/
    └── requirements.md    # Spec quality checklist (done)
```

### Source Code (repository root)

```text
scripts/
├── pipeline-build-test-distribute.sh   # NEW — top-level orchestrator (thin glue)
├── pipeline/                            # NEW — orchestrator's phase sub-scripts
│   ├── phase-00-precondition.sh          # WIRED. FR-000: master + clean tree guard
│   ├── phase-01-build.sh                 # WIRED. FR-001: dispatcher for all 5 artifacts (not 4)
│   ├── phase-01-build-android.sh         # ADDED — the 4 Android variants, ONE Gradle invocation
│   ├── phase-01-build-lava-api-go.sh     # ADDED — the Go binary, via the module's own `make build`
│   ├── phase-02-test.sh                  # WIRED. FR-002/003/004: dispatches 7 category wrappers
│   ├── phase-02-test-{go,kotlin,hermetic,stress-chaos,release-canary,challenge,constitutional-gate-sweep}.sh
│   │                                      # ADDED — one per category; "go" covers BOTH Go categories
│   ├── phase-03-install-boot.sh          # WIRED. FR-005/006/007: systemd --user install + health-check
│   ├── phase-04-live-verify-api.sh       # WIRED. FR-008, lava-api-go half: real HTTP over the wire
│   ├── phase-04-live-verify-api-app.sh   # WIRED. FR-008, :api-app half: real emulator per §6.AH
│   ├── phase-05a-changelog-entry.sh      # BUILT, NOT WIRED — per R-004, must precede distribute
│   ├── phase-06-docs.sh                  # BUILT, NOT WIRED. FR-013: changelog/FAQ/diagram refresh
│   └── lib/
│       ├── evidence.sh                    # shared: writes Evidence Records (7 args, phase_dir first)
│       ├── anti-bluff-validate.sh         # shared: FR-004's evidence validator
│       └── run-report.sh                  # FR-019: run-report writer — FOUR public functions
├── firebase-distribute.sh                # EXISTING — would be invoked by phase-05-distribute.sh
├── run-release-canary.sh                 # EXISTING — invoked by phase-02-test-release-canary.sh
├── run-chaos-stress.sh                   # EXISTING — invoked by phase-02-test-stress-chaos.sh
├── verify-all-constitution-rules.sh      # EXISTING — invoked by the gate-sweep wrapper directly
├── sync-markdown-exports.sh              # EXISTING — invoked by phase-06-docs.sh (which is unwired)
└── advance-all-submodules.sh             # NEW — FR-015's per-submodule fetch/advance/push/pin-update
                                          #   BUILT; never run against a real submodule upstream

systemd/
└── user/
    └── lava-api.service.template          # EXISTING (commit 7951bf4f) — reused as-is, not recreated

scripts/
├── systemd-install.sh                     # EXISTING — invoked by phase-03-install-boot.sh, verbatim
├── systemd-status.sh                      # EXISTING — NOT invoked. CORRECTED 2026-08-21: phase-03
│                                           #   calls tools/lava-containers/bin/lava-containers
│                                           #   -cmd=status directly for a machine-parseable signal,
│                                           #   the option R-012's own Alternatives paragraph flagged.
│                                           #   That binary's exit code is deliberately NOT trusted —
│                                           #   its Status() returns nil regardless of health, so it
│                                           #   exits 0 even when unhealthy (§6.B). The printed
│                                           #   `Healthy:` field is the load-bearing signal.
└── systemd-uninstall.sh                   # EXISTING — invoked by phase-03-install-boot.sh (FR-007)
                                           # (both Android apps — :app, :api-app — run on emulator/
                                           #   device, not systemd; only lava-api-go's container is
                                           #   systemd-managed, per research.md R-012)

.lava-ci-evidence/
└── pipeline-runs/<UTC-timestamp>/        # NEW — one directory per FR-018 fresh run
    ├── report.json                        # FR-019 Pipeline Run Report
    ├── phase-*/                            # per-phase raw evidence, mirrors existing evidence conventions
    └── submodule-advances/                # FR-015 Submodule Advance Records

CLAUDE.md                                 # AMENDED — §6.AA, Seventh Law clause 3, Decoupled Reusable
                                           #   Architecture (per Constitutional Impact note)
.specify/memory/constitution.md            # AMENDED — matching MAJOR version bump
```

**Structure Decision**: Single orchestrator script + a `scripts/pipeline/` directory of numbered phase scripts, matching this project's existing "thin glue over existing gate scripts" convention (e.g. `run-release-canary.sh`'s own header explicitly says "It is THIN GLUE"). No new top-level module (no `src/`, no new Gradle module) — this is pure build/test/ops tooling, not application code, so it lives entirely under `scripts/` + `systemd/` per the Decoupled Reusable Architecture rule's "thin glue tying things together" carve-out. Each phase script is independently invocable (satisfies FR-005's "scripts the operator can also run individually").

**CORRECTED 2026-08-21 — three structural facts the listing above did not anticipate.** (1) **A phase may own more than one script.** The orchestrator's phase registry maps a phase name to an ordered list, and `live_verify` owns both phase-04 halves; they run in order, the phase halts at the first failure, and each appends its own `phases[]` entry — so a five-phase run produces six entries, which the report schema permits and which is the honest shape (both must pass for the phase to have proven what its name claims). (2) **The orchestrator takes options** — `--until <phase>`, `--skip <phase>` (with `precondition` non-skippable, being the FR-000 safety boundary), and an optional repo-path override — which is what makes the per-user-story slices in `quickstart.md` runnable. These do not weaken FR-018: each invocation is still entirely fresh, with a new `run_id` and zero reads of any earlier run's output. (3) **Independent invocability holds, with one shared precondition**: every phase script except `phase-00` takes `<run_id> [repo-path]` and requires that run's `report.json` to already exist, so running one standalone means creating the run report first.

## Execution Strategy

### TDD Requirements

- [x] `scripts/pipeline/lib/anti-bluff-validate.sh`: Strict RED-GREEN-REFACTOR — this is the component that decides whether an evidence record counts as a genuine pass; a bug here silently defeats the entire feature's purpose (Seventh Law clause 4's forbidden-pattern list must each have a failing-first test).
- [x] `scripts/advance-all-submodules.sh`: Strict TDD — highest blast-radius new script (force-push-adjacent risk, breaking-change edge case from spec); every branch (no-newer-commit, conflict-on-push, breaking-change-detected) needs a failing-first hermetic test before implementation.
- [x] `scripts/pipeline/phase-00-precondition.sh`: TDD — the master-only/clean-tree guard is the pipeline's sole safety boundary against an accidental unattended release from unreviewed code; must be proven to reject every disallowed starting state before it's trusted to allow the rest.

### Parallel Execution Opportunities

- [x] `phase-01-build.sh`'s artifact builds. **CORRECTED 2026-08-21: two parallel processes, not five.** The premise "no shared build output" is true of the outputs and false of the *toolchain*: the four Android variants share one Gradle daemon and one project lock, and concurrent Gradle invocations against a single checkout are unsafe. They are built by **one** combined invocation (`./gradlew --no-daemon --parallel :app:assembleDebug :app:assembleRelease :api-app:assembleDebug :api-app:assembleRelease`), which keeps the parallelism inside Gradle's own task graph where it is safe. That runs as a genuine OS-level parallel process alongside the Go build, which shares no daemon or lock with it.
- [x] Within `phase-02-test.sh`: go, hermetic, stress-chaos and release-canary run truly in parallel throughout. **CORRECTED 2026-08-21: the Challenge stream is NOT independent of the Kotlin stream.** Both invoke Gradle against the same checkout — `./gradlew ... test` and `./gradlew ... connectedDebugAndroidTest` — so the same daemon/lock hazard applies. `phase-02-test.sh` waits for the `kotlin` wrapper specifically to finish before dispatching `real-device-challenge`; everything else keeps running in parallel across that boundary. Two further real constraints the plan did not foresee: the release-canary category needs the exact release APK **this** run built (resolved from the run's own `report.json`, never hardcoded) and is honestly not dispatched when phase-01 recorded none; and the constitutional-gate-sweep wrapper joins the first parallel group safely only because it reads **tracked** files while its siblings write only gitignored output.
- [ ] `phase-06-docs.sh` in parallel with `phase-07-closure.sh`. **Moot as written**: neither is wired, and `phase-07-closure.sh` does not exist.

### Human Checkpoints

Per the spec's resolved Clarifications (fully unattended distribution, no resume, no time ceiling), this pipeline itself has **zero in-run human checkpoints** once started — that is its entire point. The checkpoints below apply to *building this feature*, not to *running the finished pipeline*:

1. After the `CLAUDE.md` + `.specify/memory/constitution.md` amendments land (Phase 0 prerequisite) — operator review that the amendment text faithfully substitutes machine evidence for the human steps it replaces, per the spec's Constitutional Impact note.
2. After `scripts/advance-all-submodules.sh` is implemented — operator review before it is ever invoked against real submodule upstreams, given its blast radius.
3. After the first full end-to-end pipeline run on a disposable/test branch (not `master`) — operator verifies the consolidated Pipeline Run Report before the pipeline is trusted to run against `master` for real.
4. Before merge — final review against spec, per this project's standard practice.

**Status 2026-08-21 — none of the four are cleared, and the implementation respects that rather than routing around it.** #1: the amendments have not landed (tasks T040/T041, T048/T049 unapproved), so the phases that depend on them were never built — the orchestrator has no `distribute`, `docs_refresh` or `closure` phase and refuses to be asked for one. #2: `scripts/advance-all-submodules.sh` has **never been run against a real submodule upstream**; its TDD cycle is complete against disposable git fixtures only, and task T054 is open. #3: no full end-to-end run has happened on a disposable branch (task T062), and this pipeline has deliberately never been run against real `master`. #4: pending.

**One finding that belongs to checkpoint #1's review** and is not in the amendment text as researched: the amendment must be scoped by **which artifact and channel the pipeline's evidence covers**, not merely by "this pipeline produced it". As researched (R-001) plus the decision to invoke `firebase-distribute.sh --debug-and-release`, the release APK would ship gated on debug-channel evidence with the §6.AA staging check never evaluated — verified directly in that script. See research.md's Implementation note under R-001. A reviewer clearing checkpoint #1 on the current text would be approving an authorization broader than the evidence supports.
### Review Gates

- [ ] `scripts/advance-all-submodules.sh` and its interaction with `scripts/pipeline/phase-07-closure.sh`: review before any real invocation — this is the feature's highest-risk surface (16+ submodule upstreams, automatic pin advancement).
- [ ] The `CLAUDE.md` / `.specify/memory/constitution.md` amendment diffs: review before merge, per the constitution's own Amendment procedure ("at least one reviewer who has read the full diff and can attest that no clause is relaxed without explicit justification").
- [ ] `scripts/pipeline/lib/anti-bluff-validate.sh`: review before integration — this is the evidence gate every other phase depends on.

## Complexity Tracking

> Justifying the 3 Constitution Check violations above, per the spec's own Clarifications and Constitutional Impact note.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| §6.AA Two-Stage Distribute + Seventh Law clause 3 (human real-device attestation before release) relaxed to machine-evidence-gated automatic release | The user explicitly requested (and, when asked to weigh the tradeoff directly, confirmed) a fully unattended pipeline — "both variants ALWAYS" in one run, with no operator pause. A pipeline that stops and waits for a human mid-run does not satisfy FR-011/SC-001 and reintroduces exactly the manual step the feature exists to eliminate. | Keeping the human gate (i.e., building only the debug-distribute phase and stopping) was presented as the recommended, lower-risk option during `/speckit-specify`'s clarification; the operator explicitly chose the fully-unattended alternative instead, so it is not being adopted by default — it is a deliberate, informed choice this plan is honoring, gated behind a mandatory constitutional amendment rather than a silent code-level bypass. |
| Decoupled Reusable Architecture's "submodule fetch/pull is an EXPLICIT operator action, never automatic" relaxed to automatic pin-advance | Same pattern: the operator was asked directly whether the pipeline should push only local modifications at the current pin (lower risk) or also auto-advance every submodule pin, and chose the latter. FR-015/FR-017 and the "full repository closure" success criterion (SC-004) require every submodule to be at its own upstream HEAD, which is unreachable without an automatic advance step. | The lower-risk "push only what's already there" alternative was presented as recommended and explicitly not chosen; this plan implements the chosen behavior behind a new, isolated, TDD'd, heavily-reviewed script (`advance-all-submodules.sh`) with explicit conflict/breaking-change refusal (edge cases in spec), rather than folding pin-advancement invisibly into an existing script. |

## Phase 0 status

Research (`research.md`) complete. All Technical Context unknowns resolved; the constitutional-amendment prerequisite is documented as a Phase 0 research decision with its own rationale and alternatives-considered, per this project's own Governance amendment procedure. One correction to stale project documentation was discovered and folded back into `spec.md` (R-002: the `:proxy` Ktor module referenced by root `CLAUDE.md`'s "## Project" section and by this feature's own original spec text does not exist — `settings.gradle.kts` has no `:proxy` include; the real artifact set is `:app`, `:api-app`, and `lava-api-go`).

**Correction discovered during implementation (R-012, superseding R-006/R-007)**: while executing this feature's own Setup phase, real, already-committed, already-verified systemd `--user` wiring for `lava-api-go` was found already present (`systemd/user/lava-api.service.template` + `scripts/systemd-{install,status,uninstall}.sh`, commit `7951bf4f`, 2026-08-12). R-006/R-007's "build systemd support from scratch" framing is struck through and replaced by R-012's "reuse the existing scripts as thin glue" decision. `phase-03-install-boot.sh`'s scope shrinks accordingly — this is a reduction in new code, not new risk.

## Phase 1 status

`data-model.md`, `contracts/` (CLI contract + 4 JSON Schemas), and `quickstart.md` generated — see below.

## Constitution Check — post-design re-evaluation

Re-checked after Phase 1 design. No NEW violations were introduced beyond the 3 already identified and justified above (Complexity Tracking); the design's own choices reinforce rather than weaken this project's existing gates:

- R-003's finding that `firebase-distribute.sh` needs zero code changes means Principle IV (Local-Only CI/CD, "no parallel implementation") is honored more strongly than a naive design (a duplicate distribute script) would have.
- R-009's anti-bluff validator directly operationalizes Principle I/III rather than merely asserting compliance in prose — every Evidence Record is mechanically checked against the Seventh Law clause 4 forbidden-pattern list, closing the exact gap (a "process completed" bluff) that this project's own forensic history (§6.AA, §6.Z, §6.AB) shows repeatedly slipped through prose-only discipline.
- R-005's rebuild-and-test-before-committing-the-pin design means the Decoupled Reusable Architecture violation (V) is bounded: a submodule advance that would break the build is refused, not silently accepted — the automation this feature adds cannot itself introduce a broken pin, only a refused one.
- No new violation was found in §6.R, §6.U, §6.H, §6.P, or §6.W during design — R-006 (systemd env-file config) and R-008 (evidence directory conventions) were designed specifically to stay inside those rules rather than requiring new exceptions.

**Gate result: unchanged at 3 justified violations, all pre-declared, none newly discovered. Ready for `/speckit-tasks`.**

**CORRECTED 2026-08-21 — a fourth finding WAS discovered, after this re-evaluation, during implementation.** The bullet above concluding that "R-003's finding that `firebase-distribute.sh` needs zero code changes means Principle IV is honored more strongly" is true about Principle IV and hides a problem elsewhere. Reusing the existing script unchanged is the right call for "no parallel implementation"; the issue is **which mode this pipeline would invoke it in**. `--debug-and-release` sets `MODE=both`, which makes the script's §6.AA staging gate (guarded on `MODE == "release"`) never evaluate and resolves its §6.AK cycle-coverage evidence channel to `debug`, while the R8-minified release APK is uploaded in the same run. That is a **Principle II / Principle III** concern — the same two already-declared violations, but wider than declared: the justification on record argues that machine evidence can stand in for the human check, and does not establish that the evidence covers the artifact actually shipped. It is not a new violation to add to the count; it is the existing ones being under-scoped. Not currently reachable, since no distribute phase exists. Full detail in research.md's Implementation note under R-001.

**Two design choices reached in implementation that this re-evaluation did not anticipate, both strengthening Principle I/III rather than weakening them.** First, the anti-bluff validator's own rules had to be corrected before they could fail on the input they existed to catch: the `raw_output_ref` existence rule was satisfied by a directory (an empty ref resolved to the record's own directory, which exists and has non-zero size), and the generic-summary rule rejected 5 genuine PASS records from a real run because it substring-matched a phrase real test names legitimately contain. Second, and more consequential, the run-level rule gating `outcome: PASS` on `evidence_summary.rejected_by_anti_bluff == 0` read a counter **nothing ever wrote** — it stayed at its seeded zero, so a run whose evidence had been rejected could finalize `PASS`. Closed by deriving the summary from the real Evidence Record files on disk. Both are recorded here because the re-evaluation above asserted that R-009's validator "directly operationalizes Principle I/III rather than merely asserting compliance in prose" — which was the intent, and became true only after those defects were found. A gate that cannot fail is prose with a shell script around it.
