# Tasks: Multi-Provider Extension

**Input**: Design documents from `/specs/001-multi-provider-extension/`
**Prerequisites**: plan.md, spec.md, data-model.md, contracts/, research.md, quickstart.md

**Tests**: Tests are MANDATORY per project constitution (Anti-Bluff Testing Pact, Seventh Law). Every test commit MUST carry a Bluff-Audit stamp.

**Organization**: Tasks are grouped by user story and implementation phase to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Baseline verification, test infrastructure, fixture capture

- [ ] T001 Run `./gradlew test` and `cd lava-api-go && make test` to verify baseline 100% pass rate; document results in `specs/001-multi-provider-extension/baseline-test-report.md`
- [ ] T002 Audit existing fakes (`FakeTrackerClient`, `TestEndpointsRepository`, `TestBookmarksRepository`) for behavioral equivalence per Third Law; document gaps in `specs/001-multi-provider-extension/fake-audit.md`
- [ ] T003 [P] Create test fixture directories: `core/tracker/nnmclub/src/test/resources/fixtures/nnmclub/{search,topic,forum,login}`
- [ ] T004 [P] Create test fixture directories: `core/tracker/kinozal/src/test/resources/fixtures/kinozal/{search,browse,details,login}`
- [ ] T005 [P] Create test fixture directories: `core/tracker/archiveorg/src/test/resources/fixtures/archiveorg/{search,metadata,collections}`
- [ ] T006 [P] Create test fixture directories: `core/tracker/gutenberg/src/test/resources/fixtures/gutenberg/{catalog,bookshelves,opds}`
- [ ] T007 Capture ≥5 HTML fixtures from real NNMClub website (search, topic, forum, login pages) with date-stamped filenames; store in `core/tracker/nnmclub/src/test/resources/fixtures/nnmclub/`
- [ ] T008 Capture ≥5 HTML fixtures from real Kinozal website (search, browse, details, login pages) with date-stamped filenames; store in `core/tracker/kinozal/src/test/resources/fixtures/kinozal/`
- [ ] T009 Capture ≥5 JSON fixtures from Internet Archive APIs (search, metadata, collections) with date-stamped filenames; store in `core/tracker/archiveorg/src/test/resources/fixtures/archiveorg/`
- [ ] T010 Capture RDF subset (100–200 entries) and OPDS fixtures from Project Gutenberg; store in `core/tracker/gutenberg/src/test/resources/fixtures/gutenberg/`
- [ ] T011 Run bluff hunt on 5 random existing `*Test.kt` files per Seventh Law clause 5; document results in `.lava-ci-evidence/bluff-hunt/2026-05-02.json`
- [ ] T012 Configure MockWebServer for Android integration tests in `core/testing/src/main/kotlin/lava/testing/MockWebServerRule.kt`
- [ ] T013 Configure Go API integration test infrastructure with `httptest.Server` in `lava-api-go/tests/integration/setup_test.go`

**Checkpoint**: Baseline green, fakes audited, fixtures captured, bluff hunt complete, test infrastructure ready.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure and all 4 provider implementations. No user story work can begin until this phase is complete.

**⚠️ CRITICAL**: All tasks in this phase are blocking. Provider implementations can run in parallel.

### 2.1 Go API Provider Abstraction

- [ ] T014 Define `Provider` interface in `lava-api-go/internal/provider/provider.go` with 18 methods (ID, DisplayName, Capabilities, AuthType, Encoding, Search, Browse, GetForumTree, GetTopic, GetComments, AddComment, GetTorrent, DownloadFile, GetFavorites, AddFavorite, RemoveFavorite, CheckAuth, Login, FetchCaptcha, HealthCheck)
- [ ] T015 Define `Credentials` struct in `lava-api-go/internal/provider/provider.go` with Type, CookieValue, Token, APIKey, APISecret, Username, Password fields
- [ ] T016 Define `SearchOpts`, `BrowseOpts`, `LoginOpts`, and all result types in `lava-api-go/internal/provider/provider.go`
- [ ] T017 Implement `ProviderRegistry` in `lava-api-go/internal/provider/registry.go` with thread-safe map, Register, Get, List methods
- [ ] T018 Create provider dispatch middleware in `lava-api-go/internal/middleware/provider.go` extracting `{provider}` path param, injecting into Gin context, returning 404 with available providers list if not found
- [ ] T019 Generalize auth middleware in `lava-api-go/internal/auth/generalized.go` to parse `provider_id:credential_type:credential_value` from Auth-Token header; maintain backward compatibility for bare tokens
- [ ] T020 Create RuTracker Provider adapter in `lava-api-go/internal/rutracker/adapter.go` wrapping existing `*rutracker.Client` to satisfy `Provider` interface
- [ ] T021 Refactor all handler files in `lava-api-go/internal/handlers/*.go` to retrieve Provider from Gin context and call Provider methods
- [ ] T022 Update route registration from flat paths to `/v1/{provider}/...` in `lava-api-go/internal/server/server.go`
- [ ] T023 Update `cmd/lava-api-go/main.go` to instantiate ProviderRegistry and register RuTracker adapter
- [ ] T024 Write database migrations `lava-api-go/migrations/0006_provider_credentials.up.sql`, `0007_provider_configs.up.sql`, `0008_search_provider_selections.up.sql`, `0009_forum_provider_selections.up.sql`
- [ ] T025 Update `lava-api-go/api/openapi.yaml` with `/v1/{provider}/...` route structure and provider parameter
- [ ] T026 Regenerate `lava-api-go/internal/gen/` via `make generate` (oapi-codegen)
- [ ] T027 Write contract test `lava-api-go/tests/contract/rutracker_response_parity_test.go` asserting byte-equivalent responses for all 13 RuTracker routes before/after refactor
- [ ] T028 Write real-binary contract test `lava-api-go/tests/contract/healthprobe_contract_test.go` verifying healthprobe flag compatibility per Sixth Law 6.A

