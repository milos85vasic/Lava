# Lava — Bug Fix Audit Trail

> **Revision §11.4.44 (2026-06-08):** appended the 2026-06-08 session's six
> real defect fixes — the four §6.E capability-honesty bluffs (kinozal /
> nnmclub / rutor magnet wire-through + gutenberg `TORRENT_DOWNLOAD` drop),
> the HelixQA broken-pin go.mod conflict that broke `lava-api-go`'s build,
> and the nav-compose `2.9.0 → 2.9.1` test-teardown lifecycle race. Each
> entry below cites its real commit SHA + real files (verified against
> `git show --stat`). Per §11.4.6 no-guessing, details that could not be
> confirmed from git are marked `UNCONFIRMED`.

Per constitutional clause **§6.T.4 (Bugfix Documentation)** — every bug
fix in this project MUST be documented here with root cause analysis,
affected files, fix description, link to the verification test/
challenge, and the commit SHA that landed the fix.

§6.O (Crashlytics-Resolved Issue Coverage Mandate) extends this for
Crashlytics-recorded issues; their closure logs live at
`.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md`. §6.T.4
covers the rest — operator-reported, self-discovered, or
reviewer-flagged bugs that don't enter the Crashlytics pipeline.

Format per entry:

```markdown
## YYYY-MM-DD — <short slug>

**Root cause:** ...
**Affected files:** ...
**Fix:** ...
**Verification test/challenge:** path or commit ref
**Fix commit:** SHA
**Forensic anchor:** (optional) what surfaced the bug
```

---

## 2026-06-13 — onboarding catalogue fetch 401 against a freshly-discovered API (`GET /providers` auth-gated)

**Root cause:** `lava-api-go/internal/router/router.go` registered
`engine.GET("/providers", …)` AFTER `engine.Use(auth.GinMiddleware())` (and the
`auth.NewMiddleware` chain). The provider catalogue is the endpoint the Android
client fetches during ONBOARDING against an API it has just discovered on the LAN —
at which point the client holds no pre-shared `Lava-Auth` key with that API. The
auth gate therefore rejected the fetch with HTTP 401 → `OnboardingViewModel`
`fetchAndPopulateProviders` caught the failure and surfaced
`PROVIDER_CATALOG_FALLBACK_NOTICE` ("Couldn't reach the selected API — showing
bundled providers"), so a perfectly reachable API showed the bundled fallback. This
is distinct from Defect A (2026-06-12, a TLS+missing-key client problem on the
*client* side); Defect A's fix made the client send the key, but the api-app's
allowlist need not include a just-discovered client at all — the catalogue is
public metadata and must not be gated in the first place.

**Affected files:** `lava-api-go/internal/router/router.go` (registration moved
before the auth middleware, with rationale comment + a NOTE where it used to live);
`lava-api-go/internal/router/router_config_wiring_test.go` (+`TestBuild_ProvidersOpenWithFullAuthChain`);
`lava-api-go/tests/contract/providers_public_auth_boundary_test.go` (new, real-binary 3-way boundary);
`feature/onboarding/src/test/kotlin/lava/onboarding/OnboardingViewModelDynamicProvidersTest.kt`
(+replace-not-merge success test + no-banner-on-success assertion);
`core/apiengine/src/main/resources/api-source.hash` (recomputed).

**Fix:** register `GET /providers` BEFORE the auth middleware, alongside
`/health`+`/ready`. It is public, non-sensitive provider metadata (ids,
capabilities, authType, baseUrls — no credentials, no user data). Per-provider
operations under `/v1/:provider/…` remain fully auth-gated. The standalone binary
and the embedded api-app share the exact `router.Build`, so the one edit fixes both
surfaces (DRY; a divergent embed router would be a §6.J bluff vector).

**Verification test/challenge:** server `TestBuild_ProvidersOpenWithFullAuthChain`
(unit) + `TestProviders_PublicCatalogue_PerProviderStillGated` (real-binary e2e,
proves unauth `/providers`→200 while unauth `/v1/…`→401 and authed `/v1/…` crosses
the gate); client `OnboardingViewModelDynamicProvidersTest` (success → exactly the
API's set, bundled-absent, no banner; failure → banner + non-blank). Every layer's
falsifiability mutation re-performed by the main stream (not trusted from the
subagent worktree): gate `/providers` → e2e sub-test A 401; drop `populateFrom` →
replace test FAILED; success-emits-banner → assertNull FAILED. All reverted GREEN.

**Fix commit:** `9ae9ab90` (server fix) + `132d1b07` (real-binary e2e) +
`99e5893a`/`6ce100bc` (client full-flow tests).

**Forensic anchor:** operator real-device report (2026-06-13) — "choose discovered
API 192.168.0.107:8443 → next screen shows 'Couldn't reach the selected API —
showing bundled providers'". Crashlytics non-fatal `47b000d5`
(`provider_catalog_fetch_failed`, HTTP 401).

## 2026-06-12 — provider-catalogue fetch fails self-signed-LAN TLS handshake (Defect A — "only 4 providers")

**Root cause:** `ProviderCatalogRepository` injected the *unqualified* strict-TLS
`OkHttpClient` (system trust store only) and sent no auth key. The on-device
api-app serves `GET /providers` over a self-signed LAN cert behind the `Lava-Auth`
gate, so every real-device fetch died at the TLS handshake
(`CertPathValidatorException: Trust anchor … not found`) → `Result.failure` →
onboarding fell back to the 4 bundled `verified && apiSupported` descriptors.
**Real-device proof:** Crashlytics NON_FATAL `042b9b61`
(`provider_catalog_fetch_failed`), 1.3.3-1060, Galaxy S23 Ultra / Android 16.
**Affected files:** `core/data/.../ProviderCatalogRepository.kt` (inject
`@Named("lan")` client + `@Named("authFieldName")`; `fetchProviders(apiBaseUrl,
authKey)` + `withAuthKey`), `core/domain/.../FetchProvidersUseCase.kt` (thread
`authKey`), `feature/onboarding/.../OnboardingViewModel.kt` (pass
`Endpoint.GoApi.key`), `core/data/build.gradle.kts` + `gradle/libs.versions.toml`
(okhttp-tls for self-signed-HTTPS tests).
**Fix:** route the catalogue fetch through the permissive LAN client (accepts the
self-signed cert) + attach the endpoint's per-instance key — mirrors the existing
`NetworkApiRepositoryImpl.getApi()` `Endpoint.GoApi` path.
**Verification test/challenge:** `ProviderCatalogRepositoryTest` rewritten to cross
a real self-signed-HTTPS + auth-gated MockWebServer (6/6;
`strictClientFailsTheHandshake` = codified pre-fix mutation;
`missingAuthKeyIsRejectedByTheGate` proves the header is load-bearing);
`OnboardingViewModelDynamicProvidersTest` green.
**Fix commit:** `0deb54e7`
**Forensic anchor:** operator "only 4 providers in onboarding wizard"; §6.O closure
log `.lava-ci-evidence/crashlytics-resolved/2026-06-12-provider-catalog-fetch-tls.md`.
**Scope note:** Defect B (embedded Jackett in the api-app) still OPEN — the client
now shows whatever the api-app serves, but the api-app serves only native providers
until embedded Jackett lands.

---

## 2026-06-02 — api-app-ondevice-challenges-three-defects + harness-isolation

The Containers `emulator-matrix` CLI gained a generic `--gradle-module`
flag (Containers commit `9a61a153`); the Lava glue
`scripts/run-api-app-challenge-matrix.sh` now forwards it so the
`:api-app` Compose UI Challenges (C01–C04) finally **execute** against the
`:api-app` module instead of a 0-test false-green against `:app`. Running
them for real surfaced **three latent product defects** (all previously
invisible because the tests had never actually run) plus a test-harness
isolation flaw. All proven fixed: clean sequential 4/4 green on a
cold-booted Pixel_8/API35 via the Containers runner (host-direct+HVF,
`gating=true`).

**Root cause 1 (C02 — mDNS service-name shadow → API35 crash):** in
`NsdMdnsAdvertiser.register()`, `NsdServiceInfo().apply { this.serviceName
= serviceName }` — inside `apply{}` the implicit receiver is the
`NsdServiceInfo`, whose own `serviceName` property **shadowed** the
advertiser's constructor val, so the RHS `serviceName` resolved to the
receiver's (null) value. The service registered with an EMPTY name →
Android 15 `NsdManager.validateService` threw `IllegalArgumentException`
("The service name or the service type is missing") → uncaught → engine
start crashed → instrumentation process crash (C01 ok, C02 crashed,
C03/C04 never ran).

**Root cause 2 (C03 — cross-test native-engine pollution):** the Go embed's
`internal/mobile.current` is process-global, but Hilt rebuilds the
`@Singleton ApiEngineController` per test. C02 starts the engine and never
stops it; C03 then tries to Start → `mobile.Start` sees `current != nil` →
"already running" → C03's start→Running wait times out.

**Root cause 3 (C04 — notification restart-after-stop):** two bugs. (a)
`ApiEngineController.restart()` called `stop()` first unconditionally;
after a Stop the engine is already stopped → `mobile.Stop` returns "no
server running" → `Error` state → `restart()` bailed before `start()`. (b)
`ApiEngineService`'s state collector called `stopSelf()` on ANY `Stopped`,
including the INITIAL `Stopped` a freshly-recreated Service sees while
handling an ACTION_RESTART — destroying the Service and cancelling the
restart coroutine before it could drive the engine up.

**Root cause 4 (harness isolation flakiness):** the foreground
`ApiEngineService` is a process-singleton that outlives the per-test Hilt
`@Singleton` controller, so a stale Service from a prior test intercepted
the next test's start/notification actions (driving the wrong, stale
controller) — producing run-to-run inconsistent failures. Plus the
on-device `OnDeviceApiClient` 15s readTimeout flaked the HTTP/2 stream
(`SocketTimeoutException`) on the emulator's first cold-serve.

**Affected files:**
- `submodules/containers/.../cmd/emulator-matrix` + `pkg/emulator/*` (the `--gradle-module` flag, Containers commit `9a61a153`)
- `scripts/run-api-app-challenge-matrix.sh` (forward `--gradle-module`)
- `api-app/src/main/kotlin/lava/api/app/service/NsdMdnsAdvertiser.kt` (RC1)
- `api-app/src/main/kotlin/lava/api/app/control/ApiEngineController.kt` (RC3a)
- `api-app/src/main/kotlin/lava/api/app/service/ApiEngineService.kt` (RC3b)
- `api-app/src/androidTest/.../Challenge0{1,2,3,4}*Test.kt` (RC2 + RC4 `@Before` reset + Service teardown)
- `api-app/src/androidTest/.../OnDeviceApiClient.kt` (RC4 timeout)
- `lava-api-go/internal/mobile/restart_repro_test.go` (NEW Go same-port-restart coverage — proved the Go embed restart is innocent)

**Fix:**
1. RC1 — capture `serviceName`/`serviceType` into LOCALS before the `apply{}` (kills the shadow) + wrap `registerService` in `runCatching` so a future platform rejection degrades gracefully instead of crashing the engine.
2. RC2 + RC4 — each Challenge `@Before` resets the process-global native engine (`NativeApiEngine().stop()`) AND kills any stale foreground Service (`stopService`).
3. RC3a — `restart()` skips `stop()` when already `Stopped` (just `start()`).
4. RC3b — the Service collector self-stops only on a genuine running→stopped transition (`sawRunning` flag), never on the initial `Stopped`.
5. RC4 — `OnDeviceApiClient` connect/read timeouts 10/15s → 20/30s.

**Verification test/challenge:** `:api-app` Challenge C01–C04 EXECUTED green on cold-booted Pixel_8/API35 via the Containers runner (gating=true, host-direct+HVF). Evidence: `.lava-ci-evidence/phase-e-api-app/2026-06-02T11-32-48Z-gate/` (+ a confirmation run). Each fix falsifiability-rehearsed; the gate itself is the load-bearing on-device acceptance gate (§6.AE / §6.Z).
**Fix commit:** `0deb54e7` (parent) + Containers `9a61a153`.
**Forensic anchor:** the `--gradle-module` flag made the never-before-run `:api-app` Challenges execute for real — the textbook §6.J/§6.L payoff: three real product defects had been invisible behind a 0-test false-green.

---

## 2026-05-06 — phase1-distribute-three-bugs

The first real-distribute of Lava-Android-1.2.7-1027 + lava-api-go-2.1.0
exposed three bugs that landed in commit `e947081`:

**Root cause 1:** `/health` and `/ready` endpoints were registered AFTER
the auth middleware in `lava-api-go/cmd/lava-api-go/main.go` `buildRouter`,
so the orchestrator's liveness probe got 401 → restart loop.

**Root cause 2:** `core/network/impl/.../LavaAuthBlobProvider.kt` was
declared `internal`, but the Phase 11 build-time-generated
`lava.auth.LavaAuthGenerated` class lives in `:app`'s source set —
different module → cannot access internal interface → compile failure.

**Root cause 3:** `scripts/distribute-api-remote.sh` +
`deployment/thinker/thinker-up.sh` did NOT ship the operator's local
`.env` `LAVA_AUTH_*` + `LAVA_API_HTTP3_*`/`BROTLI_*`/`PROTOCOL_*`
values to the thinker.local container. The new 2.1.0 binary required
`LAVA_AUTH_FIELD_NAME` + `LAVA_AUTH_HMAC_SECRET` at boot → crash-loop.

**Affected files:**
- `lava-api-go/cmd/lava-api-go/main.go`
- `core/network/impl/src/main/kotlin/lava/network/impl/LavaAuthBlobProvider.kt`
- `scripts/distribute-api-remote.sh`
- `deployment/thinker/thinker-up.sh`

**Fix:**
1. Register `/health` + `/ready` BEFORE the auth chain.
2. Drop `internal` from `LavaAuthBlobProvider`.
3. Distribute script merges operator's `.env` auth/transport block into
   a temp env file before scp; thinker-up iterates the variables and
   passes each as `-e VAR=$VAR` to `podman run`.

**Verification test/challenge:** post-fix smoke test in
`scripts/distribute-api-remote.sh`'s 60-second `/health` wait
(passes); `curl -fsSk https://thinker.local:8443/{health,ready}`
return `{"status":"alive"}` / `{"status":"ready"}` (passes); auth
gate returns 401 with `{"error":"unauthorized"}` for missing header
(passes — fail-closed posture confirmed).

**Fix commit:** `e947081`

**Forensic anchor:** the very first
`bash scripts/distribute-api-remote.sh` after the api-go-2.1.0 build
showed `lava-api-go: config: config: LAVA_AUTH_FIELD_NAME is required`
in a tight loop, with the orchestrator marking the container
`(unhealthy)` and triggering the restart loop. Operator ran the
distribute, got the lock-up output, immediately surfaced the issue.

---

## 2026-05-12 — c03-anonymous-toggle-checkauth-bluff

**Root cause:** `OnboardingViewModel.onTestAndContinue()` (the
"Test & Continue" button handler on the Configure step) called
`sdk.checkAuth(currentId)` for BOTH `AuthType.NONE` providers AND
`config.useAnonymous=true` paths, then asserted that the result equals
`AuthState.Authenticated`. For users opting INTO anonymous mode on a
FORM_LOGIN tracker (RuTor with the toggle on), `checkAuth` correctly
returns `Unauthenticated` — that IS the user's chosen state. The code
treated it as a failure (`error = "Connection failed"`) and never
advanced to the Summary step. The wait-for-"All set!" timeout fired
and the user was stuck.

The C03 Challenge Test detected this only as a 60s timeout — no
indication of WHY. The bug was invisible to the Sixth Law clauses 1-5
because:
- The test's primary assertion was on "All set!" appearing (✓ correct
  per clause 3).
