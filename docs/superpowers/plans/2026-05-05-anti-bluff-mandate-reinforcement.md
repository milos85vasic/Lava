# Anti-Bluff Mandate Reinforcement (Group A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the constitutional doc updates + bluff-hunt evidence + propagation audit that the operator's 8th anti-bluff-mandate invocation requires — bump §6.L count, expand Seventh Law clause 5, add new clause §6.N + §6.N-debt placeholder, and propagate to ~20 files.

**Architecture:** Pure documentation + JSON evidence files. No code changes. Three parent commits + 16 submodule commits + 1 parent pin-bump commit. Submodules use the existing `lava-pin/<date>-<topic>` branch pattern, identical to the §6.M rollout in commit `20539d5`.

**Tech Stack:** Markdown + JSON only. Bash for grep audits. Git for commit/push.

**Spec:** `docs/superpowers/specs/2026-05-05-anti-bluff-mandate-reinforcement-design.md` (commit `aa3aa1e`).

**Out-of-scope:** Pre-push hook code (deferred to Group A-prime spec).

---

## Pre-flight

- [ ] **Step 0.1: Confirm working tree is clean**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
git status --short
```

Expected: empty output. If not, abort and ask the operator.

- [ ] **Step 0.2: Confirm we are on master branch**

```bash
git branch --show-current
```

Expected: `master`

- [ ] **Step 0.3: Note the §6.M propagation pattern as reference**

The §6.M propagation in commit `20539d5` is the template. Each of the 16 submodule CLAUDE.md files received a "Clause 6.M (added 2026-05-04 evening, inherited per 6.F)" block at the end of file, with Containers receiving a stronger variant. We follow the same shape for §6.N.

---

## Task 1: Root CLAUDE.md updates

**Files:**
- Modify: `CLAUDE.md` (3 distinct edit locations)

- [ ] **Step 1.1: Bump §6.L count from SEVEN to EIGHT and add 8th-invocation forensic entry**

Use Edit. The current line at `CLAUDE.md:435` starts with `The user has now invoked this mandate **SEVEN TIMES**`. Replace with:

```
The user has now invoked this mandate **EIGHT TIMES** across two working days (initial fix request, after 6.G/6.H landed, after 6.I/6.J landed, after 6.K landed, then again with `ultrathink` after the layer-3 fix, then again after spotting that "Anonymous Access" was modeled as a global toggle when it is actually a per-provider capability, then on 2026-05-05 after the architectural port-collision bluff in the matrix runner was discovered with the verbatim restatement: "all existing tests and Challenges do work in anti-bluff manner — they MUST confirm that all tested codebase really works as expected", and again on 2026-05-05 evening when the operator commissioned a comprehensive plan covering ALL open points and re-emphasized: "execution of tests and Challenges MUST guarantee the quality, the completion and full usability by end users of the product"). The count is what makes this clause load-bearing: every restatement is an admission by the operator that the prior layers of constitutional plumbing (6.A through 6.M, the Sixth and Seventh Laws) are not yet enough to evict the bluff class on their own. The repetition itself is the forensic record. This clause is the same as 6.J — every test, every Challenge Test, every CI gate has exactly one job: confirm the feature works for a real user end-to-end on the gating matrix. CI green is necessary, never sufficient. **The reason this clause is restated rather than cross-referenced** is that the operator's standing concern is that future agents and contributors will rationalize their way past 6.J and ship green-tests-with-broken-features again. Every time the operator restates it, this codebase records the restatement here so the next reading agent must look at the same wall of repetition the operator has had to type out.
```

- [ ] **Step 1.2: Verify §6.L count change**

```bash
grep -c "EIGHT TIMES" CLAUDE.md
grep -c "SEVEN TIMES" CLAUDE.md
```

Expected: `EIGHT TIMES` count = 1; `SEVEN TIMES` count = 0.

- [ ] **Step 1.3: Expand Seventh Law clause 5 with subsections 5.a + 5.b**

The current Seventh Law clause 5 starts at `CLAUDE.md:201` with `5. **Recurring Bluff Hunt.** Once per development phase…`. Read the surrounding lines to find the exact end of clause 5 (it precedes clause 6). Insert immediately before clause 6 starts:

```markdown
   **5.a — Cadence tightening (added 2026-05-05, formalized in §6.N.1).** The 2-4 week phase-end cycle remains the baseline, but three additional triggers fire IN-cycle:
   1. **Per operator anti-bluff-mandate invocation.** First invocation in any 24h window: full 5+2 hunt (5 `*Test.kt` files per the baseline rule + 2 production-code files per §6.N.2). Subsequent invocations within the same 24h: lighter "incident-response hunt" — 1-2 files most relevant to the invocation context (e.g., the area of code the latest discovery flagged).
   2. **Per matrix-runner / gate change** (pre-push enforced — owed via §6.N-debt). Any commit that touches `Submodules/Containers/pkg/emulator/`, `scripts/run-emulator-tests.sh`, `scripts/tag.sh`, or `scripts/check-constitution.sh` MUST be accompanied by a 1-target falsifiability rehearsal in the area of change.
   3. **Per phase-gating attestation file added** (pre-push enforced — owed via §6.N-debt). Any new file under `.lava-ci-evidence/sp3a-challenges/`, `.lava-ci-evidence/<tag>/real-device-verification.{md,json}`, or `.lava-ci-evidence/sixth-law-incidents/` MUST be accompanied by a falsifiability rehearsal of the production code path the attestation claims to cover.

   **5.b — Production-code coverage (added 2026-05-05, formalized in §6.N.2).** Bluff hunts MUST sample production code, not just test files. Layered:
   - **Mandatory minimum (per phase):** 2 files from gate-shaping production code. Canonical list: `scripts/tag.sh` helpers, `scripts/check-constitution.sh`, `scripts/bluff-hunt.sh`, `Submodules/Containers/pkg/emulator/`, `Submodules/Containers/cmd/emulator-matrix/`, the matrix runner's `writeAttestation` function. The list grows as new gate-shaping code lands.
   - **Recommended additional (per phase):** 0-2 files from broader CI-touched code — anything invoked by `scripts/ci.sh` or `scripts/run-emulator-tests.sh`.
   - **Conceptual filter:** for each candidate file, ask "would a bug here be invisible to existing tests?" Prefer files where the answer is yes.

   The mutation-rehearsal protocol from clause 5 baseline applies unchanged. Output recorded under `.lava-ci-evidence/bluff-hunt/<date>.json`.
