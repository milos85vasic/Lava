# :core:tracker:api

Lava-domain feature interfaces, capability enum, and shared DTOs for
the multi-tracker SDK. **Pure-Kotlin, JVM-only** — no Android, no
Hilt, no Room.

This is the public surface every per-tracker plugin module
(`:core:tracker:rutracker`, `:core:tracker:rutor`, future N-th
tracker) implements against, and the surface every feature ViewModel
ultimately consumes (transitively, via `LavaTrackerSdk`).

## What lives here

| Type                                                  | Purpose                                                                           |
|-------------------------------------------------------|-----------------------------------------------------------------------------------|
| `TrackerDescriptor`                                   | Immutable per-tracker manifest (id, displayName, baseUrls, capabilities, authType) |
| `TrackerClient`                                       | Façade that returns feature impls by `KClass<T>` via `getFeature<T>()`            |
| `TrackerCapability` (enum)                            | 13-value capability vocabulary; the source of truth for clause 6.E checks         |
| `AuthType` (enum)                                     | `ANONYMOUS`, `FORM_LOGIN`, `CAPTCHA_LOGIN`                                        |
| `feature/Searchable`, `Browsable`, `Topic`, `Comments`, `Favorites`, `Authenticatable`, `Downloadable` | The seven feature interfaces that bear capabilities |
| `model/*`                                             | Common data model: `TorrentItem`, `SearchRequest`, `SearchResult`, `BrowseResult`, `TopicDetail`, `TopicPage`, `Comment`, `CommentsPage`, `LoginRequest`, `LoginResult`, `AuthState`, `CaptchaChallenge`, `CaptchaSolution`, `SortField`, `SortOrder`, `TimePeriod`, `TorrentFile`, `ForumCategory`, `ForumTree` |

## Dependencies

```
:core:tracker:api
   └── lava.sdk:api          (composite-build pin: MirrorUrl, Protocol, ...)
   └── kotlinx-coroutines-core
   └── kotlinx-datetime
   └── kotlinx-serialization-json   (added by lava.kotlin.serialization plugin)
```

No Android dependency, no Hilt dependency, no JSON/Ktor/OkHttp
dependency — keeping this module purely declarative is what makes the
JVM unit tests in every tracker plugin run at >10 tests/second.

## Capability honesty (constitutional clause 6.E)

The relationship between `TrackerCapability` (declared in the
descriptor) and `TrackerFeature` (returned by `getFeature<T>()`) is
**not optional**. Every capability declared MUST resolve to a non-null
implementation, enforced both by `:core:tracker:registry`'s
`DefaultTrackerRegistryTest` and by `scripts/check-constitution.sh` in
the local CI gate.

If you add a new capability:

1. Add the enum entry here.
2. Add a feature interface here (under `feature/`).
3. Update at least one tracker plugin to implement and return it from
   `getFeature<T>()`.
4. Document the new capability in
   [`docs/sdk-developer-guide.md`](../../../docs/sdk-developer-guide.md).

## Test discipline

Tests in this module are **structural** — they assert that DTOs
serialise round-trip, the descriptor contract is satisfied, and the
capability enum is exhaustively switched on by every consumer. They are
run as part of `./gradlew :core:tracker:api:test`.

Per the Anti-Bluff Pact (Sixth + Seventh Laws), any new test in this
module:

- Carries a Bluff-Audit stamp on the commit (Seventh Law clause 1,
  enforced by the pre-push hook).
- Asserts on user-visible / consumer-visible state (a serialised JSON
  byte sequence, an enum cardinality, a capability resolution),
  never `verify { mock.foo() }`.
- Does NOT mock anything inside the module — there is nothing to
  mock; this module is data + interfaces.

See [§5 of the SDK developer guide](../../../docs/sdk-developer-guide.md#5-real-stack-testing-requirements-seventh-law-clauses) for the full Seventh-Law gate.

## Files

- `src/main/kotlin/lava/tracker/api/` — public surface.
- `src/test/kotlin/lava/tracker/api/` — descriptor contract + capability enum tests.
- `src/test/kotlin/lava/tracker/api/model/` — serialization round-trip tests.

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`. The Seventh
> Law's `Bluff-Audit:` stamp, real-stack verification gate, forbidden
> patterns, recurring bluff hunt, and inheritance clause apply here
> verbatim.
