# Distribution Changelog — Lava-Android-1.3.17-1088

## Version
- **versionName**: 1.3.17
- **versionCode**: 1088
- **Tag**: Lava-Android-1.3.17-1088

## Changes Since 1.3.16-1087

### Critical Fixes
- **Containers submodule timeout fix** (commit 2828578): Fixed local executor 30-minute hardcoded timeout in `internal/buildpkg/local_executor.go`. Added `buildTimeout` field and `SetBuildTimeout()` method. Wired through `cmd/distributed-build/main.go` to propagate `--timeout` flag to local builds.

### Infrastructure
- Parent repo submodule pointer updated (c323d63b) to include Containers fix
- All mirrors pushed: github.com/milos85vasic/Lava, gitlab.com/milos85vasic/lava, github.com/vasic-digital/Containers, gitlab.com/vasic-digital/containers

### Constitutional Compliance
- §11.4.32 verify-all-constitution-rules: 59/59 gates PASS
- §6.R No-Hardcoding Mandate: Coverage ledger regenerated, zero hardcoded UUIDs
- §6.AC Non-Fatal Telemetry: Scan passes
- §6.AB Anti-Bluff Test-Suite: Discrimination checks pass
- §6.AE Comprehensive Challenge Coverage: Scan passes

### Build System
- All 3 components built locally via podman (distributed-build):
  - app (Android client)
  - api-app (on-device API)
  - lava-api-go (Go backend)
- Build timeout increased to 60 minutes via `--timeout 60` flag

## Known Issues
- Challenge Tests: 3 of 8 core C1-C8 tests have issues (see real-device-verification.md)
  - Challenge04: Test isolation failure
  - Challenge05/06: Network timeouts to real trackers
  - Challenge02: Skipped without credentials

## Distribution Target
- Firebase App Distribution (debug)
- Google Play Store (release) — pending Challenge Test remediation
