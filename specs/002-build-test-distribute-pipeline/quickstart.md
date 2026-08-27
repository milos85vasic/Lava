# Quickstart: Validating the Local Build-Test-Distribute Pipeline

This is a validation guide, not an implementation guide — it documents runnable scenarios that prove the feature works end-to-end once built. See `contracts/cli-contract.md` for the full command surface and `data-model.md` / `contracts/*.schema.json` for the artifacts referenced below.

> **Reconciled against the implementations on 2026-08-21**, alongside `contracts/cli-contract.md` and `data-model.md`. Every command and expected outcome below was checked against the script that actually runs it. **Where the code and this document disagreed, the code won**, and each correction is called out inline. Read this before running anything: **two scenarios below asserted an expected outcome the implementation deliberately does the opposite of** (Scenario 1's "no directory is created", Scenario 3's "later phases show as SKIPPED"), and **one scenario as written cannot run at all** (Scenario 4 — the script refuses without required environment). Corrected commands are given in each case.
>
> **Scope, stated once and plainly**: only five phases are wired — `precondition`, `build`, `test`, `install_boot`, `live_verify`. The distribute, docs-refresh and closure phases are not, being blocked behind unapproved constitutional amendments. So the concluding claim that these five scenarios demonstrate *every* functional requirement is not true today; see the corrected note at the end.

## Prerequisites

- A `master` checkout with a clean `git status` (FR-000's own precondition — if this isn't true, the first scenario below IS the test).
- `.env` populated per this project's existing convention (Firebase token, tracker credentials for real-stack tests, `EnvironmentFile` values for the systemd unit).
- The Containers submodule's emulator/VM path available on this host (§6.AH) — required for the Challenge-category tests in Scenario 2.
- No **`lava-api.service`** already active under the current user's systemd session (or, if one is, Scenario 2 is exactly what proves the pipeline cleans it up). **CORRECTED 2026-08-21:** this used to name `lava-api-go.service`, a unit that does not exist. The real unit is `systemd/user/lava-api.service.template` → `~/.config/systemd/user/lava-api.service` — R-012 recorded this correction against R-006's draft, but this line and `data-model.md`'s table kept the wrong name. Check with `systemctl --user is-active lava-api.service`, which is the same query `phase-03-install-boot.sh` itself makes.
- **`jq` on `PATH`.** `phase-02-test.sh` and the anti-bluff validator both hard-require it and exit rather than degrade. Not previously listed here.

## Scenario 1 — Precondition refusal (User Story 4's safety boundary, validated first because it's the cheapest and highest-value check)

```bash
git checkout -b scratch/quickstart-check
touch .quickstart-scratch-file   # dirty the tree
bash scripts/pipeline-build-test-distribute.sh
echo "exit code: $?"             # expect 2
rm -f .quickstart-scratch-file
git checkout master
git branch -D scratch/quickstart-check
```

**Expected outcome**: exit code `2`, and a message naming the specific violated precondition. **CORRECTED 2026-08-21 on two points.**

1. *"a message naming the specific violated precondition (wrong branch AND dirty tree)"* — `phase-00-precondition.sh` checks the branch **first and returns immediately** on failure, so from a scratch branch you get only `FR-000: precondition failed — branch is not master (currently on 'scratch/quickstart-check')`. The dirty-tree message is never reached in this scenario. To see the dirty-tree refusal, dirty the tree while *on* `master` instead.
2. *"`.lava-ci-evidence/pipeline-runs/` gains no new directory — a refused run must not fabricate a run report for a run that never started"* — **the implementation deliberately does the opposite.** The orchestrator calls `init_run_report` **before** running phase-00, because a refusal to start is itself a run outcome worth recording. So a refused run **does** create `.lava-ci-evidence/pipeline-runs/<run_id>/report.json`, and it is finalized, not left half-written: the orchestrator appends a `precondition`/`FAIL` phase entry on phase-00's behalf and closes the report on the way out, giving `outcome: "FAIL"`. That is not a fabricated report for a run that never started — it is an honest report that the run was refused, which is strictly more auditable than leaving no trace. Assert on that instead:

