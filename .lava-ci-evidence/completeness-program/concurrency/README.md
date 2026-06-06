# Phase 3 — Concurrency / responsiveness evidence

Branch: `completeness-program-2026-06-04`

## Task 3.1 — Go race detector (ground truth)

- Command: `GOMAXPROCS=2 nice -n 19 go test -race ./...` (§6.T.2 resource cap)
- Log: `go-race-*.log`
- Result: **0 data races, 0 test failures across all packages** (exit 0).
  Every package reported `ok` (or `[no test files]`); `tests/load`,
  `tests/parity`, `tests/scripts`, `tests/e2e`, `internal/*` all clean.
- Conclusion: the Go side of the no-hazard posture (Master Plan §E) is now
  **proven**, not asserted — the race detector instrumented and executed
  the concurrent paths the suite covers and found nothing.

## Detekt correctness backlog (carried from Phase 2) — FIXED this increment

1. `core/tracker/rutracker/.../domain/Utils.kt:102` — `ImplicitDefaultLocale`.
   `String.format("%.1f …")` used the JVM default locale → a 1.5 MB torrent
   rendered as "1,5 MB" on any comma-decimal locale (de/ru/fr). Fixed to
   `String.format(Locale.ROOT, …)`. Regression test:
   `FormatSizeLocaleTest` (TDD red→green; forces `Locale.GERMANY`/`ru-RU`).
2. `api-app/.../ui/ApiControlScreen.kt:115` — `SwallowedException`. A failed
   `startActivity` (client uninstalled between check and tap) was a silent
   dead tap. Fixed to surface a snackbar + `Log.w` (§6.AB gating: a failed
   launch MUST give the user feedback; §6.AC: api-app has no AnalyticsTracker
   yet, so user+logcat is the surfacing channel).

Both module detekt baselines regenerated (−1 entry each) so they honestly
reflect the fixed state.

## Task 3.1 — Android hazard sweep findings

### FIXED: DownloadServiceImpl cache data race (core/downloads)
- `DownloadServiceImpl.cache` was a plain `mutableMapOf()` (HashMap) read on
  the caller's coroutine dispatcher (`downloadTorrentFile`) and written from
  the `DownloadManager` `BroadcastReceiver.onReceive`, which Android delivers
  on the MAIN thread. Concurrent downloads → unsynchronized HashMap access
  across two threads (lost writes / ConcurrentModificationException / resize
  spin). Extracted to `DownloadUriCache` backed by `ConcurrentHashMap`
  (Fifth-Law testability refactor — pure JVM, no emulator).
- Reproducing test: `DownloadUriCacheConcurrencyTest` (16 threads × 500 keys,
  interleaved get/put). Falsifiability rehearsed: HashMap → 1 test FAILED;
  ConcurrentHashMap → 3/3 green.

### KNOWN LIMITATION (documented, not fixed this increment)
- `DownloadServiceImpl.registerDownloadCompleteReceiver` self-unregisters the
  `BroadcastReceiver` only on `ACTION_DOWNLOAD_COMPLETE` for the matching id.
  A download that never completes (cancelled / app killed / failure that does
  not emit COMPLETE for this id) leaves the receiver registered AND the
  `suspendCoroutine` continuation un-resumed (receiver + continuation leak).
  Robust fix needs download-failure/timeout handling; tracked for Phase 4/5
  (needs Robolectric/instrumented coverage — the module has no Android test
  harness today). Severity: bounded (one receiver per in-flight download;
  released on normal completion).

### AUDITED CLEAN (symmetric lifecycle, no fix needed)
- `LocalNetworkDiscoveryServiceImpl` (core/data): each `discoverServices`
  listener is collected into a list and every one gets `stopServiceDiscovery`
  in the flow's teardown (`runCatching` guarded). Scoped to the discovery flow.
- `NsdMdnsAdvertiser` (api-app): single nullable `registrationListener`;
  `unregisterService` paired with `registerService`; degraded-advertisement
  path logs instead of crashing.

### DOCUMENTED FINDING (reachable, but not unit-falsifiable — deferred to Phase 4)
- `ApiEngineController.start()` guards re-entry with a read-then-set on a
  `MutableStateFlow` (read `_state.value`, check Running/Starting, then set
  `Starting`). This is a TOCTOU: two concurrent `start()` calls can both
  observe `Stopped` and both bind the embed → the "address already in use"
  double-bind the guard exists to prevent.
- REACHABILITY (confirmed, not assumed): the controller is a Hilt singleton
  driven by TWO collaborators on DIFFERENT dispatchers — `ApiControlViewModel`
  via Orbit intents (Dispatchers.Default) and `ApiEngineService` via
  `serviceScope` (Dispatchers.Main.immediate, ACTION_STOP/RESTART). A UI
  Restart racing the notification Restart action is genuine parallelism.
- WHY NOT FIXED THIS INCREMENT (anti-bluff honesty, §6.J Fifth Law): the
  obvious fix (atomic `compareAndSet` guard) is correct, but the read→set gap
  is nanoseconds while post-barrier thread-scheduling jitter is microseconds,
  so a stress test (400 rounds × 16 concurrent callers) does NOT reliably
  fail against the broken read-then-set guard — i.e. the fix is not
  unit-falsifiable as-is. Shipping the fix with a test that is green against
  BOTH the fixed and broken code would be a bluff test by definition (§6.J),
  and shipping the fix with no falsifiable test violates the Fifth Law.
- PLANNED RESOLUTION (Phase 4): refactor for testability per the Fifth Law —
  add a `@VisibleForTesting` guard-checkpoint seam (a no-op hook invoked
  between the guard read and the CAS) so a test can deterministically park
  caller A in the gap, run caller B fully, release A, and assert the embed
  bound exactly once. With that seam the atomic `compareAndSet` fix becomes
  deterministically falsifiable (read-then-set → double-bind → FAIL). Until
  then the sequential idempotency contract remains covered by the existing
  falsifiable `start is idempotent` test in ApiEngineControllerTest. Severity:
  LOW (requires near-simultaneous UI+notification Restart).

### RESOLVED (same session, Phase-4 testability refactor applied)
The ApiEngineController TOCTOU above is now FIXED. Per the Fifth Law, a
`@VisibleForTesting internal var guardCheckpoint: () -> Unit = {}` seam was
added (no-op in production), invoked between the guard read and the
compare-and-set. The guard is now an atomic `_state.compareAndSet(...)` loop.
`ApiEngineControllerTest.concurrent start parked in the guard gap binds the
embed exactly once` parks caller A in the gap via the seam, runs caller B to
completion, releases A, and asserts the embed bound exactly once
(`FakeApiEngine.startAttempts == 1`) ending Running. Falsifiability (verified):
reverting only the loop/CAS to a read-then-set (seam kept) makes that test
FAIL (1 of 8) — A re-sets Starting and double-binds. With compareAndSet: 8/8
green. The fix is therefore deterministically unit-falsifiable, not a bluff.
