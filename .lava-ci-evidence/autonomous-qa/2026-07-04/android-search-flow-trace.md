# Android Client Search Flow Trace — Phase 1 Evidence

**Scope:** Lava Android client search request path, from user typing a query in `SearchInputScreen` to the network request leaving the app.  
**Goal:** Identify every place where the flow can return empty results or fail silently, with exact file:line references, data shapes, risk points, §6.R hardcoded-value violations, and reproducible bug scenarios.  
**Constraint:** No production-code edits and no new tests in this phase. Evidence only.

---

## 1. High-level flow

```
SearchInputScreen
  └─ SearchInputField onSearch / onSubmitClick
       └─ SearchInputViewModel.perform(action)  (SearchInputViewModel.kt:111-122)
            └─ onSubmit()  (SearchInputViewModel.kt:164-170)
                 ├─ saveSuggestUseCase(query)
                 ├─ resolveProviderIdsForSubmit()  (SearchInputViewModel.kt:157-162)
                 └─ OpenSearch(filter) side effect  (SearchInputNavigation.kt:20-191)
                      └─ SearchResultScreen
                           └─ SearchResultViewModel.onCreate()  (SearchResultViewModel.kt:94-114)
                                ├─ filter.providerIds == null  →  legacy single-tracker paging
                                │     └─ ObserveSearchPagingDataUseCase
                                │           └─ SearchServiceImpl
                                │                 └─ NetworkApi.getSearchPage(...)  (ProxyNetworkApi.kt:76-96)
                                └─ filter.providerIds != null  →  multi-provider streaming
                                      └─ observeStreamMultiSearch(filter)  (SearchResultViewModel.kt:138-242)
                                            └─ LavaTrackerSdk.streamMultiSearch(...)  (LavaTrackerSdk.kt:856-966)
                                                  ├─ clientFor(id) → DefaultTrackerRegistry.get(id, config)  (TrackerClientModule.kt:274-333)
                                                  │     ├─ bundled factory (RuTracker / RuTor / etc.)
                                                  │     └─ ApiBackedTrackerClient for dynamic providers
                                                  └─ feature.search(request, page)  (ApiBackedTrackerClient.kt:165-181)
                                                        └─ OkHttp GET /v1/{trackerId}/search
```

---

## 2. Entry point — UI / search input

### 2.1 `SearchInputScreen.kt`
- **File:** `feature/search_input/src/main/kotlin/lava/search/input/SearchInputScreen.kt`
- The search field and provider chip bar are rendered here. `SearchInputField` dispatches `SearchInputAction.SubmitClick` when the user presses the search IME action.
- **Risk:** If the user has no onboarded providers (or the provider list is empty), the chip bar shows nothing and `SubmitClick` still fires.

### 2.2 `SearchInputViewModel.kt`
- **File:** `feature/search_input/src/main/kotlin/lava/search/input/SearchInputViewModel.kt`

| Function | Lines | Behavior | Risk |
|---|---|---|---|
| `loadOnboardedChips()` | 81-96 | Reads `ProviderConfigRepository.observeAll()`, keeps providers where `searchEnabled && isEnabled`, sorts by id. | If all providers are `searchEnabled=false` or `isEnabled=false`, the chip list is empty but search still possible. |
| `resolveProviderIdsForSubmit()` | 157-162 | Returns explicit selected ids, or repopulates from `ProviderConfigRepository` if the selected set is empty. | Silent fallback can search providers the user did **not** intend. |
| `onSubmit()` | 164-170 | Trims query, calls `saveSuggestUseCase`, resolves ids, posts `OpenSearch(filter)`. | Empty trimmed query is allowed through; downstream may return empty results with no user-visible warning. |
| `perform(action)` | 111-122 | Action dispatch. | No telemetry on unknown / unhandled actions. |

**Data class:** `Filter`

```kotlin
data class Filter(
    val query: String? = null,
    val sort: Sort = Sort.DATE,
    val order: Order = Order.DESCENDING,
    val period: Period = Period.ALL_TIME,
    val categories: List<String> = emptyList(),
    val author: String? = null,
    val providerIds: List<String>? = null,
)
```

### 2.3 `SearchInputNavigation.kt`
- **File:** `feature/search_input/src/main/kotlin/lava/search/input/SearchInputNavigation.kt`
- Serializes `Filter` into route query parameters using hardcoded numeric strings.

**§6.R violations:**

