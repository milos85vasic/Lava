# Group C Remaining — Network Simulation + Screenshot-on-Failure Design

> **Status:** Design approved (operator, 2026-05-05 — "all good" on the A.3 / B.1 / C.2 recommendations after Phase 5 SSH/QMP impls landed). Implementation pending — subagent-driven execution. Branch: Containers `lava-pin/2026-05-07-pkg-vm` (extends the existing in-flight branch).

> **Forensic anchor.** The Group B-pkg-vm closure JSON listed three Group C candidates: AVD image-cache management (delivered via the bundled pkg/vm + image-cache spec, closing §6.K-debt), network simulation per AVD/VM, and hardware-screenshot capture per row. The first landed in a separate cycle; the other two are bundled here as the final piece of Group C.

## 1. Scope

### 1.1 In scope

- **Network conditions per row** (component A.3 — profile + custom override). 8 named profiles map to (downKbps, upKbps, latencyMS, lossPercent) tuples; CLI override flags supersede the profile when set. Applied AFTER guest boot completes, BEFORE the test/script runs.
- **Screenshot capture on test failure** (component B.1). When `row.Passed == false`, capture a screenshot of the guest's framebuffer and save it under `<evidence-dir>/<row-id>/screenshot-on-failure.{png,ppm}`.
- **Both pkg/emulator AND pkg/vm coverage** (component C.2 — symmetric). Same field names + flags + behavior across both packages.
- **CLI surfaces.** Both `cmd/emulator-matrix` and `cmd/vm-matrix` gain the new flags. Lava-side wrappers (`scripts/run-emulator-tests.sh`, `scripts/run-vm-{signing,distro}-matrix.sh`) forward them.

### 1.2 Out of scope (deferred)

- Real-time network condition changes mid-test (e.g., simulate "wifi drop at second 30"). Static-for-the-test conditions only.
- Always-capture screenshots (B.2/B.3) — only on-failure for v0.1.
- PCAP packet capture per row.
- Per-row video recording.
- DNS / DHCP / VPN simulation beyond bandwidth/latency/loss.

### 1.3 Non-goals

- **Not** a re-design of any existing matrix-runner internals — both new features integrate via small additions to `runOne` (between WaitForBoot/WaitForReady and RunInstrumentation/Run for network apply; after Run for screenshot).
- **Not** a relaxation of any existing clause. Anti-bluff posture applied: every test asserts on user-visible / forensic state (the actual bandwidth/latency/loss applied; the actual screenshot file existing on disk with non-empty bytes).

## 2. Architecture

```
Containers (branch lava-pin/2026-05-07-pkg-vm — extends current HEAD)

  pkg/emulator/
    types.go         + NetworkConditions{ DownKbps, UpKbps, LatencyMS, LossPercent }
                     + MatrixConfig.NetworkProfile string
                     + MatrixConfig.NetworkOverride NetworkConditions
                     + MatrixConfig.CaptureScreenshotOnFailure bool
                     + AVDRow.NetworkProfile string  (recorded in attestation)
                     + AVDRow.ScreenshotPath string  (relative to evidence dir; empty if not captured)
    network.go       (new) — LookupNetworkProfile, mergeNetworkConditions(profile, override),
                     applyNetworkConditions(ctx, executor, serial, conditions) via adb emu console
                     `network speed <kbps>` and `network delay <ms>` commands.
    screenshot.go    (new) — CaptureScreenshot(ctx, executor, serial, dstPath) via
                     `adb -s <serial> exec-out screencap -p` writing PNG bytes.
    matrix.go        runOne integration: applyNetworkConditions after WaitForBoot,
                     CaptureScreenshot when row.Passed == false (gated on
                     config.CaptureScreenshotOnFailure).
    network_test.go  (new) + screenshot_test.go (new) — fakes-driven unit tests.

  pkg/vm/
    types.go         analogous fields on VMMatrixConfig + VMTestResult
    network.go       (new) — applyNetworkConditionsVM via SSH-in `tc qdisc add dev eth0
                     root netem delay <ms>ms rate <kbps>kbit loss <%>%` after
                     WaitForReady. Network interface name resolved via SSH `ip route
                     | awk '/^default/{print $5}'`.
    screenshot.go    (new) — CaptureScreenshotVM via QMP `screendump <path>` to a
                     guest-side temp path; QMP returns; then SCP-download from guest.
                     QEMU's screendump produces PPM (no native PNG; operator can
                     convert via ImageMagick / eog handles PPM).
    matrix.go        runOne integration: same shape as pkg/emulator.
    network_test.go (new) + screenshot_test.go (new) — fakes-driven unit tests.

  cmd/emulator-matrix/main.go + cmd/vm-matrix/main.go
    Both gain:
      --network-profile <name>             one of: edge, 2g, 3g, 4g, lte, wifi, ethernet, none
      --network-bandwidth-down <kbps>      override
      --network-bandwidth-up   <kbps>      override
      --network-latency        <ms>        override
      --network-loss           <%>         override (float, 0.0-100.0)
      --capture-screenshot-on-failure      bool, default true

Lava parent (branch master)

  scripts/run-emulator-tests.sh
  scripts/run-vm-signing-matrix.sh
  scripts/run-vm-distro-matrix.sh
    forward the new flags 1:1 (existing extra_args pattern).

  No new tests required at Lava level — the contract is "flags forwarded".
  The Containers tests cover behavior.
```

