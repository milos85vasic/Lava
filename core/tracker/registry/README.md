# :core:tracker:registry

Lava-domain wrapper around the generic `lava.sdk:registry` primitive
in the `Submodules/Tracker-SDK/` composite-build pin. Discovers and
exposes `TrackerClient` instances by `descriptor.trackerId`.

## Public API

```kotlin
interface TrackerRegistry {
    val descriptors: List<TrackerDescriptor>
    fun client(trackerId: String): TrackerClient?
    fun activeClient(): TrackerClient
    fun setActive(trackerId: String)
}

class TrackerClientFactory(
    val descriptor: TrackerDescriptor,
    val instantiate: () -> TrackerClient,
)
```

`DefaultTrackerRegistry` is the production implementation — backed by
the generic `lava.sdk:registry.InMemoryTrackerRegistry` plus a
Lava-side capability-honesty check.

## How a tracker plugin registers

Each per-tracker module exposes a `<TrackerId>RegistrationModule` Hilt
module that contributes a single `TrackerClientFactory` via Dagger
multi-binding:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object RuTorRegistrationModule {
    @Provides
    @IntoSet
    fun provideRuTorFactory(impl: RuTorClient): TrackerClientFactory =
        TrackerClientFactory(
            descriptor = RuTorDescriptor,
            instantiate = { impl },
        )
}
```

The set of all `@IntoSet TrackerClientFactory` bindings is collected by
Hilt at app start and handed to `DefaultTrackerRegistry`. There is no
reflection, no `ServiceLoader`, no manual bootstrapping in
`Application.onCreate`.

## Capability honesty enforcement

`DefaultTrackerRegistryTest` enumerates every registered factory's
`descriptor.capabilities` and asserts that the corresponding
`TrackerClient.getFeature<T>()` returns non-null for the matching
`TrackerFeature` interface. This is the JVM-test mechanical realisation
of constitutional clause 6.E.

A capability declared in the descriptor without a backing impl in
`getFeature<T>()` causes this test to fail at JVM-test time, which
makes such a regression catchable by `./gradlew :core:tracker:registry:test`
(part of the local `scripts/ci.sh --changed-only` pre-push gate).

## Dependencies

```
:core:tracker:registry
   ├── :core:tracker:api
   └── lava.sdk:registry  (composite-build pin, frozen by default)
```

## Test discipline

- Tests use real `TrackerClientFactory` instances built from the real
  `RuTrackerDescriptor` / `RuTorDescriptor` and the real client
  classes — never mocked.
- The capability-honesty assertion is the primary user-visible signal:
  a failure means a real user could call a "supported" capability and
  receive a `null` impl.

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`. The Seventh
> Law's Bluff-Audit stamp applies to every test commit here. Forbidden
> patterns (mocking the SUT, assertions only on `verify { mock.foo() }`)
> are pre-push-rejected.
