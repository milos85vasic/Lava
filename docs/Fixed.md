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

## LVA-027 — Kinozal search sizeBytes hardcoded null — every result drops its size

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** KinozalSizeParser (binary mult, comma/dot, Latin+Cyrillic units) + KinozalSearchParser sizeBytes wired; KinozalSizeParserTest 5 tests incl end-to-end. Bluff-Audit: revert sizeBytes→null → 'search row carries sizeBytes end to end FAILED', reverted; :core:tracker:kinozal:test GREEN
**Created-By:** AI

KinozalSearchParser parsed the size string into a local var then emitted sizeBytes=null, so every Kinozal row dropped size (size sort/filter + cross-tracker ranking blind). Found by parallel Kotlin tracker bug-hunt.

## LVA-025 — v1 captcha login sends the answer under the wrong form-field name (captcha login can never succeed)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-025-026-evidence.md
**Created-By:** AI

internal/handlers/v1 LoginOpts has only CaptchaCode (used as the answer) but rutracker needs CaptchaCode=dynamic-field-NAME (cap_code_<sid>) + CaptchaValue=answer. The adapter sets both to the answer (self-acknowledged TODO provider.go:221). Needs LoginOpts + OpenAPI model change (CaptchaValue field) — deferred. Found by parallel Go bug-hunt.

## LVA-026 — v1 captcha response hardcodes image/png, discards upstream Content-Type

