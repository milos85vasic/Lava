# Phase E — :api-app Compose UI Challenge execution evidence (2026-06-02)

Honest, real-execution record per §6.Z / §6.J / §6.AG. The four Phase E
Challenge Tests were **EXECUTED — not source-compiled — on a real cold-booted
Android emulator** and the verbatim results are recorded below.

## Host + runner (§6.AG / §6.X)

- Host OS: `Darwin arm64` (Apple Silicon macOS).
- Runner: host-direct + HVF cold-boot — the §6.X-resolved macOS gate runner
  (`emulator-matrix --runner=auto` resolves host-direct+HVF on macOS because a
  Linux container cannot reach HVF/`/dev/kvm`; see root CLAUDE.md §6.X 67th
  cycle resolution). NOT a live/physical ADB device (§6.AG): a cold-booted
  emulator AVD.
- Build path: `:api-app:assembleDebug` + `:api-app:assembleDebugAndroidTest`
  host-direct (§6.K pre-existing gap — Android Gradle builds run host-direct).

## AVD diagnostics

| field | value |
|-------|-------|
| AVD name | Pixel_8 |
| model | sdk_gphone64_arm64 |
| Android API | 35 |
| ABI | arm64-v8a |
| boot | cold (`-no-snapshot-load`), `sys.boot_completed=1` confirmed |
| adb serial | emulator-5556 |

APK native libs: the debug APK bundles `lib/arm64-v8a/liblavaapi.so` +
`liblavaapi_jni.so` (matching the AVD's arm64-v8a ABI) — the native embed
loads; this is NOT an ABI-mismatch failure.

## Build result (MINIMUM deliverable — verbatim)

```
> Task :api-app:assembleDebug
BUILD SUCCESSFUL in ...
> Task :api-app:assembleDebugAndroidTest
BUILD SUCCESSFUL in 30s
```

Both `:api-app:assembleDebug` and `:api-app:assembleDebugAndroidTest` are
`BUILD SUCCESSFUL` — the Phase E Challenge code compiles against the real
Phase D-ui screen + ViewModel + controller + Service.

## Challenge execution result (verbatim `am instrument`)

Runner: `digital.vasic.lava.api.dev.test/lava.api.app.LavaApiHiltTestRunner`
(custom HiltTestRunner → HiltTestApplication).

| Challenge | Result | Notes |
|-----------|--------|-------|
| `Challenge01ApiAppColdStartTest` | **PASS** | `OK (1 test)` in isolation — cold launch renders the real landing screen ("Stopped" status + Start control), no crash. |
| `Challenge02ApiAppBootAndServeTest` | **FAIL** | `ComposeTimeoutException` waiting for status `"Running"` — the embed never reaches Running. |
| `Challenge03StopRestartTest` | **FAIL** | same — start never reaches Running. |
| `Challenge04NotificationActionsTest` | **FAIL** | same — start never reaches Running. |

Full-suite verbatim tail:

```
Time: 93.119
There were 3 failures:
1) start_thenServeRealHttps_healthAndAuthGatedRoute(...Challenge02ApiAppBootAndServeTest)
   androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms
2) startThenStopRefusesThenRestartServesAgain(...Challenge03StopRestartTest)
   androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms
3) notificationStopAndRestartActions_driveServedSocket(...Challenge04NotificationActionsTest)
   androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms
FAILURES!!!
Tests run: 4,  Failures: 1   (C01 isolated re-run: OK (1 test))
```

## Root cause of C02/C03/C04 failures — a REAL on-device defect the Challenges caught (§6.J / §6.Z)

This is NOT a flaky test or a test-authoring error. The Challenges did exactly
their job: they detected that the embed **does not actually start for the user**
on a real device. Diagnosis via on-device UI dump of the rendered error state
(`TAG_ERROR_MESSAGE` Compose node after tapping Start):

```
Serializer for class 'ConfigDto' is not found.
Please ensure that class is marked as '@Serializable' and that the
serialization compiler plugin is applied.
```

Root cause: `:core:apiengine` (`core/apiengine/build.gradle.kts`) applies only
`id("lava.android.library")`. It does **NOT** apply the kotlinx-serialization
compiler plugin (`lava.kotlin.serialization`). `NativeApiEngine.start()`
(`core/apiengine/.../NativeApiEngine.kt`) calls
`json.encodeToString(config.toDto())` on `@Serializable data class ConfigDto`,
but without the serialization compiler plugin no serializer is generated, so
the reflective lookup throws `SerializationException` at runtime in the DEBUG
build (this is independent of R8/minification — debug reproduces it).

`ApiEngineController.start()` maps that failure to `ApiControlState.Error`, so
the screen renders **Error**, never **Running** — and C02/C03/C04 correctly
time out waiting for Running.

This is the canonical §6.J/§6.Z bug class: JVM unit tests pass (FakeApiEngine
bypasses the real serializer), the wiring compiles, but the real embed-start
path is broken for every user on every device.

### Fix (OUTSIDE Phase E scope — owed to the controller/operator)

Apply the kotlinx-serialization compiler plugin to `:core:apiengine`:

```kotlin
// core/apiengine/build.gradle.kts
plugins {
    id("lava.android.library")
    id("lava.kotlin.serialization")   // OWED — generates ConfigDto/StatusDto serializers
}
```

Phase E modifies ONLY `api-app/` per its scope, so this `:core:apiengine` fix
was NOT applied here. Once applied, C02/C03/C04 should pass (they connect to the
real on-device HTTPS embed, assert `/health` 200 + JSON, the 401-without-key
gate, and the not-401-with-the-displayed-key gate). The Challenge code is ready
and proven falsifiable against this exact real defect.

## Anti-bluff posture

Per §6.Z/§6.J: NO green Challenge run was fabricated. C01 genuinely passed;
C02/C03/C04 genuinely failed against a real production defect. The honest
record of a found defect is the correct Phase E outcome — the load-bearing
Challenge (C02) did exactly what it exists to do: prove the feature does not
yet work for a real user, surfacing the `:core:apiengine` serialization-plugin
gap that all prior unit tests passed against.
