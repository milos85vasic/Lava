# Crashlytics issue 7df61fdba64f9928b067624d6db395ca — closure log (catch-site sweep)

**Issue ID:** `7df61fdba64f9928b067624d6db395ca`
**Title:** `kotlinx.coroutines.JobCancellationException`
**Subtitle:** "StandaloneCoroutine was cancelled"
**Type:** NON_FATAL
**First seen:** 1.2.21 (1041)
**Last seen:** 1.2.21 (1041)
**Events at snapshot:** 8 / 1 user
**State:** OPEN (not recurring on 1.3.x per the 2026-06-13 fleet snapshot)
**Queued for:** 1.3.7 (next release cycle — version files NOT touched here)

## Relationship to prior closures

This issue already has two layers of defense in code:

1. **Sink-level filter** — `FirebaseAnalyticsTracker.recordNonFatal` drops
   throwables that ARE or WRAP a `CancellationException` (closure
   `2026-05-14-jobcancellation-nonfatal-noise-filter.md`).
2. **Per-ViewModel rethrow** — `lava.common.analytics.rethrowIfCancellation()`
   added as the first line of the telemetry-recording `catch` blocks in 8
   feature ViewModels + `LavaApplication` (closure
   `2026-06-13-jobcancellation-catch-site-rethrow.md`).

This third closure plugs the **one remaining catch-site the per-ViewModel sweep
did not reach**: a non-ViewModel production path.

## Root cause (this catch-site)

`DownloadServiceImpl.downloadHttpFile` (`core/downloads`) is a `suspend fun`
whose write path was wrapped in a broad `catch (t: Throwable) { ...
analytics.recordNonFatal(t, ...) ... }`. When the download is abandoned
mid-write (the calling scope is cancelled — the user left the screen, or the
ViewModel was cleared), `kotlinx.coroutines.CancellationException` is thrown from
inside the `try`. The broad catch swallowed it AND recorded it as a non-fatal —
exactly the false-telemetry / dashboard-noise symptom of issue `7df61fdb`, and a
broken-cooperative-cancellation bug (the catch kept running on a cancelled
coroutine, returning `null` instead of letting the cancellation propagate).

The audit (grep of every `catch` block calling `recordNonFatal` / `recordWarning`
across `core/ feature/ app/` main sources) confirmed this was the **only**
remaining offending site — all 8 ViewModels + `LavaApplication` already rethrow.

## Fix (queued for 1.3.7)

`core/downloads/.../DownloadServiceImpl.kt` — `t.rethrowIfCancellation()` is now
the FIRST statement of the `downloadHttpFile` catch, before `recordNonFatal`.
The existing shared helper `lava.common.analytics.rethrowIfCancellation` is
reused (no new helper). Cancellation now propagates cooperatively and is never
recorded.

## Validation test

`core/downloads/src/test/.../DownloadServiceCancellationTest.kt` (NEW):
`cancellation during http write is rethrown and never recorded as non-fatal`.

Wires the REAL `DownloadServiceImpl` + a recording `AnalyticsTracker` fake;
fakes only the outermost Android boundary (`Context` + the `Environment` static
the pre-Q write path resolves the Downloads dir through) to throw a
`CancellationException` mid-write. Primary assertions on the user-visible-
equivalent outcome:
- the cancellation PROPAGATES out of `downloadHttpFile` (cooperative
  cancellation honoured), and
- the recorded-non-fatal COUNT is `0` (the Crashlytics dashboard the operator
  watches stays clean).

`./gradlew :core:downloads:testDebugUnitTest --max-workers=2 --tests
"lava.downloads.impl.DownloadServiceCancellationTest"` → BUILD SUCCESSFUL.

## Falsifiability rehearsal (Bluff-Audit)

- **Mutation:** deleted the `t.rethrowIfCancellation()` line from
  `DownloadServiceImpl.downloadHttpFile` (restored the pre-fix broad catch).
- **Observed-Failure:** `java.lang.AssertionError: cancellation must propagate,
  not be swallowed into a null return` (`DownloadServiceCancellationTest.kt:87`)
  — the mutated catch swallowed the `CancellationException` and returned `null`.
- **Reverted:** yes — re-run is BUILD SUCCESSFUL.

## Closure protocol

Operator marks the issue closed in the Firebase Console after the 1.3.7 build
ships and no further cancellation-class events appear. This catch-site sweep is
the last code-path gap; the sink-level filter + per-ViewModel rethrow + this
download-path rethrow together cover every known channel through which a
`CancellationException` could reach `recordNonFatal`.
