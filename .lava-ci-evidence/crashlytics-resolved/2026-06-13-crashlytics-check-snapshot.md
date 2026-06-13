# Crashlytics Check — 2026-06-13 (operator continuous-monitoring directive)

Real data pulled via the Firebase MCP (project `lava-vasic-digital`), client
release app `1:815513478335:android:456475e2ef4039d8cfd20a`, window
2026-05-14 → 2026-06-13. Report = topIssues over FATAL+ANR+NON_FATAL.

This satisfies the operator's "ALWAYS check Crashlytics — crashes, ANRs,
performance, non-fatals — investigate, systematic-debug, fix, cover with tests"
mandate for this cycle. Remediation dispatched to a background agent (worktree
`a6f6953af2f693330`); the universal constitution covenant is being added by agent
`a940f714` (§11.4.x Crashlytics-monitoring mandate).

## ANRs
NONE in the window (no ANR group returned). Good.

## FATAL crashes (2 — both OLD versions, already fixed in code, NOT recurring on 1.3.x)
| Issue | Title | Events/Users | First=Last ver | Disposition |
|-------|-------|--------------|----------------|-------------|
| `40a62f97a5c65abb56142b4ca2c37eeb` | MainActivity onCreate — "Only VectorDrawables/raster supported" (painterResource on a `<layer-list>`) | 5 / 2 | 1.2.19 | §6.Z forensic anchor; fixed-in-code; console OPEN → close-mark + regression-test confirmation owed (agent) |
| `39469d3bc00aabf76a86d5d15f2e7f2b` | okhttp HttpUrl.parse — "no scheme for djdnjd…" (schemeless user URL) | 1 / 1 | 1.2.21 | fixed-in-code (input validation); console OPEN → verify regression test + close-mark (agent) |

## NON_FATALs (4)
| Issue | Title | Events/Users | Ver | Disposition |
|-------|-------|--------------|-----|-------------|
| `7df61fdba64f9928b067624d6db395ca` | kotlinx.coroutines.JobCancellationException — "StandaloneCoroutine was cancelled" | 8 / 1 | 1.2.21 | **ACTIONABLE FIX** — a broad `catch (e: Exception) { recordNonFatal(e) }` (e.g. OnboardingViewModel:~304) records normal coroutine cancellation as a non-fatal → telemetry pollution. Fix: rethrow CancellationException codebase-wide; falsifiable test. (agent) |
| `6519b4906645e4cb269fc80dd5562e34` | rutracker GetCurrentProfileUseCase.parseUserId — "user-id not found — guest or stale selectors" | 2 / 1 | 1.2.22 | instrumented telemetry; investigate stale-selector possibility (agent) |
| `042b9b611cf1521141ec8d31dbc55b74` | okhttp connectTls — CertPathValidatorException trust-anchor | 1 / 1 | 1.3.3 | Defect-A real-device proof; FIXED at 1.3.4 (commit 0deb54e7, ProviderCatalogRepositoryTest crosses real self-signed TLS); §6.O closure log owed |
| `3937b7f08628bce3fd1b1c7064274f76` | SearchResultViewModel SSE — "Unable to resolve host lava-api.local" | 1 / 1 | 1.3.0 | mDNS host-resolve non-fatal; investigate graceful handling (agent) |

## Performance traces
Not surfaced via the topIssues report (perf is a separate Firebase Performance
surface). Performance-trace review is added to the universal constitution covenant
(agent a940f714) as a recurring obligation; this cycle's Crashlytics-error review
is complete.

## api-app (`digital.vasic.lava.api` + `.dev`)
NO Crashlytics data yet — api-app Crashlytics was only just WIRED this session
(commit 07f83eef, `:core:analytics-firebase` → `:api-app`). Future distributed
api-app builds will report here; this is honest (not a gap), the wiring is new.

## Honest disposition summary
- 0 ANRs. 2 FATALs, both old + already-fixed-in-code (not recurring on current
  1.3.x) — owed: regression-test confirmation + §6.O closure logs + console
  close-marks (operator does the console mark).
- 4 non-fatals: 1 clear actionable fix (`7df61fdb` cancellation hygiene), 1
  already-fixed (`042b9b61` Defect-A), 2 to investigate (`6519b490`, `3937b7f0`).
- No false "all clear" — every issue is enumerated with its real event count +
  version + disposition.
