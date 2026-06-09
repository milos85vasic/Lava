## LVA-3 — Adopt HelixConstitution §11.4.79–106 (pin 53 commits behind)

**Status:** In progress
**Type:** Task
**Severity:** P1

Pin 208e2c8 is 53 commits behind origin/main 883ccc1. Highest-impact new clauses: §11.4.93/95/106 (workable-items SQLite DB tracked in git + md to DB sync engine), §11.4.79 (own-org submodules in CodeGraph), §11.4.85 (stress/chaos), §11.4.98, §11.4.102. Pin-bump is operator-gated; decision owed on §6.AD.3 Path B vs SQLite DB. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-4 — LVA workable-items ticket system (SQLite DB + Go CLI + md/HTML/PDF/DOCX export)

**Status:** In progress
**Type:** Feature
**Severity:** P1

HelixConstitution §11.4.93/95/106 materialization. Go CLI (modernc.org/sqlite, no CGO) with init/add/update/close/reopen/gen/verify/import/export. Operator directive §6.L 68th invocation, key prefix LVA. Superseded by migration to the canonical constitution binary (docs/tickets/MIGRATION-TO-CANONICAL.md). **Source:** operator-report — docs/tickets/DESIGN.md

## LVA-5 — Rotate Firebase CI token (printed to session transcript)

**Status:** Operator-blocked
**Type:** Bug
**Severity:** P0

67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json

## LVA-6 — §11.4.79 reconcile codegraph index policy (own-org submodules IN index)

**Status:** Queued
**Type:** Task
**Severity:** P2

§11.4.79 (new) requires own-org submodules IN the codegraph index; Lava currently EXCLUDES submodules/ per docs/CODEGRAPH.md + 63rd-cycle policy. Reconcile .codegraph config + docs when pin is bumped. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-7 — §11.4.85 stress + chaos test scaffold

**Status:** Queued
**Type:** Task
**Severity:** P2

§11.4.85 (new universal anchor) mandates a stress + chaos test class. Lava has no chaos/stress suite today. Assess + scaffold when pin is bumped. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-008 — C11 search_input NavBackStackEntry teardown crash (nested-NavHost lifecycle)

**Status:** Queued
**Type:** Bug
**Severity:** P1
**Created-By:** AI

Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json

## LVA-009 — HelixQA VM QA launch-dispatch exit 127 (vision works, action-dispatch command-not-found)

**Status:** Queued
**Type:** Bug
**Severity:** P2
**Created-By:** AI

After the 3-layer claude vision bridge fix (helixqa aef46e5d), the HelixQA autonomous vision QA GENUINELY drives the Lava app on the Genymotion VM: it analyzes screenshots and decides actions (vision-screen-changed PASS; rationale 'Current screen is the Android home launcher … launching the Lava client app'). Remaining blocker: the 'launch' action dispatch returns 'exit status 127' (command not found) — visionnav step 2 dispatch 'launch digital.vasic.lava.client.dev'. The derived launch action is 'shell monkey -p digital.vasic.lava.client.dev 1'; the ADB actor appears to run it without the 'adb -s <serial>' prefix (treating 'shell' as a command) OR adb is not on the dispatch subprocess PATH. Fix in helixqa pkg actor/bridge ADB dispatch. Evidence: .lava-ci-evidence/helixqa/vm-qa-vision2-20260608T182251Z/ (4/4 reached vision+navigation, 1 ERROR + 3 FAILED on the launch-dispatch 127, NOT bridge errors anymore).

## LVA-010 — lava-api-go router mounts /v1/{provider} group WITHOUT provider middleware (handlers panic→500)

**Status:** Queued
**Type:** Bug
**Severity:** P2
**Created-By:** AI

internal/router/router.go Build() registers the /v1/:provider route group without the provider-resolution middleware, so those handlers panic-recover to 500 in production instead of resolving a provider. Surfaced by the new router_test.go (W4) which asserts resolution (non-404) — the routes resolve but to a 500, not a working provider dispatch. Confirm whether production wires the middleware elsewhere (real server bootstrap) or this is a real gap. Evidence: router_test.go note + internal/router/router.go.

## LVA-011 — TestEndpointsRepository.add Third-Law divergence (stores Rutracker; real impl no-ops it)

**Status:** Queued
**Type:** Task
**Severity:** P3
**Created-By:** AI

core/testing TestEndpointsRepository.add() stores an Endpoint.Rutracker and can raise a duplicate-conflict for it, but the real EndpointsRepositoryImpl.add() early-returns for Rutracker (if (endpoint is Endpoint.Rutracker) return) and never stores it. A future test asserting 'adding Rutracker is a no-op' would pass against the fake while exercising different behavior than production — a latent §Third-Law bluff-fake. Fix: add the Rutracker no-op branch + doc to the fake. Flagged by the W6 core/domain UseCase agent (2026-06-09).

## LVA-012 — core/testing TestVisited/Favorites/Bookmarks repositories are TODO-throw stub bluff-fakes

**Status:** Queued
**Type:** Task
**Severity:** P2
**Created-By:** AI

core/testing TestVisitedRepository, TestFavoritesRepository, TestBookmarksRepository have observe/contains/add/etc methods that are TODO('Not yet implemented') (throw). They are unusable for behavioral wiring — feature ViewModel tests (account, visited, rating) work around them with local in-memory fakes. Per the Third Law these shared fakes should be behaviorally-equivalent to the real repos so tests can use them directly. Flagged by the rating+visited VM-test agent (2026-06-09). Fix: implement the stubs as real in-memory fakes matching the real repo contracts + add equivalence tests.

## LVA-013 — TestEndpointsRepository.observeAll seeds [Rutracker] but real impl never emits Rutracker (deeper Third-Law divergence)

**Status:** Queued
**Type:** Task
**Severity:** P3
**Created-By:** AI

Beyond LVA-011 (add no-op, fixed): the fake's observeAll() seeds + emits [Endpoint.Rutracker] while the real EndpointsRepositoryImpl.observeAll() filterNot{it is Rutracker} + purges Rutracker every observe (never emits it). ~12 consumer tests (core/domain + core/testing) are written around the fake's [Rutracker] seed contract, so fixing the fake requires updating those tests in lockstep. Flagged by the LVA-011 agent (2026-06-09).

