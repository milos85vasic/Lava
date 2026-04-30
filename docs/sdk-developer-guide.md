# Lava Tracker SDK — Developer Guide (partial draft)

> **Status:** SP-3a partial draft (2026-04-30). The 7-step recipe in §2 is
> the load-bearing acceptance criterion; the rest is illustrative until
> the post-1.2.0 documentation polish pass.
>
> **Validation:** Each step in §2 has been paper-traced through the
> existing `:core:tracker:rutor` module (added in SP-3a Phase 3). If you
> can answer "yes" to every step against `core/tracker/rutor/`, your new
> tracker module is structurally compliant.

---

## 1. SDK Architecture Overview

The Lava Tracker SDK lives in two layers:

1. **Generic primitives** — mounted at `Submodules/Tracker-SDK/` (the
   `vasic-digital/Tracker-SDK` submodule). These are Lava-agnostic
   building blocks: `MirrorUrl`, `TrackerDescriptor`-shape, `Mirror`
   health-state machine, in-memory registry, mirror-config store, and
   test scaffolding (clock, dispatcher, fixture loader). The submodule
   is **frozen by default**: updating the pin is a deliberate PR.

2. **Lava-domain wrappers + per-tracker plugins** — under `core/tracker/`
   in the Lava monorepo. `core/tracker/api/` exposes the seven feature
   interfaces (`Searchable`, `Browsable`, `Topic`, `Comments`,
   `Favorites`, `Authenticatable`, `Downloadable`) and the
   `TrackerCapability` enum. `core/tracker/{rutracker,rutor}/` are the
   two shipped per-tracker plugins. `core/tracker/client/` hosts
   `LavaTrackerSdk`, the Hilt-injected orchestrator that ViewModels
   consume.

A feature ViewModel never imports a per-tracker module directly. It
talks to `LavaTrackerSdk`, which routes the call through the active
tracker's `TrackerClient.getFeature<T>()`. Capability declared in the
descriptor ⇒ feature interface returned ⇒ method works (constitutional
clause 6.E, Capability Honesty).

```
[ViewModel] → [LavaTrackerSdk] → [Registry] → [TrackerClient (active)]
                                                    │
                                                    ├─→ getFeature<Searchable>()
                                                    ├─→ getFeature<Authenticatable>()
                                                    └─→ getFeature<Downloadable>()
```

When all mirrors of the active tracker fail, `LavaTrackerSdk` emits
`CrossTrackerFallbackProposed` (Phase 4); the UI presents a modal
offering the alternative tracker. There is no silent fallback.

---

## 2. Adding a New Tracker (7-step recipe)

Follow these steps in order. Each step is testable against the existing
`:core:tracker:rutor` module — when in doubt, paper-trace there first.

### Step 1 — Create the Gradle module

```bash
mkdir -p core/tracker/<trackerId>/src/{main,test}/kotlin
mkdir -p core/tracker/<trackerId>/src/test/resources/fixtures/<trackerId>
```

Create `core/tracker/<trackerId>/build.gradle.kts`:

```kotlin
plugins {
    id("lava.kotlin.tracker.module")
}
```

That convention plugin pre-wires `:core:tracker:api`, `lava.sdk:api`,
`lava.sdk:mirror`, Jsoup, OkHttp, kotlinx-coroutines,
kotlinx-serialization, JUnit4, mockk, kotlinx-coroutines-test,
`:core:tracker:testing`, and `lava.sdk:testing`. **Do not duplicate
config**; if you find yourself adding dependencies module-by-module,
extend the convention plugin instead.

Add the module to `settings.gradle.kts`:

```kotlin
include(":core:tracker:<trackerId>")
```

### Step 2 — Define the `TrackerDescriptor`

In `core/tracker/<trackerId>/src/main/kotlin/lava/tracker/<trackerId>/<TrackerId>Descriptor.kt`:

```kotlin
object <TrackerId>Descriptor : TrackerDescriptor {
    override val id = "<trackerId>"
    override val displayName = "<Display Name>"
    override val capabilities: Set<TrackerCapability> = setOf(
        TrackerCapability.SEARCH,
        TrackerCapability.TOPIC,
        TrackerCapability.DOWNLOAD,
        // declare ONLY the capabilities you will actually wire up
        // (clause 6.E Capability Honesty: declared ⇒ getFeature non-null)
    )
    override val defaultMirrors = listOf(
        MirrorUrl(host = "<primary-host>", priority = 100),
        MirrorUrl(host = "<secondary-host>", priority = 50),
    )
    override val authRequired = false  // or true for RuTracker-style auth
}
```

### Step 3 — Implement the per-feature interfaces

For each capability declared in step 2, write a class implementing the
matching `core:tracker:api` interface. Classes live in
`core/tracker/<trackerId>/src/main/kotlin/lava/tracker/<trackerId>/`:

- `<TrackerId>Search.kt`        → `Searchable`
- `<TrackerId>Topic.kt`         → `Topic`
- `<TrackerId>Download.kt`      → `Downloadable`
- `<TrackerId>Authenticator.kt` → `Authenticatable` (if applicable)
- (etc.)

Each implementation MUST use OkHttp + Jsoup against the live tracker
HTML. **No HTTP-mocking inside the production class** — the
`OkHttpClient` is constructor-injected so a test can swap in
`MockWebServer` (clause 6.E + Seventh Law clause 4: mock at the
boundary, not inside the SUT).

### Step 4 — Wire up the `TrackerClient`

In `core/tracker/<trackerId>/src/main/kotlin/lava/tracker/<trackerId>/<TrackerId>Client.kt`:

```kotlin
class <TrackerId>Client @Inject constructor(
    private val search: <TrackerId>Search,
    private val topic: <TrackerId>Topic,
    private val download: <TrackerId>Download,
) : TrackerClient {
    override val descriptor = <TrackerId>Descriptor

    override fun <T : Any> getFeature(klass: KClass<T>): T? = when (klass) {
        Searchable::class    -> if (TrackerCapability.SEARCH   in descriptor.capabilities) search   as T else null
        Topic::class         -> if (TrackerCapability.TOPIC    in descriptor.capabilities) topic    as T else null
        Downloadable::class  -> if (TrackerCapability.DOWNLOAD in descriptor.capabilities) download as T else null
        else -> null
    }
}
```

The capability check inside `getFeature` is the runtime enforcement of
clause 6.E. Drop a capability from the descriptor → `getFeature` returns
null → call site MUST handle null (no silent stub).

### Step 5 — Register the `TrackerClientFactory`

In `core/tracker/client/src/main/kotlin/lava/tracker/client/registry/`
add:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object <TrackerId>RegistrationModule {
    @Provides
    @IntoSet
    fun provide<TrackerId>Factory(
        impl: <TrackerId>Client,
    ): TrackerClientFactory = TrackerClientFactory(
        descriptor = <TrackerId>Descriptor,
        instantiate = { impl },
    )
}
```

The `@IntoSet` multi-binding picks every factory up at app start; the
registry exposes them by `descriptor.id`. No reflection, no service
loader, no manual bootstrapping in `Application.onCreate`.

### Step 6 — Drop fixtures + write parser tests

Save sample HTML pages from the live tracker (one per parser scope) at:

```
core/tracker/<trackerId>/src/test/resources/fixtures/<trackerId>/
  search/<query>-<YYYY-MM-DD>.html
  topic/<topicId>-<YYYY-MM-DD>.html
  ...
