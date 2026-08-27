# Amendment Defects Proposal — Two Defects in the Landed §6.AA Clause 8

**Status: DRAFT — PROPOSED, NOT APPLIED. Requires operator approval before any governing document or contract schema is touched.**

> **SUPERSEDED 2026-08-26 — READ THIS FIRST.** §6.AA clause 8 was amended on
> 2026-08-26 (LVA-120 + LVA-147; `.specify/memory/constitution.md` 2.0.0 → 3.0.0).
> **Every by-letter condition citation in this document uses the original
> nine-condition lettering and no longer matches the clause in force.** The clause
> is now conjunctive over **eight** conditions, **(A)–(H)**, with no gap and no
> letter (I): the former **(D)** (cycle-coverage on BOTH channels) was **withdrawn,
> not renumbered**, and former (E)–(I) became (D)–(H). Clause 8 also no longer
> authorizes the combined `--debug-and-release` invocation — that mode is retired
> and clause 3 forbids it — and the residual-gap condition (now **(G)**) records the
> gap through `build_artifacts[].build_output_path` instead of a schema-forbidden
> `residual-gap` field.
>
> **This document is deliberately NOT re-lettered.** It records what was proposed and
> what the operator approved on 2026-08-21; `tasks.md` T040 asserts the landed text was
> extracted from it byte-identically, and rewriting it would falsify an approval record.
> `CLAUDE.md` §6.AA clause 8 is authoritative. Map: (E)→(D), (F)→(E), (G)→(F),
> (H)→(G), (I)→(H); (A), (B), (C) unchanged. Note that the separate **(A)–(F)** set in
> this document's Pin-Advance Path section is a DIFFERENT lettering and is untouched.

| Field | Value |
|---|---|
| Feature | `002-build-test-distribute-pipeline` |
| Concerns | Defects found **after** amendments T040/T041/T048/T049 landed under operator approval |
| Defective text | `CLAUDE.md` §6.AA clause 8 conditions **(A)**, **(D)**, **(H)** |
| Documents this proposal would amend, if approved | `CLAUDE.md` and/or `specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json` |
| Documents changed by this proposal itself | **None.** This file is the entire output. |
| Companion record | `specs/002-build-test-distribute-pipeline/progress.yml` → `amendment_defect_found_after_landing` (lines 1185–1240) and the schema-conflict entry (lines 1336–1344) |
| Related workable item | LVA-120 (Bug, Operator-blocked) |
| Drafted | 2026-08-25 |
| Reviewer decision required | Yes — see §5 "Reviewer decision checklist" |

## 0. What this document is, and what it is not

This is a **proposal about a proposal that already landed**. Four amendments were reviewed and approved on 2026-08-21. Two defects have since been found in the landed text of one of them. Neither is fixed here.

`CLAUDE.md`, `.specify/memory/constitution.md`, and every file under `contracts/` are untouched by the work that produced this file. Amending a governing document to repair a governing document is the operator's call, not an agent's — which is the same reason the original amendments were proposed rather than applied.

**What these defects do not do.** Neither defect makes clause 8 unsafe. Conditions (B), (C), (E), (F), (G) and (I) are unaffected and individually load-bearing. Condition **(C)** — release-variant device evidence, explicitly *not* inferable from debug-variant evidence — carries the substantive protection that condition (D) was written to reinforce. Defect 1 leaves one of nine conditions decorative. Defect 2 makes the clause, read literally, unsatisfiable — which fails *closed*, not open.

**Honest provenance.** The amendment was approved in good faith on a premise about condition (D) that was incorrect. The LVA-120 investigation that established the true behaviour of `--channel` completed *after* the amendment text was drafted and reviewed. Neither defect is evidence of a review that was skipped; both are evidence of an ordering that put an investigation after the document it bore on.

Per `CLAUDE.md` §6.AD.5 (line 1018), each defect below carries an explicit `Classification:` line.

---

## 1. Independent verification

Every claim in §2 and §3 was re-derived from the files in this working tree. `grep` in this environment is a shell function wrapping `ugrep`; all exit-code-sensitive checks below were run via `bash -c` with `command grep` so the results are GNU-grep semantics, not the wrapper's.

### V-1 — `--channel` is parsed, asserted non-empty, and never used again

Every occurrence of the variable in `scripts/check-cycle-coverage.sh`:

```
4:# Enforces the §6.AK coverage-intersection contract: every CHANGELOG-claimed
32:VER="" CHAN="" EDIR="" MAP="" HEAD_FLAG="" NOW_FLAG="" STRICT=0
37:        --channel=*)      CHAN="${1#*=}"; shift 1;;
38:        --channel)        CHAN="${2:-}"; shift 2;;
51:[[ -n "$VER" && -n "$CHAN" && -n "$EDIR" ]] || { echo "FATAL: --version --channel --evidence-dir required" >&2; exit 2; }
```

Five occurrences. Line 4 is the word "CHANGELOG" inside a comment, not a use of the variable. Lines 32/37/38 declare and assign; line 51 asserts non-empty. **There is no fifth use.** The value never reaches a path, a comparison, or a branch.

### V-2 — Both artifacts resolve from `--evidence-dir` + `--version` alone

```
61:    for cand in "$EDIR/$VER-cycle-coverage-map.yaml" "$EDIR/cycle-coverage-map-$VER.yaml"; do
72:for cand in "$EDIR/$VER-test-evidence.json" "$EDIR/$VER-test-evidence.md"; do
```

`$CHAN` is absent from both resolutions.

### V-3 — `debug`, `release` and `banana` are indistinguishable

A hermetic fixture (one claim, one covering Challenge recorded PASS on a containerized runner) was run three times against the same evidence directory, varying only `--channel`:

