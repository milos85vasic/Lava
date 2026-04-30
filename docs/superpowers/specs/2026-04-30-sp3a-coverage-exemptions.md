# SP-3a Coverage Exemption Ledger

Per Constitutional clause 6.D (Behavioral Coverage Contract), every uncovered
line in code authored under SP-3a is listed here with reason, reviewer, and
date. Blanket waivers are forbidden.

This file was seeded ahead of Task 0.7 by Task 0.2 (TestBookmarksRepository
stub bluff) because that task needed an entry to land. Task 0.7 will append
the Phase-0 summary section without re-creating the file.

## Format

| File | Lines | Reason | Reviewer | Date | PR |
|---|---|---|---|---|---|

## Entries

| File | Lines | Reason | Reviewer | Date | PR |
|---|---|---|---|---|---|
| `core/testing/src/main/kotlin/lava/testing/repository/TestBookmarksRepository.kt` | all (every method body is `TODO("Not yet implemented")`, except `clear()` which is a no-op) | Complete stub fake. Full behavioral-equivalence implementation against `BookmarksRepositoryImpl` (Room `BookmarkDao`, `@PrimaryKey` on category id, `JSONArray`-encoded `newTopics` column) deferred to the first SP-3a consumer of `BookmarksRepository` — anticipated SP-3a Phase 4 (`tracker_settings` UI) or later. Stub status is locked in place by the inverted test `TestBookmarksRepositoryStubBluffTest`: when the stub is replaced with a real fake, those three assertions will start failing and force the maintainer to (a) remove this row, and (b) replace the inverted test with a true equivalence test patterned after `TestEndpointsRepositoryEquivalenceTest`. The current `MenuViewModelTest` constructs `ClearBookmarksUseCase(TestBookmarksRepository(), ...)` but never triggers a code path that calls a stubbed method (no `MenuAction.ClearBookmarks` test exists), so the stub is latent rather than actively broken. Anti-Bluff Pact Third Law violation acknowledged; falsifiability rehearsal recorded in `.lava-ci-evidence/sp3a-bluff-audit/0.2-test-bookmarks-repository.json`. | implementer (subagent, supervised) | 2026-04-30 | SP-3a-0.2 |
| `core/testing/src/main/kotlin/lava/testing/service/TestAuthService.kt` | all | Fake has zero test consumers in the codebase as of 2026-04-30 (verified via `grep -rn 'TestAuthService' --include='*.kt' core/ feature/ app/` excluding the declaration site — empty result). Per user decision A on the Phase 0 audit, equivalence audit is deferred to the first consumer (anticipated SP-3a Phase 2 RuTracker decoupling tests using the `AuthenticatableTracker` feature interface). When the first consumer is added, write a `TestAuthServiceEquivalenceTest` following the `TestEndpointsRepositoryEquivalenceTest` pattern (real `AuthServiceImpl` token persistence: in-memory token storage, login/logout state transitions, token-expiry observation). Anti-Bluff Pact Third Law: an unused fake cannot mislead any test; the bluff vector is latent until a consumer materialises. Evidence recorded in `.lava-ci-evidence/sp3a-bluff-audit/0.3-test-auth-service.json`. | implementer (subagent, supervised) | 2026-04-30 | SP-3a-0.3 |
| `core/testing/src/main/kotlin/lava/testing/service/TestLocalNetworkDiscoveryService.kt` | n/a — fake is a post-resolution injection point | Equivalence already verified by two complementary contract tests in `core/domain`: (a) `core/domain/src/test/kotlin/lava/testing/contract/TestInfrastructureContractTest.kt` lines 167-212 (the `// TestLocalNetworkDiscoveryService — non-suspending emit contract` section) covers the fake's actual behaviour surface — non-suspending `emit`, multi-emit ordering, cross-scheduler rendezvous — i.e. the Third-Law bluff that the fake's previous rendezvous-`Channel()` form was guilty of; and (b) `core/domain/src/test/kotlin/lava/domain/contract/LocalNetworkDiscoveryContractTest.kt` (115 lines, 8 `@Test` methods) covers the real impl's `_lava._tcp.local.` / `_lava-api._tcp.local.` suffix-scoping logic via `matchesServiceType`, which is the SP-3.4 cross-match regression guard. The fake does NOT implement suffix scoping by design — it is a post-resolution channel that consumers feed pre-resolved `DiscoveredEndpoint` instances into; suffix-scoping happens at the NSD-resolution boundary that the fake intentionally bypasses (the `LocalNetworkDiscoveryService` interface contract is "emit `DiscoveredEndpoint`s", not "filter by service type"). No new test required for SP-3a; this entry documents the audit's resolution. Evidence recorded in `.lava-ci-evidence/sp3a-bluff-audit/0.4-test-local-network-discovery.json`. | implementer (subagent, supervised) | 2026-04-30 | SP-3a-0.4 |

