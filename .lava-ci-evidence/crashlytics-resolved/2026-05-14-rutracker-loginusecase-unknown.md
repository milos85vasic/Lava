# Crashlytics issue a29412cf6566d0a71b06df416610be57 — closure log

**Issue ID:** `a29412cf6566d0a71b06df416610be57`
**Title:** `lava.tracker.rutracker.domain.LoginUseCase.invoke`
**Subtitle:** "lava.tracker.rutracker.model.Unknown"
**Stack:** `LoginUseCase.kt:40 → invokeSuspend → main looper`
**Type:** FATAL
**Device:** Samsung Galaxy S23 Ultra (SM-S918B), Android 16
**Version:** 1.2.8 (1028) release — 1 event 2026-05-07
**State at closure:** OPEN (operator marks closed)

## Root cause

`LoginUseCase` (in `core/tracker/rutracker/domain/`) handles 4 cases of rutracker's HTML response:
1. Token returned → `Success`
2. login-form + wrong-credits message → `WrongCredits`
3. login-form + captcha → `CaptchaRequired`
4. **Anything else** → `throw Unknown` (line 40 of LoginUseCase.kt)

Case 4 was a `throw` — the singleton `Unknown` Throwable escapes to the calling code. The immediate caller `RuTrackerNetworkApi.login` (line 46) did NOT catch the throw — it just `= loginUseCase.invoke(...)`. So the throw bubbled up the coroutine + reached the main looper as FATAL.

The user-visible scenario: user submits credentials, rutracker returns an HTML response shape that doesn't match any of the 3 known patterns (could be a CAPTCHA variant, server-side change, geo-block message, maintenance page, etc.), app crashes.

## Fix

`RuTrackerNetworkApi.login` now wraps `loginUseCase.invoke(...)` with try/catch:
- `CancellationException` is re-thrown (never swallow structured-concurrency cancellation)
- Any other `Throwable` (including `Unknown` and `NoData`) is converted to `AuthResponseDto.WrongCredits(captcha = null)` — a safe user-visible fallback that lets the user retry without app crash
- Rationale: unknown response shape is most commonly a temp server-side issue or auth gate; treating as wrong-credit is the least-disruptive behavior

The non-fatal record happens at the upstream caller (RuTrackerAuth or the ViewModel) per the Decoupled Reusable Architecture rule — `core/tracker/*` modules cannot directly depend on the Android analytics SDK.

## Validation test (per §6.O + §6.AB)

`core/tracker/rutracker/src/test/.../RuTrackerNetworkApiLoginUnknownRegressionTest.kt` — directly tests the wrap behavior:
- Mocks `LoginUseCase` to throw `Unknown`
- Constructs `RuTrackerNetworkApi` with all 14 dependencies (relaxed mocks for the 13 unused, real mock for loginUseCase)
- Calls `api.login(...)` and asserts result is `AuthResponseDto.WrongCredits` (not a thrown exception)

Verified: `./gradlew :core:tracker:rutracker:test --tests "RuTrackerNetworkApiLoginUnknownRegressionTest"` → BUILD SUCCESSFUL.

Falsifiability rehearsal documented in test KDoc: remove the try/catch in `RuTrackerNetworkApi.login` → test fails with Unknown thrown during login(); restore → passes.

## Closure protocol

Operator marks issue closed in Firebase Console; `LoginUseCase` itself is unchanged (still `throw Unknown` for unknown HTML shapes — this is the legitimate domain-error signal); the safety wrap is at the network-API boundary so any future caller of `LoginUseCase` (e.g. from a different SDK consumer) also benefits from the catch via `RuTrackerNetworkApi`.
