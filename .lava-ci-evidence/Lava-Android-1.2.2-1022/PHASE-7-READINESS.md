# Phase 7 — Release Tag Readiness Report

**Tag target:** `Lava-Android-1.2.2-1022`
**Date:** 2026-05-05 (late evening)
**Session scope:** 8-phase post-Group-C-pkg-vm follow-up sequence (Phases 0-6 complete; this Phase 7 readiness report is the operator handoff for the actual tag cut)

## Status: **operator-completion-required**

Per Constitutional Seventh Law clause 3:

> Pre-Tag Real-Device Attestation. Before any release tag is cut, the operator MUST execute the user-visible flows (login, search, browse, view topic, download .torrent) on a real Android device against the real backend services and record a JSON attestation file at `.lava-ci-evidence/<tag-name>/real-device-attestation.json`. The attestation MUST include: device model, Android version, app version, timestamp, command-by-command checklist of executed user actions, and at least 3 screenshots OR a video recording referenced by hash. `scripts/tag.sh` MUST refuse to operate on a commit lacking the matching attestation. There is no exception.

This clause is non-bypassable by any agent. The agent's role in Phase 7 is to (a) prepare the codebase + the partial evidence pack, (b) document precisely what the operator must complete, (c) verify mechanically that everything else is ready.

---

## What this session delivered (Phases 0-6)

| Phase | Lava parent commit | Containers branch state | Mirrors converged |
|---|---|---|---|
| 0 — §6.L NINTH propagation | `ca7a421` | (16 submodule pin bumps) | 4/4 |
| 1 — `tag.sh` VM gate extension | `a7cfb35` | unchanged | 4/4 |
| 2 — AVD cache integration | `7c1b98a` | `daba0a5` (Containers) | 4/4 + 2/2 |
| 3 — Real qcow2/zip extraction | `6eb0a0a` | `b7d07f2` | 4/4 + 2/2 |
| 4 — Real upstream SHA hashes | `9fa9737` | unchanged | 4/4 |
| 5 — Real SSH/QMP impls + key auth | `c03f899` | `d1c0165` | 4/4 + 2/2 |
| 6 — Group C remaining (network sim + screenshot) | `69de2e5` | `8ec8046` | 4/4 + 2/2 |

**Lava parent at `master` HEAD:** `69de2e5c9da6d0e1f48a976dfbfed3ceb4827417`
**Containers at `lava-pin/2026-05-07-pkg-vm` HEAD:** `8ec80460706b3b15c7501a4132a3543ab8e087a9`

**Total mutation rehearsals across Phases 0-6:** 16 in commit bodies + 13 from the immediately preceding pkg/vm bundled cycle = **29 mutation rehearsals** this session, all reverted, every gate-shaping production file falsifiability-tested.

## Evidence pack — what's complete in this dir

- `mirror-smoke/2026-05-05-mirror-convergence.json` ✓ — all configured mirrors verified live at HEAD
- `bluff-audit/2026-05-05-session-summary.json` ✓ — 29-rehearsal aggregate
- `PHASE-7-READINESS.md` (this file) ✓ — operator handoff document

## Evidence pack — what `scripts/tag.sh` requires next (operator action)

`scripts/tag.sh::require_evidence_for_android` (lines 218-281) checks for:

### A. `ci.sh.json` — local CI gate evidence (operator-runnable, multi-hour)

The file `.lava-ci-evidence/Lava-Android-1.2.2-1022/ci.sh.json` is produced by `scripts/ci.sh --full`. This run executes:

1. Hosted-CI forbidden-files check
2. Host-power forbidden-command regex check
3. Spotless across the whole project
4. Constitution parser via `scripts/check-constitution.sh`
5. Forbidden-files greps (credentials, hosted-CI configs)
6. Unit tests across every Gradle module
7. Cross-backend parity gate (`tests/parity/` in lava-api-go, if applicable)
8. Mutation tests where wired
9. Fixture freshness via `scripts/check-fixture-freshness.sh`
10. Compose UI Challenge Tests (`./scripts/run-emulator-tests.sh`) against a connected Android device or emulator

