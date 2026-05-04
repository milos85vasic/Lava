# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **See also:**
> - `AGENTS.md` — longer companion guide (tech stack versions, deployment, security notes). Read this when CLAUDE.md is too brief on a given subject.
> - `core/CLAUDE.md` and `feature/CLAUDE.md` — scoped Anti-Bluff rules that apply only inside those trees.
> - `lava-api-go/CLAUDE.md` and `lava-api-go/CONSTITUTION.md` — scoped instructions and constitutional addenda for the Go API service. **Read both before touching `lava-api-go/`.**
> - `Submodules/<Name>/CLAUDE.md` — each of the 16 `vasic-digital/*` submodules ships its own scoped rules, inherited per 6.F. Honour them before editing any code under `Submodules/`.
> - `docs/ARCHITECTURE.md`, `docs/LOCAL_NETWORK_DISCOVERY.md` — architecture diagrams and the mDNS discovery flow.
> - `docs/superpowers/specs/2026-04-28-sp2-go-api-migration-design.md` — full SP-2 design doc (Go API service migration).
> - `docs/superpowers/plans/2026-04-28-sp2-go-api-migration.md` — SP-2 implementation plan (14 phases, 39 tasks).

## Project

Lava is an unofficial Android client for **rutracker.org**, plus a companion **Ktor proxy server** that scrapes the site and exposes a JSON API to the app. Two artifacts share one Gradle build:

- `:app` — Android app, Kotlin + Jetpack Compose, App ID `digital.vasic.lava.client`.
- `:proxy` — Ktor/Netty headless server, packaged as a fat JAR + Docker image.

The repo is a fork of `andrikeev/Flow`, rebranded to Lava. All code/comments/docs are English.

## Commands

```bash
# Android app
./gradlew :app:assembleDebug          # debug APK (applicationIdSuffix .dev)
./gradlew :app:assembleRelease        # release APK (signed via keystores/)

# Proxy server
./gradlew :proxy:buildFatJar          # → proxy/build/libs/app.jar
./build_and_push_docker_image.sh      # builds + pushes to DigitalOcean registry

# Build all artifacts and copy to releases/
./build_and_release.sh                # → releases/{version}/android-{debug|release}/, releases/{version}/proxy/

# Run the proxy locally (with LAN mDNS discovery)
./start.sh                            # builds JAR + image, starts container, advertises _lava._tcp
./stop.sh                             # stops + removes the container
./tools/lava-containers/bin/lava-containers -cmd=status   # runtime, health, advertised IPs

# Code style (Spotless + ktlint — the only enforced checker)
./gradlew spotlessApply               # run before committing
./gradlew spotlessCheck

# Tests (coverage is minimal; one unit test exists today)
./gradlew test
./gradlew :core:preferences:test --tests "lava.securestorage.EndpointConverterTest"

# Local CI gate — IS the project's CI/CD apparatus (Local-Only CI/CD rule)
./scripts/ci.sh --changed-only        # pre-push subset: Spotless, changed-module unit tests,
                                      #   constitution parser, forbidden-files check
./scripts/ci.sh --full                # all gates: every module, parity, mutation tests,
                                      #   fixture freshness, real-device Challenge Tests
                                      #   (used by scripts/tag.sh at tag time)

# Constitution + bluff enforcement
./scripts/check-constitution.sh       # parser/validator; greps tracked files for forbidden
                                      #   commands and credential patterns; run by pre-push
./scripts/bluff-hunt.sh               # phase-gate bluff hunt (Seventh Law clause 5)
./scripts/check-fixture-freshness.sh  # detects stale network fixtures

# Real-device Challenge Tests (Sixth Law clause 4 acceptance gate)
./scripts/run-emulator-tests.sh       # Android emulator container + connectedAndroidTest

# Release tagging — refuses without local CI evidence + real-device attestation
./scripts/tag.sh <tag>

# Mirror sync (4 upstreams: GitHub, GitLab, GitFlic, GitVerse)
./Upstreams/GitHub.sh
./Upstreams/GitLab.sh
./Upstreams/GitFlic.sh
./Upstreams/GitVerse.sh
./scripts/sync-tracker-sdk-mirrors.sh
```

`./start.sh` delegates to the Lava-domain CLI at `tools/lava-containers/`, which auto-detects Podman or Docker, builds the proxy fat JAR + image, and runs it via `docker-compose.yml`. Generic container-runtime concerns are owned by the upstream `vasic-digital/Containers` submodule mounted at `Submodules/Containers/` (pinned hash); the local CLI is thin glue per the Decoupled Reusable Architecture constitutional rule. The compose file uses **`network_mode: host`** so JmDNS broadcasts reach the LAN — the Android debug build then auto-discovers the proxy via `NsdManager` (`_lava._tcp.local.`).

Signing requires a `.env` at the repo root with `KEYSTORE_PASSWORD` and `KEYSTORE_ROOT_DIR` (see `.env.example`); keystores live in `keystores/` (gitignored). The app build also expects `app/google-services.json` (gitignored) for Firebase.

## Architecture

### Module layout

There is **no root `build.gradle.kts`** — all build logic lives in `buildSrc/` as convention plugins (`lava.android.application`, `lava.android.library`, `lava.android.library.compose`, `lava.android.feature`, `lava.android.hilt`, `lava.kotlin.library`, `lava.kotlin.serialization`, `lava.kotlin.ksp`, `lava.ktor.application`). Module `build.gradle.kts` files apply these by ID — **do not duplicate config in module scripts; extend the convention plugin instead.**

