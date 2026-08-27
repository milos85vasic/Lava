# Constitutional Amendments Proposal — Local Build-Test-Distribute Pipeline

**Status: DRAFT — PROPOSED, NOT APPLIED. Requires operator approval before any governing document is touched.**

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
| Covers tasks | T040, T041, T048, T049 (all four are `[REVIEW]` human gates) |
| Source analysis | `specs/002-build-test-distribute-pipeline/research.md` R-001, R-003, R-005; `plan.md` Constitution Check + Complexity Tracking; `spec.md` Constitutional Impact note |
| Documents this proposal would amend, if approved | `CLAUDE.md`, `.specify/memory/constitution.md` |
| Documents changed by this proposal itself | **None.** This file is the entire output. |
| Drafted | 2026-08-21 |
| Reviewer decision required | Yes — see §7 "Reviewer decision checklist" |

## 0. What this document is, and what it is not

This is a **proposal**. No governing document has been modified. `CLAUDE.md`, `.specify/memory/constitution.md`, every `AGENTS.md` / `QWEN.md` / `CONSTITUTION.md`, and everything under `constitution/` and `submodules/` are untouched by the work that produced this file.

Two of these four amendments (**T040/T041**) **relax a rule that exists because this project shipped a build that crashed on every cold launch** (Lava-Android-1.2.19-1039 — the §6.Z forensic anchor). The drafts below are written to be reviewed on that basis, not rubber-stamped. §6 states plainly what protection is surrendered and whether the compensating controls are sufficient. On one point — T040 as scoped by `research.md` R-001 alone — the honest verdict is **NOT ACCEPTABLE without the additional controls drafted here**. That finding is stated up front in §6.1 rather than buried.

Per `.specify/memory/constitution.md` → Governance → Amendment procedure (lines 202–211), each draft carries: the section modified, a **forensic anchor**, a **migration plan**, a **version-bump rationale**, and — per `CLAUDE.md` §6.AD.5 (line 990) — an explicit **`Classification:`** line.

---

## 1. Findings that shape these drafts

Five facts were established by reading the actual code and files, not by trusting prose. Each changes what an honest amendment must say. Three of the five are not recorded in `research.md`.

### F-1 — `--debug-and-release` structurally skips the only mechanical §6.AA gate

`scripts/firebase-distribute.sh:204` opens the §6.AA staging check with a condition requiring `"$MODE" == "release"`. `T043` invokes `--debug-and-release`, which sets `MODE="both"` (`scripts/firebase-distribute.sh:56`). `both` is not `release`, so that gate never evaluates.

`research.md` R-003 records accurately that no script change is needed. The governance consequence — that the pipeline's chosen invocation path is the one path with no staging check at all — is not drawn there. It is drawn here.

### F-2 — In `MODE=both`, the §6.AK cycle-coverage gate checks the DEBUG channel only

`scripts/firebase-distribute.sh:322-324`:

```bash
case "$MODE" in
    release) AK_CHANNEL="release" ;;
    *)       AK_CHANNEL="debug"   ;;
esac
```

`both` falls through to the `*` branch. Phase-1 Gate 7 (lines 309–341) therefore validates **debug-channel** evidence, and the script then proceeds to distribute the **release** APK as well. The release variant is R8-minified and resource-shrunk; the 1.2.19-1039 crash was a release-variant-only failure of exactly that class.

**Consequence: absent a compensating control, a combined distribute ships the release APK with zero release-channel device evidence checked by any gate.** This fact is the reason §6.1's verdict is conditional rather than a simple approve.

### F-3 — The pipeline creates no release tag, so Seventh Law clause 3 is never reached by it

`contracts/cli-contract.md:14` enumerates the pipeline's side effects: evidence-directory creation, systemd user-unit management, git pushes, "may create Firebase App Distribution releases". No tag creation. No task in `tasks.md` invokes `scripts/tag.sh`.

Seventh Law clause 3 (`CLAUDE.md:238`) gates **release tags**, not distributions. `spec.md`'s Constitutional Impact item 2 and `plan.md`'s Constitution Check both describe clause 3 as "exactly what FR-011 replaces"; mechanically, FR-011 does not reach it.

**Consequence: relaxing clause 3 would be a governance leak — broader than the pipeline needs.** T040 is drafted accordingly; the broader variant is recorded as REJECTED, with reasoning, in §2.4.

### F-4 — `.specify/memory/constitution.md` does not contain the §6.AA rule that T040 amends

That file has no two-stage-distribute rule, no §6.AA, no §6.Z, no §6.AK. Its nearest clauses — Principle II clause 5 (line 68), Principle III clause 3 (line 88), and "Release tagging" (lines 182–188) — are all **tag-time**.

**Consequence: T041 cannot be a mirror-edit of the same sentence.** It must add a distribution-path subsection, handled under Amendment procedure item 5 (`.specify/memory/constitution.md:211`). See §3.

### F-5 — `scripts/advance-all-submodules.sh` has no exclusion for the `constitution/` submodule

Its header (`scripts/advance-all-submodules.sh:59-61`) documents an optional allow-list environment variable `LAVA_ADVANCE_SUBMODULES` whose "Default: every submodule." Searching that script for `constitution` returns no match — there is no default deny-list.

`CLAUDE.md:299` states: *"The `./constitution/` submodule itself remains pinned + advanced only per CONST-049's 7-step pipeline."*

**Consequence: as implemented, an unattended run would auto-advance the project's own governance submodule, contradicting that rule.** T048 writes the exclusion into the amendment text; §4.5 records the matching implementation defect.

### Supporting facts (positive, and load-bearing for the compensating controls)

- **The §6.Z / §6.AK gate is real code today, not paper.** `scripts/check-cycle-coverage.sh` exists, is executable, and is wired as Phase-1 Gate 7. Per the header comment at `scripts/firebase-distribute.sh:317-319`, it "also subsumes the §6.Z-debt runtime checks (evidence presence + commit-SHA match + ≤24h freshness → exit 2 / exit 1)". `tests/cycle-coverage/` exists.
- **Device evidence produced on this host can be genuine device evidence.** `nezha` (Linux x86_64) exposes `/dev/kvm`. The §6.AH container/VM emulator gate is physically runnable here; the §6.AH-debt macOS blocker does not apply. `tasks.md` T024 records a real containerized run (`accel=kvm runner=containerized`, zero live ADB devices).
- **One scope limit is still open at the time of writing.** `tasks.md` T038 records that the `live_verify` phase wires the `lava-api-go` half only; the `:api-app` emulator half (T037) is unimplemented. A green `live_verify` today does **not** mean both live surfaces are proven. Condition (E) of the T040 draft is written so that today's partial live-verification does not qualify.

---

## 2. Amendment T040 — `CLAUDE.md` §6.AA and Seventh Law clause 3

### 2.1 Amendment metadata

**Section modified:** `CLAUDE.md` §6.AA (Two-Stage Distribute Mandate) clauses 2 and 3, plus a new clause 8. `CLAUDE.md` Seventh Law clause 3 — clarifying scope only, **no relaxation** (see §2.4).

**Forensic anchor.** Two real events, in tension, both cited:

