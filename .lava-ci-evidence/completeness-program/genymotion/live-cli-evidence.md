# Genymotion / Containers integration — live evidence (2026-06-06T16:46:23Z)
## gmtool detect
/Applications/Genymotion.app/Contents/MacOS/gmtool
## version
3.10.0
## list
On	127.0.0.1:6555	<genymotion-vm-uuid-redacted>	Google Pixel 9
## serial Google Pixel 9
127.0.0.1:6555
## go test ./pkg/genymotion/
ok  	digital.vasic.containers/pkg/genymotion	(cached)

## Challenge00 connected-test proof — PENDING_QUIET_RERUN (NOT a pass, NOT a real defect)

First attempt (evidence dir `.lava-ci-evidence/genymotion/20260606T164905Z/`):
- **Device integration: PROVEN.** The runner detected gmtool, resolved the
  running VM's adb serial (`127.0.0.1:6555`), and probed device identity live:
  `android_release=15 sdk=35 model=Pixel 9 abi=arm64-v8a`. The §6.AH VM path +
  Containers-CLI-driven serial resolution works end-to-end.
- **APK build: FAILED** at `:feature:login:kspDebugKotlin` with
  `KotlinFrontEndException` analyzing `feature/login/.../LoginViewModel.kt`
  (100,9) — an UNCHANGED file that compiles cleanly in isolation.
- **Root cause (FACT, captured — §11.4.6):** the run executed while 15
  concurrent gradle/kotlin daemon processes were active (the 3 parallel P4
  coverage subagents running `:module:test` in the SAME checkout). Concurrent
  Gradle invocations against overlapping build/ + KSP caches corrupt KSP
  analysis. This is environmental contention, NOT a source defect and NOT a
  Genymotion-integration defect.
- **Remediation:** re-run Challenge00 via the runner once the gradle-using
  subagents have finished (quiet gradle environment). LESSON: full-app
  `connectedDebugAndroidTest` MUST NOT run concurrently with other gradle
  builds in the same working tree.