Three top-level Gradle groupings (all listed in `settings.gradle.kts`):

- `core/*` — 17 modules. Several use the **api/impl split** (`auth`, `network`, `work`) so consumers depend on `:api` only. Pure-Kotlin modules with no Android dep: `core:models`, `core:common`, `core:auth:api`, `core:network:api`, `core:network:rutracker`, `core:work:api`.
- `feature/*` — 15 screen-level modules; each applies `lava.android.feature` (which auto-wires Hilt, Orbit, and the standard core deps).
- `:app` — Compose entry point, depends on every core + feature module. Holds `MainActivity`, `TvActivity`, top-level navigation graph, and rutracker.org deep-link handling.

Dependency direction: `app → feature:* → core:domain → core:data → core:network:impl + core:database`, plus `core:auth:impl`, `core:work:impl`, `core:navigation`, `core:ui`, `core:designsystem`.

Two non-Gradle artifacts also live at the repo root and are NOT built by `./gradlew`:

- `lava-api-go/` — Go service replacing the Ktor `:proxy` in SP-2. Independent build (`Makefile`, `go.mod`), independent constitution (`lava-api-go/CONSTITUTION.md`), real-Postgres integration tests gated by `-Pintegration=true` running under podman/docker. Inherits 6.D and 6.E for its rutracker bridge work.
- `Submodules/` — 16 pinned `vasic-digital/*` Git submodules (`Auth`, `Cache`, `Challenges`, `Concurrency`, `Config`, `Containers`, `Database`, `Discovery`, `HTTP3`, `Mdns`, `Middleware`, `Observability`, `RateLimiter`, `Recovery`, `Security`, `Tracker-SDK`). Every component with a non-Lava-specific use case lives here per the Decoupled Reusable Architecture rule. Pins are frozen by default — never auto-update.

### Patterns to follow

- **MVI via Orbit** in every feature: `XxxViewModel` (`@HiltViewModel`, `ContainerHost<State, SideEffect>`) + sealed `XxxState` / `XxxAction` / `XxxSideEffect`. Mirror the shape of neighboring features.
- **100% Jetpack Compose** — no XML layouts, no Fragments. Theme + components live in `core:designsystem` (`LavaTheme`).
- **Custom navigation DSL** in `core:navigation`, built on Navigation-Compose. Several modules enable `-Xcontext-receivers`; the `context(NavigationGraphBuilder)` calls rely on it. Each feature exposes `addXxx()` / `openXxx()` extensions.
- **DI:** Dagger Hilt on Android, **Koin** on the proxy server.
- **Database:** Room with KSP. Schemas are checked into `core/database/schemas/`.
- **Serialization:** `kotlinx-serialization-json` — apply `lava.kotlin.serialization` rather than configuring it ad hoc.
- **Versions / deps:** add to `gradle/libs.versions.toml` and reference via the version catalog (`libs.findLibrary(...)` in convention plugins, `libs.xxx` in module scripts). Don't hard-code versions.
- **TV support:** `TvActivity` extends `MainActivity` and flips `PlatformType` to `TV`; manifest declares `android.software.leanback` and `android.hardware.touchscreen` as not required.
- **Local network discovery:** `core:data` exposes `LocalNetworkDiscoveryService` (Android `NsdManager`, service type `_lava._tcp.local.`) consumed by `DiscoverLocalEndpointsUseCase` in `core:domain` and triggered from `feature:menu`. See `docs/LOCAL_NETWORK_DISCOVERY.md` for the full flow.

### Release build quirks

`isMinifyEnabled = true` but `isObfuscate = false`. ProGuard rules in `app/proguard-rules.pro` keep `lava.network.dto.**` and Tink classes. `android:usesCleartextTraffic="true"` is set in the manifest — be deliberate if changing it.

## Anti-Bluff Testing Pact (Constitutional Law)

This project adheres to **Anti-Bluff Testing**. A "bluff test" is one that passes while the corresponding real feature is broken for end users. Bluff tests are worse than no tests because they create a false sense of security. They are forbidden.

### First Law — Tests Must Guarantee Real User-Visible Behavior
- Every test MUST verify an outcome that matters to end users (data correctness, UI state, persistence, network behavior).
- A test that only asserts "function did not crash" or "mock was called" is a bluff test.
- If a bug ships to production, there MUST be a retroactive test that would have caught it before the fix is merged.

### Second Law — No Mocking of Internal Business Logic
- **ViewModel tests MUST use real UseCase implementations**, not stubbed or mocked use cases.
- **UseCase tests MUST use real Repository implementations** (or fakes that enforce identical constraints to the real database / network layer).
- Mocking is permitted ONLY at the outermost boundaries (Android system services, actual HTTP sockets, hardware sensors).
- If a bug exists in a UseCase, a ViewModel test wired to the real UseCase MUST fail.

