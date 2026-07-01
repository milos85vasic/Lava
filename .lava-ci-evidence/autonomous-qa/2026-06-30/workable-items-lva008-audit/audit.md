# Autonomous-QA read-only audit — workable-items DB + LVA-008 + §6.S CONTINUATION

- **Date:** 2026-06-30
- **HEAD:** `613083d10275b7073ea902e5030da4868eabfa10` — *"fix(archiveorg): topic-detail FlexStringSerializer + TopicPageSource seam (LVA-070) + 1078 distribute-prep"* (2026-06-30 20:11:31 +0300)
- **Mode:** read-only / validation. No app source modified, no Android Gradle, no emulator, no `:8443` backend touched.
- **Tooling note:** the `workable-items` Go binary was built + run (deterministic, explicitly allowed). The committed SQLite DB was opened read-write by the tool's SQLite driver (WAL checkpoint grew the file); it was restored byte-identical afterwards — see Task 1 integrity caveat.

Captured raw evidence in this directory: `wi-validate.txt`, `wi-diff.txt`, `wi-gate-run.txt`, `wi-freshbuild.txt`, `wi-fresh-validate-diff.txt`.

---

## TASK 1 — workable-items DB integrity (§11.4.93 / §11.4.95)

### Result summary
| Check | Result | Detail |
|---|---|---|
| `docs/workable_items.db` git-tracked (§11.4.95) | **PASS** | `git ls-files --error-unmatch` rc=0; staged blob `10366691b9cedabc483a6eaf68ee158a64573b4f` |
| `diff` byte-identical md↔DB round-trip (gate paths) | **PASS (in sync)** | `diff --db docs/workable_items.db --issues docs/Issues.md --fixed docs/Fixed.md` → `diff: DB and Markdown are in sync`, RC=0 |
| `validate` invariants (§11.4.148 D3) | **FAIL** | RC=1, **2 violations** (below) |
| `scripts/check-workable-items.sh` gate runs on this host | **FAIL (host-portability bug)** | RC=126 `cannot execute binary file: Exec format error` |

### `validate` — FAIL, 2 violations (reproduced on BOTH prebuilt-linux AND fresh-from-source builds)
```
validate: 2 violation(s):
  - LVA-036: Operator-blocked unblock_condition has no enumerated unblock CHOICES (§11.4.148 D3): "(not specified)"
  - LVA-5: Operator-blocked unblock_condition has no enumerated unblock CHOICES (§11.4.148 D3):
           "Operator runs firebase logout then firebase login:ci and updates LAVA_FIREBASE_TOKEN in gitignored .env"
```
These are **invariant violations, NOT md↔DB drift**. Both items are `Operator-blocked` and their `unblock_condition` is not expressed as an enumerated set of CHOICES the §11.4.148-D3 validator requires. (LVA-5 is the long-standing Firebase-token rotation block; LVA-036 has `(not specified)`.)

Reproduced identically by:
- the committed prebuilt `constitution/scripts/workable-items/bin/workable-items-linux` (ELF x86-64), and
- a fresh build from source (`go1.26.2`, `CGO_ENABLED=1`, `GOMAXPROCS=2 nice -n 18`) → `/tmp/wi-fresh` (ELF x86-64, BuildID `0758bf44…`).
→ The 2 violations are a real DB-content state, not a stale-binary artifact.

### `diff` — byte-identical round-trip is CLEAN (no drift)
With the **gate's** tracker paths (`docs/Issues.md`, `docs/Fixed.md`) the round-trip is in sync (RC=0).
**CLI-default quirk (not drift):** `diff --db … ` with **default** paths reports `diff: 91 difference(s)` ("present in DB, absent in Markdown" for every LVA item) — because the tool's default `--issues`/`--fixed` are **not** `docs/Issues.md`/`docs/Fixed.md`. `scripts/check-workable-items.sh` always passes them explicitly (lines 31–32, 43), so the actual gate is unaffected. Anyone running `diff` by hand MUST pass `--issues docs/Issues.md --fixed docs/Fixed.md` or they will see false drift.

### NEW FINDING — gate is non-functional on a Linux host (host-portability bug)
`scripts/check-workable-items.sh` invokes `constitution/scripts/workable-items/bin/workable-items`, which is committed as a **Mach-O 64-bit arm64 (macOS) binary**:
```
$ bash scripts/check-workable-items.sh
scripts/check-workable-items.sh: line 40: …/bin/workable-items: cannot execute binary file: Exec format error
GATE_RC=126
```
Two compounding problems:
1. The committed `bin/workable-items` is a **macOS** binary; on Linux it cannot exec.
2. The script's rebuild guard is `if [[ ! -x "$WI_BIN" ]]` (line 35). The macOS binary **is** `+x`, so the guard never fires → the gate neither rebuilds from source nor falls back to the committed `bin/workable-items-linux` that sits right beside it. Under `set -euo pipefail`, `validate` (line 40, un-guarded) aborts the whole gate.
Net: **`CM-WORKABLE-ITEMS-SYNC` cannot pass on a Linux gate host as written.** And even after fixing exec (e.g. pointing at `bin/workable-items-linux` or rebuilding), the gate would **still FAIL** because `validate` exits 1 on the 2 §11.4.148-D3 violations above.

