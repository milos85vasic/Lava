## LVA-1 — Deflake CredentialsViewModelTest > select provider updates selectedProvider

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** feature/credentials/src/test/kotlin/lava/feature/credentials/CredentialsViewModelTest.kt
**Severity:** P1

67th-cycle full-suite flaky test (fixed-awaitState-count vs Room Flow .first() off the StandardTestDispatcher). Fixed in the 68th cycle (Commit 1) via bounded await-until-selectedProvider loop; falsifiability-rehearsed. **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-flaky-credentialsviewmodeltest.json

## LVA-2 — §6.X-debt darwin/arm64 emulator-acceleration sub-debt

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json
**Severity:** P1

Per-OS emulator acceleration (Containers c1871138 + 6aff7ea8): macOS gate runner is host-direct+HVF. PROVEN by C00 cold-start canary + full 37-class Challenge suite on Pixel_8/API35 (43 pass / 3 credential-skip / 0 fail). RESOLVED 2026-05-20. **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json

## LVA-009 — HelixQA VM QA launch-dispatch exit 127 (vision works, action-dispatch command-not-found)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** submodules/helixqa pin 639f7652 (launch verb builds launcher intent) + .lava-ci-evidence/helixqa/vm-qa-vision2-20260608T182251Z (vision drives app, exit-127 gone)
**Severity:** P2
**Created-By:** AI

After the 3-layer claude vision bridge fix (helixqa aef46e5d), the HelixQA autonomous vision QA GENUINELY drives the Lava app on the Genymotion VM: it analyzes screenshots and decides actions (vision-screen-changed PASS; rationale 'Current screen is the Android home launcher … launching the Lava client app'). Remaining blocker: the 'launch' action dispatch returns 'exit status 127' (command not found) — visionnav step 2 dispatch 'launch digital.vasic.lava.client.dev'. The derived launch action is 'shell monkey -p digital.vasic.lava.client.dev 1'; the ADB actor appears to run it without the 'adb -s <serial>' prefix (treating 'shell' as a command) OR adb is not on the dispatch subprocess PATH. Fix in helixqa pkg actor/bridge ADB dispatch. Evidence: .lava-ci-evidence/helixqa/vm-qa-vision2-20260608T182251Z/ (4/4 reached vision+navigation, 1 ERROR + 3 FAILED on the launch-dispatch 127, NOT bridge errors anymore).

## LVA-010 — lava-api-go router mounts /v1/{provider} group WITHOUT provider middleware (handlers panic→500)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** commit ec85b753 lava-api-go/internal/router/router_provider_dispatch_test.go (ProviderMiddleware mounted; /v1 dispatch 200/404/501 not 500)
**Severity:** P2
**Created-By:** AI

internal/router/router.go Build() registers the /v1/:provider route group without the provider-resolution middleware, so those handlers panic-recover to 500 in production instead of resolving a provider. Surfaced by the new router_test.go (W4) which asserts resolution (non-404) — the routes resolve but to a 500, not a working provider dispatch. Confirm whether production wires the middleware elsewhere (real server bootstrap) or this is a real gap. Evidence: router_test.go note + internal/router/router.go.

## LVA-011 — TestEndpointsRepository.add Third-Law divergence (stores Rutracker; real impl no-ops it)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** commit d9a4eaaa core/testing TestEndpointsRepository Rutracker no-op + equivalence test
**Severity:** P3
**Created-By:** AI

core/testing TestEndpointsRepository.add() stores an Endpoint.Rutracker and can raise a duplicate-conflict for it, but the real EndpointsRepositoryImpl.add() early-returns for Rutracker (if (endpoint is Endpoint.Rutracker) return) and never stores it. A future test asserting 'adding Rutracker is a no-op' would pass against the fake while exercising different behavior than production — a latent §Third-Law bluff-fake. Fix: add the Rutracker no-op branch + doc to the fake. Flagged by the W6 core/domain UseCase agent (2026-06-09).

## LVA-012 — core/testing TestVisited/Favorites/Bookmarks repositories are TODO-throw stub bluff-fakes

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** commit d28ddd22 core/testing Visited/Favorites/Bookmarks fakes implemented + 13 equivalence tests
**Severity:** P2
**Created-By:** AI

core/testing TestVisitedRepository, TestFavoritesRepository, TestBookmarksRepository have observe/contains/add/etc methods that are TODO('Not yet implemented') (throw). They are unusable for behavioral wiring — feature ViewModel tests (account, visited, rating) work around them with local in-memory fakes. Per the Third Law these shared fakes should be behaviorally-equivalent to the real repos so tests can use them directly. Flagged by the rating+visited VM-test agent (2026-06-09). Fix: implement the stubs as real in-memory fakes matching the real repo contracts + add equivalence tests.

