# §6.O Crashlytics Issue Closure — Search SocketTimeoutException (3-cause fix)

**Date:** 2026-06-24
**Crashlytics issue ID:** `9d4ad2f4d1a8b8697b1506402e045b81`
**App:** client release — `1:815513478335:android:456475e2ef4039d8cfd20a`
**Severity:** NON_FATAL — user-visible UX hang ("no results"; back-press unresponsive)
**Fix commits:** `20d98914` + `0e81730b` + `1aa42536` + `3c1fa159`
**Shipping in:** Android 1.3.11-1073 / api-app 0.2.11-19 / lava-api-go 2.3.33

---

## Stack Trace Summary

```
Exception: java.net.SocketTimeoutException: timeout
  at okhttp3.internal.http2.Http2Stream.takeHeaders(Http2Stream.kt)
  at okhttp3.internal.http2.Http2ExchangeCodec.readResponseHeaders(Http2ExchangeCodec.kt)
  ... (full OkHttp HTTP/2 read-headers chain)

Crashlytics custom keys (from SearchResultViewModel.recordProviderFailure):
  feature          = search
  operation        = streamMultiSearch
  provider         = yts
  screen           = search_result
  error_message    = timeout
  build_type       = release
  version          = 1.3.11 (1072)

Sample event: 2026-06-24T07:39:36Z, device: HUAWEI TXZ-W09 / Android 12 / LANDSCAPE
```

The request was SENT through the full OkHttp chain (including `AuthInterceptor`);
response headers never arrived within the 30 s `readTimeout` configured in
`core/network/impl/.../NetworkModule.kt:174` for the `@Named("lan")` client.
The engine (lava-api-go) side shows NO crash in the companion Crashlytics project
(`1:815513478335:android:d57b960e…`) over the same window — confirming the engine
was alive but not responding within the client deadline.

Investigation docs:
- `docs/issues/2026-06-24-search-timeout-and-interrupt-rootcause.md`
- `docs/issues/2026-06-24-search-timeout-coordination-analysis.md`

---

## Root Cause Analysis

Three independent root causes combined to produce the user-visible failure.

### Root Cause A — Engine: unbounded search handler (no request-context deadline)

`lava-api-go/internal/handlers/v1/search.go` passed `c.Request.Context()` (no
deadline) directly to `p.Search()`. The YTS provider's failover loop iterates up
to N mirrors × `perAttemptTimeout(8 s)` each. With 4+ mirrors and at least one
slow/unreachable host, total engine wall-time could exceed 32 s — past OkHttp's
30 s `readTimeout`. The engine never responded within the client's window, causing
`Http2Stream.takeHeaders` to throw `SocketTimeoutException`.

### Root Cause B — Engine: stale YTS mirror list (`yts.mx` NXDOMAIN)

`lava-api-go/internal/provider/curated/yts/client.go` `DefaultBaseURLs` led with
`yts.mx`, which is NXDOMAIN as of 2026-06-24 (verified by DNS probe). The failover
loop burned full `perAttemptTimeout(8 s)` slots on the dead domain before reaching
live mirrors, compounding the total time past the client deadline. Even with the
engine deadline fix (Root Cause A), the stale mirror list degrades performance.

### Root Cause C — Client: no cancellation on back-press + no client-side timeout

`SearchResultViewModel.observeStreamMultiSearch()` launches an Orbit `intent {}`
coroutine that blocks on `sdk.streamMultiSearch(...).collect {}`. Back-press
dispatched a new `intent {}` (`onBackClick`) that posted a `Back` side effect but
did NOT cancel the in-flight `collect {}` coroutine. Additionally, there was no
`withTimeout` guard, so a slow provider held the `SearchResultContent.Streaming`
spinner for up to 30 s with no Retry affordance.

---

## Affected Files

| File | Root cause | Change |
|---|---|---|
| `lava-api-go/internal/handlers/v1/search.go` | A | `context.WithTimeout(18 s)` deadline wrapping `p.Search()` at line 69 |
| `lava-api-go/internal/provider/curated/yts/client.go` | B | `DefaultBaseURLs` refreshed: `yts.mx` dropped; 5 live mirrors: `yts.bz`, `yts.lt`, `yts.am`, `yts.gg`, `movies-api.accel.li` |
| `feature/search_result/src/main/kotlin/lava/search/result/SearchResultViewModel.kt` | C | `@Volatile activeSearchJob`; `onBackClick()` cancels prior job; `withTimeout(25 000 ms)` wrapping `collect {}`; `TimeoutCancellationException` + generic `Exception` catches with §6.AC telemetry |

---

## Fix Description

**Root Cause A fix (engine deadline) — commit `20d98914`:**
`handlers/v1/search.go:69` wraps `p.Search()` in a child context with an 18 s
deadline (`context.WithTimeout(c.Request.Context(), 18*time.Second)`). The engine
always returns — either with results or a fast `context.DeadlineExceeded` error —
well before the client's 30 s `readTimeout`. No search-handler response can exceed
the engine deadline regardless of mirror count.

**Root Cause B fix (mirror refresh) — commit `0e81730b`:**
`DefaultBaseURLs` updated to `["yts.bz", "yts.lt", "yts.am", "yts.gg",
"movies-api.accel.li"]`. Dead `yts.mx` dropped. Lead mirror `yts.bz` verified
live (132 real results in 911 ms in `TestReproduction_StaleMirrorListFails`
after the fix). §6.R: mirror URLs are protocol-mirrors, covered by the existing
exemption comment in `client.go`.

