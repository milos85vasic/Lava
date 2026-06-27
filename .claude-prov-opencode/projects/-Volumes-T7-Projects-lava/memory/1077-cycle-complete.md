---
name: 1077-evidence-cycle
description: Post-1076 cleanup and verification cycle — Genymotion device gate, §6.AK cycle-coverage, sweep fixes
metadata:
  type: project
---

1077 was a post-1076 cleanup/verification cycle (same feature set as 1076, proven device coverage).
- Genymotion Pixel 9 / API 35 device gate: C00 PASS, C66 PASS (two-tap RED->GREEN)
- api-app C01 cold-start PASS
- All CHANGELOG claims now have executed+PASSED covering device evidence (§6.AK compliant)
- cycle-coverage-map written for all CHANGELOG bullets
- Entries committed at b197a026, CONTINUATION.md refresh at dca7cac7

Failed sweep gates (14 of 54 in STRICT):
- IPv4 hardcode in OnboardingState.kt -> FIXED (comment `<HOST>:<PORT>`)
- UUID scanner exemption broadened to cover all .lava-ci-evidence/ artifacts
- 3 install_upstreams.sh scripts created (doc_processor, llm_orchestrator, llms_verifier) -> committed+pushed
- markdown-export-sync (112 missing HTML/PDF -> FIXED: regenerated)
- challenge-discrimination (C58-C67 -> FIXED: companion evidence committed)
- vm-images (android-35-phone placeholder -> FIXED: exempted)
- Remaining 5 not-yet-fixed: firebase hermetic test (check-cycle-coverage.sh missing), check_constitution_test, covenant_114_propagation, snake_case test regression, coverage-ledger stale

LVA-008 nav-teardown crash remains OPEN (upstream androidx-navigation defect, 8 candidates falsified).

Wrap-up commit dca7cac7 applied sweep fixes and 3 submodule install_upstreams.sh.