### Network profile values

```go
var networkProfiles = map[string]NetworkConditions{
    "edge":     {DownKbps: 240,    UpKbps: 200,    LatencyMS: 840, LossPercent: 1.0},
    "2g":       {DownKbps: 50,     UpKbps: 50,     LatencyMS: 500, LossPercent: 2.0},
    "3g":       {DownKbps: 1500,   UpKbps: 750,    LatencyMS: 100, LossPercent: 0.5},
    "4g":       {DownKbps: 6000,   UpKbps: 1500,   LatencyMS: 50,  LossPercent: 0.1},
    "lte":      {DownKbps: 12000,  UpKbps: 3000,   LatencyMS: 20,  LossPercent: 0.05},
    "wifi":     {DownKbps: 50000,  UpKbps: 10000,  LatencyMS: 5,   LossPercent: 0.01},
    "ethernet": {DownKbps: 100000, UpKbps: 100000, LatencyMS: 1,   LossPercent: 0.0},
    "none":     {},  // no shaping; LookupNetworkProfile returns zero-value
}
```

Override merging: any non-zero override field wins over the profile's value. `none` profile + zero overrides = no shaping applied (early return).

## 3. Data flow

### Successful gating run

```
operator: ./scripts/run-emulator-tests.sh \
            --network-profile 4g \
            --network-loss 0.5 \
            ...

scripts/run-emulator-tests.sh
  └── forwards --network-profile 4g --network-loss 0.5 to cmd/emulator-matrix

cmd/emulator-matrix
  └── for each AVD:
      ├── Boot → WaitForBoot (Android up)
      ├── applyNetworkConditions(serial, conditions) — adb emu network speed/delay
      ├── Install APK
      ├── RunInstrumentation
      ├── if !row.Passed && CaptureScreenshotOnFailure:
      │     CaptureScreenshot(serial, evidence-dir/<avd>/screenshot-on-failure.png)
      ├── append AVDRow with NetworkProfile + (if captured) ScreenshotPath
      └── Teardown
  └── writeAttestation includes per-row network_profile + screenshot_path fields

scripts/tag.sh (unchanged) — gates on existing schema; the 2 new fields are
  additive and don't affect the Group B gates.
```

## 4. Error handling

| Scenario | Behavior |
|---|---|
| Unknown `--network-profile <name>` | exit 2 with "unknown network profile: %s" + list of valid names |
| applyNetworkConditions fails (adb/SSH error) | row's BootError records "network conditions apply failed: <err>"; row Passed=false |
| CaptureScreenshot fails (adb error / screendump error) | log warning to stderr; row.ScreenshotPath stays empty; row.Passed signal NOT changed (screenshot is forensic-extra, not gating) |
| Screenshot disk-write fails | same — log + skip; non-fatal |
| `--network-bandwidth-down <kbps>` with negative value | exit 2 at flag-parse time |
| `--network-loss <%>` outside [0, 100] | exit 2 |
| `--capture-screenshot-on-failure=false` with row.Passed=false | no screenshot written (operator opted out) |
| Profile + override merge produces zero-everything | no shaping applied (early return; this is the `none` profile path) |

## 5. Testing

