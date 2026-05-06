# Changelog

All notable changes to **Lava** (the Android client and the lava-api-go service) are documented in this file.

Per constitutional clause **§6.P (Distribution Versioning + Changelog Mandate)**, every distributed build MUST appear here BEFORE `scripts/firebase-distribute.sh` is run. The script refuses to operate without a matching entry.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) loosely, adapted to a multi-artifact repository. Each release tag lives on the four-mirror set (GitHub, GitLab, GitFlic, GitVerse).

Tag formats:
- `Lava-Android-<version>-<code>` — Android client.
- `Lava-API-Go-<version>-<code>` — Go API service (`lava-api-go`).
- `Lava-API-<version>-<code>` — legacy Ktor proxy (`:proxy`).

The `<code>` suffix is the integer version code (Android `versionCode`, api-go `version.Code`).

Per-version distribution snapshots (the exact text shipped as App Distribution release-notes) live under `.lava-ci-evidence/distribute-changelog/<channel>/<version>-<code>.md`.

---

## Lava-API-Go-2.0.11-2011 — 2026-05-06

**Channel:** container registry / remote distribution to thinker.local
**Previous published:** Lava-API-Go-2.0.10-2010 (2026-05-05)

### Added

- **scripts/distribute-api-remote.sh** — ships the lava-api-go OCI image tarball + boot script + TLS material to a remote host via passwordless SSH and brings the stack up under rootless Podman. Default target: `thinker.local`. Verifies `/health` end-to-end from the local host before reporting success. `--tear-down` mode tears containers + image down on the remote.
- **deployment/thinker/{thinker.local.env, thinker-up.sh}** — operator-customizable boot config + script that runs on the remote host. Idempotent. Pinned to rootless Podman.
- **docs/REMOTE-DISTRIBUTION.md** — runbook covering initial SSH setup, distribute, verify, tear-down.
- **`.env.example`** documents `LAVA_API_GO_REMOTE_HOST` (default `thinker.local`) + `LAVA_REMOTE_HOST_USER` (default `milosvasic`).

### Operational

- Lava-api-go now runs on the LAN host `thinker.local`. The local workstation tears down its containers + image at end-of-distribute and only builds going forward. Android clients reach the API via mDNS discovery (no client-side change needed).

### Versions bumped this cycle

| Component | Old | New |
|---|---|---|
| lava-api-go | 2.0.10 (2010) | **2.0.11 (2011)** |
| Android `:app` | 1.2.6 (1026) | (unchanged) |
| Ktor proxy | 1.0.5 (1005) | (unchanged) |

### Constitutional bindings

- §6.J — distribute script propagates failures
- §6.B — `/health` end-to-end probe, not just `podman ps`
- §6.K — image produced via the container build path
- §6.M — rootless Podman; no host power-management
- §6.P — this entry IS the §6.P-mandated changelog; per-version snapshot at `.lava-ci-evidence/distribute-changelog/container-registry/2.0.11-2011.md`

---

## Lava-Android-1.2.6-1026 — 2026-05-05

**Channels:** Firebase App Distribution (debug + release)
**Previous published:** Lava-Android-1.2.5-1025 (2026-05-05 23:43 UTC)

### Fixed (Crashlytics-driven, §6.O closure-log mandate)

- **fix(tracker-settings): Trackers-from-Settings crash (nested LazyColumn inside Column(verticalScroll))** — operator-reported via Crashlytics. Closure log at `.lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md`. Replaced `LazyColumn` with plain `Column` in `TrackerSelectorList` since the tracker list is bounded (≤ 6 entries). Validation: 2 structural tests in `feature/tracker_settings/src/test/.../TrackerSelectorListLazyColumnRegressionTest.kt`. Challenge: `app/src/androidTest/.../Challenge14TrackerSettingsOpenTest.kt`. Falsifiability rehearsal recorded.

### Added