### 2.2 Android Database Migration (v7 → v8)

- [ ] T029 Create `CredentialEntity` in `core/database/src/main/kotlin/lava/database/entity/CredentialEntity.kt`
- [ ] T030 Create `CredentialProviderAssociationEntity` in `core/database/src/main/kotlin/lava/database/entity/CredentialProviderAssociationEntity.kt`
- [ ] T031 Create `ProviderConfigEntity` in `core/database/src/main/kotlin/lava/database/entity/ProviderConfigEntity.kt`
- [ ] T032 Create `OfflineSearchCacheEntity` in `core/database/src/main/kotlin/lava/database/entity/OfflineSearchCacheEntity.kt`
- [ ] T033 Create `OfflineTopicCacheEntity` in `core/database/src/main/kotlin/lava/database/entity/OfflineTopicCacheEntity.kt`
- [ ] T034 Write `AppDatabase` v8 migration `MIGRATION_7_8` in `core/database/src/main/kotlin/lava/database/AppDatabase.kt`
- [ ] T035 Add schema JSON for v8 in `core/database/schemas/lava.database.AppDatabase/8.json`
- [ ] T036 Write migration test verifying v7→v8 preserves existing `tracker_mirror_health` and `tracker_mirror_user` data

### 2.3 Credentials Core Module

- [ ] T037 Create `core/credentials/api/Credential.kt` data class with id, label, type, associatedProviderIds, username, password, token, apiKey, apiSecret, createdAt, updatedAt, lastUsedAt
- [ ] T038 Create `core/credentials/api/CredentialType.kt` enum with USERNAME_PASSWORD, TOKEN, API_KEY
- [ ] T039 Create `core/credentials/api/CredentialRepository.kt` interface with getAll, getById, getByProvider, save, delete, associateWithProvider, dissociateFromProvider
- [ ] T040 Create `core/credentials/impl/CredentialDao.kt` Room DAO with CRUD and provider association queries
- [ ] T041 Create `core/credentials/impl/CredentialRepositoryImpl.kt` using Room + EncryptedSharedPreferences with AES-256-GCM encryption via Android Keystore
- [ ] T042 Create `core/credentials/impl/CredentialModule.kt` Hilt module providing repository bindings
- [ ] T043 Write `CredentialRepositoryImplTest` in `core/credentials/src/test/kotlin/lava/credentials/CredentialRepositoryImplTest.kt` verifying encrypted storage, CRUD operations, and provider associations using real implementation (no mocks)
- [ ] T044 Write bluff-audit rehearsal for T043: mutate `CredentialRepositoryImpl.save()` to skip encryption → verify test fails → revert → document in commit message

### 2.4 Android SDK Extensions

- [ ] T045 Update `core/tracker/api/TrackerCapability.kt` adding `HTTP_DOWNLOAD` capability value
- [ ] T046 Update `core/tracker/api/TrackerDescriptor.kt` adding `providerType` field (TORRENT, HTTP)
- [ ] T047 Update `core/tracker/client/LavaTrackerSdk.kt` adding `searchAll()`, `browseAll()`, `getForumTreeAll()`, `setCredentialsForProvider()`, `getCredentialForProvider()`, `setAnonymousMode()`, `getProviderConfig()`, `observeProviderConfigs()`
- [ ] T048 Update `core/tracker/client/LavaTrackerSdk.kt` adding per-provider timeout configuration support
- [ ] T049 Create `core/tracker/client/SearchResultBatch.kt` data class with providerId, results, error, timestamp, isComplete
- [ ] T050 Create `core/tracker/client/DeduplicationEngine.kt` with torrentMatcher (info-hash primary, title+size fallback) and httpContentMatcher (identifier/ISBN primary, title+creator fallback)
- [ ] T051 Write `DeduplicationEngineTest` in `core/tracker/client/src/test/kotlin/lava/tracker/client/DeduplicationEngineTest.kt` with known duplicate and non-duplicate pairs
- [ ] T051a [P] Create `UnifiedResult.kt` in `core/models/src/main/kotlin/lava/models/UnifiedResult.kt` with fields: id (deduplication key), title, sourceProviders (List<ProviderOccurrence>), size, date, thumbnailUrl, detailRoute
- [ ] T051b [P] Create `ProviderOccurrence.kt` in `core/models/src/main/kotlin/lava/models/ProviderOccurrence.kt` with fields: providerId, providerDisplayName, seeders, leechers, format, downloadUrl, magnetLink, originalResult

### 2.5 NNMClub Provider Implementation