- The production stack was traversed end-to-end (✓ correct per clauses
  1 + 4).
- But there was no logged stack trace; the catch block called
  `analytics.recordNonFatal` (Crashlytics-only) and silently set the
  error state. The diagnostic gap was the source of the bluff: green
  test infrastructure + green code review + green Bluff-Audit on prior
  commits all missed it because nobody had run C03 end-to-end on a
  device with logcat tailing to see the error.

**Affected files:**
- `feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingViewModel.kt`

**Fix:**
1. Skip `sdk.checkAuth(currentId)` entirely on the anonymous branch.
   Anonymous = user opted out of auth = no auth state to check =
   not a failure when there's no session.
2. Added diagnostic `logger.d`/`logger.e` breadcrumbs on the entire
   onTestAndContinue flow (auth path taken, result, exception). This
   closes the §6.T.4-spirit gap that made the C03 bug invisible —
   future failures will print stack traces to logcat.
3. Added `HttpTimeout`, `UserAgent`, and `Logging` plugins to the
   rutracker Ktor HttpClient + 30s timeouts on the main OkHttpClient.
   The HTTP improvements did NOT close the C02 issue (Cloudflare-side
   stall — see §4.5.3b in CONTINUATION.md), but they raise the
   time-budget for slow networks and move the failure mode from
   `SocketTimeoutException` to a more diagnosable signal.
4. Bumped Challenge02 test's wait for "All set!" from 30s to 90s to
   align with realistic real-network round-trip times after the
   timeout fixes.

**Verification test/challenge:**
- C03 (`Challenge03AnonymousSearchOnRuTorTest`) — PASS on
  CZ_API34_Phone API 34 (live emulator at localhost:5555),
  2026-05-12 09:25:14 to 09:25:23, total ~9.7s. Evidence at
  `.lava-ci-evidence/Lava-Android-1.2.13-1033/post-mortem/logcat-c03-fix.txt`
  shows `anon path: switchTracker(rutor) → test ok: advance to next/Summary → Perform Finish`.
- Falsifiability rehearsal: re-introducing the broken
  `if (result != null && result != AuthState.Authenticated)` check
  in the anonymous branch causes C03 to time out at 60s with the
  Configure screen still visible (the pre-fix failure mode). Verified
  through the diagnostic logcat: `checkAuth(rutor) result=Unauthenticated`
  → error path triggered → no advance.

**Fix commit:** _(this commit)_

**Forensic anchor:** Phase 1 systematic-debugging session 2026-05-12.
Operator picked option C ("investigate C02/C03 properly") + invoked
the anti-bluff mandate. Matrix runs on CZ_API34_Phone surfaced the
60s timeout; live-emulator instrumentation captured the
`anon path: checkAuth(rutor) result=Unauthenticated` line that
identified the bug.

---

## 2026-05-12 — onboarding-action-logger-leaks-credentials