### Third Law — Fakes Must Be Behaviorally Equivalent
- A test fake that is "simpler" than reality in a way that could hide a bug is a bluff fake.
- `TestEndpointsRepository` MUST enforce duplicate rejection (Room primary-key conflict) and default-endpoint seeding just like `EndpointsRepositoryImpl`.
- `TestLocalNetworkDiscoveryService` MUST simulate real NsdManager behaviors (e.g., `_lava._tcp.local.` service-type suffix) that affect production parsing logic.
- If the real implementation has a branch, the fake MUST have a matching branch or a documented limitation.

### Fourth Law — Integration Challenge Tests
- Every feature MUST include at least one **Integration Challenge Test** that exercises the real implementation stack end-to-end: ViewModel → UseCase → Repository → (Fake) Service.
- Challenge Tests must use the **actual production classes** at every layer; only external boundaries may be faked.
- A Challenge Test that passes MUST guarantee the feature works for a real user under the tested conditions.

### Fifth Law — Regression Immunity
- When a production bug is discovered, the fix commit MUST include a test that would have failed before the fix.
- If such a test cannot be written, the code is untestable and must be refactored for testability before the fix is accepted.
- Code coverage metrics are meaningless if the tests are bluffs; behavioral guarantees are the only metric that matters.

### Sixth Law — Real User Verification (Anti-Pseudo-Test Rule)

A test that passes while the feature it covers is broken for end users is the most expensive kind of test in this codebase — it converts unknown breakage into believed safety. This has happened in this project before: tests and Integration Challenge Tests executed green while large parts of the product were unusable on a real device. That outcome is a constitutional failure, not a coverage failure, and it MUST NOT recur.

Every test added to this codebase from this point on MUST satisfy ALL of the following. Violation of any of them is a release blocker, irrespective of coverage metrics, CI status, reviewer sign-off, or schedule pressure.

1. **Same surfaces the user touches.** The test must traverse the production code path the user's action triggers, end to end, with no shortcut that bypasses real wiring. If the user's action is "open screen → tap button → see result", the test exercises the same screen, the same button handler, and the same result-rendering code — not a synthetic call into the ViewModel that skips the screen, and not a hand-rolled flow that skips the network/database boundary the real action crosses.

2. **Provably falsifiable on real defects.** Before merging, the author MUST run the test once with the underlying feature deliberately broken (throw inside the use case, return the wrong row from the repository, return the wrong status from the API) and confirm the test fails with a clear assertion message. The PR description MUST state which deliberate break was used and what failure the test produced. A test that cannot be made to fail by breaking the thing it claims to verify is a bluff test by definition.

3. **Primary assertion on user-visible state.** The chief failure signal of the test MUST be on something a real user could see or measure: rendered UI text, persisted database row, HTTP response body / status / header, file written to disk, packet on the wire. "Mock was invoked N times" is a permitted secondary assertion, never the primary one.

4. **Integration Challenge Tests are the load-bearing acceptance gate.** A green Challenge Test means a real user can complete the flow on a real device against real services — not "the wiring compiles". A feature for which a Challenge Test cannot be written is, by definition, not shippable. Refactor for testability or remove the feature.

5. **CI green is necessary, not sufficient.** "All tests pass" is not a release signal. Before any release tag is cut, a human (or a scripted black-box runner that drives the real UI / real HTTP API) MUST have used the feature on a real device or environment and observed the user-visible outcome described in the feature spec. Tag scripts (e.g. `scripts/tag.sh`) MUST NOT be run until that verification is documented.

6. **Inheritance.** This Sixth Law applies recursively to every submodule, every feature, and every new artifact added to the project (including the Go API service). Submodule constitutions MAY add stricter rules but MUST NOT relax this one.

### Seventh Law — Tests MUST Confirm User-Reachable Functionality (Anti-Bluff Enforcement)

The Sixth Law states what tests must satisfy. The Seventh Law states how we enforce it mechanically — because the project has shipped passing-tests with broken-features at least once, and the operator's standing mandate (2026-04-30) is that this MUST NOT recur. The Seventh Law is the teeth.

Every commit that adds, modifies, or removes a test in this codebase, AND every commit that adds or modifies a user-facing feature, is bound by the following clauses. Violation is a release blocker — pre-push hooks reject the push, tag scripts refuse to operate, and reviewers MUST raise the violation as a blocking review comment.

1. **Bluff Audit Stamp on every test commit.** When a commit adds or modifies a `*Test.kt` / `*_test.go` / equivalent file, the commit message body MUST contain a "Bluff-Audit:" block in this exact format:

   ```
   Bluff-Audit: <test-name-or-file>
     Mutation: <what was deliberately broken in the production code>
     Observed-Failure: <copy-paste of the test failure message after mutation>
     Reverted: yes
   ```

   At least one mutation per test class added/modified. Pre-push hook rejects test commits without this stamp. The mutation MUST target the production code path the test claims to cover — mutating an unrelated branch is itself a bluff.

2. **Real-Stack Verification Gate per feature.** For every feature whose acceptance criterion mentions user-visible behavior (a screen, an HTTP response, a downloaded file, a persisted row), the feature is not "complete" until a real-stack test exists that:
   - Hits the real third-party service over the network where the service is third-party (rutracker.org, rutor.info, etc.) — gated by `-PrealTrackers=true` so default test runs don't make outbound calls
   - Runs against a real Postgres instance where the service is our own (lava-api-go) — gated by `-Pintegration=true` and using podman/docker for the database
   - Drives the real Compose UI on a real Android emulator/device where the feature is UI — gated by `-PdeviceTests=true` and using `adb shell instrument` against `connectedAndroidTest`

   A feature lacking such a real-stack test is documented as **not user-reachable** until the gap is closed. The matching coverage exemption ledger entry is mandatory.