- [ ] T052 [P] Create `core/tracker/nnmclub/build.gradle.kts` applying `lava.kotlin.tracker.module`
- [ ] T053 [P] Add `include(":core:tracker:nnmclub")` to `settings.gradle.kts`
- [ ] T054 [P] Create `NNMClubDescriptor` in `core/tracker/nnmclub/src/main/kotlin/lava/tracker/nnmclub/NNMClubDescriptor.kt` with trackerId "nnmclub", 10 capabilities, authType FORM_LOGIN, encoding Windows-1251
- [ ] T055 [P] Create `NNMClubHttpClient` with OkHttp, InMemoryCookieJar, Semaphore (4 concurrent), CircuitBreaker (5 failures/10s)
- [ ] T056 [P] Create `NNMClubSearch.kt` implementing `Searchable` with POST to `/forum/tracker.php`
- [ ] T057 [P] Create `NNMClubBrowse.kt` implementing `Browsable` with category-based browsing
- [ ] T058 [P] Create `NNMClubTopic.kt` implementing `Topic` with `viewtopic.php?t={ID}` parser
- [ ] T059 [P] Create `NNMClubComments.kt` implementing `Comments` with phpBB comment parser
- [ ] T060 [P] Create `NNMClubFavorites.kt` implementing `Favorites` with phpBB bookmark parser
- [ ] T061 [P] Create `NNMClubAuth.kt` implementing `Authenticatable` with phpBB login to `ucp.php?mode=login`
- [ ] T062 [P] Create `NNMClubDownload.kt` implementing `Downloadable` with `download.php?id={ID}`
- [ ] T063 [P] Create `NNMClubClient.kt` implementing `TrackerClient` with all feature interfaces
- [ ] T064 [P] Create `NNMClubClientFactory.kt` for Hilt registration
- [ ] T065 [P] Write parser tests for search, forum, topic, comments with HTML fixtures; include Bluff-Audit stamp
- [ ] T066 [P] Write capability honesty test `NNMClubCapabilityTest` enumerating descriptor capabilities and asserting `getFeature()` non-null
- [ ] T067 [P] Add NNMClub mirrors to `core/tracker/client/src/main/assets/mirrors.json`
- [ ] T068 Go API: Create `lava-api-go/internal/nnmclub/client.go` with circuit breaker, Windows-1251 decoding, phpBB session
- [ ] T069 Go API: Create `lava-api-go/internal/nnmclub/search.go`, `forum.go`, `topic.go`, `login.go`, `favorites.go`, `comments.go`, `download.go`
- [ ] T070 Go API: Register NNMClub provider in `cmd/lava-api-go/main.go`

### 2.6 Kinozal Provider Implementation

- [ ] T071 [P] Create `core/tracker/kinozal/` module structure and `build.gradle.kts`
- [ ] T072 [P] Create `KinozalDescriptor` with trackerId "kinozal", 6 capabilities (no FAVORITES), authType FORM_LOGIN, encoding Windows-1251
- [ ] T073 [P] Create `KinozalHttpClient` with OkHttp, InMemoryCookieJar, Semaphore (4), CircuitBreaker (3 failures/30s)
- [ ] T074 [P] Create `KinozalSearch.kt` with GET `/browse.php` parser
- [ ] T075 [P] Create `KinozalBrowse.kt` with category browsing
- [ ] T076 [P] Create `KinozalTopic.kt` with `details.php` + AJAX `get_srv_details.php` file list
- [ ] T077 [P] Create `KinozalComments.kt` from detail page
- [ ] T078 [P] Create `KinozalAuth.kt` with POST `/takelogin.php`, uid/pass cookie extraction
- [ ] T079 [P] Create `KinozalDownload.kt` with `dl.kinozal.tv/download.php`
- [ ] T080 [P] Create `KinozalSizeParser.kt` for Russian unit format (ТБ/ГБ/МБ/КБ)
- [ ] T081 [P] Create `KinozalClient.kt` and `KinozalClientFactory.kt`
- [ ] T082 [P] Write parser tests with Bluff-Audit stamps
- [ ] T083 [P] Write capability honesty test `KinozalCapabilityTest`
- [ ] T084 [P] Add Kinozal mirrors to `mirrors.json`
- [ ] T085 Go API: Create `lava-api-go/internal/kinozal/` package with client, search, topic, login, download
- [ ] T086 Go API: Register Kinozal provider

### 2.7 Internet Archive Provider Implementation

- [ ] T087 [P] Create `core/tracker/archiveorg/` module structure and `build.gradle.kts`
- [ ] T088 [P] Create `ArchiveOrgDescriptor` with trackerId "archiveorg", 6 capabilities, authType NONE, encoding UTF-8
- [ ] T089 [P] Create `ArchiveOrgHttpClient` with OkHttp, rate limiter (4 concurrent), proper User-Agent
- [ ] T090 [P] Create `ArchiveOrgSearchApi.kt` wrapping Advanced Search API with Lucene query builder
- [ ] T091 [P] Create `ArchiveOrgScrapeApi.kt` for cursor-based pagination
- [ ] T092 [P] Create `ArchiveOrgMetadataApi.kt` for item details
- [ ] T093 [P] Create `ArchiveOrgCollectionsApi.kt` for browse hierarchy
- [ ] T094 [P] Create `ArchiveOrgDownload.kt` implementing `Downloadable` with HTTP file download
- [ ] T095 [P] Create mappers: `ArchiveOrgSearchMapper.kt`, `ArchiveOrgItemMapper.kt`, `ArchiveOrgCollectionMapper.kt`
- [ ] T096 [P] Create `ArchiveOrgClient.kt` and `ArchiveOrgClientFactory.kt`
- [ ] T097 [P] Write JSON parser tests with fixtures
- [ ] T098 [P] Write rate limiting test with MockWebServer returning 429 + Retry-After
- [ ] T099 [P] Write capability honesty test `ArchiveOrgCapabilityTest`
- [ ] T100 Go API: Create `lava-api-go/internal/archiveorg/` with client, search, browse, item, download, collections
- [ ] T101 Go API: Register Internet Archive provider

### 2.8 Project Gutenberg Provider Implementation

