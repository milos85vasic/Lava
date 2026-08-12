## LVA-3 — Adopt HelixConstitution §11.4.79–106 (pin 53 commits behind)

**Status:** In progress
**Type:** Task
**Severity:** P1

Pin 208e2c8 is 53 commits behind origin/main 883ccc1. Highest-impact new clauses: §11.4.93/95/106 (workable-items SQLite DB tracked in git + md to DB sync engine), §11.4.79 (own-org submodules in CodeGraph), §11.4.85 (stress/chaos), §11.4.98, §11.4.102. Pin-bump is operator-gated; decision owed on §6.AD.3 Path B vs SQLite DB. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-5 — Rotate Firebase CI token (printed to session transcript)

**Status:** Operator-blocked
**Type:** Bug
**Operator-Block-Details:** WHAT: RuTracker CI Firebase token rotation WHY: Agent cannot rotate credentials; only the operator can run firebase logout / firebase login:ci UNBLOCK: Operator rotates the Firebase CI token (no token value ever committed, §11.4.10): [A] run "firebase logout" then "firebase login:ci" and write the new token to LAVA_FIREBASE_TOKEN in the gitignored .env (recommended); [B] mint a fresh token via "firebase login:ci --no-localhost" in a clean shell and update LAVA_FIREBASE_TOKEN in .env; [C] rotate via the Firebase console (Project Settings -> Service accounts) and update .env. Unblock signal: a Firebase distribute run authenticates with the new token and the previously-leaked token no longer authenticates. WHO: Operator
**Severity:** P0

67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json

## LVA-008 — C11 search_input NavBackStackEntry teardown crash (nested-NavHost lifecycle)

**Status:** Operator-blocked
**Type:** Bug
**Operator-Block-Details:** WHAT: All 8 app-level candidate fixes are implemented + device-falsified (containerized-KVM gate, real JUnit XML evidence): inner-NavHost Activity-scoped LifecycleOwner, move-to-outer-NavHost, ON_STOP force-pop (NavTeardownGuard), nav-compose 2.9.1->2.9.8->2.10.0-alpha bump, LenientTeardownRule (uncatchable — process-fatal, not JUnit-catchable), atomic popUpTo replace, launchSingleTop dedupe, and full nested-NavHost-architecture collapse to single Activity-hosted multi-back-stack NavHost. Every attempt reproduces the identical IllegalStateException on C06+C11. Confirmed upstream androidx-navigation defect (b/244910446 family), no fixed version through 2.10.0-alpha04. WHY: Systematic-debugging Iron Law: 3+ failed fixes each revealing the same underlying defect in a different place = architectural problem, not grounds for a 9th ad-hoc app-level attempt. Minimal repro already authored and filing-ready at docs/lva008-upstream-repro/ (README.md, MinimalRepro.kt, analysis.md). UNBLOCK: AndroidX ships a navigation-compose release that fixes the nested-NavHost teardown ordering (b/244910446 family), OR a Google/AndroidX engineer confirms a workaround this project hasn't tried. WHO: docs/lva008-upstream-repro/README.md (repro package ready to file); .lava-ci-evidence/sixth-law-incidents/2026-06-30-keystone-offmain-nav-and-lva008.json (latest incident record)
**Severity:** P1
**Created-By:** AI

Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json

## LVA-079 — Video #3 — search-input chips vs results-filter chips disagree + results chip set non-deterministic run-to-run

**Status:** In progress
**Type:** Bug
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

QA video 2026-06-25 (frames 0040 vs 0060 vs 0125): input chip bar and results filter chips show different provider sets, and the results chip set CHANGES between two identical queries. 1076 fixed the INPUT chips (observeAll filtered+sorted) but the input-vs-RESULTS divergence + run-to-run instability is distinct and still open. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md issue #3.

## LVA-081 — Fetch/pull/merge latest from all submodules + build-verify (operator directive 2026-06-26)

**Status:** In progress
**Type:** Task
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

Operator directive: fetch+pull+merge latest codebase from all submodules. Frozen-by-default overridden for this cycle. Must build-verify after each bump; revert+report any submodule whose bump breaks the build.

## LVA-082 — Crashlytics read NOT available via Firebase CLI — only symbols/mappingfile upload; no issues:list; bq absent

**Status:** Queued
**Type:** Task
**Severity:** P3
**Created-By:** AI

Operator asked to use Firebase CLI to pull Crashlytics. Verified Firebase CLI 14.17.0 exposes only crashlytics:symbols:upload + mappingfile:* (NO issue/non-fatal read). bq CLI absent. Crashlytics dashboard read requires console or a BigQuery export not configured. Fallback: in-repo §6.AC telemetry + known crash tickets; operator to paste console items for full triage. §11.4.6 honest record.

## LVA-087 — Video #6 — Welcome claims '4 providers available' but picker lists ~12

**Status:** In progress
**Type:** Bug
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0007/0010/0015 vs 0020-0030. NEW. CODE-FIX in 1076 (#6 count bound to real descriptor list). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #6.

## LVA-088 — Video #7 — 'Choose your API' shows 'lava.app:7777' preset + mislabeled 'On this network'

**Status:** In progress
**Type:** Bug
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0012/0015. 1076 investigation: NOT a §6.R hardcoding violation (config-driven preset). 'On this network' label for a cloud/remote preset still worth confirming. Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #7.

## LVA-089 — Video #8 — mDNS-discovered API shows raw IP 192.168.0.107:8443 with no friendly name

**Status:** In progress
**Type:** Bug
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0012/0015. NEW UX. CODE-FIX in 1076 (discovered-API friendly name). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #8.

## LVA-090 — Video #9 — Onboarding 'Select all' silently enables auth-requiring (Captcha/Form Login) providers

**Status:** In progress
**Type:** Bug
**Severity:** P3
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0020-0025. NEW UX, contributes to #1. CODE-FIX in 1076 (#9 select-all handling). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #9.

## LVA-091 — Video #10 — App-ID co-mingling (debug .dev + release both labeled 'Lava') — UNCONFIRMED in video

**Status:** Queued
**Type:** Bug
**Severity:** P3
**Created-By:** AI

QA video frames 0001/0005: single Lava icon launched; co-mingling NOT visually confirmed. Needs on-device package check (applicationIdSuffix .dev + launcher label). OPEN/UNCONFIRMED. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #10.

## LVA-019 — Coverage ledger partial/gap overlap and missing per-release ledgers

**Status:** Queued
**Type:** Task

Task P1 process: coverage ledger has partial/gap overlap and lacks per-release ledger snapshots. Normalize the registry and add release-attestation ledgers.

## LVA-095 — on-device API keyless mDNS endpoint does not read/persist local api-app key

**Status:** Queued
**Type:** Bug
**Severity:** P1
**Created-By:** AI

OnboardingViewModelDynamicProvidersTest > 'selecting a keyless on-device API reads and persists the local api key for search auth' FAILS: AssertionError - the local api-app key MUST be read + persisted onto the keyless mDNS-selected endpoint (was GoApi(host=localhost.localdomain, port=42873, platform=null, storage=null, key=null)). Discovered 2026-08-12 while running the combined test suite after merging 4 parallel LVA-085/086/087/093/094 fixes - confirmed genuinely pre-existing and unrelated to any of those 4 changes (git log shows the test file's last touch was commit b3cb6de2, predating this session; none of the 4 merged branches touch onboarding/api-key/mDNS code per git diff --name-only). Not yet root-caused - source: real gradle test run, feature/onboarding/build/test-results/testDebugUnitTest/.

