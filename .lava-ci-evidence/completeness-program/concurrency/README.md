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
