# Core Modules — Agent Guide

## INHERITED FROM constitution/CLAUDE.md

All rules in `constitution/CLAUDE.md` (and the `constitution/Constitution.md` it references) apply unconditionally. This file's rules below extend them — they MUST NOT weaken any inherited rule. See parent root `CLAUDE.md` §6.AD for the Lava-specific incorporation context (29th §6.L cycle, 2026-05-14) and §6.AD-debt for the implementation-gap inventory. Use `constitution/find_constitution.sh` from the parent project root to resolve the absolute path of the submodule from any nested location.

> This constitution applies to all modules under `core/`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

> Inherits Laws 1–7 from the root Anti-Bluff Testing Pact in `/CLAUDE.md`. The Sixth Law (Real User Verification) and the Seventh Law (Anti-Bluff Enforcement, added 2026-04-30) are binding on every core module — submodule rules below are additive, never relaxing. The Seventh Law's `Bluff-Audit:` commit-message stamp, real-stack verification gate, forbidden test patterns, recurring bluff hunt, and inheritance clause all apply here verbatim. Pre-push hooks reject core-module commits that violate any clause.

### Anti-Bluff Functional Reality Mandate (Constitutional clause 6.J)

Every test, every Challenge Test, and every CI gate added to or maintained in core MUST confirm the feature it claims to cover actually works for an end user, end-to-end, on the gating matrix (clause 6.I). CI green is necessary, NEVER sufficient. Any agent or contributor may invoke clause 6.J to remove a test demonstrably bluff (passes against deliberately-broken production code). `@Ignore` without an open issue is a 6.J violation by construction. Tests must guarantee the product works — anything else is theatre.

### Anti-Bluff Functional Reality Mandate, Operator's Standing Order (Constitutional clause 6.L)

Restated verbatim from clause 6.J because the operator has now invoked this mandate **THIRTEEN TIMES** across two working days; the repetition itself is the forensic record. The crash root cause of the 13th invocation was a textbook nested-scroll antipattern (LazyColumn inside Column(verticalScroll)) that no existing test caught — confirming yet again that CI green is necessary, never sufficient. (See §6.Q.) Execution of tests and Challenges MUST guarantee the quality, completion and full usability by end users of the product.

### Compose Layout Antipattern Guard (Constitutional clause 6.Q, added 2026-05-05)

Forbids nesting a vertically-scrolling lazy layout inside a parent that gives unbounded vertical space. Equivalent rule horizontally. Per-feature structural tests + Compose UI Challenge Tests on the §6.I matrix are the load-bearing acceptance gates. Inherits recursively. Tests must guarantee the product works — anything else is theatre.

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

### Scoped clause for new feature interfaces (per root clause 6.E + SP-3a)

Any new feature interface added under `core/tracker/api/` (i.e. a new capability bearer beyond the seven that ship with SP-3a — Searchable, Browsable, Topic, Comments, Favorites, Authenticatable, Downloadable) MUST be accompanied by all three of:

1. **Capability enum entry.** Add a new `TrackerCapability` value in `:core:tracker:api`. The enum is the single source of truth that `TrackerDescriptor.capabilities` and `TrackerClient.getFeature<T>()` are gated on; an interface added without an enum entry is unreachable and constitutes a 6.E violation (capability declared but no path through getFeature).

2. **Behavioral test in at least one tracker module.** Add a real-stack test under `core/tracker/{rutracker,rutor}/src/test/kotlin/.../<Feature>Test.kt` that exercises the interface end-to-end using the production parser and a fixture HTML response (no SUT mocking; only the `OkHttpClient` boundary may be replaced with a `MockWebServer`). Per Sixth Law clause 4, this test is the load-bearing acceptance gate for the new interface.

3. **Mention in `docs/sdk-developer-guide.md`.** Document the new capability in the developer guide's "Adding a new tracker" section so downstream tracker authors understand which capability they MAY (or MUST) implement.

A capability that lacks any of (1), (2), (3) is a Phase-N regression and MUST be addressed before the next release tag. The `scripts/check-constitution.sh` invoked from `scripts/ci.sh` parses the enum + descriptor + capability table and asserts each declared capability resolves to a non-null `getFeature()`; this is the mechanical 6.E gate.

### Sixth Law — Real User Verification (Anti-Pseudo-Test Rule)

