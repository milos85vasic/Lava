## Lava-Android-1.2.18-1038 / Lava-API-Go-2.3.7-2307 — 2026-05-14 (3 user-reported issues closed: onboarding back-nav + S23 Ultra insets + DEV API discovery)

**Previous published:** Lava-Android-1.2.17-1037 / Lava-API-Go-2.3.6-2306

### Fixed (Android client)

- **Onboarding back navigation works on every step.** The pre-fix
  `BackHandler` predicate in `OnboardingScreen.kt` was inverted
  (intercepted on Welcome where it should fall through; ignored on
  Providers / Configure / Summary where the user actively needs to
  walk back). The VM's `onBackStep()` was correctly designed but
  never reached. Two production changes: (a) `BackHandler(enabled =
  true)` so the VM decides per-step what back means; (b)
  `onBackStep()` extended so Configure with `currentProviderIndex >
  0` decrements through the per-provider Configure pages before
  returning to the Providers list, and Summary now re-enters
  Configure on the last selected provider so the user can amend a
  config they've already reviewed. Commit `6a315a28`.

- **Onboarding no longer overlaps the system bars on tall-aspect
  devices (Samsung Galaxy S23 Ultra reproduction).** MainActivity
  calls `enableEdgeToEdge`, but `OnboardingScreen.kt`'s
  `AnimatedContent` container did not apply
  `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`. Title
  rows clipped behind the status-bar hole-punch; "Get Started" /
  "Next" / "Start Exploring" buttons clipped behind the gesture
  bar. One-place fix at the screen level — every step inherits.
  `safeDrawing` also handles IME so Configure's text fields stay
  above the keyboard. Commit `09ce7466`.

### Added (DEV API instance)

- **Side-by-side DEV lava-api-go on `_lava-api-dev._tcp` port
  8543.** Developers can now iterate on the Go API without
  disturbing the production instance. New `docker-compose.dev.yml`
  brings up `lava-postgres-dev` (host port 5433, schema
  `lava_api_dev`) + `lava-migrate-dev` + `lava-api-go-dev`
  reusing the same binary with dev-flavored env values. Only the
  **debug** Android build (applicationIdSuffix `.dev`) subscribes
  to the dev service type; release builds ignore it entirely so
  a stray dev advertiser on a real user's LAN cannot redirect
  their traffic. Commit `69b389a2`. New §6.A real-binary contract
  test asserts every `LAVA_API_*` env var the dev compose passes
  binds to a field `config.Load()` actually reads.

### Documentation

- **CLAUDE.md targeted improvements** per the claude-md-improver
  audit (commit `225f8351`): no constitutional clause text touched;
  reorganized navigation aids (CONTINUATION.md first, debt index,
  §6.L operational summary, "Always forbidden" quick-reference).

### Tests + falsifiability evidence (per §6.J / §6.N.1.1)

- 5 new VM unit tests in `OnboardingViewModelTest` covering all
  four back-step transitions (Welcome→Finish side effect,
  Providers→Welcome, Configure→Providers single-provider,
  Configure walk-back multi-provider, Summary→Configure on last
  provider). Reproduction-before-fix gate: Summary test fails
  against pre-fix VM with `TurbineTimeoutCancellationException`;
  all 8 pass after fix.
- `OnboardingInsetRegressionTest` (JVM, structural, runs on every
  pre-push) reads `OnboardingScreen.kt` and asserts the
  `windowInsetsPadding(WindowInsets.safeDrawing)` modifier +
  imports are present. Falsifiability rehearsed: `sed`-removed the
  modifier; observed `AssertionError` at line 38; reverted; pass.
- `LocalNetworkDiscoveryServiceTypeTest` covers catalog +
  matchesServiceType discrimination (release excludes dev; debug
  includes dev as exactly-one addition; watcher(prod) rejects
  found(dev) and vice versa; watcher(dev) accepts found(dev)).
  Falsifiability rehearsed: dropped the dev type from
  `SERVICE_TYPES_DEBUG`; observed `AssertionError` at line 49;
  reverted; pass.
- `dev_compose_env_contract_test.go` (Go-side §6.A real-binary
  pattern) reads `docker-compose.dev.yml`, extracts every
  `LAVA_API_*` env var, asserts `config.Load()` surfaces the
  passed values. Falsifiability rehearsed: renamed
  `LAVA_API_MDNS_TYPE` → `LAVA_API_MDNS_SERVICE_TYPE` in
  `config.go`; observed precise drift error message naming both
  values; reverted; pass.

### Challenge Tests (instrumentation, owed at next §6.X-mounted gate host)

- C24 (`Challenge24OnboardingBackNavigationTest`) — drives
  `onBackPressedDispatcher.onBackPressed()` at Providers,
  Configure, Summary; asserts state transitions. Source compiled
  via `:app:compileDebugAndroidTestKotlin`; instrumentation run
  PENDING per §6.X-debt (operator-blocked on darwin/arm64).
- C25 (`Challenge25OnboardingInsetSafeDrawingTest`) — runs on a
  tall-aspect AVD (Pixel 9 Pro XL or 1440x3088 @ 500dpi);
  asserts top + bottom anchored nodes are reported displayed on
  Welcome / Providers / Summary. Source compiled; instrumentation
  run PENDING per §6.X-debt.

### What's NOT in this version

- No actual Firebase distribution — distribute remains
  operator-blocked on placeholder `app/google-services.json` +
  placeholder `LAVA_FIREBASE_TOKEN` + placeholder tracker
  credentials (carried over from the 1.2.17-1037 release-prep
  note; same operator-input checklist applies).
- No §6.X-debt closure — Linux x86_64 gate-host provisioning is
  still owed; the container-bound emulator matrix path is shipped
  in the Containers submodule but darwin/arm64 cannot exercise it
  per the recorded incident JSON.

### Operator inputs needed for the next distribute

Same as Lava-Android-1.2.17-1037; see
`Lava-Android-1.2.17-1037.md` "Operator inputs needed" block. The
new code in this release is functionally complete and unit-test
verified; the distribute gate is environmental, not code.
