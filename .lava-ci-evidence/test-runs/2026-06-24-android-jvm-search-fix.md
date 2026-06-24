# JVM Unit Test Attestation — Search Cancel/Timeout Fix Validation

| Field            | Value |
|------------------|-------|
| Date             | 2026-06-24 |
| HEAD SHA         | 0e81730b4b138df50970fccfa028a6f4c640c6eb |
| Branch           | master |
| Task             | Validate Android client after search cancel/timeout fix |
| Runner           | JVM (testDebugUnitTest via Gradle) |
| Gradle command   | `nice -n 10 ./gradlew testDebugUnitTest --continue --console=plain --max-workers=3` |
| Build result     | **BUILD SUCCESSFUL in 4m 25s** |
| Actionable tasks | 1261 (33 executed, 1228 up-to-date) |

## Aggregate Results

| Metric        | Count |
|---------------|-------|
| Test suites   | 163   |
| Total tests   | 863   |
| Failures      | 0     |
| Errors        | 0     |
| Skipped       | 0     |

## Verdict: ALL GREEN — 863/863 PASS, 0 failures, 0 errors

## Mandatory Target Classes (search_result module)

| Class | Tests | Failures | Errors | Status |
|-------|-------|----------|--------|--------|
| `lava.search.result.SearchResultViewModelCancelTimeoutTest` | 3 | 0 | 0 | GREEN |
| `lava.search.result.SearchResultViewModelStreamingTest` | 3 | 0 | 0 | GREEN |
| `lava.search.result.SearchResultViewModelRetryTest` | 1 | 0 | 0 | GREEN |
| `lava.search.result.ApplyMultiSearchEventTest` | 6 | 0 | 0 | GREEN |
| `lava.search.result.SearchResultViewModelFallbackTest` | 3 | 0 | 0 | GREEN |
| `lava.search.result.SearchResultNavigationProviderIdsRoundtripTest` | 3 | 0 | 0 | GREEN |
| `lava.search.result.categories.CategorySelectionViewModelTest` | 7 | 0 | 0 | GREEN |

All 3 cancel/timeout cases in `SearchResultViewModelCancelTimeoutTest` passed.

## Full Suite Listing (all 163 suites — all GREEN)

All 163 suites reported 0 failures and 0 errors. Representative selection:

- `lava.api.app.*` — 9 suites, 30 tests, 0 fail
- `lava.analytics.firebase.*` — 3 suites, 14 tests, 0 fail
- `lava.auth.impl.*` — 2 suites, 14 tests, 0 fail
- `lava.credentials.*` — 7 suites, 41 tests, 0 fail
- `lava.data.*` — 11 suites, 78 tests, 0 fail
- `lava.domain.*` — 20 suites, 79 tests, 0 fail
- `lava.tracker.client.*` — 22 suites, 118 tests, 0 fail
- `lava.network.*` — 10 suites, 55 tests, 0 fail
- `lava.onboarding.*` — 7 suites, 57 tests, 0 fail
- `lava.search.*` — 7 suites, 35 tests, 0 fail
- `lava.login.*` — 4 suites, 26 tests, 0 fail
- `lava.testing.*` — 7 suites, 31 tests, 0 fail

## Anti-Bluff Attestation

- Results read directly from JUnit XML under each module's `build/test-results/testDebugUnitTest/`.
- BUILD SUCCESSFUL line confirmed verbatim: `BUILD SUCCESSFUL in 4m 25s`
- No code was mutated, committed, or distributed.
- No test was skipped or suppressed.

## §6.Z Compliance Note

This attestation covers the JVM unit test layer only. Compose UI Challenge Tests
(connectedAndroidTest) require a running emulator and are a separate gate per
§6.Z. This evidence file covers the regression-free JVM baseline at HEAD
`0e81730b` post-search-cancel/timeout fix.