- [ ] T102 [P] Create `core/tracker/gutenberg/` module structure and `build.gradle.kts` with Room and WorkManager dependencies
- [ ] T103 [P] Create `GutenbergDescriptor` with trackerId "gutenberg", 6 capabilities, authType NONE, encoding UTF-8
- [ ] T104 [P] Create `GutenbergCatalogSyncWorker.kt` (WorkManager) with WiFi + charging constraints, 24h interval
- [ ] T105 [P] Create `GutenbergCatalogDao.kt` Room DAO with FTS5 full-text search
- [ ] T106 [P] Create `GutenbergCatalogEntity.kt` Room entity with bookId, title, creator, language, subject, bookshelf, download URLs
- [ ] T107 [P] Create `GutenbergLocalSearch.kt` implementing `Searchable` with Room FTS5 query
- [ ] T108 [P] Create `GutenbergBookshelfParser.kt` implementing `Browsable` with OPDS feed parser
- [ ] T109 [P] Create `GutenbergBookMapper.kt` mapping catalog entries to `TorrentItem` / `TopicDetail`
- [ ] T110 [P] Create `GutenbergDownload.kt` implementing `Downloadable` with multi-format URL construction
- [ ] T111 [P] Create `GutenbergClient.kt` and `GutenbergClientFactory.kt`
- [ ] T112 [P] Write RDF catalog parser test with 100-entry fixture subset
- [ ] T113 [P] Write catalog sync worker test verifying scheduling and incremental update
- [ ] T114 [P] Write capability honesty test `GutenbergCapabilityTest`
- [ ] T115 Go API: Create `lava-api-go/internal/gutenberg/` with client, catalog, search, browse, book, download, opds
- [ ] T116 Go API: Register Project Gutenberg provider

### 2.9 Constitution Gate: Foundational Phase

- [ ] T117 Run `./gradlew test` after all provider modules added; verify all unit tests pass
- [ ] T118 Run `cd lava-api-go && make test` after all providers registered; verify all tests pass
- [ ] T119 Run `scripts/check-constitution.sh` verifying all 6 providers satisfy Capability Honesty (6.E)
- [ ] T120 Verify no new generic code was added to Lava repo that belongs in `vasic-digital` submodules (Decoupled Reusable Architecture check)

**Checkpoint**: All 4 providers implemented and tested on both Android and Go API. Database migrations written. SDK extensions complete. Constitution gates passed.

---

## Phase 3: User Story 1 - Credentials Management (Priority: P1) 🎯 MVP

**Goal**: Users can create, edit, and delete encrypted credentials in Settings, share them across providers, with Android Backup Service support.

**Independent Test**: Open Settings → create credential → edit → delete → force-stop → reopen → verify persistence.

### Tests for User Story 1

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T121 [P] [US1] Write `CredentialsViewModelTest` in `feature/credentials/src/test/kotlin/lava/credentials/CredentialsViewModelTest.kt` using real UseCase implementations wired to `CredentialRepositoryImpl` (Second Law compliance)
- [ ] T122 [P] [US1] Write `CredentialsScreenTest` Compose UI test in `feature/credentials/src/androidTest/kotlin/lava/credentials/CredentialsScreenTest.kt` verifying create, edit, delete flows
- [ ] T123 [P] [US1] Write Challenge Test `C9_CreateCredentialAndAssociateTest` in `app/src/androidTest/kotlin/lava/app/challenges/C9_CreateCredentialAndAssociateTest.kt`
- [ ] T124 [P] [US1] Write bluff-audit rehearsal for T121: mutate `CredentialRepositoryImpl.delete()` to no-op → verify ViewModel test fails → revert → document in commit

### Implementation for User Story 1

- [ ] T125 [P] [US1] Create `feature/credentials/build.gradle.kts` applying `lava.android.feature`
- [ ] T126 [P] [US1] Add `include(":feature:credentials")` to `settings.gradle.kts`
- [ ] T127 [P] [US1] Create `CredentialsState.kt` sealed interface in `feature/credentials/src/main/kotlin/lava/credentials/CredentialsState.kt`
- [ ] T128 [P] [US1] Create `CredentialsAction.kt` sealed interface in `feature/credentials/src/main/kotlin/lava/credentials/CredentialsAction.kt`
- [ ] T129 [P] [US1] Create `CredentialsSideEffect.kt` sealed interface in `feature/credentials/src/main/kotlin/lava/credentials/CredentialsSideEffect.kt`
- [ ] T130 [US1] Create `CredentialsViewModel.kt` `@HiltViewModel` implementing `ContainerHost<CredentialsState, CredentialsSideEffect>`
- [ ] T131 [P] [US1] Create `CredentialsScreen.kt` with credential list, type badges, provider association chips, edit/delete actions
- [ ] T132 [P] [US1] Create `CredentialCreateDialog.kt` bottom sheet with conditional fields based on `CredentialType`
- [ ] T133 [P] [US1] Create `CredentialEditDialog.kt` bottom sheet pre-populated with existing values
- [ ] T134 [P] [US1] Wire navigation from `feature:menu` to `feature:credentials` in `app/src/main/kotlin/lava/app/navigation/MobileNavigation.kt`
- [ ] T135 [US1] Update `core/credentials/impl/CredentialRepositoryImpl.kt` to support Android Backup Service inclusion via `backup_descriptor.xml`

**Checkpoint**: Credentials can be created, edited, deleted, shared across providers, and persist across app restarts with cloud backup.

---

## Phase 4: User Story 2 - Provider Login and Anonymous Access (Priority: P1)

**Goal**: Users select a provider on Login screen, associate credentials or choose anonymous mode, with redirect to Settings when no credentials exist.

**Independent Test**: Select NNMClub → choose credential → authenticate successfully. Select RuTor → check Anonymous → browse without auth.

### Tests for User Story 2

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T136 [P] [US2] Write `LoginViewModelTest` in `feature/login/src/test/kotlin/lava/login/LoginViewModelTest.kt` using real UseCase implementations
- [ ] T137 [P] [US2] Write Challenge Test `C5_AuthenticatedSearchOnNNMClubTest` in `app/src/androidTest/kotlin/lava/app/challenges/`
- [ ] T138 [P] [US2] Write Challenge Test `C4_AnonymousSearchOnRuTorTest` in `app/src/androidTest/kotlin/lava/app/challenges/`

