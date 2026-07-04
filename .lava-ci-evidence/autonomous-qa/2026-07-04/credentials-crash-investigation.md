# Autonomous QA 2026-07-04 — Phase 1 Investigation

**Scope:**
1. Why all credentialed providers (`rutracker`, `nnmclub`, `kinozal`) were skipped.
2. Why the `nnmclub/1080p` scenario crashed the instrumentation process.

**Investigator notes:**
- No production code was changed.
- No tests were written.
- This report contains file/line references and log excerpts only; credential values are redacted.

---

## 1. Credentialed providers were skipped because the pre-built APK lacked credentials

### 1.1 BuildConfig credential injection chain

The debug `BuildConfig` fields are populated from the repo-root `.env` file at Gradle configuration time.

| Step | Location | What it does |
|------|----------|--------------|
| `.env` loader | `app/build.gradle.kts:8-19` | Parses `.env` into a `Map<String, String>`; returns empty map if `.env` is missing. |
| Credential fields | `app/build.gradle.kts:111-116` | Emits `buildConfigField("String", "RUTRACKER_USERNAME", "\"${env[...].orEmpty()}\"")` and the matching password fields for `RUTRACKER`, `KINOZAL`, and `NNMCLUB`. |
| BuildConfig generation | `app/build.gradle.kts:169` (`buildConfig = true`) | Generates `digital/vasic/lava/client/BuildConfig.java`. |

The generated file is:

```
app/build/generated/source/buildConfig/debug/digital/vasic/lava/client/BuildConfig.java
```

A fresh debug build from the current working tree populates this file with the non-empty credential values that are present in the current `.env`.

### 1.2 Test-level skip logic

`Challenge70AutonomousQaProviderMatrixTest` explicitly skips credentialed providers when the injected `BuildConfig` username or password is empty:

- File: `app/src/androidTest/kotlin/lava/app/challenges/Challenge70AutonomousQaProviderMatrixTest.kt`
- Lines: `307-313`

```kotlin
assumeTrue(
    "Missing .env credentials for ${spec.id}: BuildConfig username/password " +
        "empty (set them in .env for this provider) — §6.J-tracked skip.",
    !spec.needsCreds || (spec.username.isNotEmpty() && spec.password.isNotEmpty()),
)
```

`ProviderSpec.forId(...)` resolves the credentials from the `BuildConfig` fields at `Challenge70AutonomousQaProviderMatrixTest.kt:194-211`.

### 1.3 Why the 2026-07-04 run skipped them

The autonomous QA runner does **not** rebuild the APK before each iteration.

- `scripts/autonomous-qa/run-matrix.sh` orchestrates the backend and emulator but delegates each iteration to `run-iteration.sh`.
- `scripts/autonomous-qa/run-iteration.sh:19` hard-codes the APK path:
  ```bash
  APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  ```
- `scripts/autonomous-qa/run-iteration.sh:53-54` runs `adb uninstall` / `adb install -r` using that pre-existing APK.
- The Gradle invocation at `scripts/autonomous-qa/run-iteration.sh:103-111` runs `:app:connectedDebugAndroidTest`, but on the 2026-07-04 `nnmclub/1080p` run it reported `:app:generateDebugBuildConfig UP-TO-DATE` and `:app:packageDebug UP-TO-DATE` (see `goapi/nnmclub-1080p/raw/gradle-connected.log:134` and `:1198`).

**Conclusion:** the APK that was installed on the emulator was built earlier, when `.env` either did not exist or did not contain the credential variables. Because `BuildConfig.RUTRACKER_USERNAME`, `RUTRACKER_PASSWORD`, `NNMCLUB_USERNAME`, `NNMCLUB_PASSWORD`, `KINOZAL_USERNAME`, and `KINOZAL_PASSWORD` were empty strings, `Challenge70` correctly skipped every credentialed provider.

### 1.4 Summary evidence

- `goapi/summary.md` shows `rutracker/*`, `kinozal/*`, `nnmclub/story`, and `nnmclub/linux` as `SKIP` (30 skips total).
- `goapi/nnmclub-1080p/junit.xml` reports `tests="1" failures="1" errors="0" skipped="0"` for the crashed run, but the skipped providers are recorded as skipped in their own JUnit outputs.

---

## 2. `nnmclub/1080p` crash signature — external `signal 9 (Killed)` during instrumentation

