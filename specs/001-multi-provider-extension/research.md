# Research: Multi-Provider Extension

**Feature**: Multi-Provider Extension  
**Source**: Consolidated from `docs/refactoring/multi_provieders/Lava_Multi_Provider_Extension_Research.pdf` and codebase analysis  
**Date**: 2026-05-02

## 1. Existing System Architecture

### 1.1 Go API Service (lava-api-go)

- **Language**: Go 1.25.0
- **Framework**: Gin Gonic v1.12.0
- **Transport**: HTTP/3 (QUIC/UDP) primary, HTTP/2-over-TLS fallback on port 8443
- **Metrics**: Plain-HTTP listener at 127.0.0.1:9091
- **mDNS**: Announces as `_lava-api._tcp` for local network discovery
- **Current abstraction**: `ScraperClient` interface with 15 methods, implemented by `*rutracker.Client`
- **Auth**: `internal/auth/passthrough.go` — RuTracker-specific, prepends `bb_session=` to bare tokens
- **Routes**: 13 flat routes (`/forum`, `/search`, `/topic/{id}`, etc.) implicitly bound to RuTracker

### 1.2 Android Client

- **Architecture**: Clean multi-module, Hilt DI, Orbit MVI, Jetpack Compose, Kotlin Serialization
- **Tracker SDK**: `core/tracker/` with 7 submodules (api, registry, mirror, client, rutracker, rutor, testing)
- **Current trackers**: RuTracker (12 capabilities, CAPTCHA_LOGIN, Windows-1251), RuTor (8 capabilities, FORM_LOGIN, UTF-8)
- **Facade**: `LavaTrackerSdk` — routes calls through active tracker's `TrackerClient.getFeature<T>()`
- **Navigation**: Custom DSL on Navigation-Compose with context receivers
- **TV support**: `TvActivity` extends `MainActivity`, `PlatformType.TV`

## 2. New Provider Research

### 2.1 NNMClub (nnmclub.to)

- **Platform**: phpBB-based forum with integrated tracker
- **Encoding**: Windows-1251
- **Auth**: phpBB cookie session (no captcha). Required for `.torrent` download only.
- **Capabilities**: SEARCH, BROWSE, FORUM, TOPIC, COMMENTS (partial), FAVORITES (partial), TORRENT_DOWNLOAD, MAGNET_LINK, AUTH_REQUIRED, RSS
- **Search**: POST to `/forum/tracker.php` with `nm`, `f[]`, `o`, `s`, `tm`, `sds`, pagination via `start`
- **Topic**: `viewtopic.php?t={ID}` — magnet from `a[href^="magnet:"]`, torrent from `a[href^="download.php?id="]`
- **File list**: `filelst.php?attach_id={ID}`

### 2.2 Kinozal (kinozal.tv)

- **Focus**: Video content (movies, TV, anime, music videos)
- **Encoding**: Windows-1251
- **Auth**: POST to `/takelogin.php`, `uid` and `pass` cookies. No captcha.
- **Mirrors**: kinozal.me, kinozal.guru
- **Capabilities**: SEARCH, BROWSE, TOPIC, COMMENTS, TORRENT_DOWNLOAD, AUTH_REQUIRED
- **Freeleech**: Gold (0x) via `a.r1`, Silver (0.5x) via `a.r2`
- **Search**: GET `/browse.php` with `s`, `c`, `g`, `w`, `t`, `f`, `page` (50 results/page)
- **Topic**: `details.php?id={ID}` + AJAX `get_srv_details.php?id={ID}&pagesd=2` for file list
- **Download**: `dl.kinozal.tv/download.php?id={TORRENT_ID}`

### 2.3 Internet Archive (archive.org)

- **Type**: Public digital library, HTTP-based (not torrent)
- **Auth**: None for read/search
- **APIs**: Advanced Search API (`advancedsearch.php`), Scrape API (`services/search/v1/scrape`), Metadata API (`/metadata/{IDENTIFIER}`)
- **Capabilities**: SEARCH, BROWSE, FORUM (Collections), TOPIC (Item), COMMENTS (Reviews partial), TORRENT_DOWNLOAD (partial — some items have .torrent)
- **Rate limiting**: Honor 429 + Retry-After, ~4 concurrent requests, descriptive User-Agent mandatory
- **Query syntax**: Lucene-like (`mediatype:movies`, `collection:nasa`, date ranges)

### 2.4 Project Gutenberg (gutenberg.org)