1. *The event that created the rule.* Lava-Android-1.2.19-1039 was distributed to debug and release in a single sweep. The release APK crashed on every cold launch at `androidx.compose.ui.res.PainterResources_androidKt.loadVectorResource (PainterResources_android.kt:97)` because `R.drawable.ic_lava_logo` was a `<layer-list>` XML, which `painterResource()` does not support (Crashlytics `40a62f97a5c65abb56142b4ca2c37eeb`, FATAL, 5 events / 2 users, Samsung Galaxy S23 Ultra / Android 16). §6.AA exists to force debug-first staging so that this failure class surfaces at the smaller blast radius. **This amendment removes the human step in that staging, and must therefore be read as operating directly against this incident.**
2. *The event that motivates the amendment.* The operator's resolved clarification in `spec.md:12`: *"Fully unattended — the pipeline distributes debug AND release back-to-back with no pause, every run."* `plan.md`'s Complexity Tracking records that the lower-risk alternative (stop at debug, keep the human gate) was presented as the recommended option during `/speckit-specify` and was explicitly not chosen. This is a deliberate, informed operator choice, and the correct response is a reviewed amendment rather than a silent code-level bypass.

**`Classification:` project-specific.** The *pattern* (machine evidence substituting for a human staging confirmation under enumerated conditions) is reusable; the specific artifacts, gate scripts, channels, and evidence paths named below are Lava's own. No propagation to other HelixConstitution-consuming projects is implied.

### 2.2 (a) Exact current text being changed — quoted verbatim

**`CLAUDE.md` line 900 (§6.AA clause 2):**

> 2. **Stage 2 — Release variant only.** ONLY AFTER the operator confirms in writing that the **Firebase-distributed debug variant** works correctly on the failure-surface device class, `scripts/firebase-distribute.sh --release-only` distributes the release APK. The §6.Z evidence file is APPENDED with a `release-stage` section recording the release-APK test execution + operator real-device verification on the release variant. Same SHA as stage 1 (no code change between stages); ≤24h since stage 1 OR re-bump the version per §6.Y if more time has elapsed.

**`CLAUDE.md` line 902 (§6.AA clause 3):**

> 3. **No combined distribute permitted by default.** Running `scripts/firebase-distribute.sh` with no flags MUST default to `--debug-only` mode. The combined `--debug-and-release` flag (or any equivalent) MUST require explicit per-cycle operator authorization recorded in the §6.Z evidence file's `combined-distribute-authorization:` field. Pre-push hooks + the script itself reject release-only invocations not preceded by a documented debug-stage evidence entry.

**`CLAUDE.md` line 238 (Seventh Law clause 3) — quoted for scope, PROPOSED UNCHANGED in substance:**

> 3. **Pre-Tag Real-Device Attestation.** Before any release tag is cut, the operator MUST execute the user-visible flows (login, search, browse, view topic, download `.torrent`) on a real Android device against the real backend services and record a JSON attestation file at `.lava-ci-evidence/<tag-name>/real-device-attestation.json`. The attestation MUST include: device model, Android version, app version, timestamp, command-by-command checklist of executed user actions, and at least 3 screenshots OR a video recording referenced by hash. `scripts/tag.sh` MUST refuse to operate on a commit lacking the matching attestation. There is no exception. "Operator was busy" is not an exception. "Test environment was unstable" is not an exception. Untested-on-device commits do not get tags.

### 2.3 (b) Exact proposed replacement text

#### Replacement for clause 2 (line 900) — appended sentence only, existing text preserved verbatim

> 2. **Stage 2 — Release variant only.** ONLY AFTER the operator confirms in writing that the **Firebase-distributed debug variant** works correctly on the failure-surface device class, `scripts/firebase-distribute.sh --release-only` distributes the release APK. The §6.Z evidence file is APPENDED with a `release-stage` section recording the release-APK test execution + operator real-device verification on the release variant. Same SHA as stage 1 (no code change between stages); ≤24h since stage 1 OR re-bump the version per §6.Y if more time has elapsed. **The written operator confirmation required by this clause has exactly ONE alternate satisfier: a qualifying Pipeline Run Report under clause 8. No other substitution exists — an agent's assertion, a green CI line, a compiled-but-unexecuted test, or a prior cycle's confirmation does NOT satisfy this clause.**

#### Replacement for clause 3 (line 902) — appended sentences only, existing text preserved verbatim

> 3. **No combined distribute permitted by default.** Running `scripts/firebase-distribute.sh` with no flags MUST default to `--debug-only` mode. The combined `--debug-and-release` flag (or any equivalent) MUST require explicit per-cycle operator authorization recorded in the §6.Z evidence file's `combined-distribute-authorization:` field. Pre-push hooks + the script itself reject release-only invocations not preceded by a documented debug-stage evidence entry. **A qualifying Pipeline Run Report under clause 8 constitutes a STANDING per-cycle authorization for the combined invocation, and its `run_id` MUST be written verbatim into the `combined-distribute-authorization:` field so the audit trail names the exact run that authorized it. This standing authorization is available ONLY to `scripts/pipeline/phase-05-distribute.sh`; a human invoking `--debug-and-release` by hand still requires the per-cycle written authorization this clause has always required.**

#### New clause 8 (inserted after line 910's clause 7, renumbering the existing "7. Inheritance" to 9)

