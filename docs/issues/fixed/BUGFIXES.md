# Lava — Bug Fix Audit Trail

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
**Fix commit:** `<this commit>` (parent) + Containers `9a61a153`.
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