```
channel=debug   exit=0
channel=release exit=0
channel=banana  exit=0

debug vs release stdout: IDENTICAL
debug vs banana  stdout: IDENTICAL
debug vs banana  stderr: IDENTICAL

0a75e50552116a400b62dbed9460d80b9d248a1c28249525efcdff4fb92c5cf3  out.debug
0a75e50552116a400b62dbed9460d80b9d248a1c28249525efcdff4fb92c5cf3  out.release
0a75e50552116a400b62dbed9460d80b9d248a1c28249525efcdff4fb92c5cf3  out.banana
```

Byte-identical stdout across all three, by SHA-256. `banana` is the decisive case: the flag is not merely undifferentiating, it is **unvalidated**. A typo in a future caller would pass silently.

### V-4 — No release-channel evidence artifacts exist

The evidence tree is partitioned by **app**, not by build variant:

```
.lava-ci-evidence/distribute-changelog/firebase-app-distribution/
.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app/
```

There is no `-debug` or `-release` directory at any level. Within each app directory the coverage maps and evidence files are per-version and single-copy — `cycle-coverage-map-1.3.17-1085.yaml`, `1.3.11-1070-test-evidence.md` — with no variant qualifier in any filename.

### V-5 — "Channel" carries two conflicting meanings in this feature

This is the finding that most complicates the repair, and it was not recorded before now.

- `contracts/distribution-record.schema.json:11` — `"channel": { "type": "string", "enum": ["firebase-app-distribution", "firebase-app-distribution-api-app"] }`. In the feature's own data model, a *channel* is the **Firebase app** (client vs. `:api-app`).
- `scripts/firebase-distribute.sh:140` — `CHANGELOG_DIR="$LAVA_REPO_ROOT/.lava-ci-evidence/distribute-changelog/$CHANGELOG_CHANNEL"`, and that script's own comment at line 317 calls it "the app-resolved §6.Z evidence dir". Same meaning: channel = app.
- `scripts/firebase-distribute.sh:322-324` — `AK_CHANNEL` is `release` or `debug`. Different meaning: channel = build variant.

So `check-cycle-coverage.sh` already receives the app-channel through `--evidence-dir`, and `--channel` is a *second* parameter using the *same word* for the variant axis. Condition (D)'s "the `debug` channel and the `release` channel" adopts the second meaning. Any repair under Option 1-A must first disambiguate the vocabulary, or it will encode the collision permanently.

### V-6 — The schema forbids exactly the field clause 8(H) mandates

```
8:  "additionalProperties": false,
```

Declared top-level properties: `build_artifacts`, `commit_sha`, `completed_at`, `distributions`, `evidence_summary`, `outcome`, `phases`, `run_id`, `started_at`, `submodule_advances`. `residual-gap` is not among them.

Applying `additionalProperties: false` by hand to a report that satisfies (H):

```
unknown (therefore forbidden) keys -> ['residual-gap']
VERDICT: schema-invalid  =>  condition (A) FAILS whenever (H) is satisfied
```

`UNCONFIRMED:` this was derived by applying draft-2020-12 `additionalProperties` semantics manually. The `jsonschema` Python module is not installed on this host, so no off-the-shelf validator confirmed it. The semantics of `additionalProperties: false` are not in dispute, but the reader should know no third-party validator was run.

### V-7 — The deadlock currently manifests as (H) FAIL, not (A) FAIL

`scripts/pipeline/lib/run-report.sh` — the report **generator** — contains no reference to `residual-gap`. The latest emitted report carries exactly the ten schema-declared keys:

```
['build_artifacts', 'commit_sha', 'completed_at', 'distributions',
 'evidence_summary', 'outcome', 'phases', 'run_id', 'started_at', 'submodule_advances']
```

No report under `.lava-ci-evidence/pipeline-runs/` (55 run directories) contains the string `residual-gap`. Per `scripts/pipeline/phase-05-distribute.sh:863-864`, an absent field sets condition **H = FAIL**.

**Therefore: clause 8 cannot qualify on any run today.** Not because of the tolerance in phase-05, but because the generator was never taught to emit the field the clause requires. The deadlock is real in both directions — emit the field and (A) fails under a strict validator; omit it and (H) fails — and the pipeline is presently on the second horn. This is a fail-closed posture, and it is the correct one while the defect is open.

### V-8 — `specs/**` is out of the markdown-exporter's scope

Read from `scripts/sync-markdown-exports.sh`, not assumed:

```
12:# Scope (authority: docs/chaos-stress/EXPORT-AUDIT.md):
13:#   INCLUDED: project-root *.md, docs/**/*.md, scripts/**/*.md
14:#   EXCLUDED: external/ prebuilts/ submodules/** constitution/** lava-api-go/**
```

and the candidate enumeration at lines 73–75 is `find . -maxdepth 1 -type f -name '*.md'` plus `find docs scripts -type f -name '*.md'`. `specs/` is reached by neither the include list nor the enumeration. **This document requires no `.html` / `.pdf` siblings**, consistent with the existing `constitutional-amendments-proposal.md` — whose siblings exist because they were generated deliberately, not because the gate demands them.

---

## 2. Defect 1 — Condition (D) asserts a protection that does not exist

**Severity:** medium. **Fails:** open, in the sense that a reader over-trusts it; the underlying protection is carried by condition (C).

**Forensic anchor.** Condition (D) was drafted to close the gap recorded as LVA-120: `scripts/firebase-distribute.sh` in `MODE=both` uploads the R8-minified release APK while pointing the §6.AK gate at debug-channel evidence, which is mechanically the same setup as the 1.2.19-1039 incident (the §6.Z forensic anchor, a release-variant-only cold-start crash). The intent was correct. The mechanism named to achieve it does not work, because `--channel` does not do what its name and its own header say it does.

`Classification:` **project-specific.** The failure *pattern* — a governance condition specifying a mechanism that the referenced tool does not implement — is universal and worth remembering. The specific flag, script, channel vocabulary and evidence layout are Lava's own. No propagation to other HelixConstitution-consuming projects is implied.

### 2.1 (a) Exact current text — quoted verbatim

**`CLAUDE.md` line 926 (§6.AA clause 8 condition (D)):**