### Implementation for User Story 2

- [ ] T139 [P] [US2] Update `LoginState.kt` in `feature/login/src/main/kotlin/lava/login/LoginState.kt` adding provider selection, credential dropdown, anonymous toggle states
- [ ] T140 [P] [US2] Update `LoginAction.kt` in `feature/login/src/main/kotlin/lava/login/LoginAction.kt` adding provider selection, credential selection, anonymous mode actions
- [ ] T141 [US2] Update `LoginViewModel.kt` in `feature/login/src/main/kotlin/lava/login/LoginViewModel.kt` to consume `CredentialRepository` and `ProviderConfigRepository`
- [ ] T142 [US2] Redesign `LoginScreen.kt` in `feature/login/src/main/kotlin/lava/login/LoginScreen.kt` with provider selection cards, credential dropdown, "Create New Credentials" button, anonymous toggle
- [ ] T143 [US2] Add redirect logic in `LoginScreen.kt`: if provider requires auth and no credentials exist, show message with button navigating to `feature:credentials`
- [ ] T144 [US2] Update `LavaTrackerSdk.kt` to accept `Credential` parameter for authenticated provider operations
- [ ] T145 [US2] Update `TrackerSettingsScreen.kt` in `feature/tracker_settings/src/main/kotlin/lava/tracker_settings/TrackerSettingsScreen.kt` adding per-provider credential selector and anonymous mode toggle

**Checkpoint**: Users can log into any provider with credentials or anonymous mode. Missing credentials redirect to Settings.

---

## Phase 5: User Story 3 - Unified Search Across All Providers (Priority: P1)

**Goal**: Search dispatches to all enabled providers concurrently, streams results in real-time with deduplication, provider badges, configurable timeouts, and offline cache.

**Independent Test**: Enter query → see results from 4+ providers with badges → sort by seeders → tap result → open detail.

### Tests for User Story 3

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T146 [P] [US3] Write `SearchResultViewModelTest` in `feature/search_result/src/test/kotlin/lava/search_result/SearchResultViewModelTest.kt` verifying concurrent dispatch, deduplication, and sorting
- **Note**: `DeduplicationEngineTest` (created in T050) is validated during this phase via integration with real provider search results.
- [ ] T148 [P] [US3] Write Challenge Test `C2_UnifiedSearchTest` in `app/src/androidTest/kotlin/lava/app/challenges/C2_UnifiedSearchTest.kt`
- [ ] T149 [P] [US3] Write Challenge Test `C3_ProviderSpecificSearchFilterTest` in `app/src/androidTest/kotlin/lava/app/challenges/`
- [ ] T150 [P] [US3] Write offline cache test verifying cached results served when device offline

### Implementation for User Story 3

- [ ] T151 [US3] Implement `searchAll()` in `core/tracker/client/LavaTrackerSdk.kt` using Kotlin coroutines `async/awaitAll` with `SharedFlow<SearchResultBatch>`
- [ ] T152 [P] [US3] Create `SearchResultViewModel.kt` in `feature/search_result/src/main/kotlin/lava/search_result/SearchResultViewModel.kt` merging batches, sorting, deduplicating in real-time
- [ ] T153 [P] [US3] Update `SearchScreen.kt` in `feature/search/src/main/kotlin/lava/search/SearchScreen.kt` with provider filter chips above search bar
- [ ] T154 [P] [US3] Update `SearchScreen.kt` with search button disabled state when no providers selected
- [ ] T155 [P] [US3] Create `UnifiedResultCard.kt` in `feature/search_result/src/main/kotlin/lava/search_result/UnifiedResultCard.kt` showing title, provider badge(s), metadata, expandable per-provider details
- [ ] T156 [P] [US3] Create `ProviderBadge.kt` in `core:designsystem` with color tokens for all 6 providers
- [ ] T157 [US3] Implement partial error UI: subtle notification banner when provider fails/times out with retry button
- [ ] T158 [US3] Implement incremental sort insertion: new results inserted into correct position without full list re-render
- [ ] T159 [US3] Implement offline search cache in `core/tracker/client/OfflineSearchCache.kt` using Room with 24h TTL
- [ ] T160 [US3] Add timeout configuration UI in `feature/tracker_settings` allowing per-provider timeout adjustment (2000ms–60000ms)
- [ ] T160a [US3] Implement cache quota enforcement in `core/tracker/client/OfflineSearchCache.kt`: max total cache size 50MB, LRU eviction when quota exceeded, automatic cleanup of expired entries on app startup
- [ ] T160b [P] [US3] Add `CacheQuotaExceededTest` verifying that inserting 51MB of cache data triggers eviction of oldest entries and preserves newest 50MB
- [ ] T160c [US3] Add one-time user notification in `SearchScreen.kt` when cache eviction occurs: "Older cached content has been cleared to free space"

**Checkpoint**: Unified search works across all 6 providers with real-time streaming, deduplication, badges, and offline cache.

---

## Phase 6: User Story 4 - Unified Forums and Categories (Priority: P1)

**Goal**: Unified Forums view showing provider-grouped content hierarchies with enable/disable per provider.

**Independent Test**: Open Forums → see RuTracker forum tree + Archive.org collections + Gutenberg bookshelves → tap category → see mixed results.

