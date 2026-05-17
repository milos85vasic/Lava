# Lava — Work Continuation Index

**Purpose:** this single file is the source-of-truth for resuming the
project's work across any CLI session. A fresh agent reads this file
first, locates the active phase, and continues from there. Everything
ahead of HEAD is recorded; everything behind HEAD is in `git log`.

**Maintenance:** every release tag, every phase completion, every
operator directive that changes scope MUST update this file in the
same commit so the index stays trustworthy. Stale state in this file
is itself a §6.J spirit issue — the file claims a guarantee, the
repo has drifted, the agent acts on the claim.

> **Last updated:** 2026-05-17 (evening), **Sweep findings tier-A (Findings #1, #4-#10) CLOSED on branch `sweep-findings-tier-A-2026-05-17` + Bug 1 FULL REFACTOR + 1.2.25-1045 DISTRIBUTED + §11.4.10.A constitution clause + §6.L 58th invocation**
>
> **Sweep tier-A closure (2026-05-17 evening, branch `sweep-findings-tier-A-2026-05-17`):** 8 of the 10 comprehensive-sweep findings closed in a single coordinated commit (Findings #2 + #3 already closed by Bug 2 cascade + Bug 3 fix in prior cycles). All fixes falsifiability-rehearsed per §6.J / Seventh Law clause 1 (mutation applied → test fails with clear message → mutation reverted → test passes). Bluff-Audit stamps recorded in commit body.
>   - **Finding #1 (P0) — ToggleAnonymous persistence**: `feature/provider_config/.../ProviderConfigViewModel.kt` now persists via new `ProviderConfigRepository.setUseAnonymous(...)` → Room column `use_anonymous` (Migration 10→11 + schema 11.json). Switch state survives process restart.
>   - **Finding #4 (P1) — `LoginViewModel.serviceUnavailable` retype clear**: cleared in `validateUsername`/`validatePassword`/`validateCaptcha`/`onReloadCaptchaClick`/`onSubmitClick` reduces.
>   - **Finding #5 (P1) — `LoginViewModel.ServiceUnavailable` stale-captcha clear**: branch now sets `captcha = null, captchaInput = Initial` so the rendered challenge image doesn't lie when the sid expires.
>   - **Finding #6 (P1) — `ProviderLoginViewModel.serviceUnavailable` clear across selectProvider/backToProviders + retype**: symmetric fix to Findings #4/#5 on the multi-provider login surface.
>   - **Finding #7 (P1) — `OnboardingViewModel.onTestAndContinue` no more misleading "Invalid credentials"**: distinguishes `loginResult == null` (tracker has no auth path) from `loginResult.state != Authenticated` (real auth failure). Null path now treated as anonymous → switchTracker + advance.
>   - **Finding #8 (P1) — `OnboardingViewModel.loadProviders` excludes cloned synthetic trackers**: filters by syntheticId membership in `cloned_provider`. Clones remain configurable via Provider Config.
>   - **Finding #9 (P2) — `MainActivity` onboardingComplete re-read**: `PreferencesStorage.observeOnboardingComplete()` new Flow API (SharedPreferences-listener-backed). Two parallel `lifecycleScope.launch { repeatOnLifecycle { collect } }` blocks (one for theme, one for onboarding). Welcome screen re-appears if settings flip onboardingComplete back to false at runtime.
>   - **Finding #10 (P2) — `ToggleSync` first-tap race**: reads `toggleDao.get(providerId)?.enabled` synchronously instead of `state.syncEnabled`. First tap before observeAll() emit no longer silently flips the wrong direction.
>   - **Tests added** (5 new): `LoginViewModelTest` (3 cases: Findings #4 username, #4 password, #5 captcha), `ProviderConfigViewModelTest` (2 cases: Findings #1 + #10), Finding #7 + #8 cases added to existing `OnboardingViewModelTest`, Finding #6 case added to existing `ProviderLoginViewModelTest`.
>   - **Schema migration**: Room version bumped 10 → 11 (`MIGRATION_10_11` adds `use_anonymous INTEGER NOT NULL DEFAULT 0` to `provider_configs`). `core/database/schemas/lava.database.AppDatabase/11.json` exported by KSP.
>   - **All builds + tests green**: `:core:database` / `:core:credentials` / `:feature:provider_config` / `:feature:login` / `:feature:onboarding` / `:app:assembleDebug` / `:app:compileDebugAndroidTestKotlin`.
>
> **1.2.25-1045 distribute cycle (2026-05-17 afternoon):**
>   - Stage-1 debug Firebase release ID `1lfjqc1nnhuio` on `digital.vasic.lava.client.dev`
>   - Stage-2 release Firebase release ID `7p0h5j70eckqg` on `digital.vasic.lava.client` (production)
>   - 15/15 Compose UI Challenge test cases PASS on Pixel_8/API35 host-direct AVD (14 classes including new C36)
>   - HelixConstitution submodule advanced to `ca7c7d7` (§11.4.10.A Pre-store credential leak audit + upstream §11.4.37/38/39) — 5-mirror converged
>   - §6.L counter 57 → 58
>
> **Comprehensive UI/UX/core sweep findings (2026-05-17 evening, branch `comprehensive-sweep-2026-05-17` merged at `c3b8bf5c`):** 10 findings @ `docs/sweeps/2026-05-17-comprehensive-uiux-core-sweep.md`. Top P0s for 1.2.26:
>   1. `ProviderConfigViewModel.ToggleAnonymous` never persists — anonymous switch reverts on restart (`feature/provider_config/.../ProviderConfigViewModel.kt:82-84`)
>   2. `SearchResultContent` has no `Error` variant — Bug 2 root cause CONFIRMED ("all providers failed" looks like "0 results") (`feature/search_result/.../SearchPageState.kt:30-54`)
>   3. `SearchInputViewModel.availableProviders` is hardcoded 4-element list — SDK clones + new trackers invisible to search (`feature/search_input/.../SearchInputViewModel.kt:48-53`)
> P1 cluster (4 findings on login banner/captcha staleness + onboarding misleading cred message + clones-in-onboarding) + P2 cluster (MainActivity onboarding re-read + ToggleSync race). See sweep doc for full details + per-finding anti-bluff classification.
>
> **OPERATOR ACTION still REQUIRED:**
>   - §6.H historical credential leak: rotate the RuTracker password (credentials remain valid until rotated; in-tree redaction does NOT purge git history per §6.T.3)
>   - Bug 2 live-device log capture: install 1.2.25, attempt anonymous-only search, `adb logcat | grep -iE "error|exception|search"` to confirm sweep finding #2 root cause
>
> **Bug 1 FULL REFACTOR LANDED — `AuthResponseDto.ServiceUnavailable` sealed variant propagates through 8-layer chain; Challenge C36 + 3 unit-layer falsifiability-rehearsed tests; OpenAPI spec + Go bindings regenerated; debug APK builds clean; feature/login + core/tracker/rutracker tests green**
>
> **Bug 1 cycle (2026-05-17):**
>   - Operator's §6.L 57th invocation forensic anchor "Cant login to RuTracker with valid credentials" closed. Partial fix in commit `17ceabcb` (stderr marker line) is now superseded by the full-fix variant: the SDK catch path returns `AuthResponseDto.ServiceUnavailable(reason)` instead of bluffing `WrongCredits(null)`.
>   - **8-layer propagation chain**: `AuthResponseDto` (+ variant) → `AuthMapper` → `AuthState.ServiceUnavailable(reason)` → `RuTrackerDtoMappers` (reverse) → `AuthServiceImpl` → `AuthResult.ServiceUnavailable` → `LoginUseCase` → `LoginResultMapper` → `ProviderLoginViewModel` (recordWarning telemetry per §6.AC + state field) → `ProviderLoginState.serviceUnavailable` → `ProviderLoginScreen` (renders "Service unavailable. Please try again later. (reason)" banner with `R.string.provider_login_service_unavailable` + `ServiceUnavailableTextTestTag`).
>   - Same wiring landed in legacy `LoginViewModel` + `LoginState.serviceUnavailable` for the single-tracker path.
>   - **OpenAPI spec**: `lava-api-go/api/openapi.yaml` gains `AuthResponseDtoServiceUnavailable` under `oneOf` + discriminator mapping. Go bindings regenerated via `scripts/generate.sh`; both `internal/gen/server/api.gen.go` + `internal/gen/client/api.gen.go` updated. Note: the Go-side rutracker scraper does NOT emit the new variant today — its login handler still returns Success / WrongCredits / CaptchaRequired only; the parity test holds. The variant exists in the spec so the Android Kotlin SDK wire-shape stays compatible with future Go-side adoption.
>   - **Tests added** (3 new + 1 rewritten):
>       - `core/tracker/rutracker/src/test/kotlin/lava/tracker/rutracker/mapper/AuthMapperTest.kt` — added 2 tests for ServiceUnavailable forward mapping (with + without captcha).
>       - `core/tracker/rutracker/src/test/kotlin/lava/tracker/rutracker/mapper/RuTrackerDtoMappersTest.kt` — added round-trip test asserting reverse-mapper preserves reason.
>       - `core/tracker/rutracker/src/test/kotlin/lava/tracker/rutracker/impl/RuTrackerNetworkApiLoginUnknownRegressionTest.kt` — REWRITTEN: pre-fix asserted WrongCredits fallback (the §6.J bluff); now asserts ServiceUnavailable with reason carrying throwable class name. Same Crashlytics `a29412cf6566d0a71b06df416610be57` regression-immunity coverage, stronger discrimination.
>       - `feature/login/src/test/kotlin/lava/login/LoginResultMapperTest.kt` — NEW file. 6 tests; load-bearing assertion: `service unavailable propagates as ServiceUnavailable with reason` + explicit anti-bluff `service unavailable does NOT silently collapse to WrongCredits`.
>       - `feature/login/src/test/kotlin/lava/login/ProviderLoginViewModelTest.kt` — added VM test: `service unavailable shows banner does NOT mark creds Invalid does NOT signal authorized` exercises the §6.J anti-bluff contract end-to-end at the VM layer (real ViewModel + real ProviderCredentialManager + real Room + real LavaTrackerSdk wired with FakeTrackerClient whose `loginProvider` returns `AuthState.ServiceUnavailable(reason)`).
>       - `app/src/androidTest/kotlin/lava/app/challenges/Challenge36LoginServiceUnavailableShowsAccurateMessageTest.kt` — NEW Challenge Test under `// covers-feature: login`. Source-written + compiles green on darwin/arm64 (verified via `./gradlew :app:compileDebugAndroidTestKotlin`); EXECUTION against the §6.X-mounted gate-host is OWED.
>   - **Build verification**: `./gradlew :core:tracker:rutracker:test` PASS, `./gradlew :feature:login:test` PASS, `./gradlew :app:assembleDebug` PASS, `./gradlew :app:compileDebugAndroidTestKotlin` PASS. Spotless applied to all edited files.
>   - **§6.Y note**: this is a follow-up commit to 1.2.24-1044 — versionCode is NOT bumped here; the next distribute (1.2.25-1045) will pick up Bug 1 full fix + Bug 2 deferred investigation closure once that lands.
>   - **§6.X-debt**: C36 + the per-AVD matrix attestation row is OWED to a Linux x86_64 + KVM gate-host; the JVM-layer falsifiability rehearsals (4 tests) carry the gate until then.
>
> **Previous cycle context (preserved verbatim):**
>
> **Last updated:** 2026-05-16, **Phase 4-C-2 (pkg/detector adapter) + Phase 4-C-3 (pkg/ticket adapter) — BOTH WORKTREES LANDED + green; parent-cycle close + HelixQA SHA bump + coverage-ledger regen owed at meta-merge**
> (constitutional-plumbing-only; no user-visible feature change; no Firebase
> distribute since 1.2.22-1042 still serves the user-visible surface). Cycle
> spans commits `4def2da7` → `0c87b6ae` (33 commits since plan landing
> `832f739e`). Final state at HEAD `0c87b6ae`:
>
> **Major deliverables this cycle:**
>   - The 12-clause constitution-compliance plan (`docs/plans/2026-05-15-constitution-compliance.md`) executed end-to-end across 10 phases. Plan + every phase's deliverable: see commit log between `832f739e..0c87b6ae`.
>   - HelixQA submodule incorporated (`submodules/helixqa` at upstream `b13ba7c0`) — see Phase 4 below.
>   - 40-gate verify-all sweep wrapper at full STRICT mode after Phase 7's coverage-ledger STRICT-flip.
>   - 17 own-org submodules now have helix-deps.yaml + install_upstreams.sh (16 vasic-digital + 1 HelixDevelopment HelixQA); 0 waivers in STRICT mode.
>   - §6.L counter advanced 36 → 52 across 17 back-to-back restatements (longest sequence in project history); 53rd in-flight per the dispatch that triggered this CONTINUATION.md refresh task.
>
> **Phase-by-phase status (constitution-compliance plan):**
>   - ✅ **Phase 1** (§11.4.32 enforcement engine) — `4def2da7`. `scripts/verify-all-constitution-rules.sh` + meta-test + ci.sh wiring.
>   - ✅ **Phase 2** (§11.4.30 .gitignore audit gate) — `037389f5`. `scripts/check-gitignore-coverage.sh` + 16 new .gitignore files + sweep wiring + hermetic test.
>   - ✅ **Phase 3** (§11.4.31 helix-deps.yaml manifest gate) — `43345c3e` (gate) + `410af7ec` + `bcba3a19` (16/16 per-submodule manifests landed + Auth pin bump).
>   - ✅ **Phase 3-debt** CLOSED — all 16 vasic-digital submodules at pin advance with helix-deps.yaml present.
>   - ✅ **Phase 4** (§11.4.27 HelixQA + 100% test-type coverage) — `aa0db6bd`. HelixQA adopted as `submodules/helixqa` at upstream HEAD; `HELIX_DEV_OWNED` exemption pattern added to mirror-mandate scanners.
>   - ✅ **Phase 4 follow-up A** (Option 1 design) — `a61bd3d8`. Integration design at `docs/plans/2026-05-16-helixqa-integration-design.md` (Option 1 shell-wiring recommended; Options 2/3 deferred).
>   - ✅ **Phase 4 follow-up A executed** (shell-level wiring) — `1b66d192` + merge `d94ade0d`. 11 HelixQA Challenge scripts wrapped via `scripts/run-helixqa-challenges.sh` + `scripts/run-challenge-matrix.sh --include-helixqa` opt-in flag.
>   - ✅ **Phase 4 follow-up B** (4 open-question resolutions for Option 1) — `281780d7` + merge `84d871a5`. Runner-mode flag (`--runner=host|containerized`), toolchain-precondition gate (`HELIXQA_TOOLCHAIN_MAP`), evidence-dir env-var override, `HELIXQA_W_EXCLUSIONS` array consuming the §6.W audit doc. 11 hermetic fixtures in `tests/check-constitution/test_helixqa_wiring.sh`.
>   - 📐 **Phase 4 follow-up C** (DESIGN-ONLY) — `41b81359` + merge `be1ca3d8`. HelixQA Go-package linking design at `docs/plans/2026-05-16-helixqa-go-package-linking-design.md` (770-line Option 2 proposal: per-package adapters + 4-cycle rollout 4-C-1 `pkg/evidence` → 4-C-4 `pkg/navigator`+`pkg/validator`). **Operator-blocked on 10 open questions** (§G of the design); implementation cycle NOT started.
>   - ✅ **Phase 4-debt** CLOSED — `858ffb3e` (2026-05-16). HelixQA upstream PR `b13ba7c` landed `helix-deps.yaml` + `install_upstreams.sh` at the HelixQA repo root → Lava parent removed HelixQA from `HELIX_DEPS_WAIVERS` + `INSTALL_UPSTREAMS_WAIVERS`. 17/17 own-org submodules satisfy §11.4.31 + §11.4.35 + §11.4.36 in fully STRICT mode with **zero waivers**.
>   - ✅ **Phase 5** (§11.4.28 nested own-org submodule audit) — `bbca3a78` (gate) + `410af7ec` (STRICT flip after Challenges/.gitmodules removal via the github cascade merge).
>   - ✅ **Phase 5-debt** CLOSED — `410af7ec`. Scanner reports 0 violations in STRICT mode; Panoptic is no longer nested via Challenges.
>   - ✅ **Phase 6** (§11.4.29 lowercase snake_case naming) — `322f2081` (plan landing) + Phase 6a + 6b execution this cycle. Operator's 8 Q answers: defer Phase 6f upstream rename (Lava-side only); `helixqa` (single-token); `http3` (single-token); `ratelimiter` (single-token); Go `cmd/` hyphens exempt; ordering Mdns(low) → Containers(high) last; same defer for Tracker-SDK upstream; Phase 6a authorized to run in parallel with Phase 4-C-1. Execution: `Submodules/` → `submodules/` parent rename + 17 child renames (Auth→auth, Cache→cache, Challenges→challenges, Concurrency→concurrency, Config→config, Containers→containers, Database→database, Discovery→discovery, HelixQA→helixqa, HTTP3→http3, Mdns→mdns, Middleware→middleware, Observability→observability, RateLimiter→ratelimiter, Recovery→recovery, Security→security, Tracker-SDK→tracker_sdk) + 139 referencing files updated + `.gitmodules` rewritten + Phase 6f upstream-rename execution plan at `docs/plans/2026-05-16-phase6f-upstream-rename-execution.md` (DEFERRED per Q1). Audit confirms 0 stale `Submodules/X` references in 16/17 names (Tracker-SDK has 1 ref in a historical bluff-hunt JSON narrative quote — exempt forensic anchor).
>   - ✅ **Phase 7** (§11.4.25 coverage ledger) — `21dee741` + merge `c35af27c` (generator + verifier + 58-row baseline + 6 hermetic fixtures + sweep wiring, advisory at first) → `76507ca0` + merge `20b3fd36` (waiver backfill: 0 covered / 20 partial / 38 gap → 48 covered / 10 partial / 0 gap) → `0c87b6ae` (STRICT-flip in sweep wrapper).
>   - ✅ **Phase 7-debt** CLOSED — `0c87b6ae`. Sweep wrapper now invokes `check-coverage-ledger.sh --strict` (was `--advisory`); gate hard-fails on stale/missing rows.
>   - ✅ **Phase 8** (§11.4.35 canonical-root + §11.4.36 install_upstreams) — `d95be689` (gate) + `410af7ec` (STRICT-flip after 10 install_upstreams scripts landed across owned submodules).
>   - ✅ **Phase 8-debt** CLOSED — `858ffb3e`. HelixQA's upstream `install_upstreams.sh` is the final hold-out; scanner reports 17/17 install_upstreams present in STRICT mode.
>   - ✅ **Phase 9 Path B** (§11.4.33 + §11.4.34 equivalence-mapping) — `055fbcbe`. §6.AD.3 amended: Lava's CONTINUATION + closure-logs + sixth-law-incidents satisfy the type-aware-closure + reopened-source-attribution semantics; no parallel Issues/Fixed tracker; equivalence is binding. Gates-index gets 2 new EQUIVALENCE-MAPPED rows (CM-CLOSURE-STATUS-VOCAB-COMPLIANCE, CM-REOPENED-SOURCE-ATTRIBUTION).
>
> **Verify-all sweep result at HEAD `0c87b6ae`:** **40/40 PASS in fully STRICT mode** (post-STRICT-flip; the prior 07:00:56Z attestation showed 39/40 only because the coverage-ledger sha drifted between commit and sweep — the re-run at HEAD is clean). Attestation directory: `.lava-ci-evidence/verify-all/<UTC-timestamp>.json`.
>
> **CM-* gates inventory at HEAD:** 24 HelixConstitution `CM-*` gates tracked at `docs/helix-constitution-gates.md`; ~16 wired + ~8 paper-only (mostly Issues/Fixed-tracker-dependent — equivalence-mapped per §6.AD.3). 14 Lava-side anti-bluff gates also active.
>
> **All 33 session commits §6.C-converged on GitHub + GitLab.** Pre-push Checks 1-9 active throughout.
>
> **Prior:** 2026-05-14, **1.2.23-1043 / 2.3.12-2312 closure-cycle**
> (constitutional-plumbing-only; no user-visible feature change). HelixConstitution
> submodule incorporated + §6.AD HelixConstitution-Inheritance Mandate landed;
> 8-track §6.AD-debt opened and systematically closed across 14 commits.
> §6.AC + §6.AB scanners in STRICT mode. Build-resource stats tracker
> (§11.4.24) landed. All Lava-side debts in scope CLOSED at commit `4a7d0402`.
> See git log `66de343b..4a7d0402` for the full closure-cycle.
>
> **Prior:** 2026-05-14, **1.2.22-1042 / 2.3.11-2311 DISTRIBUTED to Firebase**
> (debug stage 1 + release stage 2, operator pre-authorized combined).
> About dialog author re-order; Crashlytics 6-issue sweep (3 fixed + 3
> closed-historical); §6.AC Comprehensive Non-Fatal Telemetry Mandate
> added (28th §6.L); §6.AA-debt PARTIAL CLOSE; per-channel
> last-version-{debug,release} pointers; both APIs running 2.3.11.
>
> **Prior:** 2026-05-14, 1.2.21-1041 DISTRIBUTED (onboarding back-press
> fix + WelcomeStep colored Image fix; §6.AB Anti-Bluff Test-Suite
> Reinforcement, 27th §6.L).
>
> **Prior:** 2026-05-14, 1.2.20-1040 DISTRIBUTED (Galaxy S23 Ultra
> cold-launch crash fix: `ic_lava_logo` layer-list → composited PNG;
> §6.Z Anti-Bluff Distribute Guard, 26th §6.L).
>
> **Prior:** 2026-05-14, 1.2.19-1039 DISTRIBUTED (§6.Y Post-Distribution
> Version Bump Mandate, 25th §6.L) + 1.2.18-1038 DISTRIBUTED (24th §6.L).
>
> **Prior:** 2026-05-13, SP-4 Phase G + Phase F.1 + F.2 + Phase D (multi-
> provider parallel search SDK) + Phase C (Trackers screen removal +
> :feature:provider_config landing). See git log `0c87b6ae..` and the
> CHANGELOG for the full delivery chain.
>
> **§6.S binding:** this file is constitutionally load-bearing per
> root `CLAUDE.md` §6.S. Every commit that changes phase status,
> lands a new spec/plan, bumps a submodule pin, ships a release
> artifact, discovers or resolves a known issue, or implements an
> operator scope directive MUST update this file in the SAME
> COMMIT. The §0 "Last updated" line MUST track HEAD. Stale
> CONTINUATION.md is itself a §6.J spirit issue under §6.L's
> repeated mandate. `scripts/check-constitution.sh` enforces
> presence + structure (§0, §7, §6.S clause + inheritance).

---

## 0. Quick orientation (read this first)

| Surface | Current state | Pin |
|---|---|---|
| Lava parent on master | 2 mirrors (GitHub + GitLab) at HEAD | `0c87b6ae` |
| API on thinker.local | 2.3.11 running (last cycle's build); not re-built this cycle | container `lava-api-go-thinker` |
| Android Firebase | 1.2.22 (1042) distributed to testers (2026-05-14, last user-visible release) | `lava-vasic-digital` Firebase project |
| 17 own-org submodules | all pushed (16 vasic-digital + 1 HelixDevelopment HelixQA) | see §3 |
| constitution submodule | at upstream HEAD `464ada14` (12 new §11.4.25-§11.4.36 clauses) | HelixDevelopment/HelixConstitution |
| Verify-all sweep | 40/40 PASS, fully STRICT mode | `.lava-ci-evidence/verify-all/` |
| Coverage ledger | 48 covered / 10 partial / 0 gap (58 rows) | `docs/coverage-ledger.yaml` |
| CM-* gates wired | ~16 of 24 wired (8 paper-only or equivalence-mapped) | `docs/helix-constitution-gates.md` |

This cycle delivered the entire 12-clause constitution-compliance plan plus HelixQA submodule adoption plus the Phase 7 STRICT-flip. **No user-visible feature change**; constitutional-plumbing-only.

---

## 1. What's DONE (this cycle, since 2026-05-15)

### Constitution-compliance plan (`docs/plans/2026-05-15-constitution-compliance.md`)

| Phase | Subject | Status | Anchor commits |
|---|---|---|---|
| 0 | Pin advance + plan land | ✅ DONE | `ed16debd` (pin) + `832f739e` (plan) |
| 1 | §11.4.32 enforcement engine | ✅ DONE | `4def2da7` |
| 2 | §11.4.30 .gitignore audit gate | ✅ DONE | `037389f5` |
| 3 | §11.4.31 helix-deps.yaml manifests | ✅ DONE | `43345c3e` + `410af7ec` + `bcba3a19` |
| 3-debt | 16/16 per-submodule manifests | ✅ CLOSED | `410af7ec` + per-submodule pin advances |
| 4 | §11.4.27 HelixQA + 100% test-type coverage | ✅ DONE | `aa0db6bd` |
| 4 follow-up A | Option 1 design + shell wiring | ✅ DONE | `a61bd3d8` + `1b66d192` + merge `d94ade0d` |
| 4 follow-up B | 4 open-question resolutions | ✅ DONE | `281780d7` + merge `84d871a5` |
| 4 follow-up C | HelixQA Go-package linking design | 📐 DESIGN-ONLY | `41b81359` + merge `be1ca3d8` |
| 4-C-1 | Lava-side `pkg/evidence` adapter (WRAP) | ✅ DONE 2026-05-16 | HelixQA `a1e2020d` + Lava `573b4a8a` |
| 4-C-2 | Lava-side `pkg/detector` adapter (WRAP) | ✅ DONE 2026-05-16 | HelixQA `a1e2020d` unchanged + Lava `<this-commit>` |
| 4-debt | HelixQA upstream install_upstreams.sh + helix-deps.yaml | ✅ CLOSED 2026-05-16 | `858ffb3e` |
| 5 | §11.4.28 nested-own-org submodule audit | ✅ DONE | `bbca3a78` |
| 5-debt | STRICT flip after Challenges/.gitmodules removal | ✅ CLOSED | `410af7ec` |
| 6 | §11.4.29 lowercase snake_case naming | 📐 PLAN-ONLY | `322f2081` + merge `c8d42434` |
| 7 | §11.4.25 coverage ledger | ✅ DONE | `21dee741` + merge `c35af27c` |
| 7-debt | waiver backfill + STRICT flip | ✅ CLOSED | `76507ca0` + merge `20b3fd36` + `0c87b6ae` |
| 8 | §11.4.35 + §11.4.36 canonical-root + install_upstreams | ✅ DONE | `d95be689` + `410af7ec` |
| 8-debt | 10 install_upstreams scripts across owned submodules | ✅ CLOSED | `410af7ec` |
| 9 Path B | §11.4.33 + §11.4.34 equivalence-mapping | ✅ DONE | `055fbcbe` |

### HelixQA submodule (NEW this cycle)

- Adopted at `submodules/helixqa` from `git@github.com:HelixDevelopment/HelixQA.git`.
- Initial pin: `403603db` (2026-05-15 in Phase 4).
- Upstream PR `b13ba7c` added `helix-deps.yaml` + `install_upstreams.sh` (2026-05-16 in Phase 4-debt closure).
- Current pin: `b13ba7c0`.
- 11 HelixQA Challenge scripts wrapped via `scripts/run-helixqa-challenges.sh` (Option 1 shell-level wiring).
- 11 hermetic fixtures at `tests/check-constitution/test_helixqa_wiring.sh` validate the wrapper.
- §6.W audit doc at `docs/helixqa-script-audit.md` (per-script git-push / curl / outside-worktree analysis; 0/11 violators on default config).
- `HELIX_DEV_OWNED` exemption pattern in `scripts/check-canonical-root-and-upstreams.sh` + `scripts/check-helix-deps-manifest.sh` (HelixDevelopment org submodules treated like vasic-digital for mirror-presence checks, distinct from arbitrary third-party submodules).

### §6.L counter advance

The §6.L Anti-Bluff Functional Reality Mandate counter advanced from 36 to 52 across the cycle (53rd is in-flight per the dispatch that triggered this CONTINUATION.md refresh task). 17-cycle back-to-back restatement is the longest sequence in project history; per §6.L the repetition itself is the constitutional record. Anchor commits: `8c47cd17`, `d159d0fc` (37-41 batched), `66803d4d` (43+44 batched), `aa0db6bd` (45), `a61bd3d8` (46), `ed7a658d` (47), `dcec9eb8` (48), `0f1b19f1` (49), `2882304b` (50+51), `0c87b6ae` (52).

---

## 2. What's BLOCKED ON OPERATOR ACTION

These items need the operator's environment / hardware / decisions
that an agent cannot make alone.

### 2.1 Phase 4 follow-up C (HelixQA Go-package linking) — STATUS UPDATE 2026-05-16

**Phase 4-C-1 (pkg/evidence adapter): COMPLETED 2026-05-16.** All 10 open questions answered by operator + implementation landed in this cycle's commit. Operator decisions: Q1 Go 1.26 bump, Q2 WRAP, Q3 Path A tag-pin (transitional Path B replace+sibling-mount until HelixQA stabilizes), Q4 preserve HelixQA terminology (Collector / Detector / Generator), Q5 upstream-contribute CaptureGeneric first (HelixDevelopment/HelixQA PR #1, branch `feat/evidence-capture-generic`, commit `a1e2020dd759d025b67ef8e024061b103940470d`), Q6 SKIP 4-C-4 navigator entirely, Q7 NO recover() wrapping, Q8 accept 2x CI build-time delta, Q9 always-track-upstream for HelixQA (§6.AD waiver documented in CLAUDE.md), Q10 coverage-ledger bumped in same commit.

Deliverables this cycle:
- HelixQA `pkg/evidence.CaptureGeneric` public method (commit `a1e2020d`, PR HelixDevelopment/HelixQA#1)
- `lava-api-go/internal/qa/evidence/{collector,collector_test}.go` (adapter + 9 unit tests, 87.9% coverage)
- `lava-api-go/tests/qa/evidence_test.go` (real-stack integration test, `//go:build helixqa_realstack`)
- `lava-api-go/go.mod` bumped to Go 1.26 + adds `digital.vasic.helixqa` require + replace
- `Submodules/HelixQA/` pin bumped to `a1e2020d`
- `docs/coverage-ledger.yaml` regenerated (58 rows; lava-api-go row: 89 unit tests + 1 integration)
- `CLAUDE.md` §6.AD-debt: HelixQA always-track-upstream waiver documented
- This `CONTINUATION.md` updated

Phase 4-C-2 (detector adapter), 4-C-3 (ticket adapter), 4-C-4 (validator + SKIP navigator per Q6) remain owed — each is 1-session scope per design doc §E.

**Phase 4-C-2 (pkg/detector adapter): COMPLETED 2026-05-16.** Operator decisions reused from 4-C-1 (Q1–Q10 unchanged). Q4 preserves `Detector` name. Q5: no HelixQA-side promotion needed — `pkg/detector`'s public API surface already exposes everything the adapter requires (`Detector`, `Option`, `New`, `WithDevice`/`WithPackageName`/`WithBrowserURL`/`WithProcessName`/`WithProcessPID`/`WithEvidenceDir`/`WithCommandRunner`, `Check`, `CheckApp`, `Platform`, `DetectionResult`, `CommandRunner` interface).

Deliverables this cycle:
- `lava-api-go/internal/qa/detector/detector.go` (255 LOC) — WRAP-strategy adapter exposing Lava-shaped `Report` struct (Crashed/Alive/StackTrace/EvidencePath); `CheckGoProcess(processName)` for name-based detection (real `pgrep -f`); `CheckGoProcessByPID(pid)` for PID-based detection (real `kill -0`); `ErrEmptyProcessName` + `ErrInvalidPID` Lava-side guards that block HelixQA's silent fallback to `processName="java"` for empty/zero inputs (forensic value: mutation rehearsal proved Alive=true would silently be returned because Java exists on every dev box)
- `lava-api-go/internal/qa/detector/detector_test.go` (11 tests, 82.9% statement coverage, race-clean) — uses HelixQA's `CommandRunner` interface as the boundary-fake (not a mock of the SUT; the HelixQA Detector itself runs unaltered, satisfying §6.J.4 forbidden-mock pattern)
- `lava-api-go/tests/qa/detector_test.go` (3 real-stack tests, `//go:build helixqa_realstack`) — spawns sacrificial `sleep` child processes via `sh -c 'exec -a <sentinel> sleep 30'` (BSD/macOS-compatible argv[0] injection); asserts the REAL HelixQA Detector against the real OS process table
- `docs/coverage-ledger.yaml` regenerated (lava-api-go unit_test_count 89 → 93)
- This `CONTINUATION.md` updated; coverage-ledger regen confirmed (58 rows preserved)

§6.J anti-bluff posture: 4 falsifiability rehearsals captured in commit body — (1) `Crashed: dr.HasCrash → !dr.HasCrash` triggered 5 unit-test assertions; (2) empty-name guard removal caught at compile time (`"strings" imported and not used`); (3) PID guard removal triggered 2 sub-test assertions + exposed HelixQA's silent `java` fallback; (4) `Alive: dr.ProcessAlive → !dr.ProcessAlive` triggered ALL 3 real-stack assertions on live/dead/ghost process paths. All reverted, all green after revert.

Honest scope statement: real-stack tests were initially executed via `go test -tags=helixqa_realstack ./tests/qa/detector_test.go` (file-target form) while Phase 4-C-3's `internal/qa/ticket/generator.go` was untracked + uncompiling. After 4-C-3 landed at `86402bfa` (which also bumped HelixQA pin `a1e2020d` → `c57c275` to gate `enhanced_generator.go` behind the `helixqa_enhanced_tickets` tag), the full package-target form `go test -tags=helixqa_realstack ./tests/qa/...` PASSes too (7/7: 2 evidence + 3 detector + 2 ticket). Re-verified at HEAD post-`86402bfa`.

**Phase 4-C-3 (pkg/ticket adapter): COMPLETED 2026-05-16.** Operator decisions reused from 4-C-1 (Q1–Q10 unchanged). Q4 preserves `Generator` name.

Deliverables this cycle:
- HelixQA-side prereq: `submodules/helixqa/pkg/ticket/enhanced_generator.go` gated behind `//go:build helixqa_enhanced_tickets` so plain consumers of `pkg/ticket` (the Lava adapter) do NOT pull LLMOrchestrator transitively. HelixQA SHA bump owed at parent-cycle close.
- `lava-api-go/internal/qa/ticket/generator.go` (≈340 LOC) — WRAP-strategy adapter with `NewGenerator`, `GenerateClosureLog`, `OutputDir`, `ClosureLogInput` shape mirroring §6.O closure-log conventions
- `lava-api-go/internal/qa/ticket/generator_test.go` (13 tests + 8 sub-tests, 93.2% statement coverage with `-race`)
- `lava-api-go/tests/qa/ticket_test.go` (2 real-stack tests, `//go:build helixqa_realstack`)
- `CLAUDE.md` §6.O extended with clause 7 — adapter authorized as programmatic closure-log path
- This `CONTINUATION.md` updated; coverage-ledger row addition owed at parent-cycle rolled regen

§6.J anti-bluff posture: 2 falsifiability rehearsals captured in commit body — (1) H1 heading mutation surfaced by schema test, (2) empty-CrashlyticsID validation removal surfaced by RejectsEmpty test; both reverted, both green after revert.

Phase 4-C-4 (validator adapter; navigator SKIPPED per Q6) remains owed.

### 2.2 Phase 6 snake_case migration — RESOLVED + EXECUTED

All 8 operator questions answered (2026-05-16). Phase 6a + 6b executed in this cycle. See `docs/plans/2026-05-16-phase6f-upstream-rename-execution.md` for the deferred upstream-rename plan (Q1: defer; document execution steps for operator).

### 2.3 Release-tagging chain (versions inherit from prior cycle)

Last Firebase distribute: 1.2.22-1042 / 2.3.11-2311 (2026-05-14). This cycle made NO user-visible changes, so no new distribute is owed. Tag-script gate per §6.I (multi-emulator container matrix + per-AVD attestation) still blocked on Linux x86_64 + KVM gate-host per the standing §6.X-debt (`.lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json`).

---

## 3. Submodule pin index

17 own-org submodules + 1 universal-rules submodule (constitution).
Bumping a pin is a deliberate operator action; never auto-update.

| Submodule | Pin | Mirrors | Notes |
|---|---|---|---|
| `Auth` | `32a80e0a` | GitHub + GitLab | helix-deps.yaml + CONST-050(B) anti-bluff cascade |
| `Cache` | `bb5b7a98` | GitHub + GitLab | helix-deps.yaml present |
| `Challenges` | `1ef27f1c` | GitHub + GitLab | helix-deps.yaml + flat layout (Panoptic dep declared, .gitmodules removed) |
| `Concurrency` | `a521b642` | GitHub + GitLab | helix-deps.yaml present |
| `Config` | `4b0933c6` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `Containers` | `c7fc343b` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `Database` | `13f63819` | GitHub + GitLab | helix-deps.yaml present |
| `Discovery` | `218cb3a1` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `HelixQA` | `a1e2020d` | GitHub | NEW 2026-05-15; HelixDevelopment org; **always-track-upstream per §6.AD-debt Q9 waiver 2026-05-16**; bumped to `feat/evidence-capture-generic` HEAD (PR #1, CaptureGeneric addition) for Phase 4-C-1 |
| `HTTP3` | `1fbdcbab` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `Mdns` | `d93139d5` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `Middleware` | `ab3d5c62` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `Observability` | `6cfbf42b` | GitHub + GitLab | helix-deps.yaml present |
| `RateLimiter` | `a109485f` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `Recovery` | `5781a89f` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `Security` | `997ebd39` | GitHub + GitLab | helix-deps.yaml present |
| `Tracker-SDK` | `ae761d5c` | GitHub + GitLab | helix-deps.yaml + install_upstreams.sh added |
| `constitution` | `464ada14` | universal (HelixConstitution upstream) | 12 new §11.4.25-§11.4.36 clauses |

**Internal-to-submodule nested submodules:** `submodules/challenges` had a nested `Panoptic` submodule that was removed via the github cascade merge in Phase 5-debt closure (CONST-051(C) flat-layout enforcement); Challenges now declares Panoptic as a `layout: flat` dependency in its `helix-deps.yaml`.

---

## 4. Known issues + bugs (carried forward — historical)

These are real defects discovered before this cycle. Tracked here for
forensic continuity; none are blocking this cycle's constitutional
work.

### 4.5 Active known issues

- **§6.X-debt (Linux x86_64 + KVM gate-host)**: STANDING. On darwin/arm64
  Apple Silicon + macOS + Podman, the emulator process cannot get HVF/KVM
  passthrough into the container; matrix attestation runs cannot complete.
  Forensic anchor: `.lava-ci-evidence/sixth-law-incidents/2026-05-13-emulator-container-darwin-arm64-gap.json`.
  Resolution: provision Linux x86_64 self-hosted runner OR add HVF
  passthrough to the Containers image.
- **macOS emulator stall** (2026-05-15 incident): Pixel_7_Pro on macOS
  + emulator 36.1.9 stalls indefinitely. Three candidate root-causes
  recorded as `PENDING_FORENSICS:` (T7 external drive contention,
  emulator-36.1.9 known issues, AVD config theory eliminated by fresh-AVD
  re-test). Forensic anchor: `.lava-ci-evidence/sixth-law-incidents/2026-05-15-macos-emulator-stall-on-android33.json`.
  Orthogonal to §6.X-debt.
- **github SSH-fail flake pattern** (resolved this cycle): the §6.L 37th
  + 39th invocation forensics document multi-push retry pattern; the
  resolution is the standing operator practice of retrying `git push github`
  on connection-reset. No code change owed.

### 4.5 Resolved this cycle

- **Ledger-staleness drift class** — Phase 7-debt closure (`0c87b6ae`)
  flipped the coverage-ledger gate from `--advisory` to `--strict` in the
  sweep wrapper. Subsequent stale-ledger commits will hard-fail at sweep
  time + pre-push.
- **§11.4.27 HelixQA non-incorporation** — Phase 4 closure (`aa0db6bd`)
  + Phase 4-debt closure (`858ffb3e`) bring HelixQA in as
  `submodules/helixqa` with full mirror compliance.
- **§11.4.31 / .35 / .36 zero-waiver state** — Phase 4-debt closure +
  Phase 8-debt closure achieve 17/17 own-org submodules satisfying all
  three mandates with **zero waivers** in STRICT mode.

### 4.5 Historical — pre-this-cycle, carried forward

(See full historical detail in the git log between the prior CONTINUATION.md
"Last updated" header and `4a7d0402`. Summary: C02 Cloudflare-mitigation
stops short of profile-parsing; C17-C22 require emulator matrix; UDP buffer
warning documented; mirror model reduced to 2-mirror per §6.W;
docs/todos/Lava_TODOs_001.md committed as historical; etc.)

---

## 5. Operator-flagged follow-up items (small, queued)

- **Phase 4-C implementation** — blocked on §2.1 open questions.
- **Phase 6a implementation** — blocked on §2.2 open questions.
- **HelixQA pin upgrade cadence** — operator decides when to re-baseline
  to track HelixQA `main` vs. holding at `b13ba7c0`.
- **Re-audit HelixQA scripts (`docs/helixqa-script-audit.md`)** on every
  pin bump per the §6.W audit doc's re-audit-trigger clause.
- **Coverage-ledger row additions** when new feature modules land — the
  generator is deterministic; re-run via `scripts/generate-coverage-ledger.sh`
  in the same commit as the new module to keep STRICT mode green.

---

## 6. Constitutional debt + memory anchors

- **§6.K-debt** (Containers extension): RESOLVED 2026-05-07.
- **§6.N-debt** (pre-push hook enforcement): RESOLVED 2026-05-05.
- **§6.AD-debt** (HelixConstitution-Inheritance per-scope + CM-* wiring): RESOLVED across the 1.2.23 closure-cycle (`4a7d0402` + earlier); 6 paper-only CM-* gates remain ⚠️ by design (equivalence-mapped per §6.AD.3).
- **§6.X-debt** (Linux x86_64 + KVM gate-host for container-bound emulator matrix): STANDING. See §4.5 above.
- **§6.L** (Anti-Bluff Functional Reality Mandate): 52 invocations across multiple working days; 17-cycle back-to-back the longest sequence in project history this cycle. Per §6.L the repetition IS the constitutional record.
- **§6.R** (No-Hardcoding Mandate): UUID + IPv4 + host:port scanners active; algorithm-parameter literal grep staged (code-review gate per §6.R clause body).
- **§6.S** (Continuation Document Maintenance): THIS file. Per §6.S the §0 "Last updated" line MUST track HEAD.
- **§6.T** (Universal Quality Constraints): four sub-points active (Reproduction-Before-Fix, Resource Limits, No-Force-Push, Bugfix Documentation).
- **§6.AC** (Comprehensive Non-Fatal Telemetry): scanner in STRICT mode; ci.sh hard-fail wired.
- **§6.AB** (Anti-Bluff Test-Suite Reinforcement): scanner in STRICT mode.
- **§6.AE** (Comprehensive Challenge Coverage + Container/QEMU Matrix): per-feature scanner in STRICT mode; container matrix runner BLOCKED on §6.X-debt.

---

## 7. RESUME PROMPT

Paste the following into a new CLI agent session to continue this
work. The agent needs no scrollback — everything it needs is in this
file plus the spec/plan/CLAUDE.md set referenced from it.

```
Continue Lava project work. Read these in order before doing anything:

  1. /Users/milosvasic/Projects/Lava/docs/CONTINUATION.md
  2. /Users/milosvasic/Projects/Lava/CLAUDE.md
  3. /Users/milosvasic/Projects/Lava/constitution/Constitution.md
  4. /Users/milosvasic/Projects/Lava/docs/plans/2026-05-15-constitution-compliance.md
  5. /Users/milosvasic/Projects/Lava/docs/helix-constitution-gates.md
  6. /Users/milosvasic/Projects/Lava/docs/coverage-ledger.yaml (skim — generated)

Then check the git state vs the CONTINUATION.md "Last updated" line.
If new commits exist on master beyond what CONTINUATION.md describes,
trust the commits and update CONTINUATION.md before proceeding (per §6.S).

Active state per CONTINUATION.md §1 (2026-05-16):
  - All 10 phases of the constitution-compliance plan DONE (Phases 1-9 closed).
  - HelixQA submodule incorporated; Phase 4-debt CLOSED 2026-05-16.
  - Verify-all sweep: 40/40 PASS in fully STRICT mode.
  - 17/17 own-org submodules with helix-deps.yaml + install_upstreams.sh; zero waivers.
  - Coverage ledger: 48 covered / 10 partial / 0 gap (58 rows).
  - §6.L counter at 52; 53rd in-flight at the moment of this CONTINUATION.md refresh.
  - 33 session commits §6.C-converged on GitHub + GitLab.

Your default next action (priority order):
  1. **Phase 4-C** (HelixQA Go-package linking): blocked on 10 operator open
     questions at `docs/plans/2026-05-16-helixqa-go-package-linking-design.md`
     §G. Surface the questions to the operator; do NOT proceed to 4-C-1.
  2. **Phase 6a** (snake_case migration): blocked on 8 operator open questions
     at `docs/plans/2026-05-16-snake_case-migration.md` §11. Surface the
     questions to the operator; do NOT proceed to Phase 6a implementation.
  3. **Crashlytics monitoring**: the last Firebase distribute was 1.2.22-1042
     (2026-05-14); check Crashlytics for any new issues per §6.O closure
     mandate.
  4. **HelixQA pin freshness**: re-baseline `submodules/helixqa` from
     upstream if operator approves; re-run the §6.W audit.
  5. **Tag-script gate**: still blocked on §6.X-debt (Linux x86_64 + KVM
     gate-host) for §6.I matrix attestation. No release tag this cycle.

Do NOT re-run completed phases — they are committed + pushed + sweep-verified.
The git log is the authoritative record.

Verify-all sweep evidence: `.lava-ci-evidence/verify-all/<UTC-timestamp>.json`
Latest gates index: `docs/helix-constitution-gates.md`
Coverage ledger: `docs/coverage-ledger.yaml`

Constitutional bindings still in force (do not relax):
  §6.J / §6.L (Anti-Bluff Functional Reality Mandate)
  §6.AB / §6.AC / §6.AE (anti-bluff scanners — STRICT)
  §6.AD (HelixConstitution Inheritance) + §6.AD.3 (equivalence-mapping)
  §6.R (No-Hardcoding Mandate)
  §6.S (Continuation Document Maintenance — THIS file)
  §6.W (GitHub + GitLab Only Remote Mandate; HELIX_DEV_OWNED exemption for HelixDevelopment org)
  §6.X (Container-Submodule Emulator Wiring; PARTIAL — gate-host owed)
  §11.4.25-§11.4.36 (12 new HelixConstitution clauses)

The operator's standing §6.L wall is preserved verbatim in CLAUDE.md.
Read it.
```

---

## 8. House-keeping the agent should keep doing

These are habits established across multiple cycles; future agents
should preserve them.

1. **Commit messages carry Bluff-Audit stamps for every test class
   added or modified** (Seventh Law clause 1; pre-push Check 2 rejects
   commits without them).
2. **Every commit must have `Co-Authored-By: Claude Opus 4.7
   (1M context) <noreply@anthropic.com>`** as the trailer.
3. **Push to both Lava parent mirrors (GitHub + GitLab)** after every
   commit chain that closes a logical unit. After every push, confirm
   convergence with
   `for r in github gitlab; do echo "$r: $(git ls-remote $r master | awk '{print $1}' | head -1)"; done`.
4. **Submodule pushes are explicit per submodule** to whatever remotes
   that submodule has (varies — see §3). Never use
   `git submodule foreach git push` blindly.
5. **Update this CONTINUATION.md** in the same commit as any
   completion-state change (phase done, new spec/plan written, submodule
   pin bumped, distribute artifact shipped, new operator-blocked open
   question surfaced).
6. **Run `scripts/verify-all-constitution-rules.sh`** before any
   release-tagging or major-state-change attempt. The sweep wrapper is
   in fully STRICT mode; a non-40/40 result is a release blocker.
7. **Re-generate the coverage ledger** (`scripts/generate-coverage-ledger.sh`)
   in the same commit as any new feature module or any module-deletion;
   the STRICT-mode gate rejects stale-ledger commits.
8. **The autonomous loop ends** when the next forward step requires
   operator-environment access (real device, real keystore secrets,
   Firebase token, ssh credentials) OR operator decision-making
   (open questions, brainstorming next phase scope, tagging, choosing
   a UI direction). At that point, summarize state + ask the operator
   the specific next-step question.

---

## 9. Cross-references

- **Plan docs (this cycle):**
  - `docs/plans/2026-05-15-constitution-compliance.md` — master plan
  - `docs/plans/2026-05-16-helixqa-integration-design.md` — Option 1 wiring (DONE)
  - `docs/plans/2026-05-16-helixqa-go-package-linking-design.md` — Option 2 design (DESIGN-ONLY; operator-blocked)
  - `docs/plans/2026-05-16-snake_case-migration.md` — Phase 6 plan (PLAN-ONLY; operator-blocked)
- **Gates inventory:** `docs/helix-constitution-gates.md`
- **Coverage ledger:** `docs/coverage-ledger.yaml` + `docs/coverage-ledger.waivers.yaml`
- **Constitution source-of-truth:** `constitution/` submodule (HelixConstitution at `464ada14`)
- **Sweep wrapper:** `scripts/verify-all-constitution-rules.sh`
- **Sweep attestations:** `.lava-ci-evidence/verify-all/<UTC-timestamp>.json`
- **HelixQA audit:** `docs/helixqa-script-audit.md`
- **HelixQA wrapper:** `scripts/run-helixqa-challenges.sh`
- **HelixQA hermetic test:** `tests/check-constitution/test_helixqa_wiring.sh`
