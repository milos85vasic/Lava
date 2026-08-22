# CLI Contract: Local Build-Test-Distribute Pipeline

This project's "external interface" is its command-line surface — the orchestrator and each independently-invocable phase script (FR-005). Every script below follows this project's existing convention (`scripts/*.sh`, `set -euo pipefail`, non-zero exit on any failure, no interactive prompts).

> **Reconciled against the implementations on 2026-08-21 (task T038).** This document previously recorded several signatures the code does not have — `evidence.sh`'s own header had been carrying a standing note that this file needed updating once phase scripts were wired. Every entry below has now been checked against the real script it describes. Where the code and this document disagreed, **the code won and this document was corrected**, because a contract doc that describes an interface nobody implements is worse than none: it is a bluff about the shape of the system. Each correction is called out inline.

## Top-level orchestrator

```
scripts/pipeline-build-test-distribute.sh [options] [repo-path]
```

**CORRECTED**: this previously read "**Arguments**: none. (No mode flags…)". The script does take options.

| Option | Meaning |
|---|---|
| `--until <phase>` | Stop after `<phase>` completes successfully. One of `precondition`, `build`, `test`, `install_boot`, `live_verify`. Default `live_verify` (the furthest wired phase). Naming a phase that is not wired (e.g. `--until distribute`) is a **usage error, exit 2** — never a silent no-op. |
| `--skip <phase>[,…]` | Omit the named phase(s). `precondition` may **not** be skipped: it is the FR-000 safety boundary, and a pipeline that can be talked out of checking its own preconditions has no safety boundary at all. Attempting it exits 2 with an explicit refusal. |
| `-h`, `--help` | Print usage, exit 0. |
| `[repo-path]` | Positional. Forwarded to every phase script as its own `[repo-path]` argument. Controls which repository the phases **inspect**; does **not** relocate the run report, which is always written relative to the current working directory. |

**These options do not weaken FR-018.** FR-018 forbids resuming or reusing a prior run; it does not require that every invocation run every phase. `--until`/`--skip` bound the scope of a run that is still entirely fresh — a new `run_id`, a new directory, and zero reads of any earlier run's output. They are what make `quickstart.md`'s per-user-story slices runnable (`--until test` is the US1 slice; `--until live_verify` is US1+US2). There is still no resume flag, and none may be added.

- **Preconditions checked** (FR-000, phase-00): current branch is `master`; `git status --porcelain` is empty. Violation → exit 2, message names the specific precondition that failed, nothing else runs.
- **Exit codes**: `0` = every phase that ran reported PASS **and** the finalized `outcome` is `PASS`. `1` = a phase failed, **or** every phase passed but the finalized `outcome` is `FAIL`. `2` = usage error, or FR-000 precondition refusal (propagated verbatim from `phase-00-precondition.sh`, never hardcoded).

  **CORRECTED / IMPORTANT**: the all-phases-passed-but-outcome-FAIL case is new here and is load-bearing. `outcome` is `FAIL` when any Evidence Record was REJECTED by anti-bluff validation, even with every phase green. The orchestrator exits non-zero in that case: **the finalized outcome is authoritative over the individual phase exit codes.**
- **Report finalization**: on every exit path once the report exists — success, phase failure, or interrupt — the orchestrator runs `recompute_evidence_summary <run_id>` and then `finalize_run_report <run_id>`. The recompute is mandatory, not bookkeeping: `outcome: PASS` is gated on `evidence_summary.rejected_by_anti_bluff == 0`, and that counter is seeded to `0` and only ever populated by the recompute scanning real Evidence Records. Skipping it silently turns the anti-bluff half of the outcome rule into a no-op.
- **Required `.gitignore` entry**: `.lava-ci-evidence/pipeline-runs/` must be ignored (task T003). The report is initialized *before* the precondition check so that a refusal to start is itself recorded, which means the run directory exists when FR-000's clean-tree rule is evaluated. Without the ignore rule the pipeline dirties the tree it is about to test and can never start; the orchestrator detects that specific case and prints an explicit `DIAGNOSIS` naming the offending paths.
- **Side effects**: creates `.lava-ci-evidence/pipeline-runs/<run_id>/`; may install/start/stop the `lava-api.service` systemd user unit. It **cannot** currently distribute or push: the distribute, docs and closure phases are not wired, being blocked behind constitutional amendment tasks T040/T041 and T048/T049.
- **Idempotency**: none claimed or needed — every invocation is a fresh run (FR-018). Running it twice back-to-back produces two distinct `run_id`s.