### Tests for User Story 4

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T161 [P] [US4] Write `ForumViewModelTest` in `feature/forum/src/test/kotlin/lava/forum/ForumViewModelTest.kt` verifying unified forum tree construction
- [ ] T162 [P] [US4] Write Challenge Test `C12_UnifiedForumsTest` in `app/src/androidTest/kotlin/lava/app/challenges/C12_UnifiedForumsTest.kt`

### Implementation for User Story 4

- [ ] T163 [US4] Implement `browseAll()` in `core/tracker/client/LavaTrackerSdk.kt` for concurrent forum/category browsing
- [ ] T164 [P] [US4] Update `ForumViewModel.kt` in `feature/forum/src/main/kotlin/lava/forum/ForumViewModel.kt` to aggregate forum trees from all enabled providers
- [ ] T165 [P] [US4] Redesign `ForumScreen.kt` in `feature/forum/src/main/kotlin/lava/forum/ForumScreen.kt` with provider-grouped sections, expandable hierarchies, source indicators
- [ ] T166 [P] [US4] Add provider filter tabs in `ForumScreen.kt` for enabling/disabling individual providers
- [ ] T167 [US4] Implement "at least one provider enabled" guard: prevent disabling last provider with explanatory message
- [ ] T168 [US4] Handle providers without browsable content: show "No browsable content" or hide section

**Checkpoint**: Forums show unified view across all providers with proper grouping and filtering.

---

## Phase 7: User Story 5 - Provider Configuration and Persistence (Priority: P1)

**Goal**: All provider selections, filters, sort orders, and preferences persist across sessions with v8 migration.

**Independent Test**: Disable Internet Archive from Search → enable only Gutenberg in Forums → force-stop → reopen → configuration intact.

### Tests for User Story 5

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T169 [P] [US5] Write `ProviderConfigRepositoryTest` in `core/preferences/src/test/kotlin/lava/preferences/ProviderConfigRepositoryTest.kt` verifying persistence across simulated restarts
- [ ] T170 [P] [US5] Write migration test verifying v7→v8 preserves existing tracker_mirror_health and tracker_mirror_user data

### Implementation for User Story 5

- [ ] T171 [P] [US5] Create `SearchProviderSelectionsRepository.kt` in `core/preferences/src/main/kotlin/lava/preferences/SearchProviderSelectionsRepository.kt`
- [ ] T172 [P] [US5] Create `ForumProviderSelectionsRepository.kt` in `core/preferences/src/main/kotlin/lava/preferences/ForumProviderSelectionsRepository.kt`
- [ ] T173 [US5] Implement filter state persistence: sort order, time period, category filters per provider in `ProviderConfigRepository`
- [ ] T174 [US5] Implement provider display order persistence with drag-and-drop support in `TrackerSettingsScreen`
- [ ] T175 [US5] Implement anonymous mode persistence per provider
- [ ] T176 [US5] Implement credential-provider association persistence
- [ ] T177 [US5] Write and verify `MIGRATION_7_8` in `core/database/src/main/kotlin/lava/database/AppDatabase.kt`
- [ ] T178 [US5] Update `core/database/schemas/lava.database.AppDatabase/8.json` with new schema
- [ ] T178a [US5] Extend `ProviderConfigRepository` with `getPreferredProvider(contentType: String): String?` and `setPreferredProvider(contentType: String, providerId: String)` methods
- [ ] T178b [US5] Add preferred provider selector UI in `TrackerSettingsScreen.kt` with section "Preferred provider for downloads" allowing user to set default per content type
- [ ] T178c [P] [US5] Update `ProviderBadge.kt` in `core:designsystem` to accept `isPreferred: Boolean` parameter; when true, render with filled style + star icon instead of outlined style
- [ ] T178d [P] [US5] Update `UnifiedResultCard.kt` to pass `isPreferred = occurrence.providerId == preferredProvider` to `ProviderBadge`

**Checkpoint**: All user selections persist correctly across app restarts. Migration from v7 preserves existing data.

---

## Phase 8: User Story 6 - Content Download from Any Provider (Priority: P2)

**Goal**: Users download content from any provider: torrents from trackers, HTTP files from digital libraries, with format selection for Gutenberg.

**Independent Test**: Find torrent on NNMClub → download .torrent. Find book on Gutenberg → download EPUB.

### Tests for User Story 8

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T179 [P] [US6] Write `DownloadViewModelTest` in `feature/topic/src/test/kotlin/lava/topic/DownloadViewModelTest.kt` verifying download flow with real repository
- [ ] T180 [P] [US6] Write Challenge Test `C6_LoginAndDownloadTest` in `app/src/androidTest/kotlin/lava/app/challenges/C6_LoginAndDownloadTest.kt`
- [ ] T181 [P] [US6] Write Challenge Test `C8_GutenbergSearchAndDownloadTest` in `app/src/androidTest/kotlin/lava/app/challenges/C8_GutenbergSearchAndDownloadTest.kt`

### Implementation for User Story 6

- [ ] T182 [P] [US6] Update `TopicScreen.kt` in `feature/topic/src/main/kotlin/lava/topic/TopicScreen.kt` showing context-aware download actions based on provider type
- [ ] T183 [P] [US6] Create `TorrentDownloadAction.kt` in `feature/topic/src/main/kotlin/lava/topic/TorrentDownloadAction.kt` for .torrent and magnet links
- [ ] T184 [P] [US6] Create `HttpDownloadAction.kt` in `feature/topic/src/main/kotlin/lava/topic/HttpDownloadAction.kt` for HTTP file downloads
- [ ] T185 [P] [US6] Create `GutenbergFormatSelector.kt` in `feature/topic/src/main/kotlin/lava/topic/GutenbergFormatSelector.kt` with EPUB (images/no images), Kindle, HTML, TXT options
- [ ] T186 [US6] Implement auth-required download guard: show error dialog with "Update Credentials" / "Use Anonymously" options when unauthenticated
- [ ] T187 [US6] Implement download retry with exponential backoff on network errors

