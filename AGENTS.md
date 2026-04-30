# Lava — Agent Guide

> This file is intended for AI coding agents. It describes the project structure, build system, conventions, and things you need to know before modifying code.

## Project Overview

**Lava** is an unofficial Android client for [rutracker.org](https://rutracker.org). It consists of two main artifacts:

1. **Android App** (`:app`) — a modular Android application written in Kotlin, using Jetpack Compose for the UI.
2. **Proxy Server** (`:proxy`) — a headless Ktor server that scrapes rutracker.org and exposes a JSON REST API for the app.

The project is a fork of `andrikeev/Flow`, maintained under `milos85vasic/Lava`. All source code, comments, and documentation are in **English**.

- **App ID:** `digital.vasic.lava.client`
- **App Version:** `1.0.0` (`versionCode = 1000`)
- **Proxy Version:** `1.0.0`
- **License:** MIT (see `LICENSE`)

## Technology Stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin `2.1.0` |
| Build System | Gradle `8.9` (via wrapper) |
| Android Gradle Plugin | `8.6.1` |
| Android compileSdk | `35` |
| Android minSdk | `21` |
| Android targetSdk | `35` |
| JDK Toolchain | `17` |
| UI Framework | Jetpack Compose (BOM `2025.06.01`) — 100 % Compose, no XML layouts |
| State Management | Orbit MVI `7.1.0` |
| Dependency Injection (Android) | Dagger Hilt `2.54` |
| Dependency Injection (Proxy) | Koin `3.4.0` |
| Networking | Ktor client `2.3.1`, OkHttp, Coil `2.7.0` |
| HTML Parsing | Jsoup `1.15.3` |
| Database | Room `2.7.2` with KSP |
| Background Work | WorkManager `2.10.2` |
| Serialization | Kotlinx Serialization `1.6.3` |
| Crash Reporting | Firebase Crashlytics (via Firebase BOM `33.15.0`) |
| Debug Tools | LeakCanary `2.10`, Chucker `4.0.0` |
| Code Formatting | Spotless `6.22.0` with ktlint |

## Repository Layout

The repository is a **multi-module Gradle project**. There is **no root `build.gradle.kts`**; all build logic is centralized in `buildSrc` as custom convention plugins.

```
Lava/
├── app/                    # Android application module
├── proxy/                  # Ktor proxy server module
├── buildSrc/               # Custom Gradle convention plugins
├── core/                   # 17 core library module directories
│   ├── auth/api, auth/impl
│   ├── common, data, database, designsystem, dispatchers, domain, downloads, logger, models, navigation, notifications, preferences, testing, ui
│   └── network/api, network/impl
│   └── tracker/api, tracker/mirror, tracker/registry, tracker/rutracker, tracker/testing
│   └── work/api, work/impl
├── feature/                # 15 feature modules
│   ├── account, bookmarks, category, connection, favorites, forum, login, main, menu, rating, search, search_input, search_result, topic, visited
├── gradle/
│   └── libs.versions.toml  # Version catalog
├── build_and_push_docker_image.sh
├── Upstreams/              # Upstream repository push scripts
└── settings.gradle.kts
```

### Module responsibilities

- `:app` — Entry point. Contains `Application`, `MainActivity`, `TvActivity`, and the top-level navigation graph. Depends on every core and feature module.
- `:proxy` — Ktor/Netty server exposing REST endpoints. Built as a fat JAR and containerized with Docker.
- `core:*` — Shared libraries. Pure Kotlin modules (`models`, `common`, `auth/api`, `network/api`, `tracker:rutracker`, `tracker:api`, `work/api`) have **no Android dependency**.
- `feature:*` — Screen-level modules. Each feature typically contains a ViewModel (Orbit MVI), Compose screens, and a navigation contract.

## Build System & Convention Plugins

All modules apply one or more custom convention plugins defined in `buildSrc`. The plugins are registered in `buildSrc/build.gradle.kts` and applied by ID.

| Plugin ID | Applied To | What it does |
|-----------|------------|--------------|
| `lava.android.application` | `:app` | Android app plugin, Firebase/Crashlytics, static analysis, Compose compiler |
| `lava.android.library` | Most `core:*` and `feature:*` | Android library + Kotlin Android + Spotless |
| `lava.android.library.compose` | Compose-enabled modules | Adds Compose BOM dependencies and compiler settings |
| `lava.android.feature` | All `feature:*` | Applies library + Hilt, wires standard core dependencies, Orbit, enables `-Xcontext-receivers` |
| `lava.android.hilt` | Android modules needing DI | Dagger Hilt plugin + KSP compilers |
| `lava.kotlin.library` | Pure Kotlin modules | `java-library` + Kotlin JVM + Spotless |
| `lava.kotlin.serialization` | Modules needing JSON serialization | Kotlin serialization plugin + `kotlinx-serialization-json` |
| `lava.kotlin.ksp` | Modules using KSP (e.g. Room) | KSP Gradle plugin |
| `lava.ktor.application` | `:proxy` | Kotlin library + serialization + `application` plugin + Ktor plugin |

Shared build constants live in `buildSrc/src/main/kotlin/lava/conventions/`:
- `AndroidCommon.kt` — `compileSdk = 35`, `minSdk = 21`, `targetSdk = 35`
- `KotlinAndroid.kt` — Java / Kotlin target `VERSION_17`
- `AndroidCompose.kt` — Compose compiler plugin + experimental opt-ins
- `StaticAnalysisConventionPlugin.kt` — Spotless configuration

### Key build files

- `settings.gradle.kts` — Includes `:app`, `:proxy`, all `core:*` and `feature:*` modules.
- `gradle/libs.versions.toml` — Version catalog with libraries, plugins, and bundles (`coil`, `ktor`, `orbit`, `room`, `work`).
- `gradle.properties` — Standard Android properties (`android.useAndroidX=true`, `kotlin.code.style=official`, `android.nonFinalResIds=false`, etc.).

## Build Commands

Because there is no root build script, you invoke tasks via the Gradle wrapper as usual. Common commands:

```bash
# Build the Android app (debug)
./gradlew :app:assembleDebug

# Build the Android app (release)
./gradlew :app:assembleRelease

# Build the proxy fat JAR
./gradlew :proxy:buildFatJar

# Run Spotless formatting/checks
./gradlew spotlessApply
./gradlew spotlessCheck

# Run all tests (there is very little test coverage today)
./gradlew test

# Build all artifacts and copy to releases/
./build_and_release.sh
```

The `build_and_release.sh` script produces:
```
releases/
└── {version}/
    ├── android-debug/
    │   └── digital.vasic.lava.client-{version}-debug.apk
    ├── android-release/
    │   └── digital.vasic.lava.client-{version}-release.apk
    └── proxy/
        └── digital.vasic.lava.api-{version}.jar
```

### App build types

- **Debug** — `isMinifyEnabled = false`, signed with the custom debug keystore (`keystores/debug.keystore`), `applicationIdSuffix = ".dev"`.
- **Release** — `isRemoveUnusedCode = true`, `isRemoveUnusedResources = true`, `isOptimizeCode = true`, **but `isObfuscate = false`**. Uses ProGuard rules from `app/proguard-rules.pro` and is signed with the custom release keystore (`keystores/release.keystore`).

## Code Style & Static Analysis

- **Spotless + ktlint** is the only enforced code-quality tool. It is configured programmatically in `buildSrc/src/main/kotlin/lava/conventions/StaticAnalysisConventionPlugin.kt`.
- There is **no Detekt** and **no Checkstyle**.
- **`.editorconfig`** exists at the project root and configures ktlint rules (e.g. allowing PascalCase for `@Composable` functions).
- Spotless targets:
  - All `**/*.kt` files (excluding `build/`)
  - All `*.gradle.kts` files
- Run `./gradlew spotlessApply` before committing.
- Kotlin code style is set to `official` in `gradle.properties`.

## Architecture & Module Organization

### Clean architecture / layered modules

The project follows a **modular clean architecture** with strict dependency direction:

```
app
 └─> feature:*
     └─> core:domain
         └─> core:data
             └─> core:network:impl
             └─> core:database
         └─> core:auth:impl
         └─> core:work:impl
     └─> core:navigation, core:ui, core:designsystem
```

- **Pure Kotlin modules** (`core:models`, `core:common`, `core:auth:api`, `core:network:api`, `core:network:rutracker`, `core:work:api`) do not depend on the Android SDK.
- **API / Impl split** — Several core layers expose an `:api` module (interfaces + models) and an `:impl` module (Hilt-bound implementations). This keeps consumers decoupled from implementation details.

### MVI with Orbit

Every feature module uses **Orbit MVI**:

- `XxxViewModel` — `@HiltViewModel`, implements `ContainerHost<State, SideEffect>`.
- `XxxState` — `sealed interface` describing UI states (`Loading`, `Loaded`, `Error`, `Empty`).
- `XxxAction` — `sealed interface` for user intents.
- `XxxSideEffect` — `sealed interface` for one-time events (navigation, toasts, etc.).

Example: `feature/forum/src/main/kotlin/lava/forum/ForumViewModel.kt`

### UI & Navigation

- **100 % Jetpack Compose**. There are no XML layouts or Fragments in feature modules.
- **Custom design system** lives in `:core:designsystem` (`LavaTheme`, `AppBar`, custom text fields, etc.).
- **Custom navigation wrapper** in `:core:navigation` sits on top of Jetpack Navigation Compose.
  - Routes are built with a Kotlin DSL using **context receivers** (enabled in several modules).
  - Each feature exposes `addXxx()` and `openXxx()` extension functions.
  - Deep links for `rutracker.org/forum/viewtopic.php`, `viewforum.php`, and `tracker.php` are handled in `app/src/main/AndroidManifest.xml` and wired into the navigation graph.
- **TV support** — `TvActivity` extends `MainActivity` and changes `PlatformType` to `TV`. Leanback launcher intent is declared in the manifest. The app also declares `android.software.leanback` as not required and `android.hardware.touchscreen` as not required.

## Anti-Bluff Testing Pact (Constitutional Law)

This project adheres to **Anti-Bluff Testing**. A "bluff test" is one that passes while the corresponding real feature is broken for end users. Bluff tests create false confidence and are strictly forbidden.

### First Law — Tests Must Guarantee Real User-Visible Behavior
Every test MUST verify an outcome that matters to end users. A test that only asserts "function did not crash" or "mock was called" is a bluff test and must be rewritten.

### Second Law — No Mocking of Internal Business Logic
- **ViewModel tests MUST use real UseCase implementations** wired to realistic fakes, never mocked use cases.
- **UseCase tests MUST use real Repository implementations** (or fakes that enforce identical invariants to the real database / network layer).
- Mocking is permitted ONLY at the outermost system boundaries (Android `NsdManager`, actual HTTP sockets, hardware).
- If a UseCase contains a bug, a ViewModel Integration Challenge Test wired to the real UseCase MUST fail.

### Third Law — Fakes Must Be Behaviorally Equivalent
- A fake that is "simpler" than reality in a way that could hide a bug is a bluff fake.
- `TestEndpointsRepository` MUST reject duplicates (Room primary-key conflict) and seed defaults just like `EndpointsRepositoryImpl`.
- `TestLocalNetworkDiscoveryService` MUST simulate real NsdManager behaviors (e.g., `_lava._tcp.local.` service-type suffix).
- Every fake MUST document any behavioral simplifications that differ from production.

### Fourth Law — Integration Challenge Tests
- Every feature MUST include at least one **Integration Challenge Test** that exercises the real implementation stack end-to-end: ViewModel → UseCase → Repository → (Fake) Service.
- Challenge Tests use actual production classes at every layer; only external boundaries are faked.
- A passing Challenge Test MUST guarantee the feature works for a real user under the tested scenario.

### Fifth Law — Regression Immunity
- Every production bug fix MUST be accompanied by a test that would have failed before the fix.
- If such a test cannot be written, the architecture is untestable and must be refactored before the fix is accepted.
- Code coverage numbers are meaningless if the tests are bluffs; behavioral guarantees are the only valid metric.

### Sixth Law — Real User Verification (Anti-Pseudo-Test Rule)

A test that passes while the feature it covers is broken for end users is the most expensive kind of test in this codebase — it converts unknown breakage into believed safety. This has happened in this project before: tests and Integration Challenge Tests executed green while large parts of the product were unusable on a real device. That outcome is a constitutional failure, not a coverage failure, and it MUST NOT recur.

Every test added to this codebase from this point on MUST satisfy ALL of the following. Violation of any of them is a release blocker, irrespective of coverage metrics, CI status, reviewer sign-off, or schedule pressure.

1. **Same surfaces the user touches.** The test must traverse the production code path the user's action triggers, end to end, with no shortcut that bypasses real wiring. If the user's action is "open screen → tap button → see result", the test exercises the same screen, the same button handler, and the same result-rendering code — not a synthetic call into the ViewModel that skips the screen, and not a hand-rolled flow that skips the network/database boundary the real action crosses.

2. **Provably falsifiable on real defects.** Before merging, the author MUST run the test once with the underlying feature deliberately broken (throw inside the use case, return the wrong row from the repository, return the wrong status from the API) and confirm the test fails with a clear assertion message. The PR description MUST state which deliberate break was used and what failure the test produced. A test that cannot be made to fail by breaking the thing it claims to verify is a bluff test by definition.

3. **Primary assertion on user-visible state.** The chief failure signal of the test MUST be on something a real user could see or measure: rendered UI text, persisted database row, HTTP response body / status / header, file written to disk, packet on the wire. "Mock was invoked N times" is a permitted secondary assertion, never the primary one.

4. **Integration Challenge Tests are the load-bearing acceptance gate.** A green Challenge Test means a real user can complete the flow on a real device against real services — not "the wiring compiles". A feature for which a Challenge Test cannot be written is, by definition, not shippable. Refactor for testability or remove the feature.

5. **CI green is necessary, not sufficient.** "All tests pass" is not a release signal. Before any release tag is cut, a human (or a scripted black-box runner that drives the real UI / real HTTP API) MUST have used the feature on a real device or environment and observed the user-visible outcome described in the feature spec. Tag scripts (e.g. `scripts/tag.sh`) MUST NOT be run until that verification is documented.

6. **Inheritance.** This Sixth Law applies recursively to every submodule, every feature, and every new artifact added to the project (including the Go API service). Submodule constitutions MAY add stricter rules but MUST NOT relax this one.

#### Sixth Law extensions (lessons-learned addenda)

The clauses above are immutable. Below are addenda recorded after a real bug shipped green and we needed to prevent the *class* of bug, not just the specific instance. Authoritative copy lives in `CLAUDE.md`; this AGENTS.md mirrors the headers so an agent reading either file sees the same rule set.

##### 6.A — Real-binary contract tests (added 2026-04-29)

Forensic anchor: `docker-compose.yml` invoked `healthprobe --http3 …` while the binary only registered `-url`/`-insecure`/`-timeout`; the probe exited 1 every 10 seconds for 569 consecutive runs while the API itself served 200. Compose validation was happy, every functional test was green, the orchestrator labelled the container "unhealthy" — and there was no test asserting the probe could even start.

Rule: every script/compose invocation of a binary we own MUST have a contract test that recovers the binary's flag set from its actual `Usage` output and asserts the script's flag set is a strict subset, with a falsifiability rehearsal sub-test that re-introduces a known-buggy flag and confirms the checker rejects it. Canonical implementation: `lava-api-go/tests/contract/healthcheck_contract_test.go`.

##### 6.B — Container "Up" is not application-healthy (added 2026-04-29)

`docker ps` / `podman ps` `Up` only means PID 1 is alive; the application inside may be stuck or crash-looping internally (cf. 6.A). Tests asserting container state alone are bluffs by clauses 1 and 3. Use the same probe the orchestrator uses, or an end-to-end functional request at the user-visible surface.

##### 6.C — Mirror-state mismatch checks before tagging (added 2026-04-29)

"All four mirrors push succeeded" is one assertion; "all four mirrors converge to the same SHA at HEAD" is stronger. `scripts/tag.sh` MUST verify post-push tip-SHA convergence across github/gitflic/gitlab/gitverse before reporting success, and SHOULD record per-mirror SHAs in the evidence file.

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

## Testing Strategy

> ⚠️ **Test coverage is minimal.** The repository currently contains almost no tests.

- **Only one unit-test file exists:** `core/preferences/src/test/kotlin/lava/securestorage/EndpointConverterTest.kt` (JUnit 4).
- **No tests** exist in `:app`, any `feature:*` module, or most `core:*` modules.
- A shared test-utilities module — `:core:testing` — provides:
  - `MainDispatcherRule` (JUnit 4 `TestWatcher`)
  - Fake repositories and services (`TestBookmarksRepository`, `TestAuthService`, etc.)
  - `TestDispatchers`
  - Test dependencies: JUnit 4, `kotlinx-coroutines-test`, Mockk Android, Compose UI test, AndroidX test runners
- Orbit test library (`orbit-test`) is already wired as a `testImplementation` in every feature module, but is unused.
- `testInstrumentationRunner` is set to `androidx.test.runner.AndroidJUnitRunner` for all feature modules.

If you add tests, prefer **JUnit 4** (to match the existing `MainDispatcherRule`) and place them in `src/test/kotlin/` for unit tests or `src/androidTest/kotlin/` for instrumentation tests. **Always write at least one Integration Challenge Test per feature using real UseCase and Repository implementations.**

## Deployment

### Android App

There is **no CI/CD pipeline** in the repository (no `.github/workflows/`, no `.gitlab-ci.yml`). The app is distributed manually via Google Play, GitHub Releases, and RuStore (per `README.md`).

### Proxy Server

The proxy is built as a Ktor fat JAR and deployed as a Docker image.

1. **Build the fat JAR:**
   ```bash
   ./gradlew :proxy:buildFatJar
   ```
   Output: `proxy/build/libs/app.jar`

2. **Build & push the Docker image:**
   ```bash
   ./build_and_push_docker_image.sh
   ```

   The script does the following:
   ```bash
   docker build -t lava-app-proxy ./proxy
   docker tag lava-app-proxy registry.digitalocean.com/lava-app/lava-app-proxy
   docker push registry.digitalocean.com/lava-app/lava-app-proxy
   ```

3. **Dockerfile** (`proxy/Dockerfile`):
   - Base image: `openjdk:17.0.1-jdk-slim` (`linux/amd64`)
   - Exposes port `8080`
   - Entrypoint: `java -jar /app/app.jar`

There are no Docker Compose files, Kubernetes manifests, or other orchestration configs in the repo.

## Security Considerations

- **Proxy auth** — The proxy expects an `Auth-Token` header (`ApplicationRequest.authToken`) but does not implement OAuth or JWT; it forwards rutracker session state.
- **Encrypted preferences** — `core:preferences` uses `androidx.security:security-crypto-ktx` (`1.1.0-alpha03`) to store credentials and settings.
- **Cleartext traffic** — `android:usesCleartextTraffic="true"` is enabled in the app manifest. Be cautious when changing this.
- **Signing configuration** — Both debug and release builds use dedicated keystores located under `keystores/`. Keystore paths and passwords are loaded from a `.env` file via `KEYSTORE_ROOT_DIR` and `KEYSTORE_PASSWORD`. See `.env.example` for the required variables. Never commit the `.env` file or the `keystores/` directory.
- **ProGuard** — `isObfuscate = false` in release. The ProGuard rules in `app/proguard-rules.pro` keep network DTOs (`lava.network.dto.**`) and Tink classes.
- **Deep links** — The app handles `rutracker.org` deep links. Any Intent processing should validate URLs to avoid injection.

## Useful Notes for Agents

- **No root `build.gradle.kts`** — Do not create one. Use or extend the convention plugins in `buildSrc` instead.
- **Adding a new module?** Register it in `settings.gradle.kts` and apply the appropriate convention plugin (`lava.android.feature`, `lava.kotlin.library`, etc.).
- **Adding a dependency?** Prefer adding it to `gradle/libs.versions.toml` first, then referencing it via `libs.findLibrary(...)` or `libs.findBundle(...)` in the convention plugin or module `build.gradle.kts`.
- **Compose compiler** is managed via the Kotlin Compose compiler plugin (BOM `2025.06.01`). Do not add the old `composeOptions` block.
- **Context receivers** are enabled in several modules (`-Xcontext-receivers`). If you see `context(NavigationGraphBuilder)` DSL calls, that is why.
- **Firebase/Google Services** — The app plugin expects `google-services.json` in the `app/` directory. It is gitignored (`app/.gitignore`).
- **Avoid XML** — The project is fully Compose. Do not add XML layouts or Fragment-based screens.
- **Orbit MVI** — When modifying a feature, keep the `ViewModel` / `State` / `Action` / `SideEffect` pattern consistent with neighboring features.

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

The following invocations are **categorically forbidden** in any committed script, any subagent's planned action, or any agent's tool call:

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

Additional rules:
- Never modify power-management settings (sleep timers, lid-close behavior, screensaver activation)
- Never trigger a full-screen exclusive mode that might interfere with session keep-alive
- Never run commands that could exhaust system RAM and trigger an OOM kill of the desktop session
- Never execute `killall`, `pkill`, or mass-process-termination targeting session processes

### Forensic record: incident 2026-04-28 18:37 (host poweroff)

See `docs/INCIDENT_2026-04-28-HOST-POWEROFF.md` for the full investigation. A user-space-initiated graceful poweroff via GNOME Shell occurred at 18:37:14 during SP-2 implementation. **Not a suspend, not a hibernate, not an OOM kill, not a kernel panic.** Root cause was external to this codebase (operator GNOME power-button click, hardware power-button, or out-of-scope scheduled task). The commit-and-push-per-phase discipline preserved every completed phase. After-incident recovery procedure (state verify → mirror parity → orphan container cleanup → resume from last commit) is documented in the incident file.

### What Agents SHOULD Do
- Keep sessions alive: prefer short, bounded operations over indefinite waits
- For builds/tests longer than a few minutes, use background tasks where possible
- Monitor system load and avoid pushing the host to resource exhaustion
- If a container build or gradle build takes a long time, warn the user and use `--no-daemon` to prevent Gradle daemon from holding locks across suspends

### Docker / Podman Specific Notes
- Container builds and long-running containers do NOT normally cause host suspend
- However, filling the disk with layer caches or consuming all CPU for extended periods can trigger thermal throttling or watchdog timeouts on some systems
- Always clean up old images/containers after builds to avoid disk pressure