### 2.1 Crash signature

The instrumentation process did **not** crash from a Java exception, native tombstone, ANR, or OOM. It was killed by the system because the package was force-stopped while the test was running.

Key logcat lines from `goapi/nnmclub-1080p/raw/logcat.txt`:

| Line | Timestamp | Message |
|------|-----------|---------|
| 1491 | `07-03 22:02:36.102` | `TestRunner: started: autonomousMatrix_onboard_search_topic_download(lava.app.challenges.Challenge70AutonomousQaProviderMatrixTest)` |
| 1820 | `07-03 22:02:49.266` | `ActivityManager: START u0 {cmp=digital.vasic.lava.client.dev/digital.vasic.lava.client.MainActivity}` — `MainActivity` launched. |
| 2105 | `07-03 22:02:49.651` | `ActivityTaskManager: Displayed digital.vasic.lava.client.dev/digital.vasic.lava.client.MainActivity` |
| 2109 | `07-03 22:02:49.655` | `ActivityManager: Force stopping digital.vasic.lava.client.dev appid=10202 user=-1: deletePackageX` |
| 2112 | `07-03 22:02:49.656` | `ActivityManager: Killing 13082:digital.vasic.lava.client.dev/u0a202 (adj 0): stop digital.vasic.lava.client.dev due to deletePackageX` |
| 2130 | `07-03 22:02:49.659` | `ActivityManager: Crash of app digital.vasic.lava.client.dev running instrumentation ComponentInfo{digital.vasic.lava.client.dev.test/lava.app.LavaHiltTestRunner}` |
| 2205 | `07-03 22:02:49.725` | `Zygote: Process 13082 exited due to signal 9 (Killed)` |

The app launched successfully, `MainActivity` reached `RESUMED`, and ~74 ms after the "Displayed" log the package was force-stopped due to `deletePackageX`, which sent `SIGKILL` to the app process and caused AndroidJUnitRunner to report `Process crashed`.

### 2.2 Gradle / JUnit evidence

- `goapi/nnmclub-1080p/raw/gradle-connected.log:1256` reports the test failure as `Process crashed`.
- `goapi/nnmclub-1080p/raw/gradle-connected.log:1278` reports `BUILD FAILED in 27s`.
- `goapi/nnmclub-1080p/junit.xml:11-12` contains:
  ```xml
  <system-err>Test run failed to complete. Instrumentation run failed due to Process crashed.</system-err>
  ```
- `goapi/nnmclub-1080p/verdict.json` records:
  ```json
  { "gradle_rc": 1, "tests": 1, "failures": 1, "errors": 0, "skipped": 0,
    "marker_download_ok": false, "teardown_known_lva008": false,
    "other_failure_signal": false, "verdict": "FAIL" }
  ```

### 2.3 What the crash is **not**

| Hypothesis | Evidence | Verdict |
|------------|----------|---------|
| Java `FATAL EXCEPTION` | No `AndroidRuntime: FATAL EXCEPTION` line in logcat. | Ruled out. |
| Native crash / tombstone | No `DEBUG`/`tombstone` lines, no signal 6/11. | Ruled out. |
| ANR | No `ActivityManager: ANR` or `traces.txt` dump. | Ruled out. |
| OOM | No `OutOfMemoryError`, no low-memory killer logs, process was killed with `signal 9` from `ActivityManager` directly. | Ruled out. |
| Tink/EncryptedSharedPreferences fatal error | Repeated `AndroidKeysetManager: keyset not found, will generate a new one` warnings (e.g. logcat lines 1511, 1552, 1593, 1633, 1673, 1713) are expected first-run behavior and are logged at `WARN`, not fatal. | Ruled out as crash cause. |

### 2.4 Ranked root-cause hypotheses

1. **Most likely: the test harness or a concurrent operation uninstalled the package mid-test.**
   - The `deletePackageX` reason string in `ActivityManager` means the package was removed (or its data was cleared) through the PackageManager while instrumentation was active.
   - `run-iteration.sh` does call `adb uninstall` at the **start** of each iteration (lines 53-54), but the log timestamps show the uninstall happening ~13 seconds **after** the test started and after `MainActivity` was already displayed.
   - Possible sub-causes:
     - A stale `adb uninstall` from a previous iteration that was delayed by ADB transport latency and arrived during this run.
     - Another process/script on the host issued `adb uninstall digital.vasic.lava.client.dev` while this iteration was running.
     - The Gradle `connectedDebugAndroidTest` task itself triggered a package reinstall/reset that collided with the running instrumentation.