3. **Pre-Tag Real-Device Attestation.** Before any release tag is cut, the operator MUST execute the user-visible flows (login, search, browse, view topic, download `.torrent`) on a real Android device against the real backend services and record a JSON attestation file at `.lava-ci-evidence/<tag-name>/real-device-attestation.json`. The attestation MUST include: device model, Android version, app version, timestamp, command-by-command checklist of executed user actions, and at least 3 screenshots OR a video recording referenced by hash. `scripts/tag.sh` MUST refuse to operate on a commit lacking the matching attestation. There is no exception. "Operator was busy" is not an exception. "Test environment was unstable" is not an exception. Untested-on-device commits do not get tags.

4. **Forbidden Test Patterns (per-language).** The following patterns are bluffs by construction. Pre-push hooks reject diffs that introduce them:

   - **Mocking the System Under Test.** A `@MockK` / `@Mock` on the class whose name appears in the test class name (e.g. `mockk<RuTrackerSearch>` in `RuTrackerSearchTest`) is the canonical bluff. The SUT must be a real instance. Mocks are permitted ONLY at the boundaries below the SUT (Android system services, real HTTP sockets when not using MockWebServer, real database drivers when not using in-memory variants).
   - **Verification-only assertions.** A test whose ONLY assertion is `verify { mock.foo() }` or `coVerify { ... }` is a call-counting test, not a behavior test. The Sixth Law clause 3 requires the primary assertion to be on user-visible state.
   - **`@Ignore`'d tests with no follow-up issue.** An ignored test asserts nothing and adds noise. If a test must be temporarily disabled, the `@Ignore("reason")` argument MUST cite an issue number tracking re-enablement; the issue MUST be triaged in the next phase.
   - **Tests that build the SUT but never invoke its methods.** A test that constructs a ViewModel, wires its dependencies, and asserts only `assertNotNull(viewModel)` is a compilation test, not a behavior test.
   - **Acceptance gates whose chief assertion is BUILD SUCCESSFUL.** A Gradle task green-light is necessary but never sufficient — the gate MUST also assert on a meaningful outcome (correct test count, presence of evidence file, field-level data equality, etc.).

5. **Recurring Bluff Hunt.** Once per development phase (every 2-4 weeks of active work), the team performs a bluff hunt. Procedure:

   ```bash
   # Pick 5 random *Test.kt files from the project
   bluff_targets=$(find core feature -name '*Test.kt' | shuf -n 5)

   # For each target:
   for t in $bluff_targets; do
     # 1. Read the test, identify the production class it claims to cover
     # 2. Apply a deliberate mutation to that production class
     # 3. Run the test
     # 4. If the test still passes, the test is a bluff — file an issue,
     #    classify the bluff (mock-the-SUT, count-only assertion, etc.),
     #    and either rewrite or remove the test
     # 5. Revert the mutation; the test must pass again
   done
   ```

   Output recorded in `.lava-ci-evidence/bluff-hunt/<date>.json` with: targets, mutations, observed failures or surviving passes, and any issues opened. The bluff hunt is a phase gate — a phase cannot be marked complete until the bluff hunt has run and any surviving bluffs are addressed.

6. **Bluff Discovery Protocol.** When a real user reports a bug whose corresponding tests are green, a Seventh Law incident is declared. The protocol:

   - The fix commit for the user-visible bug MUST include a regression test that, before the fix, fails with a clear assertion message
   - The bluff that hid the bug MUST be diagnosed and recorded in `.lava-ci-evidence/sixth-law-incidents/<date>.json` with: which test was the bluff, why it was a bluff (which clause was violated), the mutation that would have caught it
   - The bluff classification (e.g. "mocked the network call", "asserted only on call count") MUST be added to the Forbidden Test Patterns list above if not already there
   - The Seventh Law itself MUST be reviewed for a new clause that prevents the same class of bluff in the future

7. **Inheritance and Propagation.** The Seventh Law applies recursively to every submodule, every feature, and every new artifact (including all `vasic-digital/*` submodules and the `lava-api-go` service). Each submodule's `CLAUDE.md` MUST contain either the verbatim Seventh Law or a link to this section in the parent. Submodules MAY add stricter clauses but MUST NOT relax any clause. New submodule adoption is conditional on Seventh Law compliance — non-compliant external submodules MUST be forked under `vasic-digital/` and brought into compliance before adoption.

#### Sixth Law extensions (lessons-learned addenda)

The clauses above are immutable. The clauses below are added when a real bug ships green and we need to prevent the *class* of bug, not just the specific instance.

##### 6.A — Real-binary contract tests (added 2026-04-29)

**Forensic anchor:** the lava-api-go container ran 569 consecutive failing healthchecks in production while serving real HTTP/3 + HTTP/2 traffic. `docker-compose.yml` invoked `healthprobe --http3 https://localhost:8443/health`, but the binary at `lava-api-go/cmd/healthprobe/main.go` only registered `-url` / `-insecure` / `-timeout`. `flag.Parse()` rejected the unknown `--http3` and the probe exited 1 every 10 seconds. `docker compose config --quiet` was happy with the YAML; every functional test was green; the orchestrator labelled the container "unhealthy" and there was no test asserting the probe could even start.

