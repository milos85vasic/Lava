# Crashlytics issue 39469d3bc00aabf76a86d5d15f2e7f2b — schemeless-URL regression test (owed follow-up)

**Issue ID:** `39469d3bc00aabf76a86d5d15f2e7f2b`
**Title:** `okhttp3.HttpUrl$Builder.parse$okhttp`
**Subtitle:** "Expected URL scheme 'http' or 'https' but no scheme was found for djdnjd…"
**Type:** FATAL
**Version:** 1.2.21 (1041) release — 1 event, Galaxy S23 Ultra / Android 16
**State at this entry:** OPEN (operator marks closed)

This entry SUPPLEMENTS `2026-05-14-okhttp-url-scheme-djdnjd.md`. That closure
log fixed the crash (added `catch (e: IllegalArgumentException)` in
`ProbeMirrorUseCase`) but EXPLICITLY stated: "Test scaffolding for the
AddMirror input-validation branch is owed in a follow-up commit." This is that
follow-up — the regression test for the load-bearing Layer-2 use-case fix.

## Root cause (recap)

A user typed a schemeless string ("djdnjd") in the AddMirror field. It reached
`ProbeMirrorUseCase.invoke`, where `Request.Builder().url(url)` threw
`IllegalArgumentException`. The prior catch caught only `IOException`, so the
throw escaped to the main looper as a FATAL crash.

## Verification (this cycle)

The fix code path (`catch (e: IllegalArgumentException) → ProbeResult.Unreachable`)
already shipped. This cycle adds the missing regression test:

`core/domain/src/test/kotlin/lava/domain/usecase/ProbeMirrorUseCaseTest.kt`
→ new test `schemeless URL returns Unreachable instead of crashing
(Crashlytics 39469d3b)`. Real-stack: real `ProbeMirrorUseCase` + real
`OkHttpClient` (only the HTTP server boundary is absent because the URL never
parses). Asserts a schemeless `"djdnjd"` yields `ProbeResult.Unreachable`,
never an uncaught throw.

## Falsifiability rehearsal (Bluff-Audit)

- Mutation: changed `catch (e: IllegalArgumentException)` →
  `catch (e: java.util.concurrent.TimeoutException)` so the schemeless throw
  is no longer caught.
- Observed: the new test FAILED with `java.lang.IllegalArgumentException` at
  `ProbeMirrorUseCaseTest.kt:80` (the crash leaked, exactly as in production);
  only that test failed.
- Reverted: yes — `:core:domain:testDebugUnitTest --tests
  "*ProbeMirrorUseCaseTest"` BUILD SUCCESSFUL after revert.