| Literal | Location | Meaning |
|---|---|---|
| `"1"` | `SearchInputNavigation.kt` | `Sort.DATE` query value |
| `"2"` | `SearchInputNavigation.kt` | `Sort.SEEDS` query value |
| `"3"` | `SearchInputNavigation.kt` | `Sort.SIZE` query value |
| `"4"` | `SearchInputNavigation.kt` | `Sort.AUTHOR` query value |
| `"1"` | `SearchInputNavigation.kt` | `Order.ASCENDING` query value |
| `"2"` | `SearchInputNavigation.kt` | `Order.DESCENDING` query value |
| `"1"`–`"8"` | `SearchInputNavigation.kt` | `Period.TODAY` … `Period.ALL_TIME` query values |

These mapping tables are duplicated in `SearchResultNavigation.kt` (see §3.2).

---

## 3. Result screen — navigation / argument decoding

### 3.1 `SearchResultNavigation.kt`
- **File:** `feature/search_result/src/main/kotlin/lava/search/result/SearchResultNavigation.kt`

| Function / property | Lines | Behavior | Risk |
|---|---|---|---|
| `ProviderIdsKey = "pids"` | ~21-208 | Hardcoded argument key for provider ids. | If the key ever changes in the search input route, the ids deserialize to null and the legacy paging path is used. |
| `SavedStateHandle.filter` | 127-139 | Deserializes `providerIds` from a comma-separated string; returns null if the list is empty. | A null `providerIds` silently switches the ViewModel to the legacy single-tracker path, even when the user picked multiple providers. |
| Sort/Order/Period mappings | 21-208 | Same hardcoded numeric strings as `SearchInputNavigation.kt`. | §6.R violations; mismatch between input and result mapping would silently drop filters. |

### 3.2 `SearchPageState.kt`
- **File:** `feature/search_result/src/main/kotlin/lava/search/result/SearchPageState.kt`
- Defines `SearchResultContent` states: `Initial`, `Empty`, `Unauthorized`, `Error(reason)`, `Content(torrents, categories)`, `Streaming(items, activeProviders)`.
- `filterProviderChipIds` (lines 99-100) returns `filter.providerIds?.distinct()?.sorted().orEmpty()`.  
  **Risk:** If `providerIds` is null, the chip bar is empty but the result list may still be rendered from the legacy path, giving inconsistent UI.

---

## 4. Result ViewModel — the fork in the road

### 4.1 `SearchResultViewModel.kt`
- **File:** `feature/search_result/src/main/kotlin/lava/search/result/SearchResultViewModel.kt`

| Function | Lines | Behavior | Risk |
|---|---|---|---|
| `onCreate()` | 94-114 | Routes to `observePagingData()` when `filter.providerIds == null`, else to `observeStreamMultiSearch(filter)`. | The two paths have different filter semantics; switching between them is silent. |
| `observeStreamMultiSearch(filter)` | 138-242 | Builds `SearchRequest(query = filter.query.orEmpty(), sort = SortField.DATE, sortOrder = SortOrder.DESCENDING)` and calls `LavaTrackerSdk.streamMultiSearch(request, filter.providerIds!!)`. | **Concrete bug: all filter fields except `query` are ignored.** `filter.sort`, `filter.order`, `filter.period`, `filter.categories`, and `filter.author` are discarded. |
| `handleStreamEnd()` | 454-505 | Decides between `Content`, `Empty`, and `Error`; distinguishes `allProvidersFailed` from partial failure. | Partial-failure events may be rendered as a non-empty list while some providers silently failed. |
| `SEARCH_TIMEOUT_MS` | 613-627 | Hardcoded `25_000L` timeout constant. | §6.R schedule/timeout literal. |

**Concrete bug 1 — multi-provider filter drop:**

```kotlin
// SearchResultViewModel.kt:165-169
val request = SearchRequest(
    query = filter.query.orEmpty(),
    sort = SortField.DATE,
    sortOrder = SortOrder.DESCENDING,
)
```

`filter.sort`, `filter.order`, `filter.period`, `filter.categories`, and `filter.author` are present in the `Filter` object but never copied into `SearchRequest`. The user can change sort to Seeds, order to Ascending, period to Today, or add category/author filters, and the multi-provider path will still issue the default `DATE/DESCENDING/ALL_TIME` request.

**Reproducible scenario:**
1. Open search input.
2. Type "matrix".
3. Change sort to "Seeds".
4. Change order to "Ascending".
5. Change period to "Today".
6. Select two providers and submit.
7. Expected: results sorted by seeds, ascending, from today.  
   Actual: results sorted by date, descending, all time, because `SearchResultViewModel` and `ApiBackedTrackerClient` drop the other fields.

