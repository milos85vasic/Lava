# Crashlytics issue 3937b7f0 — SSE "Unable to resolve host lava-api.local" telemetry severity

**Issue ID:** `3937b7f0…`
**Title / Subtitle:** SSE error "Unable to resolve host lava-api.local" (UnknownHostException)
**Type:** NON_FATAL
**Version:** 1.3.0
**Source:** `SearchResultViewModel.applySseError` (via `SseClient.connect`)
**State at this entry:** OPEN (operator marks closed)

## Root cause

The mDNS `lava-api.local` host fails to resolve when the lava-api-go engine app
is not running OR the device has left the LAN. `SseClient.connect` catches the
`UnknownHostException` at `call.execute()` and emits
`SseEvent.Error("Connection failed: Unable to resolve host ...")`. The SSE
consumer routed every such error through `applySseError`, which recorded it as
a `recordNonFatal` — surfacing an EXPECTED connectivity condition in the crash
feed (the same telemetry-pollution class as the §6.AC cancellation noise). It
is not a backend defect; it is "the API is not reachable on this network right
now".

## Fix (this cycle — telemetry-severity refinement, user-visible state UNCHANGED)

`SearchResultViewModel.applySseError` now classifies the SSE error reason:

- **Connectivity-class** (host-resolve failure / connection failed / refused /
  timeout — see the new `String.isConnectivityFailure()` helper) →
  `analytics.recordWarning("sse_endpoint_unreachable", ...)` — a lower-severity,
  still-operator-visible signal, NOT a crash-feed non-fatal.
- **Genuine error** (HTTP 5xx, parser failure, stream read error) →
  `analytics.recordNonFatal(...)` as before.

The user still sees `SearchResultContent.Error(reason)` + the
`ShowFallbackDismissedError` toast with a Retry button — the graceful,
actionable UX is unchanged. Only the telemetry severity changes.

## Validation test

`feature/search_result/src/test/kotlin/lava/search/result/SearchResultSseConnectivityTelemetryTest.kt`
— real-stack: real `SearchResultViewModel` + a recording `AnalyticsTracker`
sink (outermost boundary). Two tests:
- `host-resolve failure records a WARNING not a non-fatal` — asserts the
  "Unable to resolve host" reason yields exactly 1 `recordWarning`
  (`sse_endpoint_unreachable`) and ZERO `recordNonFatal`.
- `a genuine backend error still records a non-fatal` — asserts an "HTTP 500"
  reason yields exactly 1 `recordNonFatal` carrying the reason and ZERO
  warnings (the filter does not over-filter).

## Falsifiability rehearsal (Bluff-Audit)

- Mutation: changed `if (reason.isConnectivityFailure())` → `if (false)` in
  `applySseError` (the pre-fix always-non-fatal behaviour).
- Observed: `host-resolve failure records a WARNING not a non-fatal` FAILED
  (the connectivity failure was wrongly recorded as a non-fatal).
- Reverted: yes — `:feature:search_result:testDebugUnitTest --tests
  "*SearchResultSseConnectivityTelemetryTest"` BUILD SUCCESSFUL after revert.
