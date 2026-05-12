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
