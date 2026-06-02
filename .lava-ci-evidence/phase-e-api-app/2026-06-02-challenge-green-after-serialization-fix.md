# Phase E — :api-app Challenge re-run after the :core:apiengine serialization fix (2026-06-02)

Honest, real-execution record per §6.Z / §6.J / §6.AG. The serialization defect
the Phase E Challenge run caught (`Serializer for class 'ConfigDto' is not
found`) is **FIXED and PROVEN gone**. Re-running the Challenges on the real
emulator surfaced a **distinct, pre-existing native-linking defect** in
`lava-api-go/` (the JNI bridge records the host build-time absolute path of
`liblavaapi.so` as its `DT_NEEDED`). Per §6.Z/§6.J **no green was fabricated** —
the second defect is reported precisely below, not papered over.

## Host + runner (§6.AG / §6.X)

- Host OS: `Darwin arm64` (Apple Silicon macOS).
- Runner: host-direct + HVF cold-boot — the §6.X-resolved macOS gate runner
  (a Linux container cannot reach HVF/`/dev/kvm`; see root CLAUDE.md §6.X 67th
  cycle resolution + §6.L 67th cycle). NOT a live/physical ADB device (§6.AG):
  a cold-booted emulator AVD.
- Commit SHA built + tested: `26df81e0bb04b04c8112371cd80ca08d884d536e` (working
  tree: the `:core:apiengine` serialization fix applied; see the follow-up
  commit recorded in BUGFIXES.md / git log for the exact landed SHA).
- Timestamp: 2026-06-02 ~14:23 local.

## AVD diagnostics

| field | value |
|-------|-------|
| AVD name | Pixel_8 |
| model | sdk_gphone64_arm64 |
| Android API | 35 |
| ABI | arm64-v8a |
| boot | cold (`-no-snapshot-load`), `sys.boot_completed=1` confirmed |
| adb serial | emulator-5556 |

Install method: `adb install -r -g` for BOTH the app APK and the androidTest APK
(Phase E approach — UTP's split-installer hits `INSTALL_FAILED_INSUFFICIENT_STORAGE`
on the shared AVD). Both installs reported `Success`.

## Build result (verbatim)

```
> Task :api-app:assembleDebug
BUILD SUCCESSFUL
> Task :api-app:assembleDebugAndroidTest
BUILD SUCCESSFUL in 21s
```

## JVM regression test (the §6.T.1 reproduction) — PASS

`./gradlew :core:apiengine:testDebugUnitTest` → `BUILD SUCCESSFUL`.
`ConfigSerializationTest` 4/4 PASS (full `:core:apiengine` suite 13/13).

RED→GREEN reproduction (plugin temporarily removed → re-added), verbatim:

```
RED (plugin absent):
  ConfigSerializationTest > configDto_serializesWithAllKeysTheGoEmbedReads FAILED
      kotlinx.serialization.SerializationException
  ... 4 failed
  kotlinx.serialization.SerializationException: Serializer for class 'ConfigDto' is not found.
  kotlinx.serialization.SerializationException: Serializer for class 'StatusDto' is not found.

GREEN (plugin applied):
  BUILD SUCCESSFUL
```

## Challenge execution result (verbatim `am instrument`)

Runner: `digital.vasic.lava.api.dev.test/lava.api.app.LavaApiHiltTestRunner`.

| Challenge | Result | Notes |
|-----------|--------|-------|
| `Challenge01ApiAppColdStartTest` | **PASS** | cold launch renders the real landing screen ("Stopped" + Start control), no crash. |
| `Challenge02ApiAppBootAndServeTest` | **FAIL** | `ComposeTimeoutException` waiting for `"Running"` — start reaches Error (NEW root cause, NOT serialization). |
| `Challenge03StopRestartTest` | **FAIL** | same NEW root cause. |
| `Challenge04NotificationActionsTest` | **FAIL** | same NEW root cause. |

```
Time: 96.791
There were 3 failures:
1) start_thenServeRealHttps_healthAndAuthGatedRoute(...Challenge02ApiAppBootAndServeTest)
   androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms
2) startThenStopRefusesThenRestartServesAgain(...Challenge03StopRestartTest)
   androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms
3) notificationStopAndRestartActions_driveServedSocket(...Challenge04NotificationActionsTest)
   androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 30000 ms
FAILURES!!!
Tests run: 4,  Failures: 3
```