```

- [ ] **Step 1.4: Verify clause 5 expansion**

```bash
grep -c "5\.a — Cadence tightening" CLAUDE.md
grep -c "5\.b — Production-code coverage" CLAUDE.md
```

Expected: each = 1.

- [ ] **Step 1.5: Insert new §6.N immediately after §6.M (before §6.L since §6.L sits at line ~433)**

Locate `##### 6.M — Host-Stability Forensic Discipline` at `CLAUDE.md:379` and find where its block ends. The §6.M section ends just before the line `##### 6.L — Anti-Bluff Functional Reality Mandate (Operator's Standing Order, repeated 2026-05-04)` at `CLAUDE.md:433`. Insert the new §6.N text BEFORE that §6.L line (so the order becomes 6.M → 6.N → 6.L). Insert this block:

```markdown
##### 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage (added 2026-05-05)

**Forensic anchor:** the 7-day-old architectural bluff in `Submodules/Containers/pkg/emulator/Boot()` (hardcoded `ADBPort=5555`) exposed by ultrathink-driven systematic-debugging on 2026-05-05; recorded at `.lava-ci-evidence/sixth-law-incidents/2026-05-05-matrix-runner-port-collision-bluff.json`. The bluff was invisible to all existing `*Test.kt`-targeted bluff hunts because the buggy code was production Go code that no test could mutate-rehearse — only end-to-end multi-AVD matrix runs would have caught it, and those runs were themselves rendered green by the bluff (textbook clause-6.J failure mode). The operator's 8th invocation of the anti-bluff mandate landed concurrently with this discovery, demanding tightened cadence and broader scope for bluff hunts.

**6.N.1 — Tightened cadence.** The 2-4 week phase-end cycle (Seventh Law clause 5 baseline) remains. Three additional triggers fire IN-cycle:

1. **Per operator anti-bluff-mandate invocation.** First invocation in any 24h window: full 5+2 hunt (5 `*Test.kt` per Seventh Law clause 5 baseline + 2 production-code files per §6.N.2). Subsequent invocations within the same 24h: lighter "incident-response hunt" — 1-2 files most relevant to the invocation context.
2. **Per matrix-runner / gate change** (pre-push enforced — see §6.N-debt below). Any commit that touches `Submodules/Containers/pkg/emulator/`, `scripts/run-emulator-tests.sh`, `scripts/tag.sh`, or `scripts/check-constitution.sh` MUST carry a Bluff-Audit stamp recording a 1-target falsifiability rehearsal in the area of change. The hook checks for the `Bluff-Audit:` block AND verifies the mutation reasoning targets a file in the diff.
3. **Per phase-gating attestation file added** (pre-push enforced — see §6.N-debt below). Any new file under `.lava-ci-evidence/sp3a-challenges/`, `.lava-ci-evidence/<tag>/real-device-verification.{md,json}`, or `.lava-ci-evidence/sixth-law-incidents/` MUST be accompanied by a falsifiability rehearsal of the production code path the attestation claims to cover. The rehearsal evidence MAY be embedded in the attestation OR live as a companion file in the same directory.

**6.N.2 — Production-code coverage in bluff hunts.** Bluff hunts MUST sample production code beyond `*Test.kt` / `*_test.go`. Layered scope:

- **Mandatory minimum (per phase):** 2 files from gate-shaping production code. Gate-shaping = files whose output determines pass/fail of a constitutional gate. Canonical list: `scripts/tag.sh` helpers, `scripts/check-constitution.sh`, `scripts/bluff-hunt.sh`, `Submodules/Containers/pkg/emulator/`, `Submodules/Containers/cmd/emulator-matrix/`, the matrix runner's `writeAttestation` function. The list grows as new gate-shaping code lands.
- **Recommended additional (per phase):** 0-2 files from broader CI-touched code — anything invoked by `scripts/ci.sh` or `scripts/run-emulator-tests.sh`, including Lava-domain Kotlin/Go production paths.
- **Conceptual filter:** for each candidate file, ask "would a bug here be invisible to existing tests?" Prefer files where the answer is yes — those are the bluff-rich targets.

The mutation-rehearsal protocol from Seventh Law clause 5 applies unchanged: pick file → apply deliberate mutation that affects the gate's verdict → run the gate → confirm the gate fails (or surfaces the wrong outcome) → revert → re-run → confirm green again. Record outcome in `.lava-ci-evidence/bluff-hunt/<date>.json`.

**6.N.3 — §6.N-debt (forward-reference to Group A-prime spec).** The pre-push hook enforcement clauses (6.N.1.2 + 6.N.1.3 above) are documented but NOT yet implemented. Implementation is deferred to the Group A-prime spec, which the parent Group A spec spawns as the next brainstorming + writing-plans cycle (`docs/superpowers/specs/<TBD-Group-A-prime>.md`). Until Group A-prime ships:
- 6.N.1.1 (per-invocation hunt) is operator-driven manual cadence — no mechanical enforcement
- 6.N.1.2 + 6.N.1.3 are documented requirements only — no mechanical enforcement
- The §6.N-debt entry stays open in this `CLAUDE.md` until Group A-prime closes it. The constitution checker (`scripts/check-constitution.sh`) MAY warn but MUST NOT yet hard-fail on missing rehearsals.

**Inheritance.** Clause 6.N applies recursively to every submodule, every feature, and every new artifact. Submodule constitutions MAY add stricter cadence requirements (e.g., `Submodules/Containers` SHOULD bluff-hunt every change to `pkg/emulator/` since it is the source of truth for the matrix gate) but MUST NOT relax this clause.

##### 6.N-debt — Pre-push hook enforcement of 6.N.1 clauses 2 + 3 (constitutional debt, 2026-05-05)

The clauses above are the contract. Mechanical enforcement (pre-push hook code that rejects non-compliant commits) is owed via the Group A-prime spec, which is the next brainstorming target after Group A lands. Until Group A-prime ships:

- 6.N.1.2 (per matrix-runner/gate change) is documented requirement; reviewer MUST manually verify Bluff-Audit stamps in commit messages touching the named files
- 6.N.1.3 (per attestation) is documented requirement; reviewer MUST manually verify falsifiability rehearsal evidence accompanies new attestation files

The next phase that touches `scripts/check-constitution.sh` MUST close 6.N-debt before its commit lands, and the close MUST: (1) parse commit messages for `Bluff-Audit:` stamps when 6.N.1.2-listed files appear in the diff, (2) check for falsifiability rehearsal evidence (in-attestation or companion file) when 6.N.1.3-listed paths gain new files, (3) update the constitution checker's gate set accordingly.

```

