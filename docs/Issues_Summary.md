# Issues_Summary

Open workable items (current_location = Issues), regenerated from the SQLite single-source-of-truth (§11.4.12).

## Counts by Type × Status

| Type | Status | Count |
|---|---|---|
| Bug | Operator-blocked | 2 |
| **TOTAL** | | **2** |

## Items

| ATM ID | Type | Status | Severity | Description |
|---|---|---|---|---|
| LVA-008 | Bug | Operator-blocked | P1 | Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json |
| LVA-5 | Bug | Operator-blocked | P0 | 67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json |
