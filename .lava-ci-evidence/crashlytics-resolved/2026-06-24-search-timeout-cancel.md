# §6.O Crashlytics Issue Closure — Search Timeout / Cancel-on-Back

**Date:** 2026-06-24
**Crashlytics issue ID:** `9d4ad2f4…` (lava.search.result — streaming hang / cannot go back)
**Severity:** Non-fatal UX hang (user stuck on Streaming spinner for up to 30 s)
**Fix commit SHA:** `7d5ffa5e6426a4c804a16fdd7d0eff94a4910cd4` *(pre-commit; final SHA updates after push)*

---

## Stack Trace Summary

```
Issue: user cannot navigate back while search is in-flight
Surface: SearchResultScreen — Streaming state with activeProviders spinner
Affected path: observeStreamMultiSearch → sdk.streamMultiSearch(...).collect {}
              ↑ Orbit intent {} blocks; onBackClick() posts Back SE but never
                cancels the blocking collect coroutine
```

---

## Root Cause Analysis

**Bug 1 — No cancellation on back-press.**
`observeStreamMultiSearch()` is launched as an Orbit `intent {}` coroutine on the
container's scope. Each `intent {}` is an independent coroutine — back-press dispatches
a *new* `intent {}` (`onBackClick`) that only calls `postSideEffect(Back)`. The
streaming `collect {}` block in the first intent continues running until the flow
naturally completes. On a slow or unresponsive provider (e.g. yts) the OkHttp
`readTimeout` (30 s — `NetworkModule`) is the only termination mechanism. Result:
user taps Back, screen navigates, but the coroutine is alive for up to 30 more
seconds, and if they return to the search screen they see the Streaming spinner
still active.

**Bug 2 — No client-side timeout.**
`observeStreamMultiSearch()` had no `withTimeout` guard. A provider that is slow
but doesn't exceed OkHttp's `readTimeout` (e.g. responds with headers but sends
data very slowly) causes `SearchResultContent.Streaming` to persist for up to 30 s
with no Retry affordance. The `handleStreamEnd()` logic (which produces
`Error(reason)`) is only called after `collect {}` returns — which requires either
the flow ending or an exception being thrown.

---

## Affected Files

| File | Change |
|---|---|
| `feature/search_result/src/main/kotlin/lava/search/result/SearchResultViewModel.kt` | Added `@Volatile activeSearchJob`, job capture via `currentCoroutineContext()[Job]`, `withTimeout(SEARCH_TIMEOUT_MS)` wrapper, `TimeoutCancellationException` + generic `Exception` catch blocks, `finally { activeSearchJob = null }`, cancellation in `onBackClick()` |

---

## Fix Description

**Cancel-on-back fix (Bug 1):**
Added `@Volatile private var activeSearchJob: Job? = null` to
`SearchResultViewModel`. Inside `observeStreamMultiSearch()`, the current
coroutine's `Job` is captured via `currentCoroutineContext()[Job]` and stored.
`onBackClick()` now calls `activeSearchJob?.cancel()` before posting the `Back`
side effect, immediately cancelling the in-flight `collect {}` block.

**Client-side timeout fix (Bug 2):**
Wrapped `sdk.streamMultiSearch(...).collect {}` in `withTimeout(SEARCH_TIMEOUT_MS)`
where `SEARCH_TIMEOUT_MS = 25_000L` (25 s, below OkHttp's 30 s `readTimeout`).
`TimeoutCancellationException` is caught locally (not re-thrown), marks all
still-SEARCHING providers as `StreamStatus.ERROR`, and falls through to
`handleStreamEnd()` which routes to `SearchResultContent.Error(reason)` — giving
the user a visible Retry button before OkHttp's own timeout fires.

A generic `Exception` catch (with `rethrowIfCancellation()` guard) handles
connectivity-lost and other whole-stream failures similarly.

§6.AC telemetry: both catch blocks record to `AnalyticsTracker` with the mandatory
`FEATURE`, `OPERATION`, `SCREEN`, `ERROR_CLASS` attributes (no credentials per §6.H).

---

## Validation Test

`feature/search_result/src/test/kotlin/lava/search/result/SearchResultViewModelCancelTimeoutTest.kt`

| Test | Assertion |
|---|---|
| `back_press_while_streaming_cancels_inflight_search_and_emits_Back` | `assertFalse(slow.isStillCollecting)` + `assertTrue(receivedBack)` |
| `slow_provider_exceeding_timeout_surfaces_Error_with_Retry_affordance` | `assertTrue(content is SearchResultContent.Error)` |
| `back_press_after_completed_search_preserves_Content_and_emits_Back` | `assertTrue(contentAfterBack is SearchResultContent.Content)` + `assertTrue(receivedBack)` |

All three tests use the REAL `SearchResultViewModel` + REAL `LavaTrackerSdk` +
REAL `DefaultTrackerRegistry`. Only the outermost network boundary is faked
(`SlowForeverClient` / `SlowClient` using `CompletableDeferred` — dispatcher-agnostic
suspension).

**Falsifiability confirmed (§6.N / Seventh Law clause 1):**
- Test 1: removing `activeSearchJob?.cancel()` → `isStillCollecting` stays `true` → FAIL
- Test 2: removing `withTimeout(SEARCH_TIMEOUT_MS)` → test times out waiting for `Error` state → FAIL

---

## Challenge Test (§6.O clause 2)

Challenge Test C-OWED: A Compose UI end-to-end Challenge asserting that the
Streaming spinner disappears after Back is tapped (within < 1 s, not 30 s) is owed
as a follow-up per §6.AE clause 1. Tracked as **LVA-SEARCH-CANCEL-CHALLENGE** in
`docs/workable_items.db`. Until the Challenge exists, the unit-level test suite is
the primary regression gate; this is documented as a known coverage gap.

---

## §6.Z Distribute Evidence

Pre-distribute Challenge test execution is required before the next Firebase
distribute that ships this fix. Evidence file path (to be created at distribute time):
`.lava-ci-evidence/distribute-changelog/debug/<versionName>-<versionCode>-test-evidence.md`

---

*Generated by operator on 2026-06-24. Closure log complies with §6.O clause 3 schema.*