### Integrity caveat (caused + remediated by this audit)
Running `validate`/`diff` opened the DB read-write; SQLite checkpointed its WAL into the main file, growing it **229376 → 356352 bytes** (working tree was clean = HEAD blob at session start). Restored byte-identical via `git cat-file blob :docs/workable_items.db > docs/workable_items.db`; post-restore `git hash-object` = `10366691…` = committed blob; `git status --porcelain docs/workable_items.db` is empty. (An `index.lock` from a concurrent git process on this shared host was present and gone by retry — not touched.) **Read-only constraint honored; the committed DB is unchanged.**

**Task 1 verdict: DB tracked ✓; md↔DB round-trip in sync ✓ (no drift); but `validate` FAILs on 2 Operator-blocked §11.4.148-D3 invariant violations (LVA-036, LVA-5), and the gate script is currently non-runnable on a Linux host (macOS-only committed binary + non-firing rebuild guard).**

---

## TASK 2 — LVA-008 upstream minimal-repro audit (read-only)

**Location correction:** the prompt-guessed path `docs/issues/upstream/lva-008-androidx-navigation/` does **NOT** exist on the master working tree. The current, consolidated, filing-ready package lives at **`docs/lva008-upstream-repro/`**:
- `README.md` — filing-ready bug report (filing target: issuetracker.google.com component **409828**).
- `analysis.md` — in-depth root-cause + 8-row falsified-fix ledger.
- `MinimalRepro.kt` — standalone, compile-correct (generic androidx-navigation only), `package com.example.navrepro`.
- `README.{html,pdf}`, `analysis.{html,pdf}` siblings.
- Incidents: `.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json` and `…/2026-06-30-keystone-offmain-nav-and-lva008.json`.
The README documents this is a **consolidation** of a prior off-master package (commit `71bee48c`, the guessed path) held back for tag-time docs-sync — not present on master.

### Completeness — HIGH (filing-ready by its own stated status)
Present and clear:
- **Repro steps:** ✓ Developer options → "Don't keep activities" → launch → tap "Open search_input" → Home/rotate → crash at destroy (README + MinimalRepro.kt KDoc).
- **Affected versions tested:** ✓ navigation-compose **2.9.1 AND 2.9.8** (latest stable, 2026-04-22), **unfixed on 2.10.0-alpha04/alpha05** (2026-05-19); lifecycle 2.9.1 is what throws; Compose BOM 2025.06.01; Kotlin 2.1.0 / AGP 8.6.1 / compileSdk-targetSdk 35 / minSdk 21; device Genymotion Pixel 9 API 35 arm64 + containerized x86_64 API-34.
- **Exact stack:** ✓ verbatim `IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED', but was 'INITIALIZED'` at `LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt:92)`, rethrown as `RuntimeException: Unable to destroy activity`.
- **Root cause:** ✓ `effectiveState = min(host, max)`; INITIALIZED(1) has no downward event → nested inner entry stranded INITIALIZED at Activity destroy → host observer attempts INITIALIZED→DESTROYED → rejected. Tied to umbrella **b/244910446** / sibling **b/178029606**.
- **Expected vs actual:** ✓ explicit.
- **Falsification ledger:** ✓ **8 device-falsified candidates** (nav bump 2.9.8 / 2.10-alpha; TestRule/try-catch proven structurally impossible; move to outer host — crash *moved* but still fired; atomic-replace; ON_STOP force-advance via public currentBackStack; Activity-scoped LocalLifecycleOwner; launchSingleTop) — each with `.lava-ci-evidence/` paths.
- **Candidate-dup list + the ask:** ✓ b/244910446, b/178029606, accompanist#1487, compose-samples#664; 3-point ask (confirm/fix/link-dup-and-target-version).