**Rule.** Every place where one of our scripts/compose files invokes a binary we own (or build) MUST be covered by a contract test that:

1. Builds (or locates) the target binary.
2. Recovers the binary's registered flag set from its actual `Usage` output (or by importing the package's flag set, where structurally possible).
3. Asserts every flag passed by the script/compose is a strict subset of the binary's registered flag set.
4. Carries a falsifiability rehearsal sub-test that re-introduces the historical buggy flag and confirms the contract checker rejects it. The recorded run must appear in the commit body (Test/Mutation/Observed/Reverted protocol).

The canonical implementation is `lava-api-go/tests/contract/healthcheck_contract_test.go`. New compose healthchecks, new lifecycle scripts, new build glue, new release scripts MUST gain an analogous contract test before the change can be merged.

##### 6.B — Container "Up" is not application-healthy (added 2026-04-29)

`docker ps` / `podman ps` reporting `Up` only means PID 1 is alive — the application inside may be crash-looping, stuck on startup, or (as 6.A shows) green-on-the-outside-broken-on-the-inside. A test that asserts `service.State.Status == "running"` is a bluff test by clauses 1 and 3. Use:

- The same probe the orchestrator uses (or a real-protocol probe at the same surface).
- An end-to-end functional request that exercises the user-visible code path, not the lifecycle plumbing.

The lava-api-go integration tests already do this (real Gin engine via httptest, real Postgres in podman, real cache). New container-bearing features MUST follow the same pattern.

##### 6.C — Mirror-state mismatch checks before tagging (added 2026-04-29)

When mirroring across N upstreams, "all four mirrors push succeeded" is one assertion; "all four mirrors converge to the same SHA at HEAD" is a stronger one. A push that fences against branch protection on one mirror but goes through on the others produces a green-looking session log and a divergent state at rest. `scripts/tag.sh` MUST verify post-push that all four mirrors report the same tip SHA before reporting success. Future releases of `tag.sh` SHOULD record the per-mirror SHA in the evidence file alongside the pretag-verify probe results.

##### 6.D — Behavioral Coverage Contract (added 2026-04-30, SP-3a)

Coverage is measured behaviorally, not lexically. Every public method of every interface added under `core/tracker/api/`, `Submodules/Tracker-SDK/api/`, or any future SDK contract module MUST have at least one real-stack test that traverses the same code path a user's action triggers. Line coverage is reported as a secondary metric. Uncovered lines after the behavioral pass are exempted only via an entry in the per-spec exemption ledger (`docs/superpowers/specs/<spec>-coverage-exemptions.md`) naming the line, the reason, the reviewer, and the date. Blanket coverage waivers are forbidden. The SP-3a exemption ledger lives at `docs/superpowers/specs/2026-04-30-sp3a-coverage-exemptions.md`.

##### 6.E — Capability Honesty (added 2026-04-30, SP-3a)

A `TrackerDescriptor` (or any future descriptor of a feature-bearing component) that declares a capability MUST cause `getFeature()` to return a non-null implementation for the corresponding feature interface. The historical "Not implemented" stub pattern (e.g., `ProxyNetworkApi.checkAuthorized()` returning `false` despite being declared) is a constitutional violation. Capability declared ⇒ feature interface returned ⇒ at least one real-stack test exists for the capability. CI gate: a unit test enumerates every descriptor, every declared capability, and asserts the corresponding `getFeature()` call returns non-null. The SP-3a Phase 2/3 implementations of `RuTrackerClient` and `RuTorClient` already gate `getFeature<T>()` on capability set; new tracker modules MUST follow the same pattern.

##### 6.F — Anti-Bluff Submodule Inheritance (added 2026-04-30, SP-3a)