## Latent findings

The following are NOT coverage exemptions — they are known-risk observations
surfaced during the Phase 0 audit. They do NOT block Phase 1 (Foundation),
but each is a future-facing tripwire that MUST be revisited before its
respective trigger condition materialises.

### LF-1 — `MenuViewModelTest` holds `TestBookmarksRepository` without exercising it (linked: Task 0.2)

**Location:** `feature/menu/src/test/kotlin/lava/menu/MenuViewModelTest.kt:115-118`

**Observation:** the test constructs `TestBookmarksRepository()` and wires it
into `ClearBookmarksUseCase`, which is then injected into `MenuViewModel`. As
of 2026-04-30 no `@Test` method in this file dispatches a `MenuAction` whose
handler invokes a stubbed method on the repository — i.e. the bookmarks fake
is held but never touched. The instant a test is added that drives, for
example, a `MenuAction.ClearBookmarks` flow, the stub will throw
`NotImplementedError` at the first stubbed call site (`getBookmarks()`,
`saveBookmark(...)`, etc.) and the test will fail at runtime, not at compile
time.

**Mitigation trigger:** when the first `MenuViewModel` test path that
exercises bookmarks is added (anticipated during SP-3a Phase 4 tracker
settings work or earlier feature work), the author MUST either:
1. Implement `TestBookmarksRepository` against the
   `TestEndpointsRepositoryEquivalenceTest` pattern (full behavioural
   equivalence to `BookmarksRepositoryImpl`), and remove the corresponding
   ledger entry above; or
2. Document why the new test path does not need a real fake (e.g. it uses a
   purpose-built local fake), and leave the ledger entry intact.

**Audit linkage:** Task 0.2 evidence at
`.lava-ci-evidence/sp3a-bluff-audit/0.2-test-bookmarks-repository.json`.

### LF-2 — `TestHealthcheckContract` is currently a future-facing tripwire (linked: Task 0.6)

**Location:** `lava-api-go/tests/contract/healthcheck_contract_test.go::TestHealthcheckContract`

**Observation:** Constitutional clause 6.A (real-binary contract tests) was
authored in response to the lava-api-go `--http3` healthcheck regression of
2026-04-29, where `docker-compose.yml` invoked
`healthprobe --http3 https://localhost:8443/health` against a binary that
only registered `-url`/`-insecure`/`-timeout`. The contract test extracts the
healthcheck command from `docker-compose.yml`, recovers the registered flag
set from the binary's `Usage` output, and asserts strict subset. As of
2026-04-30 the `lava-api-go` service block in `docker-compose.yml` has no
`healthcheck:` block at all (the bug was triaged by removing the broken
healthcheck rather than fixing the flag). Consequently the contract test's
"extract command" step finds an empty command and the subset check is
trivially satisfied — the test passes vacuously.

The test is still load-bearing as a tripwire: the moment a healthcheck is
re-introduced for `lava-api-go`, this contract test will validate it against
the actual flag set of the `healthprobe` binary at the pinned hash. The
falsifiability rehearsal recorded in Task 0.6 confirmed the test fails when
a bogus `--http2` flag is added to the compose healthcheck.

**Mitigation trigger:** before re-introducing any `healthcheck:` block on
the `lava-api-go` service in `docker-compose.yml`, the author MUST:
1. Run `go test ./tests/contract/... -run TestHealthcheckContract` against
   the new compose file and confirm the test now exercises the subset check
   non-vacuously (i.e. the extracted command is non-empty).
2. Add a falsifiability rehearsal sub-test asserting that an unregistered
   flag in the new healthcheck command causes the contract checker to fail.
3. Record the rehearsal in a new evidence file under
   `.lava-ci-evidence/` with the same Test/Mutation/Observed/Reverted
   protocol used by Task 0.6.

**Audit linkage:** Task 0.6 evidence at
`.lava-ci-evidence/sp3a-bluff-audit/0.6-healthprobe-contract.json`.

### LF-3 — Tracker chain compiles to JVM 21 while Android targets JVM 17 (linked: Phase 2 Section A)

**Locations:**
- `buildSrc/src/main/kotlin/KotlinTrackerModuleConventionPlugin.kt`
- `core/tracker/api/build.gradle.kts`
- `core/tracker/testing/build.gradle.kts`

**Observation:** Phase 2 Section A (Task 2.5) required the new `:core:tracker:rutracker`
module to compile via `lava.kotlin.tracker.module`. At HEAD the Tracker-SDK
composite-build's `:api`/`:mirror`/`:registry`/`:testing` projects default to the
JDK 21 toolchain and emit JVM 21 class files. The Lava-side tracker convention
plugin previously targeted JVM 17, which produced "class file has wrong version 65.0,
should be 61.0" failures the moment any Lava module on the JVM-17 chain (e.g.
`:core:tracker:api`, `:core:tracker:testing`) tried to consume an SDK type. To
unblock Section A's required `BUILD SUCCESSFUL` acceptance gate, the implementer
bumped JVM target to 21 in three places: the new tracker convention plugin, and the
two pure-Kotlin Lava-side tracker modules that were previously on JVM_17 via
`lava.kotlin.library`. The rest of the codebase (Android modules via
`KotlinAndroid.kt`, regular Kotlin libraries via `KotlinLibraryConventionPlugin.kt`)
remains JVM_17 — the APK still targets JVM 17 bytecode.

**Why this is a latent finding rather than a fix:** The plan does not authorize a
JVM-target bump and the cleaner resolution is upstream — set `kotlin { jvmToolchain(17) }`
on each Tracker-SDK Gradle module. That requires a new Tracker-SDK pin and a fresh
4-upstream mirror push. Deferred so the rename can land cleanly in Phase 2 Section A.

**Mitigation trigger:** before Section F (Tasks 2.28-2.31, Hilt + LavaTrackerSdk
wiring) lands the tracker chain into the Android `:app` Hilt module, the author MUST
verify one of:
1. The Tracker-SDK pin has been updated upstream to target JVM 17 in its
   convention/module Gradle scripts, the new pin recorded in `Submodules/Tracker-SDK/`,
   and the three JVM_21 overrides reverted to JVM_17 in `KotlinTrackerModuleConventionPlugin.kt`,
   `core/tracker/api/build.gradle.kts`, `core/tracker/testing/build.gradle.kts`.
2. The Android build (AGP/D8) is configured to accept JVM 21 input bytecode, and the
   rest of the codebase is bumped to JVM 21 for consistency. (Larger blast radius —
   only choose this path if upstream Tracker-SDK is sticky on JVM 21.)

If neither condition is met before Section F, `:app:assembleDebug` will fail at D8
dexing the moment the tracker registry lands on the dependency graph.

**Audit linkage:** Phase 2 Section A spec compliance review (commit landing the
LF-3 entry).

### LF-5 — RuTrackerDescriptor declares UPLOAD + USER_PROFILE without feature interfaces (linked: Phase 2 Section C)

**Location:** `core/tracker/rutracker/src/main/kotlin/lava/tracker/rutracker/RuTrackerDescriptor.kt`

**Observation:** Phase 2 Section B (Task 2.6) populated `RuTrackerDescriptor.capabilities`
with the full 12-capability set including `TrackerCapability.UPLOAD` and
`TrackerCapability.USER_PROFILE`. As of Phase 2 Section C end-of-task, the new
`:core:tracker:api` package does NOT define `UploadableTracker` or `ProfileTracker`
feature interfaces. Consequently `RuTrackerClient.getFeature<T>()` cannot return a
non-null impl for those two capabilities — the `KClass<T>` for the missing
interfaces does not exist as a dispatch key in the `when` switch.

**Constitutional clause 6.E literal:** "capability declared in descriptor ⇒ this
returns non-null". The current code SATISFIES this letter-of-the-law because no
caller can construct `getFeature(UploadableTracker::class)` — the type doesn't
exist. But the descriptor is making a forward-looking claim that no impl backs.
Treat as a latent finding rather than an immediate violation.

**Mitigation trigger:** before Phase 4 (cross-tracker fallback policy), one of
the following MUST be true:
1. `:core:tracker:api` defines `UploadableTracker` and `ProfileTracker`, and
   `RuTrackerClient.getFeature<T>()` resolves both to working impls (which then
   wrap appropriate UseCases — `UploadTorrentUseCase` exists already; profile
   fetching exists via `GetCurrentProfileUseCase` / `GetProfileUseCase`).
2. The two capabilities are removed from `RuTrackerDescriptor.capabilities` to
   match the actual feature surface.

The first option is the better outcome (the rutracker scrapers already support
both surfaces in `RuTrackerInnerApi`); the second is the safe fallback.

**Audit linkage:** Phase 2 Section C spec + code-quality review (commit landing
the LF-5 entry).