### Gaps / risks that could weaken or block the Google filing (none are missing mandatory fields)
1. **Minimal-repro non-determinism (primary filing risk).** `MinimalRepro.kt`'s own header admits whether the inner `search_input` entry is INITIALIZED vs CREATED at the destroy instant "depends on the nested controller's transition/restore timing." A triager who runs it once may not reproduce → risk of close-as-"cannot reproduce." Disclosed honestly, but it is the weakest link; should be paired with the production JUnit XML (3× ISE/teardown) it cites.
2. **Field-confirmation tension (internal contradiction a triager could probe).** README + analysis assert *"Crashlytics confirms it live in the field: FATAL, 4 events / 4 users on 1.3.11-1075."* BUT **both** incident JSONs hedge *"UNCONFIRMED whether real users hit it"*, and the 2026-06-30 keystone incident classifies LVA-008 `is_product_defect: false` / a **TEST-HARNESS artifact** ("UNCONFIRMED in field beyond the prior Crashlytics observation"). The "4 events/4 users" figure is sourced from `docs/CONTINUATION.md` (a prior-tag observation), not a fresh Crashlytics export attached to the filing. The report's strongest external claim (field-FATAL) is the one the internal forensic record is least certain about.
3. **Dup-vs-new is unresolved by the filer.** The report asks Google to confirm whether this is a dup of b/244910446 and to name the target-fix version — i.e. it may be merged/closed as a dup of the umbrella. Normal "ask", but means acceptance isn't guaranteed.
4. **Attachable external evidence is thin.** README flags its `.lava-ci-evidence/…` paths as "Lava-internal; sanitize/optional when filing." The effective external attachment set is `MinimalRepro.kt` + the pasted stack — no standalone reproducer repo link, screen-recording, or logcat is bundled for upload. Not a blocker, but a stronger filing would attach a self-contained repro project + one captured crash logcat.

**Task 2 verdict: filing-READY (operator files; Google sign-in required). All mandatory bug-report fields present and well-evidenced. The four items above are quality/credibility caveats — chiefly (1) the repro's self-disclosed non-determinism and (2) the field-FATAL-vs-internally-UNCONFIRMED tension — that a Google triager could push back on.**

---

## TASK 3 — §6.S CONTINUATION consistency

- **§0 "Last updated":** `docs/CONTINUATION.md` line 61 → `**Last updated:** 2026-06-30`. **CONFIRMED = 2026-06-30.**
- **Top entry vs HEAD:**
  - CONTINUATION top banner: *"🟢🚦 1.3.12-1078 DISTRIBUTE-PREPARED (Phase 5), NOT DISTRIBUTED — autonomous-QA matrix relaunching on nezha (2026-06-30)"* + off-main nav guard + LVA-008 + nezha egress.
  - HEAD `613083d1` (2026-06-30), subject *"…(LVA-070) + 1078 distribute-prep"*.
  - `app/build.gradle.kts`: `versionCode = 1078`, `versionName = "1.3.12"` → exactly matches the banner's `1.3.12-1078`.
- **Tracked-state cleanliness:** `docs/CONTINUATION.md` and `app/build.gradle.kts` are both committed at HEAD (not dirty).
- **Working-tree drift:** 11 uncommitted entries, **all under `.lava-ci-evidence/`** (stress-chaos `jackett/*` modified; autonomous-qa `goapi/summary.md` modified; new `archiveorg-1080p/`, `lava-api-go-verify/`, `push-readiness-gates/`, and this `workable-items-lva008-audit/` dirs). These are **evidence-only**, consistent with CONTINUATION's own "matrix relaunching / in-progress" claim; none contradict any CONTINUATION assertion. No app source, no governance doc, no DB among them.

**Task 3 verdict: CONSISTENT — NO DRIFT.** §0 "Last updated" (2026-06-30) = HEAD commit date; the top banner's `1.3.12-1078 distribute-prep / autonomous-QA / LVA-008` matches HEAD subject and the live `versionCode 1078 / versionName 1.3.12`. The only uncommitted changes are in-flight `.lava-ci-evidence/` evidence files, which CONTINUATION already describes as in progress.

---

## Overall

| Task | Verdict |
|---|---|
| 1 — workable-items DB | DB **tracked ✓**; md↔DB **in sync ✓ (no drift)**; `validate` **FAIL** (2 §11.4.148-D3 Operator-blocked violations: LVA-036, LVA-5); gate script **non-runnable on Linux** (committed macOS binary + non-firing rebuild guard) — NEW finding. |
| 2 — LVA-008 upstream repro | **Filing-ready**, all mandatory fields present + well-evidenced; 4 quality caveats (repro non-determinism; field-FATAL vs internally-UNCONFIRMED tension; dup-unresolved; thin attachable external evidence). Materials at `docs/lva008-upstream-repro/` (not the guessed path). |
| 3 — CONTINUATION | **Consistent, no drift** (Last updated 2026-06-30 = HEAD; banner 1.3.12-1078 = versionCode 1078 = HEAD subject). |

No app source modified; Android Gradle / emulator / `:8443` untouched; committed `workable_items.db` restored byte-identical.