## Phase scripts (each independently invocable per FR-005)

Every phase script except `phase-00` takes `<run_id> [repo-path]` and appends its own `phases[]` entry to the shared `report.json`. `phase-00-precondition.sh` takes only `[repo-path]` and does **not** self-append; the orchestrator appends on its behalf. All use the same exit-code convention: `0` success, `1` real failure, `2` usage/precondition error.

| Script | Status | Reads | Writes | Exit 0 means |
|---|---|---|---|---|
| `phase-00-precondition.sh` | wired | `git status`, `git branch --show-current` | nothing | on `master`, clean tree |
| `phase-01-build.sh` | wired | source tree | `releases/<version>/…`, `lava-api-go/bin/*` | every Build Artifact produced |
| `phase-02-test.sh` | wired | built artifacts | Evidence Records under `phase-02/` | every dispatched category exited 0, zero FAIL records, zero REJECTED records |
| `phase-03-install-boot.sh` | wired | `.env`, built `lava-api-go` binary | the `lava-api.service` systemd `--user` unit | stale-instance check + install + health check all succeeded |
| `phase-04-live-verify-api.sh` | wired | the running `lava-api-go` service | Evidence Records under `phase-04/` | base URL resolved and all real HTTP checks returned 2xx, every record validated |
| `phase-04-live-verify-api-app.sh` | wired | `:api-app` debug APK on a container/VM emulator (§6.AH) | Evidence Records under `phase-04/` | an emulator container was observed running, the boot-and-serve Challenge passed, and Gradle's JUnit XML and the Containers attestation agree |
| `phase-05a-changelog-entry.sh` | built, **not wired** | version files, `firebase-distribute.sh`'s own gate literals | `CHANGELOG.md` entry + `.lava-ci-evidence/distribute-changelog/<channel>/<version>-<code>.md` | both artifacts present for the exact version (satisfies `firebase-distribute.sh` Gates 2+3 only, per R-004) |
| `phase-05-distribute.sh` | **does not exist** — blocked on T040/T041 | — | — | — |
| `phase-06-docs.sh` | built, **not wired** | owned documentation | `docs/ARCHITECTURE.md` + `CLAUDE.md` staleness fixes (R-002), regenerated exports | fixes applied idempotently and independently re-verified afterwards |
| `phase-07-closure.sh` | **does not exist** — blocked on T048/T049/T054 | — | — | — |
| `scripts/advance-all-submodules.sh` | built, **never run against a real submodule** | each submodule's own upstream | one Submodule Advance Record per submodule | every submodule advanced, or explicitly rejected with a reason and its prior pin restored |

**CORRECTIONS in the table above**: the single `phase-04-live-verify.sh` this document used to name does not exist and never did — the live-verify surface is split in two, and as of 2026-08-21 both halves are implemented and both are wired into the `live_verify` phase, which owns them as an ordered pair. `phase-03` installs `lava-api.service`, not `lava-api-go.service`, and does so by invoking the existing `scripts/systemd-install.sh --start` verbatim rather than reimplementing install logic. `phase-01-build.sh` is a dispatcher: it runs `phase-01-build-android.sh` and `phase-01-build-lava-api-go.sh` as parallel background jobs and aggregates them. `phase-05-distribute.sh` and `phase-07-closure.sh` were described here as if they existed; they do not.

### `scripts/advance-all-submodules.sh` — required environment

Not optional, and not previously documented here. The script **refuses to run** unless it can honestly claim a rebuild-and-test step happened:

- `LAVA_PIPELINE_RUN_ID` — the run this advance belongs to. **Required** unless *both* `LAVA_ADVANCE_RECORD_DIR` and `LAVA_ADVANCE_VERIFY_CMD` are supplied, because without a run id it cannot reach `phase-01`/`phase-02` (both need a run id with an existing `report.json`).
- `LAVA_ADVANCE_RECORD_DIR` — where records are written. Defaults to `.lava-ci-evidence/pipeline-runs/<run_id>/submodule-advances`.
- `LAVA_ADVANCE_VERIFY_CMD` — the R-005 step-5 rebuild-and-test command, run per advanced submodule via `bash -c` with `$1` = the submodule's absolute path and `$2` = the parent repo root. Zero exit means the advanced state still builds and passes. Defaults to `phase-01-build.sh && phase-02-test.sh` for the run.
- `LAVA_ADVANCE_SUBMODULES` — optional allow-list of submodule paths.

## Shared library contract (`scripts/pipeline/lib/`)

**`evidence.sh`**

```
write_evidence_record <phase_dir> <test_id> <category> <command> <result> <assertion_summary> <raw_output_path>
```

Writes one Evidence Record JSON per `data-model.md`; prints its path on stdout.

**CORRECTED**: this document previously recorded a six-argument form with no `<phase_dir>`, leaving unstated how the function could know the `<run_dir>/phase-NN/` prefix `data-model.md` requires. The implementation takes `<phase_dir>` as an explicit leading argument. Every caller is a `phase-NN-*.sh` script that already knows its own phase number as a fixed fact, so passing it explicitly costs the caller nothing and removes a whole class of "which phase does this record belong to" bugs. The alternatives — inferring the phase from the `test_id` (which does not reliably encode it) or reading ambient global state (which a phase script can forget to set) — were both rejected.

The function sets `anti_bluff_status` to a placeholder only, satisfying the schema's pattern constraint. It **must not** be extended to perform validation itself: doing so would collapse the "an independent validator can catch a test lying about itself" property FR-004 exists to guarantee.

**`anti-bluff-validate.sh`**

```
validate_evidence_record <path-to-record.json>
```

Exits 0 and rewrites the record's `anti_bluff_status` to `"validated"`, or exits 1 and rewrites it to `"REJECTED: <reason>"`.

**`run-report.sh`** — four public functions, not two:

```
init_run_report <run_id> <commit_sha>
append_phase_result <run_id> <phase_name> <result> <duration_seconds> <evidence_dir>
recompute_evidence_summary <run_id>
finalize_run_report <run_id>
```

- `init_run_report` — called once by the orchestrator, before phase-00. `run_id` must match `^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z$`; `commit_sha` must be a full 40-hex SHA.
- `append_phase_result` — **was undocumented here.** Called by each phase script for itself. A phase that self-appends must not also be appended by the orchestrator: a duplicated `phases[]` entry corrupts the outcome computation, which requires *every* entry to be PASS.
- `recompute_evidence_summary` — **was undocumented here, and did not exist until 2026-08-21.** Rebuilds `evidence_summary` by scanning the real Evidence Records on disk. Derives counts from physical artifacts rather than from a counter each phase must remember to increment, so a phase that forgets to report cannot thereby hide its own rejected evidence.
- `finalize_run_report` — called once at the end; sets `completed_at` and computes `outcome` per `data-model.md`'s Validation rule. It deliberately does **not** recompute `evidence_summary` itself; supplying that input is `recompute_evidence_summary`'s job, and the separation is asserted by `tests/pipeline/test_evidence_and_run_report.sh`.

## Contract for downstream consumers (an operator, or a future audit/dashboard tool)

Any tool reading this pipeline's output should read `report.json` first (schema: `pipeline-run-report.schema.json`) and follow its `evidence_dir` / `raw_output_ref` / `evidence_ref` pointers only when it needs more detail than the summary provides — this is the mechanism SC-008 exists to guarantee.

**Do not treat a `phases[]` array of all-PASS as equivalent to a passing run.** Read `outcome`. A run can have every phase PASS and still be `FAIL`, because an Evidence Record was REJECTED by anti-bluff validation. That combination is not an anomaly to be smoothed over — it is the single most important signal this pipeline produces.
