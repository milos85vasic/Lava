# Lava — Agent Guide

> This file is intended for AI coding agents. It describes the project structure, build system, conventions, and things you need to know before modifying code.

## Project Overview

**Lava** is an unofficial Android client for [rutracker.org](https://rutracker.org). It consists of two main artifacts:

1. **Android App** (`:app`) — a modular Android application written in Kotlin, using Jetpack Compose for the UI.
2. **Proxy Server** (`:proxy`) — a headless Ktor server that scrapes rutracker.org and exposes a JSON REST API for the app.

The project is a fork of `andrikeev/Flow`, maintained under `milos85vasic/Lava`. All source code, comments, and documentation are in **English**.

- **App ID:** `me.rutrackersearch.app`
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
│   └── network/api, network/impl, network/rutracker
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
- `core:*` — Shared libraries. Pure Kotlin modules (`models`, `common`, `auth/api`, `network/api`, `network:rutracker`, `work/api`) have **no Android dependency**.
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

If you add tests, prefer **JUnit 4** (to match the existing `MainDispatcherRule`) and place them in `src/test/kotlin/` for unit tests or `src/androidTest/kotlin/` for instrumentation tests.

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
