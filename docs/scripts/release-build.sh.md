# `scripts/release-build.sh` — User Guide

**Last verified:** 2026-11-20 (§11.4.32 verify-all sweep, CM-SCRIPT-DOCS-SYNC gate fix)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/release-build.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
release-build.sh - Build script for Lava components
Used by distributed-build to build individual components inside builder containers

Arguments:
  --local              Run in local mode (inside builder container)
  --component NAME     Component to build (app, api-app, lava-api-go)
  --build-type TYPE    Build type (debug, release) - default: release
  --version VERSION    Version string for artifacts
  --skip-tests         Skip test execution
  --force              Force rebuild (always passed by executor)

This is the executor script invoked by the Containers submodule's
distributed-build system (cmd/distributed-build) to build individual
components. It can run both on the host (--local=false, default) and
inside the builder container (--local=true).

§6.K Builds-Inside-Containers Mandate: This script is the workhorse that
runs inside the builder containers provisioned by the Containers submodule.
The host-facing wrapper is local-container-build.sh.

Components:
  - app        → digital.vasic.lava.client (Android APK via Gradle)
  - api-app    → digital.vasic.lava.api (Android APK via Gradle)
  - lava-api-go → lava-api-go binary + Docker image (Go + Podman/Docker)

Scope:
  - Android: runs ./gradlew assembleDebug|Release (JDK 17 via JAVA_HOME)
  - Go: runs go build + podman/docker build (Go 1.25)

Acceptance: gate runs in verify-all-constitution-rules.sh (CM-SCRIPT-DOCS-SYNC).
```

## Usage

```bash
# Host mode (default) - build app release
./scripts/release-build.sh --component app --version 1.2.0 --build-type release

# Host mode - build api-app debug
./scripts/release-build.sh --component api-app --version 1.2.0 --build-type debug

# Host mode - build lava-api-go
./scripts/release-build.sh --component lava-api-go --version 2.0.8

# Local mode (inside builder container) - invoked by distributed-build
./scripts/release-build.sh --local --component app --version 1.2.0 --build-type release --force

# Skip tests
./scripts/release-build.sh --component app --version 1.2.0 --skip-tests
```

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/release-build.sh` — the script itself
- `scripts/local-container-build.sh` — host-facing wrapper that invokes this
- `lava-api-go/scripts/ci.sh` — Go CI gate
- `scripts/ci.sh` — Android CI gate
- `submodules/containers/cmd/distributed-build/` — Containers submodule build orchestrator
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.K (Builds-Inside-Containers Mandate)