- [ ] **Step 1.6: Verify §6.N insertion**

```bash
grep -c "##### 6\.N — Bluff-Hunt Cadence" CLAUDE.md
grep -c "##### 6\.N-debt" CLAUDE.md
grep -c "6\.N\.1" CLAUDE.md
grep -c "6\.N\.2" CLAUDE.md
grep -c "6\.N\.3" CLAUDE.md
```

Expected: each ≥ 1.

- [ ] **Step 1.7: Verify ordering (6.M before 6.N before 6.L)**

```bash
grep -nE "^##### 6\.[LMN]" CLAUDE.md
```

Expected output (line numbers will differ — order matters):

```
<lineA>:##### 6.M — Host-Stability Forensic Discipline (added 2026-05-04 evening, recurrence forensics)
<lineB>:##### 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage (added 2026-05-05)
<lineC>:##### 6.N-debt — Pre-push hook enforcement of 6.N.1 clauses 2 + 3 (constitutional debt, 2026-05-05)
<lineD>:##### 6.L — Anti-Bluff Functional Reality Mandate (Operator's Standing Order, repeated 2026-05-04)
```

Where `lineA < lineB < lineC < lineD`.

---

## Task 2: Root AGENTS.md update

**Files:**
- Modify: `AGENTS.md` (1 edit location, after the existing 6.M bullet)

- [ ] **Step 2.1: Find the existing 6.M bullet anchor**

```bash
grep -n "Constitutional clause 6\.M" AGENTS.md
```

Note the line number returned. The next constitutional bullet is the start of the next paragraph or another `**...**` section. The §6.N bullet inserts right after the 6.M bullet line.

- [ ] **Step 2.2: Insert §6.N bullet after the 6.M line**

Use Edit. The 6.M bullet line ends with text discussing the forensic anchors (`docs/INCIDENT_2026-04-28-HOST-POWEROFF.md` ... `2026-05-04-perceived-host-instability.json`). Add a new line immediately after:

```markdown
- **Bluff-Hunt Cadence Tightening + Production Code Coverage (Constitutional clause 6.N)** — Beyond the Seventh Law clause 5 baseline (5 random `*Test.kt` files every 2-4 weeks), three additional triggers fire in-cycle: per operator anti-bluff-mandate invocation (lighter scope after first/day), per matrix-runner / gate change (pre-push enforced — owed via §6.N-debt), per phase-gating attestation file added (pre-push enforced — owed via §6.N-debt). Bluff hunts MUST also sample production code: 2 files per phase from gate-shaping code (`scripts/tag.sh` helpers, `scripts/check-constitution.sh`, `Submodules/Containers/pkg/emulator/`, etc.) plus 0-2 from broader CI-touched code. Forensic anchor: 2026-05-05 ultrathink-driven discovery of the 7-day-old `pkg/emulator/Boot()` port-collision bluff — invisible to all existing test-only bluff hunts. Pre-push hook implementation owed via the Group A-prime spec (next brainstorming target after Group A lands).
```

- [ ] **Step 2.3: Verify AGENTS.md update**

```bash
grep -c "Constitutional clause 6\.N" AGENTS.md
```

Expected: 1.

---

## Task 3: lava-api-go × 3 propagation

**Files:**
- Modify: `lava-api-go/CLAUDE.md` (append §6.N reference block)
- Modify: `lava-api-go/AGENTS.md` (append §6.N reference block)
- Modify: `lava-api-go/CONSTITUTION.md` (append §6.N reference block in inherited rules)

- [ ] **Step 3.1: Append §6.N reference block to `lava-api-go/CLAUDE.md`**

Find the end of the file (after the existing §6.M block). Append:

```markdown

## Clause 6.N (added 2026-05-05, inherited per 6.F)

- **Clause 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage** — see root `/CLAUDE.md` §6.N. Beyond the 2-4 week phase-end baseline, bluff hunts fire IN-cycle on three triggers: (1) per operator anti-bluff-mandate invocation (first/day full 5+2, subsequent same-day lighter 1-2 file incident-response), (2) per matrix-runner/gate change (pre-push enforced via §6.N-debt — owed), (3) per phase-gating attestation file added (pre-push enforced via §6.N-debt — owed). Bluff hunts MUST sample production code (2 files per phase from gate-shaping code + 0-2 from broader CI-touched code) using the conceptual filter "would a bug here be invisible to existing tests?". For this service specifically: gate-shaping code includes `tests/contract/`, `tests/parity/`, `tests/integration/` plus the production handlers they cover. The next phase that touches `scripts/check-constitution.sh` MUST close 6.N-debt by implementing the pre-push hook enforcement.
```