> 8. **Pipeline Distribution Path (added 2026-08-21, feature `002-build-test-distribute-pipeline`).** A distribution performed by `scripts/pipeline/phase-05-distribute.sh` within a single run of `scripts/pipeline-build-test-distribute.sh` MAY proceed from debug to release without an operator pause, and MAY use the combined `--debug-and-release` invocation, IF AND ONLY IF **every** condition below holds. Failure of any single condition returns the distribution to the clause 1–3 human path in full. There is no partial qualification.
>
>    **(A) Run Report identity.** A Pipeline Run Report exists at `.lava-ci-evidence/pipeline-runs/<run_id>/report.json`, validates against `specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json`, and its `commit_sha` equals `git rev-parse HEAD` at the moment of the distribute invocation. Every Distribution Record's `version_code` equals the `versionCode` compiled into the artifact actually being uploaded. A report whose `commit_sha` does not match is not stale evidence to be tolerated — it is a REFUSAL condition.
>
>    **(B) Unqualified pass.** The report's `outcome` is `PASS`; every entry in `phases[]` is `PASS`; zero Evidence Records carry `result: FAIL`; and zero Evidence Records carry an `anti_bluff_status` other than validated. A `SKIPPED` Evidence Record in any category named in condition (C) or (E) disqualifies the run.
>
>    **(C) Device evidence for BOTH variants.** At least one Evidence Record of `category: real-device-challenge`, with `result: PASS`, exists for **each** Android artifact variant being distributed — `app-debug`, `app-release`, `api-app-debug`, `api-app-release` — executed on a §6.AH-conformant container-or-VM emulator (never host-direct, never a live ADB device, per §6.AG), against the exact artifact being uploaded. **Release-variant device evidence is mandatory and is NOT inferable from debug-variant evidence.** The R8-minified release artifact is a different artifact; the §6.Z forensic anchor was a release-variant-only failure. Cold-start survival (§6.Z clause 4) is the minimum per variant, and does not by itself satisfy §6.AK.
>
>    **(D) Cycle-coverage on BOTH channels.** The §6.AK Phase-1 Gate 7 cycle-coverage check has been executed and PASSED for the `debug` channel **and** for the `release` channel. This condition exists because `scripts/firebase-distribute.sh` resolves `AK_CHANNEL` to `debug` whenever `MODE` is not exactly `release` (lines 322–324), so a `--debug-and-release` invocation validates the debug channel only. Under this clause the pipeline MUST invoke `scripts/check-cycle-coverage.sh --channel=release` explicitly and record its PASS in the run report before the combined distribute is permitted. §6.AK is NOT relaxed by this amendment; this condition closes the channel gap that would otherwise let a combined invocation bypass it.
>
>    **(E) Live verification of every distributed live surface.** The run's live-verification phase has exercised, against actually-running services over their real interfaces, every live surface represented in the run's Distribution Records — the `lava-api-go` service **and** the on-device `:api-app`. A run whose live-verification covers only one of the two does NOT qualify, irrespective of that half's result.
>
>    **(F) Unmodified distribute path.** The distribution was performed by invoking `scripts/firebase-distribute.sh` unmodified, with all of its own Phase-1 gates active. §6.Z clause 6 is unchanged and remains absolute: no bypass flag exists, none may be added, and invoking `firebase appdistribution:distribute` outside this script remains a §6.Z violation whether a pipeline is running or not.
>
>    **(G) Scope.** This clause applies ONLY to distributions produced by this pipeline, on `master`, with the FR-000 precondition guard (clean tree, correct branch) satisfied at invocation. Every other distribution — an operator running `firebase-distribute.sh` by hand, a hotfix, a re-upload, any future channel not driven by this pipeline — remains bound by clauses 1–3 in full. This clause grants no general permission to skip staging.
>
>    **(H) Disclosed residual gap (honest, and NOT closed by this amendment).** The clause 1 human path verifies the debug APK **as installed from the Firebase invite**, not the locally-built APK. The pipeline's machine evidence exercises locally-built artifacts and therefore does NOT cover the Firebase upload → download → install path on a physical device. This gap is real and is not closed here. It is recorded as **§6.AA-pipeline-debt**: a post-distribute verification that downloads the just-distributed artifact from its Firebase channel and cold-starts it is OWED. Until that debt closes, every run report authorized under this clause MUST carry the field `residual-gap: firebase-install-path-unverified` so the gap is visible in the artifact rather than only in this rule.
>
>    **(I) Automatic suspension trigger.** If any distribution authorized under this clause produces a Crashlytics FATAL on first user-visible interaction, or an operator-reported cold-start failure, this clause is **SUSPENDED automatically** — every subsequent distribution reverts to the clause 1–3 human path — until (i) a §6.Z-class incident record is written under `.lava-ci-evidence/sixth-law-incidents/`, (ii) a covering device Challenge that reproduces the failure RED-then-GREEN lands per §6.AK clause 2, and (iii) the operator re-authorizes this clause in writing. Suspension is the default state on failure; re-authorization is an explicit act, never an inference from time passing.
>
>    **Standing note.** This clause substitutes a *mechanism*, not a *standard*. The safety property §6.AA protects — no release-variant distribution without real, executed, artifact-specific proof it works — is preserved in full by conditions (C) and (D). Any future reading of this clause that treats a green run report as sufficient WITHOUT conditions (C) and (D) having genuinely executed is the §6.J bluff class this project has recorded three times (§6.Z, §6.AB, §6.AK), and is a violation of this clause, not a use of it.

#### Seventh Law clause 3 (line 238) — proposed addition, NON-RELAXING

Append one sentence. The clause's existing text, including "There is no exception", is preserved verbatim and unchanged:

> **Scope note (added 2026-08-21).** This clause gates **release tags** via `scripts/tag.sh`. The automated pipeline of feature `002-build-test-distribute-pipeline` distributes artifacts but cuts no tag, and its §6.AA clause 8 Pipeline Distribution Path therefore does NOT reach, satisfy, or relax this clause. A Pipeline Run Report is NOT a real-device attestation and MUST NOT be presented as one. Cutting a release tag continues to require the operator-executed, operator-recorded attestation described above, without exception.

### 2.4 The broader variant, considered and REJECTED

`spec.md`'s Constitutional Impact item 2 and `plan.md`'s Constitution Check both direct that Seventh Law clause 3 be amended so that pipeline evidence "satisfies (or formally replaces)" the human attestation. This draft does **not** do that, for a reason of fact rather than caution:

Per **F-3**, the pipeline creates no tag. Clause 3 is a tag-time gate reached only through `scripts/tag.sh`, which the pipeline never invokes. Relaxing it would surrender a protection the pipeline does not need in order to function, on every future tag, forever — including tags cut by hand months from now with no pipeline involved. That is the definition of a governance leak.

The scope note in §2.3 is therefore drafted as a **clarification that preserves clause 3 intact**. If the operator's intent is in fact to automate tagging as well, that is a separate amendment with its own forensic anchor and its own review — and this proposal recommends it be raised as such rather than folded in here.

**Reviewer decision required:** accept the narrow scope note (recommended), or direct that clause 3 be genuinely relaxed. See §7.

### 2.5 (c) Migration plan

| Step | Action | Owner |
|---|---|---|
| 1 | Land the §6.AA clause 2/3 edits, new clause 8, and the Seventh Law scope note in `CLAUDE.md` in a single reviewed commit, together with T041's `.specify/memory/constitution.md` change (Amendment procedure item 1 requires the PR touch only governance files). | Operator + reviewer |
| 2 | Add `scripts/check-cycle-coverage.sh --channel=release` as an explicit call in `phase-05-distribute.sh` **before** the combined `firebase-distribute.sh` invocation, satisfying condition (D). Without this, condition (D) cannot be met and clause 8 never qualifies. This is new code owed by T043, not optional. | T043 |
| 3 | Extend `phase-02-test.sh` so that `real-device-challenge` Evidence Records are produced per **variant** (`app-release` and `api-app-release` included), satisfying condition (C). Debug-only device evidence disqualifies the run under this draft. | T024 follow-up |
| 4 | Complete T037 (`:api-app` live-verification half) so condition (E) can be met. Until T037 lands, **no run qualifies under clause 8** and the pipeline's distribute phase remains blocked — which is the intended, honest behavior, not a defect. | T037 |
| 5 | Add `residual-gap` and `combined-distribute-authorization` fields to the run report emitted by `run-report.sh`, and extend `pipeline-run-report.schema.json` accordingly (conditions (H) and clause 3). | T039 follow-up |
| 6 | Add a hermetic test under `tests/pipeline/` asserting that `phase-05-distribute.sh` REFUSES when any one of conditions (A)–(F) is unmet — one failing case per condition, falsifiability-rehearsed per §6.N. A clause-8 gate with no failing-first test is itself a bluff. | T043 |
| 7 | No existing test, evidence file, or distribution record requires migration. Distributions already performed under the human path remain valid; this clause is additive and forward-only. | — |

### 2.6 (d) Version-bump rationale

`CLAUDE.md` carries no version number; its amendments are recorded by clause and date, which this draft follows (§6.AA clause 8 "added 2026-08-21, feature `002-build-test-distribute-pipeline`").

The paired bump lands in `.specify/memory/constitution.md` and is **MAJOR (1.1.0 → 2.0.0)** — see §3.4 for the full rationale. In brief: the Versioning policy (`.specify/memory/constitution.md:214`) makes MAJOR the correct level for "relaxation of any existing principle", and §6.AA clause 2's human confirmation requirement is genuinely relaxed for a defined class of distributions. That the relaxation is narrow does not make it MINOR; narrowness is a property of scope, not of kind.

### 2.7 (e) Risk analysis — what protection is given up

