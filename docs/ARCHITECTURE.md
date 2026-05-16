# Lava Architecture

This document collects two architectural surfaces that span multiple
modules:

1. **Multi-Tracker SDK** — the layered, pluggable tracker stack added in
   SP-3a (1.2.0). Pure-Kotlin core, Hilt-injected orchestrator,
   per-tracker plugin modules, generic primitives in a `vasic-digital`
   submodule.
2. **Local Network Discovery** — the mDNS / NsdManager flow that
   auto-discovers the lava-api-go service or the legacy `:proxy` on
   the LAN.

For per-feature MVI shapes, see the relevant `feature/*/README.md` (or
the source — every feature mirrors `XxxState` + `XxxAction` +
`XxxSideEffect` + `XxxViewModel`).

---

## Multi-Tracker SDK (SP-3a, 1.2.0)

### Layering

```
┌──────────────────────────────────────────────────────────────────────┐
│ feature:* ViewModels (search_result, topic, login, provider_config,  │
│   credentials_manager, menu) — no per-tracker imports; talk to       │
│   LavaTrackerSdk only                                                │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ inject @Singleton LavaTrackerSdk
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│ :core:tracker:client    — LavaTrackerSdk (Hilt orchestrator)         │
│   • activeTracker(): TrackerClient                                   │
│   • search(req) / browse(...) / topic(...) / download(...)           │
│   • CrossTrackerFallbackPolicy → CrossTrackerFallbackProposed        │
│   • MirrorHealthCheckWorker (15-min PeriodicWork)                    │
│   • MirrorHealthRepository (Room: tracker_mirror_health)             │
│   • UserMirrorRepository    (Room: tracker_mirror_user)              │
│   • MirrorConfigLoader      (assets/mirrors.json)                    │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ TrackerClient.getFeature<T>(KClass<T>)
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│ :core:tracker:registry  — DefaultTrackerRegistry                     │
│   • @IntoSet TrackerClientFactory bindings (Hilt multibinding)       │
│   • by-id lookup: registry.client("rutracker") / .client("rutor")    │
│   • wraps lava.sdk:registry primitive                                │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │
                ┌────────────────┴───────────────────────────┐
                ▼                                            ▼
┌──────────────────────────────┐          ┌──────────────────────────────┐
│ :core:tracker:rutracker      │          │ :core:tracker:rutor          │
│   • RuTrackerDescriptor      │          │   • RuTorDescriptor          │
│   • RuTrackerClient          │          │   • RuTorClient              │
│   • RuTrackerClientFactory   │          │   • RuTorClientFactory       │
│   • per-feature impls:       │          │   • per-feature impls:       │
│       Searchable, Browsable, │          │       Searchable, Browsable, │
│       Topic, Comments,       │          │       Topic, Comments,       │
│       Favorites,             │          │       Authenticatable,       │
│       Authenticatable,       │          │       Downloadable           │
│       Downloadable           │          │   • parsers (Jsoup, UTF-8)   │
│   • parsers (Jsoup,          │          │   • OkHttp client            │
│      Windows-1251)           │          │                              │
└──────────────────────────────┘          └──────────────────────────────┘
                │                                            │
                └─────────────┬──────────────────────────────┘
                              ▼
┌──────────────────────────────────────────────────────────────────────┐
│ :core:tracker:api    — feature interfaces + capability enum + DTOs   │
│   • Searchable, Browsable, Topic, Comments, Favorites,               │
│     Authenticatable, Downloadable                                    │
│   • TrackerCapability {SEARCH, BROWSE, FORUM, TOPIC, COMMENTS,       │
│     FAVORITES, TORRENT_DOWNLOAD, MAGNET_LINK, AUTH_REQUIRED,         │
│     CAPTCHA_LOGIN, RSS, UPLOAD, USER_PROFILE}                        │
│   • TorrentItem, SearchRequest, SearchResult, TopicDetail, …         │
│   • AuthType {ANONYMOUS, FORM_LOGIN, CAPTCHA_LOGIN}                  │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ depends-on
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│ :core:tracker:mirror   typealias → lava.sdk:mirror.MirrorConfigStore │
│ :core:tracker:registry          → lava.sdk:registry primitives       │
│ :core:tracker:testing  FakeTrackerClient + builders + fixtures       │
│                                 └─→ lava.sdk:testing primitives       │
└────────────────────────────────┬─────────────────────────────────────┘
                                 │ composite-build pin (frozen by default)
                                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│ submodules/tracker_sdk/    (vasic-digital/Tracker-SDK)               │
│   • api       — MirrorUrl, Protocol, Mirror state machine            │
│   • mirror    — MirrorConfigStore interface                          │
│   • registry  — generic in-memory tracker registry primitive         │
│   • testing   — clock, dispatcher, fixture loader                    │
│                                                                      │
│   Mirrored to GitHub + GitLab. Pin updates are deliberate PRs.       │
└──────────────────────────────────────────────────────────────────────┘
```

The capability enum is the single source of truth. Constitutional
clause 6.E (Capability Honesty) requires that **every capability declared
in a `TrackerDescriptor` resolves to a non-null `TrackerClient.getFeature<T>()`**;
the `:core:tracker:registry` test enumerates descriptors + capabilities
and asserts non-null at JVM-test time.

### Cross-tracker fallback flow

```
[ViewModel.search(query)]
         │
         ▼
[LavaTrackerSdk.search(req)]
         │
         ├─ active.client.getFeature<Searchable>().search(req)   ──► success → SearchOutcome.Results
         │
         └─ all-mirrors-UNHEALTHY?  (MirrorHealthRepository check)
                       │
                       └─► CrossTrackerFallbackPolicy.propose(active, alt)
                                    │
                                    └─► SearchOutcome.CrossTrackerFallbackProposed(altTrackerId)
                                                │
                                                ▼
                       [CrossTrackerFallbackModal in :feature:search_result]
                                                │
                          accept ─────────► SDK re-issues search on altTracker
                          dismiss ─────────► Snackbar "Search failed on rutracker. All mirrors unhealthy."
```