**Checkpoint**: Downloads work across all provider types with proper error handling and format selection.

---

## Phase 9: User Story 7 - Modern UI/UX with Comprehensive Error Handling (Priority: P1)

**Goal**: Material Design 3 theme with dynamic color, skeleton loading, comprehensive error states, dark theme, accessibility.

**Independent Test**: Navigate Search → Forums → Settings → Login with consistent styling, loading skeletons, and helpful error messages.

### Tests for User Story 9

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T188 [P] [US7] Write Compose UI tests verifying loading skeletons render correctly in `feature/search/src/androidTest/`
- [ ] T189 [P] [US7] Write accessibility test verifying content descriptions and touch targets using `composeTestRule` in `app/src/androidTest/`
- [ ] T190 [P] [US7] Write dark theme contrast test verifying provider badge distinguishability

### Implementation for User Story 9

- [ ] T191 [P] [US7] Update `LavaTheme.kt` in `core/designsystem/src/main/kotlin/lava/designsystem/LavaTheme.kt` with Material 3 dynamic color support (Android 12+) and fallback static theme
- [ ] T192 [P] [US7] Create provider color tokens in `core/designsystem/src/main/kotlin/lava/designsystem/ProviderColors.kt`: RuTracker blue, RuTor red, NNMClub amber, Kinozal purple, Archive.org green, Gutenberg indigo
- [ ] T193 [P] [US7] Create `LoadingSkeleton.kt` in `core/designsystem/src/main/kotlin/lava/designsystem/LoadingSkeleton.kt` for Search, Forum, Topic, Settings screens
- [ ] T194 [P] [US7] Implement network error Snackbar with retry action in `core:ui` error handling utilities
- [ ] T195 [P] [US7] Implement auth error dialog in `core:ui` with "Update Credentials", "Switch Account", "Use Anonymously" options
- [ ] T196 [P] [US7] Implement provider unavailable badge with cross-provider fallback suggestion in `core:ui`
- [ ] T197 [P] [US7] Implement rate limit message with estimated wait time in `core:ui`
- [ ] T198 [P] [US7] Implement partial results banner in `core:ui`
- [ ] T199 [P] [US7] Implement empty state illustrations with contextual messaging per provider in `core:ui`
- [ ] T200 [P] [US7] Add smooth transitions between screens with shared element transitions in `core:navigation`
- [ ] T201 [US7] Run accessibility audit: verify all interactive elements have `contentDescription`, minimum 48dp touch targets, color contrast ratios ≥ 4.5:1
- [ ] T202 [US7] Verify dark theme support with proper color mapping for all provider badges
- [ ] T202a [P] [US7] Create `CapabilityAwareActionProvider.kt` in `core/ui/src/main/kotlin/lava/ui/CapabilityAwareActionProvider.kt` that inspects the active provider's descriptor and returns enabled/disabled state + tooltip text for each action (download, favorite, comment, etc.)
- [ ] T202b [P] [US7] Update `TopicScreen.kt` to use `CapabilityAwareActionProvider` — hide or disable actions the provider does not support, showing tooltip "Not available for [ProviderName]" on long-press
- [ ] T202c [P] [US7] Write `CapabilityAwareActionProviderTest` verifying that a provider without FAVORITES capability returns disabled state for "Add to Favorites" action

**Checkpoint**: All screens follow Material 3, show proper loading/error states, support dark theme, and meet accessibility standards.

---

## Phase 10: Polish & Cross-Cutting Concerns

**Purpose**: Testing, documentation, evidence collection, tag gate

### Challenge Tests & Anti-Bluff Verification

- [ ] T203 [P] Execute C1 on real device: `app/src/androidTest/kotlin/lava/app/challenges/C1_AppLaunchAndProviderListTest.kt` verifying all 6 providers registered; record attestation
- [ ] T204 [P] Execute C2 on real device: `C2_UnifiedSearchTest.kt` verifying concurrent dispatch, result streaming, provider badges; record attestation
- [ ] T205 [P] Execute C3 on real device: `C3_ProviderSpecificSearchFilterTest.kt`; record attestation
- [ ] T206 [P] Execute C4 on real device: `C4_AnonymousSearchOnRuTorTest.kt`; record attestation
- [ ] T207 [P] Execute C5 on real device: `C5_AuthenticatedSearchOnNNMClubTest.kt`; record attestation
- [ ] T208 [P] Execute C6 on real device: `C6_LoginAndDownloadTest.kt`; record attestation
- [ ] T209 [P] Execute C7 on real device: `C7_ArchiveOrgBrowseTest.kt`; record attestation
- [ ] T210 [P] Execute C8 on real device: `C8_GutenbergSearchAndDownloadTest.kt`; record attestation
- [ ] T211 [P] Execute C9 on real device: `C9_CreateCredentialAndAssociateTest.kt`; record attestation
- [ ] T212 [P] Execute C10 on real device: `C10_ShareCredentialAcrossProvidersTest.kt`; record attestation
- [ ] T213 [P] Execute C11 on real device: `C11_CrossProviderFallbackTest.kt`; record attestation
- [ ] T214 [P] Execute C12 on real device: `C12_UnifiedForumsTest.kt`; record attestation
- [ ] T215 Run end-of-phase bluff hunt on 5 random new test files; output to `.lava-ci-evidence/bluff-hunt/2026-05-XX.json`

### Documentation