**Protection surrendered #1 — a human looking at the running debug build before the release build ships.**
This is the core of it. §6.AA clause 1 requires verification of the debug APK *as installed from the Firebase invite*, on the failure-surface device class, by a person. That check catches a category no automated suite reliably covers: the artifact was uploaded, downloaded, installed, and launched on real consumer hardware. Condition (H) discloses that this is **not** replaced — it is a genuine, open loss, mitigated only by the promise of §6.AA-pipeline-debt.

*Compensating control:* conditions (C) and (D) — per-variant executed device Challenges plus release-channel cycle-coverage — cover the *artifact correctness* half of what the human was checking. The *distribution-channel integrity* half is uncovered and disclosed rather than papered over.

**Protection surrendered #2 — the staging delay itself.**
Debug-first staging bounds blast radius in time as well as in audience. A failure caught at debug reaches testers; a failure caught at release reaches users. Back-to-back distribution removes that time buffer entirely: both audiences receive a broken artifact within the same minute.

*Compensating control:* condition (I)'s automatic suspension. It does not prevent the first bad release, but it prevents the *second* — which is precisely the pattern the §6.AK forensic anchor describes ("Same issues are present over and over again!"). This is honestly a weaker control than prevention.

**Protection surrendered #3 — a human's judgment about whether the tests that ran were the right tests.**
§6.AK exists because a gate ran exactly one test (C00, cold-start) while the CHANGELOG claimed fixes to search, provider selection, onboarding, and display. A machine gate cannot form the judgment "these tests do not cover what this cycle claims to have fixed."

*Compensating control:* this one is genuinely strong, and it is the reason this amendment is recommendable at all. `scripts/check-cycle-coverage.sh` mechanizes exactly that judgment — it parses CHANGELOG claims into a claim-set and refuses unless every claim maps to an executed, passed, same-SHA device Challenge. It exists, is wired at `scripts/firebase-distribute.sh:309-341`, is proven by `tests/cycle-coverage/`, and subsumes the §6.Z-debt runtime checks. Condition (D) extends it to the release channel, which is where F-2 shows it currently does not reach.

**Risk NOT mitigated — F-2 as it stands today.**
If clause 8 were adopted without condition (D), the pipeline would ship the R8-minified release APK with the gate having examined debug-channel evidence only. That is a mechanically identical setup to the failure that produced §6.AA. Condition (D) is therefore not a nicety; it is the load-bearing member of this amendment.

---

## 3. Amendment T041 — `.specify/memory/constitution.md` (MAJOR bump 1.1.0 → 2.0.0)

### 3.1 Amendment metadata

**Section modified:** Principle III (Anti-Bluff Enforcement / Seventh Law) — one new subsection added after clause 7. Principle II clause 5 — one scope-clarifying sentence. Sync Impact Report header — rewritten. Version footer — rewritten.

**Forensic anchor.** Identical to T040 (§2.1); this file is the derived distillation of the same change, per its own Canonical Precedence section (`.specify/memory/constitution.md:194-200`).

**`Classification:` project-specific** (same reasoning as §2.1).

### 3.2 (a) Exact current text being changed — quoted verbatim

**`.specify/memory/constitution.md` lines 1–42 (Sync Impact Report header), current opening:**

> ```
>   Version change:    1.0.0 → 1.1.0
> ```

**`.specify/memory/constitution.md` line 68 (Principle II clause 5):**

> 5. **CI green is necessary, not sufficient.** Before any release tag is cut, a human MUST have used the feature on a real device and observed the user-visible outcome.

**`.specify/memory/constitution.md` line 88 (Principle III clause 3) — quoted for scope, PROPOSED UNCHANGED:**

> 3. **Pre-Tag Real-Device Attestation.** Every release tag requires `.lava-ci-evidence/<tag-name>/real-device-attestation.json` with device model, Android version, app version, command-by-command checklist, ≥3 screenshots or video. `scripts/tag.sh` refuses without it. No exceptions.

**`.specify/memory/constitution.md` line 225 (version footer):**

> **Version**: 1.1.0 | **Ratified**: 2026-05-02 | **Last Amended**: 2026-08-21

### 3.3 (b) Exact proposed replacement text

#### New Sync Impact Report header (replaces lines 1–42 in full)

```
<!--
  ═══════════════════════════════════════════════════════════════════════════════
  SYNC IMPACT REPORT
  ═══════════════════════════════════════════════════════════════════════════════
  Version change:    1.1.0 → 2.0.0   (MAJOR — relaxation of an existing principle)
  Modified principles:
    - II. Real User Verification — clause 5 gains a scope note distinguishing
      tag-time human verification (unchanged, still mandatory) from the
      distribution-time machine-evidence path newly permitted under Principle
      III's "Automated Pipeline Distribution Path". No tag-time requirement is
      relaxed.
    - III. Anti-Bluff Enforcement (Seventh Law) — new subsection "Automated
      Pipeline Distribution Path" records the narrowly-scoped relaxation
      enacted in root CLAUDE.md §6.AA clause 8: within a single run of
      scripts/pipeline-build-test-distribute.sh, a qualifying Pipeline Run
      Report substitutes for the human debug-then-release confirmation that
      CLAUDE.md §6.AA clauses 2-3 have required since 2026-05-14. Clause 3
      (Pre-Tag Real-Device Attestation) is EXPLICITLY NOT relaxed — the
      pipeline cuts no tag and does not reach that gate.
    - V. Decoupled Reusable Architecture — the "Submodule fetch/pull is an
      EXPLICIT operator action, never automatic" bullet gains a bounded
      carve-out for this pipeline's advance-and-verify step, mirroring the
      root CLAUDE.md amendment. The constitution/ submodule is excluded from
      the carve-out and remains governed by CONST-049's 7-step pipeline.
      (This entry belongs to amendment T048/T049, landed in the same change.)
  Added sections:
    - Principle III > Automated Pipeline Distribution Path
    - Principle V > Automated Pipeline Pin-Advance Path
  Removed sections:   N/A
  Forensic anchor (per Amendment procedure item 2):
    - Lava-Android-1.2.19-1039: distributed debug+release in a single sweep;
      release APK crashed every cold launch (Crashlytics
      40a62f97a5c65abb56142b4ca2c37eeb, FATAL, painterResource() rejecting a
      <layer-list> drawable). This incident CREATED the rule now being
      relaxed; the relaxation is therefore gated on per-variant executed
      device evidence (CLAUDE.md §6.AA clause 8 conditions C and D) rather
      than on aggregate green.
    - Operator clarification recorded in spec.md:12 and spec.md:13
      (feature 002-build-test-distribute-pipeline): fully unattended
      distribution and automatic submodule pin-advance, both chosen over
      the lower-risk alternatives that were presented as recommended.
  Amendment procedure item 5 classification:
    - (a) introducing new Lava-wide governance into this file for the first
      time. The §6.AA two-stage distribute rule has never been represented in
      this file; root CLAUDE.md IS amended in the same change (task T040/T048),
      as item 5(a) requires.
  Migration plan: see
    specs/002-build-test-distribute-pipeline/constitutional-amendments-proposal.md
    §2.5 and §4.4. No existing test, evidence file, or distribution record
    requires migration; both carve-outs are additive and forward-only.
  Templates checked, no changes required:
    - .specify/templates/plan-template.md       ✅ (Constitution Check gate is
      generic — no hardcoded principle list to update)
    - .specify/templates/spec-template.md       ✅ (no constitution refs)
    - .specify/templates/tasks-template.md      ✅ (no constitution refs)
    - .specify/templates/checklist-template.md  ✅ (no constitution refs)
    - README.md                                 ✅ (no constitution refs)
  Deferred items still open:
    - TODO(RATIFICATION_DATE): original v1.0.0 ratification date (2026-05-02)
      remains provisional; not re-litigated by this amendment.
  Follow-up TODOs introduced by this amendment:
    - §6.AA-pipeline-debt (root CLAUDE.md §6.AA clause 8 condition H): the
      Firebase invite → download → install path on a physical device is NOT
      covered by the machine path. A post-distribute download-and-cold-start
      verification is OWED.
  ═══════════════════════════════════════════════════════════════════════════════
-->
```

