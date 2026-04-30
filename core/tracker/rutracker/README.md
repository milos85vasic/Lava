# :core:tracker:rutracker

RuTracker (rutracker.org / rutracker.net / rutracker.cr) tracker
plugin. Implements `TrackerClient` + every applicable feature
interface from `:core:tracker:api`. Decoupled from the legacy
`core/network/rutracker` location in SP-3a Phase 2 (2026-04-30) by a
git-mv that preserved history; the legacy `core/network/rutracker`
path now does not exist.

## Capability matrix

Per `RuTrackerDescriptor.capabilities` and the corresponding
`getFeature<T>()` dispatch in `RuTrackerClient`. Twelve declared
capabilities (the full SP-3a vocabulary minus `RSS`):

| Capability         | Implemented? | Feature impl                  | Notes                                                            |
|--------------------|--------------|-------------------------------|------------------------------------------------------------------|
| `SEARCH`           | yes          | `RuTrackerSearch`             | `tracker.php?nm=…`; per-page pagination                          |
| `BROWSE`           | yes          | `RuTrackerBrowse`             | Forum + category browsing (`viewforum.php?f=…`)                  |
| `FORUM`            | yes          | `RuTrackerBrowse`             | Nested forum tree (`index.php` parsed as `ForumTree`)            |
| `TOPIC`            | yes          | `RuTrackerTopic`              | `viewtopic.php?t=…`; full description, attachment list           |
| `COMMENTS`         | yes          | `RuTrackerComments`           | Read + post (post requires login + form-token + captcha)         |
| `FAVORITES`        | yes          | `RuTrackerFavorites`          | `tracker.php?u=…&nm=…&hr=1` for hidden-favorites workaround       |
| `TORRENT_DOWNLOAD` | yes          | `RuTrackerDownload`           | `dl.php?t=…`; cookie session required                            |
| `MAGNET_LINK`      | yes          | `RuTrackerDownload`           | Magnet URI extracted from topic page                             |
| `AUTH_REQUIRED`    | yes          | `RuTrackerAuth`               | `bb_session` cookie; verbatim Set-Cookie forwarding (SP-3.5b)    |
| `CAPTCHA_LOGIN`    | yes          | `RuTrackerAuth`               | Captcha image + form-key extracted from `login.php`              |
| `RSS`              | **no**       | —                             | RuTracker's RSS endpoint requires per-user secret URL; out of scope |
| `UPLOAD`           | **declared, not surfaced** | —               | Latent finding LF-5 — descriptor declares it but no feature impl yet  |
| `USER_PROFILE`     | **declared, not surfaced** | —               | Latent finding LF-5 — descriptor declares it but no feature impl yet  |

`UPLOAD` and `USER_PROFILE` are present in the descriptor for
forward-looking reasons; the underlying scrapers
(`RuTrackerInnerApi.uploadTorrent`, `getProfile`) exist in this module.
Because the matching `TrackerFeature` interfaces
(`UploadableTracker`, `ProfileTracker`) do not yet exist in
`:core:tracker:api`, no caller can request them via `getFeature<T>()`,
so the literal letter of clause 6.E is satisfied — but this is logged
as a **latent finding (LF-5, OPEN)** in
`docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md` and
must be resolved before the next phase that depends on those
capabilities (cross-tracker fallback ranking, SP-3a-bridge).

`RSS` is intentionally absent (RuTracker's RSS feed requires a
per-user secret URL — exposing it from a generic tracker plugin
would leak user state).

## Encoding

**Windows-1251** for HTML response bodies. `RuTrackerHttpClient`
explicitly transcodes via `Charsets.forName("windows-1251")` before
passing to Jsoup. Mismatched encoding produces �-rendered Cyrillic
strings; the production wiring is exercised by every parser test in
`mapper/*Test.kt`.

For request bodies (`POST` to `login.php`, `posting.php`), the
client encodes form fields in Windows-1251 as well — the rutracker
backend rejects UTF-8 form posts with HTTP 200 + a generic error page.

## Authentication policy

`AuthType.CAPTCHA_LOGIN`. Login flow:

1. `GET /forum/login.php` → parse the form-key + captcha image URL.
2. Operator solves the captcha (UI surface in `:feature:login`).
3. `POST /forum/login.php` with form-key + captcha solution +
   credentials → server responds `302 + Set-Cookie: bb_session=…`.
4. The session cookie is persisted in encrypted preferences
   (`:core:preferences`) and forwarded verbatim on subsequent
   requests via `Auth-Token` header (SP-3.5b regression — verbatim
   forwarding, no `bb_session=` re-prefix).

Logout clears the cookie locally and POSTs the logout endpoint to
invalidate server-side.

## Mirrors

Bundled defaults (also exposed in `RuTrackerDescriptor.baseUrls`):

| Mirror                 | Priority | Protocol | Region | Notes                  |
|------------------------|----------|----------|--------|------------------------|
| `https://rutracker.org`| 0        | HTTPS    | global | Primary; Cloudflare-fronted |
| `https://rutracker.net`| 1        | HTTPS    | global | First fallback         |
| `https://rutracker.cr` | 2        | HTTPS    | global | Anti-block fallback    |

`MirrorHealthCheckWorker` probes each mirror every 15 minutes; the
probe checks for the substring `"rutracker"` in the response body
(configured as `RuTrackerDescriptor.expectedHealthMarker`).

The IPv4 rewrite hardening (SP-3.5 root cause #1) is enforced by the
upstream `lava-api-go` service, not by this module — direct rutracker
calls from the Android client use OkHttp's default dialer.

## Real-tracker integration test

There is no `integrationTest` source set in this module today; the
real-tracker verification path runs through the lava-api-go service
(`lava-api-go/scripts/pretag-verify.sh` against rutracker upstream).
Adding an `:integrationTest -PrealTrackers=true` source set is
tracked as future work; for now the load-bearing real-stack gate is
the Challenge Test C2 (authenticated search on RuTracker) on a real
device.

## Test discipline

Tests in this module:

- `RuTrackerDescriptorTest` — capability honesty contract.
- `mapper/*Test.kt` — parser tests against
  `src/test/resources/fixtures/rutracker/` HTML snapshots in
  Windows-1251. Every test exercises the production mapper directly,
  no shortcuts.

Per the Anti-Bluff Pact (Sixth + Seventh Laws):

- No mocking of any class under `lava.tracker.rutracker.*`.
- Bluff-Audit stamp required on every test commit (Seventh Law
  clause 1).
- Encoding correctness is the load-bearing assertion — a parser test
  that passes against a UTF-8-misencoded fixture is, by clause 1,
  a bluff test.

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`; the SDK
> developer guide §5–§7
> ([`docs/sdk-developer-guide.md`](../../../docs/sdk-developer-guide.md))
> for the mechanical compliance gates that bind this module; LF-5 in
> `docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md` for
> the UPLOAD/USER_PROFILE deferred-feature finding.