---

## 5. Legacy single-tracker path

### 5.1 `ObserveSearchPagingDataUseCase.kt`
- **File:** `core/domain/src/main/kotlin/lava/domain/usecase/ObserveSearchPagingDataUseCase.kt`
- Wraps `SearchServiceImpl` in a `PagingDataLoader`.
- **Risk:** Paging refresh failures are surfaced through `PagingData` state, but the first-page empty/error boundary depends on the service return.

### 5.2 `SearchServiceImpl.kt`
- **File:** `core/data/src/main/kotlin/lava/data/impl/service/SearchServiceImpl.kt`
- Maps `Filter` fields to `networkApi.getSearchPage(...)`.
- This path **does** honor `query`, `sort`, `order`, `period`, `author`, and `categories`.
- **Risk:** If the active `Endpoint` is `GoApi` but `NetworkApiRepositoryImpl` routes to `ProxyNetworkApi` with the strict TLS client, the LAN self-signed cert handshake fails (historical bug class; now mitigated by `@Named("lan")`).

### 5.3 `ProxyNetworkApi.kt`
- **File:** `core/network/impl/src/main/kotlin/lava/network/impl/ProxyNetworkApi.kt`
- `getSearchPage(...)` at lines 76-96 issues `GET /search` with query parameters.
- **Risk:** Any `null` parameter is omitted via Ktor; the backend then uses its own default, which may differ from the UI default. No client-side contract test asserts the parameter set.

---

## 6. Multi-provider SDK path

### 6.1 `LavaTrackerSdk.kt`
- **File:** `core/tracker/client/src/main/kotlin/lava/tracker/client/LavaTrackerSdk.kt`

| Function | Lines | Behavior | Risk |
|---|---|---|---|
| `streamMultiSearch` | 856-966 | `channelFlow` launches one coroutine per provider id. Resolves descriptor, checks `TrackerCapability.SEARCH`, gets `SearchableTracker` feature, calls `search(request, page)`, emits `MultiSearchEvent`. | If a descriptor lacks `SEARCH` capability, the provider is silently skipped (no result, no error). |
| `runOneProvider` | 989-1014 | Resolves provider and invokes `feature.search(request, page)`. | Swallowed exceptions become `SearchResult.Error` events, but if the consumer only looks at `Content`/`Empty`, partial failures are invisible. |

**Capability Honesty check:** `TrackerCapability.SEARCH` is the gate. A descriptor that declares it but returns `null` from `getFeature<SearchableTracker>()` violates clause 6.E; current bundled providers appear to satisfy it, but dynamic `ApiBackedTrackerClient` providers rely on the descriptor sent by the LAN API.

### 6.2 `ApiBackedTrackerClient.kt`
- **File:** `core/tracker/client/src/main/kotlin/lava/tracker/client/ApiBackedTrackerClient.kt`

| Function | Lines | Behavior | Risk |
|---|---|---|---|
| `searchable.search` | 165-181 | Builds URL with only `query`, `page`, `author`, and `categories` query params. | **Concrete bug 2: `SearchRequest.sort`, `sortOrder`, and `period` are ignored on the wire.** |
| `withAuth()` | 139-161 | Adds `Lava-Auth` and `Auth-Token` headers. | If `ProviderSessionTokenHolder.tokenFor(id)` is null for an auth-required provider, the request is sent unauthenticated and may return empty results or 401. |

**Concrete bug 2 — wire request drops sort/order/period:**

```kotlin
// ApiBackedTrackerClient.kt:165-181
override suspend fun search(request: SearchRequest, page: Int): SearchResult {
    val url = apiBaseUrl.toHttpUrl().newBuilder()
        .addPathSegment("v1")
        .addPathSegment(descriptor.trackerId)
        .addPathSegment("search")
        .addQueryParameter("query", request.query)
        .addQueryParameter("page", page.toString())
        .apply {
            request.author?.let { addQueryParameter("author", it) }
            request.categories.takeIf { it.isNotEmpty() }?.let {
                addQueryParameter("categories", it.joinToString(","))
            }
        }
        .build()
    ...
}
```

Even if `SearchResultViewModel` passed `sort`, `sortOrder`, and `period`, this builder would not add them to the GET URL. The Go API backend would use its defaults, again ignoring the user's choices.