The full run takes 45-90 minutes on a developer machine. **Operator command:**

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
nice -n 19 scripts/ci.sh --full
# Captures evidence into .lava-ci-evidence/<UTC-timestamp>/; copy/rename
# the outermost JSON into Lava-Android-1.2.2-1022/ci.sh.json
```

### B. `challenges/C{1..8}.json` — Challenge Test attestations with status:VERIFIED (operator-driven, real-device)

8 attestation files, one per Challenge Test (C1-C8), each with `"status": "VERIFIED"`. These come from running the Compose UI Challenge Tests on a real Android device + the operator manually walking through each Challenge's user-visible flow.

Some of these attestations may already exist in the operator's prior session evidence packs (search `.lava-ci-evidence/` for older `Lava-Android-*` packs); if attestations from a prior tag are still valid for this code state, they can be reused per `scripts/tag.sh`'s evidence-pack contract (the operator decides relevance).

### C. `bluff-audit/<recent>.json` — bluff-hunt evidence (✓ provided by this session)

The session-level summary in this pack at `bluff-audit/2026-05-05-session-summary.json` documents 29 mutation rehearsals. Operator may add their own bluff-hunt JSON if they run an additional sample (per §6.N.1.1 same-day rule, multiple invocations are permitted).

### D. `mirror-smoke/<recent>.json` — mirror convergence (✓ provided by this session)

`mirror-smoke/2026-05-05-mirror-convergence.json` documents 4-mirror Lava parent convergence + 2-mirror Containers convergence verified live.

### E. `real-device-verification.md` — operator real-device verification (operator-authored, MUST set status:VERIFIED)

A markdown file authored by the operator after they've personally executed the user-visible flows on a real Android device. Required header:

```markdown
status: VERIFIED
device: <e.g. Pixel 9a>
android_version: <e.g. 15>
app_version: 1.2.2 (1022)
timestamp: <ISO 8601>
flows_executed:
  - login (rutracker.org)
  - search
  - browse top-level categories
  - view a topic
  - download a .torrent file
screenshots_or_video:
  - <path or hash>
  - <path or hash>
  - <path or hash>
```

This is the load-bearing artifact per Seventh Law clause 3 — "There is no exception."

### F. `matrix/<run-id>/real-device-verification.json` — multi-AVD matrix attestation (operator-runnable, ~30-60 min)

Produced by:

```bash
cd /run/media/milosvasic/DATA4TB/Projects/Lava
./scripts/run-emulator-tests.sh \
    --tag Lava-Android-1.2.2-1022 \
    --avds CZ_API28_Phone:28:phone,CZ_API30_Phone:30:phone,CZ_API34_Phone:34:phone,CZ_API36_Phone:36:phone \
    --boot-timeout 8m
```

Per `scripts/tag.sh::require_matrix_attestation_clause_6_I` + `require_matrix_attestation_group_b_gates`:

- Coverage: API 28, 30, 34, AND `compileSdk` (currently 36) — all 4 must appear as rows.
- `all_passed: true` on every matrix file.
- `gating: true` on the run-level field.
- All rows: `concurrent: 1`.
- All rows: `diag.sdk == api_level`.
- All rows: `failure_summaries: []`.

The operator may also opt to run the new Phase 6 features:

```bash
# With network simulation:
./scripts/run-emulator-tests.sh --tag Lava-Android-1.2.2-1022 ... \
    --network-profile 4g

