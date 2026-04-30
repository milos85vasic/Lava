# :core:tracker:client

The Hilt-injected orchestrator that feature ViewModels consume. Hosts
`LavaTrackerSdk`, the persistence repositories
(`MirrorHealthRepository`, `UserMirrorRepository`), the bundled-mirror
loader (`MirrorConfigLoader`), and the periodic
`MirrorHealthCheckWorker`.

## Public API

```kotlin
@Singleton
class LavaTrackerSdk @Inject constructor(...) {
    suspend fun search(request: SearchRequest): SearchOutcome
    suspend fun browse(...): BrowseOutcome
    suspend fun topic(...): TopicOutcome
    suspend fun login(...): LoginResult
    suspend fun download(...): DownloadOutcome

    fun activeTracker(): TrackerClient
    fun setActiveTracker(trackerId: String)
    fun listTrackers(): List<TrackerDescriptor>
}
```

`SearchOutcome` (and its sibling outcome types) are sealed classes:
the cross-tracker fallback case is encoded as
`SearchOutcome.CrossTrackerFallbackProposed(altTrackerId)`, which the
UI layer translates into the `CrossTrackerFallbackModal` user prompt.
There is **no silent fallback** — the user is always asked.

## Hilt graph

```
@Module @InstallIn(SingletonComponent::class) TrackerClientModule
   ├── @Provides @Singleton LavaTrackerSdk
   ├── @Provides @IntoSet TrackerClientFactory (RuTracker, via :core:tracker:rutracker)
   ├── @Provides @IntoSet TrackerClientFactory (RuTor,     via :core:tracker:rutor)
   ├── @Provides @Singleton MirrorHealthRepository
   ├── @Provides @Singleton UserMirrorRepository
   ├── @Provides @Singleton MirrorConfigLoader
   ├── @Provides MirrorHealthCheckWorker (HiltWorker)
   └── @Provides @Singleton CrossTrackerFallbackPolicy

@HiltWorker class MirrorHealthCheckWorker
   - PeriodicWorkRequest, 15-min interval
   - Probes every registered mirror, writes MirrorHealthEntity rows
   - Backed by tracker_mirror_health Room table (AppDatabase v7)
```

To register a new tracker, the per-tracker module's
`<TrackerId>RegistrationModule` adds an `@IntoSet TrackerClientFactory`
binding — the multi-binding picks it up automatically and the
`DefaultTrackerRegistry` exposes it by `descriptor.trackerId`.

## Persistence

| Table                    | Entity              | Owner         | Purpose                                  |
|--------------------------|---------------------|---------------|------------------------------------------|
| `tracker_mirror_health`  | `MirrorHealthEntity` | client module | Per-mirror probe results + last-seen     |
| `tracker_mirror_user`    | `UserMirrorEntity`   | client module | User-added custom mirrors per tracker    |

The Room schema lives in `:core:database` (versioned at v7); the
client module owns the DAOs (`MirrorHealthDao`, `UserMirrorDao`) and
the repository wrappers.

Bundled defaults:
`core/tracker/client/src/main/assets/mirrors.json` — schema and
validation rules in §7 of the SDK developer guide.

## Usage from a feature ViewModel

```kotlin
@HiltViewModel
class SearchResultViewModel @Inject constructor(
    private val sdk: LavaTrackerSdk,
) : ContainerHost<...>, ViewModel() {
    fun search(query: String) = intent {
        when (val outcome = sdk.search(SearchRequest(query))) {
            is SearchOutcome.Results -> reduce { state.copy(results = outcome.items) }
            is SearchOutcome.CrossTrackerFallbackProposed -> postSideEffect(
                ShowCrossTrackerFallbackModal(altTrackerId = outcome.altTrackerId)
            )
            is SearchOutcome.Failure -> postSideEffect(ShowError(outcome.reason))
        }
    }
}
```

The ViewModel does **not** import any per-tracker package — it talks
to the SDK and reacts to outcomes.

## Test discipline

This module ships:

- `LavaTrackerSdkTest` — unit tests against `FakeTrackerClient`
  fakes from `:core:tracker:testing`.
- `LavaTrackerSdkRealStackTest` — real-stack test wiring two real
  `TrackerClient` instances (the actual `RuTrackerClient` and
  `RuTorClient`) into a live `LavaTrackerSdk`, with only the
  `OkHttpClient` boundary replaced by a `MockWebServer`.
- `LavaTrackerSdkMirrorHealthTest` — exercises the health-probe
  loop, asserts that an UNHEALTHY transition triggers the fallback
  policy.
- `LavaTrackerSdkCrossTrackerFallbackTest` — asserts that the
  fallback emits the `CrossTrackerFallbackProposed` outcome on the
  first all-mirrors-UNHEALTHY signal.
- `CrossTrackerFallbackPolicyTest` — exercises the policy in
  isolation against a deterministic clock.

Per the Anti-Bluff Pact (Sixth + Seventh Laws), no test in this
module mocks `LavaTrackerSdk`, `CrossTrackerFallbackPolicy`,
`MirrorHealthRepository`, or any class that resides under
`lava.tracker.client.*` — only the OkHttp / Room / WorkManager
boundaries are faked. The Bluff-Audit stamp is required on every
test commit.

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`.
> Constitutional clauses 6.E (Capability Honesty) and 6.F (Anti-Bluff
> Submodule Inheritance) bind every change in this module — every
> capability declared by a registered descriptor MUST resolve to a
> non-null `getFeature<T>()` at runtime.