**Status:** Implemented (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-025-026-evidence.md
**Created-By:** AI

internal/handlers/v1/captcha.go serves c.Data(200, image/png, ...) but rutracker.FetchCaptcha captures the real Content-Type, dropped by the adapter (no ContentType field on provider.CaptchaImage). Minor (most decoders sniff). Found by parallel Go bug-hunt.

## LVA-028 — Nnmclub search publishDate dropped (date column present + parseable)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-028-evidence.md
**Created-By:** AI

NnmclubSearchParser reads row.select('td') but never maps cells[4] (ISO yyyy-MM-dd date) into TorrentItem.publishDate → Nnmclub results have no date. Found by parallel Kotlin tracker bug-hunt. (Also UNCONFIRMED: nnmclub/kinozal parseSize Latin-only regex may miss Cyrillic units in real HTML — kinozal already handles both as of LVA-027.)

## LVA-029 — isLocalHost() fc/fd false-positive misclassifies public hosts as IPv6 unique-local

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-029-evidence.md
**Created-By:** AI

core/models HostUtils.isLocalHost runs the fc00::/7 unique-local check (startsWith fc/fd + take(4) hex in 0xfc00..0xfdff) on ANY host string without requiring an IPv6 literal, so a DNS host like fcba.example.com / fdcdn.net is routed as LAN (http://host:8080) instead of https://host/forum/ → no green dot, every request fails. Fix: gate on contains(':') before the hex parse. Found by parallel core/network bug-hunt.

## LVA-6 — §11.4.79 reconcile codegraph index policy (own-org submodules IN index)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/../codegraph/lva6-groundtruth-20260609.md
**Severity:** P2

§11.4.79 (new) requires own-org submodules IN the codegraph index; Lava currently EXCLUDES submodules/ per docs/CODEGRAPH.md + 63rd-cycle policy. Reconcile .codegraph config + docs when pin is bumped. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-031 — Genymotion VM+nav UUIDs in tracked evidence trip §6.R scanner

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/../codegraph/lva6-groundtruth-20260609.md
**Severity:** P2

scan-no-hardcoded-uuid.sh flags VM UUID + NavBackStackEntry UUIDs in 14 tracked genymotion evidence files → check-constitution exit 1. Redacted per e767b701 precedent.

## LVA-032 — rutracker browse/favorites blind-cast Topic→Torrent type confusion

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-032-evidence.md
**Severity:** P1

fromCategoryPage/fromFavoritesDto called AsForumTopicDtoTorrent ignoring the union discriminator → Topic variants render as empty fake-torrent rows. Fixed via AsForumTopicDtoTorrentChecked.

## LVA-033 — rutor topic page drops Добавлен publishDate

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-033-evidence.md
**Severity:** P2

RuTorTopicParser dropped the Добавлен date despite the fixture carrying it. Added dotted DD-MM-YYYY shape to RuTorDateParser.

## LVA-034 — Room endpoint-list converter drops GoApi platform/storage/key on round-trip

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-034-evidence.md
**Severity:** P1

host:port-only packing dropped the per-instance key → list-selected on-device Lava-API endpoint 401s (withKeyOverride dead). Fixed via #-sentinel encoded packing.

## LVA-035 — PagingDataLoader pagination state-machine had zero tests

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-035-evidence.md
**Severity:** P2

Added PagingDataLoaderTest (append-terminal off-by-one + refresh-error regression), production byte-identical.

## LVA-030 — 6 recently-added submodules missing §6.R inheritance pointer (pre-existing §6.AD-debt)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-030-evidence.md
**Created-By:** AI

doc_processor/helixqa/llm_orchestrator/llm_provider/llms_verifier/vision_engine CLAUDE.md lack the §6.R No-Hardcoding inheritance block; scripts/check-constitution.sh full-run exits 1 on them. Pre-existing from the 5-dep + helixqa adoption; orthogonal to the 60e2d66 constitution bump. Each needs a submodule commit + push + parent pin bump (helixqa is always-track-upstream per Q9). Surfaced by full check-constitution during the constitution-bump cycle.

## LVA-037 — §6.R hostport scanner flags its own tests/ hermetic fixture (missing tests/ exemption)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-037-evidence.md
**Severity:** P2

scan-no-hardcoded-hostport.sh regex had /test/ but not top-level tests/ → flagged tests/check-constitution/test_no_hardcoded_hostport.sh → check-constitution exit 1 → pre-push blocked. Added ^tests/ exemption.

## LVA-038 — brotli middleware emitted Content-Encoding br on bodyless 204/304 (statusAllowsBody dead code)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-038-evidence.md
**Severity:** P2

Wave-3 parallel-fleet find; fixed in f3b2d269; falsifiability-proven; main-stream re-verified.

## LVA-039 — ProviderLoginViewModel.onReloadCaptchaClick leaves stale serviceUnavailable banner

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-039-evidence.md
**Severity:** P2

Wave-3 parallel-fleet find; fixed in 9f895502; falsifiability-proven; main-stream re-verified.

## LVA-040 — onboarding anonymous-mode choice never persisted to provider_configs

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-040-evidence.md
**Severity:** P1

Wave-3 parallel-fleet find; fixed in 8e0720ab; falsifiability-proven; main-stream re-verified.

## LVA-041 — SyncPeriod.DAY background-sync flex interval 6.days should be 6.hours (WorkManager clamp)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-041-evidence.md
**Severity:** P2

Wave-3 parallel-fleet find; fixed in 0cecbe4e; falsifiability-proven; main-stream re-verified.

## LVA-042 — SyncOutbox FIFO Third-Law fake divergence + createdAt regression coverage

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-042-evidence.md
**Severity:** P2

Wave-3 parallel-fleet find; fixed in a11aee32; falsifiability-proven; main-stream re-verified.

## LVA-043 — §6.E reverse-gate phantom-capability honesty assertion

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-043-evidence.md
**Severity:** P2

Wave-3 parallel-fleet find; fixed in c117caeb; falsifiability-proven; main-stream re-verified.

## LVA-045 — §6.AI-debt CM-COVENANT-114-* §11.4.128-141 propagation gate + slash-command docs

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-045-evidence.md
**Severity:** P2

Wave-3 parallel-fleet find; fixed in c37995a7; falsifiability-proven; main-stream re-verified.

## LVA-046 — rutracker Login reported fake success on wrong credentials (blind AuthResponseDto union accessor ignored discriminator)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-046-evidence.md
**Severity:** P2

AsAuthResponseDtoSuccess() blind-decodes a WrongCredits union into non-pointer UserDto with no error → Login returns Success:true AuthToken:'' for wrong creds → every authed call 401s. LVA-032 class. Fixed via AsAuthResponseDtoSuccessChecked.

## LVA-047 — MagnetLinkValidator rejects case-insensitive urn:btih prefix (RFC 8141)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-047-evidence.md
**Severity:** P2

case-sensitive startsWith rejected magnet:?xt=urn:BTIH:<hash>; fixed to regionMatches ignoreCase.

## LVA-044 — Add HTTP_DOWNLOAD TrackerCapability so archiveorg/gutenberg can surface their working HTTP file download

**Status:** Implemented (→ Fixed.md)
**Type:** Feature
**Evidence:** .lava-ci-evidence/workable-items/LVA-044-evidence.md
**Severity:** P3

archiveorg/gutenberg wire a real HTTP-download impl that is deliberately not exposed (no capability) → user-unreachable. Not a bluff (§6.E honest), a functionality gap. Needs a new capability + feature interface.

## LVA-051 — §11.4.128 always-on device-recorder skeleton + deterministic-path test (device capture OWED)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-051-evidence.md
**Severity:** P2

scripts/record-device-session.sh + hermetic path test landed; device-bound capture UNCONFIRMED without a live device.

## LVA-048 — feature/search_input writes author NAME under AuthorIdKey (wrong nav key)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-048-evidence.md
**Severity:** P2

SearchInputNavigation openSearchInput appends filter.author?.name under AuthorIdKey instead of AuthorNameKey. Flagged by wave-4 §6.Q agent (out of its scope). Needs fix + roundtrip test.

## LVA-049 — navigation route values not URL-encoded (reserved chars in search query corrupt route)

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-049-evidence.md
**Severity:** P3

appendOptionalParams passes raw query; a query with &/? corrupts the route. Add Uri.encode at call sites + roundtrip test.

## LVA-050 — A11yContentDescriptionTest teardown RuntimeException (Robolectric RoboMonitoringInstrumentation) reds :core:designsystem:test despite assertions passing

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-050-evidence.md
**Severity:** P3

All 4 test bodies pass (XML failures=0) but a teardown RuntimeException makes Gradle report FAILED. Quarantine/fix the teardown.

## LVA-053 — PostConverters.removeLast() crashes topic screen on Android <API 35 (NoSuchMethodError)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-053-evidence.md
**Severity:** P1

Kotlin removeLast() desugars to java.util.List.removeLast (JDK21 SequencedCollection) absent on Android <35 → a PostBr followed by Hr crashes the topic screen. Fixed to removeAt(lastIndex) + 28 converter tests.

## LVA-056 — §6.R IPv4 scanner flags device-recorder tests/ hermetic fixture (missing tests/ exemption)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-056-evidence.md
**Severity:** P2

scan-no-hardcoded-ipv4.sh regex lacked top-level tests/ → flagged tests/device-recording synthetic 127.0.0.1:6555 → check-constitution exit 1 (LVA-037 class). Added ^tests/.

## LVA-052 — Wire HTTP_DOWNLOAD into the topic/download UI (consume HttpDownloadableTracker, write artifact to disk)

**Status:** Implemented (→ Fixed.md)
**Type:** Feature
**Evidence:** .lava-ci-evidence/workable-items/LVA-052-evidence.md
**Severity:** P3

HTTP_DOWNLOAD UI wiring. A full impl exists on branch worktree-agent-ad695086c8735d60a (commit c8951eee) but it re-derived LVA-044 substrate → conflicts with master's landed LVA-044; needs a clean re-apply of the LVA-052 net-new files (HttpDownloadSource/DownloadHttpFileUseCase/DownloadService.downloadHttpFile/SDK.downloadHttpFile) on top of master. Topic-screen Compose routing (provider id through nav) still OWED.

## LVA-054 — Audit codebase for removeLast/removeFirst/getFirst/getLast SequencedCollection calls that crash on Android <API35

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-054-evidence.md
**Severity:** P1

LVA-053 class: Kotlin stdlib these desugar to java.util.* methods absent below API35. Sweep all modules + add a lint/detekt guard.

## LVA-055 — run-test-pg.sh integration list omits ./internal/storage (Postgres legs skipped by canonical harness)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-055-evidence.md
**Severity:** P3

scripts/run-test-pg.sh runs only ./internal/cache + ./tests/integration; add ./internal/storage so its Postgres legs run in the canonical harness.

## LVA-057 — multi-search SSE silently drops unknown requested providers

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-057-evidence.md
**Severity:** P2

GetMultiSearch continued past an unknown ?providers= id with no provider_error event and no failed counter, while total_providers still counted it — the user's requested provider vanished with zero client signal. Now emits provider_error + counts failed.

## LVA-058 — §6.R scanners flag binary workable_items.db SSoT (LVA-037/056 class)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-058-evidence.md
**Severity:** P2

The ipv4/hostport/uuid source-literal scanners scanned the tracked binary SQLite SSoT docs/workable_items.db, which stores ticket text quoting IP/host/UUID literals, producing a Binary file matches violation. Added .db exemption to all three.

## LVA-060 — favorite/visited Torrent reconstruction drops magnetLink and date

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/workable-items/LVA-060-evidence.md
**Severity:** P1

FavoriteTopicEntity/VisitedTopicEntity toTopic() reconstructed Torrent vs BaseTopic without checking date/magnetLink, so a magnet-only favorited or visited torrent was read back as BaseTopic and became un-downloadable, and getTorrents() filtered it out entirely. Added both fields to the discriminator.

## LVA-059 — sort ProviderRegistry.IDs() consumers for deterministic multi-search SSE provider ordering

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-059-evidence.md
**Severity:** P3

multi-search auto-discovery emits providers in non-deterministic map order (cosmetic).

## LVA-061 — mobile/tls.go regenerate embed cert when persisted IP-SANs no longer cover device LAN IP

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-061-evidence.md
**Severity:** P3

self-signed cert reused across restarts without re-checking IP-SAN; LAN IP change → host-mismatch.

## LVA-062 — audit gate-shaping scripts for uncovered clauses (each gate clause needs a branch-covering falsifiability sub-test)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-062-evidence.md
**Severity:** P3

wave-6 §6.N.2 found CM-WORKABLE-ITEMS-SYNC clause-3 was uncovered; sweep other gates.

## LVA-063 — cover CredentialsViewModel + CredentialsManager vault lifecycle branches

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-063-evidence.md
**Severity:** P3

Added 9 falsifiable tests for the credentials dialog SubmitDialog flows, §6.G verified filter, and the credentials_manager FirstTimeSetup/AddNew/Edit/DismissEdit lifecycle.

## LVA-066 — validate new Jackett release against the Lava Torznab sidecar integration

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-066-evidence.md
**Severity:** P2

Operator flagged a new Jackett version. Booted lscr.io/linuxserver/jackett v0.24.2040-ls426 via podman in the loopback topology: api_key bootstrap + Torznab /caps endpoint served valid XML at the exact path IPTorrentsJackettApi uses; JVM parser tests green; pinned the validated digest in .env.example.

## LVA-064 — loadOrCreateTLS does not detect expired persisted cert (NotAfter passed)

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-064-evidence.md
**Severity:** P3

The TLS reuse gate checks IP-SAN coverage but not certificate expiry, so a persisted cert past its NotAfter is silently reused; add an expiry check to the reuse gate with a test.

## LVA-065 — audit feature/search SearchViewModel for untested error/paging branches

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-065-evidence.md
**Severity:** P3

SearchViewModel was not inspected in depth this loop; audit its error and paging-state branches and add falsifiable coverage where real gaps exist.

## LVA-068 — proactively re-mint embed TLS leaf mid-process when it crosses the expiry margin

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-068-evidence.md
**Severity:** P3

tls.go only re-checks cert expiry at Start; a multi-month-running embed could serve a leaf that expires without a restart. Add a periodic re-mint check.

## LVA-069 — cover SearchResultViewModel SSE raw-JSON parsing + onRetryClick re-dispatch

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-069-evidence.md
**Severity:** P3

handleSseEvent raw-JSON parsing (provider_start/results/provider_done/provider_error) and onRetryClick Error-state re-dispatch are untested production branches.

## LVA-073 — §6.AC telemetry instrumentation for the accepted LVA-008 nav-teardown crash

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-073-evidence.md
**Severity:** P1

Per operator accept-with-telemetry decision, NavTeardownCrashReporter (chained uncaught handler) tags the known upstream androidx nav-teardown ISE with attributable §6.AC Crashlytics context before the process dies, so it surfaces as a known/triageable defect; 6 falsifiable JVM tests; wired into LavaApplication.

## LVA-072 — §6.AC telemetry on mid-process embed TLS cert rotation (LVA-068 swap is silent)

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-072-evidence.md
**Severity:** P3

LVA-068 rotates the embed leaf mid-process but the swap is silent; emit a RecordWarning on rotation with feature/operation/old+new NotAfter/IP-SANs context (no secrets per §6.H). A wave-10 attempt was discarded because its falsifiability rehearsal was inconclusive and the SourceHash contract failed; redo with a test that fails when the actual rotation-path RecordWarning call is removed.

## LVA-074 — Genymotion runner must wake+stay-on the VM screen before connectedDebugAndroidTest

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-074-evidence.md
**Severity:** P2

A sleeping VM screen idles the render pipeline causing SurfaceFlinger commit timeouts and spurious No-compose-hierarchies Challenge failures; the runner now wakes and keeps the screen on, proven by C00/C01/C07/C08 going green after the wake.

## LVA-070 — thread source providerId through favorite/visited write+read path to complete HTTP_DOWNLOAD routing

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-070-evidence.md
**Severity:** P3

LVA-067 laid the providerId Room column; complete it by threading providerId through ToggleFavoriteUseCase/AddLocalFavoriteUseCase/GetTopicUseCase + the Topic/TopicPage models (write) and TopicModel + favorites/visited side-effects to openTopic(id,providerId) (read), mirroring search_result.

## LVA-071 — inject SseClient + base-URL into SearchResultViewModel.observeSseSearch for hermetic MockWebServer testing

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-071-evidence.md
**Severity:** P3

observeSseSearch internally constructs the SseClient with a hardcoded https base; inject it so the full SSE error to Error to retry path is hermetically testable against a MockWebServer.

## LVA-067 — persist per-row providerId on favorite/visited rows so their topics can route to HTTP_DOWNLOAD

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/workable-items/LVA-067-evidence.md
**Severity:** P3

LVA-052 scoped favorites/visited topic downloads to the active-tracker default because the favorite/visited Room rows store no provider id; add a providerId column + migration so an archiveorg/gutenberg favorite routes to HTTP_DOWNLOAD.

## LVA-4 — LVA workable-items ticket system (SQLite DB + Go CLI + md/HTML/PDF/DOCX export)

**Status:** Implemented (→ Fixed.md)
**Type:** Feature
**Evidence:** .lava-ci-evidence/workable-items/LVA-4-evidence.md
**Severity:** P1

HelixConstitution §11.4.93/95/106 materialization. Go CLI (modernc.org/sqlite, no CGO) with init/add/update/close/reopen/gen/verify/import/export. Operator directive §6.L 68th invocation, key prefix LVA. Superseded by migration to the canonical constitution binary (docs/tickets/MIGRATION-TO-CANONICAL.md). **Source:** operator-report — docs/tickets/DESIGN.md

## LVA-076 — apiengine processJavaRes buildCshared task dependency + Gradle heap

**Status:** Completed (→ Fixed.md)
**Type:** Bug
**Evidence:** docs/CONTINUATION.md
**Severity:** medium

Gradle validation BUILD FAILED on clean .cxx: process JavaRes consumed buildCshared output without a declared edge; D8 OOM on 2g heap. Declared dependency plus bumped heap to 4g.

## LVA-077 — release cold-start canary script for the R8 release artifact

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** docs/CONTINUATION.md
**Severity:** medium

App has no connectedReleaseAndroidTest because testBuildType is debug; added scripts run-release-canary.sh to validate the exact R8 release APK cold-start on the Genymotion VM per clause 6.Z.

## LVA-078 — restore lava-api-go container image build broken containerignore

**Status:** Completed (→ Fixed.md)
**Type:** Bug
**Evidence:** docs/CONTINUATION.md
**Severity:** medium

Root containerignore blanket-excluded submodules but go.mod replaces parent submodules X; in-container go build failed on missing replacement dirs. Excluded only nested git and build, kept source.

## LVA-092 — Video #11 — Negative finding: NO crash/ANR/white-icon observed in this recording (recorded for completeness)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md
**Severity:** P3
**Created-By:** AI

QA video 0001-0155: app did not crash/ANR; brand logo renders red. LVA-008 search-back crash + Sync-toggle crash + §6.AB white-icon were NOT triggered on screen here (remain open under their own tickets). Informational record. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #11.

## LVA-080 — Distribute 1076/1.3.12 (+ api-app 22): build_and_release -> §6.Z C00 gate on thinker -> §6.AA two-stage Firebase distribute

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app/0.2.11-22-test-evidence.md
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

1076 built but never distributed (last-distributed=1075). Operator directive: rebuild, gate, distribute all 4 variants. Infra confirmed UP this session: thinker+nezha reachable, T7 writable, JDK17/Gradle8.9/podman5.8.2 present.

## LVA-036 — llm_orchestrator github↔gitlab pre-existing mirror fork (non-FF) blocks §6.W convergence

**Status:** Fixed (→ Fixed.md)
**Type:** Task
**Evidence:** docs/issues/fixed/BUGFIXES.md
**Severity:** P2

github/master and gitlab/master diverged at d2a2151 with unique non-doc go.mod content each; LVA-030 commit landed gitlab+working-tree but github refused non-FF. Needs a content-merge decision (operator-gated, NO force-push per §6.T.3).

## LVA-083 — Video #1 — Search returns ZERO results then 'Something went wrong' Error (primary function unusable)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/lva-1082-device-gate/Challenge58SearchReturnsResultsTest/real-device-verification.json + firebase-distribute.sh release 2eadee3bf6vq0 (1.3.15-1082, distributed 2026-08-12)
**Severity:** P0
**Created-By:** AI
**Assigned-To:** AI

QA video 2026-06-25 frames 0060-0140: every search fails (blank ~25s then Error/Retry; 'prince' stays blank). KNOWN-class (anonymous/provider-mismatch). CODE-FIX landed in 1076 (SearchInputViewModel observeAll filtered+sorted + loading/empty state) but 1076 NOT yet distributed/device-verified. Pending §6.Z gate. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #1.

## LVA-084 — Video #2 — Onboarded provider (YTS) is NOT the provider set used by Search; unconfigured providers active as filters

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/lva-1082-device-gate/Challenge59SearchUsesOnboardedProvidersTest/real-device-verification.json + firebase-distribute.sh release 2eadee3bf6vq0 (1.3.15-1082, distributed 2026-08-12)
**Severity:** P0
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0030 vs 0040/0060: onboarded only YTS but search used RuTracker/RuTor/IA/Gutenberg etc. KNOWN (§6.L 57th/59th). CODE-FIX in 1076 (chips from ProviderConfigRepository.observeAll() searchEnabled&&isEnabled). Pending §6.Z device verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #2.

## LVA-013 — Missing 6.Z device evidence for client 1080 and api-app 24

**Status:** Obsolete (→ Fixed.md)
**Type:** Task
**Evidence:** Superseded: 1080/api-app-24 were never distributed and never will be. 1082/0.2.12-25 now have real §6.Z device-gate + release-canary evidence and are fully distributed (both stages) as of 2026-08-12.
**Obsolete-Details:** Since: 2026-08-12; Reason: superseded-by-later-mandate; Superseding-item: 1.3.15-1082 + 0.2.12-25 distribute cycle; Triple-check evidence: .lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.3.15-1082-test-evidence.md + .lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app/0.2.12-25-test-evidence.md

Task P0 Android: the 1080 client and 24 api-app cycles were distributed without per-AVD containerized emulator evidence. Need to execute the covering Challenge matrix, generate real-device-verification rows, and backfill the evidence files.

## LVA-085 — Video #4 — Provider id labels shown raw/lowercased ('torrentdownloads','archiveorg','kinozal','yts') in results filter chips

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** feature/search_result/src/test/kotlin/lava/search/result/SearchResultViewModelStreamingTest.kt::streaming_search_seeds_providerDisplayNames_on_first_Streaming_state_before_any_ProviderStart_event — real ViewModel+SDK+registry test, falsifiability rehearsal performed (mutation reverted, real observed AssertionError). Root cause: SearchResultScreen.ProviderFilterChipBar rendered from providerDisplayNames map populated only by async ProviderStart events, arriving after the first composed frame. Fix: eager synchronous seed via sdk.listAvailableTrackers() in the same reduce that opens Streaming state. Commit 80c9380d.
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0060/0125/0130: results chips render internal provider key not displayName. KNOWN-class (§6.L 60th displayLabel). CODE-FIX in 1076 (friendly chip names). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #4.

## LVA-086 — Video #5 — No empty-state and no loading indicator on search results (perceived hang)

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** feature/search_result/src/test/kotlin/lava/search/result/SearchResultViewModelLoadingEmptyStateTest.kt — 3 real Challenge tests, falsifiability rehearsal performed. Root cause: render predicate for the loading/empty state existed inline in SearchResultScreen but was never extracted/tested, so a prior fix (e7b6a652) could not be verified. Fix: extracted SearchPageState.streamingFilteredItems + showsStreamingLoadingIndicator as named, tested properties. Commit ffc0fb25.
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0060-0110: pure blank ~25s, no spinner/skeleton/no-results; 'prince' stays blank with no error. NEW. CODE-FIX in 1076 (loading/empty state branches). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #5.

## LVA-093 — Cold-start race: search can hit bundled/direct client before dynamic provider repopulation completes

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** core/domain/src/main/kotlin/lava/domain/usecase/StartupProvidersGate.kt (new) + RepopulateProvidersOnStartupUseCase.kt + SearchResultViewModel.observeStreamMultiSearch — 15 real tests across StartupProvidersGateTest/RepopulateProvidersOnStartupUseCaseTest/SearchResultViewModelStreamingTest, 3 falsifiability rehearsals performed. Fix: bounded StateFlow<Boolean> readiness gate (5s timeout fallback) awaited before the tracker client is resolved. Commit 9d85c5cf.
**Severity:** P2
**Created-By:** AI

app/src/main/kotlin/digital/vasic/lava/client/LavaApplication.kt:95-105 launches RepopulateProvidersOnStartupUseCase.repopulateProviders() fire-and-forget on Dispatchers.Default inside Application.onCreate() -- it is not awaited. app/src/main/kotlin/digital/vasic/lava/client/MainActivity.kt:105-135 gates the splash screen ONLY on local prefs (theme/showOnboarding) loading and has no dependency on repopulateProviders() completion. Consequently the splash can dismiss and the user can reach the search screen while the network round-trip inside core/domain/src/main/kotlin/lava/domain/usecase/RepopulateProvidersOnStartupUseCase.kt:80-112 is still in flight. If the user searches during that window, any provider id the catalogue vends that overlaps a BUNDLED compiled-in provider id (rutracker/rutor/nnmclub/kinozal/archiveorg/gutenberg -- see core/tracker/client/src/main/kotlin/lava/tracker/client/di/TrackerClientModule.kt:297-303) resolves to the direct-to-site bundled client for that one search instead of the user's configured API endpoint. Once the fetch completes, all later searches in that session are correctly dynamic -- this is a real, narrow-window, self-healing defect, not a permanent break. No existing test covers this exact race: the closest test, RepopulateProvidersOnStartupUseCaseTest, exercises the use case in isolation and does not measure or force the Application.onCreate-to-first-interactive-search timing window. CONFIRMED by static source-reading this session (2026-08-10/11), part of the LVA-083/084 root-cause investigation that also found the OnboardingBypassRule-skips-populateFrom test bug in Challenge58/59/60/61/62/71. Real-world frequency and user-visible impact are UNCONFIRMED without device instrumentation -- no build or test was executed for this specific finding, only source reading. Closure needs either (a) a device-level Challenge Test that measures or deliberately forces this race and asserts correct provider resolution once repopulation completes, or (b) a product decision to gate the splash screen / first search on repopulation completion (with a timeout + fallback) instead of racing it.

## LVA-094 — Cold-start provider repopulation failure is silent and never retried for the rest of the process lifetime

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** core/domain/src/main/kotlin/lava/domain/usecase/RepopulateProvidersOnStartupUseCase.kt — try/finally markReady() guarantee + fetchWithRetry() single retry + recordFetchFailure() §6.AC non-fatal telemetry per failed attempt. Real RepopulateProvidersOnStartupUseCaseTest coverage, falsifiability rehearsal performed (reverted to single-attempt/no-telemetry, both retry-rescue and telemetry tests failed for real). Commit 9d85c5cf.
**Severity:** P2
**Created-By:** AI

RepopulateProvidersOnStartupUseCase's single cold-start provider-catalogue fetch attempt fails silently on failure: RepopulateProvidersOnStartupUseCase.kt:110 returns false with no user-visible notice (unlike onboarding's own fetch-failure path, which surfaces a PROVIDER_CATALOG_FALLBACK_NOTICE), no retry, and no other production code path re-invokes populateFrom() for the rest of that process's lifetime. Confirmed: the only two production call sites for populateFrom are OnboardingViewModel.kt:950-998 (onboarding flow) and this startup use case (app cold start) -- there is no periodic worker and no connectivity-change listener that re-triggers it. A transient failure at exact boot time (network not yet associated, VPN/Wi-Fi still connecting, momentarily unreachable LAN lava-api-go/api-app endpoint) means a user who configured a non-bundled API endpoint during onboarding silently falls back to bundled/direct-to-site tracker clients for the ENTIRE app session, with zero indication anything degraded, until the process restarts (a fresh attempt) or the user re-runs onboarding. Fix direction, documented as the finding only and not yet implemented: either add a retry affordance / silent background retry with backoff, or at minimum record a non-fatal telemetry event per this project's own section 6.AC Comprehensive Non-Fatal Telemetry Mandate so the failure is visible in Crashlytics instead of invisible. CONFIRMED by static source-reading this session (2026-08-10/11), same investigation as the sibling cold-start race finding LVA-093. Real-world frequency and user-visible impact are UNCONFIRMED without device instrumentation -- no build or test was executed for this specific finding, only source reading.

## LVA-081 — Fetch/pull/merge latest from all submodules + build-verify (operator directive 2026-06-26)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/bluff-hunt/2026-08-12-lva-081-submodule-bump-evidence.md
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

Operator directive: fetch+pull+merge latest codebase from all submodules. Frozen-by-default overridden for this cycle. Must build-verify after each bump; revert+report any submodule whose bump breaks the build.

## LVA-3 — Adopt HelixConstitution §11.4.79–106 (pin 53 commits behind)

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/bluff-hunt/2026-08-12-lva-3-constitution-adoption-review.md
**Severity:** P1

Pin 208e2c8 is 53 commits behind origin/main 883ccc1. Highest-impact new clauses: §11.4.93/95/106 (workable-items SQLite DB tracked in git + md to DB sync engine), §11.4.79 (own-org submodules in CodeGraph), §11.4.85 (stress/chaos), §11.4.98, §11.4.102. Pin-bump is operator-gated; decision owed on §6.AD.3 Path B vs SQLite DB. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-087 — Video #6 — Welcome claims '4 providers available' but picker lists ~12

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/bluff-hunt/2026-08-12-lva-087-device-verification-evidence.md
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0007/0010/0015 vs 0020-0030. NEW. CODE-FIX in 1076 (#6 count bound to real descriptor list). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #6.

## LVA-090 — Video #9 — Onboarding 'Select all' silently enables auth-requiring (Captcha/Form Login) providers

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/genymotion/c66-2026-08-12-lva090-reverify/verdict.txt
**Severity:** P3
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0020-0025. NEW UX, contributes to #1. CODE-FIX in 1076 (#9 select-all handling): OnboardingViewModel.onToggleAllProviders() + requiresNoCredentials() correctly gate select-all to no-auth-only providers, cited to Issue #9 in-source. UNIT-LEVEL RE-VERIFIED 2026-08-12 (this cycle): OnboardingViewModelVideoFixesTest 'select all enables only no-credential providers' PASS, fresh execution (timestamp 2026-08-12T21:07, not cached). Existing DEVICE-LEVEL evidence at .lava-ci-evidence/genymotion/c66-redesign-{RED,GREEN}-20260626/ (proper RED-then-GREEN falsifiability pair, Challenge66SelectAllDoesNotEnableAuthProvidersTest) is 48 days / ~7 versions stale per SS6.AK -- device gate was occupied by a parallel LVA-087 verification agent this cycle, could not re-run. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #9.

## LVA-079 — Video #3 — search-input chips vs results-filter chips disagree + results chip set non-deterministic run-to-run

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** .lava-ci-evidence/bluff-hunt/2026-08-12-lva-079-verification-summary.md
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

QA video 2026-06-25 (frames 0040 vs 0060 vs 0125): input chip bar and results filter chips show different provider sets, and the results chip set CHANGES between two identical queries. 1076 fixed the INPUT chips (observeAll filtered+sorted) but the input-vs-RESULTS divergence + run-to-run instability is distinct and still open. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md issue #3.