2. **Plausible: ADB / emulator instability caused the PackageManager to reset the package state.**
   - The emulator serial was `127.0.0.1:43159` (`verdict.json:5`).
   - The `NetworkScheduler` SQLite constraint error in logcat line 1492 is from Google Play Services and is unrelated, but it demonstrates non-trivial background activity on the emulator image.
   - If the emulator container or ADB bridge flapped, the host-side tooling may have issued a package reset as a recovery action.

3. **Less likely: the app triggered a self-uninstall or device-admin action.**
   - No Lava code path is known to request package uninstallation.
   - No `DeviceAdminReceiver` or `DELETE_PACKAGES` permission use was observed.

### 2.5 Correlation with provider flow

Because the process was killed during the first screen (`MainActivity`), the test never reached the onboarding provider selection, login, search, topic, or download phases. Therefore this failure is **orthogonal to the `nnmclub` provider logic** and to the credential-empty issue described in Section 1. The same crash signature would likely occur for any provider/query pair if the same race condition repeats.

---

## 3. Recommendations

1. **Rebuild the debug APK with credentials before the next matrix run.**
   - Run `./gradlew :app:assembleDebug` after confirming `.env` contains the six credential variables.
   - Verify `BuildConfig.java` contains non-empty values before launching the matrix.
   - Consider making `run-iteration.sh` (or `run-matrix.sh`) assert that `BuildConfig` credential fields are non-empty when credentialed providers are scheduled, to fail fast instead of recording silent skips.

2. **Eliminate the mid-test `deletePackageX` kill.**
   - Audit `scripts/autonomous-qa/run-iteration.sh` and `run-matrix.sh` for any operation that can uninstall or clear the app package while instrumentation is running.
   - Add explicit synchronization: wait for the previous `adb uninstall`/`adb install` pair to fully complete and for the device state to settle before starting the next Gradle invocation.
   - Capture `adb logcat` from the host side around every uninstall/install boundary to detect delayed ADB commands.

3. **Distinguish "process killed by external action" from "app crashed" in verdict classification.**
   - The current `verdict.json` marks this as a generic `FAIL` with `other_failure_signal=false`. A new classification (e.g. `INFRA_KILL` or `PROCESS_KILLED`) would make the anti-bluff post-processing clearer.

4. **Run the matrix against a freshly built APK as part of normal flow.**
   - The current design assumes a pre-existing APK. The §6.Z/§6.AK anti-bluff gates require the distributed/tested artifact to be built from the same working tree state. Either build the APK inside `run-matrix.sh` or document the exact build command and SHA that produced it.

---

## 4. Files referenced

- `app/build.gradle.kts:8-19` — `.env` loader.
- `app/build.gradle.kts:111-116` — credential `buildConfigField` declarations.
- `app/build.gradle.kts:169` — `buildConfig = true`.
- `app/src/androidTest/kotlin/lava/app/challenges/Challenge70AutonomousQaProviderMatrixTest.kt:194-211` — credential resolution.
- `app/src/androidTest/kotlin/lava/app/challenges/Challenge70AutonomousQaProviderMatrixTest.kt:307-313` — skip-on-empty-credential guard.
- `scripts/autonomous-qa/run-iteration.sh:19` — pre-built APK path.
- `scripts/autonomous-qa/run-iteration.sh:53-54` — `adb uninstall` / `adb install`.
- `scripts/autonomous-qa/run-iteration.sh:103-111` — Gradle invocation.
- `.lava-ci-evidence/autonomous-qa/2026-07-04/goapi/summary.md` — overall matrix results.
- `.lava-ci-evidence/autonomous-qa/2026-07-04/goapi/nnmclub-1080p/raw/logcat.txt` — full device log.
- `.lava-ci-evidence/autonomous-qa/2026-07-04/goapi/nnmclub-1080p/raw/gradle-connected.log` — Gradle output.
- `.lava-ci-evidence/autonomous-qa/2026-07-04/goapi/nnmclub-1080p/junit.xml` — JUnit report.
- `.lava-ci-evidence/autonomous-qa/2026-07-04/goapi/nnmclub-1080p/verdict.json` — parsed verdict.