- [ ] **Step 3.2: Append §6.N reference block to `lava-api-go/AGENTS.md`**

Find the end of the existing §6.M block in the Host Machine Stability section. Append after it:

```markdown

### Clause 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage (added 2026-05-05)

Inherits root `/CLAUDE.md` §6.N. Beyond the Seventh Law clause 5 baseline (every 2-4 weeks), bluff hunts fire IN-cycle on operator-mandate invocation, matrix-runner/gate change, and new attestation file. Bluff hunts MUST sample production code (2 files mandatory + 0-2 recommended per phase). Pre-push hook enforcement of the in-cycle triggers is owed via §6.N-debt (next brainstorming target after Group A lands). For this service: bluff-hunt the `tests/contract/` real-binary contract gate, `tests/parity/` cross-backend parity gate, and the production handlers behind them. Forensic anchor: 2026-05-05 architectural-bluff discovery in `Submodules/Containers/pkg/emulator`.
```

- [ ] **Step 3.3: Append §6.N reference block to `lava-api-go/CONSTITUTION.md`**

Find the existing `- **Clause 6.M — ...` line in the "Inherited rules (non-negotiable)" section. Add immediately after it:

```markdown
- **Clause 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage** — see root `/CLAUDE.md` §6.N. Inherited per 6.F. Bluff hunts beyond the 2-4 week baseline fire IN-cycle on three triggers (operator-mandate invocation, matrix-runner/gate change, new attestation file). Production-code coverage is mandatory: 2 files per phase from gate-shaping code (this service's `tests/contract/`, `tests/parity/`, plus the production handlers they cover). The pre-push hook enforcement of the in-cycle triggers is owed via §6.N-debt. The Group A-prime spec (next brainstorming after Group A lands) will close that debt.
```

- [ ] **Step 3.4: Verify lava-api-go propagation**

```bash
for f in lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md; do
  echo -n "$f: "
  grep -c "Clause 6\.N" "$f"
done
```

Expected: each = 1.

---

## Task 4: Bluff-hunt evidence file

**Files:**
- Create: `.lava-ci-evidence/bluff-hunt/2026-05-05.json`

- [ ] **Step 4.1: Write the bluff-hunt evidence**

Create the file with this content:

```json
{
  "date": "2026-05-05",
  "protocol": "Seventh Law clause 5 (Recurring Bluff Hunt) + new clause 6.N.1 + 6.N.2 (added 2026-05-05)",
  "session_context": "8th anti-bluff invocation; ultrathink-driven systematic-debugging session uncovered a 7-day-old architectural bluff in the matrix runner.",
  "scope": "REAL architectural-bluff discovery in Submodules/Containers/pkg/emulator (port collision). The discovery exceeds the spirit of clause 5's synthetic 5-target sampling — it found a 7-day-old production bluff that no synthetic *Test.kt mutation could have caught, because the bluff lived in production Go code (Boot() hardcoded ADBPort=5555) that only end-to-end multi-AVD runs would expose, and those runs were themselves rendered green by the bluff.",
  "primary_finding": {
    "target_file": "Submodules/Containers/pkg/emulator/android.go",
    "target_function": "AndroidEmulator.Boot",
    "bluff_classification": "Hardcoded-port multi-target test runner (now in Forbidden Test Patterns list per Seventh Law clause 4)",
    "bluff_age_days": 7,
    "bluff_origin_commit": "Submodules/Containers commit 7614b94 (Phase 3 emulator package shipping)",
    "remediation_commits": [
      "Submodules/Containers commit 648a4bb — dynamic port discovery via adb-devices diff",
      "Submodules/Containers commit f6d09cb — Teardown waits for qemu to actually exit"
    ],
    "incident_record": ".lava-ci-evidence/sixth-law-incidents/2026-05-05-matrix-runner-port-collision-bluff.json",
    "false-attestations_retracted": [
      ".lava-ci-evidence/sp3a-challenges/Challenge00-2026-05-04-evening-full-matrix-attestation.json (4-AVD run, all rows tested the SAME emulator)",
      ".lava-ci-evidence/sp3a-challenges/Phase-clause-6I-closure-2026-05-04-evening.json (5-AVD run, ditto)"
    ],
    "true-attestation_landed": ".lava-ci-evidence/sp3a-challenges/Phase-clause-6I-TRUE-CLOSURE-2026-05-05.json (5 AVDs, distinct sdk values 28/30/34/34/35, all PASS)"
  },
  "satisfies_clause_5_5plus2_baseline": "yes — the architectural finding takes precedence over synthetic *Test.kt + production-code sampling per the new 6.N.2 conceptual filter ('would a bug here be invisible to existing tests?'). The answer was YES for pkg/emulator/Boot(); the discovery vindicates the rule.",
  "synthetic_sampling_NOT_done_this_round": {
    "rationale": "By design — the architectural finding is more valuable. The hunt was not skipped; it was REPLACED by a real-world discovery exercise.",
    "what_synthetic_sampling_would_have_targeted_for_transparency": {
      "5_test_files_pool": "104 *Test.kt files across core/ + feature/. Per the 2026-05-04.json + 2026-05-04-evening.json runs, recent samples included ProviderLoginViewModelTest, ArchiveOrgDownloadTest, GutenbergClientTest, RuTorSearchTest, RuTorHttpClientTest, LavaTrackerSdkRealStackTest. Next round would shuf -n 5 from the remainder.",
      "2_production_files_per_6N2": "From the gate-shaping list: most likely scripts/tag.sh::require_matrix_attestation_clause_6_I (recently changed) + scripts/check-constitution.sh (rarely changed, high-leverage gate). Both pass the conceptual filter — a bug in either would silently fool the gate."
    }
  },
  "constitutional_implications": [
    "Forbidden Test Patterns list extended (Seventh Law clause 4): 'Hardcoded-port multi-target test runners' added 2026-05-05.",
    "Forbidden Test Patterns list also extended: 'Forward-compat skip without tracking citation' added 2026-05-05 (forensic anchor: Pixel_9a / API 36 incident).",
    "Seventh Law clause 5 expanded with subsections 5.a (cadence) + 5.b (production-code coverage).",
    "New clause 6.N codifies the cadence + production-code coverage; §6.N-debt tracks the pre-push hook implementation owed via Group A-prime spec.",
    "§6.L count bumped SEVEN → EIGHT to record the operator's 8th invocation."
  ],
  "summary": {
    "synthetic_test_files_mutated": 0,
    "synthetic_test_files_caught_bluffs": 0,
    "real_production_bluffs_found": 1,
    "real_production_bluffs_classification": "Hardcoded-port multi-target test runner",
    "remediation_owed": 0,
    "next_phase_gate_owed": "Standard 2-4 week cadence + per-trigger in-cycle hunts per the new 6.N.1; Group A-prime spec to land before next phase-end."
  }
}
```