#### Replacement for Principle II clause 5 (line 68) — appended sentence, existing text preserved verbatim

> 5. **CI green is necessary, not sufficient.** Before any release tag is cut, a human MUST have used the feature on a real device and observed the user-visible outcome. **This tag-time requirement is unchanged and unrelaxed. It is distinct from the distribution-time path described in Principle III's "Automated Pipeline Distribution Path", which governs Firebase distributions only and cuts no tag.**

#### New subsection under Principle III, inserted after clause 7 (line 92) and before that principle's `*Rationale:*` line

> #### Automated Pipeline Distribution Path (added 2.0.0)
>
> Root `CLAUDE.md` §6.AA (Two-Stage Distribute Mandate) requires an operator's written confirmation that the Firebase-distributed **debug** artifact works on the failure-surface device class before the **release** artifact may be distributed. Within a single run of `scripts/pipeline-build-test-distribute.sh`, that written confirmation MAY be satisfied instead by a qualifying Pipeline Run Report, under the conditions enumerated in `CLAUDE.md` §6.AA clause 8, which is authoritative for the exact condition set per this file's Canonical Precedence section.
>
> The conditions are summarized here so that this file's Constitution Check gate is not misread as permitting the relaxation on weaker terms than `CLAUDE.md` grants:
>
> - The run report must be schema-valid, must match `git rev-parse HEAD` exactly, and must report `PASS` with zero failed and zero non-validated evidence records.
> - **Executed device-Challenge evidence is required per artifact variant — release as well as debug.** Release-variant evidence is never inferable from debug-variant evidence; the R8-minified artifact is a different artifact, and the incident that created this rule was a release-variant-only failure.
> - **The §6.AK cycle-coverage gate must pass for the release channel as well as the debug channel.** `scripts/firebase-distribute.sh` resolves its cycle-coverage channel to `debug` for any mode other than exactly `release`, so a combined invocation must invoke the release-channel check explicitly.
> - Live verification must cover every live surface the run distributes.
> - The distribution must go through `scripts/firebase-distribute.sh` unmodified, with all its own gates active. No bypass flag exists or may be added.
> - The path applies ONLY to this pipeline's own runs, on `master`, with the precondition guard satisfied. Every manual or ad-hoc distribution remains bound by the human two-stage path in full.
> - The path SUSPENDS automatically on any resulting cold-start FATAL or operator-reported first-interaction failure, and resumes only after an incident record, a reproduce-first covering Challenge, and explicit written re-authorization.
>
> **Clause 3 above (Pre-Tag Real-Device Attestation) is NOT relaxed by this subsection.** The pipeline cuts no release tag and does not reach `scripts/tag.sh`. A Pipeline Run Report is not a real-device attestation and MUST NOT be offered as one.
>
> **Disclosed residual gap.** The human path verifies the artifact as installed from the Firebase invite. The machine path exercises locally-built artifacts and does not cover the upload → download → install path on physical hardware. This gap is open, is tracked as §6.AA-pipeline-debt, and MUST be recorded in each qualifying run report rather than left implicit.

#### Replacement for the version footer (line 225)

> **Version**: 2.0.0 | **Ratified**: 2026-05-02 | **Last Amended**: 2026-08-21

### 3.4 (c) Migration plan

Identical to §2.5, with two additions specific to this file:

| Step | Action |
|---|---|
| M-1 | This file and `CLAUDE.md` MUST land in the **same commit**. Amendment procedure item 5(a) requires root `CLAUDE.md` to be amended in the same PR when new Lava-wide governance enters this file for the first time; per **F-4**, the §6.AA rule has never been represented here, so item 5(a) applies. |
| M-2 | `plan.md`'s Constitution Check table lists Principles II and III as "**VIOLATION** (justified)". Once this amendment lands, those rows change status from violation-pending-amendment to conditionally-compliant-under-clause-8. Updating that table is documentation follow-up, not a governance act, and does not require its own review gate. |

No script requires updating for T041 specifically. Amendment procedure item 4 ("If an amendment changes a forbidden pattern, a pre-push hook, or a tag-gate condition, the matching script MUST be updated in the same PR") is triggered by T040's condition (D) — the explicit release-channel `check-cycle-coverage.sh` invocation — which is listed as migration step 2 in §2.5 and belongs to T043.

### 3.5 (d) Version-bump rationale

**MAJOR, 1.1.0 → 2.0.0.** The Versioning policy (`.specify/memory/constitution.md:213-216`) defines MAJOR as *"Backward-incompatible governance removal or redefinition; relaxation of any existing principle."*

Three points, stated honestly including the one that cuts the other way:

1. **A relaxation is genuinely occurring.** The requirement that a human confirm the debug build before the release build ships is being replaced by machine evidence for a defined class of distributions. That is a relaxation of an existing principle in the governance chain this file distills, and the policy's MAJOR trigger is unqualified — "relaxation of **any** existing principle".
2. **Narrow scope does not reduce the level.** MINOR is defined as "New principle or section added; materially expanded guidance." A carve-out that removes a human gate is not expanded guidance, regardless of how tightly it is bounded.
3. **The counter-argument, stated rather than suppressed.** Per **F-4**, the specific clause being relaxed (§6.AA) is not textually present in *this* file, and per §2.4 this draft leaves Principle III clause 3 and Principle II clause 5 substantively intact. A reading exists under which this file gains only a new subsection — MINOR. This proposal **rejects that reading** and recommends MAJOR, because the Versioning policy speaks to principles, not to sentences in one derived file, and because the tasks and spec both specify MAJOR (`tasks.md:127`, `spec.md:26`). Recording the counter-argument is required by the no-guessing discipline; suppressing it to make the bump look inevitable would be its own small bluff.

### 3.6 (e) Risk analysis

The risk of this amendment is not the text — it is **drift**. This file is the gate Spec Kit's Constitution Check reads. If it records the relaxation in weaker terms than `CLAUDE.md` grants, a future feature's Constitution Check passes on the weaker text while `CLAUDE.md` still forbids the behavior — the inverse of the Canonical Precedence section's intent.

*Compensating control:* the new subsection restates conditions (C), (D), (E), (G), and (I) explicitly rather than only pointing at `CLAUDE.md`, and names `CLAUDE.md` §6.AA clause 8 as authoritative for the exact condition set. A reader of this file alone cannot conclude that aggregate green is sufficient.

*Residual risk:* two documents now describe one rule, and they can diverge under future edits. No mechanical drift check between `CLAUDE.md` §6.AA clause 8 and this subsection is proposed here. **UNCONFIRMED:** whether an existing gate under `scripts/verify-all-constitution-rules.sh` would detect such divergence — this was not verified while drafting, and a reviewer should not assume it would.

---