## The serialization defect is PROVEN FIXED

On-device manual UI drive (launch real app → tap Start → dump rendered state +
logcat): the rendered status moves from "Stopped" to **"Error"** (it did the same
before), BUT the underlying error class is **DIFFERENT**:

- `grep -c SerializationException` over the post-Start logcat: **0** (was the
  Phase E blocker; now gone — the `lava.kotlin.serialization` plugin generates
  the `ConfigDto`/`StatusDto` serializers).
- The on-device error now logged at Start is a **native dlopen failure** (see
  next section). The serialization layer is no longer the failure point.

## NEW defect surfaced (REPORTED PRECISELY — outside core/apiengine + api-app)

Verbatim on-device logcat after tapping Start:

```
nativeloader: Load .../base.apk!/lib/arm64-v8a/liblavaapi_jni.so ...:
  dlopen failed: library
  "/Users/milosvasic/Projects/Lava/lava-api-go/build/jniLibs/arm64-v8a/liblavaapi.so"
  not found: needed by .../liblavaapi_jni.so in namespace clns-7
```

Root cause (confirmed with `llvm-readelf -d`, NOT guessed):

- The prebuilt Go c-shared `liblavaapi.so` (built by
  `lava-api-go/scripts/build-cshared.sh` via `go build -buildmode=c-shared`) has
  **no `SONAME`** (`readelf -d` shows only NEEDED liblog/libdl/libc, no SONAME).
- The JNI bridge `liblavaapi_jni.so` (built by
  `lava-api-go/cmd/lavaapi-cshared/jni/CMakeLists.txt`, which IMPORTs the
  prebuilt via its absolute `IMPORTED_LOCATION`) therefore records the **absolute
  host build path** as its `DT_NEEDED` entry:

  ```
  llvm-readelf -d liblavaapi_jni.so:
    (NEEDED)  Shared library: [/Users/milosvasic/Projects/Lava/lava-api-go/build/jniLibs/arm64-v8a/liblavaapi.so]
    (SONAME)  Library soname:  [liblavaapi_jni.so]
  ```

- On-device, the Android linker cannot resolve an absolute host path, so
  `dlopen` of the bridge fails → `LavaNative.nativeStart` is unreachable →
  `ApiEngineController.start()` maps to Error → C02/C03/C04 time out.

This is a **distinct, pre-existing defect** independent of the serialization fix
(it is a native ELF linking issue; the serialization fix changed which error
appears first). It lives entirely in `lava-api-go/` — NOT in `core/apiengine/`
Kotlin nor `api-app/` — and is therefore **outside this task's touched-files
scope** (core/apiengine + .lava-ci-evidence + docs/issues only).

### Precise remediation for the controller/operator (NOT applied here)

Give the prebuilt `liblavaapi.so` a proper SONAME so any consumer records the
SONAME (not the host path) in `DT_NEEDED`. In `lava-api-go/scripts/build-cshared.sh`
add the linker soname flag to the `go build`:

```sh
go build -buildmode=c-shared \
  -ldflags="-extldflags=-Wl,-soname,liblavaapi.so" \
  -o "${OUT_SO}" "${PKG}"
```

(Equivalently, the CMakeLists could link via `-Wl,-l:liblavaapi.so` against a
search path, but fixing the SONAME at the producing end is the canonical fix and
benefits every consumer.) After that, rebuild the c-shared `.so` + the bridge +
`:api-app`, re-run C02/C03/C04 — they connect to the real on-device HTTPS embed,
assert `/health` 200 + JSON, and the auth-gate behavior, and SHOULD pass.

## Anti-bluff posture

Per §6.Z/§6.J: NO green Challenge run was fabricated. The serialization defect is
genuinely fixed (proven by the JVM RED→GREEN reproduction AND by zero
`SerializationException` on-device). C01 genuinely passes. C02/C03/C04 genuinely
fail against a REAL, precisely-diagnosed second defect that is out of this task's
scope. Reporting the found defect honestly is the correct outcome.
