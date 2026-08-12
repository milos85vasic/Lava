# Production-Readiness Audit + Remediation Plan — 2026-08-09

**Status:** LIVE DOCUMENT — being updated as background verification (constitutional
sweep, containerized device gate) completes. Read `docs/CONTINUATION.md` §0 for the
current pointer; this file is the point-by-point punch list behind it.

**Why this exists.** The operator asked for a full anti-bluff audit against the
constitution, an inventory of every open workable item, identification of every
gap/inconsistency/danger zone, and a systematic plan to close each one with real,
executed, non-bluff evidence. This document is that plan. Every claim below is
either (a) independently verified against the actual repo/DB state in this session
(not trusted from a subagent's summary alone), or (b) explicitly marked PENDING with
what will confirm it.

**Session interruption note (forensic, §6.M).** The Claude Code process was killed
mid-session twice (once ~15:55, once ~18:5x) while long-running verification agents
were in flight. Both times `uptime` confirmed continuous host uptime — these were
process-level interruptions, not host power events. No git-tracked work was lost;
one in-flight device-gate attempt had to be redone from a very early stage (it had
only logged its runner selection before being killed). Lesson applied: long-running
verification (the constitutional sweep, the device-gate Challenge run) now executes
as direct background shell commands from the main session rather than nested inside
subagents, which is more resilient to this class of interruption.

---

## 1. Executive summary

| Category | Count | Detail |
|---|---|---|
| Confirmed-fixed this session | 3 | credential leak (§6.H/§6.AC), `markdown-export-sync` gate, `coverage-ledger` gate |
| Confirmed already-fixed-in-source, pending device verification | 8 | LVA-083, LVA-084, LVA-085, LVA-086, LVA-087, LVA-088, LVA-089, LVA-090 |
| Genuinely unconfirmed, needs device/operator input | 1 | LVA-091 (app-ID co-mingling) |
| Doc-staleness corrections applied | 4 | submodule pin table, LVA-6 status, LVA-7 status, Firebase-token-resolution claim |
| Structurally operator-gated (no agent can close these) | 4 | Firebase CI token rotation, 2× GitLab mirror push, LVA-008 upstream Android bug |
| Verification runs in flight | 2 | full 54-gate constitutional sweep; 6-Challenge containerized device gate |

**Bottom line so far:** nothing found this session indicates the product is
fundamentally broken — the P0 "search is completely unusable" reports (LVA-083/084)
already have real root-caused fixes in source from a 2026-06-25/06-26 cycle; they
were simply never verified on a device before being left as open tickets. The
constitutional-gate debt is almost entirely mechanical staleness (regenerate a
ledger, regenerate doc exports), not missing engineering. The remaining real risk
concentrations are: (1) whether the device-verifiable fixes actually pass on a real
emulator (verification in flight), (2) two credential/mirror items that require the
operator's hands, and (3) one confirmed upstream Android framework bug (LVA-008)
that Lava's own code cannot fix.

---

## 2. Fixed and independently verified this session

### 2.1 Credential leak — `OnboardingViewModel.kt:659` (§6.H / §6.AC)
`LoginResult` is a Kotlin `data class` with a raw `sessionToken: String?` field and
no custom `toString()`; `logger.d { "...result=$loginResult" }` therefore wrote the
session token to logcat on every login. **Verified by reading the class definition
directly** (not trusted from any agent claim). Fixed: log now emits only
`state`/`hasSessionToken`/`hasCaptcha`. Not yet committed (bundled into the
consolidated commit — see §6).

### 2.2 `markdown-export-sync` gate
Was FAILING (8 stale HTML/PDF exports: `CHANGELOG.md`, `docs/CONTINUATION.md`,
`docs/Fixed.md`, `docs/Issues.md`). Regenerated; **re-ran the check script directly
myself**: `checked 265 in-scope .md file(s); 0 problem(s)`. Confirmed PASS.

### 2.3 `coverage-ledger` gate (§11.4.25)
Was stale (last generated 2026-07-26, months of module changes since). Regenerated
via `scripts/generate-coverage-ledger.sh` (66 rows: 49 covered / 9 partial / 8 gap)
and **re-ran `scripts/check-coverage-ledger.sh --strict` directly myself**: exit 0,
"ledger is well-formed, complete, and fresh". The 8 remaining `gap` rows
(`core/analytics-firebase`, `core/apiengine`, `core/applink`, and 5 HelixQA-sibling
submodules: `doc_processor`/`llm_orchestrator`/`llm_provider`/`llms_verifier`/
`vision_engine`) are a real, separately-tracked item — see §4.3 (LVA-019).

### 2.4 Documentation staleness in `docs/CONTINUATION.md`
- §3 submodule pin table: 22 of 24 rows were stale (some by 2+ months); regenerated
  from a live `git submodule status` + per-submodule fetch sweep.
- Flagged (not silently fixed): 2 real GitHub↔GitLab mirror mismatches
  (`panoptic`, `tracker_sdk` — GitLab is a real ancestor of GitHub); fixing requires
  a `git push` to a shared remote, which needs operator sign-off (see §5.2).
- `LVA-6` and `LVA-7`: doc said "OWED"/"IN PROGRESS"; DB ground truth
  (`docs/workable_items.db`) says both `Completed (→ Fixed.md)`. Corrected.
- Firebase CI token: doc claimed "RESOLVED 2026-05-31"; DB (`LVA-5`) says
  `Operator-blocked` as of 2026-06-30, and the incident file the doc cites doesn't
  exist in the tree. Marked honestly UNCONFIRMED rather than picking a side — see
  §5.1.

---

## 3. Confirmed already-fixed-in-source, pending only device verification

All 8 below trace to the same operator QA-video cluster
(`.lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md`). For each,
I (or a dispatched agent whose specific file:line claims I spot-verified — see
§3.9) read the actual production code and the actual reproduce-first Challenge Test
before accepting the classification. None required a new code fix; all trace to a
real prior fix commit.

| Ticket | Symptom | Fix location | Covering Challenge |
|---|---|---|---|
| LVA-083 (P0) | Search returns zero results / "Something went wrong" | `SearchInputViewModel.kt:104-109` — explicit onboarded-provider set, never the old `null`-means-all sentinel | `Challenge58SearchReturnsResultsTest` |
| LVA-084 (P0) | Onboarded provider (YTS) not the one Search uses | same fix as LVA-083 (`observeAll().filter { searchEnabled && isEnabled }`) | `Challenge59SearchUsesOnboardedProvidersTest` |
| LVA-085 (P1) | Provider chip labels raw/lowercased | `ProviderCatalogRepository.kt:217` `friendlyDisplayName()` humanizes blank/id-equal display names at the adapter boundary | `Challenge61ResultsChipsShowDisplayNamesTest` |
| LVA-086 (P1) | No empty/loading state, looked hung | `SearchResultScreen.kt:347-358` renders a loading spinner while streaming with zero items; `handleStreamEnd()` always terminates into Content/Empty/Error | `Challenge62SearchEmptyStateTest` |
| LVA-087 (P2) | Welcome says "4 providers", picker shows ~12 | `OnboardingViewModel.kt:472` sets `welcomeProviderCount = null` when `apiSelectionEnabled` (true in production), so the stale exact count never renders | `Challenge63WelcomeCountMatchesPickerTest` |
| LVA-088 (P2) | Cloud API preset mislabeled "On this network" | `ApiSelectionStep.kt:254,376` gives cloud-default rows an explicit "Cloud / remote server" subtitle | (covered by onboarding Challenges) |
| LVA-089 (P2) | mDNS API shown as raw IP, no name | `ApiSelectionStep.kt:162,329,385-388` resolves the mDNS instance name from a host:port-keyed map when present | `Challenge65MdnsShowsFriendlyNameTest` |
| LVA-090 (P3) | "Select all" silently enabled auth-requiring providers | `OnboardingViewModel.kt:592-622` `onToggleAllProviders()` only auto-selects `requiresNoCredentials()` providers | `Challenge66SelectAllDoesNotEnableAuthProvidersTest` |

### 3.9 Independent verification note
I personally read `SearchInputViewModel.kt` in full (§3 LVA-083/084) and
`Challenge58SearchReturnsResultsTest.kt` in full (confirmed it is a genuine,
non-bluff test: real MainActivity→ViewModel→SDK→OkHttp path, MockWebServer only at
the socket boundary, primary assertion on a rendered result row, documented
reproduce-first mutation protocol). I spot-checked LVA-090's `onToggleAllProviders`
directly against the reporting agent's claim and found it accurate, including the
exact commit (`a300b3b0`). The remaining LVA-085/086/087/088/089 citations are
trusted from the dispatched agent's report on the strength of that spot-check plus
the specificity of every citation (exact file:line, exact function names) — a
standard I judge acceptable but weaker than full independent re-reading of all 6.

### 3.10 One unresolved side-observation (not a ticket, logged here so it isn't lost)
`ProvidersStep.kt:74`'s "Select all" checkbox label computes `allSelected` from
*all* providers, while the ViewModel's toggle-direction decision only considers
no-credential-reachable providers. When credential-required providers exist and
stay unselected, the checkbox may never flip to "Deselect all" even once every
reachable provider is selected. Does NOT reproduce LVA-090's reported symptom
(auth providers being silently enabled) — flagged as a possible follow-up, not
fixed this session, not blocking.

---

## 4. Genuinely open, needs more than what's in this repo

### 4.1 LVA-091 (P3) — App-ID co-mingling, UNCONFIRMED
Source inspection across 3 independent layers (`applicationId`/`applicationIdSuffix`
in `app/build.gradle.kts`, per-build-type `app_name` string override, per-build-type
launcher icon override) shows strong evidence debug and release ARE already
distinguishable (different package id, different label, different icon color). The
ticket's own title says UNCONFIRMED. **Closure requires an on-device check**
(`adb shell pm list packages | grep lava` + a visual launcher check) — folded into
the device-gate run in §5.3 if time permits; otherwise this is the one item where
"needs a real device, not just source reading" is a legitimate, non-bluff blocker.

### 4.2 LVA-008 (P1) — nav-teardown crash — confirmed upstream defect, not Lava's
8 client-side candidate fixes have been device-falsified over prior cycles; a
minimal repro is authored for upstream androidx-navigation. This is not something
any amount of further Lava-side work will close — it needs either an
androidx-navigation fix to land upstream, or an operator decision to ship with the
existing `LenientTeardownRule` test-harness workaround (which only suppresses the
teardown-time exception in tests, not in the shipped app) permanently. **Operator
decision owed**, not additional engineering.

### 4.3 LVA-019 (P1) — coverage-ledger gap rows
8 modules show `gap` status in the freshly-regenerated ledger: `core/analytics-firebase`,
`core/apiengine`, `core/applink`, plus 5 HelixQA-sibling Go submodules
(`doc_processor`, `llm_orchestrator`, `llm_provider`, `llms_verifier`,
`vision_engine`). The gate itself passes (gaps are tracked, not blocking), but per
the "no work forgotten" mandate these are real coverage holes worth a follow-up
session — not addressed here because closing 8 modules' test coverage properly is
multi-session scope, not a mechanical fix.

### 4.4 LVA-013 (P0 per body) — missing §6.Z device evidence for already-shipped 1080 / api-app 24
Folded into the device-gate work in §5.3 as a secondary objective once the 1081
Challenge set completes, resources permitting.

---

## 5. Structurally operator-gated (cannot be closed by an agent, full stop)

### 5.1 Firebase CI token — contradictory status, needs operator confirmation
One commit claims rotation happened 2026-05-31; the workable-items DB says
`LVA-5` is still `Operator-blocked` as of 2026-06-30; `.env` was touched
2026-07-03 (after both dates) so a rotation may have happened without the tracker
being updated. **I cannot determine which is true from file evidence, and only the
operator can actually rotate `LAVA_FIREBASE_TOKEN`** (the ticket's own unblock
instructions say so explicitly). Action needed: confirm current token validity, or
rotate it (`firebase logout && firebase login:ci`), then close `LVA-5` in the DB.

### 5.2 Two GitHub↔GitLab mirror mismatches
`panoptic` and `submodules/tracker_sdk` both have a GitLab tip that is a real,
unreconciled ancestor of the GitHub tip. Fixing this is a `git push gitlab` per
submodule — a shared-remote action I won't take without explicit sign-off per this
session's operating constraints. `panoptic` is additionally missing
`install_upstreams.sh` (§11.4.36 gap) — I can add that script without pushing
anything if that's useful groundwork; say so if wanted.

### 5.3 Real-device verification of the 8 already-fixed tickets + the crash regression
This is IN PROGRESS as a background containerized-KVM Challenge run (see §6) — not
operator-gated, just genuinely takes real wall-clock time (cold emulator boot ×6
test classes). Will update this section with PASS/FAIL evidence per test the moment
it completes.

---

## 6. In-flight verification (background, main-thread, resilient to process kills)

1. **Full 54-gate constitutional sweep** (`scripts/verify-all-constitution-rules.sh`)
   — re-running from a clean state now that both known failures (§2.2, §2.3) are
   fixed. Expect a materially better pass count than the last completed run
   (53/54, single ledger failure, recorded 2026-07-26).
2. **Containerized device gate**, 6 Challenge Tests against the current build on a
   real containerized-KVM emulator (this Linux host, confirmed `/dev/kvm` +
   `podman` available — the exact gate path prior macOS-hosted sessions in this
   project's history could never reach):
   `Challenge00CrashSurvivalTest` (cold-start canary) →
   `Challenge71CategorySelectionDialogBoundedHeightTest` (closes the 1.3.15-1081
   Crashlytics regression) →
   `Challenge58SearchReturnsResultsTest` (closes LVA-083) →
   `Challenge59SearchUsesOnboardedProvidersTest` (closes LVA-084) →
   `Challenge60InputResultsChipsAgreeTest` →
   `Challenge62SearchEmptyStateTest` (closes LVA-086).
   Evidence lands incrementally at
   `.lava-ci-evidence/lva014-c00-c71-c58-c59-device-gate/` as each test finishes,
   specifically so a third process interruption (if it happens) doesn't lose
   already-completed results.

---

## 7. What "done" looks like for this cycle

Once §6's two runs complete:
1. Update this document + `docs/CONTINUATION.md` with the real PASS/FAIL evidence
   (not before — no claiming green ahead of the actual run per §6.J/§6.Z).
2. If all 6 Challenges pass: LVA-083, LVA-084, LVA-086 close for real (device-verified,
   not just source-verified); LVA-085/087/088/089/090 stay source-verified-only
   unless their own Challenges also got a slot; the 1.3.15-1081 Crashlytics
   regression closes.
3. If anything fails: that's a genuine finding, not a setback to hide — root-cause
   it via the same systematic-debugging process used throughout this session
   before touching the fix.
4. Bundle every working-tree change from this session into one well-documented,
   Bluff-Audit-stamped commit (credential-fix, CONTINUATION corrections,
   coverage-ledger, doc exports) — not before the device-gate evidence exists,
   since the commit should carry real evidence paths, not promises.
5. Present the operator with the short, genuinely-irreducible punch list from §5 —
   token rotation, 2 mirror pushes, LVA-008 upstream decision, LVA-091 device
   check — as the honest remainder between "everything an agent can verify is
   verified" and "100% shipped."