**Root cause:** Discovered during the C03 investigation above —
`OnboardingViewModel.perform()` logged the action via
`logger.d { "Perform $action" }`. For sealed-class subtypes
`OnboardingAction.UsernameChanged(value: String)` and
`OnboardingAction.PasswordChanged(value: String)`, Kotlin's
auto-generated `toString` includes the value, so logcat (and any
analytics breadcrumb that picks it up) prints the operator's real
RuTracker username + password in plain text. Severity: §6.H credential
inviolability concern — although `.env` is gitignored and the values
never reach disk via a log file in the normal user flow, on a device
with `adb logcat` running (or with `logcat` being collected by a 3rd
party app, or shared in a bug report) this is a leak surface.

**Affected files:**
- `feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingViewModel.kt`

**Fix:** changed the log expression from
`logger.d { "Perform $action" }` to
`logger.d { "Perform ${action::class.simpleName}" }` — prints only
the action type name, never any of its values.

**Verification test/challenge:** logcat tail during a re-run of C02
shows `Perform UsernameChanged` (no value) and
`Perform PasswordChanged` (no value).

**Fix commit:** _(same commit as the c03-anonymous-toggle-checkauth-bluff
entry above)_

**Forensic anchor:** logcat captures during the C02/C03 diag run on
2026-05-12 surfaced the bug. Pre-fix logcats are gitignored under
`.lava-ci-evidence/**/post-mortem/` to prevent accidental commit of
the captured credentials; only matrix attestations + gradle.logs +
JUnit XML test-reports are committed.

---

## 2026-05-12 — rutracker-cloudflare-mitigation + cookie-selection-bug

**Root cause 1 (Cloudflare anti-bot stall):** `provideRuTrackerHttpClient`
in `:core:tracker:client` constructed a `Ktor + OkHttp` HttpClient with
no cookies, no Accept-Language, no Accept, no Accept-Encoding, and no
explicit User-Agent — i.e. a client whose HTTP/2 header shape is a
flag-raising fingerprint to Cloudflare's anti-bot. A bare POST to
`rutracker.org/forum/login.php` was accepted by the TCP+TLS layer but
never received a response body — verified via Ktor `Logging` plugin
output. Host-side curl with normal browser-like headers returns
HTTP/2 200 in <1s for the same URL.

**Root cause 2 (cookie selection picked wrong cookie as session token):**
`RuTrackerInnerApiImpl.login()` previously extracted `token =
cookies.firstOrNull { !it.contains("bb_ssl") }` from the final-hop
`Set-Cookie` headers. When Cloudflare in front of rutracker started
emitting `cf_clearance=…` on every response, this filter started
selecting `cf_clearance` as the "rutracker session token". The
subsequent `mainPage(token)` GET then sent `Cookie: cf_clearance=…`
without the actual `bb_data` session token, so rutracker returned
the guest page and `parseUserId` couldn't find the logged-in element.

**Affected files:**
- `core/tracker/client/src/main/kotlin/lava/tracker/client/di/TrackerClientModule.kt`
- `core/tracker/rutracker/src/main/kotlin/lava/tracker/rutracker/impl/RuTrackerInnerApiImpl.kt`

**Fix:**
1. Installed `HttpCookies` plugin (`AcceptAllCookiesStorage`) on the
   rutracker HttpClient.
2. Added browser-class `defaultRequest` headers (Accept,
   Accept-Language, Accept-Encoding) + Chrome 124 / Android 14 / Pixel 8
   User-Agent.
3. Added a `runCatching { httpClient.get(Index).bodyAsText() }`
   pre-flight inside `RuTrackerInnerApiImpl.login()` so the
   `cf_clearance` cookie lands in the cookie jar before the POST.
4. Tightened the post-login token extraction to match by name prefix
   (`bb_data` / `bb_session` / `bb_login`) instead of the fragile
   `!contains("bb_ssl")` negation.

**Verification test/challenge:** Ktor wire-log on C02 re-run shows
- 10:16:58 GET `/forum/index.php` → 200 (~1s) — pre-flight succeeds, cookies stored
- 10:16:59 POST `/forum/login.php` → 302 (was: 60s timeout)
- 10:16:59 GET `/forum/index.php` (auto-redirect) → 200 (~1.3s) — logged-in page returned

The Cloudflare stall is fully resolved by this commit. **C02 itself
does NOT yet pass end-to-end** because `GetCurrentProfileUseCase.parseUserId`
throws on the post-login page — `#logged-in-username` element isn't
in the served HTML (selector stale or mobile-shaped variant). That's
a separate domain-archaeology task documented in
`docs/CONTINUATION.md §4.5.3c`.

**Fix commit:** _(this commit)_

**Forensic anchor:** continued Phase 1 systematic-debugging session
2026-05-12 after operator's "continue" directive. C03 fix landed in
`4d27c07`; the same session investigated whether the residual C02
failure was Lava-fixable. CF mitigation succeeded; the remaining
parser failure surfaced a separate bug that's deferred to a follow-up
SP for rutracker HTML parser refresh.

---

## 2026-05-12 — c16-stale-assumption-bluff + parser-selector-fallback + firebase-test-improvement

These three changes are landed together as the anti-bluff-audit response
to the operator's SIXTEENTH §6.L invocation on 2026-05-12.

**Root cause 1 (Challenge16 stale-assumption bluff):**
`Challenge16ApiSupportedFilterTest` asserted "Internet Archive must NOT
appear in the onboarding provider list" because at the time of writing
(Phase 12 α-hotfix) `ArchiveOrgDescriptor.apiSupported` was `false`. Phase
2b (Lava-API per-provider routing) later flipped that to `true` without
updating the test. The test continued to pass green because its `waitUntil`
clause accepted the Welcome screen — where no provider list renders —
making "Internet Archive absent" trivially true. Textbook §6.L bluff:
green-light while the actual behavior was the OPPOSITE of the claim.

**Root cause 2 (`GetCurrentProfileUseCase.parseUserId` brittle selector):**
The parser used a single Jsoup selector `#logged-in-username` to extract
the rutracker user-id post-login. With the Cloudflare anti-bot mitigation
landing in commit `f7d0a62`, the login POST now reaches a 302→200 chain
successfully — but the post-login page rutracker.org serves to the
mitigated client doesn't contain `#logged-in-username` (selector stale
or mobile-shaped variant). Single-selector parsers fail catastrophically
when the served HTML changes; multi-selector fallback gives graceful
degradation.

**Root cause 3 (`FirebaseAnalyticsTrackerTest` verify-only assertions):**
Two tests in `FirebaseAnalyticsTrackerTest` used `verify { mock.foo() }`
as the SOLE assertion — Forbidden Test Pattern per §6.L clause 4
("Verification-only assertions"). Call-counting is permitted as a
secondary signal, never the primary one.

**Affected files:**
- `app/src/androidTest/kotlin/lava/app/challenges/Challenge16ApiSupportedFilterTest.kt`
- `core/tracker/rutracker/src/main/kotlin/lava/tracker/rutracker/domain/GetCurrentProfileUseCase.kt`
- `app/src/test/kotlin/digital/vasic/lava/client/firebase/FirebaseAnalyticsTrackerTest.kt`
- `CLAUDE.md`, `AGENTS.md`, `lava-api-go/CLAUDE.md` (§6.L count + 16th invocation forensic anchor)
- `docs/CONTINUATION.md` (audit findings + verification log)

**Fix:**
1. C16 rewritten to navigate to "Pick your providers" and assert that
   all 4 verified+apiSupported providers (RuTracker.org, RuTor.info,
   Internet Archive, Project Gutenberg) actually render in the list.
   Falsifiability rehearsal documented in the KDoc: setting
   `archiveorg apiSupported=false` causes the IA assertion to fail.
2. `GetCurrentProfileUseCase` now tries 4 selectors in order before
   erroring: `#logged-in-username`, `a.logged-in-as-uname`,
   `.menu-userctrl a[href*='profile.php?u=']`, `a[href*='profile.php?u=']`.
   Error message updated to "logged-in user-id not found — page may be
   guest, or selectors stale" so future agents have a clearer
   diagnostic anchor.
3. `FirebaseAnalyticsTrackerTest`'s `verify`-only tests refactored
   to use `mockk slot` captures with `assertEquals` on the captured
   String values + `assertTrue(slot.isCaptured)` as a non-zero-count
   gate. The captured-value equality is now the primary assertion,
   per §6.L clause 4.
4. Constitutional count update in `CLAUDE.md` §6.L (THIRTEEN → SIXTEEN),
   `AGENTS.md` §6.L (THIRTEEN → SIXTEEN), `lava-api-go/CLAUDE.md` §6.L
   (TEN → SIXTEEN). Forensic anchor for the 16th invocation appended
   to the wall.

**Verification test/challenge:**
- C16 rewritten: PASS on CZ_API34_Phone API 34 live emulator
  (2026-05-12 10:50, `BUILD SUCCESSFUL`).
- 14 of 23 Challenge Tests verified PASS on live emulator this session:
  C00, C01, C03, C11, C12, C13, C14, C15, C16(rewritten), C20, C21, C22
  (in isolation; sweep-mode fails due to C21 back-press state leak —
  not a real-user bug), C23, C24.
- C02 still does not pass end-to-end (parseUserId fails even with
  fallback selectors). The Cloudflare-mitigation portion of C02 is
  fully verified working.

**Fix commit:** _(this commit)_

**Forensic anchor:** operator's SIXTEENTH §6.L invocation, 2026-05-12,
verbatim: "Do EVERYTHING NOW!!! ... Make sure that all existing tests
and Challenges do work in anti-bluff manner ... they MUST confirm that
all tested codebase really works as expected! ... execution of tests
and Challenges MUST guarantee the quality, the completition and full
usability by end users of the product!"

---

## 2026-06-02 — apiengine-missing-serialization-plugin

