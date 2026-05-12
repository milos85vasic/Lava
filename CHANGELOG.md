# Changelog
## Lava-Android-1.2.16-1036 / Lava-API-Go-2.3.5-2305 — 2026-05-12 (debug icon + RuTracker-Main full removal + §6.L 19th)

**Previous published:** Lava-Android-1.2.15-1035 / Lava-API-Go-2.3.4-2304

### Fixed
- **Debug launcher icon background.** The debug variant's `ic_launcher_background`
  is now solid `#00FF00` (green) instead of the previous gray. Added a
  debug-specific adaptive-icon at `app/src/debug/res/mipmap-anydpi-v26/ic_launcher.xml`
  pointing at the debug drawable so both `android:icon` and
  `android:roundIcon` references in the manifest pick up the green
  background in the `.dev` variant only.
- **Debug app name.** `app/src/debug/res/values/strings.xml`'s
  `app_name` changed from "Lava Dev" → "Lava DEV" per operator.
- **RuTracker (Main) persisting through reinstall.** v1.2.15 hid the
  seed entry but existing installs and Android Auto Backup restores
  carried the row back. Three layers of defense:
  1. `EndpointsRepositoryImpl.observeAll()` now `purgeRutrackerLegacy()`s
     the DAO on every observe() start AND filters `Endpoint.Rutracker`
     out of the emitted list.
  2. `EndpointsRepositoryImpl.add()` silently rejects
     `Endpoint.Rutracker` arguments.
  3. `PreferencesStorageImpl.getSettings()` migrates a persisted
     `Endpoint.Rutracker` (e.g., from a backup restore) to
     `Endpoint.GoApi(host = "lava-api.local")` and clears the prefs
     key.
- **Auto Backup / cloud-restore exclusion.** Added
  `app/src/main/res/xml/backup_rules.xml` +
  `app/src/main/res/xml/data_extraction_rules.xml` excluding
  `settings.xml` SharedPreferences from full-backup, cloud-backup,
  and device-transfer paths. Manifest now declares both via
  `android:fullBackupContent` and `android:dataExtractionRules`.
  Once a user removes a server, a reinstall (and any future backup
  restore) will NOT re-introduce stale endpoints.

### New tests
- `EndpointsRepositoryImplFilterTest` (JVM): asserts the filter +
  purge + add-rejection contracts.
- `Challenge26RutrackerMainAbsentFromServerListTest` (Compose UI):
  asserts the Server section never renders "Main" or
  "rutracker.org" entries.

### Constitutional
- §6.L mandate invoked for the 19th time. Count propagated across
  CLAUDE.md, AGENTS.md, lava-api-go's CLAUDE/CONSTITUTION/AGENTS, and
  all 48 docs across the 16 vasic-digital submodules.

### Changed
- Go API version → 2.3.5-2305
- Android version → 1.2.16-1036

---

## Lava-Android-1.2.15-1035 / Lava-API-Go-2.3.4-2304 — 2026-05-12 (operator-reported UX issues)

**Previous published:** Lava-Android-1.2.14-1034 / Lava-API-Go-2.3.3-2303

### Fixed
- **Onboarding wizard not shown on clean install.** MainActivity's
  `showOnboarding` defaulted to `false` and was loaded asynchronously,
  while `setKeepOnScreenCondition` only waited for theme — not for
  onboarding-status. Fresh-install users could see MainScreen before
  the onboarding flag was loaded → wizard never appeared. Fixed by
  making `showOnboarding` nullable and extending the splash-keep
  condition to wait for both theme AND onboarding-status to load.
- **Menu provider color-dot spacing.** Provider rows in the Menu
  screen had a `small` spacer between the color dot and the provider
  name — too tight visually. Bumped to `medium`.
- **Theme change required app restart.** `MainActivity` collected
  only the `first()` emission of `viewModel.theme` and never observed
  subsequent changes. Theme picker writes to preferences (reactive
  Flow) but the Activity didn't recompose. Fixed by switching to
  `viewModel.theme.collect { ... }` so each emission updates the
  composition immediately.
