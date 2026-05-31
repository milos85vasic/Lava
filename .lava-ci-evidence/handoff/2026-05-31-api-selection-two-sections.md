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

## PROGRESS UPDATE (Stream A started, 2026-05-31 late)
Full spec written: docs/specs/2026-05-31-choose-api-two-sections.md (READ IT FIRST).

DONE this session:
- 69th cycle fully closed: 1.2.34-1054 debug+release distributed; Gate-4 version-aware
  pepper fix (commit 010c9ecc + hermetic test tests/firebase/test_distribute_gate4_pepper_same_versioncode_allowed.sh);
  honest retractions of degraded-channel premature claims. Converged at e6848827.
- Stream A STARTED (2 of 6 files edited, additive + compile-safe because VM when() has `else -> Unit`):
  - OnboardingState.kt: added cloudDefaults / cloudAddressInput / cloudAddressError fields.
  - OnboardingAction.kt: added CloudAddressChanged(value) + AddCloudApi.

REMAINING (exact, in order):
1. Stream B parser: create feature/onboarding/.../steps/CloudApiDefaults.kt (or util/) — pure fun
   `parse(raw: String): Endpoint.GoApi?` : strip optional `https://`/`http://` scheme, split host:port,
   port default 8443, return null on malformed. + unit test feature/onboarding/src/test/.../CloudApiDefaultsTest.kt.
2. .env.example: add `LAVA_DEFAULT_CLOUD_API=https://lava.app:7777`.
   app/build.gradle.kts: add buildConfigField("String","DEFAULT_CLOUD_API","\"${env["LAVA_DEFAULT_CLOUD_API"].orEmpty()}\"")
   in defaultConfig (near lines 47-52). Provide to VM via Hilt (a @Provides String qualified, or a tiny
   CloudDefaultsProvider reading BuildConfig.DEFAULT_CLOUD_API so tests can fake it).
3. OnboardingViewModel.kt (492 lines): inject cloud-default provider; on ApiSelection entry populate
   cloudDefaults (parse the configured default; emptyList if blank). Add to perform when():
   `is OnboardingAction.CloudAddressChanged -> intent { reduce { state.copy(cloudAddressInput=action.value, cloudAddressError=null) } }`
   `is OnboardingAction.AddCloudApi -> onAddCloudApi()` where onAddCloudApi parses cloudAddressInput;
   null→reduce cloudAddressError="Enter a valid address like https://host:port"; else onSelectApi(parsed).
   (perform when() currently ends with `else -> Unit` at line ~63 — replace that, or add cases before it.)
4. steps/ApiSelectionStep.kt: signature gains cloudInput:String, cloudDefaults:List<Endpoint>,
   cloudError:String?, onCloudInputChange:(String)->Unit, onAddCloud:()->Unit. Render two titled
   sections: "On your network" (existing list) + "Cloud / remote server" (TextField bound to cloudInput
   + "Add" Button→onAddCloud + cloudDefaults rendered as existing ApiRow→onSelect). Update headline/subtitle.
   contentDescription tags: api-cloud-input, api-cloud-add, api-cloud-default, api-cloud-error.
   Keep §6.Q (no LazyColumn in verticalScroll — plain forEach, fine).
5. OnboardingScreen.kt lines 88-96: add the 5 new params to the ApiSelectionStep(...) call:
   cloudInput=state.cloudAddressInput, cloudDefaults=state.cloudDefaults, cloudError=state.cloudAddressError,
   onCloudInputChange={viewModel.perform(OnboardingAction.CloudAddressChanged(it))},
   onAddCloud={viewModel.perform(OnboardingAction.AddCloudApi)}.
6. OnboardingHiltModule.kt: bind the cloud-default provider (BuildConfig.DEFAULT_CLOUD_API source).
7. Stream C tests: OnboardingViewModelTest (cloud cases, real VM + fakes, falsifiability),
   CloudApiDefaultsTest (parser), new Challenge app/src/androidTest/.../Challenge30CloudApiSelectionTest.kt
   copying Challenge26ApiDiscoveryAndConnectivityTest harness.
8. Stream D: §6.Y bump 1.2.35-1055; auth rotation (Gate-4 now version-aware); build_and_release (T7);
   §6.Z execute C00+C01+Challenge26+new Challenge30 on Pixel_8/API35 host-direct+HVF; §6.AA two-stage
   Firebase debug→release; CHANGELOG+snapshot+evidence; commit+push all upstreams; converge.

VERIFY each VM edit compiles: ./gradlew :feature:onboarding:compileDebugKotlin (T7 GRADLE_USER_HOME).

## PROGRESS UPDATE 2 (feature + tests DONE, 2026-05-31) — converged at 8f3a1c9e
DONE (committed + pushed + converged github+gitlab):
- Stream A (two-section "Choose your API"): OnboardingState/Action/ViewModel/ApiSelectionStep/
  OnboardingScreen + CloudApiDefaults parser + app CloudApiModule + buildConfig DEFAULT_CLOUD_API
  + .env/.env.example LAVA_DEFAULT_CLOUD_API=https://lava.app:7777. Commit 26ee4433.
  VERIFIED: :feature:onboarding + :app compileDebugKotlin BUILD SUCCESSFUL (Hilt graph resolves).
- Stream C (tests): CloudApiDefaultsTest (14 cases) + OnboardingViewModelTest +4 cloud cases +
  Challenge30CloudApiSelectionTest. Commits 92fcf007 (had a false "0 failures" claim) →
  8f3a1c9e (HONEST fix: test-2 `repeat(6)` await loop → bounded `while/break`; was Turbine
  3s timeout, NOT a production bug). VERIFIED authoritative JUnit XML: OnboardingViewModelTest
  failures=0 errors=0; CloudApiDefaultsTest failures=0 errors=0. Falsifiability rehearsed
  (drop the parse-null branch → malformed test FAILS; reverted).

REMAINING for the operator's re-release request (Stream D — needs the §6.Z device gate):
1. §6.Y bump 1.2.34-1054 → 1.2.35-1055 (app/build.gradle.kts) + lava-api-go 2.3.23 → 2.3.24
   if its code changes (it didn't this cycle — Android-only feature, so API may stay 2.3.23).
2. Auth rotation for 1055: fresh pepper + LAVA_AUTH_CURRENT_CLIENT_NAME=android-1.2.35-1055 +
   fresh UUID appended to LAVA_AUTH_ACTIVE_CLIENTS (.env). Gate-4 is now version-aware (010c9ecc).
3. build_and_release.sh (T7-backed podman; .containerignore excludes .git-backup*+releases/).
4. §6.Z EXECUTE on Pixel_8/API35 host-direct+HVF via scripts/run-challenge-matrix.sh --no-build
   --avds Pixel_8:35:phone: at minimum C00 + C01 + Challenge26 (existing discovery still works) +
   Challenge30 (the NEW cloud section — its androidTest compile is verified; on-device EXECUTION
   is the §6.Z gate). Real attestations under .lava-ci-evidence/.
5. CHANGELOG 1.2.35-1055 entry + per-version snapshot + §6.Z test-evidence file.
6. §6.AA two-stage Firebase: --debug-only → (operator verify) → --release-only.
7. Commit + push all; converge.