**Root cause:** `:core:apiengine` (`core/apiengine/build.gradle.kts`) applied
only `id("lava.android.library")` and did NOT apply the kotlinx-serialization
compiler plugin (`id("lava.kotlin.serialization")`). `NativeApiEngine.start()`
calls `json.encodeToString(config.toDto())` on the `@Serializable ConfigDto`
(and `status()` decodes `@Serializable StatusDto`), but without the compiler
plugin no serializers are generated, so the reflective lookup threw at runtime:
`kotlinx.serialization.SerializationException: Serializer for class 'ConfigDto'
is not found. Please ensure that class is marked as '@Serializable' and that the
serialization compiler plugin is applied.` This is independent of R8 — the DEBUG
build reproduces it. The on-device controller mapped the failure to Error, so the
:api-app landing screen never reached "Running"; the Phase E real-device
Challenges C02/C03/C04 timed out waiting for "Running". Fake-based unit tests
(FakeApiEngine) passed because they bypass the real serializer — the canonical
§6.J/§6.Z bluff class.

**Affected files:**
- `core/apiengine/build.gradle.kts` — applied `id("lava.kotlin.serialization")`;
  removed the now-redundant explicit `kotlinx.serialization.json` dep (the
  convention plugin contributes it).
- `core/apiengine/src/main/kotlin/lava/apiengine/NativeApiEngine.kt` —
  `ConfigDto`/`StatusDto` + `toDto()`/`toApiStatus()` changed `private` →
  `internal`, and the `defaultJson` companion `private` → `internal`, so the JVM
  regression test exercises the EXACT production serializer path. (Public
  `ApiEngine`/`ApiConfig`/`ApiStatus` API unchanged.)

**Fix:** apply the project's kotlinx-serialization convention plugin so the
`@Serializable` DTOs get generated serializers.

**Verification test/challenge:**
- `core/apiengine/src/test/kotlin/lava/apiengine/ConfigSerializationTest.kt`
  (cheap JVM, §6.T.1 reproduction). RED with plugin removed:
  `kotlinx.serialization.SerializationException: Serializer for class 'ConfigDto'
  is not found ...` (4/4 FAIL); GREEN with plugin applied:
  `:core:apiengine:testDebugUnitTest BUILD SUCCESSFUL` (13/13).
- Real-device re-run (Pixel_8 / API35 / arm64-v8a, host-direct+HVF cold boot):
  serialization fix PROVEN — `grep -c SerializationException` over the post-Start
  logcat is **0** (was the Phase E blocker). C01 PASS. Evidence:
  `.lava-ci-evidence/phase-e-api-app/2026-06-02-challenge-green-after-serialization-fix.md`.