## 4. Amendment T048 — `CLAUDE.md` Decoupled Reusable Architecture rule

### 4.1 Amendment metadata

**Section modified:** `CLAUDE.md` → "Decoupled Reusable Architecture (Constitutional Constraint)" → "Mandatory consequences" → the "Submodule fetch/pull is an EXPLICIT operator action" bullet (line 1249), plus a new subsection after line 1250.

**Forensic anchor.** Prospective-with-precedent, stated honestly as such:

- *Motivating requirement:* the operator's resolved clarification at `spec.md:13`: *"Also advance every submodule pin — the pipeline fetches each submodule's own upstream latest, advances it, and updates the main repository's recorded pin to match, before pushing everything."* `plan.md`'s Complexity Tracking records that the lower-risk alternative (push only local modifications at the current pin) was presented as recommended and explicitly not chosen. FR-017 and SC-004 ("full repository closure") are unreachable without an automatic advance.
- *Precedent within this project:* `CLAUDE.md:299` already carries an operator-authorized always-track-upstream waiver for `submodules/helixqa/` (Phase 4-C-1 decision Q9, 2026-05-16). Automatic pin advancement is therefore not unprecedented here; it is currently authorized for exactly one submodule and is being extended to the remaining own-org submodules under mechanical conditions.
- *No incident anchor exists.* This is preventive/enabling, not remedial. No bug motivated it. Stating that plainly is required by Amendment procedure item 2, which permits "prospective" as an anchor but not a fabricated one.

**`Classification:` project-specific.** The submodule set, the CONST-049 exclusion, and the §6.W two-mirror scope are Lava's own. The general pattern (advance-verify-or-revert) is reusable but is not asserted as universal here.

### 4.2 (a) Exact current text being changed — quoted verbatim

**`CLAUDE.md` line 1249:**

> - **Submodule fetch/pull is an EXPLICIT operator action, never automatic.** No git hooks that silently update pins, no `git submodule update --remote` in any release script. The pin is the contract; changing the contract is a code review event.

**Related text quoted for context, PROPOSED UNCHANGED — `CLAUDE.md` line 1245:**

> - **Submodule pins are explicit and frozen by default.** A pinned submodule does NOT auto-fetch latest; we are not obligated to track upstream movement. Frozen forever is acceptable. Updating the pin is a deliberate PR.

**Related standing rule quoted for context, PROPOSED UNCHANGED — `CLAUDE.md` line 299 (excerpt):**

> The `./constitution/` submodule itself remains pinned + advanced only per CONST-049's 7-step pipeline.

### 4.3 (b) Exact proposed replacement text

#### Replacement for line 1249 — appended sentence only, existing text preserved verbatim

> - **Submodule fetch/pull is an EXPLICIT operator action, never automatic.** No git hooks that silently update pins, no `git submodule update --remote` in any release script. The pin is the contract; changing the contract is a code review event. **ONE bounded exception exists: the Automated Pipeline Pin-Advance Path defined immediately below. No other automation may advance a pin, and no git hook may do so under any circumstances.**

#### New subsection inserted after line 1250 (after the "Mirror policy applies recursively" bullet, before "### What this rule does NOT forbid")

> #### Automated Pipeline Pin-Advance Path (added 2026-08-21, feature `002-build-test-distribute-pipeline`)
>
> `scripts/advance-all-submodules.sh`, invoked by `scripts/pipeline/phase-07-closure.sh` within a single run of `scripts/pipeline-build-test-distribute.sh`, MAY advance submodule pins without a per-submodule operator action, IF AND ONLY IF every condition below holds. This is the sole exception to the bullet above.
>
> **(A) Advance-verify-or-revert is mandatory, per submodule, before the pin is recorded.** For each submodule, in this order: fetch its own configured upstream(s); compare the current pin to the remote default-branch HEAD; if identical, no-op; if different, check the remote HEAD into the submodule's working tree, then **rebuild and re-run the affected test categories against the advanced state**. If that rebuild-and-test fails, the advance is DISCARDED (the prior pin is restored) and the specific incompatibility is reported. Only a submodule that builds and tests green at the advanced commit may have its pin recorded in the parent. A pin that broke the build is never committed — it is refused.
>
> **(B) The `constitution/` submodule is EXCLUDED, without exception.** `submodules/`-adjacent governance is not ordinary code: advancing `./constitution/` automatically would let an unattended process rewrite the rules the same process is bound by. Per the standing rule recorded earlier in this document, the `./constitution/` submodule remains pinned and advanced **only** per CONST-049's 7-step pipeline. `scripts/advance-all-submodules.sh` MUST exclude it by default — not by an operator remembering to pass an allow-list, but by a default deny that requires code change to override.
>
> **(C) Own-org upstreams only, §6.W scope unchanged.** The path advances only submodules whose configured upstreams are `vasic-digital/*` or `HelixDevelopment/*` on GitHub or GitLab. §6.W is not relaxed: no new remote, no other provider, no externally-maintained upstream is advanced automatically.
>
> **(D) No forced push, no history rewrite, no divergence overwrite.** If any submodule cannot be pushed without overwriting diverged remote history, the pipeline STOPS and reports that specific conflict. It does not force, and it does not silently skip. §6.T.3 is unchanged and absolute: force push, history rewrite, and hook bypass continue to require explicit, in-conversation, per-operation operator approval, which an unattended pipeline cannot obtain and therefore may never assume.
>
> **(E) Per-submodule record.** Every submodule processed produces a Submodule Advance Record conforming to `specs/002-build-test-distribute-pipeline/contracts/submodule-advance-record.schema.json`, naming the old commit, the new commit, and the outcome (`NO_NEWER_COMMIT`, `ADVANCED`, `REJECTED_BREAKING_CHANGE`, `REJECTED_PUSH_CONFLICT`). "All submodules advanced" without per-submodule records is not evidence, per the same reasoning §6.I applies to per-AVD attestation rows.
>
> **(F) Scope.** Applies ONLY within this pipeline's own runs. A human advancing a pin outside the pipeline continues to do so as a deliberate, reviewed act per the bullet above. Nothing here authorizes a git hook, a `git submodule update --remote` in any other script, or a background job to move a pin.
>
> **Standing note.** The bullet above exists because the pin is the contract and drift between projects is the highest-bandwidth bluff vector. This exception does not deny that; it substitutes a *mechanical* review (build-and-test at the advanced commit, revert on failure) for a *human* review, for own-org submodules only, and preserves refusal — not force — as the response to every conflict.

### 4.4 (c) Migration plan

| Step | Action | Owner |
|---|---|---|
| 1 | Land the line-1249 edit and the new subsection in `CLAUDE.md`, together with T049's `.specify/memory/constitution.md` change, in one reviewed commit. May share the commit with T040/T041 per `tasks.md:153`. | Operator + reviewer |
| 2 | **Fix the defect recorded in F-5**: add a default deny-list to `scripts/advance-all-submodules.sh` excluding `constitution/`, so that condition (B) holds by construction. As written today, that script's default is "every submodule". This is not optional cleanup — condition (B) cannot be satisfied without it. | T052 follow-up |
| 3 | Add a hermetic test case to `tests/pipeline/test_advance_all_submodules.sh` asserting that a fixture submodule at the `constitution/` path is **never** advanced even when a newer upstream commit exists, and that this holds without any allow-list being passed. Falsifiability-rehearse it per §6.N. | T052 follow-up |
| 4 | Confirm T050–T052's existing cases already cover conditions (A), (D), and (E) — `tasks.md:150` records cases for no-newer-commit, clean advance, `REJECTED_BREAKING_CHANGE`, and `REJECTED_PUSH_CONFLICT`, matching those conditions. No new work identified for these three. | T054 review |
| 5 | The `submodules/helixqa/` Q9 waiver (`CLAUDE.md:299`) is **subsumed but not superseded** — HelixQA continues to track upstream, now under the same mechanical advance-verify-or-revert conditions as the rest. No edit to the Q9 waiver text is proposed. | — |
| 6 | No existing pin requires migration. Current pins remain valid; the path is forward-only and takes effect at the next pipeline run. | — |