- **Type**: Public domain eBook library, HTTP-based
- **Auth**: None
- **Anti-bot**: Direct web scraping forbidden. Must use OPDS, RDF catalog, or RapidAPI.
- **Capabilities**: SEARCH, BROWSE, FORUM (Bookshelves), TOPIC (Book), TORRENT_DOWNLOAD (No), RSS (OPDS partial)
- **Catalog**: Daily RDF/XML at `/cache/epub/feeds/pg_catalog.rdf.zip`
- **Harvest endpoint**: `/robot/harvest` with 2-second minimum delay between requests
- **Formats**: Plain text, EPUB (with/without images), Kindle, HTML
- **Download URLs**: Predictable patterns based on book ID

## 3. Key Design Decisions

| Decision | Rationale | Alternatives Rejected |
|----------|-----------|----------------------|
| Hybrid capability model (4 core mandatory, extended optional) | Aligns with constitutional 6.E Capability Honesty. Prevents "Not implemented" stub violations. | All-capabilities-mandatory (impossible for HTTP providers), fully optional (breaks unified UX) |
| Full deduplication with multi-provider badge | Spec requirement (Q4). Reduces result clutter while preserving provider transparency. | No deduplication (cluttered), silent deduplication (hides provider diversity) |
| Per-provider configurable timeout (default 10s) | Spec requirement (Q1). Balances user patience with provider diversity. | Global fixed timeout (inflexible), no timeout (poor UX) |
| Automatic cloud backup via Android Backup Service | Spec requirement (Q3). Zero-friction recovery without custom infrastructure. | No backup (poor UX), encrypted export file (extra friction), biometric backup (device dependency) |
| Recent-content offline cache (50 results, 20 topics, 24h) | Spec requirement (Q5). Limited offline utility without massive sync infrastructure. | No offline cache (poor UX), full provider sync (too complex) |
| Go API route prefix `/v1/{provider}/...` | RESTful, cacheable, explicit. Enables per-provider cache namespacing. | Header-based dispatch (less cacheable, less explicit) |

## 4. Technology Choices

### Android

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Credential encryption | Android Keystore + AES-256-GCM + EncryptedSharedPreferences fallback | Hardware-backed when available, software fallback for older devices |
| Offline cache | Room database with TTL field | Unified with existing persistence layer, supports complex queries |
| Deduplication engine | Kotlin Flow operators + in-memory map | Real-time streaming requirement, avoids blocking I/O |
| Concurrent search | Kotlin Coroutines `async/awaitAll` + `SharedFlow` | Native to existing stack, supports cancellation per provider |
| Catalog sync | WorkManager with WiFi + charging constraints | Battery-conscious, reliable scheduling |

### Go API

| Component | Choice | Rationale |
|-----------|--------|-----------|
| Provider interface | Single interface with 18 methods | Generalizes ScraperClient while supporting both HTML scrapers and JSON APIs |
| Auth middleware | Extended Auth-Token header format `provider_id:type:value` | Backward compatible with existing bare tokens |
| Routing | Gin parameter-based `/v1/:provider/...` | Clean, cache-friendly, versioned |
| Database migrations | golang-migrate (existing) | Consistent with existing migration strategy |

## 5. Open Questions Resolved During Planning

| Question | Resolution | Source |
|----------|------------|--------|
| How to handle slow providers during search? | Configurable per-provider timeout, default 10s | Clarification Q1 |
| Should all providers implement all capabilities? | Hybrid: 4 core mandatory, extended optional | Clarification Q2 |
| Credential backup strategy? | Android Backup Service automatic backup | Clarification Q3 |
| Search deduplication approach? | Full deduplication with multi-provider badge | Clarification Q4 |
| Offline support scope? | Recent-content cache only (50 results, 20 topics, 24h) | Clarification Q5 |

## 6. References

- `docs/refactoring/multi_provieders/Lava_Multi_Provider_Extension_Research.pdf` — Comprehensive research with provider details, architecture diagrams, wireframes
- `docs/sdk-developer-guide.md` — Step-by-step recipe for adding tracker modules
- `docs/ARCHITECTURE.md` — Project architecture overview
- `docs/LOCAL_NETWORK_DISCOVERY.md` — mDNS discovery flow
- `core/CLAUDE.md` — Scoped Anti-Bluff rules for core modules
- `feature/CLAUDE.md` — Scoped Anti-Bluff rules for feature modules
- `.specify/memory/constitution.md` — Project constitution (7 Laws, Local-Only CI/CD, Decoupled Architecture)