```

Fixture filenames MUST embed the date (`scripts/check-fixture-freshness.sh`
warns at >30 days, blocks at >60 days). Each parser scope MUST have at
least 5 fixtures spanning real-world variations.

Write parser tests under `src/test/kotlin/`:

```kotlin
class <TrackerId>SearchParserTest {
    @Test fun `parses search results from 2026-04 fixture`() {
        val html = readFixture("search/ubuntu-2026-04-15.html")
        val results = <TrackerId>SearchParser.parse(html)
        assertEquals(50, results.size)
        assertEquals("ubuntu-22.04.4-desktop-amd64.iso", results[0].title)
        assertTrue(results[0].sizeBytes > 0)
        assertTrue(results[0].seeders >= 0)
    }
}
```

Every test commit MUST carry a `Bluff-Audit:` stamp in the commit body
(Seventh Law clause 1, mechanical pre-push hook enforcement).

### Step 7 — Add a Challenge Test in `:app`

In `app/src/androidTest/kotlin/lava/app/challenges/`, add a Compose UI
test that exercises the full stack against your new tracker. Mirror the
shape of `C1_AppLaunchAndTrackerSelectionTest.kt` (added in SP-3a
Phase 5). The Challenge Test:

1. Drives `MainActivity` to the search screen.
2. Switches the active tracker to your new tracker via Settings →
   Trackers.
3. Performs a real search.
4. Asserts ≥1 result row renders with parseable size + seeders text.

Falsifiability rehearsal: temporarily mutate the tracker's `getFeature`
to return null for `Searchable` → re-run → confirm the UI shows the
"not supported" path → revert. Capture the rehearsal in
`.lava-ci-evidence/sp3a-challenges/<TestName>-<sha>.json` per Sixth
Law clause 2.

---

## 3. Mirror Configuration

Mirror sources, in priority order:

1. **Bundled defaults** — `core/tracker/client/src/main/assets/mirrors.json`,
   merged at app start by `MirrorConfigLoader`.
2. **User-added custom mirrors** — persisted in Room
   (`tracker_mirror_user` table), exposed by `UserMirrorRepository`.
3. **Health-tracked active subset** — `MirrorHealthRepository` writes
   `tracker_mirror_health` rows on every probe; the
   `MirrorHealthCheckWorker` runs every 15 minutes and writes
   `HEALTHY` / `DEGRADED` / `UNHEALTHY` states.

When all mirrors of the active tracker hit `UNHEALTHY`,
`LavaTrackerSdk` emits a `CrossTrackerFallbackProposed` outcome. The
UI shows `CrossTrackerFallbackModal`; user accept → re-issues the call
on the alternative tracker; user dismiss → explicit failure UI
(snackbar). No silent fallback.

To add a tracker's default mirrors, edit `mirrors.json`:

```json
{
  "<trackerId>": [
    { "host": "<primary>", "priority": 100 },
    { "host": "<secondary>", "priority": 50 }
  ]
}
```

`MirrorConfigLoader` validates this on app start; an entry without a
matching `TrackerDescriptor` is logged as a config error and ignored.

---

## 4. Testing Requirements

Every tracker module MUST satisfy:

| Requirement | Mechanism |
|---|---|
| Real-stack parser tests | Fixtures under `src/test/resources/fixtures/<id>/`, one test per parser scope, ≥5 fixtures each. Pre-push hook rejects new test commits without a `Bluff-Audit:` stamp. |
| Capability honesty | Constitutional clause 6.E: every capability declared in the descriptor MUST resolve to a non-null `getFeature<T>()`. The `:core:tracker:registry` test enumerates descriptors + capabilities and asserts non-null. |
| Cross-tracker parity | Where two trackers expose the same capability (e.g. both have `SEARCH`), at least one shared-shape test asserts the result-set DTO is identical (modulo tracker id). |
| Real-tracker integration test | Gated by `-PrealTrackers=true`. Hits the live tracker. Run before tagging; results recorded in `.lava-ci-evidence/sp3a-rutor/` (or equivalent for new trackers). |
| Real-device Challenge Test | One Compose UI test per user-visible scenario in `app/src/androidTest/kotlin/lava/app/challenges/`. Falsifiability rehearsal captured in `.lava-ci-evidence/sp3a-challenges/`. Operator real-device attestation required before the next release tag. |

Coverage exemptions for individual lines go in
`docs/superpowers/specs/<spec>-coverage-exemptions.md` per clause 6.D.
Blanket waivers are forbidden.

---

## 5. Reference: existing trackers

| Tracker | Module | Capabilities | Auth |
|---|---|---|---|
| RuTracker | `:core:tracker:rutracker` | SEARCH + BROWSE + TOPIC + COMMENTS + FAVORITES + AUTH + DOWNLOAD | Required (login) |
| RuTor     | `:core:tracker:rutor`     | SEARCH + TOPIC + DOWNLOAD                                       | Anonymous (decision 7b-ii) |

When adding the third tracker, follow this guide; if any step doesn't
match the existing modules, that is the first thing to clarify in the
PR description.

---

*Last updated: 2026-04-30 (SP-3a Phase 5, partial draft).*