Clauses 6.A through 6.E inherit recursively to every `vasic-digital` submodule mounted in this repository, to every future submodule, and to every code module added to a submodule. A submodule constitution MAY add stricter rules (e.g. `Tracker-SDK`'s "no domain shape" rule) but MUST NOT relax 6.A–6.F. Adopting an externally maintained dependency that does not satisfy these clauses requires forking it under `vasic-digital/` first. The Seventh Law (Anti-Bluff Enforcement) inherits under the same rule. The Go API service (`lava-api-go/`) inherits 6.D and 6.E binding on its rutracker bridge work in the SP-3a-bridge follow-up.

##### 6.G — End-to-End Provider Operational Verification (added 2026-05-04)

**Forensic anchor:** The Internet Archive provider (`archiveorg`) shipped with `AuthType.NONE` but the `ProviderLoginScreen` → `ProviderLoginViewModel` → `LavaTrackerSdk.login()` flow hung on `CircularProgressIndicator` because `onSubmitClick()` did not short-circuit for no-auth providers and never reset `isLoading = false` when `sdk.login()` returned `null`. All Challenge Tests C1–C8 passed green while the provider was completely unusable.

Rule: Every provider declared in `TrackerDescriptor` and shown in the provider list MUST have at least one end-to-end test that exercises the real production stack from the user's first tap to a successful outcome on a real device or real service. A provider that appears selectable but cannot complete its primary flow (search, browse, download, or anonymous login) is a constitutional violation, irrespective of unit-test coverage.

1. **Auth-type诚实.** A provider with `AuthType.NONE` must have a test that taps "Continue" and reaches the main app. A provider with `AuthType.FORM_LOGIN` must have a test that enters real credentials (from `.env`) and authenticates successfully against the real service. A provider with `AuthType.API_KEY` must have a test that enters a real key and succeeds.
2. **No synthetic-only coverage for provider flows.** Unit tests of `ProviderLoginViewModel` in isolation are insufficient. The test must traverse the real screen, the real ViewModel, the real `LavaTrackerSdk`, and the real HTTP client hitting the real base URL declared in the descriptor.
3. **Falsifiability rehearsal per provider.** Before any new provider is added, the author MUST deliberately break the provider's primary flow (e.g., return `AuthResult.Error` from `login()`, throw in `search()`, return empty results from `browse()`) and confirm the end-to-end test fails with a clear assertion. The PR description MUST state which break was used and what failure the test produced.
4. **Challenge Test expansion.** Challenge Tests C1–C8 cover rutracker and rutor. For every additional provider, a new Challenge Test MUST be added in `app/src/androidTest/kotlin/lava/app/challenges/` before that provider is declared "supported." Unsupported providers MUST NOT appear in the provider list shipped to end users.
5. **Real-device or emulator mandate.** Provider end-to-end tests MUST run on a real Android device or on an Android emulator inside a container. Running them on the JVM with Robolectric or on a mocked NavHost is a bluff test by clause 1.

##### 6.H — Credential Security Inviolability (added 2026-05-04)

**Forensic anchor:** Real tracker credentials (`RUTRACKER_USERNAME`, `RUTRACKER_PASSWORD`, etc.) were stored in `../Boba/.env` and had to be copied into this repository for integration testing. Accidental commit of these credentials would be a security incident.

Rule: Files containing real credentials, signing keys, or API secrets MUST never be committed to any upstream. Violation is a release blocker and a security incident.

1. **`.env` and `.env.*` are inviolable.** The root `.gitignore` MUST contain `.env`, `.env.*`, and `.env.local`. No `.env` file shall ever appear in `git ls-files`.
2. **`keystores/` are inviolable.** The root `.gitignore` MUST contain `keystores/`. Keystore files contain private signing keys.
3. **`app/google-services.json` is inviolable.** The root `.gitignore` MUST contain this file. It contains Firebase API keys.
4. **No credential strings in source code.** No hardcoded username, password, API key, or token shall exist in any `.kt`, `.java`, `.go`, `.gradle`, `.md`, or `.xml` file. The only permissible location for real credentials is `.env` (gitignored) or a local secrets manager.
5. **Pre-push credential scan.** The pre-push hook MUST reject any push that introduces a file matching `.env*` or `*.keystore` or that adds a line matching credential patterns (`password=`, `api_key=`, etc.) to tracked files. `scripts/check-constitution.sh` MUST verify this.
6. **Leak response protocol.** If credentials are accidentally committed, the commit MUST be purged from all four upstreams immediately, credentials MUST be rotated, and the incident MUST be recorded in `.lava-ci-evidence/sixth-law-incidents/` per the Seventh Law clause 6 protocol.
7. **Inheritance.** This clause inherits recursively to every submodule. Submodule constitutions MAY add stricter rules but MUST NOT relax this one.

## Local-Only CI/CD (Constitutional Constraint)

This project does NOT use, and MUST NOT add, GitHub Actions, GitLab pipelines, Bitbucket pipelines, CircleCI, Travis, Jenkins-as-a-service, Azure Pipelines, or any other hosted/remote CI/CD service. All build, test, lint, security-scan, mutation-test, load-test, image-build, and release-verification activity MUST run on developer machines or on a self-hosted local runner under the operator's direct control.

### Why

1. **Sixth Law alignment.** Real-device, real-environment verification is load-bearing. Hosted CI runs in synthetic, ephemeral environments — green there proves the synthetic env, not the user's product on a real device.
2. **Hosted-CI green has historically produced bluff confidence in this project.** A remote dashboard saying "passing" is psychologically indistinguishable from "shipped and works", which is exactly the failure mode the Sixth Law was written to prevent.
3. **Vendor independence.** This project mirrors to four independent upstreams (GitHub, GitFlic, GitLab, GitVerse) intentionally. Coupling our quality gate to one vendor's pipeline product would re-introduce the single point of failure mirroring is meant to remove.

### Mandatory consequences

- The `scripts/` directory (and any `Makefile`, task runner, or build tool introduced later) IS the CI/CD apparatus. Whatever runs in "release CI" MUST be the same script a developer runs locally — no parallel implementation.
- A single local entry point — `scripts/ci.sh` — invokes every quality gate appropriate to the changed surface (unit, integration, contract, e2e, fuzz, load, security, mutation, real-device). Two modes: `--changed-only` (pre-push: Spotless, changed-module unit tests, constitution parser, forbidden-files check) and `--full` (every module, parity gate, mutation tests, fixture freshness, Compose UI Challenge Tests on a connected Android device or emulator; used at tag time). Each run writes evidence under `.lava-ci-evidence/<UTC-timestamp>/`. It MUST be runnable offline once toolchains and base images are present.
- **Forbidden files.** No `.github/workflows/*`, `.gitlab-ci.yml`, `.circleci/config.yml`, `azure-pipelines.yml`, `bitbucket-pipelines.yml`, `Jenkinsfile` (for hosted Jenkins), or equivalent shall exist on any branch of any of the four upstreams. A pre-push hook MUST reject pushes that introduce such files.
- **Pre-push gate.** A git pre-push hook installed under `.githooks/` (and enabled via `git config core.hooksPath .githooks`) MUST run the relevant subset of `scripts/ci.sh` before any push to any upstream. The hook MUST NOT be bypassable in routine work; `--no-verify` is reserved for documented emergencies and any such use MUST be noted in the next commit message.
- **Release tagging gate.** `scripts/tag.sh` MUST refuse to operate against any commit that has not been certified locally — i.e. the local CI gate must have been run successfully against the exact commit being tagged, and the result recorded in a tracked artifact (e.g. `.lava-ci-evidence/`). This implements the Sixth Law clause 5 in mechanical form.
- **No "it passes on CI" handwave.** A failure that reproduces locally on the developer's machine takes precedence over any other signal. Conversely, a developer who claims "it works for me" without having run the local CI gate has not actually verified anything per the Sixth Law.

### What this rule does NOT forbid

- Running tests, linters, or scanners *on the developer's machine* via any tool (this is the whole point).
- Running tests inside containers locally (this is the whole point).
- Running a self-hosted runner (a real machine the operator controls, invoking the same `scripts/ci.sh`) — that is local CI by another name.
- Per-upstream branch-protection rules that simply *require* status checks to be green; we just don't satisfy those checks via hosted runners.

### Inheritance

Every submodule and every new artifact added to the project (including the Go API service) inherits this rule. Submodule constitutions MAY add stricter local-CI requirements but MUST NOT relax this one or introduce hosted-CI configuration.

## Decoupled Reusable Architecture (Constitutional Constraint)

EVERY component that has a non-Lava-specific use case MUST live in a `vasic-digital` submodule, not in this repo. The boundary is enumerated per-component in the design doc for the sub-project that introduces the component (SP-2 onward). Code that ends up in this repo MUST be either:

- (a) **Lava domain logic** — the 13 rutracker routes, the rutracker HTML parsers, the Compose UI for Lava screens, the `Endpoint` sealed-interface, app/manifest entries, etc.; OR
- (b) **Thin glue** tying `vasic-digital` submodules together for Lava's specific needs.

### Why

1. **Sixth Law alignment.** "It works in Lava" must mean the same thing as "it works in the next vasic-digital project that uses this submodule" — otherwise we are silently coupling generic capability to one product, which is the same class of failure as silently disabling rate-limiting (Sixth Law clause 5 territory).
2. **Bluff prevention.** Code copy-pasted between projects is the highest-bandwidth bluff vector: behaviour drifts silently, fixes don't propagate, "we have an mDNS implementation" turns out to mean four divergent implementations with one bug each.
3. **Operator economics.** The `vasic-digital` org is owned end-to-end. New repos under it cost zero (we own the org, we have CLI access on GitHub and GitLab). Reusing existing submodules costs zero pinning effort. Re-implementing per-project is the only expensive option.

### Mandatory consequences

- **Submodule pins are explicit and frozen by default.** A pinned submodule does NOT auto-fetch latest; we are not obligated to track upstream movement. Frozen forever is acceptable. Updating the pin is a deliberate PR.
- **New non-Lava-specific code added to this repo without a documented "why not a vasic-digital submodule" decision is rejected.** The decision MUST appear either in the relevant design doc or in the PR description.
- **Generic functionality is contributed UPSTREAM first.** Any component that another `vasic-digital` project would conceivably want goes to the appropriate submodule (or a new `vasic-digital/<name>` repo created via `gh repo create vasic-digital/<name>` and `glab repo create vasic-digital/<name>`). Lava then pins to the new hash. Order matters: upstream first, Lava pin second.
- **Every `vasic-digital` submodule we own inherits the Sixth Law and the Local-Only CI/CD rule transitively.** Adopting an externally maintained submodule that violates either is forbidden — fork it under `vasic-digital/` and adopt the fork.
- **Submodule fetch/pull is an EXPLICIT operator action, never automatic.** No git hooks that silently update pins, no `git submodule update --remote` in any release script. The pin is the contract; changing the contract is a code review event.
- **Mirror policy applies recursively.** Every `vasic-digital` submodule we own MUST be mirrored to the same set of upstreams Lava itself mirrors to (GitHub + GitLab + GitFlic + GitVerse), unless explicitly waived for that submodule with a documented reason.

### What this rule does NOT forbid

- Lava-domain code in this repo. The 13 rutracker routes, the rutracker scrapers, the Compose UI, the `Endpoint` model, the `:proxy` Ktor server, the `app/` Android module — all stay here.
- Thin Lava-specific glue files in this repo that compose `vasic-digital` primitives.
- Deferring extraction of a borderline piece during a single PR; the deferral must be tracked as a TODO with a target sub-project for extraction.

### Inheritance

This Decoupled Reusable Architecture rule applies recursively to every `vasic-digital` submodule we own. A submodule constitution MAY add stricter rules (e.g. "no external dependencies") but MUST NOT relax this one — meaning, `vasic-digital` submodules themselves MUST extract their own reusable parts to deeper submodules rather than copy-paste between siblings.

## Testing

Test coverage is essentially zero today. The only existing unit test is `core/preferences/src/test/kotlin/lava/securestorage/EndpointConverterTest.kt` (JUnit 4). New tests should:

- Use **JUnit 4** to match the existing `MainDispatcherRule` in `core:testing`.
- Reuse fakes from `core:testing` (`TestBookmarksRepository`, `TestAuthService`, `TestDispatchers`, …) — **but verify those fakes obey the Anti-Bluff Pact** (behavioral equivalence to real implementations).
- For Orbit ViewModels, use `orbit-test` — already wired as `testImplementation` in every feature module but currently unused.
- Write **Integration Challenge Tests** for every new feature using real UseCase and Repository implementations.

## Things to avoid

- Creating a root `build.gradle.kts` — extend `buildSrc` convention plugins instead.
- Adding XML layouts or Fragment-based screens.
- Adding a `composeOptions { kotlinCompilerExtensionVersion = ... }` block — Compose is managed by the Kotlin Compose compiler plugin + BOM.
- Committing `.env`, `keystores/`, or `app/google-services.json`.
- Letting a `Test*` fake in `:core:testing` drift from its real counterpart — that is a "bluff fake" under the Anti-Bluff Pact and must be updated in the same commit as the real implementation.

---

## Host Machine Stability Directive (Critical Constraint)

**IT IS FORBIDDEN to directly or indirectly cause the host machine to:**
- Suspend, hibernate, or enter standby mode
- Sign out the currently logged-in user
- Terminate the user session or running processes
- Trigger any power-management event that interrupts active work

### Why This Matters
AI agents may run long-duration tasks (builds, tests, container operations). If the host suspends or the user is signed out, all progress is lost, builds fail, and the development session is corrupted. This has happened before and must never happen again.

### What Agents Must NOT Do — Explicit Forbidden Command List

The following invocations are **categorically forbidden** in any committed script, any subagent's planned action, or any agent's tool call. Each is a constitutional violation and a release blocker:

```
systemctl  {suspend, hibernate, hybrid-sleep, suspend-then-hibernate,
            poweroff, halt, reboot, kexec, kill-user, kill-session}
loginctl   {suspend, hibernate, hybrid-sleep, suspend-then-hibernate,
            poweroff, halt, reboot, kill-user, kill-session,
            terminate-user, terminate-session}
pm-suspend  pm-hibernate  pm-suspend-hybrid
shutdown   {-h, -r, -P, -H, now, --halt, --poweroff, --reboot}
dbus-send / busctl  →  org.freedesktop.login1.Manager.{Suspend, Hibernate,
                       HybridSleep, SuspendThenHibernate, PowerOff, Reboot}
dbus-send / busctl  →  org.freedesktop.UPower.{Suspend, Hibernate, HybridSleep}
gsettings set       →  *.power.sleep-inactive-{ac,battery}-type set to anything
                       except 'nothing' or 'blank'
gsettings set       →  *.power.power-button-action  set to anything except
                       'nothing' or 'interactive'
```

Additional rules (broader than the explicit list):
- Never modify power-management settings (sleep timers, lid-close behavior, screensaver activation)
- Never trigger a full-screen exclusive mode that might interfere with session keep-alive
- Never run commands that could exhaust system RAM and trigger an OOM kill of the desktop session
- Never execute `killall`, `pkill`, or mass-process-termination targeting session processes

**Mechanical enforcement.** `scripts/check-constitution.sh` greps tracked files for the forbidden-command patterns above and for credential patterns (per 6.H clause 5); the pre-push hook runs it. A push that introduces any forbidden command or credential string is rejected at the hook layer — do not work around it; fix the underlying violation.

### Forensic record: incident 2026-04-28 18:37 (host poweroff)

A user-space-initiated graceful poweroff occurred via GNOME Shell at 18:37:14 during an active SP-2 implementation session. Root-cause investigation confirmed the trigger was **external to this codebase** (manual GNOME power-button click, hardware power-button press, or out-of-scope scheduled task). Forensic detail and the recovery procedure that worked are recorded in `docs/INCIDENT_2026-04-28-HOST-POWEROFF.md`. Key takeaways now binding on every future agent session:

- The commit-and-push-after-every-phase discipline preserved all completed SP-2 work; the incident was a non-event for the work output. **Maintain that discipline.**
- A pre-push verification (`git ls-files | xargs grep -lE '<forbidden-command-pattern>'` returns empty) is now part of the recovery checklist. The exact regex is in the incident doc.
- After any host power event (whatever the cause), recovery procedure lives in the incident doc — do not skip the orphan-container audit.

### What Agents SHOULD Do
- Keep sessions alive: prefer short, bounded operations over indefinite waits
- For builds/tests longer than a few minutes, use background tasks where possible
- Monitor system load and avoid pushing the host to resource exhaustion
- If a container build or gradle build takes a long time, warn the user and use `--no-daemon` to prevent Gradle daemon from holding locks across suspends

### Docker / Podman Specific Notes
- Container builds and long-running containers do NOT normally cause host suspend
- However, filling the disk with layer caches or consuming all CPU for extended periods can trigger thermal throttling or watchdog timeouts on some systems
- Always clean up old images/containers after builds to avoid disk pressure
