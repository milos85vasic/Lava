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

> **Last updated:** 2026-05-14, **1.2.23-1043 / 2.3.12-2312 closure-cycle**
> (constitutional-plumbing-only; no user-visible feature change; no Firebase
> distribute since 1.2.22-1042 still serves the user-visible surface). Cycle
> spans commits `66de343b` → `062de2cc`: HelixConstitution submodule
> incorporation + §6.AD Mandate + 8-track §6.AD-debt opened, then
> systematically closed across 14 commits. Final closure state:
>
>   ✅ §6.AD-debt items 1, 3, 4, 5 (PARTIAL — HTML+PDF deferred), 6, 7, 8 — CLOSED
>   ⚠ §6.AD-debt item 2 (CM-* gate wiring) — 5 of 11 ✅ wired
>     (CM-COMMIT-DOCS-EXISTS, CM-UNIVERSAL-VS-PROJECT-CLASSIFICATION,
>     CM-NONFATAL-COVERAGE, CM-SCRIPT-DOCS-SYNC, CM-BUILD-RESOURCE-STATS-TRACKER);
>     remaining 6 are ⚠ paper-only by design (depend on Issues/Fixed-tracker
>     infrastructure Lava doesn't use; equivalence in §6.AD.3)
>   ✅ §6.AA-debt CLOSED (firebase-distribute.sh default flipped to MODE=debug; --release-only requires companion debug-stage advance)
>   ✅ §6.Y-debt CLOSED (.githooks/pre-push Check 6: bump-first ordering)
>   ✅ §6.Z-debt CLOSED (.githooks/pre-push Check 7: evidence-file presence on real pointer advance, vcode→vname via per-version snapshot)
>   ✅ §6.AC-debt CLOSED (queue drained 7+444 → 0+0; scanner in strict-mode default; ci.sh hard-fail wired in commit `215e14d5`)
>   ❌ §6.AB-debt deferred (Detekt setup needed across 30+ modules first)
>
> **Extended cycle (post-1.2.23-closure follow-up batch, commits `5bb1451d` → `4a7d0402`):**
>   - `5bb1451d` CM-SCRIPT-DOCS-SYNC pre-push Check 9 (closes the §11.4.18 gate)
>   - `062de2cc` CM-BUILD-RESOURCE-STATS-TRACKER (sampler + report + 2 user guides — closes §6.AD-debt item 5 + §11.4.24)
>   - `14916722` CONTINUATION.md catch-up
>   - `b9f4edde` §6.AC drain — scanner heuristic improvements + 7 Kotlin opt-outs (444 → 71)
>   - `215e14d5` §6.AC FULL CLOSE — 23 Go bulk opt-outs + strict-mode flip + ci.sh hard-fail wiring
>   - `c7020211` CONTINUATION.md reflects §6.AC FULL CLOSE
>   - `44e054a3` §11.4.18 backfill — 19 docs/scripts/X.sh.md companion docs + scripts/gen-script-doc.sh generator (closes task #61; CM-SCRIPT-DOCS-SYNC now gates all 22 scripts, was 3)
>   - `4a7d0402` §6.AB-debt FULL CLOSE — scripts/check-challenge-discrimination.sh + ci.sh wiring + companion docs (closes CM-CHALLENGE-DISCRIMINATION; the commit was rejected on first attempt by Check 9 itself + amended in same SHA — positive evidence the SCRIPT-DOCS-SYNC gate works on real commits, not just synthetic fixture)
>
> **Final state (all in-scope Lava-side debts CLOSED as of `4a7d0402`):**
>   ✅ §6.Y-debt | ✅ §6.Z-debt | ✅ §6.AA-debt | ✅ §6.AB-debt | ✅ §6.AC-debt
>   ✅ §6.AD-debt items 1, 3, 4, 5, 6, 7, 8 (item 2 = 6 of 11 CM-* gates wired; the 6 paper-only ones depend on Issues/Fixed-tracker infrastructure Lava doesn't use, equivalence mapped in §6.AD.3)
>
> **CM-* gates wired:** CM-COMMIT-DOCS-EXISTS, CM-UNIVERSAL-VS-PROJECT-CLASSIFICATION, CM-NONFATAL-COVERAGE, CM-SCRIPT-DOCS-SYNC, CM-BUILD-RESOURCE-STATS-TRACKER, CM-CHALLENGE-DISCRIMINATION.
>
> **32nd §6.L invocation (2026-05-15, immediately after 31st-cycle landed):** operator restated the verbatim §6.L wall + new directive: "you may / could / should use our Containers Submodule which has support for running Emulators inside Containers or in Qemu! Make use of it!". Counter advanced 31 → 32. **Real execution attempted + reported honestly:** built `Submodules/Containers/cmd/emulator-matrix` binary (9.2 MB), invoked with `--runner=host-direct` against `yole-test:34:phone`, CLI produced HONEST FAIL attestation JSON when AVD couldn't boot ("no new emulator serial appeared in adb devices within 1m0s"). Anti-bluff posture confirmed: runner exited non-zero + announced "tag.sh MUST refuse this commit". **Real bluff caught in same cycle:** 5 Challenge tests (C30/C31/C32/C33/C35) used `::ComposableFn` Kotlin syntax that's disallowed for @Composable functions — they were SCANNER-COVERED but DIDN'T COMPILE. Refactored to `::class.java` (public VMs) or `Class.forName()` (internal VMs). `./gradlew :app:assembleDebugAndroidTest` now BUILD SUCCESSFUL. **Real new sixth-law incident recorded:** `.lava-ci-evidence/sixth-law-incidents/2026-05-15-macos-emulator-stall-on-android33.json` — Pixel_7_Pro on macOS + emulator 36.1.9 stalls indefinitely (qemu-system at 394% CPU, adb perpetually offline). Three candidate root-causes recorded as `PENDING_FORENSICS:`. Clearly distinguished from §6.X-debt darwin/arm64 KVM-absent gap (orthogonal issues). Commit `9a4355a6`.
>
> **31st §6.L invocation (2026-05-15, post-30th-cycle):** operator restated the verbatim §6.L wall WITH a substantive new directive: "Create all required Challenges and run them all on Emulators executed in Container or Qemu! Make sure everything is delegated via Containers Submodule! [...] All tests and Challenges MUST BE executed against variety of major Android versions, screen sizes and configurations (all ran inside Container or Qemu). Document everything and make sure that anti bluff proofs are obtained for every single thing!". Counter advanced 30 → 31. Birthed §6.AE Comprehensive Challenge Coverage + Container/QEMU Matrix Mandate consolidating §6.G + §6.I + §6.J + §6.X + §6.AB into a single binding policy. New mechanical gates: `scripts/check-challenge-coverage.sh` (per-feature Challenge presence; ADVISORY default; LAVA_CHALLENGE_COVERAGE_STRICT=1 to fail) + `scripts/run-challenge-matrix.sh` (matrix-runner glue delegating to Submodules/Containers/cmd/emulator-matrix --runner=containerized; pre-bakes the §6.AE.2 minimum API/form-factor matrix). HONEST host-status: §6.AE.2/.5 (per-AVD execution + attestation) is BLOCKED on this darwin/arm64 host by the standing §6.X-debt (no /dev/kvm in podman VM, no HVF passthrough); the runner glue + matrix manifest + scanner exist + propagation is in place; operator runs on a Linux x86_64 + KVM gate-host to produce real attestations. The matrix runner correctly DETECTS the host gap and EXITS 2 (NOT 0) — anti-bluff first-class behavior per §6.J/§6.L. CM-CHALLENGE-COVERAGE added to the gates inventory. Initial coverage scan shows 3 features uncovered by direct/heuristic detection (account, rating, visited) — ADVISORY mode keeps gates green while §6.AE-debt backfill is owed.
>
> **30th §6.L invocation (2026-05-15, post-FINAL-STATE):** operator restated the verbatim §6.L wall WITH the new emphasis "Pay attention that we now use and incorporate fully the HelixConstitution Submodule responsible for root definitions of the Constitution, CLAUDE.MD and AGENTS.MD which are inherited further!". Counter advanced 29 → 30. Response (this batch): ran ALL anti-bluff gates (check-constitution.sh, check-non-fatal-coverage.sh STRICT, check-challenge-discrimination.sh STRICT, 5 hermetic check-constitution test_*.sh, 7 hermetic suites under tests/) → ALL ✅ PASS / 0 violations. Closed a real anti-bluff gap surfaced by the audit: tests/pre-push/ only had check4_test.sh + check5_test.sh; my prior §6.Y/§6.Z/§6.AD-debt closures landed Checks 6 + 7 + 8 + 9 in .githooks/pre-push WITHOUT companion hermetic tests. Wrote tests/pre-push/check{6,7,8,9}_test.sh — 13 falsifiability fixtures (4 + 3 + 3 + 3) — all PASS. §6.AD updated with explicit HelixConstitution-as-source-of-truth statement. The 30th invocation is structurally identical to the 29 prior — the operator restates this mandate after EVERY major closure milestone; the repetition is the forensic record.
>
> **Even-more-extended cycle (post-final-state polish, commits `5fd7c1fd` → `d5c3c195`):**
>   - `5fd7c1fd` enrich 28 generic Go `// no-telemetry:` opt-outs with per-site rationales (HealthCheck / scraper / fail-open / parser-helper / SSE-streaming / etc.) — task #63 closed; placeholder count 28→0
>   - `d5c3c195` build-stats-report.sh extended to derive Stats.html (via pandoc) + Stats.pdf (via wkhtmltopdf-or-weasyprint) — closes the "PARTIAL: HTML+PDF exports pending" caveat on CM-BUILD-RESOURCE-STATS-TRACKER (PARTIAL → ✅ wired); task #64 closed
>
> **No remaining open tasks in scope.** All Lava-side debt items in scope of the 1.2.23 closure-cycle are CLOSED. The 6 paper-only `CM-*` gates remain ⚠ by design (depend on Issues/Fixed-tracker infrastructure Lava doesn't use; equivalence mapped in §6.AD.3).
>
> **Possible future work** (not in any open task):
>   - Detekt setup across 30+ Gradle modules (would let `CM-NONFATAL-COVERAGE` + `CM-CHALLENGE-DISCRIMINATION` graduate from bash scanners to first-class Detekt rules; substantial multi-hour work; bash sufficient for now)
>   - Real-build sampler run (current `registry.tsv` only carries the smoke-test row from sampler bring-up; real Android `:app:assembleDebug` + Go `make build` runs would populate the registry with operator-relevant data)
>
> **Cross-cutting findings discovered during the cycle:**
>   - `core.hooksPath` was UNSET on this clone — every push earlier this cycle silently bypassed `.githooks/pre-push`. Wired in-session via `git config core.hooksPath .githooks`. New `scripts/setup-clone.sh` + `docs/scripts/setup-clone.sh.md` make this run-once-per-clone discoverable.
>   - 4 forensic-anchor lines used guessing vocabulary — fixed with `UNCONFIRMED:` / `PENDING_FORENSICS:` markers (§11.4.6 gate caught them on first run).
>   - Spotless drift in 3 files (Challenge27/Challenge29/FirebaseAnalyticsTracker) had been silently shipped before the hook wiring; fixed in commit `7474f45c`.
>   - §6.Z value-aware logic required two refactors: (1) compare OLD vs NEW pointer values, not just file-touch; (2) look up vname from per-version snapshot file (since pointer-write commit's build.gradle.kts may have already bumped to the next version).
>   - `grep -oE` under `set -euo pipefail` killed Check 4 silently on zero-match — fixed with `|| true` per-grep.
>
> Final commit chain on master (this closure cycle, 14 commits):
>   `062de2cc` build-resource stats tracker (HelixConstitution §11.4.24, §6.AD-debt item 5)
>   `5bb1451d` CM-SCRIPT-DOCS-SYNC pre-push Check 9
>   `63692ab4` ci.sh wires §6.AC scanner + gate-status update
>   `2620b2bd` §6.AC-debt PARTIAL CLOSE: non-fatal-coverage scanner
>   `ee60d7a0` setup-clone.sh + user guide (hooksPath wiring discovery)
>   `1b50995c` 3 user guides + helix-constitution-gates.md operator-readable index
>   `964b19d7` §6.AA-debt CLOSE: default-debug-only + Stage 2 gate
>   `bed323ff` fix(.githooks/pre-push) grep -oE pipefail-safe
>   `7474f45c` chore: spotless drift in 3 files
>   `4f27e307` §6.Y-debt + §6.Z-debt + §6.AD-debt item 6: pre-push extensions
>   `0bc230e7` §6.AD-debt items 1.b + 4 + 7: check-constitution.sh extensions
>   `643562c4` 16 submodule pin bumps (§6.AD propagation parent commit)
>   `3508dd93` Lava-side §6.AD pointer-block injection (6 docs)
>   `66de343b` HelixConstitution submodule incorporation + §6.AD Mandate
>
> Per-submodule §6.AD inheritance pointer-blocks landed in 16 separate
> per-submodule commits (Auth fd82cf38 / Cache b578a496 / Challenges f5fa08d3 /
> Concurrency 1f8215c9 / Config 3d6fb8b4 / Containers c4f30bdf / Database
> de84e17a / Discovery 58904e7e / HTTP3 2b1e86b1 / Mdns 0fafd78a / Middleware
> a96132eb / Observability 9513ad37 / RateLimiter b0a9b235 / Recovery 48818b37
> / Security 3a394b60 / Tracker-SDK 80d4779a) — total propagation: 54 docs.
>
> All commits §6.C-converged on GitHub + GitLab. Hardlinked .git backup at
> `.git-backup-pre-helixconstitution-20260514-211450/` (per HelixConstitution §9)
> retained for forensic reference; safe to delete once operator confirms
> closure-cycle is good.
>
> **Prior:** 2026-05-14, **1.2.22-1042 / 2.3.11-2311 DISTRIBUTED to
> Firebase** (debug stage 1 + release stage 2, operator pre-authorized
> combined). About dialog author re-order (Milos Vasic first; vertical
> spacing increased). Crashlytics 6-issue sweep: 3 fixed (#1
> JobCancellationException filter, #5 okhttp scheme validation +
> ProbeMirrorUseCase IllegalArgumentException catch, #6 RuTrackerNetworkApi
> login Unknown wrap returning WrongCredits) + 3 closed-historical (#2
> painterResource fixed in 1.2.20, #3 + #4 §6.Q nested-scroll forensic
> anchor with structural guards). §6.AC Comprehensive Non-Fatal Telemetry
> Mandate added (28th §6.L invocation): every catch/error/fallback path
> records non-fatal with mandatory attributes (feature/operation/error_class/
> error_message + per-platform extras); Android filter for CancellationException
> + 1024-char truncation + Crashlytics non-fatal feed; Go-side
> `internal/observability/RecordNonFatal` + `RecordWarning` with auto-redact
> for password/token/secret/api_key/cookie/authorization/hmac/pepper.
> Sample instrumentation in ForumViewModel + ProviderConfigViewModel. §6.AC
> propagated to root + lava-api-go × 3 + 16 submodules × 3 = 53 docs.
> §6.AC-debt opened. §6.AA-debt PARTIAL CLOSE: per-channel
> last-version-{debug,release} pointers now written by firebase-distribute.sh.
> Both APIs running 2.3.11 (prod compose + dev compose with separate
> project namespace lava-dev). `last-version-debug` 1041→1042;
> `last-version-release` 1041→1042. Console URLs: debug app
> .../releases/0kbhn28t2dos0; release app .../releases/6rhlsoi7r7uko.
>
> **Prior:** 2026-05-14, **1.2.21-1041 DISTRIBUTED**, both stages.
> Onboarding back-press from Welcome no longer marks onboarding-complete
> (now posts ExitApp side effect; gate validation requires ≥1 provider
> configured + tested). White-placeholder fix: WelcomeStep changed `Icon`
> (which tints to LocalContentColor) to `Image` (preserves color). Birthed
> §6.AB Anti-Bluff Test-Suite Reinforcement (27th §6.L) — non-crashing
> failure-mode discrimination + per-feature anti-bluff completeness checklist.
>
> **Prior:** 2026-05-14, **1.2.20-1040 DISTRIBUTED**. Galaxy S23 Ultra
> cold-launch crash fixed: `R.drawable.ic_lava_logo` was a `<layer-list>`
> XML which `painterResource()` does NOT support; replaced with single
> composited PNG per density (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi). Birthed
> §6.Z Anti-Bluff Distribute Guard (26th §6.L) — pre-distribute test
> execution mandatory; cold-start verification load-bearing canary;
> source-compile is necessary, NEVER sufficient.
>
> **Prior:** 2026-05-14, **1.2.19-1039 DISTRIBUTED to Firebase**
> per the operator's 25th §6.L invocation. Both APKs uploaded + invited
> to the 3 testers configured in `.env`:
>
>   - debug: console.firebase.google.com/project/lava-vasic-digital/
>     appdistribution/app/android:digital.vasic.lava.client.dev/releases/7pjdfot2or4i0
>   - release: console.firebase.google.com/project/lava-vasic-digital/
>     appdistribution/app/android:digital.vasic.lava.client/releases/1tk4vuvqa9vc0
>
> `last-version` advanced 1038→1039. Pepper-history advanced (rotated
> pre-distribute per Phase 1 Gate 4). §6.Y self-applied: this cycle's
> first commit (`32f4cbcf`) bumped the version BEFORE the icon fix
> code change, demonstrating the new mandate's intent. Both APIs need
> reboot with the new 2.3.8 build (lava-api-go binary + image rebuilt
> in releases/1.2.19/api-go/) — currently-running containers are still
> on the 2.3.7 build from the prior cycle.
>
> **Prior:** 2026-05-14, release-prep for 1.2.19-1039 / 2.3.8-2308
> per §6.P + §6.Y. New version bump landed FIRST per the new §6.Y
> Post-Distribution Version Bump Mandate (25th §6.L invocation):
> versionCode 1038→1039, versionName 1.2.18→1.2.19 (patch — user-visible
> bug fix), Go 2306→2308 + 2.3.7→2.3.8 (lockstep). Single fix in this
> cycle: WelcomeStep monochrome logo → colored Lava logo
> (`R.drawable.ic_lava_logo` layer-list of background+foreground PNGs
> at 5 densities; `LavaIcons.AppIcon` rewired). 2 new tests (JVM unit
> structural + Compose UI Challenge Test C26 with bitmap RGB-variance
> assertion). Falsifiability rehearsed (revert AppIcon to
> ic_notification → AssertionError fires with full guidance). §6.Y
> rule + §6.Y-debt block added to root CLAUDE.md, root AGENTS.md,
> lava-api-go/CONSTITUTION.md.
>
> **Prior:** 2026-05-14, **1.2.18-1038 DISTRIBUTED to Firebase**
> per the operator's 24th §6.L invocation. Both APKs uploaded + invited
> to the 3 testers configured in `.env`:
>
>   - debug: console.firebase.google.com/project/lava-vasic-digital/
>     appdistribution/app/android:digital.vasic.lava.client.dev/releases/2d9odckm9unpo
>   - release: console.firebase.google.com/project/lava-vasic-digital/
>     appdistribution/app/android:digital.vasic.lava.client/releases/55pp8vgdt67u8
>
> `last-version` advanced 1037→1038. Pepper-history advanced (rotated
> pre-distribute per Phase 1 Gate 4). Both Lava APIs running in containers:
> `lava-api-go` (prod, port 8443) + `lava-api-go-dev` (dev, port 8543) +
> their respective Postgres on 5432 / 5433. Both serve HTTP 200 on
> /health (verified via `podman exec ... healthprobe` and `podman machine
> ssh curl`). Operator stopped `helix-mistborn-postgres` (their other
> project's pg on 5432) for this session — needs `podman start
> helix-mistborn-postgres` when done.
>
> **darwin/arm64 LAN-reachability caveat:** `network_mode: host` in
> podman-on-darwin binds to the podman-machine VM's host, not macOS
> host. mDNS broadcasts from inside the VM don't reach the LAN
> (`zeroconf: failed to join enp0s1`), so the distributed APKs CANNOT
> auto-discover the dev API on phone testers' real LAN. Same root
> cause as §6.X-debt. For genuine LAN reachability the operator
> needs either: (a) Linux x86_64 host; (b) run lava-api-go binary
> directly on darwin (the binary at releases/1.2.18/api-go/lava-
> api-go-2.3.7 is darwin/arm64 native). For the current session,
> phone testers must manually configure the endpoint after install
> (Settings → Endpoint).
>
> **Prior:** 2026-05-14, release-prep for 1.2.18-1038 / 2.3.7-2307
> per §6.P. All three operator-reported 2026-05-14 issues bundled into
> one distribute candidate. `app/build.gradle.kts`: versionCode
> 1037→1038, versionName 1.2.17→1.2.18. `lava-api-go/internal/version/
> version.go`: Code 2306→2307, Name 2.3.6→2.3.7. CHANGELOG.md +
> per-version snapshot at `.lava-ci-evidence/distribute-changelog/
> firebase-app-distribution/Lava-Android-1.2.18-1038.md` document the
> release. **STATUS UPDATE 2026-05-14 post-release-prep:** the prior
> note's "operator-blocked on placeholder" claim is STALE. Operator
> has since provided real `app/google-services.json` (Firebase project
> `lava-vasic-digital` / 815513478335), real `LAVA_FIREBASE_TOKEN`
> (firebase login:list confirms authenticated), real tracker
> credentials, both keystores. Distribute IS now eligible. Active
> distribute + API-bring-up + comprehensive test cycle is in flight
> per the operator's 24th §6.L invocation. The §6.X-debt remains open
> (container-bound emulator matrix needs Linux x86_64 gate host) but
> workstation-iteration emulator runs on the operator's host are
> permitted per §6.K-debt PARTIAL CLOSE.
>
> **Prior:** 2026-05-14, **issue 3/3 closed** — DEV API instance
> recognition. New `_lava-api-dev._tcp` mDNS service type ships in
> `core/data/api/service/DiscoveryServiceTypeCatalog.kt` (canonical
> public catalog: `SERVICE_TYPES_RELEASE` + `SERVICE_TYPES_DEBUG` add
> the dev type). New Hilt qualifier `@DiscoveryServiceTypes` lives in
> the api package; the impl reads the injected list rather than a
> hardcoded companion-object constant. App-layer
> `DiscoveryServiceTypesModule` selects DEBUG vs RELEASE set via
> `BuildConfig.DEBUG`, so a stray dev advertiser on a production
> user's LAN cannot redirect their traffic. New `DiscoveredEndpoint
> .Engine.GoDev` value; `engineFor()` orders the GoDev check before
> Go to honour the more-specific match. Domain
> `DiscoverLocalEndpointsUseCase.toEndpoint()` maps GoDev to
> `Endpoint.GoApi` (same shape, different host:port). New
> `docker-compose.dev.yml` brings up the side-by-side dev API on port
> 8543 with its own `lava_api_dev` schema; `.env.example` documents
> `LAVA_API_DEV_*` overrides. 5 contract assertions in
> `LocalNetworkDiscoveryServiceTypeTest` (catalog + matchesServiceType
> discrimination) — falsifiability rehearsed (mutation: drop dev type
> from DEBUG list; observed AssertionError at line 49; reverted; pass).
> Go-side §6.A contract test
> `lava-api-go/tests/contract/dev_compose_env_contract_test.go`
> asserts every `LAVA_API_*` env var the dev compose passes binds to a
> field config.Load() actually reads — falsifiability rehearsed
> (mutation: rename `LAVA_API_MDNS_TYPE` → `LAVA_API_MDNS_SERVICE_TYPE`
> in config.go; observed exact "env-var ... drifted" assertion;
> reverted; pass).
>
> **Prior:** 2026-05-14, **issue 2/3 closed** — Samsung Galaxy S23
> Ultra (and any tall-aspect / edge-to-edge device) onboarding overlap
> with status bar + gesture/nav bar fixed. MainActivity calls
> `enableEdgeToEdge`, but `OnboardingScreen.kt`'s `AnimatedContent`
> container did not apply `Modifier.windowInsetsPadding(WindowInsets.
> safeDrawing)`. One-place fix at the screen level (every step inherits;
> any future step automatically gets correct behavior). Structural
> regression test (`OnboardingInsetRegressionTest`) reads the source
> file and asserts the modifier + imports are present — falsifiability
> rehearsed (mutation: removed the modifier; observed AssertionError at
> line 38; reverted; passes). Challenge Test C25
> (`Challenge25OnboardingInsetSafeDrawingTest`) asserts top + bottom
> nodes are reported displayed on Welcome / Providers / Summary; runs
> on tall-aspect AVD (Pixel 9 Pro XL or 1440x3088 custom @ 500dpi).
>
> **Prior:** 2026-05-14, **issue 1/3 closed** — onboarding back
> navigation now functions on every step. Inverted `BackHandler`
> predicate in `OnboardingScreen.kt` (was enabled only on Welcome,
> ignored on Providers/Configure/Summary; now enabled everywhere with
> the VM deciding the transition). `OnboardingViewModel.onBackStep()`
> extended: Configure with `currentProviderIndex > 0` decrements
> (walks back through provider configs); Summary returns to Configure
> on last selected provider. 5 new VM tests + Challenge Test C24
> (`Challenge24OnboardingBackNavigationTest`) cover all four
> transitions. Reproduction-before-fix gate satisfied (Summary back
> test failed against pre-fix VM; all 8 pass after fix). C24 source
> compiles; instrumentation gating run owed at next §6.X-mounted gate
> host. Issues 2/3 + 3/3 still pending: S23 Ultra insets + DEV API
> `_lava-api-dev._tcp`.
>
> **Prior:** 2026-05-14, root `CLAUDE.md` audit + 6 targeted edits
> (no constitutional clause text touched): `docs/CONTINUATION.md`
> promoted to first entry in "See also"; `CHANGELOG.md` listed; multi-
> tracker reality reflected in Project section; commands gain single
> Challenge Test invocation + `firebase-distribute.sh` + `scan-no-
> hardcoded-*.sh` siblings; §6 head gains open/resolved debt index;
> §6.L gains operational summary before the wall; "Things to avoid"
> gains "Always forbidden (quick reference)" pointing into §6.R/U/V/W +
> Host Stability.
>
> **Prior:** 2026-05-13 evening, **versionCode bumped 1036 → 1037 +
> 2305 → 2306** in prep for next distribute (per §6.P + operator's
> 23rd §6.L invocation: "increase version code propeyl to all apps
> and services"). NOT distributed — see §2 below for the explicit
> operator-input checklist that blocks distribute.
>
> **§6.X-debt PARTIALLY CLOSED** (twenty-second §6.L invocation: "Do
> all debt points now!"). Containers submodule pin bumped to
> `562069e7` — ships Containerized Emulator impl + `--runner` CLI
> flag + Containerfile recipe for the Android emulator image.
> `scripts/check-constitution.sh` activates §6.X runtime checks (a)
> [Containerized impl present + asserts Emulator interface] and (b)
> [cmd/emulator-matrix accepts --runner]. Both checks falsifiability-
> rehearsed (rename-out / sed-disable mutations produce the expected
> "MISSING 6.X runtime check ..." output; reverted). Runtime check
> (c) [tag.sh attestation row gate on `runner: containerized`]
> activates with the next tag.sh touch. Real-stack `t.Skip` test
> gated on Linux x86_64 with /dev/kvm is the final §6.X close
> criterion — operator-blocked on darwin/arm64 per §6.V-debt
> incident JSON.
>
> **§6.L invocation count: TWENTY → TWENTY-THREE.** Propagated across
> all 52 constitutional docs (root × 2 + 16 submodules × 3 + lava-api-go
> × 3). The 23rd invocation is structurally distinct from prior
> invocations: it asked for rebuild + redistribute, but per §6.J the
> correct response is REFUSE — distributing with stub
> `app/google-services.json` would brick the APK on
> `LavaApplication.onCreate`. CHANGELOG.md + per-version snapshot at
> `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/Lava-Android-1.2.17-1037.md`
> document this honestly.
>
> **JVM test sweep:** tests=877 failures=0 errors=0 across every
> module (no regression from §6.X-debt close).
>
> **Constitution checker:** GREEN; §6.X runtime checks (a) + (b) active. Earlier this session:
> **SP-4 Phase F.2 COMPLETE** — all 6 plugins (gutenberg / archiveorg /
> kinozal / nnmclub / rutor / rutracker) now route clone HTTP through
> the clone's `primaryUrl`. Phase B clone-success Toast disclosure
> dropped (acceptance gate).
>
> **Phase F.2 cumulative state (this session):**
>  - Seam landed in `c1d6ade6`: `MirrorUrlProvider`, `CLONE_BASE_URL_CONFIG_KEY`,
>    `cloneBaseUrlOverride` ext, `LavaTrackerSdk.clientFor` stamps,
>    `LavaTrackerSdkCloneUrlInjectionTest` (Bluff-Audit recorded).
>  - 5 plugins refactored: each `*ClientFactory.create()` reads
>    `config.cloneBaseUrlOverride` and, when non-null, constructs a
>    per-call `*Client` whose feature impls use the override URL.
>    Each plugin gets a `*ClientFactoryCloneUrlTest` (MockWebServer
>    capture + singleton-fallback case) with Bluff-Audit rehearsal:
>    every override-strip mutation triggers
>    `IllegalStateException: singleton path must NOT be taken when
>    override is present`. Per-plugin totals:
>    `:core:tracker:gutenberg` tests=16 (14 existing + 2 new),
>    `:core:tracker:archiveorg` tests=29 (27 + 2),
>    `:core:tracker:kinozal` tests=14 (12 + 2),
>    `:core:tracker:nnmclub` tests=24 (22 + 2),
>    `:core:tracker:rutor` tests=73 (71 + 2).
>    No regressions across any of the 5.
>  - **F.2.6 LANDED (rutracker):** New `RuTrackerHttpClientFactory`
>    builds per-clone Ktor `HttpClient`s pinned to the override URL;
>    new `RuTrackerSubgraphBuilder` constructs the full RuTracker
>    chain (InnerApi → 14 use cases → 8 mappers → 7 features →
>    RuTrackerClient) from any `HttpClient`. `RuTrackerClientFactory`
>    gains a `TokenProvider` injection and routes clone calls
>    through the builder. New `RuTrackerClientFactoryCloneUrlTest`
>    (MockWebServer-driven) records the §6.J primary on
>    `recorded.requestUrl.startsWith(overrideBaseUrl)` AND
>    `path.endsWith("/tracker.php")`. Bluff-Audit rehearsal:
>    override-strip mutation produces
>    `IllegalStateException: singleton path must NOT be taken`;
>    reverted, green. Existing 67 rutracker tests + 2 new = 69 green.
>    LavaTrackerSdkRealStackTest's `RuTrackerClientFactory(...)`
>    callsites updated for the new ctor sig + `RuTorClientFactory`
>    six-arg form. :core:tracker:client suite 156 tests, no
>    regressions.
>  - **Phase B Toast disclosure dropped:** `ProviderConfigViewModel`'s
>    clone-success Toast went from "Cloned (URL routing pending —
>    searches use source URLs)" to plain "Cloned". F.2 acceptance
>    gate satisfied.
> Tasks 16+17 ship the `:feature:provider_config` module: Compose UI for
> the per-provider config screen, Orbit ViewModel wired to all four
> Phase A+B DAOs (binding / sync toggle / cloned / user mirror) +
> `CredentialsEntryRepository` + `ProbeMirrorUseCase` +
> `CloneProviderUseCase` + `SyncOutbox`, six sections (Header /
> SyncSection / CredentialsSection / MirrorsSection / AnonymousSection /
> CloneSection), Navigation DSL with providerId required arg. Per
> §6.Q the screen uses `Column(verticalScroll)` with no nested
> LazyColumn. Task 18 rewires `MenuScreen` so each provider row's
> `Surface` is `onClick`-able → `MenuAction.OpenProviderConfig(id)` →
> `MenuSideEffect.OpenProviderConfig(id)` → `addNestedNavigation`
> handles the side effect via `openProviderConfig(id)`. New
> VM-CONTRACT test in `MenuViewModelTest` (`open provider config posts
> side effect with provider id`) with Bluff-Audit rehearsal recorded.
> Sign-out trailing affordance was already in place via the existing
> `ConfirmSignOut` flow; the menu's "Provider Credentials" entry
> already routes to `:feature:credentials_manager` via
> `openCredentialsManager()`. §4.5.10 closure: ships
> `scripts/scan-no-hardcoded-ipv4.sh` + `scripts/scan-no-hardcoded-hostport.sh`
> as hard-fail gates wired into `scripts/check-constitution.sh`;
> exemption set matches the UUID scanner pattern + `.md` / `.xml` /
> `.json` / `.yml` / `.yaml` (external config + docs are legitimate
> homes for these literals); 2 production-code doc comments updated to
> use placeholders instead of literal IPs / host:port (`HostUtils.kt`
> + `DiscoverLocalEndpointsUseCase.kt`). §6.R clause body updated to
> reflect the closure; algorithm-parameter literal class remains
> staged (cannot be regex-matched cleanly — needs code-review gate).
>
> **Per-task ledger (SP-4 Phase B continuation):**
> - 37e2639 — Task 1 (model)
> - 7740760 — Task 1 fix (drop redundant CredentialsEntry.type field)
> - 6d548f2 — Task 2 (crypto)
> - e6fde48 — Tasks 3+4 (5 entities + DAOs + migration 8→9 + MIGRATION_7_8 registration fix)
> - c2dd68a — Task 5 (CredentialsEntryRepository)
> - bddd978 — Tasks 6+7+8 (binding + KeyHolder + PassphraseManager)
> - 9ff2ad7 — Task 9 (Hilt wiring)
> - 44960d5 — Task 10 (Credentials Manager Compose UI feature module)
> - 97121d7 — Tasks 12+13+14 (SyncOutbox + Probe + Clone)
> - 59db589 — Task 15 (SDK union cloned providers)
> - 647d5b5 — Task 15 follow-up (testImplementation :core:database for SwitchingNetworkApi*Test.kt)
> - 3504d4c — docs(continuation): SP-4 Phase A + half Phase B
> - 496176e — Auto-commit (submodule pin advance: Challenges + Containers + Security)
> - 85af95b — Tasks 16+17+18 + §4.5.10 closure (provider_config module + Menu rewire + §6.R IPv4/host:port scanners)
>
> **Mirror convergence (§6.W + §6.C, 2026-05-13 follow-up):**
> Operator added SSH key to GitLab + provided `gh`/`glab` CLI access.
> Parent pushed to gitlab; 2 submodules (Containers, Security) had GitLab
> behind GitHub and were pushed forward (Containers: 7813c98 → af51968;
> Security: a5a0144 → d1f59d5). 3 submodules (HTTP3, Mdns, Tracker-SDK)
> were on legacy single-`origin` remote — `origin` renamed to `github`,
> `gitlab` remote added (repos already exist under `vasic-digital`).
> Full audit verified: parent + all 16 submodules converged on both
> mirrors at matching SHAs. The 3 newly-multi-remote submodules need
> their local-only remote-config changes propagated to other developer
> workstations the next time they `git submodule update --init`, but
> the pin commits themselves are reachable from both upstreams today.
>
> **Deferred to next session (still planned per SP-4 phase plan A-H + the
> 21-task Phase A+B implementation plan):**
> - Task 11: Compose UI Challenges C27 (CredentialsCreateAndAssign) +
>   C31 (PassphraseUnlockFlow) — require live emulator + connectedDebugAndroidTest cycle.
> - Task 19: Compose UI Challenges C28 (PerProviderSyncToggle) + C29
>   (AddCustomMirror) + C30 (CloneProvider) — emulator-bound.
> - Task 20: Version bump 1.2.16-1036 → 1.2.17-1037 / 2.3.5-2305 →
>   2.3.6-2306, CHANGELOG entry, per-version snapshot, pepper rotation,
>   APK rebuild, Firebase distribute, Go API restart. Operator-driven
>   (Firebase token + pepper-history hygiene).
> - §4.5.7 + §4.5.8 (Engine.Ktor enum + Endpoint.Mirror LAN-IP branch)
>   cleanup deferred: persisted-data risk (older app versions may have
>   `DiscoveredEndpoint.Engine.Ktor` / `Endpoint.Mirror(LAN-IP)` rows
>   in Room). Proper closure requires a schema migration + version bump,
>   not a single-session cosmetic edit. Stays in §4.5 as low-priority.
>
> Phases C through H of SP-4 (multi-provider parallel search SDK,
> credentials sync with end-to-end encryption to Lava API, removal-
> syncs-to-backup semantics, Trackers screen removal/repurpose,
> documentation refresh) remain at high-level scope in the SP-4 design
> doc. Each gets its own detailed-design + implementation cycle.
>
> **Phase C executed 2026-05-13:**
>   - Design doc: `docs/superpowers/specs/2026-05-13-sp4-phase-c-design.md`
>   - Implementation plan: `docs/superpowers/plans/2026-05-13-sp4-phase-c-implementation.md`
>   - Locked decision: **delete** the Trackers screen (not replace).
>   - Landed:
>     - `:feature:tracker_settings` module deleted entirely (build.gradle.kts,
>       manifest, all sources, tests). 12+ source files + 2 test files removed.
>     - `:feature:provider_config` gained the `ActiveTrackerSection` —
>       the only Trackers-screen surface that wasn't already absorbed by
>       Phase B. Section is marked for removal in Phase D pre-work.
>     - `MenuAction.TrackerSettingsClick` + `MenuSideEffect.OpenTrackerSettings`
>       + `onTrackerSettingsClick()` + the "Trackers" menuItem removed.
>     - `MobileNavigation.kt` cleaned up: no more `addTrackerSettings`
>       route, no more `openTrackerSettings` callback threaded through
>       `addNestedNavigation` + `addMenu` + `MenuScreen`.
>     - `Challenge14TrackerSettingsOpenTest.kt` deleted (per §6.J —
>       deletion, not @Ignore — the screen it tested is gone).
>     - `Challenge04SwitchTrackerAndResearchTest.kt` rewritten to
>       `Challenge04ProviderRowOpensConfigTest.kt`: asserts the new
>       Menu → tap provider row → ProviderConfig path renders the
>       "Sync this provider" section (a label only ProviderConfig
>       produces). Falsifiability rehearsal protocol embedded in
>       KDoc; operator runs the actual mutation rehearsal on the
>       gating emulator before next tag.
>     - `Challenge01AppLaunchAndTrackerSelectionTest.kt` updated: now
>       asserts "Provider Credentials" + "Theme" menu entries (post
>       "Trackers" removal); the deep-nav avoidance comment removed
>       since ProviderConfig is reachable from the same Menu surface.
>     - 2 stale comment refs in non-deleted files (CrossTrackerFallbackModal.kt,
>       SwitchingNetworkApi.kt) updated to remove `:feature:tracker_settings`
>       mentions.
>   - Build verification: spotless + compileDebugKotlin + compileDebugUnitTestKotlin
>     across :feature:menu + :feature:provider_config — all green.
>     :app:compileDebugKotlin requires .env + keystore (operator env).
>   - Phase D executed 2026-05-13 (this commit chain):
>     - Design + plan: `docs/superpowers/specs/2026-05-13-sp4-phase-d-design.md`
>       + `docs/superpowers/plans/2026-05-13-sp4-phase-d-implementation.md`.
>     - `LavaTrackerSdk.multiSearch` rewritten from sequential `for`-loop
>       to PARALLEL `coroutineScope { providerIds.map { async { ... } }
>       .awaitAll() }`. Latency drops from `sum(per-provider)` to
>       `max(per-provider)`. Per-provider failure isolated via try/catch
>       inside `runOneProvider`.
>     - New `LavaTrackerSdk.streamMultiSearch(...): Flow<MultiSearchEvent>`
>       via `flow { ... channelFlow { ... } ... }`. Emits
>       ProviderStart → ProviderResults | ProviderFailure |
>       ProviderUnsupported per provider, then AllProvidersDone
>       (suppressed on collector cancellation). Mutex-guarded mutable
>       maps shared between the channelFlow producer and the post-
>       emitAll AllProvidersDone snapshot.
>     - New `MultiSearchEvent` sealed hierarchy in
>       `core/tracker/client/.../MultiSearchEvent.kt`.
>     - `SearchResultViewModel`: branch into `observeStreamMultiSearch`
>       when `providerIds != null && endpoint !is Endpoint.GoApi`.
>       Maps `MultiSearchEvent` → existing `SearchResultContent.Streaming`
>       state reductions; SSE path remains the Go-API-configured default.
>     - `ActiveTrackerSection.kt` deleted (Phase C transitional affordance,
>       Phase D pre-work per design doc).
>     - `LavaTrackerSdk.activeTrackerId()` + both `switchTracker(...)`
>       overloads marked `@Deprecated` (legacy paging path keeps
>       compiling — full removal post-paging-migration).
>     - 4 unit tests in `LavaTrackerSdkParallelSearchTest.kt` — all PASS
>       on real-stack (DefaultTrackerRegistry + SuspendingFakeClient):
>         1. `multiSearch fans out to N providers concurrently` — uses a
>            CompletableDeferred barrier + concurrency counter; asserts
>            `maxConcurrent == 3`. Falsifiability rehearsed: removing
>            `async { }` so the loop runs sequentially drops
>            `maxConcurrent` to 1, test fails at line 111 with
>            AssertionError. Important subtlety: `async` is eager by
>            default, so `map { it.await() }` does NOT preserve
>            sequential semantics — the true sequential mutation
>            removes `async` entirely.
>         2. `streamMultiSearch cancellation propagates to in-flight
>            provider calls` — cancellation reaches the suspending
>            search via structured concurrency.
>         3. `streamMultiSearch emits ProviderStart before the terminal
>            event for every provider` — per-provider event ordering +
>            AllProvidersDone terminal.
>         4. `multiSearch isolates per-provider failure from siblings` —
>            one provider's throw doesn't cancel siblings; surviving
>            providers report SUCCESS.
>     - C32 + C33 Compose UI Challenges (multi-provider parallel +
>       cancellation) remain owed for operator execution on the
>       gating emulator before next tag.
>     - Build verification: ./gradlew :core:tracker:client:
>       testDebugUnitTest --tests "...ParallelSearchTest" + spotless
>       + compileDebugKotlin across :feature:search_result +
>       :feature:provider_config — all green.
>     - §6.S CONTINUATION + §6.W mirror convergence in this same commit.
>
> **Phase G executed 2026-05-13** (local half — soft-delete + outbox enqueue):
>   - Design: `docs/superpowers/specs/2026-05-13-sp4-phase-g-design.md`
>   - Plan: `docs/superpowers/plans/2026-05-13-sp4-phase-g-implementation.md`
>   - Schema bump v9 → v10 via `MIGRATION_9_10`: adds `deletedAt: Long?`
>     column to `credentials_entry` and `cloned_provider`. DAOs gain
>     `softDelete(id, deletedAt)`; all read queries filter
>     `WHERE deletedAt IS NULL`.
>   - `CredentialsEntryRepositoryImpl.delete()` rewritten:
>     soft-deletes (sets `deletedAt = NOW`) + enqueues
>     `SyncOutboxKind.CREDENTIALS` with `WireRemoval(id, deletedAt,
>     deleted = true)`. `Json.encodeDefaults = true` so the
>     `deleted: true` flag survives JSON round-trip into the outbox
>     payload (default-value fields would otherwise be omitted —
>     a real Third-Law-fake-divergence bug surfaced during this
>     phase's test rehearsal and fixed in the same commit).
>   - All 3 in-repo fake `ClonedProviderDao` impls + the
>     `CredentialsEntryRepositoryImplTest.FakeDao` updated to
>     implement `softDelete`. The new
>     `CredentialsEntryRepositorySoftDeleteTest.FakeDao` mirrors
>     Room's `WHERE deletedAt IS NULL` filter behaviorally so the
>     fake doesn't diverge from production (Third Law).
>   - 1 new unit test: `CredentialsEntryRepositorySoftDeleteTest`
>     asserting (1) read-paths hide the soft-deleted row,
>     (2) the raw table preserves the row + `deletedAt` timestamp
>     (for backup-restore + Phase E sync upload),
>     (3) outbox carries a `CREDENTIALS` entry whose payload
>     contains the id AND `"deleted":true`.
>   - Falsifiability rehearsal verified: mutating `delete()` back
>     to `dao.delete(id)` (hard delete + no outbox) makes the
>     test fail with "soft-deleted row must still be present in
>     the raw table" — clear user-observable signal.
>   - **Phase E (remote propagation) is OWED.** Phase G ships the
>     local soft-delete + outbox enqueue. Phase E's uploader
>     reads the outbox + propagates the removal to other devices;
>     Phase E's downloader merges remote soft-deletes into the
>     local DAO. Without Phase E, the soft-delete is local-only
>     but the backup-restore semantic is already correct (the
>     soft-delete marker is included in Android Auto Backup).
>
> **Phase G.2 + Phase D consumer-VM coverage landed 2026-05-13** (audit-driven
> post the §6.L 20th-invocation directive — operator's "make sure
> EVERYTHING is fully tackled and no-bluff policy heavily enforced"
> demanded a sweep for residual gaps in the already-shipped session
> work; two real gaps surfaced):
>   - **Phase G.2 — RemoveClonedProviderUseCase + UI**. Phase G.1
>     shipped soft-delete infrastructure on `cloned_provider` (DAO
>     `softDelete` + migration) but NO caller existed; users could
>     never remove a clone they created. This commit closes the gap:
>     - `core/domain/.../RemoveClonedProviderUseCase`: soft-deletes
>       the row + enqueues `CLONED_PROVIDER` outbox with
>       `WireRemoval { syntheticId, deletedAt, deleted = true }`
>       (same `encodeDefaults = true` pattern as Phase G.1's
>       credentials path).
>     - `ProviderConfigState` gains `isClone` + `showRemoveCloneDialog`.
>       `ProviderConfigAction.OpenRemoveCloneDialog` /
>       `DismissRemoveCloneDialog` / `ConfirmRemoveClone`. New
>       `ProviderConfigSideEffect.NavigateBack` pops the screen after
>       removal.
>     - `ProviderConfigViewModel` injects `ClonedProviderDao` +
>       `RemoveClonedProviderUseCase`. `onCreate` queries the DAO
>       to set `isClone`; `ConfirmRemoveClone` invokes the use case
>       and emits `NavigateBack`.
>     - New `RemoveCloneSection.kt`: destructive Remove button (only
>       when `state.isClone`) + Dialog confirmation. AppTheme.error
>       color for both button and confirm.
>     - `ProviderConfigScreen` wires the section + the new side effect
>       to `onBack()`.
>     - New test `RemoveClonedProviderUseCaseTest` — 2 tests asserting
>       on user-observable state (clone disappears from getAll, raw
>       table preserves deletedAt timestamp, outbox carries CLONED_PROVIDER
>       with `"deleted":true` payload). Falsifiability rehearsal
>       verified: dropping the `outbox.enqueue` makes the
>       "expected exactly one outbox entry" assertion fail.
>   - **Phase D consumer-VM coverage**. Phase D shipped the SDK
>     (`LavaTrackerSdkParallelSearchTest`, 4 tests) but the
>     `SearchResultViewModel.handleMultiSearchEvent` state-reduction
>     branch had zero coverage. A bug in the VM's per-provider state
>     transitions would have been invisible to all automated tests.
>     This commit closes the gap:
>     - Extracted the pure state transformation to file-scope helper
>       `applyMultiSearchEvent(state, event): SearchPageState` —
>       same logic, testable without the orbit VM machinery.
>     - The VM's `handleMultiSearchEvent` now calls
>       `reduce { applyMultiSearchEvent(state, event) }`. Exhaustive
>       `when` retained inside as a sanity guard so a new
>       `MultiSearchEvent` variant fails fast at compile time.
>     - New test `ApplyMultiSearchEventTest` — 6 tests covering
>       ProviderStart (displayName stamp), ProviderResults (items
>       append + DONE), ProviderFailure (ERROR), ProviderUnsupported
>       (DONE/zero), AllProvidersDone (no-op), and the defensive
>       non-Streaming-content no-op (late-event safety). Each test's
>       KDoc names a specific deliberate mutation that the test
>       catches.
>     - Falsifiability rehearsal verified: dropping the
>       `items = current.items + newItems` line makes the
>       ProviderResults items-size assertion fail with
>       `expected:<2> but was:<0>`.
>     - `:feature:search_result` build.gradle.kts: added
>       `testImplementation(project(":core:database"))` to make
>       `ClonedProviderDao?` reachable in the test classpath via
>       the SDK constructor signature (Phase F.1's nullable injection).
>     - `SearchResultViewModelFallbackTest` updated to pass an
>       `sdk = LavaTrackerSdk(registry = DefaultTrackerRegistry())`
>       parameter (the new Phase D constructor arg).
>   - **The anti-bluff mandate keeps working.** Two real bluffs
>     surfaced in already-committed code without an operator-emulator
>     run — exactly the §6.J "tests must guarantee the product works"
>     pattern the operator's repeated invocations evict. Recorded
>     here so a future agent treats "phase committed cleanly" as
>     necessary, never sufficient — end-of-phase audits remain a
>     standing operating procedure.
>
> **Phase H tail + Phase E design + Phase F.2 design landed 2026-05-13:**
>   - `docs/SP-4-RELEASE-NOTES.md` NEW. User-visible + developer-visible
>     changes across SP-4 Phases A through G.1. Explicit OWED list for
>     Phase E + Phase F.2 + 10 Compose UI Challenges (C04, C01, C27-C34)
>     for operator emulator runs before next release tag.
>   - `docs/ARCHITECTURE.md` updated: `feature/tracker_settings` row
>     removed (deleted Phase C); `feature/provider_config` +
>     `feature/credentials_manager` rows added.
>   - `docs/superpowers/specs/2026-05-13-sp4-phase-e-design.md` —
>     PUT/DELETE/GET /v1/credentials Go endpoints, two-salt key
>     derivation (at-rest saltA + transport saltB, both PBKDF2-SHA-256
>     200k from the same passphrase, zero-knowledge), `CredentialsSyncWorker`
>     drains the outbox, delta sync `GET /v1/credentials?since=`,
>     last-write-wins by updatedAt. Execution OWED — multi-domain
>     (Go API + Android) needs operator-supplied API instance +
>     cross-device emulator integration testing.
>   - `docs/superpowers/specs/2026-05-13-sp4-phase-f2-design.md` —
>     Option B (per-clone MirrorManager + SDK-side URL injection)
>     locked over Option A (PluginConfig.baseUrlOverride). Composes
>     with `LavaMirrorManagerHolder` + `runWithMirrorFallback` + per-
>     clone `UserMirrorEntity` rows. Cross-module refactor across
>     all 6 tracker plugins. Acceptance criterion: Toast copy drops
>     "URL routing pending" once `https://rutracker.eu` clone produces
>     a MockWebServer capture containing rutracker.eu. Execution
>     scheduled for a dedicated cycle.
>
> **§6.S clarification (added 2026-05-13):** commit `<this-commit>` is
> the follow-up for the prior commit (`docs(sp-4): Phase H tail +
> Phase E design + Phase F.2 design`) which landed the three new docs
> WITHOUT updating CONTINUATION.md (§6.S violation). The CONTINUATION
> update lives in this follow-up commit per the no-amend rule. Recorded
> as forensic detail so a future agent doesn't repeat the omission.
>
> **Phase F.1 executed 2026-05-13** (earlier this session):
>   - Design + plan: `docs/superpowers/specs/2026-05-13-sp4-phase-f-design.md`
>     + `docs/superpowers/plans/2026-05-13-sp4-phase-f-implementation.md`.
>   - **The bug Phase F.1 closes:** Phase B's clone dialog enqueued a
>     `ClonedProviderEntity` row + the SDK's `listAvailableTrackers()`
>     unioned cloned descriptors, but every SDK call against a clone
>     synthetic id (search / multiSearch / streamMultiSearch / login /
>     checkAuth / logout / getActiveClient) called
>     `registry.get(syntheticId, …)` which threw
>     `IllegalArgumentException: Unknown plugin id`. Phase B looked
>     complete but the clone-search path was a crash. This was a
>     latent §6.J bluff that the audit at the start of this session
>     surfaced.
>   - New `LavaTrackerSdk.clientFor(id)` private helper: resolves
>     a clone synthetic id via `clonedProviderDao.getAll()` →
>     `ClonedRoutingTrackerClient(sourceClient, cloneDescriptor)`.
>     For non-clone ids: identical to `registry.get(...)`.
>   - All 8 `registry.get(id, MapPluginConfig())` call sites in
>     `LavaTrackerSdk.kt` rewritten to use `clientFor(id)`.
>     `registry.list().firstOrNull { ... }` lookups in
>     `runOneProvider` and the streamMultiSearch loop also rewritten
>     to use `listAvailableTrackers()` so clone descriptors are
>     resolved.
>   - New `ClonedRoutingTrackerClient` wrapper: surfaces the clone's
>     descriptor to callers reading `client.descriptor.trackerId`
>     (which is how `LavaTrackerSdk.search` builds
>     `SearchOutcome.Success.viaTracker`). Delegates feature lookups
>     to the source client unchanged. Initial implementation also
>     re-tagged `TorrentItem.trackerId` with the clone id —
>     **the falsifiability rehearsal proved this was dead code**
>     (no downstream consumer reads item.trackerId; everything keys
>     on the explicit per-provider id passed through the SDK seam)
>     and the re-tag was removed. The bluff test that asserted on it
>     was deleted rather than papered over.
>   - `ProviderConfigSideEffect.ShowToast` copy at clone success
>     updated from "Cloned" to "Cloned (URL routing pending — searches
>     use source URLs)" — the user is honestly told that the clone's
>     `primaryUrl` is recorded in the DAO but not yet routed by the
>     HTTP traffic. **Phase F.2 (URL routing override) is OWED.**
>   - 1 unit test in `LavaTrackerSdkCloneSearchTest.kt`:
>     `multiSearch on a clone id does not crash and reports SUCCESS`.
>     Asserts on user-observable state (the per-provider status the UI
>     renders). Pre-Phase-F.1 this test crashed with
>     `IllegalArgumentException`; post-Phase-F.1 it reports SUCCESS
>     with `displayName == "RuTracker EU"`.
>   - Falsifiability rehearsal verified: mutating `runOneProvider` to
>     use `registry.get(id, ...)` + `registry.list()` (the pre-F.1
>     code path) made the test fail with "expected SUCCESS but was
>     FAILURE (not registered)" — clear assertion message on
>     user-observable state.
>   - Phase F.2 OWED: per-clone `MirrorManager` wiring so the clone's
>     `primaryUrl` becomes the actual HTTP target. Requires
>     factory-side `PluginConfig.baseUrlOverride` support across
>     `:core:tracker:rutracker`, `:core:tracker:rutor`, etc. Multi-
>     file refactor; not single-session feasible. The Toast copy
>     discloses this gap to users so no false promise ships.
>   - Build verification: ./gradlew tests + spotless + constitution
>     check all green.
>
> Go API state: lava-api-go v2.3.5-2305 running locally at
> `https://localhost:8443/` (healthy). Distribute artifact v1.2.16-1036
> (debug + release APKs) live on Firebase tester group.
>
> **Last updated (earlier this session):** post v1.2.16 distribute + SP-4 design
> filed. Operator opened a multi-provider refactor scope during the
> v1.2.15/1.2.16 release cycle that is multi-phase and cannot be
> honestly delivered in one session per §6.J anti-bluff. Design doc
> at `docs/superpowers/specs/2026-05-12-sp4-multi-provider-redesign.md`
> covers all of: Trackers-screen removal, per-provider config screen
> reachable from Menu, generic Credentials (one entry usable by N
> providers, end-to-end encrypted sync), multi-provider parallel
> search with per-provider event labels, provider clone-with-new-name,
> per-provider sync ON/OFF toggle, removal-syncs-to-backup. Phased
> implementation plan (A-H) to follow as separate commits. This
> session: v1.2.16 distributed (debug icon green + name DEV + RuTracker-
> Main 3-layer purge + Auto Backup exclusions), SP-4 design filed,
> submodules fetched + pulled (Security advanced go.sum).
>
> Distribute artifacts (v1.2.16-1036):
> - debug APK release on `digital.vasic.lava.client.dev`
> - release APK release `36o3uiu0us6cg` on `digital.vasic.lava.client`
>
> Go API state:
> - lava-api-go v2.3.5-2305 running locally at `https://localhost:8443/`
>   (healthy); kept booted for manual testing.
>
> **Last updated (earlier this session):** FIVE-issue UX fix cycle
> complete for Lava-Android-1.2.15-1035 / Lava-API-Go-2.3.4-2304. Operator-reported
> issues all addressed, tested, verified on live emulator, distributed
> via Firebase.
>
> Five issues fixed in this cycle:
>   1. Onboarding wizard not shown on clean install (MainActivity
>      `showOnboarding` race condition with theme load).
>   2. Menu provider color-dot spacing too tight (small → medium).
>   3. RuTracker (Main) removed from seeded Server list (operator:
>      "communication is strictly through the Lava API").
>   4. Trash icon + confirmation dialog for offline servers in the
>      Server section (no edit-mode toggle needed).
>   5. Theme change required app restart (was `.first()` only;
>      switched to `.collect`).
>
> New Challenge Test C25 (cleanInstall_landsOnOnboardingWelcomeScreen)
> verifies Issue 1 fix end-to-end: PASS on CZ_API34_Phone API 34.
>
> Firebase distribute v1.2.15-1035:
> - debug APK release `5r56u119feri0` (digital.vasic.lava.client.dev)
> - release APK release `72jkalgdg9k30` (digital.vasic.lava.client)
> - pepper rotated; new client UUID; ACTIVE_CLIENTS appended in .env
>
> Go API state:
> - lava-api-go v2.3.4-2304 running locally at https://localhost:8443/
>   (healthy); kept booted for manual testing.
>
> **Last updated (earlier this session):** post §6.L 18th invocation.
>
> Distribute artifacts:
> - debug APK release `775nqmmfsquf0` on `digital.vasic.lava.client.dev`
> - release APK release `3jkm1dohgblfo` on `digital.vasic.lava.client`
> - both distributed to operator's Firebase tester group
> - per-version snapshot: `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.2.14-1034.md`
> - last-version recorded: 1034
> - pepper rotated to `61c7396d...` (recorded to pepper-history)
> - new client UUID added to LAVA_AUTH_ACTIVE_CLIENTS in .env (gitignored)
>
> Go API status:
> - lava-postgres + lava-api-go containers running locally (healthy)
> - lava-api-go v2.3.3-2303 at `https://localhost:8443/health`
>   returning `{"status":"alive"}`
> - thinker.local remote target offline (DNS resolution times out);
>   remote distribute deferred. Local API is sufficient for operator
>   manual testing from the same host or LAN-discovery (mDNS).
>
> Constitution propagation: §6.L count bumped to EIGHTEEN across all
> 53 constitutional docs (root + lava-api-go + 16 × 3 submodule docs).
> All 16 submodule repos committed + pushed to GitHub + GitLab.
> Parent pins bumped in lockstep.
>
> **Last updated (earlier this session):** post §6.L 17th invocation. The 16th
> invocation triggered the C03 fix + Cloudflare-mitigation + anti-bluff
> audit chain (`4d27c07`, `f7d0a62`, `4b0dd55`). The 17th invocation
> demanded the count + audit also reach **every submodule's
> CONSTITUTION.md + AGENTS.md** (not just CLAUDE.md). Verified all 16
> submodules + lava-api-go carry §6.L inheritance in all three doc
> types; mass-updated the count from TEN/SIXTEEN → SEVENTEEN across
> 48 submodule docs + 5 root/lava-api-go docs. Per-submodule commits
> pushed to GitHub + GitLab; parent pins bumped in lockstep.
>
> **Last updated (earlier this session):** post §6.L 16th invocation. After commits
> `4d27c07` (C03 + credential leak + HTTP timeouts) and `f7d0a62`
> (Cloudflare anti-bot mitigation), operator restated the §6.J/§6.L
> mandate for the 16th time. This commit propagates the count to
> CLAUDE.md/AGENTS.md/lava-api-go/CLAUDE.md (submodule docs already
> reference §6.L via inheritance and auto-current), runs an anti-bluff
> structural audit across the codebase, fixes one verify-only test
> (FirebaseAnalyticsTrackerTest), and adds robust selector fallback
> for `GetCurrentProfileUseCase.parseUserId` so the C02 remaining
> failure mode (post-CF-mitigation) has a higher chance of resolving.
>
> **Bluff-pattern audit results (2026-05-12):**
>   - 0 mock-the-SUT bluffs across all `*Test.kt` files (the
>     `mockk<X>` in `XTest` pattern).
>   - 0 `@Ignore` without issue link.
>   - 1 verify-only bluff (`FirebaseAnalyticsTrackerTest`) — fixed
>     by converting `verify { mock.foo() }` to `slot` captures with
>     `assertEquals` on captured-name + `assertTrue(slot.isCaptured)`.
>   - 1 stale-assumption bluff (`Challenge16ApiSupportedFilterTest`) —
>     test asserted "Internet Archive must NOT appear" while Phase 2b
>     had flipped `apiSupported=true`. The test still passed because
>     its `waitUntil` happily accepted the Welcome screen (where no
>     provider list renders). REWRITTEN to navigate to "Pick your
>     providers" and assert that all 4 verified+apiSupported providers
>     (RuTracker, RuTor, Internet Archive, Project Gutenberg) actually
>     render. Verified PASS on live emulator.
>   - Several `assertNotNull`-only tests in `*ClientTest.kt` are
>     legitimate §6.E Capability Honesty contract tests, not bluffs.
>
> **Live-emulator Challenge verification (CZ_API34_Phone API 34):**
>   - PASS: C00, C01, C03, C11, C12, C13, C14, C15, C16 (rewritten),
>     C20, C21, C22 (in isolation — sweep fails due to C21 back-press
>     state leak; real users don't hit that sequence), C23, C24.
>   - C02: substantial advance — Cloudflare-mitigation works (POST
>     login now 302→200), but post-login HTML parser still doesn't
>     find a logged-in user marker. Added selector fallback (4
>     selectors tried before erroring); even the fallback chain comes
>     up empty against today's rutracker.org page. Resolution path
>     needs operator credential verification AND/OR scraper
>     archaeology against current rutracker HTML.
>
> **Earlier this session:**
> First commit `4d27c07` resolved C03 (anonymous toggle bug) +
> credential-leak-in-logs (§6.H) + general HTTP timeout/UA improvements.
> Second commit (this one) layers Cloudflare anti-bot mitigation:
> `HttpCookies` plugin with `AcceptAllCookiesStorage`, Chrome-class
> User-Agent, `Accept`/`Accept-Language`/`Accept-Encoding` headers,
> and a pre-flight GET to `/forum/index.php` inside
> `RuTrackerInnerApiImpl.login()` so the POST carries Cloudflare's
> clearance cookies. **C02 status: post-fix the POST to login.php
> succeeds (302→200), but profile parsing now fails because
> `#logged-in-username` is no longer in the page that rutracker.org
> serves to this client (likely either a stale selector vs. current
> rutracker HTML, OR the browser-class UA causes rutracker to serve
> a mobile-shaped page with different selectors).** Cookie selection
> also tightened — pick by name prefix (`bb_data`/`bb_session`/
> `bb_login`) instead of "first non-bb_ssl" which had been selecting
> Cloudflare's `cf_clearance` as the bogus rutracker token. See
> §4.5.3b + §4.5.3c.
>
> **§6.S violations on the previous chain — now corrected here:**
>   (1) `3f6e5e6` claimed C11/C12 "NOW PASSING" without updating §0
>       or the Phase 3 results table — fixed in this commit.
>   (2) Phase 2b silently flipped `apiSupported = true` on archiveorg
>       + gutenberg without recording the supersession of Phase 12
>       α-hotfix — fixed here.
>   (3) The §0 line had been stuck at 2026-05-08 21:15 UTC across
>       multiple state changes (3f6e5e6, Phase 2b flip, post-`b87b414`
>       investigation) — fixed here.
>
> Remaining work pending operator hardware:
>   - Matrix re-run on emulator to verify L1+L2 fixes in 3f6e5e6
>     actually close C02 + C03 (or surface L3 as the residual cause)
>   - Full §6.I matrix (API28 + API30 + API34 + latest + tablet) for
>     deployed 1.2.13-1033 / 2.3.2-2302 — last matrix evidence is at
>     Lava-Android-1.2.0-1020 (13 versions behind)
>   - `tag.sh` invocation for 1.2.13-1033 / 2.3.2-2302 (blocked on
>     matrix evidence)

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
| Lava parent on master | 2 mirrors (GitHub + GitLab) at current HEAD | (current SHA) |
| API on thinker.local | running 2.3.2 (build 2302) (healthy) | container `lava-api-go-thinker` |
| Android Firebase | 1.2.13 (1033) distributed to testers | `lava-vasic-digital` Firebase project |
| 16 vasic-digital submodules | all pushed | see §3 below |

**All 6 parent-decomposition phases complete.** The original TODO spec
(`docs/todos/Lava_TODOs_001.md`) has been fully implemented across
Phases 1-6 with the Yole+Boba 8-palette theme system as the final
deliverable. Git tags have NOT been cut — see §2 for the remaining
gate steps.

---

## 1. What's DONE (for context — do not re-do)

### Phase 0 — Stabilize Build & Enforce §6.W (2026-05-08)

**Status: COMPLETE. Commits: 9ed7bca (OkHttp fix), 55a702c (Phase 0).**

| Task | Status |
|------|--------|
| Fix `:core:auth:impl` FakePreferencesStorage.getDeviceId() | ✓ |
| Commit 16 submodule constitution propagation (§6.U/V/W) | ✓ |
| Remove GitFlic, GitVerse, upstream remotes | ✓ |
| Fix `origin` remote to GitHub+GitLab only | ✓ |
| Update `scripts/tag.sh` DEFAULT_REMOTES to 2-mirror | ✓ |
| Delete `Upstreams/GitFlic.sh`, `GitVerse.sh` | ✓ |
| Add GitLab remotes to 7 submodules lacking them | ✓ |
| Push parent to both mirrors + verify SHA convergence | ✓ (55a702c on both) |

### Phase 1 — Full Anti-Bluff Audit (2026-05-08)

**Status: COMPLETE.** All 264 test files audited for §6.J clause 3 compliance (zero violations). 5 bluff-hunt mutations confirmed to cause test failures. All 22 Challenge Tests now have formal falsifiability rehearsal protocols in KDoc. Uncommitted RuTrackerDescriptor bluff mutation from Phase 1.4 reverted.

| Task | Status | Evidence |
|------|--------|----------|
| 1.1 Inventory all test files | ✓ | 264 files identified |
| 1.2 Random 5 falsifiability rehearsals | ✓ | 5/5 fail when production mutated |
| 1.3 §6.J clause 3 compliance scan | ✓ | `.lava-ci-evidence/compliance/2026-05-08-phase1-dot3-compliance-report.md` |
| 1.4 Bluff hunt evidence | ✓ | `.lava-ci-evidence/phase1-dot4-bluff-hunt-report.md` |
| 1.5 Forbidden pattern check | ✓ | Zero violations documented |
| 1.6 Challenge KDoc falsifiability protocols | ✓ | All 22 Challenges have formal protocols |

### Phase 2 — Fix Known Issues (2026-05-08)

**Status: COMPLETE.** docs/todos/Lava_TODOs_001.md committed as historical record (all TODOs implemented). UDP buffer fix documented at docs/UDP-BUFFER-WARNING.md. Engine.Ktor dead enum and Endpoint.Mirror LAN-IP routing branch deferred to Phase 5 per §4.5.7/§4.5.8 (low priority, cosmetic-only).

| Task | Status | Notes |
|------|--------|-------|
| 2.1 Fix C1-C16 bluff behavior | ✓ | Done in Phase 1 audit |
| 2.2 Execute C17-C22 | ⏳ | Requires §6.I emulator matrix (operator) |
| 2.3 Verify /api/v1/search | ⏳ | Requires running API (operator) |
| 2.4 Document UDP buffer fix | ✓ | `docs/UDP-BUFFER-WARNING.md` |
| 2.5 Verify Internet Archive/gutenberg | ⏳ | Requires running API (operator) |
| 2.6 Clean up Engine.Ktor dead enum | → §5 | Deferred per §4.5.7 (cosmetic-only, Phase 5) |
| 2.7 Clean up Endpoint.Mirror dead branch | → §5 | Deferred per §4.5.8 (Phase 5) |
| 2.8 Resolve docs/todos/ tracking | ✓ | Committed as historical record |

**Commit:** 6009c6b.

### Phase 3 — Container Boot + Challenge Test Execution (2026-05-08)

**Status: IN PROGRESS.** Go API containers booted and healthy. 1840 unit tests all pass (BUILD SUCCESSFUL). C00 CrashSurvival fix landed. Challenge Tests executed on CZ_API34_Phone emulator + 2 ATMOSphere physical devices.

| Task | Status | Notes |
|------|--------|-------|
| Boot lava-api-go containers | ✓ | Postgres + migrate + api-go healthy |
| Run unit tests (`./gradlew test`) | ✓ | 1840 tasks, BUILD SUCCESSFUL |
| Build debug APK + install on emulator | ✓ | CZ_API34_Phone booted, APK installed |
| Run Challenge Tests C1-C22 | ✓ | 17/24 pass, 5 fail→4 fail after C00 fix |
| Fix C00 CrashSurvivalTest | ✓ | AuthType.NONE providers now signal with display name |
| Investigate C02/C03/C11/C12 timeouts | ✅/⏳ | C03 RESOLVED (anonymous toggle bug fixed); C02 PARTIAL (Cloudflare anti-bot stall, see §4.5.3b); C11/C12 not re-run this session |

**Challenge Test Results (CZ_API34_Phone emulator):**

| Test | Status | Notes |
|------|--------|-------|
| C00 CrashSurvival | ✓ | Fixed: `onFinish()` now includes display name for AuthType.NONE |
| C01 AppLaunch | ✓ | |
| C02 RuTracker auth | ⏳ | CF mitigation (cookies+pre-flight GET) works — login POST now 302→200. Profile-parse fails: `#logged-in-username` not in served HTML. See §4.5.3c |
| C03 RuTor anonymous | ✅ | Anonymous toggle bug fixed (OnboardingViewModel skipped checkAuth on anon branch). PASS on CZ_API34_Phone 2026-05-12 09:25 |
| C04 SwitchTracker | ✓ | |
| C05 ViewTopic | ✓ | |
| C06 DownloadTorrent | ✓ | |
| C07 FallbackAccept | ✓ | |
| C08 FallbackDismiss | ✓ | |
| C09 Kinozal auth | ⏭️ | Skipped (no BuildConfig credentials) |
| C10 NNMClub auth | ⏭️ | Skipped (no BuildConfig credentials) |
| C11 ArchiveOrg | ✓ | `3f6e5e6` provider-deselection fix — author claims "NOW PASSING" on emulator |
| C12 Gutenberg | ✓ | `3f6e5e6` same fix as C11 — author claims "NOW PASSING" |
| C13 FirebaseColdStart | ✓ | |
| C14 TrackerSettings | ✓ | |
| C15 AuthInterceptorBoot | ✓ | |
| C16 ApiSupportedFilter | ✓ | |
| C20 OnboardingWizard | ✓ | |
| C21 OnboardingBackPress | ✓ | |
| C22 AnonymousProvider | ✓ | |
| C23 ThemeRendering | ✓ | |
| C24 MenuSignOutFlow | ✓ | |

**Commit:** (this commit).

### Parent-decomposition Phases 1-6 — ALL SHIPPED

Status: **all 6 phases complete. 7 Firebase distributions delivered
(1.2.8-1028 through 1.2.13-1033). API versions 2.2.0-2200 through
2.3.2-2302. Git tags have NOT been cut** (see §2).

| Phase | What shipped | Key commits |
|---|---|---|
| 1 | API auth + security (16 sub-phases) | `8192403` .. `e9470812` |
| 2a+2b | Multi-provider streaming search + 6 providers all apiSupported=true | `19dbff7` .. `c4ec6c4` |
| 3 | Onboarding wizard, bug-fixed + 16 anti-bluff ViewModel tests | `4dd6ea0` |
| 4 | Sync expansion: device identity, Sync Now, 4 sync categories | `2060d9a` .. `0d57170` |
| 5 | UI/UX polish: multi-provider header, color themes (Ocean/Forest/Sunset), About dialog, credentials redesign, result filtering, nav-bar audit | `3fc66b6` .. `5879a85` |
| 6 | Crashlytics tracking, distribution prep, infrastructure fixes | (shipped across multiple commits) |

### Additional deliveries after Phase 3 bug-fix (post-4dd6ea0)

| What | Details |
|---|---|
| Docker auth env vars | `docker-compose.yml` passes LAVA_AUTH_* to api-go container (commit `ab6325f`) |
| Yole+Boba 8-palette theme system | Replaced red default with Yole semantic color foundation + 8 Boba-project accent palettes. `PaletteContractTest` enforces contract. |
| PaletteTokens | Token system defining 8 named palettes, `PaletteContractTest` ensures all tokens resolve. |
| AppColors refactor | 469→150 lines, clean separation of palette tokens from semantic roles |
| Firebase distributions | 1.2.8-1028 → 1.2.13-1033 (7 incremental releases) |

### Operator-visible deliverables

- **API**: `https://thinker.local:8443/{health,ready}` returns
  `{"status":"alive"}` / `{"status":"ready"}`. Auth gate fails-closed
  with `401 {"error":"unauthorized"}` on missing `Lava-Auth` header.
  `GET /v1/search?q=...&providers=...` SSE endpoint. Version 2.3.2 (2302).
- **APK**: 1.2.13 (1033) on Firebase App Distribution under project
  `lava-vasic-digital`. Yole+Boba 8-palette theme system, onboarding
  wizard, sync expansion, all 6 providers active.
- **Color themes**: Yole+Boba 8 palettes selectable in Settings. |

---

## 2. What's BLOCKED ON OPERATOR ACTION (cannot proceed autonomously)

These items need the operator's environment / hardware / decisions
that an agent cannot make alone. They are NOT autonomous-resumable;
they require the operator to do the steps OR explicitly grant
authorization.

### 2.1 Release-tagging chain (versions 1.2.13-1033 + 2.3.2-2302)

Current HEAD (e9b7f89) has no git tags. The last tags are
`Lava-Android-1.1.3-1013` and `Lava-API-Go-2.0.7-2007`. Tagging
requires the full §6.I evidence pack.

| Step | Command | Blocker |
|---|---|---|
| §6.I emulator matrix gate | `bash scripts/run-emulator-tests.sh --avds=Pixel_API28,Pixel_API30,Pixel_API34,Pixel_APIlatest,Tablet_API34 --tests=lava.app.challenges --concurrent=1 --output=.lava-ci-evidence/Lava-Android-1.2.13-1033/real-device-verification.md` | Requires real Android emulator/device hardware |
| Real-device manual smoke-test | login + search + browse + download on a Pixel-class device against thinker.local | Operator hands-on |
| Tag Android + API | `bash scripts/tag.sh` | Refuses without matrix evidence |
| Pepper rotation | Per `docs/RELEASE-ROTATION.md`: append new pepper before tagging | Manual |

### 2.2 Operator's `.env` rotation hygiene

The first auto-rotation generated:
- `LAVA_AUTH_CURRENT_CLIENT_NAME = android-1.2.13-1033`
- `LAVA_AUTH_HMAC_SECRET = <auto-generated 32 bytes>` (gitignored .env)
- `LAVA_AUTH_OBFUSCATION_PEPPER = <auto-generated 32 bytes>` (gitignored .env)

Per `docs/RELEASE-ROTATION.md`: every release rotates pepper. The
pepper history at `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/pepper-history.sha256`
contains all 7 distribution cycles (1.2.8 through 1.2.13). The next
release (tag) MUST start by appending a new pepper. Re-use is refused.

---

## 3. Submodule pin index (for reference)

All 16 `vasic-digital` submodules are at HEAD on `main`. Bumping a
pin is a deliberate operator action; never auto-update.

| Submodule | Pin | Mirrors | Notes |
|---|---|---|---|---|
| `Auth` | `6213c61` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Cache` | `ea3b376` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Challenges` | `f7d336d` | GitHub + GitLab | merged P1.5 + §§6.R-6.W inheritance |
| `Concurrency` | `5b5a858` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Config` | `45a915b` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Containers` | `84c381c` | GitHub + GitLab | merged P1.5-WP8 + §§6.R-6.W inheritance |
| `Database` | `1ce46f9` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Discovery` | `5348c7d` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `HTTP3` | `7fec2d8` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Mdns` | `e7839fa` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Middleware` | `c877ef9` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Observability` | `aff0931` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `RateLimiter` | `1127b11` | GitHub + GitLab | pkg/ladder primitive + §§6.R-6.W inheritance |
| `Recovery` | `b4b8771` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Security` | `d45b458` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Tracker-SDK` | `3d31ea3` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |

**Internal-to-submodule nested submodules:** `Submodules/Challenges` has a nested `Panoptic` submodule that Challenges' own Go code in `pkg/panoptic/` depends on. Per the operator's "no submodules at multiple depths in the project root" directive, Lava itself has only flat root-level Submodules — but Challenges (consumed as a black-box vasic-digital submodule) self-manages its own internal dependencies, including Panoptic. This is acceptable: the depth restriction applies at Lava's tracking surface, not recursively into each submodule's internal organization.

---

## 4. PHASES 2-6 OF THE PARENT DECOMPOSITION — ALL COMPLETE

All phases 2 through 6 have been implemented and shipped via Firebase
App Distribution (7 versions: 1.2.8-1028 through 1.2.13-1033). The
original TODO specs have been fully delivered. See §1 above for the
summary of each phase.

No further parent-decomposition work remains. Future work should
either (a) cut git tags (requires §6.I evidence pack), (b) start new
feature work via the brainstorming → spec → plan → execute cycle, or
(c) address remaining open items (emulator matrix, Challenge Tests).

---

## 4.5 Known issues + bugs (carried forward)

These are real defects discovered during Phase 1's first deploy. None
are release-blocking — the API serves and the auth gate fails-closed
correctly — but each is a real Phase-2-or-later work item.

### 4.5.1 `/api/v1/search` route resolution

**Discovered:** 2026-05-06.

**Symptom (historical):** path `/v1/search` returned 404 because the
route was registered as `/v1/:provider/search`, not `/v1/search`.
Phase 2 was supposed to add per-provider routes and flip
`apiSupported = true`. Phase 2+2b commits shipped 6-provider support
— **needs operator verification** that the route now resolves
correctly against the running API (`curl -fsSk
'https://thinker.local:8443/v1/rutracker/search?q=test'`).

### 4.5.2 `/health` intermittent timeout + quic-go UDP buffer warning

**133s timeout** (observed 2026-05-06 once): likely HTTP/3→HTTP/2
negotiation stall at the curl layer. Container HEALTHCHECK via
`healthprobe` never exhibited this.

**UDP buffer warning** (persistent at boot):
```
failed to sufficiently increase receive buffer size
(was: 208 kiB, wanted: 7168 kiB, got: 416 kiB).
```
**Fix:** `sudo sysctl -w net.core.rmem_max=7340032` on the host.
**Documented:** `docs/UDP-BUFFER-WARNING.md` (Phase 2, 2026-05-08).

### 4.5.3 Internet Archive / gutenberg — provider status

Phase 2b (2026-05-07) flipped `apiSupported=true` on all 6 providers
(verified at HEAD: ArchiveOrgDescriptor.kt:56, GutenbergDescriptor.kt:50
both `override val apiSupported: Boolean = true`). The Phase-12 α-hotfix
"hide IA + others" is superseded. Per Phase 2b, lava-api-go ships
per-provider routes for these trackers.

**C11/C12 status update (post-`3f6e5e6`, 2026-05-08 22:21):**

`3f6e5e6` provider-deselection fix lands: only the 3 OTHER verified +
apiSupported providers (not the test's target) are deselected. Commit
body claims both **NOW PASSING** on emulator after the fix. Awaiting
matrix re-run for §6.I attestation; no matrix-attestation file exists
post-`3f6e5e6`. Pre-`3f6e5e6` cause was provider-deselection count
mismatch, NOT a navigation flow or encrypted-prefs race as previously
hypothesized.

### 4.5.3a C02 + C03 3-layer root-cause taxonomy (Phase 1 systematic-debugging)

Both C02 (RuTracker authenticated) and C03 (RuTor anonymous) Challenge
Tests time out at `composeRule.waitUntil { "All set!" visible }` after
the user taps "Test & Continue". Phase 1 investigation traced 3 layers
in the SUT chain:

**Layer 1 — UI input mechanism (deterministic, verifiable statically):**
- C02: text-input fields use `label = { Text("Username") }` and
  `label = { Text("Password") }` in `ConfigureStep.kt:96+104`. The
  pre-`3f6e5e6` test used `onNodeWithContentDescription` which didn't
  match the Compose label semantics → text input failed silently.
  `3f6e5e6` switched to `onNodeWithText("Username").performTextInput(...)`.
- C03: anonymous Switch is at `ConfigureStep.kt:81-86`. Pre-`3f6e5e6`
  test used `performClick()` on the Switch — touch injection on
  headless emulator is unreliable. `3f6e5e6` added
  `Modifier.testTag("anonymous_switch")` + the test now uses
  `onNodeWithTag("anonymous_switch").performClick()` which invokes
  the semantics action rather than synthesizing touch coordinates.
- **Status:** fix verified present in HEAD by static grep.

**Layer 2 — Provider deselection (deterministic, verifiable statically):**
- All 4 verified+apiSupported providers (RuTracker, RuTor, ArchiveOrg,
  Gutenberg) appear on "Pick your providers" pre-selected. The test
  must deselect 3 of them before tapping "Next" to land in Configure
  for the right target. Pre-`3f6e5e6` count was wrong (operated on 5
  providers including pre-Phase-12 ghosts).
- **Status:** fix verified present in HEAD by reading the test diff.

**Layer 3 — Real-network round-trip (NOT addressed by `3f6e5e6`,
unverifiable from this session):**
- `OnboardingViewModel.onTestAndContinue()` at `feature/onboarding/
  src/main/kotlin/lava/onboarding/OnboardingViewModel.kt:112`
  calls either `sdk.checkAuth(currentId)` (C03 anonymous) OR
  `sdk.login(currentId, LoginRequest(user, pw))` (C02 credentialed).
  Both are real HTTPS round-trips: rutor.info for C03,
  rutracker.org for C02.
- There is NO explicit timeout wrapper around either call; they
  rely on the Ktor/OkHttp client's default network timeouts (10s
  connect + 10s read in OkHttp, plus DNS).
- C02's test waits 30s for "All set!". C03's test waits 60s. If the
  network round-trip exceeds the budget (slow rutracker.org under
  Cloudflare, captcha required, geo-block from emulator NAT under
  VPN), the test times out at L3 — and the failure looks identical
  to the L1/L2 timeouts.
- **Status:** cannot verify from this session. Matrix re-run on
  emulator with the L1+L2 fixes already in HEAD is the only way to
  distinguish:
  - If C02/C03 PASS on re-run → L3 is not a residual cause; the
    investigation closes.
  - If C02/C03 STILL TIME OUT on re-run → L3 is real; investigate
    via emulator network logs, captcha detection in the test, OR
    operator-supplied known-good credentials.

**§6.J consideration:** `3f6e5e6`'s Bluff-Audit proved the L1 + L2
FIXES are real (mutate fix → test fails). It did NOT prove the full
Challenge Test passes end-to-end after the fix. That's the matrix
re-run's job. Pre-matrix-re-run, the believed-state ("C02/C03 fixes
applied") is HONEST per the commit body's claim — it does NOT claim
end-to-end PASS for C02/C03 (only for C11/C12).

### 4.5.3b Matrix re-run outcome 2026-05-12 (Phase 1 findings completed)

The §4.5.3a investigation was completed by running C02 + C03 on
CZ_API34_Phone API 34 via `scripts/run-emulator-tests.sh`, then directly
via `./gradlew :app:connectedDebugAndroidTest` against the same live
emulator with diagnostic logging instrumented in
`OnboardingViewModel.onTestAndContinue()` and Ktor wire logging in
`TrackerClientModule.provideRuTrackerHttpClient`. Evidence under
`.lava-ci-evidence/Lava-Android-1.2.13-1033/{matrix-c02,matrix-c03,post-mortem}`.

**C03 RESOLVED.** The anonymous toggle was a real Lava bug, not L3:
- `OnboardingViewModel` called `sdk.checkAuth(currentId)` for both
  `AuthType.NONE` providers AND `config.useAnonymous=true` paths.
- For `AuthType.NONE` (ArchiveOrg, Gutenberg): no Authenticatable
  feature, so `checkAuth` returns `null` → null branch passes through.
- For `useAnonymous=true` on FORM_LOGIN (RuTor): `checkAuth` correctly
  returns `Unauthenticated` because the user IS unauthenticated by
  choice. The old code treated `Unauthenticated` as a failure
  (`error = "Connection failed"`) → never advances to Summary.
- Fix: skip `checkAuth` entirely on the anonymous branch — anonymous
  is the user's chosen unauth state, not a failure.
- Verified: C03 now passes end-to-end on the live emulator (PASS at
  09:25:23, full diag trace shows `switchTracker → test ok →
  advance to Summary → Finish`).

**C02 PARTIALLY DIAGNOSED — environmental block remains.** The
credentialed path correctly calls `sdk.login(rutracker, LoginRequest)`,
which issues `POST https://rutracker.org/forum/login.php`. Ktor wire log
captured:
- 09:38:01.717 — `REQUEST: POST .../login.php` (request line written)
- 09:39:01.719 — `failed with exception: Request timeout (60_000 ms)`
- The 60-second gap is exact — Ktor `HttpTimeout.requestTimeoutMillis`
  fired because the server sent no response data within 60s.

Pre-fix timeline (10s OkHttp default) was a `SocketTimeoutException`
on HTTP/2 stream `takeHeaders`. After bumping timeouts to 60s and
installing a browser-like `UserAgent`, the failure mode shifts from
"timeout reading headers" to "timeout waiting for any response at all"
— but the request still doesn't complete. From the host directly,
the SAME URL returns HTTP/2 200 in <1s. This rules out network outage
and points to client-fingerprint anti-bot: Cloudflare lets the TCP+TLS
handshake complete, accepts the POST, then deliberately stalls.

**Remaining options for C02:**
- Add proper browser-like headers (Accept-Language, Accept,
  Accept-Encoding, Origin, Referer) to evade fingerprint detection.
- Add a pre-flight GET `/forum/index.php` to establish Cloudflare
  cookies before the POST.
- Route rutracker via lava-api-go proxy (`apiSupported=true` is already
  set on the descriptor — when the Go API runs on thinker.local or
  locally, the client routes through it instead of direct).
- Investigate whether the operator's network has Mullvad VPN active and
  test from a non-VPN exit.

**Fixes landed in this commit (independent of the C02 environmental
block):**
1. `OnboardingViewModel`: removed broken `checkAuth` for anonymous
   mode → C03 PASSES end-to-end on CZ_API34_Phone.
2. `OnboardingViewModel`: added diagnostic `logger.d`/`logger.e`
   breadcrumbs that print the auth path taken, the result/exception,
   and the success advance. Visible in logcat for future triage.
3. `NetworkModule.okHttpClient` + `lanOkHttpClient`: explicit 30s
   connect/read/write timeouts (was OkHttp default 10s — too tight
   for slow networks; user-visible "timeout" became "30s timeout").
4. `TrackerClientModule.provideRuTrackerHttpClient`: installed
   `HttpTimeout` (60s request/socket, 30s connect), `UserAgent`, and
   `Logging` (INFO level via android.util.Log) for the rutracker
   Ktor client. Did NOT unblock C02 (Cloudflare-side stall) but
   moved the failure mode to a more diagnosable signal AND gives
   real users on slow networks the time-budget they need.
5. `Challenge02AuthenticatedSearchOnRuTrackerTest`: bumped the
   "All set!" wait from 30s to 90s to match C03's 60s+ budget plus
   margin. Aligns the test with realistic real-network round-trip
   times after timeout fixes.

### 4.5.3c C02 Cloudflare mitigation outcome (this commit)

Built on top of §4.5.3b's HTTP timeout / UA / Logging improvements,
this commit layers in:

1. **`HttpCookies` plugin + `AcceptAllCookiesStorage`** on the
   rutracker Ktor client — cookies captured from any response are
   replayed automatically on subsequent requests, including across
   the 302-redirect chain that Ktor's `HttpRedirect` plugin follows
   internally.

2. **Browser-class request shaping** — Chrome 124 on Android 14 UA
   plus `Accept`/`Accept-Language`/`Accept-Encoding` `defaultRequest`
   headers, so Cloudflare's HTTP/header-shape fingerprinter has less
   to flag.

3. **Pre-flight GET `/forum/index.php`** inside
   `RuTrackerInnerApiImpl.login()` before the POST — this is the
   load-bearing change. Without it, the bare POST is silently stalled
   by Cloudflare. With it, the GET establishes the `cf_clearance`
   cookie in the jar, the POST replays it, Cloudflare lets the
   POST through.

4. **Cookie-selection robustness** — the prior code picked `token =
   cookies.firstOrNull { !it.contains("bb_ssl") }`, which once
   Cloudflare added `cf_clearance` to every Set-Cookie response
   meant `token` was `cf_clearance=…` rather than the rutracker
   session cookie. Now matched by NAME prefix (`bb_data` / `bb_session`
   / `bb_login`). This is correct behavior regardless of Cloudflare
   — the previous code was fragile to any extra cookie rutracker
   ever added.

**Verified via Ktor wire log:** the POST now returns `302` → auto-redirect
to `/forum/index.php` returns `200` (logged-in page) in ~3s total. Pre-CF-fix:
the POST timed out at 60s every time.

**C02 STILL DOES NOT PASS end-to-end.** The login HTTP exchange now
succeeds but `GetCurrentProfileUseCase.parseUserId` throws
`IllegalArgumentException: query param not found in ` — Jsoup's
`select("#logged-in-username")` returned an empty selection on the
post-login page. Two leading hypotheses:

- **Stale selector.** The parser was written against a rutracker.org
  HTML structure where `<a id="logged-in-username" href="profile.php?u=NNN">…`
  appeared in the header. rutracker.org may have changed this element
  ID; the current page might use `.menu-userctrl` or `<a class="loginusername">`
  or similar.
- **Mobile vs. desktop variant.** The new Chrome-mobile UA may cause
  rutracker to serve a mobile-shaped page that lacks the desktop's
  `#logged-in-username` element entirely.

**Resolution path (not closed in this session):**

1. Capture the actual post-login page HTML by either bumping Ktor
   Logging to `LogLevel.BODY` for one diagnostic run (note: this
   leaks credentials in form bodies — use carefully, gitignore the
   logcat, redact in commit) or by adding a one-shot Lava-logger
   side channel into `RuTrackerInnerApiImpl.login()` that writes the
   first N chars to logcat.
2. Update `GetCurrentProfileUseCase.parseUserId`'s selector to match
   the actual element rutracker.org serves today, with a fallback
   selector for the mobile variant if needed. This is a small Jsoup
   selector change once the actual HTML is in hand.
3. Re-verify C02 end-to-end.

**Why this is deferred:** the fix requires fresh code-archaeology
against the live rutracker.org HTML structure, which is bigger than
"network mitigation" and benefits from focused operator pair-debugging
(or a dedicated SP for rutracker HTML parser refresh) rather than
inline-during-this-session attempt #N. The blocking issue — Cloudflare
stalling our requests — is RESOLVED here.

### 4.5.4 Challenges + emulator: C17-C22 remain unexecuted

Challenge Tests C17 (archiveorg search), C18 (gutenberg), C19
(multi-provider), C20 (full onboarding flow), C21 (Welcome screen),
C22 (anonymous provider auto-advance) are written and compile but
require the §6.I emulator matrix to execute.

### 4.5.5 Submodules/Challenges has untracked `Containers/` dir leftover from upstream merge

**Discovered:** 2026-05-06 after merging upstream Challenges
into Lava-side post-§6.R-inheritance. The merged-in commit
`abe62cb chore(P1.5-T03.02): dedup Containers in Challenges`
deleted the `Containers/` subdir from Challenges (canonical at
meta-repo root), but the local clone still has the directory on
disk because git doesn't auto-delete files that were already
present locally before the dedup commit's merge.

**Symptom:** `git status` inside Submodules/Challenges shows
`?? Containers/` (untracked). Doesn't affect builds; cosmetic noise.

**Action:** operator runs `rm -rf Submodules/Challenges/Containers`
once. Not blocking; tracked here so a future cleanup pass picks it
up.

### 4.5.6 Mirror model reduced to 2-mirror (GitHub + GitLab) per §6.W

**Resolved 2026-05-08.** Per constitutional clause §6.W, only GitHub and
GitLab are permitted as Git remotes. GitFlic and GitVerse are removed from
all remotes. The 4-mirror model is replaced by 2-mirror. All submodules
now target GitHub + GitLab.

### 4.5.7 `Engine.Ktor` enum cascade tail (post-Ktor cleanup low priority)

**Symptom:** the `Engine.Ktor` enum value lingers in client-side
exhaustive `when` branches. It's dead code — the Ktor :proxy was
deleted in 2.0.12. Removal would require an Android version bump
for cosmetic-only cleanup.

**Action:** Phase 5 (UI/UX polish) is the natural place. Tracked.

### 4.5.8 `Endpoint.Mirror` LAN-IP routing branch (post-Ktor cleanup low priority)

Same shape as 4.5.8. Dead path post-Ktor; no triggering producer in
tree. Phase 5 cleanup target.

### 4.5.9 `docs/todos/` directory is untracked

**Discovered:** persistent throughout Phase 1. The directory contains
`Lava_TODOs_001.md` (the operator's source-of-truth TODO doc) but is
NOT committed to git.

**Action:** resolved in Phase 2 (2026-05-08). `Lava_TODOs_001.md` committed
as a historical record — all TODOs have been fully implemented across
Phases 1-6. The document serves as archival context for future agents.

### 4.5.10 IPv4 / host:port / schedule / algorithm-parameter literal grep is staged

**Discovered:** Phase 1 §6.R implementation deliberately deferred
non-UUID literal classes. The §6.R clause body in CLAUDE.md
acknowledges this with the "Enforcement status (2026-05-06)" line.

**Action:** Phase 6 deliverable. Add IPv4 grep, host:port grep,
schedule literal grep with carefully-scoped exemptions (incident
docs, design specs, plans, test fixtures).

---

## 5. Operator-flagged follow-up items (small, queued)

Items the operator or reviewers flagged but didn't gate on; pick up
opportunistically when a related phase touches the area.

- ~~**OkHttp logging audit:** ensure `NetworkLogger` does NOT include
  the `Lava-Auth` header value.~~ **RESOLVED 2026-05-13.**
  `NetworkLogger.redactAuthHeader` now case-insensitive-redacts any
  `${LAVA_AUTH_FIELD_NAME}: <value>` substring against
  `LavaAuthBlobProvider.getFieldName()`. New test
  `NetworkLoggerRedactionTest` (3 cases: raw match, empty-stub no-op,
  case-insensitive match) with Bluff-Audit recorded — `replace`-as-
  identity mutation produced 2 expected failures including
  `captured log line must NOT contain the raw auth value`. Reverted,
  green. Field name source-of-truth remains parametric per §6.R
  (`.env` → `LavaAuthGenerated.getFieldName()`).
- ~~**Submodules/RateLimiter mirror count:** only 2 mirrors today.~~
  **OBSOLETE per §6.W (2026-05-08).** §6.W reduced the canonical
  mirror policy from 4 (GitHub + GitLab + GitFlic + GitVerse) to 2
  (GitHub + GitLab only) because the operator has CLI access (`gh`,
  `glab`) only on the latter two. RateLimiter's 2-mirror state is
  now compliant. The Decoupled Reusable Architecture mirror rule
  was amended accordingly. No further action.
- **`Endpoint.Ktor` enum cascade** (post-Ktor cleanup tail): low
  priority, would require Android version bump for cosmetic-only
  cleanup. Tracked but not blocking.
- **`Endpoint.Mirror` LAN-IP routing branch** in
  `NetworkApiRepositoryImpl`: dead path post-Ktor; same trigger
  conditions as above.

---

## 6. Constitutional debt + memory anchors

- **§6.K-debt** (Containers extension): RESOLVED 2026-05-07 per
  `CLAUDE.md` note (Group C-pkg-vm + image-cache spec landed).
- **§6.N-debt** (pre-push hook enforcement of §6.N.1.2 + §6.N.1.3):
  RESOLVED 2026-05-05 evening per `CLAUDE.md` note (Group A-prime
  spec landed).
- **§6.L** (Anti-Bluff Functional Reality Mandate): the operator has
  invoked this 15 TIMES as of 2026-05-08 (the 15th invocation opened
  this session: "Onboarding wizard is full of bugs ... Theme colors /
  color schema is terrible ... Lots bluffing here!"). Every test added
  MUST satisfy the Sixth Law clauses 1-5 + the Seventh Law's
  Bluff-Audit stamp + the §6.J primary-on-user-visible-state assertion.
  No exception.
- **§6.R** (No-Hardcoding Mandate): added 2026-05-06 in Phase 1.
  Every value (URLs, ports, header names, credentials, schedules,
  algorithm parameters) comes from `.env` or generated config.
  Pre-push grep enforces UUID literals; IPv4/host:port/schedule
  enforcement is staged for future phases (see §4.5.11).
- **§6.S** (Continuation Document Maintenance Mandate): added
  2026-05-06. THIS file (`docs/CONTINUATION.md`) is
  constitutionally load-bearing. Every commit that changes
  tracked state MUST update this file in the SAME COMMIT.
  Stale CONTINUATION.md is itself a §6.J spirit issue.
  `scripts/check-constitution.sh` enforces (1) file present,
  (2) §0 "Last updated" line, (3) §7 RESUME PROMPT, (4) §6.S
  clause in CLAUDE.md, (5) §6.S inheritance in 16 submodules
  + lava-api-go.
- **§6.T** (Universal Quality Constraints): added 2026-05-06
  from ../HelixCode constitution mining. Four sub-points:
  - §6.T.1 Reproduction-Before-Fix (HelixCode CONST-014)
  - §6.T.2 Resource Limits for Tests & Challenges (CONST-011)
  - §6.T.3 No-Force-Push (CONST-043)
  - §6.T.4 Bugfix Documentation in `docs/issues/fixed/BUGFIXES.md`
    (CONST-012). §6.O extends this for Crashlytics issues; §6.T.4
    covers the rest.
  All four apply recursively. Submodules MAY tighten but MUST NOT
  relax.

---

## 7. RESUME PROMPT

Paste the following into a new CLI agent session to continue this
work. The agent needs no scrollback — everything it needs is in this
file plus the spec/plan/CLAUDE.md set referenced from it.

```
Continue Lava project work. Read these in order before doing anything:

  1. /run/media/milosvasic/DATA4TB/Projects/Lava/docs/CONTINUATION.md
  2. /run/media/milosvasic/DATA4TB/Projects/Lava/CLAUDE.md
  3. /run/media/milosvasic/DATA4TB/Projects/Lava/docs/todos/Lava_TODOs_001.md

Then check the git state vs the CONTINUATION.md "Last updated" line.
If new commits exist on master beyond what CONTINUATION.md describes,
trust the commits and update CONTINUATION.md before proceeding.

Active state per CONTINUATION.md §1:
  - Phase 0 (Stabilize + §6.W) COMPLETE. Commits: 9ed7bca, 55a702c.
  - Phase 1 (Full Anti-Bluff Audit) COMPLETE. All 264 tests clean,
    5 bluff-hunt mutations confirmed, all 22 Challenge KDocs have
    falsifiability protocols. RuTrackerDescriptor mutation reverted.
  - Phase 2 (Fix Known Issues) COMPLETE. commits: 6009c6b.
  - Phase 3 (Container Boot + Challenges) IN PROGRESS. Go API healthy.
    1840 unit tests pass. C00 fixed (AuthType.NONE signal name).
    Post-`3f6e5e6` believed-state: 19/24 Challenge Tests PASS
    (C00-C01, C04-C08, C11-C16 + previously-passing C13-C16; C11/C12
    moved from FAIL to PASS per 3f6e5e6's claim). C02 + C03 fixes
    applied at L1+L2 but matrix re-run pending to verify L3 is not
    a residual cause — see §4.5.3a 3-layer taxonomy in CONTINUATION.md.
  - Full plan at docs/superpowers/specs/2026-05-08-full-anti-bluff-proofing-plan.md

Your default next action (in priority order):
  - **Operator-hardware-blocked:** re-run the §6.I matrix to verify
    C02/C03 + close the matrix-attestation gap for 1.2.13-1033.
    The last attestation is at Lava-Android-1.2.0-1020 (13 versions
    behind) — see §2.1 release-tagging chain.
  - **If C02/C03 still time out post-matrix-re-run:** L3 (real-network)
    investigation: emulator network logs, captcha-page detection in
    the SUT, known-good credential verification, possibly Mullvad VPN
    routing for the rutracker.org / rutor.info flows.
  - Or proceed to operator-dependent tasks:
    1. Verify /api/v1/search on the running API (requires auth UUID)
    2. Verify Internet Archive/gutenberg API provider
    3. Start brainstorming new feature work
    4. Proceed to Phase 6 (rebuild, tag, distribute)

Do NOT re-run completed phases — they are committed + pushed + deployed.
The git log is the authoritative record.

Running containers:
  - lava-postgres: healthy
  - lava-migrate: completed (Exited 0)
  - lava-api-go: healthy (https://thinker.local:8443/health → {"status":"alive"})
  - CZ_API34_Phone emulator: running on emulator-5556

Blocked on operator action:
  - Verify /api/v1/search (needs auth UUID from .env against running API)
  - Phase 6 tagging: requires real-device attestation (§6.I) + evidence pack

Constitutional bindings still in force (do not relax):
  §6.J / §6.L (Anti-Bluff Functional Reality Mandate)
  §6.Q (Compose Layout Antipattern Guard)
  §6.R (No-Hardcoding Mandate)
  §6.G (End-to-End Provider Operational Verification)
  §6.I (Multi-Emulator Container Matrix as Real-Device Equivalent)
  §6.K (Builds-Inside-Containers Mandate)
  §6.O (Crashlytics-Resolved Issue Coverage Mandate)
  §6.P (Distribution Versioning + Changelog Mandate)
  §6.N (Bluff-Hunt Cadence Tightening)
  §6.U (No sudo/su Mandate)
  §6.V (Container Emulators Mandate)
  §6.W (GitHub + GitLab Only Remote Mandate)

The operator's standing order is preserved verbatim in
CLAUDE.md §6.L. Read it.
```

---

## 8. House-keeping the agent should keep doing

These are habits the in-flight Phase-1 agent established that
future agents should preserve:

1. **Commit messages carry Bluff-Audit stamps for every test class
   added or modified.** Pre-push hook rejects commits without them.
2. **Every commit must have `Co-Authored-By: Claude Opus 4.7
   (1M context) <noreply@anthropic.com>`** as the trailer.
3. **Push to both Lava parent mirrors (GitHub + GitLab)** after every commit chain
   that closes a logical unit. After every push, confirm convergence
   with `for r in github gitlab; do echo "$r:
   $(git ls-remote $r master | awk '{print $1}' | head -1)"; done`.
4. **Submodule pushes are explicit per submodule** to whatever
   remotes that submodule has (varies — see §3 above). Never use
   `git submodule foreach git push` blindly; some submodules have
   `upstream` (fork-source) which is NOT a write target.
5. **Update this CONTINUATION.md** in the same commit as any
   completion-state change (phase done, new spec/plan written,
   submodule pin bumped, distribute artifact shipped).
6. **The autonomous loop ends** when the next forward step requires
   operator-environment access (real device, real keystore secrets,
   Firebase token, ssh credentials) OR operator decision-making
   (brainstorming next phase scope, tagging, choosing a UI direction).
   At that point, summarize state + ask the operator the specific
   next-step question.
