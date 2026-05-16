## Lava-Android-1.2.17-1037 / Lava-API-Go-2.3.6-2306 — 2026-05-13 (§6.X-debt PARTIAL CLOSE + §6.L 21st-23rd invocations)

**Previous published:** Lava-Android-1.2.16-1036 / Lava-API-Go-2.3.5-2305

### Constitutional
- **§6.X added (TWENTY-FIRST §6.L invocation).** Container-Submodule Emulator
  Wiring Mandate — every Android emulator the project depends on for testing
  MUST execute its emulator process INSIDE a podman/docker container managed
  by `submodules/containers/`. Propagated to 52 docs (root × 2 + 16 submodules
  × 3 + lava-api-go × 3). Mechanical enforcement via
  `scripts/check-constitution.sh` (inheritance presence checks).
- **§6.X-debt PARTIAL CLOSE (TWENTY-SECOND §6.L invocation).** Containers
  submodule commit `562069e7` ships:
  - `pkg/emulator/containerized.go` — Containerized type implementing the
    Emulator interface via podman/docker `run -d --device /dev/kvm`.
  - `pkg/emulator/containerized_test.go` — 9 test functions / 12 sub-cases
    with Bluff-Audit rehearsal (mutating `"--device", "/dev/kvm"` out of
    Boot args produces "captured args missing --device /dev/kvm").
  - `pkg/emulator/Containerfile` + `entrypoint.sh` — Android emulator image
    recipe (Linux x86_64 buildable; darwin/arm64 blocked per §6.V-debt).
  - `cmd/emulator-matrix/main.go` — `--runner=host-direct|containerized`
    flag + `--container-image` + `--container-runtime`.
- **§6.X runtime checks (a) + (b) activated** in Lava parent
  `scripts/check-constitution.sh`. Both falsifiability-rehearsed (`mv` /
  `sed` mutations produce "MISSING 6.X runtime check (...)").
- **§6.L invocation count: TWENTY → TWENTY-THREE.** Operator invoked the
  Anti-Bluff Functional Reality Mandate three times in this session window.
  Verbatim restatement of the no-bluff covenant propagated across all 52
  constitutional docs.

### Build infrastructure (not user-visible)
- Submodule pin: `submodules/containers` 8197c222 → 562069e7+ (full
  §6.X-debt close set).
- `scripts/check-constitution.sh` gains 5 new lines + 2 new runtime
  checks; the existing inheritance checks are reorganized.

### What's NOT in this version
- **No Firebase distribute.** Per operator's 23rd §6.L invocation: rebuild
  + redistribute requires real `app/google-services.json` + real
  `LAVA_FIREBASE_TOKEN`. Both are placeholders in this commit. Distributing
  with stub secrets produces signed-but-broken APKs (Firebase init crashes
  on `LavaApplication.onCreate`) — that's the canonical §6.J "tests green,
  feature broken for users" bluff this mandate exists to prevent.
- **No §6.X gate run.** The Containers/cmd/emulator-matrix `--runner=
  containerized` path requires Linux x86_64 with `/dev/kvm`; this build
  is on darwin/arm64. Real-stack boot test recorded as honestly outstanding
  per §6.V-debt incident JSON.

### Operator inputs needed for the next distribute
1. Real `app/google-services.json` (not the stub).
2. Real `LAVA_FIREBASE_TOKEN` in `.env`.
3. Real `RUTRACKER_USERNAME` + `RUTRACKER_PASSWORD` (for C02 verification).
4. Real `KINOZAL_USERNAME`/`KINOZAL_PASSWORD` (for C09).
5. Real `NNMCLUB_USERNAME`/`NNMCLUB_PASSWORD` (for C10).
6. Linux x86_64 gate host (or remote runner) for the §6.X attestation
   producing `runner: containerized` rows.

# Changelog
