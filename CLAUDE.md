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
   - **Hardcoded-port multi-target test runners (added 2026-05-05 from the matrix-runner port-collision incident).** A multi-target test runner (multi-AVD matrix, multi-VM matrix, multi-host distributed test) that hardcodes a single port/serial/endpoint and relies on per-iteration cleanup to free that target for the next iteration is a bluff by construction. When cleanup is unreliable (which it is under normal operating conditions: race conditions, residual state, partially-failed teardowns), the runner targets the LAST surviving target instead of the new one — silently testing the wrong thing N times while reporting N successful test rows for N different targets. The remediation pattern is **dynamic discovery**: pre/post snapshot of the target list (e.g. `adb devices` for emulators) to detect the NEW target appearing after each launch. Forensic anchor: the 7-day-old port-collision bluff in `Submodules/Containers/pkg/emulator/Boot()` exposed by the 2026-05-05 ultrathink-driven systematic-debugging session — see `.lava-ci-evidence/sixth-law-incidents/2026-05-05-matrix-runner-port-collision-bluff.json`. The architectural fix landed in Submodules/Containers commit 648a4bb.
   - **Forward-compat skip without a tracking citation (added 2026-05-05).** A `@SdkSuppress(maxSdkVersion = N)` (or analogous skip) without a comment citing an open issue + an incident JSON is a green-on-skip — the test reports PASS by not running, which is identical in failure-mode to `@Ignore` without a tracking issue. Per clause 6.J, every skip MUST have a tracking citation. The forward-compat-skip pattern itself is permitted; the unmotivated skip is the bluff. Forensic anchor: the Pixel_9a (API 36) Compose-LazyLayout-Looper failure surfaced by the matrix-runner architectural fix; see `.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json`.

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

   **5.a — Cadence tightening (added 2026-05-05, formalized in §6.N.1).** The 2-4 week phase-end cycle remains the baseline, but three additional triggers fire IN-cycle:
   1. **Per operator anti-bluff-mandate invocation.** First invocation in any 24h window: full 5+2 hunt (5 `*Test.kt` files per the baseline rule + 2 production-code files per §6.N.2). Subsequent invocations within the same 24h: lighter "incident-response hunt" — 1-2 files most relevant to the invocation context (e.g., the area of code the latest discovery flagged).
   2. **Per matrix-runner / gate change** (pre-push enforced — owed via §6.N-debt). Any commit that touches `Submodules/Containers/pkg/emulator/`, `scripts/run-emulator-tests.sh`, `scripts/tag.sh`, or `scripts/check-constitution.sh` MUST be accompanied by a 1-target falsifiability rehearsal in the area of change.
   3. **Per phase-gating attestation file added** (pre-push enforced — owed via §6.N-debt). Any new file under `.lava-ci-evidence/sp3a-challenges/`, `.lava-ci-evidence/<tag>/real-device-verification.{md,json}`, or `.lava-ci-evidence/sixth-law-incidents/` MUST be accompanied by a falsifiability rehearsal of the production code path the attestation claims to cover.

   **5.b — Production-code coverage (added 2026-05-05, formalized in §6.N.2).** Bluff hunts MUST sample production code, not just test files. Layered:
   - **Mandatory minimum (per phase):** 2 files from gate-shaping production code. Canonical list: `scripts/tag.sh` helpers, `scripts/check-constitution.sh`, `scripts/bluff-hunt.sh`, `Submodules/Containers/pkg/emulator/`, `Submodules/Containers/cmd/emulator-matrix/`, the matrix runner's `writeAttestation` function. The list grows as new gate-shaping code lands.
   - **Recommended additional (per phase):** 0-2 files from broader CI-touched code — anything invoked by `scripts/ci.sh` or `scripts/run-emulator-tests.sh`.
   - **Conceptual filter:** for each candidate file, ask "would a bug here be invisible to existing tests?" Prefer files where the answer is yes.

   The mutation-rehearsal protocol from clause 5 baseline applies unchanged. Output recorded under `.lava-ci-evidence/bluff-hunt/<date>.json`.

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

##### 6.I — Multi-Emulator Container Matrix as Real-Device Equivalent (added 2026-05-04)

**Forensic anchor:** clauses 6.G and the Sixth Law clause 5 + Seventh Law clause 3 originally required **operator real-device attestation** before a release tag. In practice, "real device" became a single Pixel-class phone, which (a) only exercises one Android version + one screen size at a time, (b) creates an operator-availability bottleneck where releases stall waiting on hardware, and (c) silently encourages "looks fine on my Pixel" to substitute for "works on the matrix of devices users actually run." That is the same class of bluff the Sixth Law was written to prevent — a single physical device is not a "matrix" any more than a single test is a "suite."

**Rule.** Real-device verification, where required by clause 6.G clause 5, the Sixth Law clause 5, or the Seventh Law clause 3, is satisfied by **a multi-emulator container matrix** that meets ALL of:

1. **Container-bound.** The emulators MUST run inside the project's emulator container (`docker-compose.test.yml` + `docker/emulator/Dockerfile`), not on a developer's host directly. Host-emulator runs are permitted for development iteration; the constitutional gate is the container path because it is reproducible across operator workstations and self-hosted runners (Local-Only CI/CD rule).

2. **Android version coverage.** At minimum: API 28 (Android 9), API 30 (Android 11), API 34 (Android 14), and the latest stable API the project's `compileSdk` targets. A subset is permitted ONLY if a clause-6.G evidence entry names exactly which API levels were skipped and why; "fast iteration" is not a permitted reason.

3. **Screen-size coverage.** At minimum: one phone-class device (any Pixel small/medium), one tablet-class device (Pixel Tablet or Nexus 9), and one TV-class device when the feature touches `TvActivity` or the leanback manifest entries. Form factor matters: layouts that pass on a 6" phone routinely break on a 10" tablet, and the project ships a `TvActivity` exactly because of that.