A test that passes while the feature it covers is broken for end users is the most expensive kind of test in this codebase — it converts unknown breakage into believed safety. This has happened in this project before: tests and Integration Challenge Tests executed green while large parts of the product were unusable on a real device. That outcome is a constitutional failure, not a coverage failure, and it MUST NOT recur.

Every test added to this codebase from this point on MUST satisfy ALL of the following. Violation of any of them is a release blocker, irrespective of coverage metrics, CI status, reviewer sign-off, or schedule pressure.

1. **Same surfaces the user touches.** The test must traverse the production code path the user's action triggers, end to end, with no shortcut that bypasses real wiring. If the user's action is "open screen → tap button → see result", the test exercises the same screen, the same button handler, and the same result-rendering code — not a synthetic call into the ViewModel that skips the screen, and not a hand-rolled flow that skips the network/database boundary the real action crosses.

2. **Provably falsifiable on real defects.** Before merging, the author MUST run the test once with the underlying feature deliberately broken (throw inside the use case, return the wrong row from the repository, return the wrong status from the API) and confirm the test fails with a clear assertion message. The PR description MUST state which deliberate break was used and what failure the test produced. A test that cannot be made to fail by breaking the thing it claims to verify is a bluff test by definition.

3. **Primary assertion on user-visible state.** The chief failure signal of the test MUST be on something a real user could see or measure: rendered UI text, persisted database row, HTTP response body / status / header, file written to disk, packet on the wire. "Mock was invoked N times" is a permitted secondary assertion, never the primary one.

4. **Integration Challenge Tests are the load-bearing acceptance gate.** A green Challenge Test means a real user can complete the flow on a real device against real services — not "the wiring compiles". A feature for which a Challenge Test cannot be written is, by definition, not shippable. Refactor for testability or remove the feature.

5. **CI green is necessary, not sufficient.** "All tests pass" is not a release signal. Before any release tag is cut, a human (or a scripted black-box runner that drives the real UI / real HTTP API) MUST have used the feature on a real device or environment and observed the user-visible outcome described in the feature spec. Tag scripts (e.g. `scripts/tag.sh`) MUST NOT be run until that verification is documented.

6. **Inheritance.** This Sixth Law applies recursively to every submodule, every feature, and every new artifact added to the project (including the Go API service). Submodule constitutions MAY add stricter rules but MUST NOT relax this one.

### §6.R — No-Hardcoding Mandate (inherited 2026-05-06, per §6.F)

See root `/CLAUDE.md` §6.R. No connection address, port, header field name, credential, key, salt, secret, schedule, algorithm parameter, or domain literal shall appear as a string/int constant in tracked source code under `core/`. Every such value MUST come from `.env` (gitignored), generated config class, runtime env var, or mounted file. Submodule MAY add stricter rules but MUST NOT relax.

### §6.S — Continuation Document Maintenance Mandate (inherited 2026-05-06, per §6.F)

See root `/CLAUDE.md` §6.S. The file `docs/CONTINUATION.md` (in the parent Lava repo) is the single-file source-of-truth handoff document for resuming work across any CLI session. Every commit that changes phase status, lands a new spec/plan, bumps a submodule pin, ships a release artifact, discovers/resolves a known issue, or implements an operator scope directive in `core/` MUST update `docs/CONTINUATION.md` in the SAME COMMIT. The §0 "Last updated" line MUST track HEAD.

### §6.T — Universal Quality Constraints (inherited 2026-05-06, per §6.F)

See root `/CLAUDE.md` §6.T. All four sub-points (Reproduction-Before-Fix, Resource Limits, No-Force-Push, Bugfix Documentation) apply verbatim to every commit in `core/`. Submodule MAY add stricter rules but MUST NOT relax.

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

## Auth UUID memory hygiene (added 2026-05-06, Phase 1)

The decrypted UUID inside the Lava client MUST be held only as `ByteArray`, never as `String`. The decrypt-use-zeroize lifetime is bounded by a single OkHttp `Interceptor.intercept()` call; the `ByteArray` is `fill(0)`'d in a `finally` block before the function returns. The Base64-encoded header VALUE is a `String` (immutable) but never logged, never persisted, never assigned to a class field, and leaves Lava code as soon as OkHttp consumes it. Adding logging that includes the header value is a constitutional violation; pre-push grep enforces.

The `core/network/impl/AuthInterceptor` is the ONLY allowed consumer of `LavaAuth.BLOB`; reflective access from elsewhere is also a violation.