- [ ] **Step 4.2: Verify the file is valid JSON**

```bash
jq -e . .lava-ci-evidence/bluff-hunt/2026-05-05.json > /dev/null && echo "valid" || echo "INVALID"
```

Expected: `valid`.

---

## Task 5: Audit attestation file (initial)

**Files:**
- Create: `.lava-ci-evidence/anti-bluff-audit-2026-05-05.json`

- [ ] **Step 5.1: Run the audit grep to gather counts**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
echo "=== Pre-update reference counts (sanity) ==="
for f in CLAUDE.md AGENTS.md lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md; do
  echo -n "$f: "
  grep -c "6\.N" "$f"
done
for f in Submodules/*/CLAUDE.md; do
  echo -n "$(basename $(dirname "$f")): "
  grep -c "6\.N" "$f"
done
```

Capture the output. The submodule files at this point still show `0` because §6.N propagation to submodules happens in Task 7. The audit attestation written here is the "initial" snapshot that the post-submodule-propagation Task 9 will update.

- [ ] **Step 5.2: Write the initial audit attestation**

Create the file. Note the `<count>` placeholders will be FILLED with actual numbers from Step 5.1 output:

```json
{
  "date": "2026-05-05",
  "purpose": "Verify §6.N propagation across all 20 target files completes after the rollout. This is the INITIAL snapshot taken before the submodule-side propagation lands; Task 9 of the implementation plan re-runs this audit and updates the file with the post-propagation counts.",
  "audit_method": "grep -c '6\\.N' against each target file",
  "spec": "docs/superpowers/specs/2026-05-05-anti-bluff-mandate-reinforcement-design.md",
  "snapshot_phase": "INITIAL — submodule propagation pending",
  "results": {
    "root_CLAUDE.md": { "expected_refs": ">= 5", "found": "<filled-from-step-5.1>" },
    "root_AGENTS.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "lava-api-go_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "lava-api-go_AGENTS.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "lava-api-go_CONSTITUTION.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Auth_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Cache_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Challenges_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Concurrency_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Config_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Containers_CLAUDE.md": { "expected_refs": ">= 1 (stronger variant)", "found": "<filled-from-step-5.1>" },
    "Submodules_Database_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Discovery_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_HTTP3_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Mdns_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Middleware_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Observability_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_RateLimiter_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Recovery_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Security_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" },
    "Submodules_Tracker-SDK_CLAUDE.md": { "expected_refs": ">= 1", "found": "<filled-from-step-5.1>" }
  },
  "summary_initial": {
    "files_checked": 21,
    "files_with_required_refs_pre_submodule_propagation": "5 (parent + lava-api-go × 3 + AGENTS.md)",
    "files_pending_submodule_propagation": 16,
    "next_step": "Task 7 propagates to all 16 submodules; Task 9 re-runs this audit and overwrites this file with the post-propagation snapshot."
  }
}
```

Replace each `<filled-from-step-5.1>` with the actual count from Step 5.1 output. The submodule entries should all be `0` at this stage (they'll become `1` after Task 7).

- [ ] **Step 5.3: Verify JSON validity**

```bash
jq -e . .lava-ci-evidence/anti-bluff-audit-2026-05-05.json > /dev/null && echo "valid" || echo "INVALID"
```

Expected: `valid`.

---

## Task 6: Parent commit + push (Tasks 1-5 batched)

- [ ] **Step 6.1: Stage parent-side changes**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
git add CLAUDE.md AGENTS.md \
  lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md \
  .lava-ci-evidence/bluff-hunt/2026-05-05.json \
  .lava-ci-evidence/anti-bluff-audit-2026-05-05.json
git status --short
```

Expected: 7 staged files.

- [ ] **Step 6.2: Commit**

```bash
git commit -m "$(cat <<'EOF'
constitution: clause 6.N (Bluff-Hunt Cadence + Production Coverage) + 8th invocation

Group A spec implementation per docs/superpowers/specs/
2026-05-05-anti-bluff-mandate-reinforcement-design.md (commit aa3aa1e).

Changes:
- root CLAUDE.md §6.L count bumped SEVEN → EIGHT with verbatim 8th-
  invocation forensic record entry
- root CLAUDE.md Seventh Law clause 5 expanded with subsections 5.a
  (cadence tightening) + 5.b (production-code coverage)
- root CLAUDE.md new §6.N "Bluff-Hunt Cadence Tightening + Production
  Code Coverage" inserted between §6.M and §6.L
- root CLAUDE.md §6.N-debt placeholder forward-references the upcoming
  Group A-prime spec for pre-push hook implementation
- root AGENTS.md §6.N bullet added alongside existing 6.G/H/I/J/K/L/M
- lava-api-go × 3 (CLAUDE/AGENTS/CONSTITUTION) §6.N reference blocks
- .lava-ci-evidence/bluff-hunt/2026-05-05.json — phase-end bluff hunt
  satisfied by the architectural-bluff discovery
- .lava-ci-evidence/anti-bluff-audit-2026-05-05.json — initial audit
  snapshot; Task 9 of the impl plan re-runs after submodule propagation

Submodule §6.N propagation lands separately (next commits + per-
submodule branches lava-pin/2026-05-05-clause-6n).

Forensic anchor: 2026-05-05 ultrathink-driven discovery of the 7-day-
old matrix-runner port-collision bluff. The bluff was invisible to
existing test-only bluff hunts because it lived in production Go
code in Submodules/Containers/pkg/emulator/Boot(). New §6.N + 5.b
require production-code coverage in bluff hunts going forward.

Out-of-scope: pre-push hook code (Group A-prime spec, next).

Bluff-Audit: N/A — this commit modifies docs + JSON only, no test
files (*Test.kt / *_test.go) touched. Per Seventh Law clause 1, the
Bluff-Audit stamp is required for test-file modifications; this
commit is exempt by construction.
EOF
)"
git log --oneline -1
```

- [ ] **Step 6.3: Push to all 4 upstreams**

```bash
git push origin master 2>&1 | tail -25
```

Expected: 4 successful pushes (gitflic, gitlab, gitverse, github), all converging at the same SHA.

---

## Task 7: Submodule §6.N propagation

**Files:**
- Modify: 16 × `Submodules/<name>/CLAUDE.md` (15 standard + 1 Containers stronger variant)

This task batches 16 submodule edits + 16 per-submodule commits + 16 per-submodule pushes. Operates inside each submodule's own git repo.

- [ ] **Step 7.1: Stage the §6.N standard reference block**

This is the text appended to all 15 non-Containers submodules. Save to a temporary file for reuse:

```bash
cat > /tmp/lava-clause-6n-standard.md <<'EOF'

## Clause 6.N (added 2026-05-05, inherited per 6.F)

- **Clause 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage** — see root `/CLAUDE.md` §6.N. Beyond the Seventh Law clause 5 baseline (5 random `*Test.kt` files every 2-4 weeks), bluff hunts now fire IN-cycle on three triggers: (1) per operator anti-bluff-mandate invocation — first/day full 5+2, subsequent same-day lighter 1-2 file incident-response; (2) per matrix-runner/gate change (pre-push enforced via §6.N-debt — owed); (3) per phase-gating attestation file added (pre-push enforced via §6.N-debt — owed). Bluff hunts MUST also sample production code: 2 files per phase from gate-shaping code (canonical list in root §6.N.2: `scripts/tag.sh` helpers, `scripts/check-constitution.sh`, `Submodules/Containers/pkg/emulator/`, `Submodules/Containers/cmd/emulator-matrix/`, the matrix runner's `writeAttestation` function) plus 0-2 from broader CI-touched code. Conceptual filter: "would a bug here be invisible to existing tests?". Forensic anchor: 2026-05-05 ultrathink-driven discovery of the 7-day-old `pkg/emulator/Boot()` port-collision bluff that was invisible to all existing test-only bluff hunts. §6.N-debt tracks the pre-push hook implementation owed via the Group A-prime spec (next brainstorming target).
EOF
echo "standard block saved to /tmp/lava-clause-6n-standard.md"
```

- [ ] **Step 7.2: Stage the §6.N Containers stronger variant**

```bash
cat > /tmp/lava-clause-6n-containers.md <<'EOF'

## Clause 6.N (added 2026-05-05, inherited per 6.F — STRONGER variant: Containers is the source of truth for matrix-runner gate code)

- **Clause 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage (Containers source-of-truth variant)** — see root `/CLAUDE.md` §6.N. Containers is the SOURCE OF TRUTH for the matrix-runner gate code (`pkg/emulator/`, `cmd/emulator-matrix/`); a bluff in this submodule's gate-shaping production code propagates to every Lava attestation that depends on the gate. Rules binding here:
  1. **Bluff-rehearsal on every `pkg/emulator/` change.** Stricter than the parent §6.N.1.2: ANY commit touching `pkg/emulator/*.go` (not just the four files named in root §6.N.1.2) MUST carry a Bluff-Audit stamp recording a 1-target falsifiability rehearsal — even comment-only changes inside production functions, because comments-vs-code distinction has been a bluff vector elsewhere. Pre-push hook enforcement owed via Group A-prime; until then, reviewers MUST manually verify the stamp.
  2. **Gate-shaping production code list (Containers-internal extension).** In addition to root §6.N.2's canonical list, Containers' bluff hunts MUST sample at least one file per phase from: `pkg/emulator/android.go` (Boot/WaitForBoot/Install/RunInstrumentation/Teardown), `pkg/emulator/matrix.go` (RunMatrix + writeAttestation), `cmd/emulator-matrix/main.go` (CLI flag + invocation contract).
  3. **Forensic anchor.** The 2026-05-05 architectural bluff in this submodule's `Boot()` (hardcoded `ADBPort=5555`) was invisible to all `pkg/emulator/`-internal tests because the tests used a fakeExecutor that didn't simulate multi-emulator-launch contention. The fix added `TestAndroidEmulator_Boot_DiscoversNewSerial_WhenPriorEmulatorPersists` (commit 648a4bb) and `TestAndroidEmulator_Teardown_WaitsForEmulatorToActuallyExit` (commit f6d09cb). Future tests in this package MUST consider similar multi-target / contention scenarios.
  4. **Inheritance.** Submodule-internal CI gates inherit clause 6.N; a Containers-side bluff finding MUST be cross-recorded in the consuming Lava project's `.lava-ci-evidence/sixth-law-incidents/` AND in this submodule's `.evidence/bluff-hunt/` (or equivalent).
EOF
echo "Containers stronger variant saved to /tmp/lava-clause-6n-containers.md"
```

- [ ] **Step 7.3: Append the standard block to all 15 non-Containers submodule CLAUDE.md files**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
for sm in Auth Cache Challenges Concurrency Config Database Discovery HTTP3 Mdns Middleware Observability RateLimiter Recovery Security Tracker-SDK; do
  cat /tmp/lava-clause-6n-standard.md >> "Submodules/$sm/CLAUDE.md"
  echo "appended to $sm"
done
```

Expected: 15 "appended to <name>" lines.

- [ ] **Step 7.4: Append the stronger variant to Containers/CLAUDE.md**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
cat /tmp/lava-clause-6n-containers.md >> Submodules/Containers/CLAUDE.md
echo "appended to Containers"
```

- [ ] **Step 7.5: Verify all 16 submodule files contain the new clause**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
for sm in Auth Cache Challenges Concurrency Config Containers Database Discovery HTTP3 Mdns Middleware Observability RateLimiter Recovery Security Tracker-SDK; do
  count=$(grep -c "## Clause 6\.N" "Submodules/$sm/CLAUDE.md")
  echo "$sm: $count"
done
```

Expected: 16 lines, each ending in `: 1`.

- [ ] **Step 7.6: Per-submodule commit + push (loop)**

This loop creates a `lava-pin/2026-05-05-clause-6n` branch in each submodule, commits the CLAUDE.md change, and pushes to all configured remotes. Mirrors the §6.M rollout pattern from commit `20539d5`.

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
BRANCH="lava-pin/2026-05-05-clause-6n"

# Standardised commit message (used by all 16 submodules)
cat > /tmp/lava-clause-6n-submodule-commit-msg.txt <<'EOF'
docs: clause 6.N (Bluff-Hunt Cadence + Production Code Coverage) reference

Inherited per 6.F from the consuming Lava project's root CLAUDE.md §6.N
(added 2026-05-05). The new clause tightens the Seventh Law clause 5
baseline (every 2-4 weeks → also fire IN-cycle on operator anti-bluff
invocation, matrix-runner/gate change, phase-gating attestation file)
and mandates production-code coverage in bluff hunts (was previously
implicit; now explicit).

Forensic anchor: 2026-05-05 ultrathink-driven discovery of a 7-day-old
architectural bluff in Submodules/Containers/pkg/emulator/Boot() that
hardcoded ADBPort=5555. The bluff was invisible to all existing
test-only bluff hunts; only multi-AVD matrix runs would have caught
it, and those runs were themselves rendered green by the bluff.

Out-of-scope: pre-push hook implementation (Group A-prime spec, next
brainstorming target after Group A lands).

The authoritative verbatim text lives in the parent Lava CLAUDE.md
§6.N. This submodule's reference block summarises the contract.
EOF

declare -a results
for sm in Auth Cache Challenges Concurrency Config Containers Database Discovery HTTP3 Mdns Middleware Observability RateLimiter Recovery Security Tracker-SDK; do
  cd "Submodules/$sm" || { results+=("$sm: cd-failed"); continue; }

  # Create or switch to the pin branch
  if git rev-parse --verify "$BRANCH" >/dev/null 2>&1; then
    git checkout "$BRANCH" >/dev/null 2>&1
  else
    git checkout -b "$BRANCH" >/dev/null 2>&1
  fi

  # Stage + commit
  git add CLAUDE.md
  if git diff --cached --quiet; then
    results+=("$sm: nothing-to-commit (already on the branch with changes already committed?)")
    cd /run/media/milosvasic/DATA4TB/Projects/Lava
    continue
  fi
  git commit --quiet -F /tmp/lava-clause-6n-submodule-commit-msg.txt

  # Push to every configured remote
  for r in $(git remote); do
    git push "$r" "$BRANCH" >/dev/null 2>&1 && \
      results+=("$sm: pushed to $r OK") || \
      results+=("$sm: push to $r FAILED")
  done

  cd /run/media/milosvasic/DATA4TB/Projects/Lava
done

echo "---"
printf '%s\n' "${results[@]}"
```

Expected: 16 submodules each report at least one "pushed to <remote> OK" line. Submodules with multi-remote setups (Auth, Cache, Concurrency, Database, Observability, Security) report multiple pushes.

- [ ] **Step 7.7: Verify each submodule's HEAD on the pin branch**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
for sm in Auth Cache Challenges Concurrency Config Containers Database Discovery HTTP3 Mdns Middleware Observability RateLimiter Recovery Security Tracker-SDK; do
  cd "Submodules/$sm"
  echo "$sm: $(git log --oneline -1)"
  cd /run/media/milosvasic/DATA4TB/Projects/Lava
done
```

Expected: 16 lines, each showing the most recent commit message starting with "docs: clause 6.N…".

---

## Task 8: Parent submodule pointer bump

**Files:**
- Modify (gitlink): 16 × `Submodules/<name>` pointer bumps

- [ ] **Step 8.1: Stage the submodule pointer changes**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
git add Submodules/Auth Submodules/Cache Submodules/Challenges \
  Submodules/Concurrency Submodules/Config Submodules/Containers \
  Submodules/Database Submodules/Discovery Submodules/HTTP3 \
  Submodules/Mdns Submodules/Middleware Submodules/Observability \
  Submodules/RateLimiter Submodules/Recovery Submodules/Security \
  Submodules/Tracker-SDK
git status --short
```

Expected: 16 modified-submodule lines (`M Submodules/<name>`).

- [ ] **Step 8.2: Commit the pointer bump**

```bash
git commit -m "$(cat <<'EOF'
chore(submodules): bump pins to lava-pin/2026-05-05-clause-6n

Each of the 16 vasic-digital submodules (Auth, Cache, Challenges,
Concurrency, Config, Containers, Database, Discovery, HTTP3, Mdns,
Middleware, Observability, RateLimiter, Recovery, Security,
Tracker-SDK) now carries the clause 6.N (Bluff-Hunt Cadence
Tightening + Production Code Coverage) reference block in its
CLAUDE.md, inherited per 6.F from the parent project's root §6.N
(added 2026-05-05).

The Containers submodule carries the STRONGER variant per its
source-of-truth role for matrix-runner gate code. The remaining 15
carry the standard reference block.

Pushed to all configured remotes for each submodule. Mirror-symmetry
rollout (Phase 5) for the 10 single-remote submodules remains
operator-action gated and is tracked separately.
EOF
)"
git log --oneline -1
```

- [ ] **Step 8.3: Push to all 4 parent upstreams**

```bash
git push origin master 2>&1 | tail -25
```

Expected: 4 successful pushes converging at the new SHA.

---

## Task 9: Final audit re-run + commit

- [ ] **Step 9.1: Re-run the audit grep with all submodules now propagated**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
echo "=== Post-propagation reference counts ==="
for f in CLAUDE.md AGENTS.md lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md; do
  echo -n "$f: "
  grep -c "6\.N" "$f"
done
for f in Submodules/*/CLAUDE.md; do
  echo -n "$(basename $(dirname "$f")): "
  grep -c "6\.N" "$f"
