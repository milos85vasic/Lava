# Changelog

All notable changes to **Lava** (the Android client and the lava-api-go service) are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) loosely, adapted to a multi-artifact repository. Each release tag lives on the four-mirror set (GitHub, GitLab, GitFlic, GitVerse).

Tag formats:
- `Lava-Android-<version>-<code>` — Android client.
- `Lava-API-Go-<version>-<code>` — Go API service (`lava-api-go`).
- `Lava-API-<version>-<code>` — legacy Ktor proxy (`:proxy`).

The `<code>` suffix is the integer version code (Android `versionCode`, api-go `version.Code`).

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
  SUT pattern rejection, hosted-CI config rejection.
- **Local-Only CI/CD apparatus** materialized as `scripts/ci.sh`
  (single entry point, three modes), `scripts/check-fixture-
  freshness.sh`, `scripts/check-constitution.sh`. Pre-push hook
  runs `scripts/ci.sh --changed-only`. Tag script enforces an
  Android evidence-pack gate at
  `.lava-ci-evidence/Lava-Android-<version>/`.

### Fixed
- (none — this release is feature-additive)

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