**Remaining defect (separate, out of this task's scope — reported, not fixed):**
The Challenge re-run surfaced a DISTINCT pre-existing native-linking defect in
`lava-api-go/`: the prebuilt Go c-shared `liblavaapi.so` has no `SONAME`, so the
JNI bridge `liblavaapi_jni.so` records the absolute host build path
(`/Users/.../lava-api-go/build/jniLibs/arm64-v8a/liblavaapi.so`) as its
`DT_NEEDED`, which the on-device linker cannot resolve → `dlopen failed` →
embed-start unreachable → C02/C03/C04 still time out. Remediation (owed to
`lava-api-go/`, NOT applied here): add
`-ldflags="-extldflags=-Wl,-soname,liblavaapi.so"` to the `go build
-buildmode=c-shared` in `lava-api-go/scripts/build-cshared.sh`. Full diagnosis in
the evidence file above.

**Fix commit:** _(this commit)_

**Forensic anchor:** Phase E real-device Challenge run caught the serialization
defect (`.lava-ci-evidence/phase-e-api-app/2026-06-02-phase-e-challenge-execution.md`)
— the §6.J/§6.Z bluff class: JVM unit tests green, real embed-start broken for
every user.

## 2026-06-02 — cshared-liblavaapi-missing-soname

**Root cause:** the prebuilt Go c-shared `liblavaapi.so` (built by
`lava-api-go/scripts/build-cshared.sh` via `go build -buildmode=c-shared`) had
NO `DT_SONAME`. The JNI bridge `liblavaapi_jni.so`
(`lava-api-go/cmd/lavaapi-cshared/jni/CMakeLists.txt`) imports it as a CMake
`IMPORTED SHARED` library via its absolute `IMPORTED_LOCATION`, so the NDK
linker recorded the **absolute host build path**
(`/Users/.../lava-api-go/build/jniLibs/arm64-v8a/liblavaapi.so`) as the bridge's
`DT_NEEDED`. On-device, the Android dynamic linker cannot resolve a host
filesystem path → `dlopen failed: library "/Users/.../liblavaapi.so" not found:
needed by .../liblavaapi_jni.so` → `LavaNative.nativeStart` is unreachable →
`ApiEngineController.start()` maps to Error → the :api-app Challenges C02/C03/C04
time out waiting for "Running". Proven with `llvm-readelf -d`, NOT guessed.
(This is the "remaining defect" the 2026-06-02 serialization-fix entry above
recorded as out-of-scope; it is now fixed.)

**Affected files:**
- `lava-api-go/scripts/build-cshared.sh` — added the NDK linker soname flag to
  the c-shared `go build`: `-ldflags="-extldflags=-Wl,-soname,liblavaapi.so"`.

**Fix:** give the c-shared `.so` a real `DT_SONAME` (`liblavaapi.so`, relative)
so any consumer linking against it records the SONAME — not the absolute host
path — in `DT_NEEDED`. The Android linker then resolves it from the APK's lib
dir at `dlopen` time.

**Verification (ELF-level, definitive):**
- `llvm-readelf -d` of all 3 rebuilt ABIs: `DT_SONAME = liblavaapi.so` present
  (was absent before). `go build` EXIT 0 for arm64-v8a / x86_64 / armeabi-v7a;
  `LavaApiStart` present in each dynamic symbol table.
- `llvm-readelf -d` of `liblavaapi_jni.so` **inside the rebuilt
  `api-app-debug.apk`**: `(NEEDED) liblavaapi.so` (relative) — was the absolute
  host path. The APK ships both `lib/arm64-v8a/liblavaapi.so` (53 MB) +
  `liblavaapi_jni.so` (25 KB). `:api-app:assembleDebug :api-app:assembleDebugAndroidTest`
  BUILD SUCCESSFUL.
- Full evidence:
  `.lava-ci-evidence/phase-e-api-app/2026-06-02-soname-fix-and-containers-gate-blocker.md`.

**Runtime gate status (honest, §6.Z/§6.J/§6.AG):** the C01-04 runtime GREEN
through the Containers submodule is OWED on a Containers `cmd/emulator-matrix`
`--gradle-module` flag — the CLI is hardwired to `:app:connectedDebugAndroidTest`
(`submodules/containers/pkg/emulator/{android,containerized}.go`) and cannot run
`:api-app` Challenges; that submodule is out of this task's touch-list. The
emulator boot itself WAS Containers-orchestrated (`--runner=auto`→host-direct+HVF,
Pixel_8/API35 cold-booted AVD), proven by the runner's `[matrix-diag]` output.
No green was faked; the wrong-module run was stopped before it could false-pass.
`scripts/run-api-app-challenge-matrix.sh` (added this commit) is module-parameterised
so the gate runs the moment Containers gains the flag.

**Fix commit:** _(this commit)_

**Forensic anchor:** the soname defect was caught by the Phase E real-device
Challenge re-run AFTER the serialization fix
(`.lava-ci-evidence/phase-e-api-app/2026-06-02-challenge-green-after-serialization-fix.md`)
— a native-ELF bluff vector invisible to every JVM/host test: the libraries
link + the APK assembles fine on the host, but the embed cannot `dlopen` on
the user's device.

---

## archiveorg search pagination halved totalPages (under-reported page count)

**Discovered:** 2026-06-06 (Completeness Program Phase 4, Stream B), while adding
anti-bluff unit tests for the Internet Archive DTO→domain mapping.

**Root cause:** `SearchResponseDto.toDomain()` computed `totalPages` with page
size 100 (`response.numFound / 100`), while the search request issued by
`ArchiveOrgSearch` (and `ArchiveOrgBrowse`) uses `rows=50`. `toBrowseResult()`
already used the correct `/50`. Because the divisor was double the real page
size, `totalPages` for search results was under-reported by ~half — the search
UI could not paginate into the back half of any result set with more than 50
hits (e.g. 1234 hits reported 13 pages instead of 25).

**Affected files:**
- `core/tracker/archiveorg/src/main/kotlin/lava/tracker/archiveorg/feature/ArchiveOrgDto.kt`
  — `toDomain()` divisor 100 → 50, matching the `rows=50` request and
  `toBrowseResult()`.

**Fix:** use page size 50 in `toDomain()` to derive `totalPages`, with a comment
anchoring the value to the `rows=50` request in `ArchiveOrgSearch`.

**Verification:** `ArchiveOrgDtoTest.toDomain computes total pages by ceiling
division of numFound by 50` asserts exact totalPages for 0/1/50/51/100/101/1234
hits. Falsifiability: re-introducing the `/100` divisor fails that test with the
expected vs actual page-count mismatch; reverting to `/50` passes. The test
matrix also covers `toBrowseResult` totalPages and the conditional metadata map.

**Fix commit:** _(this commit)_

---

## formatSize rendered comma-decimal separators on non-US locales

**Discovered:** 2026-06-06 (Completeness Program Phase 3, Detekt correctness backlog).

**Root cause:** `core/tracker/rutracker/.../domain/Utils.kt:102` `formatSize` used
`String.format("%.1f %sB", …)` without a `Locale`, so the JVM default locale
applied. On any comma-decimal locale (de/ru/fr/…) a 1.5 MB torrent rendered as
"1,5 MB".

**Fix:** `String.format(Locale.ROOT, …)`.

**Verification:** `FormatSizeLocaleTest` forces `Locale.GERMANY`/`ru-RU` and
asserts dot output. Falsifiability: Locale-less format → 2 of 4 tests FAIL
(expected "1.5 MB" but was "1,5 MB"). **Fix commit:** bc0a478d.

---

## DownloadServiceImpl cache data race

**Discovered:** 2026-06-06 (Completeness Program Phase 3, Android leak/race sweep).

**Root cause:** `DownloadServiceImpl.cache` was a plain HashMap read on the
caller's coroutine dispatcher (`downloadTorrentFile`) and written from the
`DownloadManager` `BroadcastReceiver.onReceive` (main thread). Concurrent
downloads touched a non-thread-safe map across two threads.

**Fix:** extracted `DownloadUriCache` backed by `ConcurrentHashMap` (pure-JVM,
unit-testable per Fifth Law).

**Verification:** `DownloadUriCacheConcurrencyTest` (16 threads × 500 keys).
Falsifiability: HashMap backing → test FAILS; ConcurrentHashMap → 3/3 green.
**Fix commit:** 76fb0879.

---

## ApiEngineController.start() TOCTOU double-bind

**Discovered:** 2026-06-06 (Completeness Program Phase 3/4).

**Root cause:** `ApiEngineController.start()` re-entry guard was a read-then-set
on `MutableStateFlow`. The controller is a Hilt singleton driven by the
ViewModel (Dispatchers.Default) and the Service (Main.immediate, ACTION_RESTART);
two concurrent start() calls could both observe Stopped and both bind the embed
("address already in use").

**Fix:** atomic `_state.compareAndSet` guard loop + a `@VisibleForTesting`
guard-checkpoint seam (Fifth Law: refactor for testability).

**Verification:** `ApiEngineControllerTest.concurrent start parked in the guard
gap binds the embed exactly once` parks one caller in the read→set gap. 
Falsifiability: read-then-set → test FAILS (double-bind); compareAndSet → 8/8.
**Fix commit:** 30e880c6.

---

## TestSearchHistoryRepository.remove kept the matched row (bluff fake)

**Discovered:** 2026-06-06 (Completeness Program Phase 4, flagged independently by
two subagent streams).

**Root cause:** the shared `:core:testing` fake's `remove(id)` did
`filter { it.id == id }` — it KEPT the matched row and dropped the others, the
inverse of the real `SearchHistoryRepositoryImpl.remove` (Room `delete(id)`). A
Third-Law bluff fake.

**Fix:** `filterNot { it.id == id }`; added `TestSearchHistoryRepositoryTest`.

**Verification:** the test asserts remove(1) on [0,1,2] leaves [0,2].
Falsifiability: the shipped bug fails it (expected [0,2] but was [1]).
feature/search + feature/menu stay green. **Fix commit:** _(this session)_.

## On-device API embed could silently drift from the lava-api-go source (no input-tracking)

**Discovered:** 2026-06-06 (Completeness Program, STREAM API-SYNC).

**Root cause:** the `core:apiengine` Gradle `buildCshared` task declared ONLY
`inputs.file(cSharedScript)` as its input. The lava-api-go SOURCE TREE that
actually compiles into `liblavaapi.so` was NOT declared as a task input, so
Gradle considered the task up-to-date whenever the per-ABI `.so/.h` outputs
existed — even after the lava-api-go Go source changed. A developer who edited
`lava-api-go/internal/...` and re-assembled the api-app got the OLD `.so`: the
on-device API embed silently drifted from the current API codebase, while every
other gate stayed green (a §11.4.69 / §6.J "tests pass, wrong-version feature"
bluff vector). There was no mechanism asserting "the packaged embed equals the
current API source".

**Affected files:**
- `core/apiengine/build.gradle.kts` (missing source inputs on `buildCshared`)
- `lava-api-go/scripts/build-cshared.sh` (no source-hash injection / manifest)
- `lava-api-go/internal/version/version.go`, `internal/mobile/mobile.go`
- `core/apiengine/.../ApiEngine.kt`, `NativeApiEngine.kt`

**Fix (drift-prevention mechanism):**
1. `scripts/compute-api-source-hash.sh` — single source of truth: a
   deterministic sha256 over the exact non-test `.go` files under
   `cmd/lavaapi-cshared` + `internal` plus `go.mod`/`go.sum` (the file set that
   links into the `.so`).
2. `build-cshared.sh` injects that hash into `version.SourceHash` via
   `-ldflags -X` (the running `.so` reports it through `mobile.Status()`), and
   writes the committed manifest `core/apiengine/src/main/resources/api-source.hash`
   on every successful build.
3. `scripts/check-api-app-sync.sh` — CI gate (wired into `scripts/ci.sh`,
   both `--changed-only` and `--full`): recompute the live hash, compare to the
   committed manifest, exit 1 loudly on mismatch.
4. `core/apiengine/build.gradle.kts` `buildCshared` now declares the lava-api-go
   source dirs + `go.mod`/`go.sum` as `@InputFiles`, so Gradle re-runs
   `build-cshared.sh` on ANY embed-source change (kills the stale-cache drift).
5. On-device proof: api-app Challenge C05
   (`Challenge05ApiEmbedSourceHashMatchesTest`) starts the real embed and asserts
   `ApiStatus.sourceHash == BuildConfig.LAVA_API_SOURCE_HASH` (no drift), with an
   empty-hash hard-fail discrimination.

**Verification:** `lava-api-go/tests/contract/sourcehash_contract_test.go`
asserts hash stability, manifest-matches-live, and falsifiability (a content
edit to a tracked embed-linked `.go` changes the hash). 3/3 PASS. The gate
`scripts/check-api-app-sync.sh` PASSES on the seeded manifest; falsifiability
rehearsal: appending a byte to `internal/version/version.go` → gate exits 1
("on-device API embed is STALE"); revert → exit 0. **Fix commit:** _(this
session)_.

---

## 2026-06-08 — kinozal-magnet-link-declared-but-null (§6.E bluff)

**Root cause:** `KinozalDescriptor` declares the `MAGNET_LINK` capability,
but `KinozalDownload.getMagnetLink()` returned `null` even though the magnet
is parsed from the topic/search HTML — a declared-but-empty capability, i.e.
a §6.E capability-honesty bluff. The `getMagnetLink` interface method is
non-suspend and cannot itself fetch.

**Affected files:**
- `core/tracker/kinozal/src/main/kotlin/lava/tracker/kinozal/feature/KinozalDownload.kt`
- `core/tracker/kinozal/src/main/kotlin/lava/tracker/kinozal/feature/KinozalTopic.kt`
- `core/tracker/kinozal/src/main/kotlin/lava/tracker/kinozal/magnet/KinozalMagnetCache.kt` (NEW)
- `core/tracker/kinozal/src/main/kotlin/lava/tracker/kinozal/KinozalClientFactory.kt`
- `core/tracker/kinozal/src/test/kotlin/lava/tracker/kinozal/feature/KinozalDownloadTest.kt`
- `core/tracker/kinozal/src/test/kotlin/lava/tracker/kinozal/feature/KinozalTopicTest.kt`
- `core/tracker/kinozal/src/test/kotlin/lava/tracker/kinozal/KinozalClientFactoryCloneUrlTest.kt`

**Fix:** wire it honestly — a `@Singleton KinozalMagnetCache` populated by the
real topic-view path (`KinozalTopic.getTopic`), read back synchronously by
`getMagnetLink(id)`; honest `null` until a topic surfaces it. Same pattern
RuTracker documents for `GetMagnetLinkUseCase`. Cache threaded through the
clone-override path.

**Verification test/challenge:** `KinozalDownloadTest` — "getMagnetLink
returns real magnet after the topic page has been viewed". Falsifiability
rehearsal (per commit body): reverting `getMagnetLink` to `= null` (the
original defect) FAILED with `AssertionError: magnet should be exposed after
topic view (KinozalDownloadTest.kt:59)`; reverted, `core:tracker:kinozal:test`
all green (forced `--rerun-tasks`).

**Fix commit:** `6fa31ad2`

**Forensic anchor:** §6.E capability-honesty audit, 2026-06-08 session.

---

## 2026-06-08 — nnmclub-magnet-link-declared-but-null (§6.E bluff)

**Root cause:** `NnmclubDescriptor` declares `MAGNET_LINK`, but
`NnmclubDownload.getMagnetLink()` returned `null` though both
`NnmclubSearchParser` and `NnmclubTopicParser` parse the genuine magnet from
NNM-Club HTML — a §6.E bluff.

**Affected files:**
- `core/tracker/nnmclub/src/main/kotlin/lava/tracker/nnmclub/feature/NnmclubDownload.kt`
- `core/tracker/nnmclub/src/main/kotlin/lava/tracker/nnmclub/feature/NnmclubTopic.kt`
- `core/tracker/nnmclub/src/main/kotlin/lava/tracker/nnmclub/feature/NnmclubSearch.kt`
- `core/tracker/nnmclub/src/main/kotlin/lava/tracker/nnmclub/http/NnmclubMagnetCache.kt` (NEW)
- `core/tracker/nnmclub/src/main/kotlin/lava/tracker/nnmclub/NnmclubClientFactory.kt`
- `core/tracker/nnmclub/src/test/kotlin/lava/tracker/nnmclub/feature/NnmclubMagnetExposureTest.kt` (NEW)
- `core/tracker/nnmclub/src/test/kotlin/lava/tracker/nnmclub/feature/NnmclubDownloadTest.kt`
- `core/tracker/nnmclub/src/test/kotlin/lava/tracker/nnmclub/feature/NnmclubSearchTest.kt`
- `core/tracker/nnmclub/src/test/kotlin/lava/tracker/nnmclub/feature/NnmclubTopicTest.kt`
- `core/tracker/nnmclub/src/test/kotlin/lava/tracker/nnmclub/NnmclubClientTest.kt`
- `core/tracker/nnmclub/src/test/kotlin/lava/tracker/nnmclub/NnmclubClientFactoryCloneUrlTest.kt`
- `core/tracker/nnmclub/src/test/resources/fixtures/nnmclub/search/search-with-magnet-2026-06-08.html` (NEW fixture)

**Fix:** wire it honestly — a `@Singleton NnmclubMagnetCache` populated by the
real topic-view (`NnmclubTopic.getTopic`) and search-row
(`NnmclubSearch.search`) paths, read back by `getMagnetLink(id)`; honest
`null` when nothing has surfaced it. Cache threaded through the clone-override
path. Adds a real fixture (`search-with-magnet-2026-06-08.html`) containing a
magnet row.

**Verification test/challenge:** `NnmclubMagnetExposureTest`
(`lava.tracker.nnmclub.feature`). Falsifiability rehearsal (per commit body):
reverting `getMagnetLink` to `return null` (the historical bluff) FAILED 2 of
3 — "exposes the parsed magnet after the topic page surfaces it" +
"after a search row surfaces it" — `AssertionError` at
`NnmclubMagnetExposureTest.kt:50` and `:82`; reverted,
`core:tracker:nnmclub:test` 27 tests / 0 failures (forced `--rerun-tasks`).

**Fix commit:** `13703bc8`

**Forensic anchor:** §6.E capability-honesty audit, 2026-06-08 session.

---

## 2026-06-08 — rutor-magnet-link-declared-but-null + bluff-codifying test (§6.E)

**Root cause:** `RuTorDescriptor` declares `MAGNET_LINK` but
`RuTorDownload.getMagnetLink()` returned an unconditional `null` though
`RuTorTopicParser` + `RuTorSearchParser` parse the genuine magnet from RuTor
HTML — a §6.E bluff. Worse, a pre-existing test "rutor has no synchronous
magnet (Capability Honesty)" CODIFIED the bluff as intentional.

**Affected files:**
- `core/tracker/rutor/.../feature/RuTorDownload.kt`
- `core/tracker/rutor/.../feature/RuTorTopic.kt`
- `core/tracker/rutor/.../feature/RuTorSearch.kt`
- `core/tracker/rutor/.../magnet/RuTorMagnetCache.kt` (NEW)
- `core/tracker/rutor/.../RuTorClientFactory.kt`
- `core/tracker/rutor/.../feature/RuTorMagnetExposureTest.kt` (NEW)
- `core/tracker/rutor/.../feature/RuTorDownloadTest.kt`
- `core/tracker/rutor/.../feature/RuTorSearchTest.kt`
- `core/tracker/rutor/.../feature/RuTorTopicTest.kt`
- `core/tracker/rutor/.../RuTorClientTest.kt`
- `core/tracker/rutor/.../RuTorClientFactoryCloneUrlTest.kt`
- `.gitmodules` + `submodules/{doc_processor,llm_orchestrator,llm_provider,llms_verifier,vision_engine}` (the 5 HelixQA dep submodule gitlinks landed in this same commit; see the HelixQA pin entry below)

> UNCONFIRMED: the exact `core/tracker/rutor` source-root prefix is abbreviated
> above; `git show --stat 6d87019d` reports the file basenames but the path
> column is truncated in the stat output. The basenames + `feature/` /
> `magnet/` subdirs are confirmed.

**Fix:** wire it honestly via a `@Singleton RuTorMagnetCache` populated by the
real topic-view + search-row paths, read back by `getMagnetLink(id)`; honest
`null` on miss. Threaded through the clone-override path. The bluff-codifying
test was replaced with honest-absence semantics.

**Verification test/challenge:** `RuTorMagnetExposureTest`. Falsifiability
rehearsal (per commit body): reverting `getMagnetLink` to `return null` (the
historical bluff) FAILED 2 of 3 — "exposes the parsed magnet after the topic
page surfaces it" + "after a search row surfaces it" — `AssertionError` at
`RuTorMagnetExposureTest.kt:57` and `:97`; reverted,
`core:tracker:rutor:test` 76 tests / 0 failures; `RuTorMagnetExposureTest`
3/0/0 (forced `--rerun-tasks`).

**Fix commit:** `6d87019d`

**Forensic anchor:** §6.E capability-honesty audit, 2026-06-08 session — this
one is the sharpest §6.J case of the four because a passing test had been
encoding the bluff as the intended contract.

---

## 2026-06-08 — gutenberg-dishonest-torrent-download-capability (§6.E)

**Root cause:** `GutenbergDescriptor` declared `TORRENT_DOWNLOAD` but the
provider serves HTTP e-books (Gutendex EPUB/text/HTML), not `.torrent` files
— a §6.E capability-honesty bluff. The downloaded artifact is not a bencoded
`.torrent`.

**Affected files:**
- `core/tracker/gutenberg/src/main/kotlin/lava/tracker/gutenberg/GutenbergDescriptor.kt`
- `core/tracker/gutenberg/src/main/kotlin/lava/tracker/gutenberg/GutenbergClient.kt`
- `core/tracker/gutenberg/src/test/kotlin/lava/tracker/gutenberg/GutenbergCapabilityHonestyTest.kt` (NEW)
- `core/tracker/gutenberg/src/test/kotlin/lava/tracker/gutenberg/GutenbergClientTest.kt`
- `core/tracker/gutenberg/src/test/kotlin/lava/tracker/gutenberg/GutenbergDescriptorTest.kt`

**Fix:** mirror archiveorg's established honest pattern — drop
`TORRENT_DOWNLOAD` from the descriptor; `GutenbergClient.getFeature(
DownloadableTracker::class)` returns `null` explicitly (the `GutenbergDownload`
impl stays wired but unexposed via the torrent surface). No enum/api change.
Stale tests that encoded the bluff were removed.

**Verification test/challenge:** `GutenbergCapabilityHonestyTest` pins the
contract. Falsifiability rehearsal (per commit body): re-declaring
`TORRENT_DOWNLOAD` + re-gating `getFeature` so the EPUB-serving impl is again
exposed via the torrent-download surface FAILED — "download capability is
honest about the artifact it produces" — `AssertionError: TORRENT_DOWNLOAD
declared but downloaded artifact is not a bencoded .torrent (first byte was
'e', expected 'd') (GutenbergCapabilityHonestyTest.kt:91)`; reverted,
independently re-verified `GutenbergCapabilityHonestyTest` 2/0/0.

**Fix commit:** `b197f96d`

**Forensic anchor:** §6.E capability-honesty audit, 2026-06-08 session.

---

## 2026-06-08 — helixqa-broken-pin-gomod-conflict-markers (broke lava-api-go build)

**Root cause:** HelixQA's `go.mod` `replace` directives require 8 sibling
own-org (`digital.vasic.*`) Go modules; 3 were present
(challenges/containers/security), 5 were MISSING, so the full `helixqa` binary
could not build. The broken pinned HelixQA `go.mod` also carried unresolved
merge-conflict markers (at lines 124 / 131 / 138 per the commit body), which
broke `lava-api-go`'s Go build.

**Affected files:**
- `submodules/helixqa` (pin bump `5112906` → `dd3cf1d`)
- `docs/qa/helixqa-dependency-submodules.md` (NEW doc)
- `docs/CONTINUATION.md` (§0 + §3 sync)
- (paired commit `6d87019d` adds the 5 dep submodule gitlinks
  `submodules/{doc_processor,llm_orchestrator,llm_provider,llms_verifier,vision_engine}`
  from `vasic-digital/{DocProcessor,LLMOrchestrator,LLMProvider,LLMsVerifier,VisionEngine}`)

> UNCONFIRMED: the conflict-marker line numbers 124/131/138 are quoted from the
> commit body, not independently re-diffed in this audit. The pin SHAs
> `5112906 → dd3cf1d` and the `submodules/helixqa` change are confirmed by
> `git show --stat dd72669b`.

**Fix:** bump the helixqa pin `5112906 → dd3cf1d` (clean `go.mod`); add the 5
missing dep submodules (local paths lowercase snake_case per §11.4.29 matching
HelixQA's `../doc_processor` replace targets; URLs use the real CamelCase repo
names per the must-not-break-technology carve-out).

**Verification:** physical proof recorded in the commit body — `go build
./cmd/helixqa` exit 0 → 29.7 MB binary; `helixqa version` → v0.2.0; and
`lava-api-go` builds (exit 0).

**Fix commit:** `dd72669b` (pin bump + doc) — the 5 dep submodule gitlinks
landed in companion commit `6d87019d`.

**Forensic anchor:** operator directive ("add all missing dependency
Submodules from vasic-digital/HelixDevelopment") + §11.4.27/§11.4.28 (own-org
deps reachable from root). OWED (tracked in CONTINUATION): §6.W GitLab mirrors
for the 5 new submodules; §11.4.29 upstream CamelCase→snake_case repo rename.

---

## 2026-06-08 — nav-compose-2.9.0-test-teardown-lifecycle-race

**Root cause (confirmed, stack-trace-quoted in the systematic-debugging
investigation):** androidx-navigation-compose 2.9.0's single-top
`NavBackStackEntry` lifecycle reaches `CREATED` asynchronously; the
Espresso/Compose test-runner's immediate `Activity.performDestroy` then tries
`INITIALIZED → DESTROYED` in one step → `IllegalStateException "State must be
at least 'CREATED' to be moved to 'DESTROYED'"`. 2.9.1 release note: "Fixed an
issue that caused NavEntries instantiated using single top to never go beyond
CREATED in their Lifecycle.State" ([I043ba], b/421095236) — matches exactly
(Lava uses `launchSingleTop=true`).

**User impact:** NONE — real users get intervening lifecycle pauses. It is a
synthetic test-teardown artifact that had forced Challenge tests C04–C08 +
deep C11 to be gutted to shallow versions. Blast radius LOW: single direct
consumer (`core/navigation`); `-Xcontext-receivers` is orthogonal (a Kotlin
compiler feature, not the nav lib).

**Affected files:**
- `gradle/libs.versions.toml` (pin `2.9.0 → 2.9.1`)

**Fix:** bump `androidx-navigation-compose` `2.9.0 → 2.9.1`.

**Verification:** `./gradlew :core:navigation:compileDebugKotlin` → BUILD
SUCCESSFUL with 2.9.1.

> UNCONFIRMED / OWED (per commit body): the race-fix confirmation (restoring
> the deep C04–C08/C11 Challenges + proving the teardown crash is gone) is
> device-gated — requires the Genymotion VM / a real device, OWED when a
> device is booted. Not yet verified on-device in this session.

**Fix commit:** `7e6e7bcb`

**Forensic anchor:** systematic-debugging investigation of the C04–C08/C11
shallow-test regression, 2026-06-08 session.

---

## §6.E — RuTracker `MAGNET_LINK` declared but `getMagnetLink` returned null (2026-06-08)

**Class:** §6.E capability-honesty bluff (declared-but-empty capability).

**Symptom:** `RuTrackerDescriptor` declares `TrackerCapability.MAGNET_LINK`
(`RuTrackerDescriptor.kt:36`) and `getFeature(DownloadableTracker)` resolves to a
non-null `RuTrackerDownload`, but the only reachable `getMagnetLink` impl
(`GetMagnetLinkUseCase`) returned `null` unconditionally for EVERY id on the real
stack — even though RuTracker genuinely parses the magnet (`TopicMapper.kt:121`).
A user tapping "Magnet" on a RuTracker topic could never get the magnet the app
had already parsed. Identical to the bluff RuTor closed with `RuTorMagnetCache`.

**Root cause:** `GetMagnetLinkUseCase.invoke(id) = null` stub; no path carried the
parsed `TorrentItem.magnetUri` from the topic/search fetch to the synchronous
`DownloadableTracker.getMagnetLink`. Audit: `docs/qa/magnet-label-honesty-audit-2026-06-08.md` (W4a).

**Affected files:**
- `core/tracker/rutracker/.../magnet/RuTrackerMagnetCache.kt` (new — RuTor pattern)
- `core/tracker/rutracker/.../domain/GetMagnetLinkUseCase.kt` (reads the cache)
- `core/tracker/rutracker/.../feature/RuTrackerTopic.kt` (populates on getTopic)
- `core/tracker/rutracker/.../feature/RuTrackerSearch.kt` (populates per result row)
- `core/tracker/rutracker/.../RuTrackerSubgraphBuilder.kt` (shared cache, clone path)
- `core/tracker/client/.../di/TrackerClientModule.kt` (Hilt @Provides reads the cache)

**Fix:** adopt the RuTor magnet-cache pattern — a `@Singleton` process-lifetime
`RuTrackerMagnetCache` populated by `RuTrackerTopic.getTopic` /
`RuTrackerSearch.search` from the already-mapped `magnetUri`, read by
`GetMagnetLinkUseCase`. Honest null preserved for ids never surfaced.

**Verification (§6.T.1 reproduction-before-fix):**
- RED (pre-fix, against the stub): `RuTrackerMagnetExposureTest > getMagnetLink
  exposes the magnet the topic mapper surfaced` FAILED — "§6.E BLUFF: ... getMagnetLink
  returned null".
- Mutation rehearsal (post-fix, `invoke → null`): same test FAILED again (exit 1).
- GREEN (fix): `RuTrackerMagnetExposureTest` 2/2 PASS; `:core:tracker:client:testDebugUnitTest`
  BUILD SUCCESSFUL (Hilt graph valid; enumeration gate 5/5 + 8/8).

**Forensic anchor:** SA4 §6.E honesty audit, 2026-06-08 parallel-fleet session.
Full-loop (topic fetch → cache → download) covered on-device by the restored
C05/C06 Challenges (device run owed).

---

## §6.Q/C37 — CrossTrackerFallbackModal dead-ended at the screen layer (2026-06-08)

**Class:** dead-UI (capability present in code, unreachable by users — the §6.Q/C37 class).

**Symptom:** `feature/search_result` has a full cross-tracker-fallback feature —
`CrossTrackerFallbackModal` composable + `SearchResultViewModel.proposeFallback`/
`onFallbackAccept`/`onFallbackDismiss` + `SearchResultAction.FallbackAccept`/
`FallbackDismiss` + `SearchResultState.crossTrackerFallback` — BUT
`SearchResultScreen` never read `state.crossTrackerFallback` and never called
`CrossTrackerFallbackModal(...)`. A real user whose search failed on one tracker
could never see the "Try <other tracker>" offer. Found by the SA5 Challenge
restoration audit (C07/C08 had to be written as contract guards, not the real
flow, because the real flow was unreachable).

**Affected files:**
- `feature/search_result/.../SearchResultScreen.kt` (renders the modal)
- `app/src/androidTest/.../challenges/Challenge07CrossTrackerFallbackAcceptTest.kt`
- `app/src/androidTest/.../challenges/Challenge08CrossTrackerFallbackDismissTest.kt`

**Fix:** in `SearchResultScreen`, render `CrossTrackerFallbackModal(failedTracker,
proposedTracker, onAccept = { onAction(FallbackAccept) }, onDismiss = { onAction(
FallbackDismiss) })` when `state.crossTrackerFallback != null` (display names via
the existing `state.providerDisplayNames`). C07/C08 upgraded from contract guards
to real rendered-modal Compose tests (C30 pattern): render → assert
"RuTracker is unavailable" + "Try RuTor"/"Cancel" → tap → assert the callback fires.

**Verification:** `:feature:search_result:compileDebugKotlin` +
`:app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL. §6.J falsifiability (KDoc):
delete the `state.crossTrackerFallback?.let{}` block → dead-end returns; or change
the confirm/dismiss label → the rendered test's `onNodeWithText` finds no node →
assert fails. Device-run of C07/C08 owed on the VM (network-independent).

**Forensic anchor:** SA5 Challenge-restoration finding, 2026-06-08 parallel-fleet session.

---

## §6.J DEVICE-GATE CORRECTION — nav-compose 2.9.1 does NOT fully fix the NavBackStackEntry teardown crash (2026-06-08)

**Correcting the 2.9.0→2.9.1 entry (commit `7e6e7bcb`).** That bump claimed to
fix the test-teardown NavBackStackEntry lifecycle race and explicitly marked the
device confirmation OWED. Device confirmation is now done — on the Genymotion
Pixel 9 VM (API 35) — and it **FALSIFIES the claim** for the `search_input`
route: `Challenge11ArchiveOrgAnonymousSearchTest` crashes the app at
`MainActivity` destroy with
`IllegalStateException: State must be at least 'CREATED' to be moved to
'DESTROYED'` on the `search/search_input?...` NavBackStackEntry
(`LifecycleRegistry.kt:92`). 2.9.1 was necessary but NOT sufficient.

**Status:** OPEN. C00/C01/C07/C08 PASS on the VM (6/7); C11 RED. NOT bluffed
green. Incident:
`.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json`.
Evidence: `.lava-ci-evidence/genymotion/9e7505b3-fleet-run/`.

**Next:** dedicated systematic-debugging cycle — evaluate a newer
androidx-navigation OR a NavHost/teardown lifecycle guard, then device re-verify.

## api-source.hash manifest staleness — §6.A contract test RED→GREEN (2026-06-08)

Commit `7ce9c2b2` edited two embed-linked lava-api-go sources
(`internal/cache/cache.go` + `internal/storage/sqlite.go`) without regenerating
the API↔embed sync manifest, so `TestSourceHash_ManifestMatchesLive`
(`lava-api-go/tests/contract`) failed: committed
`95c36d1773362cee2b90db398f2e2827586fef83071ba3feb12ce51e02e12b9d` !=
live `70ebb53a1a9c3365f651ae7ae651dd4404d58976b97b3b2589db39f4d7752470`.
Root cause: the manifest at `core/apiengine/src/main/resources/api-source.hash`
was last written at `e153d7c0` (2026-06-06), pre-dating `7ce9c2b2`. The contract
test correctly caught real source/embed drift — exactly its §6.A purpose.

**Fix:** regenerated `api-source.hash` to the live
`scripts/compute-api-source-hash.sh` value (the canonical mechanism
`build-cshared.sh` uses). NOT a test edit — the regeneration the test's own error
message prescribes. Verified: `go test ./tests/contract -run TestSourceHash` →
3/3 PASS (incl. the falsifiability sibling `…ContentEditChangesHash`); full
`go test ./...` → exit 0 (39 packages ok, real Postgres-in-podman e2e ran).

## YTS curated provider unreachable — yts.mx domain rotated out of DNS (2026-06-13)

**Symptom:** the YTS curated provider (shipped 2026-06-12, commit `462a0308`)
passed all fixture unit tests but its LIVE call failed:
`live Search: yts: provider: unknown error`. Surfaced by a main-stream §6.J
re-verification (running the `-tags realtrackers` live tests instead of trusting
the prior commit-body claim).

**Root cause:** the provider hardcoded a single base URL `https://yts.mx`. As of
2026-06-13 `yts.mx` has NO A record on public DNS (confirmed `dig @1.1.1.1` and
`@8.8.8.8` both return `<none>`) — YTS rotated its domain since the provider
shipped. The fixture parser was correct (unit tests green) while the feature was
broken for real users (§6.G/§6.L fixture-green / feature-broken). The current
live canonical host is `yts.bz` (yts.lt / yts.am 301-redirect to it).

**Affected files:** `lava-api-go/internal/provider/curated/yts/{client.go,provider.go,live_realtrackers_test.go,yts_test.go}`.

**Fix:** mirror failover — `Client` now holds a list of base URLs
(`DefaultBaseURLs` = yts.mx, yts.bz, yts.lt, yts.am) tried in order; the first
successful answer wins; each attempt is bounded by `perAttemptTimeout` (8s) so a
dead/hanging mirror cannot stall the search. `NewClient(single)` preserved as the
httptest seam; production `New()` uses `NewClientWithMirrors(DefaultBaseURLs)`.
The live test now queries a real movie TITLE (`interstellar`; `1080p` matches no
movie and legitimately returns 0) across the mirror list.

**Verification:** new `TestSearch_FailsOverToHealthyMirror` (falsifiable: break
the failover loop → `Search across [dead, healthy] mirrors should fail over, got
error: yts: HTTP 503`; reverted) + `TestSearch_AllMirrorsDownSurfacesError`.
Live `-tags realtrackers` PASS in 0.559s (fast failover past dead yts.mx to a
live mirror, real magnets). Full `go test ./...` → 0 FAIL; embed source-hash
regenerated `0d7da603`; `check-api-app-sync.sh` GREEN. Evidence:
`.lava-ci-evidence/bluff-hunt/2026-06-13-yts-domain-failover.json`.

**Follow-up:** TPB (apibay.org) + Torrents-CSV (torrents-csv.com) still hardcode
a single domain — same rotation-risk class; mirror-failover hardening noted as a
follow-up.

## Crashlytics remediation sweep — 6 open issues (2026-06-13)

Operator directive: investigate ALL open Crashlytics issues, systematic-debug,
fix/improve, cover with falsifiable validation tests + §6.O closure logs. Real
data pulled from Firebase (project `lava-vasic-digital`, release app). Per-issue:

- **`7df61fdb` NON_FATAL JobCancellationException (8 events, 1.2.21) — FIXED.**
  Catch-site rethrow (defense-in-depth over the prior sink-level filter). New
  `Throwable.rethrowIfCancellation()` in `core/common/.../analytics/CancellationRethrow.kt`
  called first in every coroutine catch/onFailure that records a non-fatal across
  8 ViewModels (onboarding, login, menu, search_input, search_result, topic,
  bookmarks, favorites). Root cause: broad `catch (e: Exception)` swallowed
  Kotlin's structured-concurrency `CancellationException` — both telemetry
  pollution AND broken cooperative cancellation. Test:
  `core/common/.../CancellationRethrowTest.kt` (5 tests). Bluff-Audit: no-op the
  rethrow → 3 tests RED at CancellationRethrowTest.kt:75; reverted.
  Closure: `.lava-ci-evidence/crashlytics-resolved/2026-06-13-jobcancellation-catch-site-rethrow.md`.

- **`40a62f97` FATAL painterResource layer-list (1.2.19) — VERIFIED already
  covered.** `LavaIconsAppIconColorRegressionTest` + closure
  `2026-05-14-welcome-layerlist-painter-crash.md` already in place. No change.

- **`39469d3b` FATAL okhttp schemeless URL "djdnjd" (1.2.21) — FIXED (owed
  regression test added).** The prior closure admitted the use-case test was
  "owed in a follow-up"; this is it. New
  `ProbeMirrorUseCaseTest.schemeless URL returns Unreachable instead of crashing`
  (real `ProbeMirrorUseCase` + real OkHttpClient). Bluff-Audit: change the
  `catch (IllegalArgumentException)` to a non-matching type → test RED with the
  leaked `IllegalArgumentException` at :80; reverted.
  Closure: `.lava-ci-evidence/crashlytics-resolved/2026-06-13-probemirror-schemeless-url-regression.md`.

- **`042b9b61` NON_FATAL CertPath trust-anchor (1.3.3) — VERIFIED already
  covered.** Fixed at 1.3.4 (commit `0deb54e7`); `ProviderCatalogRepositoryTest`
  uses a self-signed-TLS MockWebServer; closure
  `2026-06-12-provider-catalog-fetch-tls.md` present. No change.

- **`6519b490` NON_FATAL rutracker parseUserId "user-id not found" (1.2.22) —
  WORKING-AS-DESIGNED (no production change).** New
  `GetCurrentProfileUseCaseUserIdTest` proves the 4 production selectors DO parse
  a logged-in page (not stale) and the non-fatal fires only on a genuine
  guest/expired page. The logged-in test is the future staleness canary.
  Bluff-Audit: reduce selectors to a non-matching one → canary RED at :100;
  reverted. Closure: `.lava-ci-evidence/crashlytics-resolved/2026-06-13-rutracker-parseuserid-guest-page.md`.

- **`3937b7f0` NON_FATAL SSE "Unable to resolve host lava-api.local" (1.3.0) —
  IMPROVED.** `SearchResultViewModel.applySseError` now classifies
  connectivity-class reasons (host-resolve / refused / timeout) as a
  lower-severity `recordWarning("sse_endpoint_unreachable")` instead of a
  crash-feed non-fatal; genuine backend errors stay non-fatals. User-visible
  Error + Retry UX unchanged. New `String.isConnectivityFailure()` helper. Test:
  `SearchResultSseConnectivityTelemetryTest` (2 tests). Bluff-Audit: force the
  classifier to `if (false)` → host-resolve test RED; reverted.
  Closure: `.lava-ci-evidence/crashlytics-resolved/2026-06-13-sse-host-resolve-telemetry-severity.md`.

**Affected production files:** `core/common/.../analytics/CancellationRethrow.kt` (new);
8 feature ViewModels (rethrow calls); `core/common/build.gradle.kts`
(coroutines-test dep); `feature/search_result/.../SearchResultViewModel.kt`
(SSE severity classifier). `ProbeMirrorUseCase.kt` + rutracker
`GetCurrentProfileUseCase.kt` UNCHANGED (already-correct fixes; tests added).

## §6.AC/§6.O — JobCancellation non-fatal noise: download catch-site rethrow (2026-06-13)

**Crashlytics:** `7df61fdba64f9928b067624d6db395ca` (NON_FATAL,
`kotlinx.coroutines.JobCancellationException` — "StandaloneCoroutine was
cancelled", 8 events / 1 user / first=last 1.2.21; not recurring on 1.3.x).

**Root cause.** `DownloadServiceImpl.downloadHttpFile` (`core/downloads`) — a
`suspend fun` — wrapped its write path in a broad `catch (t: Throwable)` that
called `analytics.recordNonFatal(t, ...)`. When the download is abandoned
mid-write (calling scope cancelled — user left the screen / ViewModel cleared),
`CancellationException` was swallowed AND recorded as a non-fatal: false
telemetry (issue `7df61fdb` dashboard noise) plus broken cooperative
cancellation (the catch returned `null` instead of letting the cancellation
propagate). A grep of every telemetry-recording `catch` across
`core/ feature/ app/` main sources confirmed this was the ONLY remaining
offending site — all 8 feature ViewModels + `LavaApplication` already call
`rethrowIfCancellation()`.

**Fix.** `t.rethrowIfCancellation()` (the existing shared
`lava.common.analytics` helper — reused, not re-created) is now the FIRST
statement of the `downloadHttpFile` catch, before `recordNonFatal`.

**Verification test.**
`core/downloads/src/test/.../DownloadServiceCancellationTest.kt` (new) —
`cancellation during http write is rethrown and never recorded as non-fatal`.
Real `DownloadServiceImpl` + recording `AnalyticsTracker`; only the outermost
Android boundary (`Context` / the `Environment` static the write path uses) is
faked to throw `CancellationException` mid-write. Asserts the cancellation
PROPAGATES and the recorded-non-fatal count is `0`.
Bluff-Audit: delete the rethrow line → `AssertionError: cancellation must
propagate, not be swallowed into a null return`
(`DownloadServiceCancellationTest.kt:87`); reverted → BUILD SUCCESSFUL.

**Affected files:** `core/downloads/.../DownloadServiceImpl.kt` (rethrow),
`core/downloads/build.gradle.kts` (mockk + coroutines-test test deps +
`returnDefaultValues`), `core/downloads/.../DownloadServiceCancellationTest.kt`
(new). Closure log:
`.lava-ci-evidence/crashlytics-resolved/2026-06-13-cancellation-noise-7df61fdb.md`.
Queued for the 1.3.7 release cycle (version files NOT touched).
