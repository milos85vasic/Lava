# HANDOFF — "Choose your API" two-section feature (operator request 2026-05-31)

## Status of the 69th cycle (DONE, converged at git 21c22f42 on github+gitlab)
- 1.2.34-1054 debug (Firebase 0f9a72d53suhg) + release (3kqlekav93b1o) BOTH distributed.
- Gate-4 version-aware pepper fix committed (010c9ecc) + hermetic test passing.
- last-version-debug=1054, last-version-release=1054.
- T7 fast-storage relocation done (docs/ops/T7-fast-storage.md); .containerignore added
  (excludes .git-backup* 4.5G + releases/) — image build root-cause fixed.
- KNOWN-OWED (separate, pre-existing): lava-api-go OCI image + compose boot for live testing
  was NOT completed (no image; podman-compose needs the image). Not blocking APK distribute.

## NEW operator request (NOT yet started)
On the onboarding "Choose your API" screen (feature/onboarding ApiSelectionStep.kt +
OnboardingViewModel.kt + OnboardingScreen.kt), make TWO sections:
1. **Local discovery** (the existing one — mDNS-discovered API instances on the LAN).
2. **Cloud / remote server** (NEW):
   - user can ADD a new address WITH port manually, AND
   - pick from pre-installed default options. We have none yet → add ONE placeholder:
     **https://lava.app:7777**
- Update all titles + descriptions on this screen accordingly.
- UX: user continues with exactly ONE chosen API variant + ONE selected instance.
- Cover with tests: E2E + Full Automation producing REAL evidence (no false positives, no bluff).
- Commit + push all submodules + main to all upstreams.
- After tests pass: re-release new version (§6.Y bump 1.2.34-1054 → 1.2.35-1055) via Firebase,
  BOTH debug + release. Per §6.Z: Compose Challenge tests must be EXECUTED green on the exact
  artifact first (the repepper pattern: build → §6.Z C00+C01+the new ApiSelection Challenge on
  Pixel_8/API35 host-direct+HVF → debug distribute → release distribute). Pepper rotation per
  §6.AA (new versionCode = new pepper + new client name android-1.2.35-1055 in .env).

## Key files
- feature/onboarding/src/main/kotlin/lava/onboarding/ApiSelectionStep.kt  (the screen UI)
- feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingViewModel.kt (state/logic)
- feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingScreen.kt (step host)
- Existing Challenge: app/src/androidTest/.../challenges/Challenge26ApiDiscoveryAndConnectivity*
  (covers the current single-section discovery flow; extend or add a sibling for the cloud section)
- mDNS discovery service: core/data LocalNetworkDiscoveryService (consumed by the step)
- Connectivity probe: the step runs an HTTPS /health probe on selection.

## Bash channel caveat (this host)
The Bash channel garbles/cancels large parallel batches (exit 144) and the `sync` alias
fires the push hook (noise). Work in SMALL single calls; route output to /tmp files and
Read them; verify git HEAD + last-version pointers with `git ls-remote` before trusting.
Restore byte-churn before commits: git checkout -- '*.pdf' '*.html' docs/workable_items.db
constitution ; rm -rf constitution/scripts/workable-items/bin