| Test | Asserts |
|---|---|
| `TestLookupNetworkProfile_Known` | each of 8 profile names returns expected (DownKbps, UpKbps, LatencyMS, LossPercent) |
| `TestLookupNetworkProfile_Unknown_ReturnsError` | "unknown network profile" error |
| `TestMergeNetworkConditions_OverrideWinsPerField` | profile=4g (down=6000), override.DownKbps=12345 → result.DownKbps=12345; other fields inherit from profile |
| `TestApplyNetworkConditions_NoOpOnZeroes` | conditions all-zero → adb emu network commands NOT issued |
| `TestApplyNetworkConditions_AdbEmuConsoleCommandsIssued_Android` | conditions=4g profile → fakeExecutor records `adb emu console "network speed 6000:1500"` + `network delay 50` (or analogous emulator-console invocations) |
| `TestApplyNetworkConditionsVM_TCQdiscIssued` | conditions=4g → fake SSH session records `tc qdisc add dev eth0 root netem delay 50ms rate 6000kbit loss 0.1%` |
| `TestCaptureScreenshot_Android_WritesPNGBytes` | adb screencap fake produces PNG bytes; CaptureScreenshot writes them to dst path |
| `TestCaptureScreenshot_AndroidAdbError_LogsWarningReturnsNil` | when adb fails, function returns nil (forensic-only) and does NOT propagate the error to row.Passed |
| `TestCaptureScreenshotVM_QMPScreendumpIssued` | QMP fake records `screendump <path>` command |
| `TestRunOne_NetworkConditionsAppliedBetweenBootAndTest` | runOne with --network-profile 4g → apply happens after WaitForBoot, BEFORE Install/RunInstrumentation |
| `TestRunOne_ScreenshotCapturedOnRowFailure` | runOne with row.Passed=false + CaptureScreenshotOnFailure=true → screenshot file exists at expected path |
| `TestRunOne_ScreenshotSkippedOnRowSuccess` | runOne with row.Passed=true → screenshot NOT captured |
| `TestRunOne_ScreenshotSkippedWhenFlagFalse` | runOne with row.Passed=false + CaptureScreenshotOnFailure=false → screenshot NOT captured |

≥4 falsifiability rehearsals across the test suite.

## 6. Order of operations

| Phase | Branch | Files | Commits |
|---|---|---|---|
| **A.** Containers code | `lava-pin/2026-05-07-pkg-vm` (extends) | `pkg/emulator/{types,network,screenshot,matrix,network_test,screenshot_test,matrix_test}.go`, `pkg/vm/{types,network,screenshot,matrix,network_test,screenshot_test,matrix_test}.go`, `cmd/{emulator,vm}-matrix/main.go` | 1 commit |
| **B.** Lava parent | `master` | `scripts/run-emulator-tests.sh`, `scripts/run-vm-{signing,distro}-matrix.sh`, `Submodules/Containers` gitlink bump | 1 commit |

Total: **1 Containers commit + 1 Lava parent commit**. Subagent-driven; 4-mirror push verified live.

## 7. Constitutional bindings

- **Sixth Law clauses 1-5.** Tests assert on user-visible behavior: the actual adb/tc commands issued, the actual screenshot file's existence + non-empty bytes, the actual network_profile field in the attestation row.
- **Clause 6.I.** Per-row schema gains `network_profile` + `screenshot_path`; tag.sh's existing Group B gates are unchanged (new fields are additive).
- **Clause 6.J/6.L.** Anti-bluff posture: screenshot-on-failure is forensic gold for diagnosing broken tests; the network-conditions apply is observable on the emulator console / via tc inside the guest. No silent failures.
- **Clause 6.M.** No new host-stability concerns. `tc qdisc` runs INSIDE the guest, not on the host.
- **Clause 6.N.** The Containers commit's body carries ≥4 falsifiability rehearsals per the stricter §6.N variant.
- **Decoupled Reusable Architecture.** Network profiles + screenshot capture are generic emulator/VM concerns; they live in Containers. Lava-side scripts forward the flags.

## 8. Self-review checklist

- [x] **Placeholder scan.** No "TBD" / "TODO" / "implement later" in the spec body.
- [x] **Internal consistency.** Architecture diagram, components, data flow, testing all reference the same field names (`NetworkProfile`, `NetworkOverride`, `CaptureScreenshotOnFailure`, `ScreenshotPath`), the same 8 profile names, the same flag names.
- [x] **Scope check.** Bundled (network sim + screenshot) is appropriate — both are runOne-integration concerns. Not too large for one cycle (1 Containers commit + 1 Lava parent commit).
- [x] **Ambiguity check.** Network condition apply ordering ("after WaitForBoot, before Install/RunInstrumentation") stated in 3 places (component A.3, data flow, testing table). Screenshot capture trigger ("when row.Passed==false AND CaptureScreenshotOnFailure==true") stated in 3 places (component B.1, error handling table, testing table).

---

**Spec status:** approved. Plan written inline (next section). Implementation dispatched to a single subagent (smaller scope than Group A-prime / B / pkg-vm so the per-task review pattern collapses to a single implementer + spec/quality review).
