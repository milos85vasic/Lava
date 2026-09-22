# Real-device / real-stack verification — signed debug build unblocked by newly-retrieved secrets

**Date (UTC):** 2026-09-22
**Commit:** `dc21b82293b6b47a7bbddfa27264117ebda5008c` (master, HEAD at task start)
**Purpose:** `.env`, `keystores/{debug,release}.keystore`, and `app/google-services.json` became available on this host for the first time this session. This record captures the FIRST genuinely signed-and-device-tested build produced from them, per the project's Anti-Bluff Pact (§6.Z / §6.AA / §6.X / §6.AH / §6.I). This supersedes nothing by itself — it is the evidence a follow-up session needs before deciding whether `scripts/tag.sh` can supersede the existing source-only release for `Lava-Android-1.3.17-1087` / `Lava-API-Go-2.3.35-2335`.

**Scope note (honest, per §6.J):** this is a single-AVD, single-Challenge-class device run (Challenge00 cold-start canary on API 34 phone only), NOT a full §6.AE.2 gate matrix (API 28/30/34/latest × phone+tablet). It satisfies the §6.Z clause 4 mandatory minimum (cold-start survival) for the debug variant. It does NOT by itself satisfy §6.AA/§6.AE's full matrix requirement for a release tag — that is explicitly out of scope per this task's constraints (no `scripts/tag.sh` invocation).

---

## Task 1 — Signed debug APK build: PASS

```
$ export JAVA_HOME=/home/milosvasic/t062_work/jdk17/jdk-17.0.20.1+1
$ ./gradlew :app:assembleDebug --no-daemon
...
BUILD SUCCESSFUL in 1m 25s
873 actionable tasks: 455 executed, 418 up-to-date
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
SHA-256: `77465aef47df5b932c69ef848839f4a487b7e7ffd9680d5712229534741adc7a`

Signature verification (`apksigner verify -v`):
```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Number of signers: 1
```
Certificate (`apksigner verify --print-certs`):
```
V2 Signer: certificate DN: CN=Lava Debug, O=Lava, C=RS
V2 Signer: certificate SHA-256 digest: 5ff75fae542b6fdad272cab16a167caff7d448cd03a2dddfb10884553fa3899a
```
This confirms the newly-retrieved `.env` (`KEYSTORE_PASSWORD`) genuinely unlocks `keystores/debug.keystore` and produces a real, verifiable v1+v2 signed artifact — not a placeholder.

## Task 2 — Containerized emulator boot (§6.AH: container/VM only, never host-direct): PASS

Host pre-flight (Linux x86_64, `/dev/kvm` present + rw-accessible via ACL for this user, podman 5.7.0):
```
==> §6.X acceleration resolved: accel=kvm runner=containerized (platform=Linux)
```

**Blocker encountered and resolved (documented per task instructions, not hidden):** the default `--container-image` reference `ghcr.io/vasic-digital/lava-android-emulator:api{api}-x86_64` is NOT publicly pullable —
```
$ podman pull ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64
Error: unable to copy from source docker://...: Requesting bearer token: received unexpected HTTP status: 403 Forbidden
```
No image had ever been published/authenticated for this registry path on this host. Per the script's own documented fallback #1 ("Build the image locally from `submodules/containers/pkg/emulator/Containerfile`"), the image was built locally instead of routing around the gate:
```
$ cd submodules/containers && podman build --build-arg API_LEVEL=34 --build-arg ABI=x86_64 \
    -f pkg/emulator/Containerfile -t ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64 .
...
Successfully tagged ghcr.io/vasic-digital/lava-android-emulator:api34-x86_64
```
(Downloads Android cmdline-tools + emulator package + `system-images;android-34;google_apis;x86_64` from Google's canonical mirror; build completed successfully, all 26 steps.)

Matrix run (single AVD, containerized runner, cold-boot, `--concurrent 1`):
```
$ ./scripts/run-challenge-matrix.sh --no-build \
    --avds "CZ_API34_Phone:34:phone" \
    --test-class "lava.app.challenges.Challenge00CrashSurvivalTest" \
    --evidence-dir ".lava-ci-evidence/2026-09-22-signed-debug-device-verification" \
    --boot-timeout 8m --test-timeout 8m
