# :core:tracker:rutor

RuTor (rutor.info / rutor.is) tracker plugin. Implements `TrackerClient`
+ feature interfaces from `:core:tracker:api`. Added in SP-3a Phase 3
(2026-04-30).

## Capability matrix

Per `RuTorDescriptor.capabilities` and the corresponding `getFeature<T>()`
dispatch in `RuTorClient`:

| Capability         | Implemented? | Feature impl                | Notes                                             |
|--------------------|--------------|-----------------------------|---------------------------------------------------|
| `SEARCH`           | yes          | `RuTorSearch`               | Across all categories; pagination via `?p=`       |
| `BROWSE`           | yes          | `RuTorBrowse`               | RuTor categories (flat list, no nested forum tree) |
| `FORUM`            | **no**       | —                           | RuTor has categories only; no forum tree shape    |
| `TOPIC`            | yes          | `RuTorTopic`                | Description, files list, magnet                   |
| `COMMENTS`         | yes          | `RuTorComments`             | Read public comments; post requires login         |
| `FAVORITES`        | **no**       | —                           | No per-user favorites endpoint comparable to RuTracker |
| `TORRENT_DOWNLOAD` | yes          | `RuTorDownload`             | `.torrent` file download                          |
| `MAGNET_LINK`      | yes          | `RuTorDownload`             | Magnet URI extraction from topic page             |
| `AUTH_REQUIRED`    | yes          | `RuTorAuth` (Authenticatable) | Login is invoked only when an authenticated op is requested |
| `CAPTCHA_LOGIN`    | **no**       | —                           | RuTor uses a plain form POST; no captcha          |
| `RSS`              | yes          | `RuTorSearch`               | Search results expose RSS feed URL                |
| `UPLOAD`           | **no**       | —                           | Out of scope for 1.2.0                            |
| `USER_PROFILE`     | **no**       | —                           | Out of scope for 1.2.0                            |

The "no" rows are absent from the descriptor's `capabilities` set —
not silently mapped to a stub. Constitutional clause 6.E (Capability
Honesty) requires `getFeature<T>()` to be exhaustive on the declared
set, and the `:core:tracker:registry` test asserts non-null resolution
at JVM-test time.

## Encoding

UTF-8 throughout. No charset transcoding (unlike RuTracker which is
Windows-1251). The `RuTorHttpClient` enforces `Charsets.UTF_8` on
every response body.

## Authentication policy (decision 7b-ii)

**Anonymous by default.** Read operations (search, browse, topic,
download, magnet, RSS) never require login. Login is invoked only when
the SDK consumer calls an authenticated operation
(`CommentsTracker.addComment()` is the only such operation in 1.2.0)
and `checkAuth()` returns Unauthenticated. The SDK posts the login
form, captures the session cookie, and retries the original request.

The form-POST shape (no captcha) is captured in
`RuTorLoginParser` and exercised by
`fixtures/rutor/login/login-form-2026-04-30.html`.

## Mirrors

Bundled defaults (also exposed in `RuTorDescriptor.baseUrls`):

| Mirror                  | Priority | Protocol | Region          | Notes                               |
|-------------------------|----------|----------|-----------------|-------------------------------------|
| `https://rutor.info`    | 0        | HTTPS    | global          | Primary                             |
| `https://rutor.is`      | 1        | HTTPS    | global          | First fallback                      |
| `https://www.rutor.info`| 2        | HTTPS    | global          | www-prefixed alias                  |
| `https://www.rutor.is`  | 3        | HTTPS    | global          | www-prefixed alias                  |
| `http://6tor.org`       | 4        | HTTP     | ipv6-only       | Last resort, IPv6-preferred network |

Health probe: each mirror is HEAD'd by `MirrorHealthCheckWorker` every
15 minutes; the probe checks for the substring `"RuTor"` in the
response body (configured as `RuTorDescriptor.expectedHealthMarker`).

User-added custom mirrors are stored in `tracker_mirror_user`
(Room) and are merged with the bundled set on every health probe.

## Fixture refresh protocol

Fixtures live at
`src/test/resources/fixtures/rutor/<scope>/<name>-<YYYY-MM-DD>.html`.

Scopes: `search`, `browse`, `topic`, `comments`, `login`, `files`.

`scripts/check-fixture-freshness.sh` (invoked by `scripts/ci.sh`):

- Warns at >30 days since the fixture's embedded date.
- Blocks `scripts/tag.sh` at >60 days.

To refresh a fixture:

1. With a real device or curl, capture the live response of the URL
   the fixture represents. Strip cookies / session-state headers.
2. Save under
   `src/test/resources/fixtures/rutor/<scope>/<name>-<YYYY-MM-DD>.html`.
3. Run `./gradlew :core:tracker:rutor:test --tests "RuTor*ParserTest"`
   to confirm the existing parser still works against the refreshed
   shape.
4. If the parser fails, the live tracker shape has drifted —
   update the parser, add a Bluff-Audit stamp on the commit, and
   record the rehearsal in `.lava-ci-evidence/sp3a-bluff-audit/`.
5. Delete (or rename) the obsolete fixture.

See the broader fixture protocol in
`docs/refactoring/decoupling/refresh-fixtures.md` (if/when that
document is created — the protocol is stable, the dedicated doc is
nice-to-have).

## Real-tracker integration test

```
./gradlew :core:tracker:rutor:integrationTest -PrealTrackers=true
```

Source: `src/integrationTest/kotlin/lava/tracker/rutor/RealRuTorIntegrationTest.kt`.
This test hits the live `https://rutor.info` server. It is **gated by
`-PrealTrackers=true`** and is NOT part of the default `test` task —
running it from a CI runner without operator authorisation is forbidden
by the Local-Only CI/CD rule.

Operator runbook for tagging:

1. Run the integration test against the real tracker: `./gradlew
   :core:tracker:rutor:integrationTest -PrealTrackers=true`.
2. Save the `build/reports/tests/integrationTest/` HTML report to
   `.lava-ci-evidence/Lava-Android-<v>/mirror-smoke/rutor-<date>.html`.
3. Reference the file from `real-device-verification.md` "Mirror smoke"
   checkbox.

## Test discipline

Per Sixth + Seventh Laws:

- Every parser test exercises the production parser against a saved
  live response — no synthetic HTML, no string templating.
- The OkHttp boundary is replaced with `MockWebServer` only at the
  outermost layer; classes inside `lava.tracker.rutor.*` are never
  mocked.
- Bluff-Audit stamp required on every test commit (Seventh Law clause
  1, pre-push hook enforced).
- `RealRuTorIntegrationTest` is the load-bearing real-stack gate
  (Seventh Law clause 2) — without it the parser tests prove only
  that the parser handles a 2026-04-30 snapshot, not the live
  tracker.

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`; the SDK
> developer guide §5–§7
> ([`docs/sdk-developer-guide.md`](../../../docs/sdk-developer-guide.md))
> for the mechanical compliance gates that bind this module.
