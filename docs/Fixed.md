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

## LVA-017 — feature favorites/topic local fakes: add() lacks REPLACE dedup (LATENT)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** feature/favorites InMemoryFavoritesRepository.add + feature/topic FakeFavoritesRepository.add now dedup-by-id (REPLACE) + 2 falsifiability tests. Bluff-Audit: revert to append → 'expected:<[7]> but was:<[7, 7]>', reverted; feature:favorites 6/0, feature:topic 4/0
**Created-By:** AI

feature/favorites InMemoryFavoritesRepository.add + feature/topic FakeFavoritesRepository.add append without dedup, while FavoritesRepositoryImpl/FavoriteTopicDao are @Insert REPLACE (id PK). LATENT: no test re-adds a duplicate id, so unexercised. Tighten add() to replace-by-id for consistency with LVA-011..015. Source: parallel fake-audit 2026-06-09.

## LVA-018 — core/preferences dead getters getHistorySyncPeriod/getCredentialsSyncPeriod (zero call sites)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** core/preferences PreferencesStorage[Impl] getHistorySyncPeriod/getCredentialsSyncPeriod removed (zero call sites, proven) + 2 test-fake overrides removed; core:preferences 22/0, core:auth:impl 14/0; values still surfaced via getSettings()
**Created-By:** AI

PreferencesStorage.getHistorySyncPeriod()/getCredentialsSyncPeriod() (interface + Impl) have zero production call sites; sync-period values are surfaced via getSettings() instead. Orphaned per-field-getter leftover (no Favorites/Bookmarks counterpart). Remove decl + override. Source: parallel dead-code audit 2026-06-09 (codebase otherwise clean: 0 shipped TODO(), 0 if(false), 0 silent-no-op effect methods).

## LVA-019 — nnmclub IsAuthorised profile-link fallback was dead (unquoted CSS attribute selectors)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** internal/nnmclub/login.go selectors quoted; login_isauthorised_branch_test.go (IsAuthorised 77.8→88.9%); Bluff-Audit: revert to unquoted → TestIsAuthorised_ProfileLinkFallback_NoLoginLink fails 'expected IsAuthorised=true', reverted; nnmclub tests + index_anon/logged_in fixtures pass; api-source.hash regenerated, sourcehash contract GREEN
**Created-By:** AI

internal/nnmclub/login.go IsAuthorised used unquoted CSS attribute selectors a[href*=login.php]/a[href*=profile.php]; an unquoted value containing '.' is not a valid CSS identifier so cascadia/goquery matched NOTHING — the profile-link auth-detection fallback was dead code. Fixed to quoted form a[href*="..."]. Found by parallel hermetic-coverage agent; real production bug.

## LVA-7 — §11.4.85 stress + chaos test scaffold

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** lava-api-go/internal/handlers/v1/search_thundering_herd_test.go — 512 concurrent identical-key reqs through REAL read-through cache; asserts all-200 + post-burst upstream loads=0 (convergence) + no goroutine-leak + -race clean. Bluff-Audit: delete cache.Set in search.go → 'convergence FAILED: post-burst loads=1 (want 0)', reverted. First runnable §11.4.85 chaos dim beyond phase-1; evidence JSON committed.
**Severity:** P2

§11.4.85 (new universal anchor) mandates a stress + chaos test class. Lava has no chaos/stress suite today. Assess + scaffold when pin is bumped. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-020 — archiveorg array-valued creator/title/year/date fails the WHOLE search/browse/topic response

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** flexString type (string|number|array→joined) in internal/archiveorg/flexstring.go; structs+map sites in search/browse/topic.go converted; flexstring_test.go. Bluff-Audit: route '[' away from array case → TestSearchResponse_ArrayValuedFields + TestMetadataResponse + TestFlexString FAIL, reverted; go test GREEN, api-source.hash regenerated, sourcehash contract GREEN
**Created-By:** AI

internal/archiveorg search.go/browse.go/topic.go decoded creator/title/year/date as string/*string; archive.org returns these as JSON arrays for multi-author/date items → json.Unmarshal fails the ENTIRE response (every result), not one row. Found by parallel Go bug-hunt.

## LVA-021 — observability error_class collapses to literal "error" for all real typed errors (telemetry blindness)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** underlyingTypeName fallback → fmt.Sprintf(%T, err); nonfatal_typed_errorclass_test.go (unnamed typed + *fs.PathError chain + context sentinels). Bluff-Audit: revert to "error" → TestClassOf_UnnamedTypedError + _StdlibTypedError FAIL 'collapsed to error', reverted; GREEN
**Created-By:** AI

internal/observability/nonfatal.go underlyingTypeName returned "error" for any error without Name() or the 2 context sentinels — i.e. virtually every real *fs.PathError/*net.OpError/*url.Error/pgx error → §6.AC per-type triage defeated. Existing nonfatal_errorclass_test was a bluff (only tested Name()-implementers).

## LVA-022 — gutenberg bestFormatName returns blank label for charset-suffixed Gutendex MIME keys

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** matchFormatByPrefix helper; bestFormatName+pickBestFormatURL prefix-match; utils_charset_format_test.go. Bluff-Audit: disable charset prefix → TestBestFormatName_CharsetSuffixed + TestPickBestFormatURL FAIL, reverted; existing TestBestFormatName_Table still GREEN (PDF-label scope-creep reverted)
**Created-By:** AI

internal/gutenberg/utils.go bestFormatName + pickBestFormatURL exact-matched bare MIME keys (text/plain) but Gutendex always suffixes charset (text/plain; charset=utf-8) → blank Format label + skipped preferred ordering for text-only books.

## LVA-023 — v1 login returns User.Id (profile data-uid) as AuthToken instead of User.Token (session cookie)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** provider.go AuthToken: success.User.Token; provider_adapter_e2e_test.go TestAdapter_Login_ReturnsSessionCookieNotDataUID_E2E (real POST /login.php→/index.php→/profile.php flow). Bluff-Audit: revert to User.Id → test FAILS 'AuthToken = the data-uid 99999', reverted; GREEN
**Created-By:** AI

internal/rutracker/provider.go ProviderAdapter.Login returned success.User.Id (numeric data-uid) as AuthToken; the real session cookie is User.Token. Login 'succeeds' but every authenticated v1 call (favorites/add-comment/download) sends a non-cookie value → 401. No e2e login test existed (why it shipped).

## LVA-024 — v1 GetTopic fabricates a bogus TopicFile{Name:"Size"} from the torrent size string

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** provider.go drops the synthetic Files entry; provider_mapping_test.go TestFromTopicPage rewritten to assert len(Files)==0. Bluff-Audit: restore the Size-file line → TestFromTopicPage FAILS 'want 0 entries', reverted; GREEN
**Created-By:** AI

internal/rutracker/provider.go fromTopicPage wedged the size string into a synthetic file entry → topic detail screen shows one nonsense 'Size' file instead of the real list. The existing TestFromTopicPage asserted the bogus file (bluff).