### 6.3 `SearchRequest.kt`
- **File:** `core/tracker/api/src/main/kotlin/lava/tracker/api/model/SearchRequest.kt`

```kotlin
data class SearchRequest(
    val query: String,
    val categories: List<String> = emptyList(),
    val sort: SortField = SortField.DATE,
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    val author: String? = null,
    val period: TimePeriod? = null,
)
```

The model supports sort/order/period, so the omission in `SearchResultViewModel` and `ApiBackedTrackerClient` is a plain wiring gap, not a model limitation.

### 6.4 `DeduplicationEngine.kt`
- **File:** `core/tracker/client/src/main/kotlin/lava/tracker/client/dedup/DeduplicationEngine.kt`
- Merges results by info-hash, title+size tolerance, or exact title match.
- **Risk:** Two different providers returning the same torrent can collapse to a single item. This is by design but can make the result list look unexpectedly short after multi-provider search, even when every provider returned matches.

### 6.5 `ProviderSessionTokenHolder.kt`
- **File:** `core/tracker/client/src/main/kotlin/lava/tracker/client/ProviderSessionTokenHolder.kt`
- Holds per-provider session tokens for authenticated providers.
- **Risk:** If a token is never written (login not completed, or write path misses a provider), `Auth-Token` is omitted. For providers that require authentication, the Go API returns empty or 401, and the UI may render `Empty` instead of `Unauthorized`.

---

## 7. Network construction and routing

### 7.1 `TrackerClientModule.kt`
- **File:** `core/tracker/client/src/main/kotlin/lava/tracker/client/di/TrackerClientModule.kt`

| Item | Lines | Behavior | Risk |
|---|---|---|---|
| `provideTrackerRegistry` | 274-333 | Registers bundled factories and a dynamic `ApiBackedTrackerClient` factory. | `apiBaseUrl = ApiBaseUrlHolder.current()`; if the holder is empty (e.g. onboarding not completed), the client is built with a null/empty base URL and every request fails. |
| `@Named("lan")` OkHttpClient | 292 | Used for LAN Go API calls. | Correctly permissive for self-signed certs. Using the strict client here is a known historical failure mode. |
| `sessionToken` | 330 | Read late-bound from `ProviderSessionTokenHolder.tokenFor(descriptor.trackerId)`. | See §6.5. |

### 7.2 `NetworkApiRepositoryImpl.kt`
- **File:** `core/network/impl/src/main/kotlin/lava/network/data/NetworkApiRepositoryImpl.kt`
- Selects between `Endpoint.GoApi` and `Endpoint.RutrackerEndpoint`.
- For LAN mirror entries, parses host:port and defaults to `8080` (`LEGACY_LAN_PROXY_PORT`).
- **§6.R risk:** `LEGACY_LAN_PROXY_PORT = 8080` is a hardcoded port constant. The Go API default of 8443 is also wired inline at call sites.

---

## 8. Risk summary — empty results / silent failure

| # | Location | Failure mode | User-visible outcome | Root cause class |
|---|---|---|---|---|
| 1 | `SearchInputViewModel.kt:157-162` | Empty/no provider selection falls back to all search-enabled providers | User searches unintended providers | Silent fallback |
| 2 | `SearchResultNavigation.kt:127-139` | `providerIds` deserializes to null when empty string | Switches to legacy single-tracker path silently | Argument decoding |
| 3 | `SearchResultViewModel.kt:165-169` | `Filter.sort/order/period/categories/author` ignored in `SearchRequest` | Sort/period/author filters don't work | **Concrete bug** |
| 4 | `ApiBackedTrackerClient.kt:165-181` | `sort`, `sortOrder`, `period` omitted from wire URL | Same as #3, even if ViewModel passed them | **Concrete bug** |
| 5 | `LavaTrackerSdk.kt:856-966` | Provider lacking `SEARCH` capability skipped silently | Fewer results than selected providers | Capability gate |
| 6 | `ApiBackedTrackerClient.kt:139-161` | `Auth-Token` missing when session token null | Auth-required provider returns empty/401 | Auth wiring |
| 7 | `TrackerClientModule.kt:316-322` | `ApiBaseUrlHolder.current()` empty/null | All dynamic-provider requests fail | Uninitialized holder |
| 8 | `DeduplicationEngine.kt` | Duplicate results collapsed | Result count lower than sum of provider results | By-design deduplication |
| 9 | `SearchResultViewModel.kt:454-505` | Partial provider failures treated as non-empty content | User sees some results while other providers failed silently | Partial-failure UX |
| 10 | `SearchInputViewModel.kt:164-170` | Empty trimmed query allowed | Empty query sent; backend returns empty | Input validation gap |

