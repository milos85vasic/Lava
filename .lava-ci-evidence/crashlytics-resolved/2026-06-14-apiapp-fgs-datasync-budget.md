# Crashlytics closure — api-app foreground-service dataSync 6h-budget crash

**Issues:** `9ba8502ee0ba0d1fdd03987650b8acf8` (FATAL, ForegroundServiceStartNotAllowedException)
+ `b9baeaede585fc3bc9b515c27cde532c` (FATAL, ForegroundServiceDidNotStopInTimeException).
**App:** api-app 0.2.6-10 (release), reported under the client Firebase app id (see the
separate telemetry-misattribution item — Defect B, still operator-gated).
**Scope (real Firebase data):** 1 device (Galaxy S23 Ultra / Android 16), ~6 events,
first/last seen 0.2.6.

## Root cause (CONFIRMED)
`ApiEngineService` declared `foregroundServiceType="dataSync"`. Android 14+ caps `dataSync`
(and `mediaProcessing`) foreground services at ~6h cumulative runtime per 24h; the
long-lived embedded LAN API server exhausts the budget after ~6h uptime, after which
`startForeground(...)` at `ApiEngineService.kt:113` throws
`ForegroundServiceStartNotAllowedException` (and the OS later throws
`ForegroundServiceDidNotStopInTimeException`). NOT introduced by the 1.3.6/1.3.7 changes —
the FGS code predates them (Phase D-infra).

## Fix (operator-approved 2026-06-14: specialUse)
`foregroundServiceType` `dataSync` → `specialUse` (the only non-time-budgeted type with a
free-form Play justification; the canonical "long-lived LAN API server" fit) + the
`FOREGROUND_SERVICE_SPECIAL_USE` permission + the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
manifest property carrying the Play-review justification. Defensive: `startForeground` is
wrapped in `try/catch (ForegroundServiceStartNotAllowedException)` → graceful `stopSelf`
(no FATAL), and an `onTimeout(startId, fgsType)` override releases locks + stops. So even
if any future budget applies, the service degrades gracefully instead of crashing.

**Fix commit:** `ed03cac2`. Design + option analysis: `docs/issues/2026-06-14-apiapp-fgs-datasync-budget-fix-design.md`.

## Verification
- `:api-app:compileDebugKotlin` BUILD SUCCESSFUL.
- Device gate: api-app 0.2.8-12 cold-start canary on the Genymotion VM — the service
  starts under `specialUse` with no FGS crash (the immediate crash site is removed). The
  >6h-budget removal is BY DESIGN (specialUse has no cumulative cap) — not soak-tested in
  the gate (a >6h soak is out of CI scope); the design doc records this honestly.
- §6.AE Challenge plan (C-FGS-01..04) recorded in the design doc for a future long-run gate.

## Console close-mark
Owed to the operator after 0.2.8-12 is distributed + observed. (Note: api-app Crashlytics
currently misattributes to the client Firebase app — the google-services.json fix, Defect
B, is a separate operator-gated item; once both ship, the dashboard attribution + close-mark
align.)