- **§6.Q Compose Layout Antipattern Guard** — root constitution forbids nesting vertically-scrolling lazy layouts (LazyColumn, etc.) inside parents giving unbounded vertical space (verticalScroll, etc.). Per-feature structural tests + Challenge Tests on the §6.I matrix are the gates. Propagated to AGENTS.md.

### Versions bumped this cycle

| Component | Old | New |
|---|---|---|
| Android `:app` | 1.2.5 (1025) | **1.2.6 (1026)** |
| Ktor proxy | 1.0.5 (1005) | (unchanged) |
| lava-api-go | 2.0.10 (2010) | (unchanged) |

The 1.2.6 cycle is Android-only — proxy + lava-api-go did not require new fixes.

---

## Lava-Android-1.2.5-1025 — 2026-05-05

**Channels:** Firebase App Distribution (debug + release)
**Previous published:** Lava-Android-1.2.4-1024 (2026-05-05 23:25 UTC)

### Fixed (preemptive Hilt-graph hardening)

- **fix(firebase): Hilt @Provides for Firebase SDKs now tolerates getInstance() throwing.** Pre-1.2.5, a feature ViewModel that injects `AnalyticsTracker` (Login, ProviderLogin, Search, Topic) would crash on construction if `FirebaseAnalytics.getInstance(context)` / `FirebaseCrashlytics.getInstance()` / `FirebasePerformance.getInstance()` threw. The 1.2.3 → 1.2.4 fix only hardened the LavaApplication path, not the Hilt graph. 1.2.5 closes that gap:
  * `FirebaseProvidesModule` wraps each SDK accessor in `runCatching { ... }.getOrNull()` and provides nullable types.
  * `FirebaseAnalyticsTracker` accepts nullable SDKs and `runCatching`-guards every per-call SDK invocation.
  * New `NoOpAnalyticsTracker` is selected by the AnalyticsTracker `@Provides` when both Crashlytics and Analytics are unavailable.
  * Validation: `app/src/test/.../FirebaseAnalyticsTrackerTest.kt` (4 tests covering null SDKs, throwing SDK, present SDK forwarding).

### Added

- §6.P enforcement extended to `scripts/tag.sh` — refuses tags lacking CHANGELOG.md entry or per-version distribute-changelog snapshot, for android + api + api-go.
- Bluff-hunt evidence record at `.lava-ci-evidence/bluff-hunt/2026-05-05-firebase-and-distribute-mandates.json` covering 5 falsifiability rehearsals + 2 production-code targets per §6.N.2.
- Per-version distribute-changelog snapshots for the proxy (1.0.5-1005) and api-go (2.0.10-2010) channels.

### Versions bumped this cycle

| Component | Old | New |
|---|---|---|
| Android `:app` | 1.2.4 (1024) | **1.2.5 (1025)** |
| Ktor proxy | 1.0.5 (1005) | (unchanged) |
| lava-api-go | 2.0.10 (2010) | (unchanged) |

The 1.2.5 cycle is Android-only — proxy + lava-api-go did not require new fixes; their 1.2.4-cycle versions stay current.

---

## Lava-Android-1.2.4-1024 — 2026-05-05

**Channels:** Firebase App Distribution (debug + release)
**Previous published:** Lava-Android-1.2.3-1023 (2026-05-05 22:33 UTC)

### Fixed

- **fix(firebase): harden Firebase init against the 2 Crashlytics crashes recorded against 1.2.3 (1023)** — closure log at `.lava-ci-evidence/crashlytics-resolved/2026-05-05-firebase-init-hardening.md`. Removed redundant `FirebaseApp.initializeApp(this)` (FirebaseInitProvider auto-init covers it; the explicit call raced with StrictMode in some launches). Extracted Firebase init into testable `FirebaseInitializer` with per-SDK `runCatching` guards. Added Firebase keep rules to `app/proguard-rules.pro` since the BOM consumer rules don't fully cover R8 stripping of reflective entry points. Validation test: `app/src/test/.../FirebaseInitializerTest.kt` (5 tests). Challenge Test: `app/src/androidTest/.../Challenge13FirebaseColdStartResilienceTest.kt`. Falsifiability rehearsal recorded in commit body. Commit: `6758b73`.

