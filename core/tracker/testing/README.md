# :core:tracker:testing

Shared test fakes, builders, and fixture loaders for the multi-tracker
SDK. Pure-Kotlin, JVM-only. Consumed as `testImplementation` by every
other tracker module and by `:core:tracker:client`.

## Public API

| Type                       | Purpose                                                                                  |
|----------------------------|------------------------------------------------------------------------------------------|
| `FakeTrackerClient`        | In-memory `TrackerClient` impl with explicit per-feature behaviour controls              |
| `TorrentItemBuilder`       | Fluent builder producing realistic `TorrentItem` instances for ViewModel tests           |
| `SearchRequestBuilder`     | Fluent builder producing realistic `SearchRequest` instances                             |
| `LavaFixtureLoader`        | Lava-side wrapper around `lava.sdk:testing.FixtureLoader` — loads HTML fixtures by path  |

`FakeTrackerClient` is **not** "the simple way to skip wiring real
clients"; it is the compliance fake that simulates real-tracker
behaviours that production code relies on (latency, partial
responses, cookie handling, mirror health transitions). Adding a new
production behaviour without updating this fake is a Third-Law
violation (bluff fake) under the Anti-Bluff Pact.

## Usage

```kotlin
@Test
fun searchOutcome_isResults_when_active_tracker_returns_rows() = runTest {
    val fake = FakeTrackerClient.builder()
        .withDescriptor(RuTorDescriptor)
        .onSearch { req -> SearchResult(items = listOf(
            TorrentItemBuilder().title("ubuntu").seeders(42).build()
        )) }
        .build()

    val sdk = LavaTrackerSdk(
        registry = registryWith(fake),
        // ... real other deps ...
    )

    val outcome = sdk.search(SearchRequestBuilder().query("ubuntu").build())

    assertEquals(1, (outcome as SearchOutcome.Results).items.size)
}
```

## Fixture loading

```kotlin
val html = LavaFixtureLoader.loadHtml(
    trackerId = "rutor",
    scope = "search",
    name = "search-normal-2026-04-30",
)
```

The loader resolves to `src/test/resources/fixtures/<trackerId>/<scope>/<name>.html`
on the consumer module's test classpath, with a clear error message
on miss. It does NOT silently substitute a "default" fixture — a miss
raises an exception so test failures surface immediately.

## Behavioural-equivalence requirements

`FakeTrackerClient` MUST behave like `RuTrackerClient` /
`RuTorClient` (or any future N-th tracker) in every respect that
affects the test's load-bearing assertion:

- Capability gating: `getFeature<T>()` returns null for capabilities
  not in the descriptor.
- Outcome shapes: `SearchOutcome.Results` vs `Failure` vs
  `CrossTrackerFallbackProposed` all reachable.
- Mirror health: `markMirrorHealth(state)` mutates an in-memory
  store that the production `MirrorHealthRepository` would mutate
  in reality.
- Authentication transitions: `Authenticated` → `Unauthenticated` on
  expiry, `Unauthenticated` → `Authenticated` on `login()` success.

If a divergence from real-client behaviour cannot be eliminated, it
MUST be documented in this README and an entry added to the coverage
exemption ledger
(`docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`).

## Test discipline

This module's own tests
(`src/test/kotlin/lava/tracker/testing/FakeTrackerClientTest.kt`)
exercise the fake against the same contract its production
counterparts must satisfy. Per Sixth + Seventh Laws:

- No mocking of `FakeTrackerClient` itself (don't mock the fake).
- Primary assertions on the fake's emitted outcomes, never
  `verify { … }`.
- Bluff-Audit stamp required on every test commit.

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`; Third Law
> (Behavioural Equivalence) bites this module hardest — a fake that
> diverges from production is the single highest-bandwidth bluff
> vector in the codebase.
