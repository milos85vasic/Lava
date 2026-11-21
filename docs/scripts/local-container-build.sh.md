# `scripts/local-container-build.sh` — User Guide

**Last verified:** 2026-11-20 (§11.4.32 verify-all sweep, CM-SCRIPT-DOCS-SYNC gate fix)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/local-container-build.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/local-container-build.sh - Build Lava components inside builder containers using Podman
Implements §6.K Builds-Inside-Containers mandate: release-artifact builds run in container-bound path

Usage:
  ./scripts/local-container-build.sh --component <app|api-app|lava-api-go> --version <version> [--build-type <debug|release>] [--skip-tests]

§6.K Builds-Inside-Containers Mandate (added 2026-05-06 from HelixCode CONST-034):
Every release-artifact build (:app:assembleDebug, :app:assembleRelease,
:proxy:buildFatJar, lava-api-go static binary, OCI image builds) MUST run
inside the project's container-bound build path, anchored on
vasic-digital/Containers's build orchestration (cmd/distributed-build +
pkg/distribution + pkg/runtime). Local incremental dev builds on the host
are permitted for iteration; the constitutional gate, the release-artifact
build, and the build whose output goes through the emulator matrix (§6.I)
MUST go through Containers.

This script is the transitional thin glue until §6.K-debt closes.
It uses Podman to run builds inside builder containers (lava-android-builder,
lava-go-builder) rather than the full Containers submodule orchestration.

Components:
  - app        → digital.vasic.lava.client (Android APK via Gradle)
  - api-app    → digital.vasic.lava.api (Android APK via Gradle)
  - lava-api-go → lava-api-go binary + Docker image (Go + Podman)

Builder Images (must be pre-built):
  - localhost/lava-android-builder:latest (JDK 17, Android SDK, Gradle)
  - localhost/lava-go-builder:latest (Go 1.25)

Scope:
  - Android: runs ./gradlew assembleDebug|Release inside android-builder container
  - Go: runs go build + podman build inside go-builder container

Acceptance: gate runs in verify-all-constitution-rules.sh (CM-SCRIPT-DOCS-SYNC).
```

## Usage

```bash
# Build release APK for app
./scripts/local-container-build.sh --component app --version 1.2.0 --build-type release

# Build debug APK for api-app
./scripts/local-container-build.sh --component api-app --version 1.2.0 --build-type debug

# Build lava-api-go binary + Docker image
./scripts/local-container-build.sh --component lava-api-go --version 2.0.8

# Skip tests
./scripts/local-container-build.sh --component app --version 1.2.0 --skip-tests
```

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/local-container-build.sh` — the script itself
- `scripts/release-build.sh` — the distributed-build executor script
- `lava-api-go/scripts/ci.sh` — Go CI gate
- `scripts/ci.sh` — Android CI gate
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.K (Builds-Inside-Containers Mandate)