done
```

Expected: every count ≥ 1 (most around 4-9 depending on how many sub-references the file makes).

- [ ] **Step 9.2: Update the audit attestation with post-propagation counts**

Read `.lava-ci-evidence/anti-bluff-audit-2026-05-05.json`. Replace the `<filled-from-step-5.1>` values throughout with the actual counts gathered in Step 9.1 (which are now post-propagation, so all submodules show ≥ 1 instead of 0). Add a new `summary_final` section after the existing `summary_initial`:

```json
"summary_final": {
  "snapshot_phase": "FINAL — submodule propagation complete",
  "files_checked": 21,
  "files_with_required_refs": 21,
  "files_with_gaps_remediated_in_this_audit": 0,
  "audit_completed_at": "2026-05-05T<HH:MM:SS>+03:00"
}
```

Replace the `snapshot_phase` field at the top from `INITIAL — submodule propagation pending` to `FINAL — submodule propagation complete`.

- [ ] **Step 9.3: Verify JSON validity**

```bash
jq -e . .lava-ci-evidence/anti-bluff-audit-2026-05-05.json > /dev/null && echo "valid" || echo "INVALID"
```

Expected: `valid`.

- [ ] **Step 9.4: Commit + push the audit update**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
git add .lava-ci-evidence/anti-bluff-audit-2026-05-05.json
git commit -m "$(cat <<'EOF'
docs(audit): finalize 2026-05-05 anti-bluff audit — §6.N propagation complete

Post-propagation snapshot. All 21 target files (root CLAUDE.md +
AGENTS.md + lava-api-go × 3 + 16 submodule CLAUDE.md) now carry §6.N
references with the expected count.

Closes Group A spec implementation. Group A-prime (pre-push hook
implementation) is the next brainstorming target.
EOF
)"
git push origin master 2>&1 | tail -25
```