## LVA-013 — TestEndpointsRepository.observeAll seeds [Rutracker] but real impl never emits Rutracker (deeper Third-Law divergence)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** core/testing TestEndpointsRepository.observeAll filterNot Rutracker + no-seed (matches EndpointsRepositoryImpl); 4 phantom-seed test methods rewritten across 3 files; Bluff-Audit: mutation reintroducing onStart seed → core/testing TestEndpointsRepositoryEquivalenceTest expected:<[]> but was:<[Rutracker]> + core/domain TestInfrastructureContractTest 'Fresh-install first observe MUST emit []... Got: [Rutracker]', reverted; GREEN core:testing 24/0, core:domain 57/0, feature:connection 10/0
**Severity:** P3
**Created-By:** AI

Beyond LVA-011 (add no-op, fixed): the fake's observeAll() seeds + emits [Endpoint.Rutracker] while the real EndpointsRepositoryImpl.observeAll() filterNot{it is Rutracker} + purges Rutracker every observe (never emits it). ~12 consumer tests (core/domain + core/testing) are written around the fake's [Rutracker] seed contract, so fixing the fake requires updating those tests in lockstep. Flagged by the LVA-011 agent (2026-06-09).

## LVA-014 — TestSuggestsRepository was a TODO-throw stub (LVA-012 bluff class)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** core/testing TestSuggestsRepository implemented (in-memory, case-insensitive UPSERT id=lowercase().hashCode(), newest-first, clear) + TestSuggestsRepositoryTest (4 tests); Bluff-Audit: drop dedup filterNot → 'case-insensitive UPSERT MUST keep one row expected:<1> but was:<2>', reverted; core:testing 31/0, feature:menu 17/0
**Created-By:** AI

core/testing TestSuggestsRepository.observeSuggests/addSuggest threw TODO(); clear() no-op. Real SuggestsRepositoryImpl emits newest-first (timestamp DESC) + case-insensitive UPSERT (id=lowercase().hashCode(), REPLACE) + clear deletes all. Unusable stub = Third-Law bluff fake.

## LVA-015 — TestSearchHistoryRepository positional-id + no-UPSERT + wrong ordering (Third-Law divergence + bluff test)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** core/testing TestSearchHistoryRepository content-derived id (replicates Filter.id()) + UPSERT + newest-first; TestSearchHistoryRepositoryTest rewritten to real contract (6 tests); Bluff-Audit: revert to positional-append → 'upserts the same logical search' + 'dedups searches that differ only by sort' FAILED expected:<1> but was:<2>, reverted; core:testing 31/0, feature:menu 17/0, feature:search 9/0
**Created-By:** AI

core/testing TestSearchHistoryRepository.add used positional id (it.size) + always appended (no dedup) + insertion order, while real SearchHistoryRepositoryImpl uses content-derived Filter.id() + @Insert REPLACE UPSERT + ORDER BY timestamp DESC. The existing TestSearchHistoryRepositoryTest asserted the fake-shaped behavior (ids [0,2], oldest-first) — a bluff test.

## LVA-016 — RatingViewModelTest RealObserveRatingRequestUseCase drops the engagement gate (ACTIVE bluff)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** core/domain ObserveRatingRequestUseCaseImpl made public (Fifth Law); feature/rating RatingViewModelTest now wires the REAL use case over LVA-012/015-fixed shared fakes, seeds real engagement (3 bookmarks>2) in the 4 Show-path tests. Bluff-Audit: neutralize seedEngagement → 4 Show-path tests FAIL 'TurbineAssertionError: No value produced in 3s' (real engagement gate the old fake dropped), 2 Hide tests pass; reverted. GREEN feature:rating 6/0, core:domain 57/0
**Created-By:** AI

feature/rating RatingViewModelTest's local RealObserveRatingRequestUseCase re-implements ObserveRatingRequestUseCaseImpl but OMITS the 3rd condition (engagement: pinned>1 OR other>3 OR visited>5 OR bookmarks>2). The test 'Show rating request is rendered when conditions are met' seeds ZERO engagement + asserts Show, but the REAL use case would emit Hide for that user state. Second-Law/§6.J bluff: re-implements internal business logic + asserts an outcome production would not produce. Fix: wire the real ObserveRatingRequestUseCaseImpl (deep tree: ObserveSearchHistory/Visited[->EnrichTopics]/Bookmarks use cases over shared in-memory fakes, now usable post LVA-012/015) + seed engagement in the Show test. Source: parallel fake-audit 2026-06-09.

