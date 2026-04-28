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
