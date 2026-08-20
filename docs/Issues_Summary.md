# Issues_Summary

Open workable items (current_location = Issues), regenerated from the SQLite single-source-of-truth (§11.4.12).

## Counts by Type × Status

| Type | Status | Count |
|---|---|---|
| Bug | In progress | 2 |
| Bug | Operator-blocked | 2 |
| Bug | Queued | 1 |
| Task | Queued | 1 |
| **TOTAL** | | **6** |

## Items

| ATM ID | Type | Status | Severity | Description |
|---|---|---|---|---|
| LVA-008 | Bug | Operator-blocked | P1 | Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json |
| LVA-019 | Task | Queued | — | Task P1 process: coverage ledger has partial/gap overlap and lacks per-release ledger snapshots. Normalize the registry and add release-attestation ledgers. |
| LVA-088 | Bug | In progress | P2 | QA video frames 0012/0015. 1076 investigation: NOT a §6.R hardcoding violation (config-driven preset). 'On this network' label for a cloud/remote preset still worth confirming. Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #7. |
| LVA-089 | Bug | In progress | P2 | QA video frames 0012/0015. NEW UX. CODE-FIX in 1076 (discovered-API friendly name). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #8. |
| LVA-091 | Bug | Queued | P3 | QA video frames 0001/0005: single Lava icon launched; co-mingling NOT visually confirmed. Needs on-device package check (applicationIdSuffix .dev + launcher label). OPEN/UNCONFIRMED. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #10. |
| LVA-5 | Bug | Operator-blocked | P0 | 67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json |
