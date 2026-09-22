# Falsifiability rehearsal — §6.N.1.3 companion to `real-device-verification.{md,json}`

Per the pre-push hook's §6.N.1.3 gate, this file records a genuine, executed
falsifiability rehearsal of the production code path that
`real-device-verification.{md,json}` (commit `c1b13ca7`) claims to cover —
specifically Task 3 (`Challenge00CrashSurvivalTest`), the load-bearing
cold-start-survival canary that PASSed in that attestation.

## Target

`Challenge00CrashSurvivalTest.continueOnArchiveOrg_persists_signaledAuthState_to_disk`
(`app/src/androidTest/kotlin/lava/app/challenges/Challenge00CrashSurvivalTest.kt`),
whose own KDoc already documents this exact rehearsal (lines 50-65). This
record is the **genuine execution** of that documented rehearsal against the
same containerized-emulator matrix path (`scripts/run-challenge-matrix.sh`,
`--runner=containerized`, `--avds "CZ_API34_Phone:34:phone"`) that produced
the attestation being gated.

## Mutation

`core/auth/impl/src/main/kotlin/lava/auth/impl/AuthServiceImpl.kt`,
`signalAuthorized()` — commented out:

```kotlin
preferencesStorage.saveSignaledAuthState(name = name, avatarUrl = avatarUrl)
```

## Step 1 — Rebuild + run against MUTATED code: FAIL (as expected)

```
$ export JAVA_HOME=/home/milosvasic/t062_work/jdk17/jdk-17.0.20.1+1
$ ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
BUILD SUCCESSFUL in 52s

$ ./scripts/run-challenge-matrix.sh --no-build \
    --avds "CZ_API34_Phone:34:phone" \
    --test-class "lava.app.challenges.Challenge00CrashSurvivalTest" \
    --evidence-dir ".lava-ci-evidence/2026-09-22-rehearsal-MUTATED-signalAuthorized" \
    --boot-timeout 8m --test-timeout 8m
...
  [1] CZ_API34_Phone            api=34 phone FAIL
       error: exit status 1
MATRIX FAILED — at least one AVD did not pass. tag.sh MUST refuse this commit.
```

Observed failure (`CZ_API34_Phone/gradle.log:1242-1245`, full log preserved at
`.lava-ci-evidence/2026-09-22-rehearsal-MUTATED-signalAuthorized/`):

```
lava.app.challenges.Challenge00CrashSurvivalTest > continueOnArchiveOrg_persists_signaledAuthState_to_disk[sdk_gphone64_x86_64 - 14] FAILED
	java.lang.AssertionError: PRIMARY: SignaledAuthState MUST be persisted on disk after Continue tap. Phase 1.1 production-stack guarantee for force-stop survival.

Tests on sdk_gphone64_x86_64 - 14 failed: There was 1 failure(s).
```

Machine attestation: `.lava-ci-evidence/2026-09-22-rehearsal-MUTATED-signalAuthorized/real-device-verification.json` — `"all_passed": false`, `"test_passed": false`.

(Note: a first attempt at this step failed with an unrelated `25.0.4` Gradle/JDK
error because `JAVA_HOME` was not exported for that invocation — a host-toolchain
issue, not the rehearsal's mutation. Re-run with `JAVA_HOME` correctly exported
produced the genuine assertion failure above. Recorded honestly per §11.4.6.)

## Step 2 — Revert

```
$ git diff --stat core/auth/impl/src/main/kotlin/lava/auth/impl/AuthServiceImpl.kt
(empty — byte-identical to pre-mutation)
```

## Step 3 — Rebuild + run against REVERTED code: PASS

```
$ ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
BUILD SUCCESSFUL in 50s

$ ./scripts/run-challenge-matrix.sh --no-build \
    --avds "CZ_API34_Phone:34:phone" \
    --test-class "lava.app.challenges.Challenge00CrashSurvivalTest" \
    --evidence-dir ".lava-ci-evidence/2026-09-22-rehearsal-REVERTED-signalAuthorized" \
    --boot-timeout 8m --test-timeout 8m
...
  [1] CZ_API34_Phone            api=34 phone PASS
MATRIX PASSED — every AVD booted and every test passed.
```

Machine attestation: `.lava-ci-evidence/2026-09-22-rehearsal-REVERTED-signalAuthorized/real-device-verification.json` — `"all_passed": true`, `"test_passed": true`.

## Verdict

**GENUINE.** `Challenge00CrashSurvivalTest` fails with a clear, specific
assertion message when the exact production code path it claims to cover
(`AuthServiceImpl.signalAuthorized` persisting `SignaledAuthState` to disk)
is broken, and passes again once reverted. The attestation in
`real-device-verification.{md,json}` (commit `c1b13ca7`) is not a bluff.

| Step | Result | Evidence dir |
|---|---|---|
| Mutated | FAIL — `AssertionError: PRIMARY: SignaledAuthState MUST be persisted...` | `.lava-ci-evidence/2026-09-22-rehearsal-MUTATED-signalAuthorized/` |
| Reverted | PASS | `.lava-ci-evidence/2026-09-22-rehearsal-REVERTED-signalAuthorized/` |
