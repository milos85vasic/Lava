# `scripts/run-vm-signing-matrix.sh` — User Guide

**Last verified:** 2026-05-16 (Phase 6a + 6b §11.4.29 snake_case migration: scripts/run-vm-signing-matrix.sh path-references updated from `Submodules/` to `submodules/`)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/run-vm-signing-matrix.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/run-vm-signing-matrix.sh — cross-arch signing matrix wrapper.
Drives cmd/vm-matrix against (alpine,debian,fedora) × (x86_64,aarch64,riscv64)
= 9 configs. Each VM signs the same input APK with the same keystore.
Post-processing computes per-row signing_match by comparing the
SHA-256 of /tmp/signed.apk to the alpine-3.20-x86_64 KVM reference.

Bluff vector this catches: JCA-provider divergence — the same JRE
producing different signing bytes across architectures. If signing
bytes diverge, an APK signed on architecture A might validate but
not match what architecture B produces, breaking reproducible-build
guarantees and silently exposing keystore-binding inconsistencies.

The matrix RUN is the constitutional gate; this wrapper exists so
Lava-side CI invocation is uniform (a single bash entry-point) and
the post-processing block (the divergence detector) is unit-testable
via tests/vm-signing/test_*.sh fixtures.

Pre-requisites the operator MUST satisfy before running:
  - tests/vm-signing/sample.apk exists (operator supplies; intentionally
    NOT shipped — anti-bluff: don't pretend a stub APK is signable)
  - proxy/build/libs/app.jar exists (./gradlew :proxy:buildFatJar)
  - keystores/upload.keystore.p12 exists (Lava signing keystore)
  - tools/lava-containers/vm-images.json has REAL SHA-256 + size for
    each of the 9 qcow2 entries (placeholder zeros reject in pkg/cache)

Exit codes:
  0 — all 9 rows produced byte-equivalent signed APKs
  1 — at least one row diverged (JCA bluff caught) OR a row's
      signing-output.json is missing
  2 — configuration error (missing inputs, missing tooling)
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/run-vm-signing-matrix.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