- [ ] T216 Write user manual: Credentials Management (`docs/user-manuals/credentials-management.md`)
- [ ] T217 Write user manual: Provider Selection and Configuration (`docs/user-manuals/provider-configuration.md`)
- [ ] T218 Write user manual: Unified Search and Forums (`docs/user-manuals/unified-search-forums.md`)
- [ ] T219 Write API documentation: `/v1/{provider}/...` route reference (`lava-api-go/docs/api-reference.md`)
- [ ] T220 Write developer guide: Step-by-step recipe for adding a seventh provider (`docs/developer-guide/adding-a-provider.md`)
- [ ] T221 Update `README.md` with multi-provider architecture overview
- [ ] T222 Update `CHANGELOG.md` with all changes

### Evidence & Tag Gate

- [ ] T223 Create real-device attestation: `.lava-ci-evidence/Lava-Android-1.3.0-1030/real-device-verification.md` with device model, Android version, command-by-command checklist, ≥3 screenshots
- [ ] T224 Create per-Challenge attestation files: `.lava-ci-evidence/Lava-Android-1.3.0-1030/challenges/C{1..12}.json` with status VERIFIED
- [ ] T225 Run `scripts/tag.sh` to verify all evidence gates pass
- [ ] T226 Performance baseline: Measure concurrent search P95 latency across all 6 providers; document in `.lava-ci-evidence/Lava-Android-1.3.0-1030/performance-baseline.json`

### Final Checks

- [ ] T227 Run `./gradlew spotlessCheck` across entire project; fix any violations
- [ ] T228 Run `./gradlew test` across all modules; verify 100% pass
- [ ] T229 Run `cd lava-api-go && make ci`; verify all gates pass
- [ ] T230 Verify `scripts/check-constitution.sh` passes (Capability Honesty, no hosted CI files, no forbidden test patterns)

**Checkpoint**: All 12 Challenge Tests pass on real device. Documentation complete. Evidence pack ready. Tag gate passes.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies
- **Foundational (Phase 2)**: Depends on Setup. BLOCKS all user stories. Provider implementations (T052–T116) can run in parallel within Phase 2.
- **User Stories (Phase 3–9)**: All depend on Foundational completion
  - US1 (Credentials UI) can start immediately after Foundational
  - US2 (Provider Login) depends on US1 (credentials repository ready)
  - US3 (Unified Search), US4 (Unified Forums), US5 (Config) can start in parallel after US2
  - US6 (Download) depends on US3 (search results provide download targets)
  - US7 (UI/UX) can run in parallel with US3–US6 but should integrate after
- **Polish (Phase 10)**: Depends on all user stories complete

### User Story Dependencies

| Story | Depends On | Can Parallel With |
|-------|-----------|-------------------|
| US1 Credentials | Phase 2 | Nothing (first user story) |
| US2 Provider Login | US1 | Nothing |
| US3 Unified Search | US2 | US4, US5, US7 |
| US4 Unified Forums | US2 | US3, US5, US7 |
| US5 Provider Config | US2 | US3, US4, US7 |
| US6 Content Download | US3 | US7 |
| US7 Modern UI/UX | US2 | US3, US4, US5, US6 |

### Within Each User Story

- Tests MUST be written and FAIL before implementation (TDD per spec requirement)
- Models before services
- Services before UI/screens
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- All 4 provider implementations in Phase 2 can run in parallel (different teams/developers)
- All Setup tasks in Phase 1 can run in parallel
- Test tasks within each story marked [P] can run in parallel
- Challenge Tests T203–T214 in Phase 10 can run in parallel (different test scenarios)
- Documentation tasks T216–T222 in Phase 10 can run in parallel

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (all 4 providers + core infrastructure)
3. Complete Phase 3: User Story 1 (Credentials Management)
4. **STOP and VALIDATE**: Test credentials CRUD on real device
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add US1 (Credentials) → Test independently → Deploy/Demo (MVP!)
3. Add US2 (Provider Login) → Test independently → Deploy/Demo
4. Add US3 (Unified Search) + US4 (Unified Forums) → Test independently → Deploy/Demo
5. Add US5 (Config) + US6 (Download) → Test independently → Deploy/Demo
6. Add US7 (UI/UX) → Test independently → Deploy/Demo
7. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: US1 (Credentials) + US2 (Login)
   - Developer B: US3 (Search) + US4 (Forums)
   - Developer C: US5 (Config) + US6 (Download) + US7 (UI/UX)
3. All developers collaborate on Phase 10 (Challenge Tests, docs, evidence)

---

## Task Count Summary

| Phase | Tasks | Description |
|-------|-------|-------------|
| Phase 1: Setup | 13 | Baseline audit, fixtures, test infrastructure |
| Phase 2: Foundational | 107 | Go API abstraction, DB migration, credentials core, 4 providers |
| Phase 3: US1 Credentials | 15 | Credentials UI, ViewModel, tests, Challenge Test C9 |
| Phase 4: US2 Provider Login | 10 | Login redesign, anonymous mode, Challenge Tests C4/C5 |
| Phase 5: US3 Unified Search | 15 | Concurrent search, deduplication, badges, offline cache, C2/C3 |
| Phase 6: US4 Unified Forums | 8 | Unified forum view, provider grouping, C12 |
| Phase 7: US5 Provider Config | 10 | Persistence, migration, display order |
| Phase 8: US6 Content Download | 9 | Download actions, format selector, C6/C8 |
| Phase 9: US7 Modern UI/UX | 15 | Material 3, error states, accessibility, dark theme |
| Phase 10: Polish | 28 | 12 Challenge Tests, docs, evidence, tag gate |
| **Total** | **230** | |

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing (TDD)
- Commit after each task or logical group
- Every test commit MUST carry a Bluff-Audit stamp per Seventh Law clause 1
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence
