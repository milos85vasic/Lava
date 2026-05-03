# Quickstart: Multi-Provider Extension

**Feature**: Multi-Provider Extension  
**Date**: 2026-05-02

## Prerequisites

- Android Studio Ladybug+ with Kotlin 2.1.0 support
- Go 1.25.0
- PostgreSQL 15+ (for Go API)
- Docker or Podman (for local PostgreSQL)
- Android device or emulator (API 21+) for Challenge Tests
- Access to `docs/refactoring/multi_provieders/` research materials

## 1. Environment Setup

### Android

```bash
# Verify Android toolchain
./gradlew --version

# Verify Spotless (required before any commit)
./gradlew spotlessCheck

# Build debug APK to verify baseline
./gradlew :app:assembleDebug
```

### Go API

```bash
cd lava-api-go

# Verify Go version
go version

# Install dependencies
go mod download

# Run existing tests
make test

# Verify CI gate
make ci
```

## 2. Running the Baseline Test Suite

Before making any changes, confirm the baseline is green:

```bash
# Android
./gradlew test

# Go API
cd lava-api-go && make test

# Proxy (legacy, verify still builds)
./gradlew :proxy:buildFatJar
```

If any baseline test fails, fix it before proceeding (constitutional Fifth Law — regression immunity).

## 3. Adding a New Provider (Step-by-Step)

This project adds 4 new providers. Use this recipe for each, or for any future provider.

### 3.1 Android Tracker Module

```bash
# Step 1: Create module structure
mkdir -p core/tracker/<trackerId>/src/{main,test}/kotlin/lava/tracker/<trackerId>
mkdir -p core/tracker/<trackerId>/src/test/resources/fixtures/<trackerId>/{search,topic,forum,login}
```

Create `core/tracker/<trackerId>/build.gradle.kts`:

```kotlin
plugins {
    id("lava.kotlin.tracker.module")
}
```

Add to `settings.gradle.kts`:

```kotlin
include(":core:tracker:<trackerId>")
```

Create files (minimum):
- `<TrackerId>Descriptor.kt` — capabilities, auth type, encoding, mirrors
- `<TrackerId>Client.kt` — `TrackerClient` implementation
- `<TrackerId>ClientFactory.kt` — Hilt registration
- `<TrackerId>Search.kt`, `<TrackerId>Browse.kt`, `<TrackerId>Topic.kt`, `<TrackerId>Download.kt`
- Parsers: `*Parser.kt` files for each HTML/JSON scope
- Tests: `*Test.kt` with HTML/JSON fixtures

Register in `core/tracker/client/src/main/kotlin/lava/tracker/client/registry/TrackerClientModule.kt`:

```kotlin
@Provides
@IntoSet
fun provide<TrackerId>Factory(impl: <TrackerId>Client): TrackerClientFactory = ...
```

Add default mirrors to `core/tracker/client/src/main/assets/mirrors.json`.

### 3.2 Go API Provider Package

```bash
mkdir -p lava-api-go/internal/<providerId>
```

Create files (minimum):
- `client.go` — HTTP client with circuit breaker and charset handling
- `search.go`, `browse.go`, `topic.go`, `download.go` — feature implementations
- `login.go` — auth flow if applicable
- Register in `cmd/lava-api-go/main.go`:

```go
registry.Register("<providerId>", <providerId>.NewClient(cfg))
```

### 3.3 Testing Requirements (Anti-Bluff Compliance)

Every new provider MUST have:

1. **Parser tests** (≥5 fixtures per scope, date-stamped filenames)
2. **Integration tests** with MockWebServer
3. **Capability honesty test** — enumerate descriptor capabilities, assert `getFeature()` non-null
4. **Bluff-Audit stamp** in commit message with deliberate mutation rehearsal
5. **Challenge Test** in `app/src/androidTest/kotlin/lava/app/challenges/`

Example Bluff-Audit stamp:

```
Bluff-Audit:
  Test:      lava.tracker.nnmclub.feature.NNMClubSearchTest
  Mutation:  Renamed select("td.s") to select("td.q") in SearchParser.kt
  Observed:  expected: <non-empty list> but was: <[]>
  Reverted:  yes
```

## 4. Working with the Credentials Module

### 4.1 Adding a New Credential Type

If a future provider requires a credential type not in `{USERNAME_PASSWORD, TOKEN, API_KEY}`:

1. Add value to `CredentialType` enum in `core/credentials/api/`
2. Add encrypted storage field in `CredentialEntity`
3. Update UI in `feature/credentials/` to show conditional fields
4. Update auth middleware in Go API to parse new type
5. Write regression test before merge (Fifth Law)

### 4.2 Encryption Key Rotation

If Android Keystore key rotation is needed:

1. Implement re-encryption worker in `core/credentials/impl/`
2. Run on app upgrade
3. Verify backup compatibility
4. Document in migration notes

## 5. Running Challenge Tests

Challenge Tests require a real Android device or emulator:

```bash
# Run all Challenge Tests
./gradlew :app:connectedDebugAndroidTest

# Run specific Challenge Test
./gradlew :app:connectedDebugAndroidTest \
  --tests "lava.app.challenges.C2_UnifiedSearchTest"

# Compile without device (pre-push gate)
./gradlew :app:compileDebugAndroidTestKotlin
```

## 6. Pre-Push Checklist

Before every push, run:

```bash
# 1. Code style
./gradlew spotlessCheck

# 2. Unit tests
./gradlew test

# 3. Verify no forbidden files
git ls-files | grep -E '\.(github|gitlab-ci|circleci|azure-pipelines|bitbucket-pipelines|Jenkinsfile)'
# Expected: empty output

# 4. Verify Bluff-Audit stamps on test commits
git log --format=%B -n 1 | grep -A 4 "Bluff-Audit:"

# 5. Go API tests
cd lava-api-go && make ci
```

## 7. Common Issues

### Issue: `TrackerClient` not discovered at runtime

**Cause**: Missing `@IntoSet` binding in registry module.  
**Fix**: Verify `TrackerClientFactory` is provided with `@Provides @IntoSet`.

### Issue: Parser tests fail after provider website updates

**Cause**: Fixtures older than 60 days.  
**Fix**: Run `scripts/check-fixture-freshness.sh`, capture new fixtures, update tests.

### Issue: Deduplication false positives

**Cause**: Weak matching key (title-only instead of info-hash).  
**Fix**: Ensure torrents use info-hash as primary key. HTTP content uses identifier/ISBN.

### Issue: Credential encryption fails on older device

**Cause**: Android Keystore unavailable on API < 23.  
**Fix**: Verify `EncryptedSharedPreferences` fallback is configured.

### Issue: Go API health probe exits 1

**Cause**: Flag mismatch between compose file and binary (6.A violation).  
**Fix**: Run contract test `healthcheck_contract_test.go`, verify flag subset.

## 8. References

- `docs/sdk-developer-guide.md` — Full recipe for adding tracker modules
- `core/CLAUDE.md` — Core module Anti-Bluff rules
- `feature/CLAUDE.md` — Feature module Anti-Bluff rules
- `.specify/memory/constitution.md` — Project constitution
- `docs/refactoring/multi_provieders/` — Research materials with provider details
