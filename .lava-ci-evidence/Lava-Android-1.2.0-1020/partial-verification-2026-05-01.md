# Partial Mechanical Verification — Lava-Android-1.2.0-1020

**Status:** PARTIAL — operator action still required to advance to RELEASE-READY.
**Run by:** Claude Code session (Opus 4.7)
**Date:** 2026-05-01
**Commit being verified:** `9d1c0377336b34c42ed502becf389c4c2bffe7d1` (master HEAD, all 4 upstreams converged)

This file documents what was mechanically verifiable in this session without a connected Android device or emulator. It is NOT a substitute for the operator real-device verification required by Sixth Law clause 5; that gate remains open (see `real-device-verification.md` for the operator checklist).

## What was verified mechanically

### ✅ Pure-JVM tracker module test suites

`./gradlew :core:tracker:api:test :core:tracker:registry:test :core:tracker:mirror:test :core:tracker:testing:test :core:tracker:rutracker:test :core:tracker:rutor:test --rerun-tasks` → **BUILD SUCCESSFUL** (44 actionable tasks executed, 6 module test suites green).

### ✅ Tracker-SDK submodule test suites

`cd submodules/tracker_sdk && ./gradlew test --rerun-tasks` → **BUILD SUCCESSFUL** (4 module test suites: `:api`, `:mirror`, `:registry`, `:testing`).

### ✅ Phase 0 + Phase 5 retroactive Bluff-Audit completeness

All 8 expected `.lava-ci-evidence/sp3a-bluff-audit/` evidence files present:
- 0.1 TestEndpointsRepository
- 0.2 TestBookmarksRepository (stub-bluff lock-in)
- 0.3 TestAuthService (deferred — zero consumers)
- 0.4 TestLocalNetworkDiscoveryService (covered by existing TestInfrastructureContractTest)
- 0.5 EndpointConverterTest re-rehearsal
- 0.6 healthprobe contract test re-rehearsal
- 1.32 TrackerCapabilityTest retroactive (committed `b6c0d65`, 2026-05-01)
- 1.33 TrackerDescriptorContractTest retroactive (committed `b6c0d65`, 2026-05-01)
- `_summary.json` aggregator

### ✅ Seventh Law incident resolved

`.lava-ci-evidence/sixth-law-incidents/2026-04-30-sp3a-retroactive-stamps.json` — both substance-pending follow-ups closed by commit `b6c0d65`. Incident status: **RESOLVED**.

### ✅ Per-mirror SHA convergence verified

| Upstream | SHA at master |
|---|---|
| github.com/milos85vasic/Lava | `9d1c037` |
| gitflic.ru/milosvasic/lava | `9d1c037` |
| gitlab.com/milos85vasic/lava | `9d1c037` |
| gitverse.ru/milosvasic/Lava | `9d1c037` |

### ✅ Submodule pin verification

All 16 submodule pinned SHAs verified present on their respective remotes (run earlier in this session). `submodules/tracker_sdk` pinned at `b2754ea` (= **v0.2.0**, present on github + gitlab).

## What is BLOCKED by host environment / hardware

### ❌ Full `./scripts/ci.sh --full` run

**Blocker:** `:core:database:compileDebugJavaWithJavac` fails because the local JDK 17 install is missing the `jlink` executable. The Android Gradle Plugin's `JdkImageTransform` needs `jlink` to compile any Android module.

**Verbatim error:**
```
Could not resolve all files for configuration ':core:database:androidJdkImage'.
   > Failed to transform core-for-system-modules.jar to match attributes ...
      > Execution failed for JdkImageTransform: .../platforms/android-35/core-for-system-modules.jar.
         > jlink executable /usr/lib/jvm/java-17-openjdk-17.0.18.0.8-alt2.x86_64/bin/jlink does not exist.
```

**Operator fix:** install the `java-17-openjdk-devel` (or distro-equivalent) package that ships `jdk.jlink`. Verify with `ls "$JAVA_HOME/bin/jlink"`. Then re-run `./scripts/ci.sh --full`.

### ❌ Compose UI Challenge Tests C1-C8

**Blocker:** No connected Android device (`adb devices` shows empty) and no `emulator` command on PATH.

**Operator fix:** see `real-device-verification.md` operator checklist.

### ❌ Real-tracker integration tests against rutracker.org / rutor.info

**Blocker:** these tests are marked `@RealTracker` / `-PrealTrackers=true` and require explicit operator opt-in plus working network access to the trackers (which may be geo-blocked / mirror-degraded at run time).

## Open release blockers (in priority order)

1. **Install `jlink`** so the Android module chain compiles. Without this, no APK can be built and no Android-side test can run.
2. Connect a physical Android device (or start an emulator).
3. Run the full `./scripts/ci.sh --full` against `9d1c037`. Overwrite `.lava-ci-evidence/Lava-Android-1.2.0-1020/ci.sh.json` with real evidence.
4. Execute the 8 Challenge Tests on the device. Update `challenges/C<N>.json` files per the operator checklist.
5. Run the real-tracker mirror smoke against rutracker.org and rutor.info. Update `mirror-smoke/PENDING_OPERATOR.md` → real evidence.
6. Update `real-device-verification.md` `status:` from `PENDING_OPERATOR` to `VERIFIED`.
7. Run `./scripts/tag.sh --app android` to cut `Lava-Android-1.2.0-1020` and push to all 4 upstreams.

Once steps 1-7 are complete, the tag is shippable per the Constitution.

## What this report is NOT

- This is **not** an operator attestation. The operator real-device verification gate remains open.
- This is **not** a substitute for `./scripts/ci.sh --full` evidence. The pure-JVM subset is necessary but not sufficient.
- This is **not** a release certification. The release-tag gates in `scripts/tag.sh` will still refuse to operate until the items above are addressed.

This file is a faithful inventory of "what has been mechanically verified at session-end SHA `9d1c037`, against a host that lacks `jlink` and any Android device." It exists to make the gap explicit so the operator's next session knows what to pick up.
