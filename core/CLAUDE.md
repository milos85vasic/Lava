# Core Modules — Agent Guide

> This constitution applies to all modules under `core/`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

> Inherits Laws 1–7 from the root Anti-Bluff Testing Pact in `/CLAUDE.md`. The Sixth Law (Real User Verification) and the Seventh Law (Anti-Bluff Enforcement, added 2026-04-30) are binding on every core module — submodule rules below are additive, never relaxing. The Seventh Law's `Bluff-Audit:` commit-message stamp, real-stack verification gate, forbidden test patterns, recurring bluff hunt, and inheritance clause all apply here verbatim. Pre-push hooks reject core-module commits that violate any clause.

Every core module MUST obey the root Anti-Bluff Testing Pact. In addition:

### UseCase Tests
- Every public UseCase MUST have unit tests that exercise the **real implementation** (`*Impl`) wired to realistic fakes.
- UseCase tests MUST verify **repository side effects** (data actually persisted) and **cross-module interactions**, not just return values.
- If a UseCase branches on repository state, both branches MUST be tested with the real repository fake.

### Repository Fakes
- `Test*Repository` fakes live in `:core:testing`. They MUST be kept in sync with real repository behavior.
- When a real repository adds a constraint (e.g., Room `@PrimaryKey`, `UNIQUE` index, `FOREIGN KEY` cascade), the matching fake MUST be updated in the same commit.
- A fake that diverges from reality is a bluff fake and is forbidden.

### Service Fakes
- `Test*Service` fakes MUST simulate real-world protocol behaviors:
  - Network timeouts
  - mDNS service-type suffixes (e.g., `.local.`)
  - Partial / malformed responses
- If the real service has a behavior that affects parsing logic, the fake MUST expose it.

### Integration Challenge Tests for Core
- For every new UseCase, write at least one test that wires it to real fakes and verifies end-to-end behavior.
- Example: `DiscoverLocalEndpointsUseCaseImpl` tested with `TestLocalNetworkDiscoveryService` + `TestEndpointsRepository` + `TestSettingsRepository` — verifying that discovery → add → set-active actually mutates repository state.

### Tracker plugin modules (`:core:tracker:*`)
Per-tracker plugin modules under `core/tracker/` (e.g. `:core:tracker:rutracker`, `:core:tracker:rutor`) all follow the same shape: pure-Kotlin, JVM-only, applying the `lava.kotlin.tracker.module` convention plugin which pre-wires `:core:tracker:api`, `lava.sdk:api`, `lava.sdk:mirror`, Jsoup, OkHttp, kotlinx-coroutines, kotlinx-serialization, JUnit4, mockk, kotlinx-coroutines-test, `:core:tracker:testing`, and `lava.sdk:testing`. Each module exposes one `TrackerDescriptor` object, one `TrackerClient` implementation that wires N feature implementations (Searchable / Browsable / Topic / Comments / Favorites / Authenticatable / Downloadable, only those the descriptor declares as capabilities — clause 6.E Capability Honesty), one `TrackerClientFactory` registered into the SDK registry, and HTML fixtures under `src/test/resources/fixtures/<trackerId>/<scope>/`. New tracker modules MUST follow this layout; do not duplicate Hilt/Android wiring, do not add an `android.namespace` line. Hilt processing for `@Inject constructor` annotations happens transitively via `:core:tracker:client` once the factory is registered there (Section J in the SP-3a plan).

### Sixth Law — Real User Verification (Anti-Pseudo-Test Rule)

A test that passes while the feature it covers is broken for end users is the most expensive kind of test in this codebase — it converts unknown breakage into believed safety. This has happened in this project before: tests and Integration Challenge Tests executed green while large parts of the product were unusable on a real device. That outcome is a constitutional failure, not a coverage failure, and it MUST NOT recur.

Every test added to this codebase from this point on MUST satisfy ALL of the following. Violation of any of them is a release blocker, irrespective of coverage metrics, CI status, reviewer sign-off, or schedule pressure.

1. **Same surfaces the user touches.** The test must traverse the production code path the user's action triggers, end to end, with no shortcut that bypasses real wiring. If the user's action is "open screen → tap button → see result", the test exercises the same screen, the same button handler, and the same result-rendering code — not a synthetic call into the ViewModel that skips the screen, and not a hand-rolled flow that skips the network/database boundary the real action crosses.

2. **Provably falsifiable on real defects.** Before merging, the author MUST run the test once with the underlying feature deliberately broken (throw inside the use case, return the wrong row from the repository, return the wrong status from the API) and confirm the test fails with a clear assertion message. The PR description MUST state which deliberate break was used and what failure the test produced. A test that cannot be made to fail by breaking the thing it claims to verify is a bluff test by definition.

3. **Primary assertion on user-visible state.** The chief failure signal of the test MUST be on something a real user could see or measure: rendered UI text, persisted database row, HTTP response body / status / header, file written to disk, packet on the wire. "Mock was invoked N times" is a permitted secondary assertion, never the primary one.

4. **Integration Challenge Tests are the load-bearing acceptance gate.** A green Challenge Test means a real user can complete the flow on a real device against real services — not "the wiring compiles". A feature for which a Challenge Test cannot be written is, by definition, not shippable. Refactor for testability or remove the feature.

5. **CI green is necessary, not sufficient.** "All tests pass" is not a release signal. Before any release tag is cut, a human (or a scripted black-box runner that drives the real UI / real HTTP API) MUST have used the feature on a real device or environment and observed the user-visible outcome described in the feature spec. Tag scripts (e.g. `scripts/tag.sh`) MUST NOT be run until that verification is documented.

6. **Inheritance.** This Sixth Law applies recursively to every submodule, every feature, and every new artifact added to the project (including the Go API service). Submodule constitutions MAY add stricter rules but MUST NOT relax this one.

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