...
[§6.X] runner=auto resolved to containerized (accel=kvm, goos=linux)
[containerized] requested AVD "CZ_API34_Phone" is not baked into image ...; using baked AVD "default" (api 34 matches requested api 34; requested name was advisory)
Matrix run finished. Attestation: .lava-ci-evidence/2026-09-22-signed-debug-device-verification/real-device-verification.json
[1] CZ_API34_Phone            api=34 phone PASS
Gating: TRUE  (serial run, --dev=false — clause-6.I-clause-7-eligible)
MATRIX PASSED — every AVD booted and every test passed.
==> §6.AE matrix run complete (exit=0)
```

Machine attestation (`real-device-verification.json`, same directory):
```json
{
  "all_passed": true,
  "gating": true,
  "rows": [
    {
      "avd": "CZ_API34_Phone",
      "api_level": 34,
      "form_factor": "phone",
      "boot_seconds": 52.702177142,
      "test_class": "lava.app.challenges.Challenge00CrashSurvivalTest",
      "test_passed": true,
      "test_seconds": 127.654086121,
      "concurrent": 1
    }
  ]
}
```

Emulator ran INSIDE a podman container (`--device /dev/kvm`), never host-direct, per §6.AH. Real cold boot took 52.7s.

## Task 3 — Install signed debug APK + run Challenge00CrashSurvivalTest: PASS

The matrix run above (Task 2) drove `:app:connectedDebugAndroidTest` against the containerized emulator with the EXACT APK built in Task 1. Real instrumentation output (`CZ_API34_Phone/gradle.log`):
```
> Task :app:connectedDebugAndroidTest
Starting 1 tests on sdk_gphone64_x86_64 - 14

Finished 1 tests on sdk_gphone64_x86_64 - 14

BUILD SUCCESSFUL in 2m 7s
911 actionable tasks: 35 executed, 876 up-to-date
```
`sdk_gphone64_x86_64 - 14` confirms Android API 14 (API level 34) — the device the test actually ran on. 1 test started, 1 finished, `BUILD SUCCESSFUL` with zero failures reported in the JUnit result (`test_passed: true` in the JSON attestation above). This is the load-bearing §6.Z clause 4 cold-start-survival canary, genuinely executed against the genuinely-signed APK.

Broader Challenge suite: NOT run this cycle (scope-limited per task instructions to the mandatory minimum; §6.T.2 resource discipline). `app/src/androidTest/kotlin/lava/app/challenges/` contains 50+ additional Challenge classes not exercised here — remains OWED for a full §6.AE.2 gate matrix.

## Task 4 — lava-api-go real-stack tests using `.env` credentials/config: PASS (7/8), 1 external-site FAIL

```
$ cd lava-api-go && go vet ./...          # clean, no output
$ go test -tags realtrackers ./internal/provider/curated/...
```
Per-package result:
```
ok    .../curated/knaben              6.744s
ok    .../curated/nyaa                7.040s
ok    .../curated/thepiratebay        1.333s
ok    .../curated/tokyotosho          12.909s
ok    .../curated/torrentdownloads    6.427s
ok    .../curated/torrentscsv         0.305s
ok    .../curated/yts                 52.962s
FAIL  .../curated/bitsearch           12.928s
```
Bitsearch failure detail (real assertion, not a skip):
```
=== RUN   TestLive_SearchUbuntuReturnsRealMagnets
    live_realtrackers_test.go:26: live Search: bitsearch: provider: unknown error
--- FAIL: TestLive_SearchUbuntuReturnsRealMagnets (3.20s)
=== RUN   TestLive_QueryActuallyFilters
    live_realtrackers_test.go:51: live Search: bitsearch: provider: unknown error