### 4.5 (d) Version-bump rationale

`CLAUDE.md` is versioned by clause and date, followed here. The paired `.specify/memory/constitution.md` bump is **MAJOR** and is shared with T041 — a single 1.1.0 → 2.0.0 covering both amendments, per `tasks.md:154`'s "same MAJOR version bump as T041". If T048/T049 land separately from T040/T041, T049 becomes 2.0.0 → 3.0.0 by the same Versioning-policy reasoning (relaxation of Principle V), and this proposal recommends landing all four together to avoid two consecutive MAJOR bumps for one feature.

### 4.6 (e) Risk analysis — what protection is given up

**Protection surrendered #1 — a human reading the diff before foreign code enters the build.**
"The pin is the contract; changing the contract is a code review event." Automatic advance means commits authored elsewhere enter Lava's build without anyone reading them. All 17 submodules are own-org (`vasic-digital/*` plus HelixDevelopment's HelixQA), which bounds this considerably, but "own-org" is not "reviewed" — an own-org submodule advanced automatically can carry a behavior change nobody in this repository has looked at.

*Compensating control:* condition (A)'s rebuild-and-test-before-recording, which makes a build-breaking advance mechanically impossible to commit. Per `plan.md`'s post-design re-evaluation, "the automation this feature adds cannot itself introduce a broken pin, only a refused one."

*Residual risk, stated plainly:* an advance that **passes** the build and tests but changes runtime behavior in a way the suite does not cover. That is the §6.J gap in its purest form, and condition (A) does not close it — it closes only the compile-and-test-visible subset. The honest bound on this amendment's safety is exactly the coverage of the test suite that runs during the advance.

**Protection surrendered #2 — deliberateness as a rate limiter.**
"Frozen forever is acceptable" (line 1245, unchanged) encodes that not tracking upstream is a legitimate default. Advancing every pin on every run replaces a deliberate act with a scheduled one, so upstream churn now enters Lava at the pipeline's cadence rather than the operator's.

*Compensating control:* condition (E)'s per-submodule records make each advance auditable after the fact, and condition (A) makes each one independently gated. There is no control that restores deliberateness itself; that is what the operator chose to trade.

**Protection surrendered #3 — none, in three areas frequently assumed at risk.** Stated explicitly so the reviewer need not verify them separately: §6.W's two-mirror scope is unchanged (condition C); §6.T.3's no-force-push rule is unchanged and reaffirmed (condition D); and the `constitution/` submodule's CONST-049 governance is unchanged and now mechanically enforced (condition B) where today it is enforced only by prose that the implementing script does not honor (F-5).

**Risk NOT mitigated as currently implemented — F-5.**
`scripts/advance-all-submodules.sh` today defaults to every submodule, including `constitution/`. Until migration step 2 lands, an unattended run would advance the project's own governance submodule. Condition (B) is written to make that a constitutional violation, but the code change is required for the condition to be true in practice.

---

## 5. Amendment T049 — `.specify/memory/constitution.md` (Principle V)

### 5.1 Amendment metadata

**Section modified:** Principle V (Decoupled Reusable Architecture) — the "Submodule fetch/pull is an EXPLICIT operator action" bullet (line 119), plus a new subsection. Sync Impact Report header and version footer — covered by the combined header and footer already drafted in §3.3.

**Forensic anchor.** Identical to T048 (§4.1).

**`Classification:` project-specific** (same reasoning as §4.1).

### 5.2 (a) Exact current text being changed — quoted verbatim

**`.specify/memory/constitution.md` line 119:**

> - **Submodule fetch/pull is an EXPLICIT operator action, never automatic.** No git hooks that silently update pins, no `git submodule update --remote` in any release script.

**Related text quoted for context, PROPOSED UNCHANGED — line 115:**

> - **Submodule pins are explicit and frozen by default.** A pinned submodule does NOT auto-fetch latest. Updating the pin is a deliberate PR.

### 5.3 (b) Exact proposed replacement text

#### Replacement for line 119 — appended sentence only, existing text preserved verbatim

> - **Submodule fetch/pull is an EXPLICIT operator action, never automatic.** No git hooks that silently update pins, no `git submodule update --remote` in any release script. **One bounded exception exists — the Automated Pipeline Pin-Advance Path below. Git hooks remain categorically excluded from that exception.**

#### New subsection appended to Principle V, after line 121's Inheritance bullet and before that principle's `*Rationale:*` line

> #### Automated Pipeline Pin-Advance Path (added 2.0.0)
>
> Within a single run of `scripts/pipeline-build-test-distribute.sh`, `scripts/advance-all-submodules.sh` MAY advance submodule pins without a per-submodule operator action. Root `CLAUDE.md`'s Decoupled Reusable Architecture rule is authoritative for the exact condition set; the conditions are summarized here so this file's Constitution Check gate is not misread as permitting the exception on weaker terms:
>
> - **Advance-verify-or-revert per submodule.** Fetch, compare, and — when a newer commit exists — check it out and **rebuild plus re-run the affected tests against the advanced state**. On failure, discard the advance, restore the prior pin, and report the incompatibility. A pin that broke the build is never recorded.
> - **The `constitution/` submodule is excluded by default.** It remains governed solely by CONST-049's 7-step pipeline. An unattended process may not advance the governance it is bound by.
> - **Own-org upstreams only.** GitHub and GitLab, `vasic-digital/*` and `HelixDevelopment/*`. Principle V's mirror-policy bullet is unchanged.
> - **No forced push, no history rewrite, no divergence overwrite.** A push that would overwrite diverged remote history STOPS the run and is reported, never forced and never skipped.
> - **Per-submodule Submodule Advance Records** are produced for every submodule processed. An aggregate "all advanced" claim without per-submodule records is not evidence.
> - **Scope:** this pipeline's own runs only. Git hooks, background jobs, and every other script remain categorically forbidden from moving a pin.
>
> **Disclosed residual risk.** Rebuild-and-test gates only the compile-and-test-visible subset of an upstream change. A submodule advance that passes the suite while changing runtime behavior the suite does not cover is NOT prevented by this path. The safety of this exception is bounded by the coverage of the tests that run during the advance, and that bound is stated here rather than left implicit.

### 5.4 (c) Migration plan

Identical to §4.4. Two file-specific notes:

| Step | Action |
|---|---|
| M-1 | Must land in the same commit as T048's `CLAUDE.md` change, per Amendment procedure item 5. Principle V's bullet exists in **both** files (unlike §6.AA per F-4), so this is a true mirror-edit and item 5(b) — "catching this file up to a decision already enacted in root `CLAUDE.md`" — applies only if T048 lands first. If both land together, item 5(a) governs and root `CLAUDE.md` is amended in the same PR as required. |
| M-2 | `plan.md`'s Constitution Check row for Principle V changes from "**VIOLATION** (justified)" to conditionally-compliant. Documentation follow-up, not a governance act. |

