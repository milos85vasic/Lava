# api-app 0.2.8-12 — FGS specialUse device gate VERDICT: PASS

Genymotion VM (Pixel 9 / API 35 / arm64-v8a, serial 127.0.0.1:6555).
Release APK: releases/api-app/0.2.8/android-release/lava-api-app-0.2.8-12-release.apk
(rebuilt with the Defect-B telemetry fix).

## What was verified (real OS state, not "no crash")
1. App installed + cold-launched under the CORRECT package `digital.vasic.lava.api`
   (Firebase SessionLifecycleService runs under .api → Defect-B telemetry fix live).
2. Foreground service STARTED from the app UI ("Start" button, since the service is
   not exported — adb-shell start is correctly Permission-Denied).
3. The Go engine actually ran: `liblavaapi_jni.so` loaded; mDNS advertised
   `_lava-api._tcp` "Lava API" port 8443 {engine=go, version=2.3.30, ...}.
4. **The FGS reached foreground under the specialUse type — the load-bearing proof:**
   `isForeground=true  startForegroundCount=1  types=0x40000000`
   0x40000000 == 1073741824 == FOREGROUND_SERVICE_TYPE_SPECIAL_USE.
5. NO FGS exception in logcat (ForegroundServiceStartNotAllowed /
   DidNotStopInTime / MissingForegroundServiceType — all absent).
6. Code review: startForeground is called IMMEDIATELY at the top of
   onStartCommand (ApiEngineService.kt:122), before async engine work, wrapped in
   try/catch→stopSelf + onTimeout override (defensive). The 2-arg startForeground
   uses the manifest type → specialUse (confirmed by types=0x40000000).

## Anti-bluff note
A transient `startForegroundCount=0 / "does not have any types"` was observed in
the ~few-second cold-start window between startForegroundService and onStartCommand
executing (JNI engine boot). It resolved to types=0x40000000 once settled. PASS was
NOT declared during the transient window — only on the settled OS-reported type.

Verdict: PASS. The dataSync→specialUse fix (commit ed03cac2) works on device.