- **Server section: RuTracker (Main) removed from seeded list.**
  Per the operator's directive "communication is now strictly through
  the Lava API", the historical direct rutracker.org seed entry
  (`Endpoint.Rutracker`) is no longer surfaced to the user. The
  `defaultEndpoints` seed in `EndpointsRepositoryImpl` is now empty;
  discovery + manual-add populate the list. The `Endpoint.Rutracker`
  type remains as a fallback constant for now; full type deletion
  is documented as a follow-up SP because of its ~15 cascading
  call-site touches.
- **Server section: trash icon + confirmation dialog for offline
  endpoints.** Each `Mirror` / `GoApi` row in the Connections list
  that is `removable && !selected && status != Active` now shows a
  red trash (Delete) icon directly (no need to toggle edit mode).
  Tapping it shows a confirmation `Dialog` ("Remove server? — Remove %s
  from the server list? This cannot be undone."). Confirm → removal;
  Cancel → no-op. The edit-mode Remove icon was also updated to use
  the trash icon and now routes through the same confirmation dialog.

### Live-emulator verification
- 9-Challenge sweep on CZ_API34_Phone API 34 (post-fix re-run): PASS.
- New Compose UI Challenge: `Challenge25OnboardingFreshInstallTest`
  verifies the splash + onboarding-wizard rendering on clean prefs.

### Changed
- Go API version → 2.3.4-2304
- Android version → 1.2.15-1035

---

## Lava-Android-1.2.14-1034 / Lava-API-Go-2.3.3-2303 — 2026-05-12 (§6.L 16th+17th invocation: C03 fix + Cloudflare anti-bot + anti-bluff audit)

**Previous published:** Lava-Android-1.2.13-1033 / Lava-API-Go-2.3.2-2302

### Fixed
- **C03 RuTor anonymous onboarding** — Onboarding flow stuck on Configure
  screen for users picking RuTor with the Anonymous Access toggle on. Root
  cause: `OnboardingViewModel.onTestAndContinue()` called `sdk.checkAuth()`
  on the anonymous branch and treated `AuthState.Unauthenticated` as
  failure — but Unauthenticated IS the user's chosen state for anonymous.
  Fixed by skipping `checkAuth` on the anonymous branch entirely.
  (Commit `4d27c07`)

- **Credential-leak-in-logs (§6.H)** — `OnboardingViewModel.perform()`
  logged actions via `logger.d { "Perform $action" }` which printed the
  operator's real RuTracker username + password in plain text via the
  sealed-class auto-`toString` of `UsernameChanged(value=…)` /
  `PasswordChanged(value=…)`. Discovered during the C03 investigation.
  Fixed by printing only `action::class.simpleName`. (Commit `4d27c07`)

- **C02 RuTracker login — Cloudflare anti-bot stall** — POST to
  `/forum/login.php` was silently stalled by Cloudflare's anti-bot
  (TLS+TCP succeeded, request body written, no response data ever
  returned). Mitigation: HttpCookies plugin + browser-class headers
  (Accept, Accept-Language, Accept-Encoding) + real Chrome 124 UA +
  pre-flight `GET /forum/index.php` so the POST carries Cloudflare
  clearance cookies. POST now returns 302→200. (Commit `f7d0a62`)

- **rutracker cookie selection bug** — `RuTrackerInnerApiImpl.login()`
  picked the wrong cookie as the rutracker session token when
  Cloudflare added `cf_clearance` to Set-Cookie headers. Tightened
  selection to match by NAME prefix (bb_data/bb_session/bb_login)
  instead of fragile "not bb_ssl" negation. (Commit `f7d0a62`)

- **HTTP timeouts** — Main + LAN OkHttp clients had no explicit
  timeouts (OkHttp default 10s — too tight for slow networks).
  Set explicit 30s connect/read/write. Rutracker Ktor client gets
  HttpTimeout plugin (60s request, 30s connect, 60s socket).
  (Commit `4d27c07`)

- **Challenge16 stale-assumption bluff** — Test asserted "Internet
  Archive must NOT appear in onboarding list" while Phase 2b had
  flipped `apiSupported=true` on archiveorg. The test passed only
  because its `waitUntil` accepted the Welcome screen (where no
  provider list renders). Rewritten to navigate to "Pick your
  providers" and assert that all 4 verified+apiSupported providers
  actually render. (Commit `4b0dd55`)

- **GetCurrentProfileUseCase brittle parser** — Single-selector Jsoup
  approach (`#logged-in-username`) failed after Cloudflare mitigation
  changed the served page. Added 4-selector fallback chain. (Commit
  `4b0dd55`)

- **FirebaseAnalyticsTracker verify-only test** — Two tests used
  `verify { mock.foo() }` as their sole assertion (§6.L clause 4
  Forbidden Test Pattern). Refactored to `mockk slot` captures with
  `assertEquals` on captured values. (Commit `4b0dd55`)

### Constitutional
- §6.L mandate invoked for the 16th + 17th times. Count propagated
  across CLAUDE.md, AGENTS.md, lava-api-go's CLAUDE/CONSTITUTION/AGENTS,
  and all 48 docs across the 16 vasic-digital submodules. (Commits
  `4b0dd55`, `d8b90ab`, this commit)

### Live-emulator Challenge Test verification (CZ_API34_Phone API 34)
- **PASS** (14 of 24): C00, C01, C03, C04, C05, C06, C07, C08, C09,
  C10, C11, C12, C13, C14, C15, C16 (rewritten), C20, C21, C22 (in
  isolation), C23, C24.
- **PARTIAL** (1): C02 — Cloudflare mitigation portion verified;
  blocked at `parseUserId` post-login (none of 4 selectors match
  today's rutracker HTML — needs scraper archaeology or operator
  credential verification).
- **HONEST SHALLOW SCOPE** (C04-C08): test classes named after deep
  features (DownloadTorrentFile, ViewTopicDetail, CrossTrackerFallback)
  but only assert "tab is visible" per their KDocs (gap forensic in
  `.lava-ci-evidence/sp3a-challenges/C4-2026-05-04-redesign.json`).
  Deep tests owed.

### Unit-test suite
- 421 unit tests across all modules, 0 failures, 0 errors.

### Verified bluff-pattern audit (across all `*Test.kt` files)
- 0 mock-the-SUT bluffs.
- 0 `@Ignore` without issue link.
- 1 verify-only test (FirebaseAnalyticsTrackerTest) — fixed.
- 1 stale-assumption test (Challenge16ApiSupportedFilterTest) — rewritten.

### Changed
- Go API version → 2.3.3
- Android version → 1.2.14

---

## Lava-Android-1.2.13-1033 / Lava-API-Go-2.3.2-2302 — 2026-05-08 (Yole+Boba 8-palette theme system)

Yole semantic color foundation with 8 distinct palettes from Boba project accents.



## Lava-Android-1.2.12-1032 / Lava-API-Go-2.3.2-2302 — 2026-05-08 (Release build)

**Previous published:** Lava-Android-1.2.11-1031 / Lava-API-Go-2.3.2-2302

First production release build with full signing + ProGuard optimization.
Includes all fixes from 1.2.9+1.2.10.

---

## Lava-Android-1.2.11-1031 / Lava-API-Go-2.3.2-2302 — 2026-05-08

**Previous published:** Lava-Android-1.2.10-1030 / Lava-API-Go-2.3.1-2301

(incremental release — see git log for details)

---

## Lava-Android-1.2.10-1030 / Lava-API-Go-2.3.1-2301 — 2026-05-08 (Docker auth fix)

**Previous published:** Lava-Android-1.2.9-1029 / Lava-API-Go-2.3.0-2300

### Fixed
- docker-compose.yml: pass LAVA_AUTH_FIELD_NAME, LAVA_AUTH_HMAC_SECRET, LAVA_AUTH_ACTIVE_CLIENTS, LAVA_AUTH_RETIRED_CLIENTS, LAVA_AUTH_TRUSTED_PROXIES to lava-api-go container (was crashing on startup)

### Changed
- Go API version → 2.3.1

---

## Lava-Android-1.2.9-1029 / Lava-API-Go-2.3.0-2300 — 2026-05-08 (Theme fix + anti-bluff onboarding)

**Previous published:** Lava-Android-1.2.8-1028 / Lava-API-Go-2.2.0-2200

### Fixed — Theme readability (critical)

- LavaTheme now wires MaterialTheme.colorScheme from AppColors, fixing dark-mode text being unreadable
  (MaterialTheme.colorScheme returned light-theme defaults even in dark mode)
- AppColors extended with secondary, tertiary, surfaceVariant, onSurfaceVariant, error roles
- All custom themes (Ocean/Forest/Sunset) updated with full Material3 color roles

### Fixed — Onboarding wizard

- WelcomeStep shows provider count ("6 providers available") per design spec
- ConfigureStep back press now goes to Providers per spec (was going to previous provider)
- SummaryStep hardcoded colors replaced with AppTheme accents (§6.R No-Hardcoding fix)
- All onboarding steps use AppTheme.colors/typography/shapes instead of MaterialTheme defaults
- Anonymous provider TestAndContinue no longer erroneously calls checkAuth for health validation

### Added — Anti-bluff tests

- 16 OnboardingViewModel unit tests (all passing): step transitions, provider toggling, back press, anon/auth TestAndContinue, credential saving, Finish signaling, filtering
- 3 Challenge Tests (C20-C22) for onboarding wizard — compile, need emulator to execute

### Changed — Constitution

- §6.J/§6.L/§6.Q added to core/, feature/, app/ CLAUDE.md + AGENTS.md (6 files)
- Lava constitution inheritance added to Panoptic submodule (CLAUDE/AGENTS/CONSTITUTION)
- FakeTrackerClient now exposes `authState` property for testability
- Duplicate include(":feature:onboarding") removed from settings.gradle.kts

### Changed — Go API

- version.Name → 2.3.0, Code → 2300

---

## Lava-Android-1.2.8-1028 / Lava-API-Go-2.2.0-2200 — 2026-05-07 (Phases 2-6)

**Previous published:** Lava-Android-1.2.7-1027 / Lava-API-Go-2.1.0-2100

### Added — Multi-provider streaming search (Phase 2)

- `GET /v1/search?q=...&providers=...` SSE endpoint fans out to all registered providers
- `SseClient` (OkHttp-based SSE parser), `ProviderChipBar` multi-select filter
- Provider label chips on search result cards
- `apiSupported=true` on all 6 providers (rutracker, rutor, nnmclub, kinozal, archiveorg, gutenberg)
- Provider result filtering chips on search results screen

### Added — Onboarding wizard (Phase 3)

- New `:feature:onboarding` module with 4-step wizard: Welcome → Pick Providers → Configure → Summary
- AnimatedContent sliding transitions
- Connection auto-test on credential submit, closes app on back press at Welcome

### Added — Sync expansion (Phase 4)

- Device identity UUID generated on first launch
- Sync Now buttons on Favorites and Bookmarks screens
- History and Credentials sync categories with WorkManager workers
- Menu sync settings expanded from 2 to 4 categories

### Changed — UI/UX polish (Phase 5)

- Menu multi-provider header showing all signed-in providers with sign-out
- Ocean/Forest/Sunset color themes alongside SYSTEM/LIGHT/DARK
- About dialog shows versionCode: "Version: 1.2.8 (1028)"
- Credentials screen modern redesign with ProviderColors, nav-bar FAB fix
- Nav-bar overlap audit: `navigationBarsPadding` added at Scaffold level

### Added — Crashlytics (Phase 6)

- Non-fatal `recordException` tracking in 8 ViewModels across all error paths

### Fixed

- Hardcoded `thinker.local:8443` → config-driven via `ObserveSettingsUseCase`
- Credentials FAB no longer overlaps 3-button navigation bar

---

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

## Lava-API-Go-2.1.0-2100 — 2026-05-06 (Phase 1)

**Channel:** container registry / remote distribution to thinker.local
**Previous published:** Lava-API-Go-2.0.16-2016 (2026-05-06)

### Added — API auth + transport (Phase 1 of `docs/todos/Lava_TODOs_001.md`)

- **UUID-based client allowlist** enforced via the `Lava-Auth` header
  (name itself config-driven via `LAVA_AUTH_FIELD_NAME` per §6.R). Active
  vs retired separation: retired UUIDs return `426 Upgrade Required` with
  min-version JSON instead of advancing the backoff counter.
- **Per-IP fixed-ladder backoff** (`2s,5s,10s,30s,1m,1h` configurable via
  `LAVA_AUTH_BACKOFF_STEPS`) shipped as the `pkg/ladder` primitive
  upstream-contributed to `Submodules/RateLimiter`.
- **HTTP/3 preferred** with HTTP/2 fallback + `Alt-Svc` advertisement.
- **Brotli response compression** when the client sends `Accept-Encoding: br`.
- **Prometheus protocol metric** — `lava_api_request_protocol_total{protocol,status}`.
- **Constitutional clause §6.R** — No-Hardcoding Mandate (added to root +
  16 submodules + AGENTS.md).

### Tests (§6.G real-stack + §6.A contract + §6.N rehearsals)

- 8 integration tests under `lava-api-go/tests/integration/` (active,
  retired, unknown, ladder, reset, brotli, alt-svc, metric).
- 1 contract test asserting `LAVA_AUTH_FIELD_NAME` does NOT appear as
  a literal in production source.
- All Bluff-Audit stamps recorded with crisp failure messages from
  deliberate-mutation rehearsals.

### Submodule pin
- `Submodules/RateLimiter` pinned at `3faf7a51` (introduces `pkg/ladder/`).

### Versions in this build
- lava-api-go: 2.1.0 (2100)
- Android: 1.2.7 (1027) — paired with this API release

---

## Lava-Android-1.2.7-1027 — 2026-05-06 (Phase 1)

**Channel:** Firebase App Distribution
**Previous published:** Lava-Android-1.2.6-1026 (2026-05-05)

### Added — client-side auth foundation

- **`AuthInterceptor`** — OkHttp interceptor decrypts the per-build
  encrypted UUID, injects it into the `Lava-Auth` header, zeroizes the
  plaintext bytes in `finally`. Auth UUID memory hygiene per
  `core/CLAUDE.md` (added in this release).
- **Build-time encryption (Phase 11)** — Gradle task
  `generateLavaAuthClass{Debug,Release}` reads `.env` + the variant
  keystore and emits `lava.auth.LavaAuthGenerated` containing the
  AES-GCM-encrypted UUID + nonce + pepper bytes. Generated dir is
  gitignored.
- **L2 client-side obfuscation** — AES-256-GCM keyed by
  `HKDF-SHA256(salt = SHA256(signing-cert)[:16], ikm = pepper)`. A
  re-signed APK has a different cert hash → different derived key →
  decrypt fails closed.
- **α-hotfix: TrackerDescriptor.apiSupported** filter — the user-facing
  provider list now hides Internet Archive (and other providers without
  lava-api-go routes) until Phase 2 ships per-provider routing. Closes
  the alice-bug class.
- **C15 + C16 Compose UI Challenge Tests** — boot-with-AuthInterceptor +
  apiSupported-filter rendering assertions.

### Tests
- HKDFTest (RFC 5869 §A.1 vector), AesGcmTest (round-trip + tamper
  detection), SigningCertProviderTest (digest math), AuthInterceptorTest
  (header injection + empty-blob skip + re-signed-APK fail-closed).

### Versions in this build
- Android: 1.2.7 (1027)
- lava-api-go: 2.1.0 (2100) — required for full auth flow

---

## Lava-API-Go-2.0.16-2016 — 2026-05-06

**Channel:** container registry / remote distribution to thinker.local
**Previous published:** Lava-API-Go-2.0.15-2015 (2026-05-06)

### Fix (post-Ktor cleanup, §6.J bluff in `lava-containers` CLI)

The `lava-containers` workstation CLI had three commands (`build`, `status`, `logs`) that silently targeted dead surfaces post-Ktor-:proxy-removal:

- **`-cmd=build`** — `Manager.BuildImage()` shelled `<runtime> build -t digital.vasic.lava.api:latest ./proxy`. The `./proxy` directory was deleted in 2.0.12 (commit `a00b28f`), so this command would fail at runtime.
- **`-cmd=status`** — `Manager.isHealthy()` probed `http://localhost:8080/`. The api-go service listens on `https://localhost:8443/health`. Status would always report `Healthy: false` even when api-go was running locally.
- **`-cmd=logs`** — `Manager.Logs()` called `<runtime> logs lava-proxy`. The `lava-proxy` service was removed from `docker-compose.yml` in 2.0.12; the active service is `lava-api-go`.
- **`internal/runtime.Runtime.IsHealthy()` + `ContainerIP()`** — both filtered on `name=lava-proxy`. Now `LavaContainerName = "lava-api-go"`.

That's a textbook §6.J bluff: the tool reports outcomes from probing nothing.

### Changes

- `internal/orchestrator/manager.go` — full rewrite of broken paths:
  - `ServiceName = "lava-api-go"` (was `"lava-proxy"`)
  - `DefaultPort = "8443"` (was `"8080"`)
  - `BuildImage()` now invokes `<runtime> compose --profile api-go build` — uses the `build:` directive in `docker-compose.yml`'s `lava-api-go` service entry (context: `.`, dockerfile: `lava-api-go/docker/Dockerfile`, target: `runtime`).
  - `isHealthy()` probes `https://localhost:8443/health` with `InsecureSkipVerify: true` (LAN cert is self-signed; this is a local-dev liveness probe, not a security gate).
  - `Status()` prints "Lava API Container Status" instead of "Lava Proxy"; URL line shows the HTTPS health endpoint.
  - Dead methods deleted: `Start()`, `Stop()`, `printStatus()` — never called from `main.go` post-2.0.13 (the Orchestrator type owns compose-up/down).
  - Package doc comment rewritten — was still describing the removed Ktor proxy.
- `internal/runtime/runtime.go` — `IsHealthy()` and `ContainerIP()` now reference `LavaContainerName = "lava-api-go"` constant (was hardcoded `"lava-proxy"`).
- `internal/orchestrator/orchestrator.go` — doc comment updated; `Profile` field doc narrowed to `"api-go"` (was `"api-go" | "legacy" | "both"`).
- `internal/orchestrator/orchestrator_test.go` — three tests retargeted from legacy/both profile names to realistic `api-go + observability + dev-docs` compositions (the Orchestrator type passes profile names through opaquely, but tests should reflect the validated set).

### New §6.A real-binary contract test

- `internal/orchestrator/manager_test.go` — `TestManagerConstantsMatchCompose` asserts `ServiceName + DefaultPort` match `docker-compose.yml`'s `container_name:` and `LAVA_API_LISTEN:` entries. `TestManagerConstantsAreNonLegacy` is a regression guard against the legacy `"lava-proxy"`/`"8080"` values.
- Falsifiability rehearsal recorded in commit body (Bluff-Audit stamp). Both mutations produce crisp failure messages with explicit forensic context.

### Verification

- `go vet ./...` in `tools/lava-containers`: green.
- `go test ./...` in `tools/lava-containers`: 9 tests across 2 packages PASS (was 7).
- All 31 hermetic bash test suites: green.
- thinker.local API (the running 2.0.13 binary, unchanged): `{"status":"alive"}`, `{"status":"ready"}`.

### Versions in this build

- lava-api-go: 2.0.16 (2016) — workstation-CLI cleanup; the api-go binary on thinker.local is unchanged from 2.0.13.
- Android: 1.2.6 (1026) — unchanged.

---

## Lava-API-Go-2.0.15-2015 — 2026-05-06

**Channel:** container registry / remote distribution to thinker.local
**Previous published:** Lava-API-Go-2.0.14-2014 (2026-05-06)

### Refactor (post-Ktor naming cleanup)

- **`tools/lava-containers/internal/proxy` → `internal/orchestrator`** — renamed the Go package (the "proxy" name was orchestrator-meaning, confusing post-Ktor-removal). `proxy.go` → `manager.go` (the file holds the `Manager` type for compose lifecycle). Imports + call sites updated in `cmd/lava-containers/main.go`. `git mv` preserves file history.
- **`Manager.BuildJar()` removed** — it ran `./gradlew :proxy:buildFatJar` which would fail at runtime since the `:proxy` module is gone. `os/exec` import removed (was only used by `BuildJar`). `Manager.BuildImage()` retained for the api-go image build.
- All `go test ./...` in `tools/lava-containers` PASS post-rename (3 packages).

### Versions bumped

| Component | Old | New |
|---|---|---|
| lava-api-go | 2.0.14 (2014) | **2.0.15 (2015)** |

---

## Lava-API-Go-2.0.14-2014 — 2026-05-06

**Channel:** container registry / remote distribution to thinker.local
**Previous published:** Lava-API-Go-2.0.13-2013 (2026-05-06)

### Removed (post-Ktor cleanup, second pass)

- **`tools/lava-containers/cmd/lava-containers/main.go`** — dropped the `legacy` and `both` profile branches. `validateProfile` now accepts only `api-go`. `runStart` no longer carries the `BuildJar` + `BuildImage` fall-through for the deleted Ktor proxy. `mgr.BuildJar()` removed from the `build` command. `autoDetectProjectDir` no longer probes for the deleted `proxy/` directory.
- **`main_test.go`** — `TestValidateProfile_Accepts` reduced to `api-go` only; `legacy` + `both` moved to the rejection table.
- KDoc + comment refresh: header references to "legacy Ktor proxy and/or the new Go API service" reduced to "the lava-api-go service".

### Versions bumped

| Component | Old | New |
|---|---|---|
| lava-api-go | 2.0.13 (2013) | **2.0.14 (2014)** |

(api-go version bumped per §6.P even though the changes are workstation-side; the bump keeps the distribute pipeline's gate cleanly happy and the per-version snapshot maintains the chain.)

---

## Lava-API-Go-2.0.13-2013 — 2026-05-06

**Channel:** container registry / remote distribution to thinker.local
**Previous published:** Lava-API-Go-2.0.12-2012 (2026-05-06)

### Fixed (mDNS not reaching the LAN)

- **deployment/thinker/thinker-up.sh: lava-api-go now uses `--network host`** instead of a podman bridge network. The previous bridge-network setup confined JmDNS / mDNS broadcasts to the bridge subnet, so Android testers running Lava could NOT auto-discover thinker.local's API via `_lava-api._tcp`. Matches the local docker-compose.yml pattern where lava-api-go uses `network_mode: host` for the same reason.
- Postgres still uses a bridge network with `127.0.0.1:${POSTGRES_PORT}` published on the host; api-go connects via `127.0.0.1:5432` (host-namespace local).
- Verified: `podman logs lava-api-go-thinker` now shows `mDNS announced port=8443 type=_lava-api._tcp`. Cross-host curl succeeds: `curl https://thinker.local:8443/health` returns `{"status":"alive"}` from the workstation.

### Versions bumped

| Component | Old | New |
|---|---|---|
| lava-api-go | 2.0.12 (2012) | **2.0.13 (2013)** |

---

## Lava-API-Go-2.0.12-2012 — 2026-05-06

**Channel:** container registry / remote distribution to thinker.local
**Previous published:** Lava-API-Go-2.0.11-2011 (2026-05-06)

### Removed

- **Legacy Ktor proxy** removed from the codebase. Going forward, lava-api-go (Go) is the only API. Files removed: `proxy/` (entire module), `:proxy` Gradle include, `proxy/build.gradle.kts` parsing in `build_and_release.sh` + `scripts/tag.sh`, `lava-proxy` service in `docker-compose.yml`, `--legacy` / `--both` profiles from `start.sh` + `stop.sh`. The Android client was already using the lava-api-go endpoint by default.

### Versions bumped

| Component | Old | New |
|---|---|---|
| lava-api-go | 2.0.11 (2011) | **2.0.12 (2012)** |

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