### Added

- **AnalyticsTracker wired into real user paths** — `LoginViewModel`, `SearchViewModel`, `TopicViewModel`, `ProviderLoginViewModel` emit canonical events (`lava_login_submit`, `lava_login_success`, `lava_login_failure`, `lava_search_submit`, `lava_view_topic`, `lava_download_torrent`, `lava_download_torrent_failure`) via the Hilt-injectable `AnalyticsTracker` interface. Implementation lives in `:app` (`FirebaseAnalyticsTracker`) so feature modules remain reusable per the Decoupled Reusable Architecture rule. Commits: `6758b73`, follow-up.
- **lava-api-go FirebaseTelemetry middleware** at `internal/middleware/firebase.go` — Gin middleware that records 5xx + recovered panics as Firebase non-fatals; 4xx + 2xx logged as events. Wired into `cmd/lava-api-go/main.go` `buildRouter`. 6 unit tests with falsifiability rehearsal. Honest no-op fallback when no service-account key configured.

### Constitution / Process

- **§6.O Crashlytics-Resolved Issue Coverage Mandate** — every Crashlytics-resolved issue requires (a) validation test, (b) Challenge Test, (c) closure log under `.lava-ci-evidence/crashlytics-resolved/`. Propagated to all 16 vasic-digital submodules + lava-api-go's three doc files. Constitution checker hard-fails on missing §6.O reference in any of the 21+ doc trios. Commits: `6758b73`, `017da23`.
- **§6.P Distribution Versioning + Changelog Mandate** — every distribute action requires strictly increasing versionCode + matching CHANGELOG.md entry + per-version snapshot. `scripts/firebase-distribute.sh` enforces both gates. **This entry is the inaugural application of §6.P.**

### Versions bumped this cycle

| Component | Old | New |
|---|---|---|
| Android `:app` | 1.2.3 (1023) | **1.2.4 (1024)** |
| Ktor proxy | 1.0.4 (1004) | **1.0.5 (1005)** |
| Proxy `ServiceAdvertisement.API_VERSION` | 1.0.4 | **1.0.5** |
| lava-api-go | 2.0.9 (2009) | **2.0.10 (2010)** |

---

## Lava-Android-1.2.3-1023 — 2026-05-05 22:33 UTC

**Channel:** Firebase App Distribution (inaugural)
**Previous published:** N/A (first Firebase-instrumented build)

### Added (inaugural Firebase integration)

- Crashlytics + Analytics + Performance Monitoring wired in `LavaApplication.kt`.
- App Distribution replaces local `releases/` flow as canonical operator delivery channel.
- 5 distribution scripts under `scripts/`: `firebase-env.sh`, `firebase-setup.sh`, `firebase-distribute.sh`, `firebase-stats.sh`, `distribute.sh`.
- Tester roster loaded from `.env` (`LAVA_FIREBASE_TESTERS_*`).
- 2 anti-bluff bash regression tests under `tests/firebase/` (no WARN-swallow + gitignore-coverage).
- `lava-api-go/internal/firebase/` server-side skeleton with no-op fallback when service-account key absent.

Commit: `e9de508`.

### Versions bumped this cycle

| Component | Old | New |
|---|---|---|
| Android `:app` | 1.2.2 (1022) | 1.2.3 (1023) |
| Ktor proxy | 1.0.3 (1003) | 1.0.4 (1004) |
| Proxy `ServiceAdvertisement.API_VERSION` | 1.0.1 (3 versions stale!) | 1.0.4 |
| lava-api-go | 2.0.8 (2008) | 2.0.9 (2009) |

---

## Lava-Android-1.2.0-1020 — 2026-05-01

First release of the **multi-tracker SDK foundation** (SP-3a). The
Android client now supports two trackers — RuTracker (existing) and
RuTor (new) — with user-selectable active tracker, custom mirrors,
mirror health tracking, and an explicit cross-tracker fallback flow.

### Added
- **RuTor (rutor.info / rutor.is) tracker support** — anonymous-by-
  default per decision 7b-ii; capabilities `SEARCH + TOPIC + DOWNLOAD`.
- **Tracker selection UI in Settings → Trackers** — list of registered
  trackers, single-tap to switch the active tracker, per-tracker
  health summary.
- **Custom mirror entry per tracker** — operators can add mirrors
  beyond the bundled defaults; persisted in Room
  (`tracker_mirror_user`).
- **Mirror health tracking** — periodic `MirrorHealthCheckWorker`
  (15-min interval) probes each registered mirror; status
  `HEALTHY` / `DEGRADED` / `UNHEALTHY` persisted in
  `tracker_mirror_health`.
- **Cross-tracker fallback modal** — when all mirrors of the active
  tracker hit `UNHEALTHY`, the SDK emits
  `CrossTrackerFallbackProposed`; the UI presents a modal offering
  the alternative tracker. Accept → re-issues the call on the alt
  tracker; dismiss → explicit failure UI (snackbar). No silent
  fallback.
- **`docs/sdk-developer-guide.md` (partial draft)** — 7-step recipe
  for adding a third tracker, paper-traced through the existing
  RuTor module.
- **8 Compose UI Challenge Tests** under
  `app/src/androidTest/kotlin/lava/app/challenges/` (C1-C8) — each
  with a documented falsifiability rehearsal protocol.

### Changed
- **Internal: RuTracker implementation now fully decoupled behind
  the multi-tracker SDK.** `core/network/rutracker` git-moved to
  `core/tracker/rutracker`. `RuTrackerClient` implements
  `TrackerClient` + applicable feature interfaces (Searchable,
  Browsable, Topic, Comments, Favorites, Authenticatable,
  Downloadable). `SwitchingNetworkApi` now delegates to
  `LavaTrackerSdk` rather than to a single hard-wired client.
- **New `vasic-digital/Tracker-SDK` submodule mounted at
  `Submodules/Tracker-SDK/`.** Generic primitives (registry,
  mirror-config store, test scaffolding). Pin is **frozen by
  default** per the Decoupled Reusable Architecture rule. Mirrored
  to GitHub + GitLab (2-upstream scope per 2026-04-30 spec
  deviation).

### Constitutional
- **Added clauses 6.D (Behavioral Coverage Contract), 6.E (Capability
  Honesty), 6.F (Anti-Bluff Submodule Inheritance) to root
  `CLAUDE.md`** and cascaded to `core/CLAUDE.md`,
  `feature/CLAUDE.md`, `lava-api-go/{CLAUDE,AGENTS}.md`,
  `Submodules/Tracker-SDK/{CLAUDE,CONSTITUTION,AGENTS}.md`, and
  root `AGENTS.md`.
- **Added the Seventh Law (Anti-Bluff Enforcement, all 7 clauses)**
  with mechanical pre-push hook enforcement at `.githooks/pre-push`:
  Bluff-Audit commit-message stamp on every test commit, mock-the-
  SUT pattern rejection, hosted-CI config rejection. The Seventh Law
  is binding on every test commit and on every release tag —
  `scripts/tag.sh` refuses to operate without
  `.lava-ci-evidence/<TAG>/real-device-verification.md` at status
  `VERIFIED` and per-Challenge-Test attestation files.
- **Local-Only CI/CD apparatus** materialized as `scripts/ci.sh`
  (single entry point, three modes — `--changed-only`,
  `--full`, `--smoke`), `scripts/check-fixture-freshness.sh`,
  `scripts/check-constitution.sh`, `scripts/bluff-hunt.sh`
  (Seventh Law clause 5 recurring hunt driver). Pre-push hook runs
  `scripts/ci.sh --changed-only`. Tag script enforces an Android
  evidence-pack gate at `.lava-ci-evidence/Lava-Android-<version>/`.

### Phases (commit summary)

The SP-3a development arc spans 6 phases (Phase 0 audit + Phases 1–5
implementation). Approximate per-phase commit counts:

| Phase | Scope                                                                  | Commits |
|-------|------------------------------------------------------------------------|---------|
| 0     | Pre-implementation audit, ledger seeding, equivalence test scaffolding | 5       |
| 1     | Foundation — `core/tracker/api`, registry, mirror, testing modules     | 12      |
| 2     | RuTracker decoupling — git-mv, `RuTrackerClient`, parser refit         | 40      |
| 3     | RuTor implementation — descriptor, parsers, feature impls, fixtures    | 41      |
| 4     | Mirror health, cross-tracker fallback, `tracker_settings` UI           | 20      |
| 5     | Constitution updates, 8 Challenge Tests, scripts/ci.sh, tag gate       | 26      |
| —     | Misc (Seventh Law, JVM-17 hardening, bluff audit, phase wraps)         | 4       |
| —     | Documentation polish (this release)                                    | 5       |
| **SP-3a total** |                                                              | **153** |

Phase 5 closes the implementation arc. Real-device verification
(Task 5.22) is the operator-required gate before tagging — see
"Known limitations" below.

### Known limitations (operator-required gates)

These are NOT bugs; they are explicit acceptance gates the operator
MUST satisfy before tagging Lava-Android-1.2.0-1020:

- **Task 5.22 — real-device Challenge Test attestation.** The 8 Compose
  UI Challenge Tests (`app/src/androidTest/kotlin/lava/app/challenges/`)
  cannot be run from the agent environment. The operator MUST run each
  on a real Android device (API 26+, internet-connected), capture the
  user-visible state per the test's primary assertion, perform the
  falsifiability mutation listed in the test header, and update each
  `.lava-ci-evidence/Lava-Android-1.2.0-1020/challenges/C<n>.json`
  from `PENDING_OPERATOR` to `VERIFIED`. `scripts/tag.sh` refuses
  to operate without all 8 at `VERIFIED`.
- **Task 5.25 — connectedAndroidTest runner not yet wired into
  `:app/build.gradle.kts`.** Until the androidx.test runner deps and
  the `connectedDebugAndroidTest` task are wired, the operator's
  C1–C8 verification is performed by manually exercising each
  scenario on the device rather than by the gradle task. This is
  constitutional debt tracked in `feature/CLAUDE.md`.
- **Task 4.20 — Phase 4 integration smoke on real device.** Phase 4
  shipped the cross-tracker fallback modal end-to-end; Task 4.20 is
  the integration smoke (mirror probe loop + fallback on real
  rutracker / rutor mirrors). The smoke commit landed
  (`80975e0 sp3a-4.20`) but the real-device replay falls under the
  same Task 5.22 gate above.

### Latent findings (open + resolved)

Tracked in
[`docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`](docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md):

- **LF-1** — `MenuViewModelTest` holds `TestBookmarksRepository`
  without exercising it. **OPEN** — tripwire fires when first
  `MenuAction.ClearBookmarks` test path is added.
- **LF-2** — `TestHealthcheckContract` is currently a future-facing
  tripwire (lava-api-go has no healthcheck block at HEAD). **OPEN** —
  tripwire fires the moment a healthcheck is re-introduced.
- **LF-3** — Tracker chain compiles to JVM 21 while Android targets
  JVM 17. **RESOLVED in Phase 2 Section E wrap-up** (Tracker-SDK pin
  `b779fda` enforces JVM 17 on every SDK subproject).
- **LF-5** — `RuTrackerDescriptor` declares `UPLOAD` and
  `USER_PROFILE` without backing feature interfaces. **OPEN** —
  letter-of-the-law clause 6.E is satisfied (no caller can ask), but
  the descriptor makes a forward-looking claim with no impl. Triggers
  before any phase that depends on those capabilities (cross-tracker
  fallback ranking, SP-3a-bridge).
- **LF-6** — `TorrentItem.sizeBytes` is permanently null for
  rutracker (the legacy scraper discards the byte count and keeps
  only the formatted display string in `metadata["rutracker.size_text"]`).
  **OPEN** — triggers before any cross-tracker comparison logic that
  needs numeric size.

### Fixed
- (none — this release is feature-additive)

---

## Lava-API-1.0.2-1002 — 2026-05-01

Maintenance release of the legacy Ktor proxy. Routine patch bump after a clean
re-build + re-test cycle — no behavioral changes vs `Lava-API-1.0.1-1001`.

### Operational
- Container image rebuilt against the current Submodules/Containers pin and
  pushed to `localhost/lava-proxy:dev` via `./build_and_push_docker_image.sh`
  / `./build_and_release.sh`.
- Boot verified: `./start.sh --both` brings the proxy up alongside lava-api-go;
  `lava-containers status` reports `Healthy: true`, LAN IP advertised via
  mDNS service-type `_lava._tcp.local.` with symmetric TXT records (engine,
  version, protocols, compression, tls).
- Real-network smoke: `GET http://localhost:8080/` returns `200 OK` after
  ~1.7s warmup; `GET /forum` returns 142 KB of legacy Ktor scrape output.

### Constitutional
- Inherits the new **Seventh Law (Anti-Bluff Enforcement)** added to root
  `CLAUDE.md` on 2026-04-30. The pre-push hook's Bluff-Audit-stamp gate +
  forbidden-test-pattern gate apply to all future proxy commits.

### Tests
- All 18 `lava-api-go` Go test packages green at HEAD; the proxy's
  Kotlin-side tests inherit the project-wide Spotless / ktlint /
  unit-test gate run by `scripts/ci.sh --changed-only`.

---

## Lava-API-Go-2.0.7-2007 — 2026-05-01

Maintenance release of the Go API service. Re-anchors the version to the
post-SP-3a HEAD with all consumer-side constitutional infrastructure
(submodule mirrors, Seventh Law inheritance, integration-test podman
runs) verified against real backends.

### Verified — pretag (Sixth Law clause 5 + SP-2 Phase 13.1)
- `lava-api-go/scripts/pretag-verify.sh` exercised the running api-go
  on `https://localhost:8443` with all 5 scripted black-box probes:
  - `GET /` → `200` (5B, 745ms)
  - `GET /forum` → `200` (142 KB, 920ms)
  - `GET /search?query=test` → `401` (auth gate honored)
  - `GET /torrent/1` → `404` (known empty topic)
  - `GET /favorites` → `401` (auth gate honored)
- Evidence at `.lava-ci-evidence/1f7f3c0610a353048ef1c3d9daffd41f5aa7f7b1.json`.

### Verified — integration tests against real podman containers
- **Phase 4.3 cache integration:** 7 tests PASS (1.13s) against
  `docker.io/postgres:16-alpine` via `scripts/run-test-pg.sh`.
  Real key generation + Set/Get/Invalidate cycle exercised.
- **Phase 10.2 e2e:** 6 tests PASS (16.49s). Real Gin engine, real
  auth middleware, real handlers; no mocks below the SUT.
- Evidence at `.lava-ci-evidence/sp2-podman-tests-2026-04-30/integration-evidence.json`.

### Constitutional
- Inherits the new **Seventh Law (Anti-Bluff Enforcement)** with seven
  mechanically-enforced clauses. `lava-api-go/CLAUDE.md` and
  `lava-api-go/AGENTS.md` reference the Seventh Law's text in the parent
  Lava `CLAUDE.md`. Bluff-Audit stamps now mandatory on every Go test
  commit (`*_test.go`).
- All 16 vasic-digital submodules consumed by `lava-api-go` (Auth, Cache,
  Challenges, Concurrency, Config, Containers, Database, Discovery,
  HTTP3, Mdns, Middleware, Observability, RateLimiter, Recovery, Security,
  Tracker-SDK) carry the Seventh Law inheritance pointer.

### Mirror status
- Submodule pin lava-pin/2026-04-30-seventh-law-anchor pushed to GitHub
  + GitLab for all 13 affected submodules; per-mirror SHA convergence
  verified via `git ls-remote` per Sixth Law clause 6.C.

---

## Lava-API-Go-2.0.6-2006 — 2026-04-29

Critical bugfix release. **Upgrade strongly recommended** for any 2.0.x deployment — every prior 2.0.x build had at least one of the four root causes below silently breaking authenticated endpoints.

### Fixed
- **SP-3.5 — login 502 (three independent root causes in `internal/rutracker.Client`):**
  1. **IPv6 silent drop on Cloudflare's rutracker edge.** Go's `net.Dialer` preferred AAAA records; TLS handshake completed; request body uploaded; response was silently dropped. Fixed by `Transport.DialContext` rewriting `tcp` / `tcp6` → `tcp4` for the rutracker upstream client only.
  2. **Default redirect-following discarded the `bb_session` cookie.** Login response is `HTTP 302 + Location:/forum/index.php + Set-Cookie:bb_session=…`; the auth token is on the 302, not on `/index.php`. Default `http.Client.CheckRedirect` followed the 302 silently and the scraper saw the unauthenticated login form. Fixed by `CheckRedirect = http.ErrUseLastResponse`.
  3. **`charset.NewReader` on an empty body returned EOF.** The 302 carries `content-type: text/html; charset=cp1251` AND a zero-length body; first Read returned EOF; auth headers we needed were never inspected. Fixed by reading raw bytes first and short-circuiting on `len==0`.
- **SP-3.5b — search/favorites/etc. all returning empty for valid sessions.** `auth.UpstreamCookie` unconditionally prepended `bb_session=` to the `Auth-Token` header value; the Android client (and the legacy Ktor proxy) store the **raw upstream Set-Cookie line** at login, so the upstream request landed with `Cookie: bb_session=bb_session=…` — a doubly-prefixed cookie that rutracker parsed as anonymous. Fixed by forwarding tokens that already contain `=` verbatim.

### Diagnostics
- `internal/handlers/login.go` now logs the raw scraper error (err.Error() only — credentials are never in err) so a future 502 is debuggable from `podman logs` alone.

### Tests (Sixth Law)
- `TestNewClient_TransportForcesIPv4` — pins the IPv4 rewrite.
- `TestNewClient_DoesNotFollowRedirects` — pins `CheckRedirect = ErrUseLastResponse`.
- `TestUpstreamCookieForwardsCookieLineVerbatim` — pins the verbatim-forward branch with the real-world Set-Cookie shape.
- `TestUpstreamCookie_TokenWithEqualsForwardsVerbatim` — defensive guard that any `name=value` pair forwards as-is.

Each test is a Sixth-Law Challenge with a documented MUTATION rehearsal in the test KDoc.

### Verified
- Operator's own credentials, real LAN, real device:
  - `POST /login` → `200 + Success`.
  - `GET /search?query=ps4` → `{"page":1,"pages":10,"torrents":[…50 hits…]}` (first hit "Eternights").
- Pretag-verify (Sixth Law clause 5 mechanical gate): all 5 black-box probes green.

---

## Lava-Android-1.1.3-1013 — 2026-04-29

Real-device verified on Samsung Galaxy S23 Ultra (SM-S918B / Android 16). This release fixes every issue surfaced by the operator's real-device testing on 2026-04-29 against the lava-api-go LAN service.

### Fixed
- **SP-3.4 — mDNS service-type cross-match.** The `_lava._tcp` listener was cross-matching `_lava-api._tcp` services because `"lava-api".contains("lava")` is true. A discovered lava-api-go service ended up classified as the legacy Ktor proxy (`Endpoint.Mirror`), routing to the wrong port (8080) instead of the Go API's port (8443). Fixed by replacing the substring filter with strict prefix-with-dot matching (`matchesServiceType`).
- **SP-3.3 — Connections list cleanup, port-aware reachability, route-clean LAN Mirror:**
  - Database migration v5→v6 deletes legacy `type='Proxy'` rows (collapses the duplicate "Main" entry) and `Mirror` rows whose host contains `:` (legacy `ip:port` shape).
  - `ConnectionService.isReachable` is now Endpoint-aware: TCP probes the exact host:port the network layer would actually open per variant.
  - `NetworkApiRepositoryImpl.proxyApi` parses `host:port` out of `Mirror.host` instead of feeding it through Ktor's URLBuilder as a hostname; LAN Mirror without an explicit port defaults to 8080.
  - Discovery strips the embedded port at conversion time so persisted `Mirror` rows are bare-host shaped.
- **SP-3.2 — `Endpoint.Proxy` removed; Unauthorized search UX:**
  - Removed `Endpoint.Proxy` from the model entirely (the public lava-app.tech instance was retired). Pre-existing rows are migrated forward to `Endpoint.Rutracker` on read.
  - Search shows a "Login required" empty-state with a Login button when not signed in (no more misleading "Nothing found").
- **SP-3.1 — LAN HTTPS without manual cert install.** A dedicated permissive-TLS `OkHttpClient` (`@Named("lan")`) accepts any LAN-side server cert, used **only** for `Endpoint.GoApi` and LAN `Endpoint.Mirror`. The strict default client is unchanged for public Internet endpoints. The user mandate — "must work without manual installation" — is satisfied.

### Changed
- `Endpoint.GoApi` now renders as **"Lava API"** in the Connections list (was "Mirror"), so the operator can distinguish a discovered Go service from a manually-configured rutracker mirror at a glance.

### Tests (Sixth Law)
- Killed a 2h22m gradle test hang in `:core:domain:testDebugUnitTest` caused by 4 interlocking Third-Law bluff fakes (TestDispatchers, TestEndpointsRepository, TestLocalNetworkDiscoveryService, MainDispatcherRule).
- Added 25+ Sixth-Law Challenge tests across `:core:data`, `:core:domain`, `feature:connection`, `feature:menu`, `feature:search_result`. Each carries a documented MUTATION falsifiability rehearsal.

### Compatibility
- Tested against `lava-api-go 2.0.6` (current API release).
- Also works against the legacy Ktor proxy and rutracker direct.

---

## Lava-API-Go-2.0.5-2005 — 2026-04-29

Superseded by 2.0.6. Login fix (SP-3.5 root causes 1+2+3) shipped here; the search/favorites empty-results fix (SP-3.5b) landed in 2.0.6.

---

## Lava-Android-1.1.2-1012 — 2026-04-29

Superseded by 1.1.3. Issue-1/2/3 SP-3.3 fixes shipped here; the SP-3.4 mDNS cross-match fix landed in 1.1.3.

---

## Lava-API-Go-2.0.4-2004 — 2026-04-29

Container build hardening:
- `start.sh` and `build_and_release.sh` now export `BUILDAH_FORMAT=docker` so Podman builds Docker-format images that persist `HEALTHCHECK` directives.
- `build_and_release.sh` always rebuilds the `:dev` image with `--format=docker` before saving, so the saved image tarball never carries a stale OCI-format build.

---

## Lava-API-Go-2.0.2-2002 — 2026-04-29

Sixth Law inheritance docs added across all `vasic-digital` submodules.

---

## Lava-API-Go-2.0.0..2.0.2 — 2026-04-28..29

SP-2 — initial Go service migration. Cross-backend parity with the Ktor proxy verified (8/8 fixtures), k6 load tests green, real Postgres in podman, real HTTP/3 client. See `docs/superpowers/specs/2026-04-28-sp2-go-api-migration-design.md` and `docs/superpowers/plans/2026-04-28-sp2-go-api-migration.md` for the full design.

---

## Lava-Android-1.1.0-1010 — 2026-04-29

SP-3 — Android dual-backend support: discover and route to lava-api-go alongside the legacy proxy.

---

## Earlier history

See `git log --oneline --decorate` for the full history before the SP-2/SP-3 series; the changelog above starts at the point where the Sixth Law was instituted (2026-04-28).
