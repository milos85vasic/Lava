# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **See also:**
> - `AGENTS.md` — detailed agent guide (tech stack versions, deployment, security notes).
> - `core/CLAUDE.md` and `feature/CLAUDE.md` — scoped Anti-Bluff rules that apply only inside those trees.
> - `docs/ARCHITECTURE.md`, `docs/LOCAL_NETWORK_DISCOVERY.md` — architecture diagrams and the mDNS discovery flow.

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
./containers/bin/lava-containers -cmd=status   # runtime, health, advertised IPs

# Code style (Spotless + ktlint — the only enforced checker)
./gradlew spotlessApply               # run before committing
./gradlew spotlessCheck

# Tests (coverage is minimal; one unit test exists today)
./gradlew test
./gradlew :core:preferences:test --tests "lava.securestorage.EndpointConverterTest"
```

`./start.sh` delegates to the `containers/` Go module (`digital.vasic.containers`), which auto-detects Podman or Docker, builds the proxy fat JAR + image, and runs it via `docker-compose.yml`. The compose file uses **`network_mode: host`** so JmDNS broadcasts reach the LAN — the Android debug build then auto-discovers the proxy via `NsdManager` (`_lava._tcp.local.`).

Signing requires a `.env` at the repo root with `KEYSTORE_PASSWORD` and `KEYSTORE_ROOT_DIR` (see `.env.example`); keystores live in `keystores/` (gitignored). The app build also expects `app/google-services.json` (gitignored) for Firebase.

## Architecture

### Module layout

There is **no root `build.gradle.kts`** — all build logic lives in `buildSrc/` as convention plugins (`lava.android.application`, `lava.android.library`, `lava.android.library.compose`, `lava.android.feature`, `lava.android.hilt`, `lava.kotlin.library`, `lava.kotlin.serialization`, `lava.kotlin.ksp`, `lava.ktor.application`). Module `build.gradle.kts` files apply these by ID — **do not duplicate config in module scripts; extend the convention plugin instead.**

Three top-level groupings (all listed in `settings.gradle.kts`):

- `core/*` — 17 modules. Several use the **api/impl split** (`auth`, `network`, `work`) so consumers depend on `:api` only. Pure-Kotlin modules with no Android dep: `core:models`, `core:common`, `core:auth:api`, `core:network:api`, `core:network:rutracker`, `core:work:api`.
- `feature/*` — 15 screen-level modules; each applies `lava.android.feature` (which auto-wires Hilt, Orbit, and the standard core deps).
- `:app` — Compose entry point, depends on every core + feature module. Holds `MainActivity`, `TvActivity`, top-level navigation graph, and rutracker.org deep-link handling.

Dependency direction: `app → feature:* → core:domain → core:data → core:network:impl + core:database`, plus `core:auth:impl`, `core:work:impl`, `core:navigation`, `core:ui`, `core:designsystem`.

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

## Local-Only CI/CD (Constitutional Constraint)

This project does NOT use, and MUST NOT add, GitHub Actions, GitLab pipelines, Bitbucket pipelines, CircleCI, Travis, Jenkins-as-a-service, Azure Pipelines, or any other hosted/remote CI/CD service. All build, test, lint, security-scan, mutation-test, load-test, image-build, and release-verification activity MUST run on developer machines or on a self-hosted local runner under the operator's direct control.

### Why

1. **Sixth Law alignment.** Real-device, real-environment verification is load-bearing. Hosted CI runs in synthetic, ephemeral environments — green there proves the synthetic env, not the user's product on a real device.
2. **Hosted-CI green has historically produced bluff confidence in this project.** A remote dashboard saying "passing" is psychologically indistinguishable from "shipped and works", which is exactly the failure mode the Sixth Law was written to prevent.
3. **Vendor independence.** This project mirrors to four independent upstreams (GitHub, GitFlic, GitLab, GitVerse) intentionally. Coupling our quality gate to one vendor's pipeline product would re-introduce the single point of failure mirroring is meant to remove.

### Mandatory consequences

- The `scripts/` directory (and any `Makefile`, task runner, or build tool introduced later) IS the CI/CD apparatus. Whatever runs in "release CI" MUST be the same script a developer runs locally — no parallel implementation.
- A single local entry point — `scripts/ci.sh` (to be created in SP-2) — MUST invoke every quality gate appropriate to the changed surface (unit, integration, contract, e2e, fuzz, load, security, mutation, real-device). It MUST be runnable offline once toolchains and base images are present.
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

### What Agents Must NOT Do
- Never execute `systemctl suspend`, `systemctl hibernate`, `pm-suspend`, or equivalent
- Never modify power-management settings (sleep timers, lid-close behavior, screensaver activation)
- Never trigger a full-screen exclusive mode that might interfere with session keep-alive
- Never run commands that could exhaust system RAM and trigger an OOM kill of the desktop session
- Never execute `killall`, `pkill`, or mass-process-termination targeting session processes

### What Agents SHOULD Do
- Keep sessions alive: prefer short, bounded operations over indefinite waits
- For builds/tests longer than a few minutes, use background tasks where possible
- Monitor system load and avoid pushing the host to resource exhaustion
- If a container build or gradle build takes a long time, warn the user and use `--no-daemon` to prevent Gradle daemon from holding locks across suspends

### Docker / Podman Specific Notes
- Container builds and long-running containers do NOT normally cause host suspend
- However, filling the disk with layer caches or consuming all CPU for extended periods can trigger thermal throttling or watchdog timeouts on some systems
- Always clean up old images/containers after builds to avoid disk pressure