---

## 9. §6.R hardcoded literals catalog

| Literal | File | Line(s) | Classification |
|---|---|---|---|
| Sort/Order/Period numeric mapping (`"1"`, `"2"`, …) | `feature/search_input/src/main/kotlin/lava/search/input/SearchInputNavigation.kt` | 21-191 | Domain literals |
| Sort/Order/Period numeric mapping (duplicate) | `feature/search_result/src/main/kotlin/lava/search/result/SearchResultNavigation.kt` | 21-208 | Domain literals |
| `ProviderIdsKey = "pids"` | `feature/search_result/src/main/kotlin/lava/search/result/SearchResultNavigation.kt` | 21-208 | Argument key |
| `SEARCH_TIMEOUT_MS = 25_000L` | `feature/search_result/src/main/kotlin/lava/search/result/SearchResultViewModel.kt` | 613-627 | Schedule/timeout |
| `LEGACY_LAN_PROXY_PORT = 8080` | `core/network/impl/src/main/kotlin/lava/network/data/NetworkApiRepositoryImpl.kt` | 95 | Network port |
| Default `SortField.DATE`, `SortOrder.DESCENDING` | `core/tracker/api/src/main/kotlin/lava/tracker/api/model/SearchRequest.kt` | 5-12 | Domain defaults |
| Hardcoded user-agent string | `core/tracker/client/src/main/kotlin/lava/tracker/client/di/TrackerClientModule.kt` | 118 | Header value |

The navigation mapping literals are the clearest §6.R violations: stable domain constants are embedded as raw strings in two UI navigation files.

---

## 10. Concrete bug scenario (reproducible)

### Scenario: "Sort by Seeds / Today filter does nothing in multi-provider search"

**Steps:**
1. Launch app and complete onboarding with at least two providers that expose `SEARCH` (e.g. RuTracker and RuTor).
2. Navigate to search.
3. Type query `"linux"`.
4. Tap the sort chip and choose **Seeds**.
5. Tap the order chip and choose **Ascending**.
6. Tap the period chip and choose **Today**.
7. Ensure both providers are selected and submit.

**Expected request:**
```
GET /v1/rutracker/search?query=linux&sort=seeds&order=asc&period=today&page=0
GET /v1/rutor/search?query=linux&sort=seeds&order=asc&period=today&page=0
```

**Actual request (evidence from code):**
```
GET /v1/rutracker/search?query=linux&page=0
GET /v1/rutor/search?query=linux&page=0
```

Because:
- `SearchResultViewModel.kt:165-169` constructs `SearchRequest(query = "linux", sort = DATE, sortOrder = DESCENDING)` and ignores `filter.sort`, `filter.order`, `filter.period`.
- `ApiBackedTrackerClient.kt:165-181` does not append `sort`, `order`, or `period` even if they were present.

**Observed user outcome:** The result list is sorted by date descending and covers all time, regardless of the user's choices. No error is shown, so the user believes there are no seeded Linux torrents from today.

---

## 11. Additional silent-failure risks

- **Null `Filter.query`:** `onSubmit()` trims the query but does not block an empty string; `SearchRequest(query = "")` is sent and likely returns empty.
- **Provider ID serialization mismatch:** `ProviderIdsKey = "pids"` is a hardcoded contract between `SearchInputNavigation` and `SearchResultNavigation`. A future change on one side breaks the other with no compile-time safety.
- **Dynamic provider descriptor mismatch:** If the LAN API advertises a provider with `capabilities` that do not include `SEARCH`, the provider is silently skipped in `streamMultiSearch`; the user sees fewer results than selected chips imply.
- **`ApiBaseUrlHolder` empty:** If the onboarding API-selection step is bypassed or fails to write the holder, every dynamic provider request builds a URL with an empty host and fails at the transport layer, producing `Error` or `Empty` depending on error handling.
- **`ProviderSessionTokenHolder` stale token:** A token written for one provider id but looked up for a different id (e.g. descriptor id vs tracker id mismatch) results in an unauthenticated request to an auth-required provider, returning empty results instead of prompting login.

---

## 12. Evidence integrity

All file:line references above are from a direct source read of the repository at commit `5f803af5` (master HEAD at the time of tracing). No production code was modified and no test files were added during this Phase 1 evidence-gathering step.