```bash
RUN_ID=$(ls -t .lava-ci-evidence/pipeline-runs | head -1)
python3 -c "
import json, sys
r = json.load(open(f'.lava-ci-evidence/pipeline-runs/$RUN_ID/report.json'))
assert r['outcome'] == 'FAIL', r['outcome']
assert [p for p in r['phases'] if p['name'] == 'precondition' and p['result'] == 'FAIL'], r['phases']
assert r['distributions'] == [] and r['build_artifacts'] == []
print('Scenario 1: PASS — refusal recorded honestly, nothing built, nothing distributed')
"
```

This ordering is also why `.gitignore` must ignore `.lava-ci-evidence/pipeline-runs/` (task T003). Without that entry the pipeline dirties the very tree it is about to test and can then never satisfy FR-000 — a failure mode whose symptom ("working tree is not clean") gives no hint that the pipeline itself is the cause. The orchestrator detects that specific case and prints an explicit `DIAGNOSIS` naming the offending paths.

## Scenario 2 — Full run of the wired phases, from a clean `master`

```bash
git checkout master && git status   # confirm clean
bash scripts/pipeline-build-test-distribute.sh
```

**CORRECTED 2026-08-21, REVISED 2026-08-26: this scenario was titled "Full run" and is still not one — but for a different and smaller reason than it used to be.** The 2026-08-21 text said a bare invocation defaults to `--until live_verify` and that `phase-05-distribute.sh`, `phase-05a-changelog-entry.sh` and `phase-06-docs.sh` were unwired. **All three of those statements are now stale.** MEASURED 2026-08-26: `pipeline-build-test-distribute.sh:315` sets `UNTIL_PHASE="docs_refresh"`, and the registry wires **eight** phases in R-004 order — `precondition → build → test → install_boot → live_verify → changelog_entry → distribute → docs_refresh` (T046/T057, landed 2026-08-25). `phase-05-distribute.sh` and `phase-06-docs.sh` both exist on disk.

What remains true is the *reason* this is not a full run: **`closure` is the one phase still unwired**, because `scripts/pipeline/phase-07-closure.sh` does not exist. Asking for it is a usage error, never a silent no-op:

```bash
bash scripts/pipeline-build-test-distribute.sh --until closure
echo "exit code: $?"   # expect 2, with a message naming the wired phases
```

**Consequence the operator should expect rather than discover** (`pipeline-build-test-distribute.sh:104-122`): because `changelog_entry` and `docs_refresh` are now inside the default, **a completed default run leaves the working tree DIRTY on purpose** — the CHANGELOG entry, the per-version snapshot, the R-002 documentation fixes and their regenerated `.html`/`.pdf` siblings. The phase that would commit them is `closure`. Until it is wired, committing is a human act, and a **second** run started before those changes are committed or discarded will correctly refuse at `precondition`. `--until live_verify` reproduces the pre-T046 default exactly.

Two slices of this scenario are separately runnable, and they are what make the per-user-story claims testable: `--until test` is the US1 slice (build + test only), `--until live_verify` is US1 + US2.

**Expected outcome** (per SC-002; SC-001 is **not** demonstrable today — see above): the command eventually exits `0` (no fixed time budget — this may legitimately take a long time, per the spec's own Clarification). A new `.lava-ci-evidence/pipeline-runs/<run_id>/` directory exists with `report.json` validating against `contracts/pipeline-run-report.schema.json`, `outcome: "PASS"`, every `phases[].result == "PASS"`, and `evidence_summary.rejected_by_anti_bluff == 0`.

Note that `phases[]` will contain **six** entries for five phases: `live_verify` owns two scripts (the running `lava-api-go` service and the `:api-app` build on a containerized emulator), each of which appends its own entry. The report schema permits repeated phase names, and it is the honest shape — both must pass for the phase to have proven what its name claims.

Two assertions in the snippet below are load-bearing and were not obvious before this reconciliation. `outcome` is authoritative over the individual phase results: a run can have every phase `PASS` and still be `FAIL`, because an Evidence Record was `REJECTED` by anti-bluff validation. And `evidence_summary` must be non-zero — a report whose counts are all zero means either that nothing ran or that the summary was never recomputed, and for a period the latter was a real defect that made the `rejected_by_anti_bluff` check a no-op.

```bash
RUN_ID=$(ls -t .lava-ci-evidence/pipeline-runs | head -1)
python3 -c "
import json, jsonschema
report = json.load(open(f'.lava-ci-evidence/pipeline-runs/$RUN_ID/report.json'))
schema = json.load(open('specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json'))
jsonschema.validate(report, schema)
assert report['outcome'] == 'PASS'
assert report['evidence_summary']['rejected_by_anti_bluff'] == 0
# ADDED 2026-08-21 — both of these catch real defects this feature had:
assert report['phases'], 'empty phases[] must never be a pass'
assert all(p['result'] == 'PASS' for p in report['phases']), report['phases']
assert report['evidence_summary']['total'] > 0, 'a run that scanned zero Evidence Records proved nothing'
print('Scenario 2: PASS')
"
```

## Scenario 3 — Falsifiability: a genuinely broken test blocks distribution (SC-003)

```bash
# Deliberately break a real, already-covered code path — pick any test this
# pipeline's phase-02 already runs and introduce a one-line regression in the
# production code it covers (per this project's own Bluff-Audit discipline —
# see any existing commit's "Mutation:" stamp for the pattern to follow).
git checkout master
# <apply the deliberate mutation here>
bash scripts/pipeline-build-test-distribute.sh
echo "exit code: $?"   # expect 1
git checkout -- .       # revert the mutation
```

**Expected outcome**: exit `1`, `report.json`'s `outcome: "FAIL"`, and the specific broken test's Evidence Record showing `result: "FAIL"` with a real `assertion_summary` (not a generic message).

**CORRECTED 2026-08-21 on two points.**

1. *"`phases[]` shows `test` as `FAIL` and every phase after it (`install_boot` onward) as `SKIPPED`"* — the orchestrator **halts at the first failing phase and never appends anything for the phases after it**. Those phases are **absent** from `phases[]`, not present with `result: "SKIPPED"`. The token `SKIPPED` is never written by the orchestrator at all: `append_phase_result` accepts it as a valid phase result, and nothing uses it. (`SKIPPED` *is* a real, frequently-used value one level down, on individual Evidence Records — a different thing, easy to conflate.) Assert absence:

   ```bash
   python3 -c "
   import json
   r = json.load(open('.lava-ci-evidence/pipeline-runs/$RUN_ID/report.json'))
   names = [p['name'] for p in r['phases']]
   assert r['outcome'] == 'FAIL'
   assert [p for p in r['phases'] if p['name'] == 'test' and p['result'] == 'FAIL']
   assert 'install_boot' not in names and 'live_verify' not in names, names
   print('Scenario 3: PASS — halted at test, nothing downstream ran')
   "
   ```

2. *"**zero** Distribution Records and **zero** Submodule Advance Records exist in the report, proving FR-009's refusal-to-proceed actually held"* — both arrays are **empty on every run today**, passing or failing, because no distribute or closure phase is wired to populate them. As written this assertion cannot fail and therefore proves nothing about FR-009; it is a vacuous check of exactly the kind this feature's own anti-bluff rules exist to reject, and it should not be counted as evidence until those phases exist. What genuinely does hold today is the assertion in point 1: the run stopped at `test` and no later phase ran.

**A stronger variant of this scenario, available now.** Scenario 3 as written proves the pipeline stops on a genuinely failing test. The distinct and more valuable property — that a test which *reports* `PASS` but whose evidence is a bluff also blocks the run — is exercised by mutating an Evidence Record's `assertion_summary` to a forbidden generic phrase, or its `raw_output_ref` to a path that is not a regular file, and confirming the run finalizes `FAIL` with `evidence_summary.rejected_by_anti_bluff > 0` even though every phase exited 0. That is the case `outcome` exists to catch, and it is covered hermetically by `tests/pipeline/test_run_report_evidence_summary.sh` CASE 3 and `tests/pipeline/test_anti_bluff_missing_evidence_fields.sh`.

## Scenario 4 — Submodule pin advancement and repository closure (FR-015/FR-016/FR-017)

**REVISED 2026-08-26 on explicit operator decision: FR-017 wins.** This scenario previously asserted a *staged, not clean* tree as its expected outcome. That contradicted FR-017, which defines a complete run as one whose repositories are clean and matching their upstreams, and it contradicted the closure behaviour the operator asked for (commit and push, then stop — never leave staged work behind). The scenario is now in two steps: **Step A** is the pin advance, which genuinely does end staged, and is an intermediate state; **Step B** is closure, whose assertions are FR-017's end state and which is the scenario's pass condition.

### Step A — pin advancement (runnable today)

Run this against a disposable clone, never against the real `master` on first use (per the plan's Human Checkpoint #2). **As of 2026-08-21 this checkpoint has not been cleared and the script has never been run against a real submodule upstream** — only against the disposable git fixtures in `tests/pipeline/test_advance_all_submodules.sh`.

**CORRECTED 2026-08-21: the command as written cannot run.** `scripts/advance-all-submodules.sh` **refuses** (exit 2, nothing attempted) unless `LAVA_PIPELINE_RUN_ID` is set, or both `LAVA_ADVANCE_RECORD_DIR` and `LAVA_ADVANCE_VERIFY_CMD` are supplied. That refusal is the point, not an inconvenience: without a run id it cannot reach the R-005 step-5 rebuild-and-test (both `phase-01-build.sh` and `phase-02-test.sh` require a run id with an existing `report.json`), and it will not advance a pin it cannot honestly claim to have re-verified. The corrected invocation:

```bash
git clone --recurse-submodules <this-repo> <disposable-clone-path>
cd <disposable-clone-path>

# The script writes into an existing run's directory, so create one first.
RUN_ID=$(date -u +%Y-%m-%dT%H-%M-%SZ)
python3 - "$RUN_ID" "$(git rev-parse HEAD)" <<'PY'
import json, os, sys
run_id, sha = sys.argv[1], sys.argv[2]
d = f".lava-ci-evidence/pipeline-runs/{run_id}"
os.makedirs(d, exist_ok=True)
json.dump({"run_id": run_id, "commit_sha": sha, "started_at": "1970-01-01T00:00:00Z",
           "completed_at": "1970-01-01T00:00:00Z", "outcome": "BLOCKED", "phases": [],
           "build_artifacts": [], "evidence_summary": {"total": 0, "passed": 0, "failed": 0,
           "skipped": 0, "rejected_by_anti_bluff": 0}, "distributions": [],
           "submodule_advances": []}, open(f"{d}/report.json", "w"), indent=2)
PY

LAVA_PIPELINE_RUN_ID="$RUN_ID" bash scripts/advance-all-submodules.sh
echo "exit code: $?"                # 0 = every submodule reached a non-rejecting outcome; 1 = at least one REJECTED
git status --porcelain              # MID-RUN state, not the outcome: STAGED pin changes, tree not yet clean.
                                    # Step B below is what turns this into FR-017's end state.
git -C submodules/helixqa log -1    # expect this to match submodules/helixqa's own upstream HEAD at run time
```

**Only `submodules/helixqa` can advance here, and that is the whole governance model, not a limitation of the fixture.** `scripts/advance-all-submodules.sh` was inverted from a deny-list to a **default-DENY allow-list on 2026-08-26** (LVA-138, explicit operator decision), and `GOVERNANCE_ALLOW` has **exactly one entry: `helixqa`** — the sole submodule carrying a standing operator authorization to track upstream unattended (root `CLAUDE.md`'s Q9 waiver). Every other submodule is recorded `REFUSED_GOVERNANCE_DENY` **without being examined for a newer commit**, before any fetch. So on this repository's 25 top-level submodules the expected shape of a successful run is **1 candidate for advance and 24 governance refusals**, each with its own Submodule Advance Record. A run in which `submodules/auth` advanced would be a governance failure, not a success — which is why the line above names `helixqa` and not `auth`.

On a real repository the default verify command runs a full build and test pass per advanced submodule, so this is a long operation. Set `LAVA_ADVANCE_SUBMODULES` to an allow-list of one submodule path to keep a first trial bounded.

**Expected outcome**, corrected: every submodule with a newer upstream commit is advanced, re-verified by a real rebuild-and-test, and staged; a submodule whose advance breaks that verification is **discarded** (restored to its prior pin) and recorded `REJECTED_BREAKING_CHANGE`, and one whose push cannot fast-forward is recorded `REJECTED_PUSH_CONFLICT` — a rejection is the correct outcome, never something to work around. Each `submodule-advances/*.json` validates against `contracts/submodule-advance-record.schema.json`; note the filename is the **sanitized** submodule path (`submodules/auth` → `submodules_auth.json`), while the `submodule_name` field inside keeps the real slashed path.

**Why a staged, not-clean tree is the correct result of Step A — and why it is NOT the result of this scenario.** The script stages the parent's pin with `git add` and **never commits in the parent repository**, deliberately, so a review gate sits between "pins staged" and "pins pushed". Step A therefore ends with staged changes; a clean parent tree after Step A would mean nothing was advanced. That is an **intermediate** state of an **incomplete** run, and this document previously presented it as the scenario's expected outcome — which contradicted FR-017 and has been corrected here (2026-08-26, on explicit operator decision that FR-017 wins). Committing and pushing the parent belongs to the closure phase.

### Step B — closure: FR-017's end state (**not runnable today**)

**FR-017 verbatim** (`spec.md:141`): *"A pipeline run is only considered complete when every repository it touched — the main project and every submodule — reports a clean working tree matching its upstream(s), with every submodule pin in the main repository matching that submodule's own upstream HEAD at the time of the run."*

So the pass condition of this scenario is a **committed, pushed, clean** repository — never a staged one. `scripts/pipeline/phase-07-closure.sh` is the phase that produces it, and **it does not exist yet**: T054 gates T055. T054 is the mandatory review of `scripts/advance-all-submodules.sh` — MEASURED: four completed rounds, every verdict APPROVE-WITH-FIXES, never a clean approval, with the round-4 fixes landed and no fifth verdict returned. Stating the assertions here without being able to run them is deliberate — this is what Step B will assert on the day it runs, not a claim that it has:

```bash
# NOT RUNNABLE UNTIL T055 LANDS — scripts/pipeline/phase-07-closure.sh does not exist.
bash scripts/pipeline/phase-07-closure.sh "$RUN_ID"
echo "exit code: $?"                        # 0 = closure complete; 1 = closure failed; 2 = precondition, nothing attempted

git status --porcelain                      # expect EMPTY — committed, nothing left staged, nothing left outstanding
git rev-parse HEAD                          # the closure commit
for r in $(git remote); do
  test "$(git ls-remote "$r" "$(git branch --show-current)" | cut -f1)" = "$(git rev-parse HEAD)" \
    || { echo "§6.C divergence at $r"; exit 1; }
done                                        # every configured upstream carries the same tip SHA (§6.C)
git submodule status --recursive | grep -c '^[+-]'   # expect 0 — every submodule, at every level, clean and at its pin
```

**Three assertions above are load-bearing and each replaces a weaker one this document used to imply.**

1. **`git status --porcelain` empty.** Not "staged", not "clean except the pins" — empty. FR-017 defines completion as a *state*, so a run that ended with work staged did not complete.
2. **Per-remote `ls-remote` equality.** "The push succeeded" is one claim; "every mirror converged on the same SHA" is the stronger one §6.C exists to force, and a partial push (one mirror carrying the commit, one not) is a reachable state that an exit code alone does not distinguish.
3. **`--recursive`, not top-level.** Per the operator decision of 2026-08-26, the closure scope is the **full recursive submodule set**. MEASURED on this repository the same day: **25** top-level, **59** recursive, **27** of those uninitialised, **0** carrying a `+`.

**One thing this scenario must NOT assert, because it is not achievable and asserting it would be the false-completeness signal this feature exists to prevent.** FR-017's clause *"every submodule pin … matching that submodule's own upstream HEAD"* is satisfiable only for submodules the governance allow-list permits this pipeline to advance. MEASURED: of the 59 recursive submodules, **exactly one** path (`submodules/helixqa`) has a final component on that allow-list, and **27** — every `submodules/helixqa/tools/**` entry — have third-party upstreams (`github.com/appium/appium`, `github.com/google/perfetto`, …) that root `CLAUDE.md` condition (C) forbids the automated path from advancing at all. The reachable assertion is therefore: *clean everywhere, at the recorded pin everywhere, and every submodule the allow-list permitted was advanced to its own upstream HEAD.* Reconciling that against FR-017's literal text is an open operator question recorded against T055, not something this document may quietly paper over.

## Scenario 5 — Restart-from-scratch on interruption (FR-018)

```bash
bash scripts/pipeline-build-test-distribute.sh &
PID=$!
sleep 30   # let it get partway into the build phase
kill -TERM "$PID"
bash scripts/pipeline-build-test-distribute.sh
```

**Expected outcome**: the second invocation creates a brand-new `run_id` directory, does not reference the interrupted run's directory anywhere in its own `report.json`, and re-runs the build/test phases from the beginning rather than skipping them. There is no resume flag and none may be added.

**CORRECTED 2026-08-21 — three implementation facts change what to expect here.**

1. **`sleep 30` will not reach the build phase on a cold checkout.** The build phase runs a real Gradle assemble of four Android variants plus a Go build. Wait until the console prints `phase 'build'` and has been in it a while, rather than trusting a fixed sleep.
2. **The killed run's report is `FAIL`, not left `BLOCKED`.** The orchestrator traps `INT` and `TERM` and closes the report on the way out, so `kill -TERM` produces a finalized report. `BLOCKED` at rest means finalize never ran at all — a `SIGKILL`, or a host crash. Use `kill -KILL` if the intent is to observe a genuinely unfinalized run.
3. **Re-invoking within the same second fails.** `run_id` is `date -u +%Y-%m-%dT%H-%M-%SZ`, one-second resolution, and `init_run_report` refuses to overwrite an existing `report.json`. Allow at least a second between invocations. This refusal is correct — silently overwriting an earlier run's report would destroy evidence — but it is not what this scenario is trying to demonstrate.

**REVISED 2026-08-26 — this paragraph used to claim more than is true.** It said the second invocation always satisfies FR-000's clean-tree precondition "because everything the first run produced is gitignored (`releases/`, and `.lava-ci-evidence/pipeline-runs/`)". That holds only for a run **interrupted before `changelog_entry`**, which is what this scenario's `kill` produces and is therefore still the right expectation *here*. It does **not** hold in general: since T046/T057 the default run reaches `changelog_entry` and `docs_refresh`, which write tracked files, so a *completed* default run does block its own next run at `precondition` until a human commits or discards. That is not a SC-007 violation — it is the gap that `closure` exists to close, and it is the strongest single argument for wiring it.

## Success signal for this quickstart as a whole

**CORRECTED 2026-08-21 — this claim is not true today, and stating it as though it were would be exactly the false-completeness signal this feature exists to prevent.**

All 5 scenarios passing demonstrates FR-000 through FR-009 and FR-018/FR-019 — the precondition boundary, build, test, evidence, anti-bluff validation, install/boot, live verification, restart-from-scratch, and the consolidated run report. That is a genuine feature-level Integration Challenge for the wired half of the pipeline.

It does **not** demonstrate FR-010 through FR-017. **REVISED 2026-08-26 — the reason has changed, and the old reason is no longer true.** This paragraph used to say those phases were "blocked behind unapproved constitutional amendments (tasks T040/T041 and T048/T049)". MEASURED against `tasks.md`: **all four of those tasks are `[x]` and landed 2026-08-23 under explicit operator approval.** No constitutional amendment blocks anything here any more.

What is true today, per requirement:

- **FR-013** (documentation refresh) now has a wired phase, `docs_refresh` → `phase-06-docs.sh`, inside the default run. It is exercised by Scenario 2.
- **FR-010 / FR-011 / FR-012** (distribute debug, distribute release, version monotonicity) have a wired `distribute` phase, but `phase-05-distribute.sh` is a **gate that cannot distribute** — it qualifies a run and exits 3 without uploading. So these three remain undemonstrated, and `distributions[]` is empty on every run, passing or failing.
- **FR-014 / FR-015 / FR-016 / FR-017** (commit-and-push the main repository, submodule pin advance, push-conflict refusal, clean-everywhere closure) have **no wired phase**: `scripts/pipeline/phase-07-closure.sh` does not exist. The blocker is **T054** — the mandatory review of `scripts/advance-all-submodules.sh`, which has run four rounds, returned APPROVE-WITH-FIXES every time, has never returned a clean approval, and gates T055.

Scenario 4 Step A exercises FR-015/FR-016 against disposable fixtures only, which is real coverage but not the same claim; Scenario 4 Step B, which is where FR-017 would actually be demonstrated, is not runnable until T055 lands.

Two scenarios also carry caveats worth repeating here: Scenario 3's "zero Distribution Records" assertion is vacuous today (see its own note), and Scenario 2 is a run of the wired phases, not a full run.