# With screenshot-on-failure (default true; flag to opt out is --capture-screenshot-on-failure=false)
```

### Optional: VM matrix attestations (Phases 5+6 closures)

`scripts/run-vm-signing-matrix.sh --tag Lava-Android-1.2.2-1022` and `scripts/run-vm-distro-matrix.sh --tag Lava-Android-1.2.2-1022` produce additional attestations under the same pack dir. These are NOT required for the Android tag gate but ARE produced by the new Phase 6 capabilities and DO give the operator forensic evidence about cross-arch + cross-OS behavior. The 9-config cross-arch matrix takes ~75-120 min under TCG; the 3-distro cross-OS matrix takes ~30-45 min.

---

## Mechanical readiness check

Run `scripts/tag.sh --dry-run --app android --no-push --no-bump` to see the current gate state. Today's output (with this evidence pack partial):

```
[tag] Repo:        /run/media/milosvasic/DATA4TB/Projects/Lava
[tag] Branch:      master
[tag] Apps:        android
[tag:warn] [android] --dry-run: bypassing SP-3a evidence-pack gate
[tag] [android] current 1.2.2-1022 → tag 'Lava-Android-1.2.2-1022'
[dry] git tag -a Lava-Android-1.2.2-1022 -m Release Android 1.2.2 (versionCode 1022)
[tag] would create tag: Lava-Android-1.2.2-1022
```

The dry-run bypasses the evidence-pack gate. Without `--dry-run`, the gate enforces all of A-F above.

---

## Recommended operator path to actually cut the tag

1. **Collect existing C1-C8 attestations from prior tag's evidence pack.** Most likely `Lava-Android-1.2.1-127/challenges/` has them; verify they're still valid for this code state, copy into `Lava-Android-1.2.2-1022/challenges/`.

2. **Run the Compose UI matrix on the real-AVD set.**
   ```bash
   ./scripts/run-emulator-tests.sh --tag Lava-Android-1.2.2-1022 \
       --avds CZ_API28_Phone:28:phone,CZ_API30_Phone:30:phone,CZ_API34_Phone:34:phone,CZ_API36_Phone:36:phone
   ```

3. **Run scripts/ci.sh --full and capture evidence.**
   ```bash
   nice -n 19 scripts/ci.sh --full
   # then manually copy/rename the resulting .lava-ci-evidence/<UTC>/ci.sh.json
   # into Lava-Android-1.2.2-1022/ci.sh.json
   ```

4. **Author `real-device-verification.md`** with status:VERIFIED + the required fields above. Execute the user-visible flows on the operator's real Pixel 9a (or equivalent). Capture screenshots/video.

5. **Cut the tag.**
   ```bash
   scripts/tag.sh --app android
   ```
   With all evidence files in place, the 4 mirrors should accept the tag push. Verify via:
   ```bash
   for r in github gitlab gitflic gitverse; do
     git ls-remote $r refs/tags/Lava-Android-1.2.2-1022
   done
   ```
   All four MUST converge at the same SHA.

---

## What I (the agent) cannot do

Per Seventh Law clause 3 ("There is no exception"):
- Author `real-device-verification.md` with `status: VERIFIED` (authoring this without having actually executed the flows on a real device IS the exact bluff vector the clause exists to prevent)
- Run the Compose UI Challenge Tests against a real device that I don't have controlled access to
- Cut the tag itself (scripts/tag.sh's evidence-pack gate would correctly reject)

What I CAN do:
- Continue to maintain mirror convergence + branch state (DONE for Phases 0-6)
- Produce mirror-smoke + bluff-audit summaries (DONE for this pack)
- Produce documentation of operator-required steps (this file)
- Run mechanical, hermetic gates that don't require real devices (Containers tests, fixture tests, lint)

---

## Phase 7 closure status

**This phase remains OPEN until the operator cuts the tag.** The 8-phase follow-up sequence's mechanical work is complete (Phases 0-6); Phase 7 is the human-in-the-loop gate that exists by constitutional design. This is not a bug or a gap — it's the load-bearing protection against the historical "all-tests-green / most-features-broken" failure mode.

When the operator completes steps 1-5 above and cuts the tag, this file should be amended to record:
- The actual `Lava-Android-1.2.2-1022` tag SHA
- The 4-mirror convergence verification
- The next version's bump status

This file is itself part of the evidence pack and will be committed alongside whatever residual changes the operator makes.
