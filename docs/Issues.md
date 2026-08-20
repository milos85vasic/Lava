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