**Root Cause C fix (client cancel + timeout) — commit `20d98914`:**
Added `@Volatile private var activeSearchJob: Job? = null`. Inside
`observeStreamMultiSearch()`, the coroutine `Job` is captured via
`currentCoroutineContext()[Job]` and stored. `onBackClick()` now calls
`activeSearchJob?.cancel()` before posting `Back`. `collect {}` is wrapped in
`withTimeout(SEARCH_TIMEOUT_MS = 25_000L)`. `TimeoutCancellationException` is
caught locally; remaining SEARCHING providers are marked `StreamStatus.ERROR`
and fall through to `handleStreamEnd()` → `SearchResultContent.Error(reason)`
with a visible Retry button. §6.AC non-fatal telemetry recorded in both
exception catch blocks (no credentials per §6.H).

**Cleanup — commit `1aa42536`:**
Defensive prior-job cancel added at the start of `observeStreamMultiSearch()`
(future-proofs against double-invocation). `search.go:64` comment corrected
from "4 mirrors = 32 s" to "5 mirrors" (the engine deadline caps regardless).

---

## Validation Tests

### Client regression tests (§6.O clause 1)

`feature/search_result/src/test/kotlin/lava/search/result/SearchResultViewModelCancelTimeoutTest.kt`
— 3 tests using REAL `SearchResultViewModel` + REAL `LavaTrackerSdk` + REAL
`DefaultTrackerRegistry`. Only the outermost network boundary is faked via
`CompletableDeferred`-based clients (dispatcher-agnostic indefinite suspension,
no mocking of the SUT per Second Law).

| Test | Primary assertion (user-visible) |
|---|---|
| `back_press_while_streaming_cancels_inflight_search_and_emits_Back` | `assertFalse(slow.isStillCollecting)` + `assertTrue(receivedBack)` |
| `slow_provider_exceeding_timeout_surfaces_Error_with_Retry_affordance` | `assertTrue(content is SearchResultContent.Error)` |
| `back_press_after_completed_search_preserves_Content_and_emits_Back` | `assertTrue(contentAfterBack is SearchResultContent.Content)` + `assertTrue(receivedBack)` |

**Falsifiability rehearsed (§6.N / Seventh Law clause 1):**
- Remove `activeSearchJob?.cancel()` → Test 1: `isStillCollecting` stays `true` → FAIL
- Remove `withTimeout(SEARCH_TIMEOUT_MS)` → Test 2: times out waiting for `Error` state → FAIL
- Reverted: yes. `:feature:search_result:testDebugUnitTest` BUILD SUCCESSFUL (26/0).

### Engine regression tests (§6.O clause 1)

`lava-api-go/internal/provider/curated/yts/search_total_deadline_test.go`:
- `TestSearch_ExceedsDeadline_ReturnsContextDeadlineExceeded` — slow mock server +
  18 s ctx deadline confirms engine surfaces an error rather than hanging.
- `TestSearch_WithinDeadline_ReturnsResults` — confirms normal path unaffected.
- `TestReproduction_StaleMirrorListFails` — `NewClientWithMirrors(["yts.mx"]).Search`
  → provider error (dead-domain reproduction); fresh 5-mirror list → 132 real
  results in 911 ms (first "Batman Azteca…" title).

**Falsifiability rehearsed (commit `0e81730b` Bluff-Audit block):**
- Mutation: revert `DefaultBaseURLs` to `yts.mx`-only list.
- Observed: `TestReproduction_StaleMirrorListFails` → `yts.mx: provider: unknown error`.
- Reverted: yes. `go test ./internal/provider/curated/yts/...` 10/1-skip GREEN.

**Suite totals at fix commits:** Android 863/0, lava-api-go 47 pkg / 0 failures.

---

## Challenge Test (§6.O clause 2)

A Compose UI end-to-end Challenge asserting that (a) the Streaming spinner
disappears within < 1 s of Back being tapped (not 30 s), and (b) a slow-provider
search surfaces the Error+Retry content within 25 s, is owed per §6.AE clause 1.

The parallel-authored on-device search-timeout Challenge is the §6.O clause-2
e2e gate. Once it lands, this log should be updated with its class name and
the §6.I matrix attestation path.

Current status: **Challenge OWED** — tracked as `C-SEARCH-CANCEL` in
`docs/workable_items.db`. Until the Challenge exists, the client + engine
unit-level test suite is the primary regression gate; this is a documented
coverage gap per §6.AE-debt.

---

## §6.O Clause 5 — Crashlytics Console Close-Mark

Issue `9d4ad2f4d1a8b8697b1506402e045b81` MUST NOT be marked closed in the
Firebase Console until the operator has verified the fix on a real device
(§6.O.5): search with YTS selected, observe results arrive in < 25 s, confirm
Back is responsive mid-search. The Crashlytics note added by the automated
tooling records the root cause and fix version; the operator closes the issue
interactively after on-device verification.

---

## §6.Z Pre-Distribute Evidence Requirement

Per §6.Z clause 1, before distributing builds that include this fix
(Android 1.3.11-1073 / api-app 0.2.11-19), the test-evidence file MUST exist at:

`.lava-ci-evidence/distribute-changelog/debug/1.3.11-1073-test-evidence.md`

It must contain: executed Challenge class names (at minimum `Challenge00CrashSurvivalTest`
+ `C-SEARCH-CANCEL` once authored), AVD/device model, Android API level, commit
SHA `3c1fa159` (the version-bump head), timestamp within 24 h of distribute
invocation, and verbatim `BUILD SUCCESSFUL` from `connectedDebugAndroidTest`.

---

*Closure log authored 2026-06-24 per §6.O clause 3 schema.
Companion §6.T.4 entry: `docs/issues/fixed/BUGFIXES.md` § BUG-2026-06-24-A.*
