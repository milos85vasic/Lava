# Phase E — native SONAME defect FIXED + honest Containers-gate blocker (2026-06-02)

Honest, real-execution record per §6.Z / §6.J / §6.AG. The native-linking
defect the prior Phase E run caught (the JNI bridge recorded the absolute HOST
build path of `liblavaapi.so` as its `DT_NEEDED` → on-device `dlopen failed:
library "/Users/.../liblavaapi.so" not found` → embed never starts → C02/C03/C04
time out) is **FIXED and ELF-PROVEN gone**. The §6.AG gate re-run through the
Containers submodule surfaced a **genuine, code-confirmed CLI limitation** that
is OUTSIDE this task's allowed touch-list — reported precisely below, NOT
papered over with a faked green.

## The fix (in lava-api-go/scripts/build-cshared.sh)

Added the NDK linker soname flag to the c-shared `go build`:

```sh
go build -buildmode=c-shared \
    -ldflags="-extldflags=-Wl,-soname,liblavaapi.so" \
    -o "${OUT_SO}" "${PKG}"
```

`go build` EXIT 0 for all three ABIs (arm64-v8a, x86_64, armeabi-v7a);
`LavaApiStart` present in the dynamic symbol table of each.

## ELF proof — DT_SONAME present on liblavaapi.so (all 3 ABIs)

`llvm-readelf -d` (NDK 25.1.8937393), verbatim:

```
arm64-v8a:     0x000000000000000e (SONAME)  Library soname: [liblavaapi.so]
x86_64:        0x000000000000000e (SONAME)  Library soname: [liblavaapi.so]
armeabi-v7a:   0x0000000e         (SONAME)  Library soname: [liblavaapi.so]
```

BEFORE the fix, `liblavaapi.so` had NO SONAME entry (only `NEEDED liblog.so /
libdl.so / libc.so`).

## ELF proof — the bridge's DT_NEEDED is now the relative SONAME (the load-bearing assertion)

After rebuilding the JNI bridge (`./gradlew :api-app:assembleDebug
:api-app:assembleDebugAndroidTest`, BUILD SUCCESSFUL in 54s), `llvm-readelf -d`
of `liblavaapi_jni.so` **as packaged inside `api-app-debug.apk`**, verbatim:

```
lib/arm64-v8a/liblavaapi_jni.so:
  0x0000000000000001 (NEEDED)   Shared library: [liblavaapi.so]      <- RELATIVE, was the abs host path
  0x000000000000000e (SONAME)   Library soname: [liblavaapi_jni.so]
```

BEFORE the fix this NEEDED entry was
`[/Users/milosvasic/Projects/Lava/lava-api-go/build/jniLibs/arm64-v8a/liblavaapi.so]`
(absolute host path — unresolvable on-device). The APK ships BOTH libs in
`lib/arm64-v8a/`:

```
53340872  lib/arm64-v8a/liblavaapi.so
   25032  lib/arm64-v8a/liblavaapi_jni.so
```

So the Android linker now resolves `liblavaapi.so` from the APK's lib dir at
`dlopen` time. The root cause of the C02/C03/C04 `dlopen failed` is eliminated
at the ELF level — definitively, not by assertion.

Build commit SHA: `4fc9c21374bbd22f8c75685b246f4ac008ae40ea` (master tip with
the prior `:core:apiengine` serialization fix) + the soname fix on branch
`fix-cshared-soname-2026-06-02`.

## §6.AG / §6.X Containers-gate re-run — GENUINELY ATTEMPTED, honestly BLOCKED

Host: `Darwin arm64`. `host-preflight.json`: `accel=hvf`, `gate_eligible=true`
— the §6.X-resolved macOS gate path (host-direct + HVF, Containers-orchestrated).

`scripts/run-api-app-challenge-matrix.sh` invoked the Containers submodule's
`cmd/emulator-matrix --runner=auto` CLI. The CLI **DID boot a cold emulator AVD**
(Containers-orchestrated, NOT a live ADB device per §6.AG), confirmed by the
runner's own forensic diagnostics, verbatim:

```
[§6.X] runner=auto resolved to host-direct (accel=hvf, goos=darwin)
[§6.X] runner=host-direct is the OS-correct accelerated gate runner for darwin (accel=hvf)
[matrix-diag] target=localhost:5555 sdk="35" device="emu64a"
[matrix-diag-devices] List of devices attached | emulator-5554	device | localhost:5555	device
```

**BLOCKER (code-confirmed, then process-confirmed):** the Containers CLI's
instrumentation step is HARDWIRED to `./gradlew :app:connectedDebugAndroidTest`
(`submodules/containers/pkg/emulator/android.go:858` host-direct +
`containerized.go:320`). It has NO `--gradle-module` override. The live process
list captured the CLI running the WRONG module against the api-app class filter:

```
java ... org.gradle.wrapper.GradleWrapperMain :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=lava.api.app.challenges.Challenge01ApiAppColdStartTest,...Challenge04NotificationActionsTest \
  --no-daemon
```

`:app:connectedDebugAndroidTest` builds + installs the `:app` module's APKs and
instruments THEM — the `lava.api.app.challenges.*` classes do not exist in
`:app` (they live in `:api-app`). Gradle would report BUILD SUCCESSFUL with ZERO
matching tests → the CLI would emit a **false green that tested nothing in
api-app**. Per §6.Z/§6.J that is a PASS-bluff by construction, so the run was
STOPPED (emulator-matrix PID + gradle child killed by exact PID; emulator torn
down via `adb emu kill` — NOT pkill) and NO `real-device-verification.json` was
fabricated.

## Why this is BLOCKED rather than fixable here

Making the Containers CLI run `:api-app:connectedDebugAndroidTest` requires a
generic `--gradle-module` flag (or a boot-only mode) added to
`submodules/containers/cmd/emulator-matrix` + `pkg/emulator/{android,containerized}.go`.
A gradle-module string is a UNIVERSAL parameter (not Lava-project-specific, so it
does NOT violate the Containers decoupling rule) — but `submodules/containers/`
is OUTSIDE this task's allowed touch-list (`lava-api-go/`, `api-app/`,
`scripts/run-*challenge*`, `.lava-ci-evidence/`, `BUGFIXES.md` only). The
Lava-side glue (`scripts/run-api-app-challenge-matrix.sh`) already parameterises
the module (`--gradle-module`, default `:api-app`) and forwards it via the
`LAVA_GRADLE_MODULE` env var, so the gate runs GREEN the moment Containers gains
the flag (current pin `6aff7ea8` ignores the env var).

Per the task's explicit escape hatch ("If you genuinely cannot route through the
Containers CLI on this host, STOP and report exactly why — don't fall back to
raw adb as gate evidence"), this is the honest report. The soname defect is
FIXED + ELF-PROVEN; the runtime C01-04 GREEN through the full Containers gate is
OWED on a Containers `--gradle-module` (or boot-only) feature.

## OWED (Containers-submodule follow-up, out of this task's scope)

`submodules/containers/cmd/emulator-matrix` + `pkg/emulator/RunInstrumentation`
gain a `--gradle-module <name>` flag (default `:app`) so non-`:app` modules
(e.g. `:api-app`) can be gated. Then re-run
`scripts/run-api-app-challenge-matrix.sh` (already module-parameterised) on the
macOS host-direct+HVF runner; C01 PASSes (already did in the prior Phase E run),
and C02/C03/C04 are EXPECTED to PASS now that the embed's native library
resolves at `dlopen` (the ELF proof above). Until then the runtime gate is
honestly BLOCKED, never faked.