--- FAIL: TestLive_QueryActuallyFilters (3.23s)
```
This is a genuine outbound network call to the real `bitsearch.to` live site that currently fails (site down/changed/blocking — not diagnosed further, out of this task's scope; no production code was touched). 7 of 8 curated no-auth providers pass live. These providers do not use `.env` tracker credentials (Nyaa/YTS/Knaben/etc. are public, unauthenticated).

Real-Postgres, real-Gin-stack e2e suite (`tests/e2e`, transient podman Postgres):
```
$ go test -v ./tests/e2e/...
--- PASS: TestE2E_AllRoutes (2.70s)
--- PASS: TestE2E_DownloadEmptyCookie_Returns401 (2.67s)
--- PASS: TestE2E_LoginMissingPassword_Returns400_NoUpstream (2.69s)
--- PASS: TestE2E_FakeUpstream_SanityProbe (0.00s)
--- PASS: TestE2E_NoSkipMarkerFile (0.00s)
--- PASS: TestE2E_RouterShape (0.00s)
--- PASS: TestE2E_JackettProvider_DiscoverSearchDownload (0.00s)
PASS
ok    digital.vasic.lava.apigo/tests/e2e    8.076s
```

Contract + integration + parity + full unit suite (`go test ./...`, no `-tags`): **all `ok`**, 0 `FAIL`, across `cmd/...`, `internal/...`, `tests/contract`, `tests/e2e`, `tests/integration`, `tests/parity`, `tests/load`, `tests/scripts`.

**Credential handling:** `RUTRACKER_USERNAME`/`PASSWORD`, `KINOZAL_*`, `NNMCLUB_*`, `RUTOR_*`, `IPTORRENTS_*`, `JACKETT_API_KEY`, `LAVA_FIREBASE_*` keys were confirmed PRESENT in `.env` (key names only, never values, printed to this session). No test in this run's scope (`internal/provider/curated/*`, `tests/e2e`, `tests/contract`, `tests/integration`, `tests/parity`, full unit suite) reads `RUTRACKER_USERNAME`/`PASSWORD` directly by that env-var name — those specific credentials are consumed by QA-bank YAML journeys (`qa/banks/lava-rutracker-journey.yaml`) rather than a Go `_test.go` gated by `-tags realtrackers`/`-tags integration`, so no rutracker-authenticated live test exists to execute in this scope. This gap is recorded honestly rather than papered over.

## Task 5 — This evidence file

Written at `.lava-ci-evidence/2026-09-22-signed-debug-device-verification/real-device-verification.md` (this file), alongside the machine-generated `real-device-verification.json` and `host-preflight.json` and the raw `CZ_API34_Phone/gradle.log`.

## Summary

| Task | Result | Evidence |
|---|---|---|
| 1. Signed debug APK build | PASS | `BUILD SUCCESSFUL`, v1+v2 signature verified, real cert |
| 2. Containerized emulator boot (§6.AH) | PASS (after local image build) | `real-device-verification.json`, boot_seconds=52.7 |
| 3. Install + Challenge00CrashSurvivalTest | PASS | `test_passed: true`, `BUILD SUCCESSFUL in 2m 7s`, real instrumentation run on `sdk_gphone64_x86_64 - 14` |
| 4. lava-api-go real-stack tests | PASS (7/8 live curated providers; e2e/contract/integration/parity/unit all green) | per-package `go test` output above |
| 5. Evidence record | DONE | this file |

**What remains unverified / explicitly out of scope for this task:** release-variant (R8-minified) APK build and test; full §6.AE.2 matrix (API 28/30/latest + tablet form factor); broader Challenge suite beyond Challenge00; a rutracker-credentialed live test (none exists in the current Go test tree to run); Firebase App Distribution; `scripts/tag.sh`. These are explicit follow-up decisions for the orchestrating session.
