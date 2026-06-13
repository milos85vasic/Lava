# Crashlytics issue 7df61fdba64f9928b067624d6db395ca — catch-site rethrow (defense-in-depth follow-up)

**Issue ID:** `7df61fdba64f9928b067624d6db395ca`
**Title:** `kotlinx.coroutines.JobCancellationException`
**Subtitle:** "StandaloneCoroutine was cancelled"
**Type:** NON_FATAL
**First/Last seen:** 1.2.21 (1041)
**Events / users:** 8 / 1
**State at this entry:** OPEN (operator marks closed in Console)

This entry SUPPLEMENTS the prior closure log
`2026-05-14-jobcancellation-nonfatal-noise-filter.md`. That earlier fix
filtered cancellations at the telemetry SINK
(`FirebaseAnalyticsTracker.recordNonFatal`). This cycle adds the correct,
more robust layer: rethrowing the cancellation at the CATCH SITES.

## Why the sink filter alone was insufficient

The sink filter stopped the cancellation from reaching the Crashlytics
non-fatal feed (the visible symptom). But the underlying `catch (e: Exception)`
inside each `viewModelScope.launch { }` body STILL CAUGHT the
`CancellationException` and ran its catch body — typically reducing a spurious
`Failure` UI state on a screen the user had already navigated away from. That
is a swallowed-cancellation bug: it breaks cooperative cancellation even when
no telemetry is recorded.

## Root cause

A broad `catch (e: Exception) { analytics.recordNonFatal(e, ...) }` (and the
equivalent `runCatching { }.onFailure { }` / `catch (t: Throwable)` shapes)
inside structured-concurrency coroutine bodies catches Kotlin's
`CancellationException`, which is thrown on normal scope teardown (user
navigates away / ViewModel cleared). Both the telemetry pollution AND the
swallowed cancellation follow from that.

## Fix (this cycle)

New extension `Throwable.rethrowIfCancellation()` in
`core/common/src/main/kotlin/lava/common/analytics/CancellationRethrow.kt`
re-throws when the throwable IS or WRAPS a `CancellationException` (cause chain
walked to depth 32). Called as the FIRST statement of every catch / onFailure
block that records a non-fatal in production ViewModels, BEFORE any
`recordNonFatal` / `recordWarning` / error-state reduce. The sink filter
remains as defense-in-depth.

### Affected files (catch sites that now rethrow cancellation first)
- `feature/onboarding/.../OnboardingViewModel.kt` (api-probe, connection-test, provider-catalogue fetch, onFailure lambda, apiKeyReader catch)
- `feature/login/.../ProviderLoginViewModel.kt` (load-providers catch + 3 switchTracker onFailure lambdas)
- `feature/menu/.../MenuViewModel.kt` (checkAuth, getCredentials, 3 sign-out catches)
- `feature/search_input/.../SearchInputViewModel.kt` (suggests collect catch)
- `feature/search_result/.../SearchResultViewModel.kt` (endpoint-probe catch, favorite onFailure)
- `feature/topic/.../TopicViewModel.kt` (load-topic onFailure, favorite onFailure)
- `feature/bookmarks/.../BookmarksViewModel.kt` (sync catch)
- `feature/favorites/.../FavoritesViewModel.kt` (sync catch)

## Validation test

`core/common/src/test/kotlin/lava/common/analytics/CancellationRethrowTest.kt`
— 5 tests, real-stack: a real cancelled coroutine running the production
catch-block shape, asserting ZERO non-fatals recorded for the cancellation,
the real exception STILL recorded, wrapped-cancellation rethrown, and the job
ends `isCancelled` (cooperative cancellation restored).

## Falsifiability rehearsal (Bluff-Audit)

- Mutation: made `if (t is CancellationException) throw this` a no-op in
  `rethrowIfCancellation`.
- Observed: 3 tests RED — `cancellation is NOT recorded as a non-fatal`
  (`AssertionError` at CancellationRethrowTest.kt:75), `wrapped
  CancellationException is rethrown not recorded`, and `cancellation thrown
  inside a launched job propagates to cancel the job`.
- Reverted: yes — `:core:common:test` BUILD SUCCESSFUL after revert.
