## LVA-1 — Deflake CredentialsViewModelTest > select provider updates selectedProvider

**Status:** Fixed (→ Fixed.md)
**Type:** Bug
**Evidence:** feature/credentials/src/test/kotlin/lava/feature/credentials/CredentialsViewModelTest.kt
**Severity:** P1

67th-cycle full-suite flaky test (fixed-awaitState-count vs Room Flow .first() off the StandardTestDispatcher). Fixed in the 68th cycle (Commit 1) via bounded await-until-selectedProvider loop; falsifiability-rehearsed. **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-flaky-credentialsviewmodeltest.json

## LVA-2 — §6.X-debt darwin/arm64 emulator-acceleration sub-debt

**Status:** Completed (→ Fixed.md)
**Type:** Task
**Evidence:** .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json
**Severity:** P1

Per-OS emulator acceleration (Containers c1871138 + 6aff7ea8): macOS gate runner is host-direct+HVF. PROVEN by C00 cold-start canary + full 37-class Challenge suite on Pixel_8/API35 (43 pass / 3 credential-skip / 0 fail). RESOLVED 2026-05-20. **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json