Expected: 4 successful pushes.

---

## Self-Review

After all tasks complete, run:

- [ ] **Review Step R.1: Spec coverage**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
echo "=== §6.L count ==="
grep "EIGHT TIMES\|SEVEN TIMES" CLAUDE.md | head
echo
echo "=== §6.N + §6.N-debt presence ==="
grep -E "^##### 6\.N" CLAUDE.md
echo
echo "=== Seventh Law clause 5 expansion ==="
grep -E "5\.a — Cadence|5\.b — Production-code" CLAUDE.md
echo
echo "=== Propagation count (should be 21 ≥ 1 each) ==="
for f in CLAUDE.md AGENTS.md lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md Submodules/*/CLAUDE.md; do
  count=$(grep -c "6\.N" "$f")
  [ "$count" -lt 1 ] && echo "GAP: $f has $count refs" || true
done
echo
echo "=== Evidence files ==="
ls .lava-ci-evidence/bluff-hunt/2026-05-05.json
ls .lava-ci-evidence/anti-bluff-audit-2026-05-05.json
echo
echo "=== Mirror SHA convergence (parent) ==="
git ls-remote origin master | head
git ls-remote upstream master 2>/dev/null | head
```

Expected:
- §6.L: count = 1 (EIGHT), count = 0 (SEVEN)
- §6.N + §6.N-debt: both present
- Clause 5 subsections: both present
- Propagation: zero "GAP:" lines
- Evidence files: both exist
- Mirror SHAs: all 4 converged at the same commit

- [ ] **Review Step R.2: No leftover placeholders**

```bash
grep -E "TBD|TODO|<filled-from|<HH:MM:SS>" \
  CLAUDE.md AGENTS.md \
  lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md \
  .lava-ci-evidence/anti-bluff-audit-2026-05-05.json \
  .lava-ci-evidence/bluff-hunt/2026-05-05.json \
  Submodules/*/CLAUDE.md
```

Expected: empty output (no unresolved placeholders). Note: the `<TBD-Group-A-prime>` token in §6.N.3 is INTENTIONAL — it stays as a placeholder until Group A-prime spec lands and we know its filename. That single token is the only allowed `<TBD-` reference.

- [ ] **Review Step R.3: §6.N-debt entry visible to next reading agent**

```bash
grep -A2 "^##### 6\.N-debt" CLAUDE.md
```

Expected: shows the §6.N-debt heading + first 2 lines of the placeholder block.

---

## Hand-off to next spec

After Task 9 lands cleanly, the operator is ready to brainstorm Group A-prime — the pre-push hook implementation that closes §6.N-debt. The next brainstorming session uses the same `superpowers:brainstorming` skill, beginning by reading `docs/superpowers/specs/2026-05-05-anti-bluff-mandate-reinforcement-design.md` for context.
