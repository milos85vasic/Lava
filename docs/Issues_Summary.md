# Issues_Summary

Open workable items (current_location = Issues), regenerated from the SQLite single-source-of-truth (§11.4.12).

## Counts by Type × Status

| Type | Status | Count |
|---|---|---|
| Bug | In progress | 5 |
| Bug | Operator-blocked | 2 |
| Bug | Queued | 2 |
| Task | Queued | 2 |
| **TOTAL** | | **11** |

## Items

| ATM ID | Type | Status | Severity | Description |
|---|---|---|---|---|
| LVA-008 | Bug | Operator-blocked | P1 | Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json |
| LVA-019 | Task | Queued | — | Task P1 process: coverage ledger has partial/gap overlap and lacks per-release ledger snapshots. Normalize the registry and add release-attestation ledgers. |
| LVA-079 | Bug | In progress | P1 | QA video 2026-06-25 (frames 0040 vs 0060 vs 0125): input chip bar and results filter chips show different provider sets, and the results chip set CHANGES between two identical queries. 1076 fixed the INPUT chips (observeAll filtered+sorted) but the input-vs-RESULTS divergence + run-to-run instability is distinct and still open. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md issue #3. |
| LVA-082 | Task | Queued | P3 | Operator asked to use Firebase CLI to pull Crashlytics. Verified Firebase CLI 14.17.0 exposes only crashlytics:symbols:upload + mappingfile:* (NO issue/non-fatal read). bq CLI absent. Crashlytics dashboard read requires console or a BigQuery export not configured. Fallback: in-repo §6.AC telemetry + known crash tickets; operator to paste console items for full triage. §11.4.6 honest record. |
| LVA-087 | Bug | In progress | P2 | QA video frames 0007/0010/0015 vs 0020-0030. NEW. CODE-FIX in 1076 (#6 count bound to real descriptor list). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #6. |
| LVA-088 | Bug | In progress | P2 | QA video frames 0012/0015. 1076 investigation: NOT a §6.R hardcoding violation (config-driven preset). 'On this network' label for a cloud/remote preset still worth confirming. Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #7. |
| LVA-089 | Bug | In progress | P2 | QA video frames 0012/0015. NEW UX. CODE-FIX in 1076 (discovered-API friendly name). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #8. |
| LVA-090 | Bug | In progress | P3 | QA video frames 0020-0025. NEW UX, contributes to #1. CODE-FIX in 1076 (#9 select-all handling). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #9. |
| LVA-091 | Bug | Queued | P3 | QA video frames 0001/0005: single Lava icon launched; co-mingling NOT visually confirmed. Needs on-device package check (applicationIdSuffix .dev + launcher label). OPEN/UNCONFIRMED. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #10. |
| LVA-095 | Bug | Queued | P1 | OnboardingViewModelDynamicProvidersTest > 'selecting a keyless on-device API reads and persists the local api key for search auth' FAILS: AssertionError - the local api-app key MUST be read + persisted onto the keyless mDNS-selected endpoint (was GoApi(host=localhost.localdomain, port=42873, platform=null, storage=null, key=null)). Discovered 2026-08-12 while running the combined test suite after merging 4 parallel LVA-085/086/087/093/094 fixes - confirmed genuinely pre-existing and unrelated to any of those 4 changes (git log shows the test file's last touch was commit b3cb6de2, predating this session; none of the 4 merged branches touch onboarding/api-key/mDNS code per git diff --name-only). Not yet root-caused - source: real gradle test run, feature/onboarding/build/test-results/testDebugUnitTest/. |
| LVA-5 | Bug | Operator-blocked | P0 | 67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json |