> **(D) Cycle-coverage on BOTH channels.** The §6.AK Phase-1 Gate 7 cycle-coverage check has been executed and PASSED for the `debug` channel **and** for the `release` channel. This condition exists because `scripts/firebase-distribute.sh` resolves `AK_CHANNEL` to `debug` whenever `MODE` is not exactly `release` (lines 322–324), so a `--debug-and-release` invocation validates the debug channel only. Under this clause the pipeline MUST invoke `scripts/check-cycle-coverage.sh --channel=release` explicitly and record its PASS in the run report before the combined distribute is permitted. §6.AK is NOT relaxed by this amendment; this condition closes the channel gap that would otherwise let a combined invocation bypass it.

**`scripts/check-cycle-coverage.sh` lines 13–14 (the header that documents the flag):**

> ```
> #   --version=<vName-vCode>   (required)  e.g. 1.3.12-1078
> #   --channel=<chan>          (required)  e.g. debug | release
> ```

**`scripts/check-cycle-coverage.sh` line 51 (the entire extent of the flag's effect):**

> ```
> [[ -n "$VER" && -n "$CHAN" && -n "$EDIR" ]] || { echo "FATAL: --version --channel --evidence-dir required" >&2; exit 2; }
> ```

The sentence in condition (D) that is false today is: *"Under this clause the pipeline MUST invoke `scripts/check-cycle-coverage.sh --channel=release` explicitly and record its PASS in the run report."* The pipeline can invoke it, and does. The invocation examines the same two files as the `debug` invocation and returns the same bytes. Recording that as an independent release-channel PASS would be a §6.J bluff — an assertion of verification that did not occur.

### 2.2 (b) Proposed replacements — three options

---

#### Option 1-A — Make `check-cycle-coverage.sh` honour `--channel`

The script's own header documents the flag as `(required) e.g. debug | release`. That reads as original intent left unimplemented rather than as a flag added for decoration. Under this option condition (D) is left **unchanged** and the script is brought up to it.

**No replacement text for `CLAUDE.md`.** Condition (D) stands verbatim as landed. The change is entirely in `scripts/check-cycle-coverage.sh`, and the shape of that change is *not* determinable without an operator decision, because **the release-channel artifacts it would resolve do not exist** (V-4). Something must be invented before anything can be implemented. The two sub-shapes:

- **1-A-i — Variant-qualified artifacts.** Coverage maps and evidence files gain a variant suffix, so `check-cycle-coverage.sh` resolves `$EDIR/cycle-coverage-map-$VER-$CHAN.yaml` and `$EDIR/$VER-$CHAN-test-evidence.json`. Requires a producer for the release-variant files, a migration story for 68 existing per-version files across two app directories, and a decision on whether an absent release file is a FAIL (fail-closed, and the whole point) or a fall-through to the unqualified name (which reintroduces the defect silently).
- **1-A-ii — Variant-scoped records inside the existing files.** The files stay per-version; `check-cycle-coverage.sh` grows a filter so that in `--channel=release` mode only Evidence Records naming a release artifact satisfy a claim. Requires the evidence format to carry a variant field per record. `contracts/evidence-record.schema.json` is `additionalProperties: false` with properties `test_id`, `category`, `command`, `result`, `assertion_summary`, `raw_output_ref`, `anti_bluff_status` — **there is no variant field today**, so this sub-shape needs a schema amendment of its own.

**Prerequisite for either sub-shape:** resolve the vocabulary collision in V-5. `--evidence-dir` already encodes the app-channel; `--channel` would encode the variant. Two axes named "channel" in one command line is how the defect became invisible in the first place. A rename to `--variant=debug|release` — with `--channel` retained as a deprecated alias that *errors* rather than silently accepting — is the honest form.

**Also required regardless of sub-shape:** validate the value against a closed set. `banana` returning exit 0 (V-3) is a defect in its own right and survives every other option below.

---

#### Option 1-B — Reword (D) to state what it actually verifies

**Proposed replacement for `CLAUDE.md` line 926, in full:**

> **(D) Cycle-coverage executed for the version being distributed.** The §6.AK Phase-1 Gate 7 cycle-coverage check has been executed and PASSED for the exact `versionName-versionCode` of every artifact this run distributes, against the §6.Z evidence directory of that artifact's Firebase app, on the same commit, within the §6.Z freshness window. The check verifies that every CHANGELOG-claimed user-visible fix maps to a device Challenge that was executed and PASSED on a non-host-direct runner. **It verifies this once per app, per version. It does NOT verify the debug and release build variants independently, and this clause does not claim that it does:** `scripts/check-cycle-coverage.sh` accepts a `--channel` argument but does not differentiate on it — the flag is parsed and asserted non-empty and never used to resolve a path or alter a check, and both artifacts resolve from `--evidence-dir` and `--version` alone. The release-variant protection that a per-variant coverage check would provide is carried in this clause by condition **(C)**, which requires release-variant device evidence that is explicitly NOT inferable from debug-variant evidence. §6.AK is not relaxed by this amendment. **The absent per-variant coverage check is recorded as §6.AA-pipeline-debt-D and is OWED**; until it closes, condition (C) is the sole mechanical guarantee that the R8-minified release artifact was exercised, and any weakening of (C) re-opens the LVA-120 gap in full.

This is what `scripts/pipeline/phase-05-distribute.sh` already reports at runtime. Its condition-(D) disclosure string (line 714) states the same facts and labels the condition `DEGRADED`. Option 1-B aligns the governing text with the behaviour the gate already discloses, instead of leaving the two in contradiction.

---

#### Option 1-C — Retire `--debug-and-release`, which dissolves (D)'s premise

This is the **standing LVA-120 recommendation**, whose remedy list is: *"[B] retire `--debug-and-release` entirely and require the two-stage `--debug-only` then `--release-only` flow, which already evaluates both gates on the correct channels."*

The observation that makes this the strongest option on the merits: **`--release-only` already resolves `AK_CHANNEL=release` and already evaluates the §6.AA staging gate** (`scripts/firebase-distribute.sh:322`, and the staging gate guarded on `MODE == "release"` at line 204). The two-stage path does not have the defect. Only the combined mode does. Condition (D) exists solely because the combined mode exists.

**Proposed change to `CLAUDE.md` line 926 — strike condition (D) entirely**, and replace the reference to it in the clause-8 preamble and Standing note. Because clause 8 is conjunctive over nine lettered conditions, striking one requires either re-lettering (E)–(I) — which invalidates every existing cross-reference in `phase-05-distribute.sh`, `progress.yml`, `tasks.md` and this document — or leaving (D) as a reserved, explicitly-retired marker. **The second is recommended:**

> **(D) — RETIRED 2026-08-2X.** This condition required cycle-coverage on the `debug` and `release` channels separately. It existed only because the combined `--debug-and-release` invocation pointed the §6.AK gate at debug-channel evidence while uploading the release APK (LVA-120). With the combined invocation retired, the pipeline distributes via `--debug-only` followed by `--release-only`, and each stage's §6.AK gate resolves the correct channel by construction. The condition is retained as a numbered placeholder so that references to conditions (E)–(I) elsewhere remain stable. **This letter is retired, not vacant: no future condition may be assigned to it.**

**Proposed consequential change to `CLAUDE.md` line 908 (§6.AA clause 3)** — the sentence granting a standing authorization for the combined invocation becomes dead text and must be struck, or clause 8 will continue to authorize a mode that no longer exists:

> ~~**A qualifying Pipeline Run Report under clause 8 constitutes a STANDING per-cycle authorization for the combined invocation, and its `run_id` MUST be written verbatim into the `combined-distribute-authorization:` field so the audit trail names the exact run that authorized it.**~~ **A qualifying Pipeline Run Report under clause 8 constitutes a STANDING per-cycle authorization for the pipeline to run the `--debug-only` and `--release-only` stages back-to-back without an operator pause between them, and its `run_id` MUST be written verbatim into the `combined-distribute-authorization:` field of BOTH stages so the audit trail names the exact run that authorized each. This standing authorization is available ONLY to `scripts/pipeline/phase-05-distribute.sh`.**

Note what this preserves and what it does not: clause 8 still removes the **human pause**, which is the operator's stated requirement (`spec.md:12`, "no pause, every run"). It removes only the **combined flag**, which was never the requirement — merely the implementation the task description reached for.

### 2.3 (c) Migration notes

**If Option 1-A:**

| # | Action | Owner |
|---|---|---|
| 1 | Operator decides 1-A-i (variant-qualified files) vs 1-A-ii (variant-scoped records). This is a data-model decision and it cannot be inferred. | Operator |
| 2 | Rename `--channel` to `--variant`; retain `--channel` as an alias that **errors** with a pointer to the new flag. Add closed-set validation so `banana` exits 2. | Follow-up task |
| 3 | If 1-A-i: build a producer for release-variant maps and evidence; decide the absent-file policy (fail-closed recommended); migrate or grandfather 68 existing per-version files across both app directories. | Follow-up task |
| 4 | If 1-A-ii: amend `contracts/evidence-record.schema.json` to add a variant field (it is `additionalProperties: false`). This is a second contract amendment. | Follow-up task |
| 5 | Update `scripts/pipeline/phase-05-distribute.sh` to drop the `DEGRADED` disclosure at line 714 and assert a genuine two-invocation PASS. | T043 follow-up |
| 6 | Extend `tests/cycle-coverage/` with a RED-first case proving a release-variant regression is caught when debug-variant evidence is clean. Without this the repair is unfalsifiable, which is the §6.N bar. | Follow-up task |
| 7 | `CLAUDE.md` unchanged. | — |

**If Option 1-B:**

| # | Action | Owner |
|---|---|---|
| 1 | Replace `CLAUDE.md` line 926 with the §2.2 Option 1-B text. | Operator + reviewer |
| 2 | Add the `§6.AA-pipeline-debt-D` entry to the open-debt block at `CLAUDE.md:303`, alongside the existing `§6.AA-pipeline-debt`. | Operator + reviewer |
| 3 | Update `scripts/pipeline/phase-05-distribute.sh`: the condition-(D) disclosure at line 714 changes from `DEGRADED` to a plain PASS **citing the reworded clause**, and the header note at lines 70–74 is updated to record that the governance question was decided. The disclosure text must not be deleted — it becomes the evidence that the limitation is known and accepted. | T043 follow-up |
| 4 | Still fix the `banana` case: validate `--channel` against a closed set, or remove the flag from the interface entirely. A parameter that accepts anything is a latent defect under every option. | Follow-up task |
| 5 | File the per-variant coverage check as a workable item so the debt is tracked, not merely mentioned. | Operator |
| 6 | No evidence file, run report, or distribution record requires migration. | — |

**If Option 1-C:**

| # | Action | Owner |
|---|---|---|
| 1 | Replace `CLAUDE.md` line 926 with the retired-marker text, and line 908 with the two-stage authorization text. Both in one commit. | Operator + reviewer |
| 2 | Change `scripts/pipeline/phase-05-distribute.sh` to expect eight live conditions plus one retired marker. Its "all nine clause 8 conditions" output strings (lines 911, 1008, 1021) become inaccurate and must change. | T043 follow-up |
| 3 | Change the T043 distribute invocation to two sequential stages. **This is the substantive behavioural change** and requires its own falsifiability rehearsal: a RED case proving the release stage refuses when release-channel evidence is absent. | T043 |
| 4 | Retire the flag in `scripts/firebase-distribute.sh:56` (`--debug-and-release|--both`). Decide whether it errors loudly or is deleted. Erroring loudly is recommended, so an existing caller fails visibly rather than changing meaning. | Follow-up task |
| 5 | Convert `tests/firebase/repro_mode_both_channel_gap.sh` into a standing regression test. Its header documents inverted exit semantics: **0 = gap reproduced, 1 = gap gone, 2 = extraction failed**. Its exit must become 1, and its role must flip from reproducer to guard. This is LVA-120's own stated unblock signal. | Follow-up task |
| 6 | Close LVA-120 with the remedy recorded and the regression test named. | Operator |
| 7 | The word "channel" remains overloaded (V-5) but stops being load-bearing in governance text, since no clause then distinguishes debug-channel from release-channel coverage. | — |

### 2.4 (d) Risk analysis

**Option 1-A — protection gained:** a genuine per-variant coverage check. This is the only option that delivers what condition (D) currently claims. The §6.Z forensic anchor was a release-variant-only failure, and a coverage gate that cannot see the release variant is blind to exactly that class.

**Option 1-A — protection given up:** none directly. The risk is different in kind. This option invents a data model under time pressure to satisfy a sentence already written into the constitution, which inverts the correct order — the artifact design should determine the clause, not the reverse. A hurried variant-qualified layout with a fall-through on missing files would produce a check that reports PASS on debug evidence while claiming release coverage: the identical defect, now with an implementation behind it and therefore harder to see. **If this option is chosen, the absent-file policy must be fail-closed and must be tested RED-first.** There is also a real cost: this is the most expensive of the three, and until it lands clause 8 continues to carry a decorative condition.

**Option 1-B — protection gained:** honesty. A reader of clause 8 stops concluding that release-channel coverage was independently verified. Under §6.J that is not a cosmetic gain — a condition that reads as a protection and delivers none is the precise bluff class this project has recorded three times.

**Option 1-B — protection given up:** none that currently exists. The per-variant check is absent today; rewording does not remove it. What is surrendered is the *aspiration* — the clause stops asserting the stronger property, and the gap moves into a debt entry, where debts in this project have a documented tendency to remain open for long periods (§6.X-debt, §6.Z-debt, §6.AB-debt, §6.AC-debt are all still open or partially open). **The honest risk is that the debt is never paid and the weaker text becomes permanent.** Filing it as a tracked workable item rather than a prose mention is the mitigation, and it is a weak one.

**Option 1-C — protection gained:** the largest, and it is not a governance gain but a mechanical one. Retiring the combined mode removes the LVA-120 defect **from the distribute script itself**, not merely from the pipeline's use of it. The two-stage path already evaluates §6.AK on the correct channel and already evaluates the §6.AA staging gate. Every future caller — pipeline, operator, hotfix — inherits the fix. Options 1-A and 1-B leave `--debug-and-release` in place with its hole intact for any hand invocation.

**Option 1-C — protection given up:** none. The operator's requirement is no human pause; two stages back-to-back within one pipeline run satisfies that. **The honest cost is not safety but work:** T043 must be re-specified from a single invocation to a sequenced pair, and a failure between the two stages needs defined semantics — debug distributed, release refused, run report recording a partial distribution. That intermediate state does not exist today and needs designing. `contracts/pipeline-run-report.schema.json` permits it (`distributions[]` is an array and `outcome` includes `FAIL`), so no schema change is forced, but the orchestrator's behaviour on a half-completed distribute must be specified rather than discovered.

**Recommendation: Option 1-C, with Option 1-B's rewording of (D) applied as part of it.**

Reasoning. 1-C is the only option that fixes the *defect* rather than the *description of the defect*, and it does so in the shared script where every caller benefits. It is also the remedy LVA-120 already recommends after a line-by-line investigation of the real script, which is a stronger evidentiary basis than this document's own analysis. It does not compromise the operator's unattended requirement. 1-B is recommended *alongside* it rather than instead of it, because even with the combined mode retired, the per-variant coverage check still does not exist — condition (D)'s replacement text must say so plainly rather than leaving a retired marker that implies the property was achieved by other means. 1-A is the right long-term destination and the wrong immediate move: it should be entered deliberately, with the artifact design decided first, not reverse-engineered from a sentence.

**Where this recommendation is uncertain, stated plainly:** the partial-distribution semantics under 1-C (debug uploaded, release refused) is a design question I have not resolved and cannot resolve without knowing whether the operator considers a debug-only distribution a successful run or a failed one. That answer changes the run report's `outcome` value and the pipeline's exit code. **This needs a decision I cannot make.**

---

## 3. Defect 2 — Conditions (A) and (H) deadlock

**Severity:** medium. **Fails:** closed. No run can qualify under clause 8 today.

**Forensic anchor.** The original proposal's own migration plan anticipated this. `constitutional-amendments-proposal.md` §2.5 step 5 reads: *"Add `residual-gap` and `combined-distribute-authorization` fields to the run report emitted by `run-report.sh`, and extend `pipeline-run-report.schema.json` accordingly (conditions (H) and clause 3)."* The clause landed; step 5 did not. The defect is a migration step that was specified and then not executed, and the two halves are now in direct contradiction.

`Classification:` **project-specific.** The pattern — a governing clause mandating a field that a machine-checked contract forbids — is universal and worth remembering. The specific schema, field name and run-report shape are Lava's own.

### 3.1 (a) Exact current text — quoted verbatim

**`CLAUDE.md` line 920 (condition (A)), first sentence:**

> **(A) Run Report identity.** A Pipeline Run Report exists at `.lava-ci-evidence/pipeline-runs/<run_id>/report.json`, validates against `specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json`, and its `commit_sha` equals `git rev-parse HEAD` at the moment of the distribute invocation.

**`CLAUDE.md` line 934 (condition (H)), final sentence — the mandate:**

> Until that debt closes, every run report authorized under this clause MUST carry the field `residual-gap: firebase-install-path-unverified` so the gap is visible in the artifact rather than only in this rule.

**`contracts/pipeline-run-report.schema.json` line 8:**

> ```
>   "additionalProperties": false,
> ```

The ten declared top-level properties are listed in V-6. `residual-gap` is not among them. (A) requires validation against a schema that forbids the field; (H) requires the field. Read literally, no run can ever qualify.

**`scripts/pipeline/phase-05-distribute.sh` lines 76–89 — the interim measure, quoted so the reviewer sees it was declared, not hidden:**

> ```
> # 2. CONDITIONS (A) AND (H) CONFLICT UNDER THE CURRENT SCHEMA.
> #    (A) requires the report to validate against
> #    contracts/pipeline-run-report.schema.json. That schema is
> #    "additionalProperties": false and does not define a `residual-gap`
> #    property. (H) requires every clause-8-authorized report to CARRY the
> #    field `residual-gap: firebase-install-path-unverified`. Taken
> #    literally, both cannot hold: the field (H) mandates is a property (A)
> #    forbids, so no run could ever qualify.
> #    This script resolves the deadlock in the only direction that keeps the
> #    clause operable: `residual-gap` is treated as the one clause-8-mandated
> #    extension property, and EVERY OTHER unknown property still fails (A).
> #    CLAUDE.md is the constitution and clause 8 postdates the schema, so the
> #    schema needs an amendment. That amendment is OWED and is flagged on
> #    every run — this script cannot make it, as specs/ is not its to edit.
> ```

Per V-7, this tolerance is not currently reached: the generator emits no `residual-gap`, so condition (H) fails first. The tolerance is a correct anticipation of a state the pipeline has not yet entered.

### 3.2 (b) Proposed replacements — three options

---

#### Option 2-A — Amend the schema to define `residual-gap`

**Proposed addition to `contracts/pipeline-run-report.schema.json`, inserted into `properties` after `submodule_advances` (line 66):**

```json
    "residual-gap": {
      "type": "string",
      "enum": ["firebase-install-path-unverified"],
      "description": "§6.AA clause 8(H) disclosure. Present on every run report authorized under the clause-8 Pipeline Distribution Path, recording that the Firebase upload -> download -> install path on a physical device is NOT covered by this run's evidence. Removed only when §6.AA-pipeline-debt closes."
    }
```

`residual-gap` is deliberately **not** added to `required`. A run report produced outside the clause-8 path carries no such disclosure and must stay valid.

Two properties of this draft are load-bearing:

- **`enum` with a single member, not a free `string`.** The field is a disclosure of one specific named gap. A free string permits `residual-gap: "none"` or `residual-gap: ""`, which would satisfy (H)'s letter while inverting its meaning. The enum makes the only valid value the honest one. `phase-05-distribute.sh:861-866` already enforces exactly this at runtime — the schema should agree rather than be laxer.
- **The hyphen is preserved, and it is inconsistent.** Every other property in every contract schema in this feature is `snake_case`: `run_id`, `commit_sha`, `build_artifacts`, `anti_bluff_status`, `version_code`. `residual-gap` is hyphenated because `CLAUDE.md` clause 8(H) wrote it that way. **Renaming it to `residual_gap` would require amending `CLAUDE.md` too**, and this proposal does not assume the operator wants a governing-document edit for a style point. The alternative — schema defines `residual_gap`, clause mandates `residual-gap` — would be a second deadlock, so the two must agree either way. Flagged for the reviewer as a decision, not a detail.

---

#### Option 2-B — Reword (H) to require the disclosure where the schema already permits it

**Proposed replacement for the final sentence of `CLAUDE.md` line 934:**

> Until that debt closes, every run authorized under this clause MUST record the disclosure `residual-gap: firebase-install-path-unverified` in a sidecar file at `.lava-ci-evidence/pipeline-runs/<run_id>/clause-8-disclosures.json`, alongside the run report and referenced by it through the run's own evidence directory, so the gap is visible in the run's artifacts rather than only in this rule. The disclosure lives beside the run report rather than inside it because `contracts/pipeline-run-report.schema.json` is `additionalProperties: false` and the run report's shape is a machine contract that this clause does not amend.

The available in-schema alternatives were checked and are all worse:

- **`phases[].evidence_dir`** (a plain `string`) and **`distributions[].evidence_ref`** (`string`, `minLength: 1`) could physically carry disclosure text. Both are path fields; stuffing a disclosure into a path is an abuse that a future reader would not find and a future validator could tighten away.
- **`contracts/evidence-record.schema.json`** is also `additionalProperties: false` with seven fixed properties, so an Evidence Record cannot carry it either without its own amendment.
- A **sidecar file** is genuinely unconstrained: no schema governs `.lava-ci-evidence/pipeline-runs/<run_id>/` beyond `report.json` itself.

**Honest weakness of this option:** clause 8(H)'s stated purpose is that *"the gap is visible in the artifact rather than only in this rule."* The run report is **the** artifact — the single consolidated machine-readable record, per the schema's own description. A sidecar is one indirection further from the thing a reviewer opens. This option satisfies the letter of the intent and weakens its spirit.

---

#### Option 2-C — Relax `additionalProperties`

**Proposed replacement for `contracts/pipeline-run-report.schema.json` line 8:**

```json
  "additionalProperties": true,
```

`CLAUDE.md` line 920 needs no change. Condition (A) is satisfied because the schema stops objecting.

**This option weakens a real protection, and this document says so rather than presenting three equivalent choices.**

What `additionalProperties: false` currently buys: a typo'd or renamed key is caught at validation. If `run-report.sh` emitted `commit-sha` instead of `commit_sha`, the `required` check catches the absence — but if it emitted `evidence_summary_v2` alongside a stale `evidence_summary`, or `outcome_final` beside `outcome`, `additionalProperties: false` is what rejects the report instead of letting a consumer read the wrong field. That is a live concern for this specific artifact: `phase-05-distribute.sh` reads the report with `jq` to decide whether a **distribution to real users** is authorized. A report that validates while carrying an unexpected key is a report whose shape drifted without anyone being told.

The nested objects would retain their own `additionalProperties: false` (`phases[]` items line 20, `build_artifacts[]` items line 37, `evidence_summary` line 50), so this relaxes only the top level. That limits the blast radius; it does not change the character of what is given up.

### 3.3 (c) Migration notes

**Common to all three options — this must happen regardless, and is the reason nothing qualifies today (V-7):**

| # | Action | Owner |
|---|---|---|
| 0 | `scripts/pipeline/lib/run-report.sh` must emit the disclosure. It contains no reference to `residual-gap` and no report under `.lava-ci-evidence/pipeline-runs/` (55 runs) carries it. **Whichever option is chosen, condition (H) fails on every run until the generator is changed.** The emission must be conditional on the clause-8 path, so that non-clause-8 runs do not carry a disclosure about an authorization they never sought. | T039 follow-up |

**If Option 2-A:**

| # | Action | Owner |
|---|---|---|
| 1 | Add the `residual-gap` property to the schema. Do **not** add it to `required`. | Operator + reviewer |
| 2 | Decide the hyphen-vs-underscore question. If `residual_gap` is preferred, `CLAUDE.md` line 934 must change in the same commit — otherwise the deadlock recurs in a new form. | Operator |
| 3 | Simplify `phase-05-distribute.sh` lines 349–371: remove `CLAUSE8_EXTENSION`, remove the `AMEND` output, and remove the `SCHEMA AMENDMENT OWED` block at lines 949–953. The tolerance becomes dead code and must not linger — a tolerance for a resolved conflict is a hole waiting for a second occupant. | T043 follow-up |
| 4 | Update the header note at lines 76–89 to record that the deadlock was resolved and how. | T043 follow-up |
| 5 | Add a case to `tests/pipeline/test_evidence_and_run_report.sh` asserting a report **with** `residual-gap` validates and one with an arbitrary extra key does **not**. That test currently references the schema but asserts nothing about `additionalProperties`. | Follow-up task |
| 6 | The 55 existing reports remain valid — the property is optional. | — |

**If Option 2-B:**

| # | Action | Owner |
|---|---|---|
| 1 | Replace the final sentence of `CLAUDE.md` line 934. | Operator + reviewer |
| 2 | Change `phase-05-distribute.sh:860` to read the sidecar rather than `jq` the report. The three-branch verdict at lines 861–867 is otherwise unchanged. | T043 follow-up |
| 3 | Remove the schema tolerance (lines 349–371) and the `SCHEMA AMENDMENT OWED` block (949–953): with (H) satisfied outside the report, the report carries no extension and `additionalProperties: false` stands unmodified. **This is the option's real advantage — the schema's strictness is preserved untouched.** | T043 follow-up |
| 4 | The generator writes the sidecar rather than a report field (common step 0 adjusts accordingly). | T039 follow-up |
| 5 | No schema file changes. No existing report requires migration. | — |

**If Option 2-C:**

| # | Action | Owner |
|---|---|---|
| 1 | Change line 8 to `true`. | Operator + reviewer |
| 2 | Remove the tolerance and the `SCHEMA AMENDMENT OWED` block, as with 2-A. | T043 follow-up |
| 3 | **Add a compensating check**, because the schema no longer provides one: an explicit assertion in `phase-05-distribute.sh` that the report's top-level key set equals the ten declared properties plus at most `residual-gap`. This reimplements in a script what the schema was doing declaratively — which is a reason to prefer 2-A, and should be weighed as such. | T043 follow-up |
| 4 | Record in the schema's `description` that top-level strictness was deliberately surrendered and why, so a future reader does not "fix" it back and break clause 8. | Operator + reviewer |
| 5 | No existing report requires migration. | — |

### 3.4 (d) Risk analysis

**Option 2-A — protection gained:** both conditions become simultaneously satisfiable with no protection surrendered anywhere. `additionalProperties: false` continues to reject every unexpected key except the one the constitution mandates, and the single-member `enum` means the mandated key can only carry the honest value. The schema becomes the accurate machine encoding of what clause 8 requires, which is what a contract is for.

**Option 2-A — protection given up:** none identified. The residual concern is a governance-shape one: a constitutional clause is driving a machine contract's shape, so every future clause-8 amendment that names a new field requires a paired schema amendment or reproduces this exact deadlock. That coupling is inherent to (H)'s design — it mandates a field in a schema-governed artifact — and it is worth the reviewer noting as a standing maintenance obligation rather than a defect.

**Option 2-B — protection gained:** the schema is untouched and its strictness is fully preserved. This is the cheapest option in contract-surface terms and carries no coupling obligation.

**Option 2-B — protection given up:** disclosure visibility. Clause 8(H) exists because the Firebase install-path gap is real and unclosed; putting the disclosure in the run report means anyone auditing an authorized distribution sees the gap in the same file as the authorization. A sidecar can be missed, can be deleted independently, and is not covered by the run report's own validation. **For a clause whose entire purpose is honest visibility of a known gap, moving the disclosure one file away works against its reason for existing.** This option is defensible; it is not the one that best serves (H)'s intent.

**Option 2-C — protection gained:** the deadlock resolves with a one-word edit and no governing-document change.

**Option 2-C — protection given up: real, and larger than the problem it solves.** Top-level shape enforcement on the artifact that authorizes distributions to real users is surrendered permanently, to accommodate one known field. `phase-05-distribute.sh` reads this report with `jq` to decide whether release APKs ship; a silently-drifted report shape is exactly the input that should be rejected loudly. Step 3 of its migration reimplements the lost check in bash — which concedes the point: the protection is still wanted, just enforced somewhere weaker and less visible than the contract that declares the shape. **This option is presented because it was asked for, and is not recommended.**

**Recommendation: Option 2-A.**

Reasoning. It is the only option that leaves every protection intact while making the clause satisfiable, and it is what the original migration plan (§2.5 step 5) already specified before the clause landed without it — so it closes an owed step rather than inventing a new direction. The single-member `enum` makes the schema strictly *more* protective than a bare permitted field, since it forbids a dishonest value that even today's runtime check would have to catch on its own. 2-B is a reasonable fallback if the operator prefers never to amend a contract schema for a constitutional field, accepting reduced visibility as the price. 2-C should be rejected: it trades a durable structural protection for a convenience, on the one artifact where shape drift has the highest consequence.

**One point where I am uncertain and say so:** whether `residual-gap` should be hyphenated or renamed to `residual_gap` for consistency with every other property in every contract in this feature. Consistency argues for the rename; the rename requires editing `CLAUDE.md`, and expanding a defect repair into a style change to a governing document is not a call an agent should make. **Either is workable provided both documents agree** — and if they do not, defect 2 recurs in a new form.

---

## 4. How the two defects interact

They are independent in cause and coupled in effect.

**Coupled:** clause 8 is conjunctive over nine conditions with no partial qualification. Defect 2 alone means no run qualifies (V-7). Repairing defect 1 while leaving defect 2 open changes nothing observable — the clause still cannot be reached. **Defect 2 is therefore on the critical path and defect 1 is not.** If the operator wishes to restore clause 8 to an operable state with a single decision, it is D-4 below.

**Independent:** the repairs touch different files and can land separately. Nothing in §2 constrains §3 or the reverse. The single exception is Option 1-C, which changes what a "distribution" is within one run (two stages, not one), and so interacts with the partial-distribution semantics flagged as unresolved at the end of §2.4 — a question that also touches what the run report records, and therefore brushes against §3's territory.

**A shared observation worth stating once.** Both defects have the same shape: a governing clause naming a mechanism, where the mechanism does not do what the clause asserts. In defect 1 the flag does not differentiate. In defect 2 the schema does not permit. In both cases the clause text was written from a reasonable belief about a component's behaviour that was not verified against the component at drafting time. The proposal template already demands a forensic anchor, a migration plan and a risk analysis; **it does not demand that each mechanical assertion in the draft be executed against the real tool before the draft is reviewed.** That is a gap in the amendment procedure itself, and closing it is cheaper than repairing its output. It is raised here as an observation for the operator, not drafted as a further amendment — this document is already a proposal to fix a proposal, and it is not the place to start a third.

---

## 5. Reviewer decision checklist

Nothing below has been decided. Each item requires an explicit operator answer before any governing file or schema is edited.

| # | Decision | Options | This proposal recommends |
|---|---|---|---|
| D-1 | Defect 1 remedy. | 1-A (implement `--channel`) / 1-B (reword (D)) / 1-C (retire `--debug-and-release`) / combination | **1-C together with 1-B's rewording.** 1-C fixes the defect in the shared script for every caller and is LVA-120's own recommendation; 1-B keeps (D)'s replacement text honest about the per-variant check that still does not exist. |
| D-2 | Under 1-C, what is a run whose debug stage distributed and whose release stage refused? | Successful partial / failed run / new outcome value | **No recommendation — this needs a decision I cannot make.** It determines the run report's `outcome` and the pipeline's exit code. |
| D-3 | Validate `--channel` against a closed set, so `banana` stops exiting 0? | Yes / remove the flag entirely / leave as-is | **Yes, or remove the flag.** This survives every defect-1 option and is a latent defect on its own. |
| D-4 | Defect 2 remedy. | 2-A (amend schema) / 2-B (reword (H) to a sidecar) / 2-C (relax `additionalProperties`) | **2-A.** Only option preserving every protection while making the clause satisfiable; it also closes the owed step 5 of the original migration plan. **2-C is not recommended** — §3.4 states what it gives up. |
| D-5 | `residual-gap` or `residual_gap`? | Keep hyphen (matches `CLAUDE.md`) / rename both (matches every other contract property) | **No recommendation.** Either works if both documents agree. Renaming requires a `CLAUDE.md` edit for consistency alone, which is the operator's call. |
| D-6 | Teach `run-report.sh` to emit the clause-8 disclosure. | Yes, conditional on the clause-8 path / yes, unconditionally / no | **Yes, conditional on the clause-8 path.** Required under every defect-2 option; unconditional emission would attach a disclosure to runs that never sought the authorization. |
| D-7 | Land both repairs in one commit, or separately? | One commit / separate | **Separate.** Independent causes, different files, and defect 2 is on the critical path while defect 1 is not. Batching delays the one that unblocks the clause. |
| D-8 | Close LVA-120 under the chosen defect-1 remedy? | Close with 1-C / keep open under 1-A or 1-B | **Close only under 1-C.** LVA-120's stated unblock signal is `tests/firebase/repro_mode_both_channel_gap.sh` exiting 1; only 1-C produces that. |
| D-9 | Add a verify-the-mechanism step to the amendment procedure (§4)? | Yes / no / defer | **Defer to a separate proposal.** Both defects share this root cause, but a third nested proposal is not the right vehicle. |

**On approval**, the changes in §2 and §3 may be applied to `CLAUDE.md` and/or `contracts/pipeline-run-report.schema.json` as the answers direct. **Until then, both defects remain open, clause 8 remains unqualifiable, and no governing document or schema may be edited.**

---

## 6. Verification appendix — what was and was not run

**Run, with output quoted in §1:** every `CHAN` occurrence traced in `check-cycle-coverage.sh`; the three-channel hermetic fixture including `banana`, compared by SHA-256; the evidence-tree layout survey; the top-level key set of the most recent run report; a `residual-gap` search across all 55 run directories; the `channel` enum in `distribution-record.schema.json`; the property set of `evidence-record.schema.json`; the scope block and candidate enumeration of `sync-markdown-exports.sh`.

**Not run:** no off-the-shelf JSON-Schema validator (the `jsonschema` module is not installed on this host) — the `additionalProperties: false` conclusion in V-6 was derived by applying the specification's semantics by hand. No Gradle, no emulator, no `scripts/firebase-distribute.sh`, no git state-changing command. The fixture in V-3 was written under the session scratchpad, not in the repository.

**Not modified:** `CLAUDE.md`, `.specify/memory/constitution.md`, every file under `contracts/`, and every script named in this document. The single file this work created is the one you are reading.