There is **no silent fallback**. The user is always shown the modal and
must explicitly accept or dismiss.

### Persistence

Room database `AppDatabase` v7 owns two SP-3a tables (migration v6 → v7):

| Table                    | Entity                | Purpose                                              |
|--------------------------|-----------------------|------------------------------------------------------|
| `tracker_mirror_health`  | `MirrorHealthEntity`  | Per-mirror probe results (HEALTHY/DEGRADED/UNHEALTHY) |
| `tracker_mirror_user`    | `UserMirrorEntity`    | User-added custom mirrors per tracker                |

Bundled defaults live at
`core/tracker/client/src/main/assets/mirrors.json` and are merged at
app start by `MirrorConfigLoader`.

### Module reference

| Path                                   | Plugin                            | Purpose                                                   |
|----------------------------------------|-----------------------------------|-----------------------------------------------------------|
| `core/tracker/api`                     | `lava.kotlin.library` + serialization | Public feature interfaces + capability enum + DTOs    |
| `core/tracker/registry`                | `lava.kotlin.library`             | Lava-domain `TrackerRegistry` over `lava.sdk:registry`    |
| `core/tracker/mirror`                  | `lava.kotlin.library` + serialization | `MirrorConfigStore` typealias bridge                  |
| `core/tracker/client`                  | `lava.android.library` + Hilt + serialization | `LavaTrackerSdk`, persistence repos, WorkManager   |
| `core/tracker/rutracker`               | `lava.kotlin.tracker.module`      | RuTracker plugin                                          |
| `core/tracker/rutor`                   | `lava.kotlin.tracker.module`      | RuTor plugin                                              |
| `core/tracker/testing`                 | `lava.kotlin.library`             | `FakeTrackerClient` + builders + fixture loaders          |
| `feature/provider_config`              | `lava.android.feature` + Compose  | Per-provider configuration (mirrors, credentials binding, sync toggle, anonymous mode, clone). Reachable from Menu by tapping a provider row. Replaces the SP-3a Trackers screen (deleted in SP-4 Phase C). |
| `feature/credentials_manager`          | `lava.android.feature` + Compose  | Passphrase-gated credentials CRUD UI (Phase A). Add / edit / delete `CredentialsEntry` rows. |

Generic primitives mounted via composite build at `submodules/tracker_sdk/`
(submodule pin frozen by default per the Decoupled Reusable Architecture
constitutional rule).

For the full design rationale see
[`docs/superpowers/specs/2026-04-30-sp3a-multi-tracker-sdk-foundation-design.md`](superpowers/specs/2026-04-30-sp3a-multi-tracker-sdk-foundation-design.md).
For the step-by-step recipe to add a third tracker see
[`docs/sdk-developer-guide.md`](sdk-developer-guide.md).

---

## Local Network Discovery Architecture

## Sequence Diagram

```
┌─────────┐     ┌──────────────┐     ┌─────────────────┐     ┌─────────────┐
│  User   │     │  MenuScreen  │     │ MenuViewModel   │     │ DiscoverUse │
└────┬────┘     └──────┬───────┘     └────────┬────────┘     └──────┬──────┘
     │                 │                      │                     │
     │  Open Settings  │                      │                     │
     │────────────────>│                      │                     │
     │                 │  LaunchedEffect      │                     │
     │                 │─────────────────────>│                     │
     │                 │                      │  invoke()           │
     │                 │                      │────────────────────>│
     │                 │                      │                     │
     │                 │                      │<────────────────────│
     │                 │                      │  Discovered /       │
     │                 │                      │  NotFound /         │
     │                 │                      │  AlreadyConfigured  │
     │                 │                      │                     │
     │                 │  OpenConnectionSettings (if discovered)    │
     │                 │<─────────────────────│                     │
     │                 │                      │                     │
     │  Show endpoints │                      │                     │
     │<────────────────│                      │                     │
```

## Data Flow

```
┌─────────────────────────────┐
│  LocalNetworkDiscoveryService │  (core:data)
│  - NsdManager on Android     │
│  - callbackFlow              │
└─────────────┬───────────────┘
              │ Flow<DiscoveredEndpoint>
              ▼
┌─────────────────────────────┐
│ DiscoverLocalEndpointsUseCase│  (core:domain)
│  - firstOrNull() with timeout│
│  - adds mirror to repo       │
│  - sets active endpoint      │
└─────────────┬───────────────┘
              │ DiscoverLocalEndpointsResult
              ▼
┌─────────────────────────────┐
│       MenuViewModel          │  (feature:menu)
│  - onCreate: discovers       │
│  - posts side effect         │
└─────────────┬───────────────┘
              │ MenuSideEffect.OpenConnectionSettings
              ▼
┌─────────────────────────────┐
│       MenuScreen             │  (feature:menu)
│  - collects side effects     │
│  - shows bottom sheet        │
└─────────────────────────────┘
```

## Proxy Advertisement

```
┌─────────────────────────────┐
│  ServiceAdvertisement        │  (proxy)
│  - JmDNS.create()            │
│  - registerService()         │
│  - type: _lava._tcp.local.   │
└─────────────────────────────┘
```

## Module Dependencies

```
feature:menu
    └── core:domain
            ├── core:data
            │       └── LocalNetworkDiscoveryService (NsdManager)
            └── core:models

proxy
    └── JmDNS advertisement
```