### 5.5 (d) Version-bump rationale

Shared MAJOR bump with T041 — a single 1.1.0 → 2.0.0 covering both amendments. Principle V's "never automatic" rule is genuinely relaxed for a bounded class of pin advances, which is the Versioning policy's MAJOR trigger. Landing T048/T049 separately from T040/T041 would require a second MAJOR (2.0.0 → 3.0.0); `tasks.md:154` anticipates both options, and this proposal recommends the combined landing.

### 5.6 (e) Risk analysis

Same substantive risks as §4.6. The file-specific risk is the same drift risk as §3.6: this file is what Spec Kit's Constitution Check reads, so it must not state the exception in looser terms than `CLAUDE.md` grants.

*Compensating control:* the summary restates the `constitution/` exclusion, the rebuild-and-test requirement, the no-force-push requirement, and the hooks-still-forbidden carve-out explicitly, and names `CLAUDE.md` as authoritative. A reader of this file alone cannot conclude that a bare `git submodule update --remote` is now permitted.

---

## 6. Consolidated risk verdict

### 6.1 T040 / T041 — the pair that trades away a human confirmation

**Verdict: CONDITIONALLY ACCEPTABLE — acceptable as drafted here; NOT acceptable as scoped by `research.md` R-001 alone.**

R-001 frames the substitution as: the pipeline's aggregated evidence — build success, FR-002's test categories passing with anti-bluff validation, and a live-verification pass — stands in for the human check. Judged against the incident this rule exists for, **that framing is insufficient**, for one concrete reason:

Per **F-2**, the `--debug-and-release` invocation that R-003 selects causes `scripts/firebase-distribute.sh` to run its §6.AK cycle-coverage gate against the **debug** channel only, and per **F-1** the script's §6.AA staging check does not evaluate at all in that mode. A distribution authorized purely on "aggregate green" would therefore push the R8-minified **release** APK with no gate having examined release-channel evidence. That is not an abstract concern — it is mechanically the same setup as Lava-Android-1.2.19-1039, whose crash was release-variant-only and R8-mediated.

The drafted clause 8 is acceptable **because** conditions (C) and (D) close exactly that hole: per-variant executed device evidence, and an explicit release-channel cycle-coverage pass. Remove either condition and the recommendation inverts to reject.

**What is genuinely given up, restated without softening:** a person installing the debug build from the Firebase invite onto real hardware and looking at it before the release build reaches users. Condition (H) discloses that this is not replaced. The staging *time buffer* is also gone — both audiences now receive a broken artifact within the same minute, and condition (I)'s automatic suspension prevents the second bad release, not the first.

**Why it is nevertheless recommendable:** the strongest thing this project has built since the incident is `scripts/check-cycle-coverage.sh` — it mechanizes the one judgment (do the executed tests intersect the claimed fixes?) whose absence produced both the §6.Z and §6.AK anchors, it is wired at `scripts/firebase-distribute.sh:309-341`, and it is proven by `tests/cycle-coverage/`. A machine gate that enforces claim-coverage on every run is, on the specific failure mode this project has actually suffered twice, more reliable than a human confirmation that the §6.AK anchor records as having been skipped in practice.

**Blocking preconditions before clause 8 can qualify a single run** — all three are open today:

1. Release-channel `check-cycle-coverage.sh` invocation in `phase-05-distribute.sh` (condition D) — not yet written.
2. Per-variant `real-device-challenge` evidence including release variants (condition C) — `tasks.md` T024 records a development-iteration subset (one class, one AVD, API 34 phone), explicitly not a §6.AE.2 conformant matrix.
3. T037's `:api-app` live-verification half (condition E) — unimplemented per `tasks.md` T038.

Until all three land, **no run qualifies and the distribute phase stays blocked.** That is the intended behavior of this draft, and the reviewer should expect it rather than treat it as a regression.

### 6.2 T048 / T049

**Verdict: ACCEPTABLE as drafted, conditional on the F-5 code fix landing in the same change.**

The protection given up — a human reading the diff before foreign code enters the build — is materially compensated by condition (A)'s rebuild-and-test-or-revert, which makes a build-breaking pin mechanically uncommittable. The residual gap (an advance that passes tests but changes uncovered runtime behavior) is real, is bounded by suite coverage, and is disclosed in both drafts rather than assumed away.

The blocking item is **F-5**: the implementing script has no `constitution/` exclusion and defaults to every submodule. Condition (B) makes auto-advancing the governance submodule a constitutional violation, but the default-deny code change must land with the amendment, not after it.

### 6.3 A note on what these four amendments do NOT do

Stated so no future reading over-extends them: §6.Z is not relaxed (the pre-distribute test-execution mandate stands in full, and clause 8 condition (F) reaffirms that no bypass flag exists). §6.AK is not relaxed (clause 8 condition (D) strengthens its reach). §6.AH / §6.AG are not relaxed (condition (C) requires container-or-VM emulators and forbids live ADB devices). §6.T.3 is not relaxed (T048 condition (D) reaffirms it). §6.W is not relaxed (T048 condition (C)). Seventh Law clause 3 is not relaxed (§2.4, by deliberate choice against the spec's broader framing). Only two things change: the human debug-then-release confirmation for this pipeline's own distributions, and the human per-pin action for this pipeline's own submodule advances.

---

## 7. Reviewer decision checklist

Nothing below has been decided. Each item requires an explicit operator answer before any governing file is edited.

| # | Decision | Options | This proposal recommends |
|---|---|---|---|
| D-1 | Adopt §6.AA clause 8 **with** conditions (C) and (D)? | Adopt as drafted / adopt without (C) or (D) / reject | **Adopt as drafted.** Without (C) or (D), §6.1's verdict inverts to reject. |
| D-2 | Seventh Law clause 3 — narrow scope note, or genuine relaxation? | Narrow scope note (preserves clause 3) / genuine relaxation per `spec.md` item 2 | **Narrow scope note.** Per F-3 the pipeline cuts no tag; relaxing clause 3 surrenders protection on every future tag for no functional gain. |
| D-3 | Version bump level for `.specify/memory/constitution.md`. | MAJOR 2.0.0 / MINOR 1.2.0 | **MAJOR 2.0.0**, with the counter-argument recorded in §3.5(3). |
| D-4 | Land all four amendments in one commit, or split T040/T041 from T048/T049? | Combined (one MAJOR) / split (two MAJORs) | **Combined**, per `tasks.md:153`. |
| D-5 | Condition (I)'s automatic-suspension trigger — accept as drafted? | Accept / weaken / strengthen | **Accept.** It is the only control that bounds repeat failures, and it is deliberately default-on-failure. |
| D-6 | F-5 fix (default-deny `constitution/` in `advance-all-submodules.sh`) lands in the same change as T048? | Same change / follow-up | **Same change.** Condition (B) is otherwise untrue in practice. |
| D-7 | Accept that the distribute phase stays **blocked** until §6.1's three preconditions land? | Accept / relax a precondition | **Accept.** A qualifying-by-default clause 8 would reproduce the §6.AK anchor. |

**On approval**, the amendments in §2–§5 may be applied to `CLAUDE.md` and `.specify/memory/constitution.md`, and `tasks.md`'s T040/T041/T048/T049 checkboxes marked complete with the reviewing operator named. **Until then, all four remain open review gates and no governing document may be edited.**