4. **Per-AVD attestation row.** The evidence file at `.lava-ci-evidence/<tag>/real-device-verification.md` (or `real-device-attestation.json`) MUST contain one row per AVD-test pair: AVD name, Android API level, screen size, test class executed, pass/fail, screenshot or test-report path, timestamp. A green matrix is a SET of rows, not a single PASS line. Missing rows are missing evidence; "all green" without per-AVD detail is a bluff in itself. **Group B extension (added 2026-05-05 evening):** every row additionally carries `diag` (target/sdk/device/adb_devices_state — the per-AVD forensic snapshot captured immediately before instrumentation invocation), `failure_summaries` (parsed JUnit `<failure>` + `<error>` entries; empty array on pass), and `concurrent` (the matrix runner's `--concurrent` setting at the time the row ran; 1 = serial). The run-level `gating` field (boolean; true ⇔ `--concurrent == 1` AND `--dev=false`) is the constitutional eligibility flag — `scripts/tag.sh` refuses to operate on attestations whose `gating` is false (Group B Gate 2) OR whose any row has `concurrent != 1` (Group B Gate 1) OR whose any row's `diag.sdk` does not equal `api_level` (Group B Gate 3, the AVD-shadow bluff). These three additional gates are tag-time enforcement of the per-row evidence the Group B spec at `docs/superpowers/specs/2026-05-05-anti-bluff-mandate-reinforcement-group-b-design.md` (commit e2a3af2) introduces.

5. **Falsifiability rehearsal still applies per matrix.** For at least one AVD per Android-version-class, the deliberate-mutation rehearsal (Sixth Law clause 2) MUST be performed and recorded — the matrix exists to detect divergence between API levels, so a mutation that breaks on API 34 but not on API 28 is a finding worth keeping.

6. **Cold-boot only for the gate run.** The matrix run that produces the evidence MUST start from cold-booted emulators (no snapshot reload). Snapshot reuse during dev is fine; for the gate it is forbidden, because snapshot drift is a known source of false-green ("worked yesterday" ≠ "works after upgrade").

7. **`scripts/tag.sh` enforcement.** `scripts/tag.sh` MUST refuse to operate on a commit whose `.lava-ci-evidence/<tag>/real-device-verification.md` lacks AT LEAST one row per minimum-coverage AVD listed in clause 2 + clause 3, with all such rows reporting pass. There is no exception. "Operator was busy" is not an exception. "Emulator was flaky" is not an exception — flakiness must be diagnosed and fixed; intermittent green is constitutionally indistinguishable from intermittent red.

8. **Inheritance.** Clause 6.I applies recursively to every feature, every submodule, and every new artifact. Submodule constitutions MAY add stricter coverage requirements (e.g. "tablet-class is mandatory for this feature even though the matrix minimum is phone-class") but MUST NOT relax this clause.

The matrix is the gate. A single passing emulator is not.

##### 6.I-debt — Containers-submodule extraction (constitutional debt, 2026-05-04)

The current `docker-compose.test.yml` + `docker/emulator/Dockerfile` + `scripts/run-emulator-tests.sh` are Lava-side container-orchestration code. Per the Decoupled Reusable Architecture rule, generic container-orchestration concerns MUST live in `vasic-digital/Containers`. The matrix-runner machinery required by clause 6.I (multi-AVD orchestration, per-AVD attestation collection, cold-boot enforcement, falsifiability-rehearsal hooks) is the same kind of capability another `vasic-digital` project would want — therefore it MUST be extracted to a new `Submodules/Containers/pkg/emulator/` package and a `cmd/emulator-matrix/` CLI. Lava's `scripts/run-emulator-tests.sh` then becomes thin glue invoking the Containers CLI with Lava-specific arguments (the AVD list, the test class, the evidence path).

This is recorded as constitutional debt because the immediate-session work (writing + running Challenge Tests C9-C12 on the four hidden providers) needs the matrix capability NOW, while the proper extraction is multi-hour work that includes Anti-Bluff falsifiability rehearsal of the new Containers package's own tests. Acceptable transitional state: development-iteration runs use Lava-side compose; the constitutional-gate run (the one that produces `.lava-ci-evidence/<tag>/real-device-verification.md` and unblocks `scripts/tag.sh`) MUST go through the Containers package once it ships. No release tag is cut while this debt is open.

Tracking: the next phase that touches release tagging MUST close this debt before its tag.

##### 6.J — Anti-Bluff Functional Reality Mandate (added 2026-05-04)

**Forensic anchor (recurring):** the operator has now twice flagged that "tests pass green while features don't actually work for users" (Internet Archive stuck-on-loading + the broader concern that Sixth Law clauses 1-5 alone are insufficient enforcement). The Anti-Bluff Pact is mature; what is needed is the most direct possible re-statement so that no future agent or contributor can read past it.

**Rule.** Every test, every Challenge Test, and every CI gate added to or maintained in this codebase MUST do exactly one job: **confirm that the feature it claims to cover actually works for an end user, end-to-end, on the user's real surfaces.**

If a test passes while the feature is broken — irrespective of what the test's author intended, irrespective of what the test's name says, irrespective of how green CI looks — the test has failed at its only job. There is no "but the assertion was technically correct" defense. There is no "the test was just for the wiring" defense. There is no "we'll add the real test next sprint" defense.

The execution of tests and Challenge Tests in this project has ONE purpose: **guarantee the quality, the completion, and the full usability of the product by end users.** Anything that does not contribute to that guarantee is removed, refactored, or rewritten.

**Mechanical implications:**

1. CI green is necessary, NEVER sufficient. (Sixth Law clause 5 in even more direct words.)
2. A green Challenge Test means a real user can complete the flow on the gating matrix (clause 6.I). It does not mean "the wiring compiles" or "the mock returned the expected value."
3. Any agent or contributor may invoke clause 6.J to remove a test they can demonstrate is bluff (the test passes against a deliberately-broken production code path) — even retroactively, even if the test was sacred for years. The bluff classification + the broken-mutation evidence go to `.lava-ci-evidence/sixth-law-incidents/<date>.json`, and the removal is a NORMAL change, not a controversial one.
4. Adding `@Ignore` to a failing test without an open issue is a clause-6.J violation by construction — "ignored" is not "covered."
5. Adding a Challenge Test that the agent CANNOT execute against the gating matrix is itself a bluff, regardless of the assertion's plausibility. Either the test runs on the matrix and passes truthfully, or it does not exist.

This clause exists not to add new mechanics — clauses 6.A through 6.I + the Sixth and Seventh Laws already provide the mechanics — but to be the unambiguous restatement that a future agent or contributor cannot rationalize away. **Tests must guarantee the product works. Anything else is theatre.**

**Inheritance.** Applies recursively to every submodule, every feature, every new artifact. Submodule constitutions MAY emphasize this clause more loudly (e.g. by repeating it verbatim in their `CLAUDE.md`) but MUST NOT relax it.

##### 6.K — Builds-Inside-Containers Mandate (added 2026-05-04)

**Forensic anchor:** the project's emulator-matrix gate (clause 6.I) requires test runs inside containers, but every build that PRODUCES the artifacts those tests exercise (the debug APK, the release APK, the Go API binary, the Ktor proxy fat JAR, the OCI image tarballs) currently runs on the developer's bare host. That is a constitutional inconsistency: the gate run is reproducible, the artifact under test is not. A debug APK built on developer A's machine with their installed Java toolchain + their `~/.gradle/caches/` + their `local.properties` is NOT byte-equivalent to the one built on developer B's machine, and neither is byte-equivalent to the one the CI gate would have produced — any of them might pass on the matrix while the others fail, and we would have no way to tell because there is no shared build surface.

**Rule.** Every build that produces an artifact placed in `releases/`, every build whose output is run inside the emulator-matrix or any clause-6.I gate, and every build whose output is signed for release MUST run inside the project's container path — specifically inside the `vasic-digital/Containers` submodule's build orchestration (existing `cmd/distributed-build` + `pkg/distribution` + `pkg/runtime` primitives), invoked by thin Lava-side glue.

1. **Container-bound builds.** `:app:assembleDebug`, `:app:assembleRelease`, `:proxy:buildFatJar`, `lava-api-go` static-binary build, OCI image builds — all run inside a build container (a JDK-bearing container for Gradle, a Go-bearing container for the Go service) brought up by `Submodules/Containers`. Local incremental dev builds on the host are PERMITTED for iteration; the constitutional gate, the release-artifact build, and the build whose output goes through the emulator matrix MUST go through Containers.

2. **Containers MUST natively support Android emulators.** The existing `cmd/distributed-test` already routes Android-test execution through container orchestration. Per this clause, `Submodules/Containers/pkg/emulator/` is added as a first-class package alongside `pkg/runtime`, `pkg/compose`, `pkg/orchestrator`, `pkg/health`, `pkg/lifecycle`, `pkg/distribution`. Its responsibility: spin up Android emulators in containers (cold-boot per clause 6.I), wire `adb` to the host, install APKs, drive instrumentation tests, collect per-AVD attestation evidence, tear down. Lava's `scripts/run-emulator-tests.sh` becomes thin glue invoking this package.

3. **Containers SHOULD investigate non-Android emulators.** Roadmap items for `pkg/emulator/` (or a sibling `pkg/vm/` if architecturally cleaner), recorded as 6.K-debt and tracked but NOT release-blocking for the next Lava tag:
   - **QEMU full-system emulation** — boot non-x86 OS images (ARM, RISC-V, MIPS) inside KVM-accelerated containers for cross-architecture testing. Useful for Lava's signing/keystore code that may rely on JCA providers behaving differently across CPU architectures.
   - **Other OS emulators** — Linux distributions (Alpine, Debian, Fedora, Arch) for testing the Lava proxy's distroless container behavior across base-image families; FreeBSD for verifying `lava-api-go`'s POSIX-only assumptions; minimal Windows for verifying the gradle wrapper's `gradlew.bat` parity.
   - **iOS / macOS emulation** — out of scope unless and until Lava ships an iOS client; recorded here so the roadmap item exists.

4. **Sixth Law alignment.** Per 6.J ("tests must guarantee the product works"), an artifact built outside the gating container path and tested inside the gating emulator path is constitutionally suspect — green tests against an artifact the gate did not build are a bluff vector by construction. Closing 6.K closes that vector. Until 6.K is closed, every release-evidence note MUST cite which artifact came from which build path; tags with mismatched-path builds are forbidden.

5. **`scripts/check-constitution.sh` enforcement.** Once `Submodules/Containers/pkg/emulator/` ships, the constitution checker MUST verify the existence of: (a) the `pkg/emulator/` package in the pinned Containers submodule, (b) Lava-side thin-glue scripts referencing the package's CLI, (c) at least one passing test inside `Submodules/Containers/pkg/emulator/` exercising a real container-emulator boot via `pkg/runtime`'s auto-detected Docker/Podman. Failure of any of (a)/(b)/(c) is a clause-6.K violation; pre-push rejects.

6. **Falsifiability rehearsal applies to 6.K's own infrastructure.** Per 6.J + clause 6.A, the new Containers `pkg/emulator/` package's tests MUST themselves be falsifiable — deliberate-mutation rehearsal recorded in commit body, observed-failure captured, reverted. The same standard the Lava-side Anti-Bluff Pact applies.

7. **Inheritance.** Applies recursively to every submodule, every feature, every new artifact. Submodule constitutions MAY add stricter rules (e.g. "this submodule's binary MUST also be built inside Containers' multi-arch matrix") but MUST NOT relax this clause.

##### 6.K-debt — Containers extension implementation (constitutional debt, 2026-05-04)

The clauses above are the contract. The implementation is owed: `Submodules/Containers/pkg/emulator/` does not yet exist. Until it does, the Lava-side `docker-compose.test.yml` + `docker/emulator/Dockerfile` + `scripts/run-emulator-tests.sh` are the transitional path — they remain in this repo as Lava-domain glue rather than being extracted. The next phase that touches release tagging or the emulator-matrix gate MUST close this debt before its tag, and the close MUST: (1) add `pkg/emulator/` to Containers, (2) add at least the QEMU baseline to a sibling `pkg/vm/`, (3) extract Lava's emulator-orchestration glue, (4) update the constitution checker per clause 6.K clause 5. No release tag is cut while this debt is open, except for hotfixes whose changeset does not touch the emulator-matrix gate's output.

**RESOLVED 2026-05-07** via `pkg/vm + image-cache bundled` spec at `docs/superpowers/specs/2026-05-05-pkg-vm-image-cache-design.md` (commit c8dc198) and plan at `docs/superpowers/plans/2026-05-05-pkg-vm-image-cache.md`. Implementation chain: Containers commits on `lava-pin/2026-05-07-pkg-vm` (Phase A introduces `pkg/cache/`, `pkg/vm/`, `cmd/vm-matrix/`; Phase B refactors `pkg/emulator/` to route image fetch through `pkg/cache/`). Lava parent commits on master (Phase C ships `tools/lava-containers/vm-images.json` + `scripts/run-vm-{signing,distro}-matrix.sh` + `tests/vm-{signing,distro}/*` + this RESOLVED note; Phase D bumps the pin + closure attestation). The §6.K-debt entry stays in this `CLAUDE.md` as a forensic record but is no longer load-bearing.

##### 6.M — Host-Stability Forensic Discipline (added 2026-05-04 evening, recurrence forensics)

**Forensic anchor:** the operator reported a second possible host-stability incident on 2026-05-04 evening: "computer has stuck or it was suspended / sent to standby / hybernated or signed out (somehow by someone or by something — it is unclear)". A 5-step audit (uptime, who, journalctl logind events, free memory, OOM kernel events) confirmed that **no actual host event occurred** — the host had been continuously up 9h43m, the same user was logged in throughout, no logind transitions appeared in the journal, no OOM kills, no critical kernel events, no forbidden commands invoked anywhere in the session's command stream. The operator's perception was real, but no constitutional violation occurred and no project code path was implicated. Full audit recorded at `.lava-ci-evidence/sixth-law-incidents/2026-05-04-perceived-host-instability.json`.

The audit revealed that **the Forbidden Command List + the existing Host Machine Stability Directive were sufficient** — no command in the session's history matched any forbidden pattern. The clause that this incident motivates is not a new prohibition; it is a forensic discipline so the next perceived-instability event can be classified in 60 seconds rather than rederived from scratch.

**Rule.** Every perceived-instability event — whether a real host suspend, a real sign-out, OR an operator-perceived freeze without forensic evidence — MUST be classified into one of three categories AND recorded with the same audit set:

1. **Class I** — verifiable host event (poweroff, suspend, hibernate, sign-out). `uptime` reset; logged-in users changed; journalctl shows logind transition. Example: `docs/INCIDENT_2026-04-28-HOST-POWEROFF.md`. Triggers the post-poweroff recovery procedure (orphan-container audit, pre-push verification, etc.).

2. **Class II** — measurable resource pressure causing partial freeze (kernel OOM kill of a session process, swap exhaustion, thermal throttling, fs full). `uptime` continuous but `journalctl` shows the kernel intervening. No observed example yet in this project.

3. **Class III** — operator-perceived instability with NO forensic evidence. `uptime` continuous, same user, no journal events, no OOM, no resource pressure. Example: `.lava-ci-evidence/sixth-law-incidents/2026-05-04-perceived-host-instability.json`. The user's perception is real (often a long-running gradle build that paused GUI responsiveness, an emulator GPU lockup, or a remote SSH session disconnect), but no project code path is implicated and no host event is recorded.

**Audit protocol (60-second forensic).** Every perceived-instability response MUST run this set BEFORE concluding anything:

```bash
# 1. Uptime + logged-in users (Class I detector)
uptime; who

# 2. Logind events since session start (Class I detector)
journalctl --user --since '<start-time>' | grep -iE 'suspend|hibernate|poweroff|halt|kill-user|kill-session|terminate-user|terminate-session|sign-out'

# 3. Kernel critical events (Class II detector)
journalctl --since '10 min ago' | grep -iE 'oom|killed process|emergency|critical'

# 4. Memory + swap (Class II detector)
free -h

# 5. Disk pressure (Class II detector)
df -h /run/media/<volume>

# 6. Forbidden-command-in-tracked-files cross-check
grep -rE 'systemctl[[:space:]]+(suspend|hibernate|...)|loginctl[[:space:]]+(...)|pm-suspend|...' \
  --include='*.sh' --include='*.kt' --include='*.go' --include='*.kts' . | \
  grep -v '\.git\|build/\|/INCIDENT_\|forbidden'

# 7. Container state (operator-directive: "destroy + rebuild" mandate)
podman ps -a | head
docker ps -a 2>/dev/null  # may be "command not found" on hosts using only podman
```

**Outputs from each step go into the incident record.** A perceived-instability event without an audit record is itself a Seventh Law violation — clause 6.J's "tests must guarantee the product works" applies to the project's response to incidents too.

**Container-runtime safety analysis (recorded once, referenced forever).**

- **Podman (rootless)** — the runtime in use on the operator's primary host. Rootless Podman has NO host-level power-management privileges by design. It cannot invoke systemctl suspend, cannot trigger logind transitions, cannot sign out the user. The per-user `podman.service` is itself a session service; stopping it stops containers but does NOT affect the user's login session. Read-only operations (`podman ps`, `podman images`) are safe to invoke from any audit. Mutating operations (`podman rm`, `podman stop`) only affect containers, not the host. **Conclusion: Podman cannot cause Class I host events.** Recorded once here so future sessions don't re-derive.
- **Docker (rootful daemon)** — NOT installed on this host (`command not found`). When present elsewhere, the docker daemon runs as root, so in principle a maliciously-scripted container restart cycle could pressure the host. Lava does not install or invoke Docker; if a future host requires it, the rootless Podman compatibility layer (`docker` symlink to `podman` via podman-docker package) is preferred. **Conclusion for Lava-on-this-host: Docker is not a factor.**
- **Container builds (`./build_and_release.sh`)** — invokes `podman build` + `podman save` for image export. Both are session-scoped operations. Image-tar exports can be I/O heavy on `/run/media/...` filesystems (the audit's disk-pressure step catches this); archive-rotation hygiene is in `releases/` directory cleanup, not in container management.

**Emulator zombie-cleanup hook (action item from this incident).** `scripts/run-emulator-tests.sh` MUST be extended with a pre-boot hook that kills any `qemu-system` processes left over from interrupted matrix runs before launching new ones. Zombies don't cause host events but they (a) hold ADB ports, (b) consume RAM, (c) confuse the operator's audit. Owed in the next phase that touches the script.

**Inheritance.** Clause 6.M applies recursively to every submodule, every feature, and every new artifact. Submodule constitutions MAY add stricter forensic requirements (e.g., `Submodules/Containers` SHOULD record per-container-runtime audit findings as it's the source of truth for runtime detection) but MUST NOT relax this clause.

##### 6.N — Bluff-Hunt Cadence Tightening + Production Code Coverage (added 2026-05-05)

**Forensic anchor:** the 7-day-old architectural bluff in `Submodules/Containers/pkg/emulator/Boot()` (hardcoded `ADBPort=5555`) exposed by ultrathink-driven systematic-debugging on 2026-05-05; recorded at `.lava-ci-evidence/sixth-law-incidents/2026-05-05-matrix-runner-port-collision-bluff.json`. The bluff was invisible to all existing `*Test.kt`-targeted bluff hunts because the buggy code was production Go code that no test could mutate-rehearse — only end-to-end multi-AVD matrix runs would have caught it, and those runs were themselves rendered green by the bluff (textbook clause-6.J failure mode). The operator's 8th invocation of the anti-bluff mandate landed concurrently with this discovery, demanding tightened cadence and broader scope for bluff hunts.

**6.N.1 — Tightened cadence.** The 2-4 week phase-end cycle (Seventh Law clause 5 baseline) remains. Three additional triggers fire IN-cycle:

1. **Per operator anti-bluff-mandate invocation.** First invocation in any 24h window: full 5+2 hunt (5 `*Test.kt` per Seventh Law clause 5 baseline + 2 production-code files per §6.N.2). Subsequent invocations within the same 24h: lighter "incident-response hunt" — 1-2 files most relevant to the invocation context.
2. **Per matrix-runner / gate change** (pre-push enforced — see §6.N-debt below). Any commit that touches `Submodules/Containers/pkg/emulator/`, `scripts/run-emulator-tests.sh`, `scripts/tag.sh`, or `scripts/check-constitution.sh` MUST carry a Bluff-Audit stamp recording a 1-target falsifiability rehearsal in the area of change. The hook checks for the `Bluff-Audit:` block AND verifies the mutation reasoning targets a file in the diff.
3. **Per phase-gating attestation file added** (pre-push enforced — see §6.N-debt below). Any new file under `.lava-ci-evidence/sp3a-challenges/`, `.lava-ci-evidence/<tag>/real-device-verification.{md,json}`, or `.lava-ci-evidence/sixth-law-incidents/` MUST be accompanied by a falsifiability rehearsal of the production code path the attestation claims to cover. The rehearsal evidence MAY be embedded in the attestation OR live as a companion file in the same directory.

**6.N.2 — Production-code coverage in bluff hunts.** Bluff hunts MUST sample production code beyond `*Test.kt` / `*_test.go`. Layered scope:

- **Mandatory minimum (per phase):** 2 files from gate-shaping production code. Gate-shaping = files whose output determines pass/fail of a constitutional gate. Canonical list: `scripts/tag.sh` helpers, `scripts/check-constitution.sh`, `scripts/bluff-hunt.sh`, `Submodules/Containers/pkg/emulator/`, `Submodules/Containers/cmd/emulator-matrix/`, the matrix runner's `writeAttestation` function. The list grows as new gate-shaping code lands.
- **Recommended additional (per phase):** 0-2 files from broader CI-touched code — anything invoked by `scripts/ci.sh` or `scripts/run-emulator-tests.sh`, including Lava-domain Kotlin/Go production paths.
- **Conceptual filter:** for each candidate file, ask "would a bug here be invisible to existing tests?" Prefer files where the answer is yes — those are the bluff-rich targets.

The mutation-rehearsal protocol from Seventh Law clause 5 applies unchanged: pick file → apply deliberate mutation that affects the gate's verdict → run the gate → confirm the gate fails (or surfaces the wrong outcome) → revert → re-run → confirm green again. Record outcome in `.lava-ci-evidence/bluff-hunt/<date>.json`.

**6.N.3 — §6.N-debt (forward-reference to Group A-prime spec).** The pre-push hook enforcement clauses (6.N.1.2 + 6.N.1.3 above) are documented but NOT yet implemented. Implementation is deferred to the Group A-prime spec, which the parent Group A spec spawns as the next brainstorming + writing-plans cycle (`docs/superpowers/specs/<TBD-Group-A-prime>.md`). Until Group A-prime ships:
- 6.N.1.1 (per-invocation hunt) is operator-driven manual cadence — no mechanical enforcement
- 6.N.1.2 + 6.N.1.3 are documented requirements only — no mechanical enforcement
- The §6.N-debt entry stays open in this `CLAUDE.md` until Group A-prime closes it. The constitution checker (`scripts/check-constitution.sh`) MAY warn but MUST NOT yet hard-fail on missing rehearsals.

**Inheritance.** Clause 6.N applies recursively to every submodule, every feature, and every new artifact. Submodule constitutions MAY add stricter cadence requirements (e.g., `Submodules/Containers` SHOULD bluff-hunt every change to `pkg/emulator/` since it is the source of truth for the matrix gate) but MUST NOT relax this clause.

##### 6.N-debt — Pre-push hook enforcement of 6.N.1 clauses 2 + 3 (constitutional debt, 2026-05-05)

The clauses above are the contract. Mechanical enforcement (pre-push hook code that rejects non-compliant commits) is owed via the Group A-prime spec, which is the next brainstorming target after Group A lands. Until Group A-prime ships:

- 6.N.1.2 (per matrix-runner/gate change) is documented requirement; reviewer MUST manually verify Bluff-Audit stamps in commit messages touching the named files
- 6.N.1.3 (per attestation) is documented requirement; reviewer MUST manually verify falsifiability rehearsal evidence accompanies new attestation files

The next phase that touches `scripts/check-constitution.sh` MUST close 6.N-debt before its commit lands, and the close MUST: (1) parse commit messages for `Bluff-Audit:` stamps when 6.N.1.2-listed files appear in the diff, (2) check for falsifiability rehearsal evidence (in-attestation or companion file) when 6.N.1.3-listed paths gain new files, (3) update the constitution checker's gate set accordingly.

**RESOLVED 2026-05-05 evening** via Group A-prime spec at `docs/superpowers/specs/2026-05-05-anti-bluff-mandate-reinforcement-group-a-prime-design.md` (commit `bb2d6a1`). Implementation chain: `Submodules/Containers` commit (Cleanup API + matrix.go gradle-log persistence) + Lava parent commit (pre-push Check 4 + Check 5 + check-constitution.sh §6.N awareness + scripts/run-emulator-tests.sh refactor). Pre-push hook Checks 4 + 5 active; constitution checker hard-fails on missing §6.N propagation OR missing rehearsal stamps. The §6.N-debt entry stays in this CLAUDE.md as a forensic record but is no longer load-bearing.

##### 6.O — Crashlytics-Resolved Issue Coverage Mandate (added 2026-05-05, ELEVENTH §6.L invocation)

**Forensic anchor:** within minutes of the first Firebase-instrumented APK distribution (Lava-Android-1.2.3-1023, commit `e9de508`, distributed 2026-05-05 22:33), Firebase Crashlytics recorded 2 crashes that the operator surfaced via the dashboard. The constitutional concern: Crashlytics issues that are "resolved" by a code fix tend to drift back to broken when the same class of bug recurs months later — because the original incident left no test record, the regression is invisible to the test suite. The operator has now invoked the §6.L mandate for the **ELEVENTH TIME** to make this binding.

**Rule.** Every Crashlytics-recorded issue (fatal OR non-fatal) that is closed/resolved by any commit MUST be covered by AT LEAST ONE validation test AND ONE Challenge Test that, before the fix, fails with a clear assertion message. There is no exception. "It's an environmental flake" is not an exception. "We can't reproduce it locally" is not an exception — the absence of a reproducer is itself a Sixth Law clause 4 violation, which means the feature is not shippable until the reproducer exists.

1. **Validation test (unit / integration level).** A test in the language of the crashing surface (Kotlin/JUnit for Android, Go for lava-api-go) that reproduces the conditions that triggered the Crashlytics record. Lives at the appropriate `*Test.kt` or `*_test.go` path. Falsifiability rehearsal recorded in commit body (Bluff-Audit stamp).

2. **Challenge Test (end-to-end level).** A Compose UI Challenge Test under `app/src/androidTest/kotlin/lava/app/challenges/` (for client-side issues) or a `tests/e2e/` test (for lava-api-go issues) that drives the same user-facing path that produced the original crash, asserts on user-visible state, and would fail before the fix.

3. **Crashlytics issue closure log.** Each closed issue MUST have an entry in `.lava-ci-evidence/crashlytics-resolved/<YYYY-MM-DD>-<short-slug>.md` containing: the original Crashlytics issue ID (or shareable link from the Console), the stack trace summary, the root-cause analysis, the fix commit SHA, and links to the validation test + Challenge Test that cover the regression. The constitution checker (`scripts/check-constitution.sh`) MAY warn if a commit references a Crashlytics fix but no closure log exists; the next phase that touches the checker SHOULD make this a hard fail.

4. **Pre-tag verification.** Before any release tag is cut on a commit that closes one or more Crashlytics issues, the operator MUST verify (a) the validation tests pass, (b) the Challenge Tests pass on the gating matrix per §6.I, (c) the closure logs exist. `scripts/tag.sh` MUST refuse to operate on commits whose CHANGELOG mentions Crashlytics fixes without matching closure logs. There is no exception.

5. **Closing the Crashlytics dashboard issue.** Marking an issue "closed" in the Firebase Console requires an interactive Console action (no public REST API). The operator does this AFTER the commit lands AND the closure log + tests are in place — never before, because the dashboard close-mark loses the ability to track regressions if the test coverage is missing.

6. **Inheritance.** Clause 6.O applies recursively to every submodule, every feature, every new artifact (including lava-api-go and every `vasic-digital/*` submodule). Submodule constitutions MAY add stricter requirements (e.g. "this submodule's Crashlytics issues MUST also gain a chaos-engineering scenario") but MUST NOT relax this clause.

The Crashlytics dashboard records what user devices saw. The validation + Challenge tests guarantee the same user-visible defect cannot recur silently. Both surfaces — the Crashlytics close-mark AND the test suite — MUST be in lockstep; otherwise the test suite is a bluff per §6.J/§6.L.

##### 6.P — Distribution Versioning + Changelog Mandate (added 2026-05-05, TWELFTH §6.L invocation)

**Forensic anchor:** the operator (TWELFTH §6.L invocation, 2026-05-05 23:11): "when distributing new build it must have version code bigger by at least one then the last version code available for download (already distributed). Every distributed build MUST CONTAIN changelog with the details what it includes compared to previous one we have published! Make sure all these points are in Constitution, CLAUDE.MD and AGENTS.MD."

**Rule.** Every distribution channel — Firebase App Distribution, container registry pushes, releases/ directory snapshots, scripts/tag.sh artifact production — MUST:

1. **Strictly increasing version code per artifact, per channel.** No re-distribution of an already-published versionCode is permitted. A bug fix to an already-distributed build MUST bump the version code (e.g., 1023 → 1024) AND the version name (e.g., 1.2.3 → 1.2.4 for user-visible bug fixes; 1.2.3 → 1.2.3.1 is NOT acceptable since Android version names are 3-component). For the Go API: `Code` integer monotonic increment + corresponding `Name` semver bump. For the Ktor proxy: `apiVersionCode` integer + `apiVersionName` semver bump in lockstep. ServiceAdvertisement.API_VERSION MUST track apiVersionName (per the §6.A real-binary contract test added in commit 4411def).

2. **Mandatory changelog per distribution.** Every distribute action MUST include a CHANGELOG entry detailing what changed since the previous published build:
   - **Source:** `CHANGELOG.md` at repo root (canonical) + per-version snapshot at `.lava-ci-evidence/distribute-changelog/<channel>/<version>-<code>.md`.
   - **Content:** Bullet list of user-visible changes since the previous published version. Each bullet MUST cite the commit SHA, the high-level change, and (where applicable) the §6.O Crashlytics issue closure log it relates to.
   - **Distribution payload:** `scripts/firebase-distribute.sh` MUST inject the changelog into the App Distribution release-notes field via `--release-notes` (truncated to 16KB if needed; full text remains in `CHANGELOG.md` and the per-version snapshot).
   - **Operator-visible:** the testers' Firebase invitation email surface the changelog AND a link to the canonical CHANGELOG.md.

3. **Mechanical version-code bump enforcement.** `scripts/firebase-distribute.sh` MUST refuse to operate when:
   - The CURRENT versionCode (parsed from `app/build.gradle.kts`) is ≤ the LAST distributed versionCode for the same channel (read from `.lava-ci-evidence/distribute-changelog/<channel>/last-version`).
   - The CHANGELOG.md does not contain an entry for the current `versionName (versionCode)` header.
   - The per-version snapshot file is missing.

4. **Pre-tag enforcement.** `scripts/tag.sh` MUST refuse to operate when CHANGELOG.md lacks an entry for the tag's version, OR when the per-version snapshot file is missing under `.lava-ci-evidence/distribute-changelog/`. There is no exception. "Operator was busy" is not an exception.

5. **Re-distribution of an existing version is forbidden.** If a build needs re-uploading (e.g., signing-key rotation), the version MUST be bumped first. The exception: the same `versionCode/versionName` MAY be re-uploaded WITHIN A SINGLE distribute session (idempotent retry on transient network failure) but MUST NOT cross session boundaries — the persisted last-version file is the authority.

6. **Inheritance.** Clause 6.P applies recursively to every submodule, every feature, every new artifact (Android client, Ktor proxy, lava-api-go, vasic-digital submodule binaries, future iOS clients, future container images). Submodule constitutions MAY add stricter rules (e.g., "this submodule's artifacts also require a SBOM in the changelog") but MUST NOT relax this clause.

##### 6.Q — Compose Layout Antipattern Guard (added 2026-05-05, THIRTEENTH §6.L invocation)

**Forensic anchor:** 2026-05-05 23:51 operator report: "Opening Trackers from Settings crashes the app." Root cause: `TrackerSelectorList` Composable used `LazyColumn` nested inside `TrackerSettingsScreen`'s `Column(verticalScroll(rememberScrollState()))`. Compose's measurement protocol throws `IllegalStateException: Vertically scrollable component was measured with an infinite maximum height constraint` when a lazy layout receives unbounded height from a parent's `verticalScroll`. The crash had been latent in production code since SP-3a Phase 4 (Task 4.12) — multiple Crashlytics-fix cycles passed before any tester actually opened the screen and surfaced it. Closure log at `.lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md`.

**Rule.** Lava's Compose UI code MUST NOT nest a vertically-scrolling lazy layout (`LazyColumn`, `LazyVerticalGrid`, `LazyVerticalStaggeredGrid`) inside a parent that gives it unbounded vertical space. The forbidden parents include — but are not limited to — `Modifier.verticalScroll`, `Modifier.wrapContentHeight(unbounded = true)`, and any `LinearLayout`-with-weight wrapper. Equivalent rule for horizontal layouts: no `LazyRow` / `LazyHorizontalGrid` / `LazyHorizontalStaggeredGrid` inside `Modifier.horizontalScroll` or unbounded-width parents.

1. **Mechanical enforcement.** A static structural test under `tests/compose-layout/` (or per-feature equivalent — see `feature/tracker_settings/src/test/.../TrackerSelectorListLazyColumnRegressionTest.kt` for the pattern) MUST scan source files and reject:
   - Imports of `androidx.compose.foundation.lazy.LazyColumn` (and siblings) in files where the enclosing screen uses `verticalScroll`.
   - The opposite case: Composables that import `verticalScroll` AND are invoked from a Composable that already vertical-scrolls.

   Per-screen overrides are permitted ONLY when a comment cites the `LazyListState`-or-equivalent pattern that bounds the lazy layout's height (e.g. `Modifier.heightIn(max = X.dp)` on the LazyColumn). The override MUST link to a real-stack screenshot test or Compose UI Challenge Test that proves the bounded layout renders.

2. **The list of forbidden patterns is canonical, not exhaustive.** Other measurement-conflict antipatterns also fall under §6.Q:
   - `Modifier.fillMaxHeight()` on a child of `verticalScroll` — bounded child of unbounded parent → unexpected layout.
   - `Modifier.weight(1f)` on a `Row` whose parent is `horizontalScroll` — weight requires bounded width.
   - Multiple `LazyColumn`s as siblings inside a non-lazy parent — each gets unbounded height; only the first composes its viewport correctly.

3. **Acceptance gate per layout-touching feature.** Every Compose UI module that adds OR modifies a screen-level Composable MUST gain (a) a structural test asserting no nested-scroll antipattern in the new file, and (b) a Compose UI Challenge Test under `app/src/androidTest/kotlin/lava/app/challenges/C<N>_<Name>Test.kt` that drives the rendered screen and asserts on user-visible state. Falsifiability rehearsal recorded per §6.N.

4. **Inheritance.** Clause 6.Q applies recursively to every submodule, every feature, every new artifact. Submodule constitutions MAY add stricter Compose-layout rules (e.g. "no lazy layouts at all" for a constrained-list-only screen) but MUST NOT relax this clause.

##### 6.R — No-Hardcoding Mandate (added 2026-05-06, FOURTEENTH §6.L invocation)

**Forensic anchor:** 2026-05-06 operator directive during Phase 1 brainstorm: "Pay attention that we MUST NOT hardcode anything ever!" — restating the spirit of §6.J for an entire class of bluffs (literal values that drift silently from their intended source-of-truth).

**Rule.** No connection address, port, header field name, credential, key, salt, secret, schedule, algorithm parameter, or domain literal shall appear as a string/int constant in tracked source code (`.kt`, `.java`, `.go`, `.gradle`, `.kts`, `.xml`, `.yaml`, `.yml`, `.json`, `.sh`). Every such value MUST come from a config source: `.env` (gitignored), generated config class (build-time codegen reading `.env`), runtime env var, or mounted file.

The placeholder file `.env.example` (committed) carries dummy values for every variable so a developer cloning the repo knows what to set.

`scripts/check-constitution.sh` MUST grep tracked files for forbidden literal patterns:
- Any IPv4 address outside `.env.example` and incident docs
- The header name from `.env.example`'s `LAVA_AUTH_FIELD_NAME`
- Any 36-char UUID outside `.env.example`
- Hardcoded `host:port` pairs in HTTP/HTTPS URLs

Pre-push rejects on match. Bluff-Audit stamp required on any commit that adds new config-driven values, demonstrating the no-hardcoding contract test fails when a literal is reintroduced.

**Exemptions** (test fixtures, incident docs, design specs):
- `.env.example` — by definition carries placeholders
- `.lava-ci-evidence/sixth-law-incidents/*.json` — forensic anchors quoting historical literals
- `docs/superpowers/specs/*.md`, `docs/superpowers/plans/*.md` — design docs and implementation plans may show example values for clarity (placeholders preferred but examples permitted)
- `*_test.go`, `*Test.kt`, `*Tests.kt`, `*Test.java` — test fixtures may use synthetic literals, MUST NOT use real production values

**Enforcement status (2026-05-06):** UUID literals are enforced today by `scripts/check-constitution.sh` (delegating to `scripts/scan-no-hardcoded-uuid.sh`). IPv4, host:port, schedule, and algorithm-parameter literals are tracked for future phases per the staged-enforcement principle (rule first, mechanical gate as the project ships its scopes). Open a forensic-anchor entry in `.lava-ci-evidence/sixth-law-incidents/` if a literal of any forbidden class ships in production.

**Inheritance:** applies recursively to every submodule and every new artifact. Submodule constitutions MAY add stricter rules but MUST NOT relax this clause.

##### 6.S — Continuation Document Maintenance Mandate (added 2026-05-06, FIFTEENTH §6.L invocation)

**Forensic anchor:** 2026-05-06 operator directive: "during any work we perform, during Phases implementation, debugging and fixing, during ANY effort we have the Continuation document MUST BE maintained and it MUST NOT BE out of sync with current work we are doing! If for any reson we stop our work, we MUST BE able to continue any time, with current work, exactly where we have left of and from any CLI agent or any LLM model we chose! Nothing can be broken or faulty in maintained Continuation document!"

**Rule.** The file `docs/CONTINUATION.md` is the single-file source-of-truth handoff document for resuming the project's work across any CLI session. It MUST be maintained continuously and MUST NOT drift out of sync with the actual repository state. Every commit that changes any of the following MUST update `docs/CONTINUATION.md` in the SAME COMMIT:

- A phase completes (mark "DONE" in §1)
- A phase starts (move from §4 NOT STARTED into §1 with progress)
- A new spec or plan lands under `docs/superpowers/{specs,plans}/` (record in §1 / §4)
- A submodule pin bumps (update §3 pin index)
- A release artifact ships (Firebase upload, container registry push, tag) — update §0 + §2
- A known issue is discovered (add to §4.5 known-issues subsection)
- A known issue is resolved (move from §4.5 to commit-history reference)
- An operator scope directive lands (update §0 "Last updated" + relevant section)
- A bug is fixed in a way that changes user-visible behavior (record under §4.5 + the closure log path)

**The §0 "Last updated" line MUST be the date of the most recent CONTINUATION-touching commit.** A `Last updated: YYYY-MM-DD` value older than the most recent state-changing commit is a §6.S violation.

**Mechanical enforcement.** `scripts/check-constitution.sh` MUST verify:
1. `docs/CONTINUATION.md` exists at repo root
2. Contains a §0 with a "Last updated" line
3. Contains §7 with the operator-pasteable RESUME PROMPT
4. The §6.S clause is present in CLAUDE.md
5. The §6.S inheritance reference appears in every `Submodules/*/CLAUDE.md` and in `lava-api-go/CLAUDE.md`

Pre-push rejects on any of (1)-(5) failing.

**Why this clause exists.** The operator has had multiple sessions interrupted (host poweroff incident 2026-04-28, mandate restatements across 14 §6.L invocations, model rate-limits, organic context exhaustion). Without a maintained CONTINUATION.md, every session-restart costs hours re-establishing state. With a maintained CONTINUATION.md + the §7 RESUME PROMPT, ANY CLI agent (any model, any session) can pick up the work in under five minutes. The cost of stale CONTINUATION.md is not abstract — it is exactly the lost-work-time the operator's directive cited.

**The bluff this clause prevents.** A future agent rationalizing "I'll update CONTINUATION.md at the end of this phase, not commit-by-commit" — and then losing the session before that end. Or "the file is out of date but it's close enough." Or "I'll fix CONTINUATION.md as a follow-up commit." Each of these reads CONTINUATION.md as a maintenance chore. It is not. It is a §6.J primary-on-user-visible-state assertion: the document that says the project is at state X must STILL say so at every commit's HEAD, or the project IS NOT at state X — the document is lying, and the next agent acts on the lie.

**Inheritance.** Applies recursively to every submodule, every feature, every new artifact (including `lava-api-go`). Submodule constitutions MAY add stricter rules (e.g., "this submodule maintains its own CONTINUATION.md in addition to the parent") but MUST NOT relax this clause. The pre-push hook in the parent enforces presence of the §6.S clause in submodule CLAUDE.md; per-submodule CONTINUATION files are not yet mandated but recommended for any submodule that develops independently.

##### 6.L — Anti-Bluff Functional Reality Mandate (Operator's Standing Order, repeated 2026-05-04)

The user has now invoked this mandate **THIRTEEN TIMES** across two working days (initial fix request, after 6.G/6.H landed, after 6.I/6.J landed, after 6.K landed, then again with `ultrathink` after the layer-3 fix, then again after spotting that "Anonymous Access" was modeled as a global toggle when it is actually a per-provider capability, then on 2026-05-05 after the architectural port-collision bluff in the matrix runner was discovered with the verbatim restatement: "all existing tests and Challenges do work in anti-bluff manner — they MUST confirm that all tested codebase really works as expected", then on 2026-05-05 evening when the operator commissioned a comprehensive plan covering ALL open points and re-emphasized: "execution of tests and Challenges MUST guarantee the quality, the completion and full usability by end users of the product", then on 2026-05-05 late evening immediately after Group C-pkg-vm closed §6.K-debt, when the operator surveyed the next-step menu and re-issued the verbatim mandate, AND now on 2026-05-05 after Phase 7 readiness was reported, when the operator commissioned the full rebuild-and-test-everything cycle for tag Lava-Android-1.2.3 and re-issued the verbatim mandate yet again with the addition: "Rebuild Go API and client app(s), put new builds into releases dir (with properly updated version codes) and execute all existing tests and Challenges! Any issue that pops up MUST BE properly addressed by addressing the root causes (fixing them) and covering everything with validation and verification tests and Challenges!", and now on 2026-05-05 late evening AGAIN immediately after the first Firebase-instrumented APK distribution surfaced 2 Crashlytics-recorded crashes — the verbatim restatement: "We had been in position that all tests do execute with success and all Challenges as well, but in reality the most of the features does not work and can't be used! This MUST NOT be the case and execution of tests and Challenges MUST guarantee the quality, the completition and full usability by end users of the product! ... Each Crashlytics resolved issue MUST BE covered with validation and verification tests and Challenges!", and immediately after that on 2026-05-05 23:11 — "when distributing new build it must have version code bigger by at least one then the last version code available for download (already distribited). Every distributed build MUST CONTAIN changelog with the details what it includes compared to previous one we have published! Make sure all these points are in Constitution, CLAUDE.MD and AGENTS.MD.", and on 2026-05-05 23:51 — the THIRTEENTH invocation, immediately after a real tester reported "Opening Trackers from Settings crashes the app. See Crashlytics for stacktraces. Create proper tests to validate and verify the fix! ... execution of tests and Challenges MUST guarantee the quality, the completition and full usability by end users of the product! This MUST BE part of Constitution of our project, its CLAUDE.MD and AGENTS.MD if it is not there already, and to be applied to all Submodules's Constitutuon, CLAUDE.MD and AGENTS.MD as well (if not there already)!" The crash root cause was a textbook nested-scroll antipattern (`LazyColumn` inside `Column(verticalScroll)`) that no existing test caught — confirming yet again that CI green is necessary, never sufficient. Closure log: `.lava-ci-evidence/crashlytics-resolved/2026-05-05-tracker-settings-nested-scroll.md`.). The count is what makes this clause load-bearing: every restatement is an admission by the operator that the prior layers of constitutional plumbing (6.A through 6.M, the Sixth and Seventh Laws) are not yet enough to evict the bluff class on their own. The repetition itself is the forensic record. This clause is the same as 6.J — every test, every Challenge Test, every CI gate has exactly one job: confirm the feature works for a real user end-to-end on the gating matrix. CI green is necessary, never sufficient. **The reason this clause is restated rather than cross-referenced** is that the operator's standing concern is that future agents and contributors will rationalize their way past 6.J and ship green-tests-with-broken-features again. Every time the operator restates it, this codebase records the restatement here so the next reading agent must look at the same wall of repetition the operator has had to type out.

If you are reading this in a future session and you find yourself thinking *"this test is a small exception"* — STOP. The exception is what produced the Internet Archive stuck-on-loading bug, the broken post-login navigation, the credential leak in C2, the bluffed C1-C8. There are no small exceptions. Tests must guarantee the product works. Anything else is theatre.

**Inheritance.** Applies recursively. Submodule constitutions MAY paste this clause verbatim into their CLAUDE.md to ensure their reading agents see it locally. They MUST NOT abbreviate it; the wall-of-text effect is the point.

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
