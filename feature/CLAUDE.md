# Feature Modules — Agent Guide

> This constitution applies to all modules under `feature/`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

> Inherits Laws 1–7 from the root Anti-Bluff Testing Pact in `/CLAUDE.md`. The Sixth Law (Real User Verification) and the Seventh Law (Anti-Bluff Enforcement, added 2026-04-30) are binding on every feature module — submodule rules below are additive, never relaxing. The Seventh Law's `Bluff-Audit:` commit-message stamp, real-stack verification gate, pre-tag real-device attestation, forbidden test patterns, recurring bluff hunt, bluff discovery protocol, and inheritance clause all apply here verbatim. Pre-push hooks reject feature commits that violate any clause.

### Anti-Bluff Functional Reality Mandate (Constitutional clauses 6.J, 6.L, 6.Q)

Inherited from root `/CLAUDE.md`. Every test, Challenge Test, and CI gate MUST confirm the feature actually works for an end user, end-to-end, on the gating matrix (clause 6.I). CI green is necessary, NEVER sufficient. Tests that pass against deliberately-broken production code are bluffs and must be removed. No LazyColumn inside verticalScroll — per §6.Q. The operator's mandate (invoked 13 times): execution of tests and Challenges MUST guarantee the quality, completion and full usability by end users of the product.

Every feature module MUST obey the root Anti-Bluff Testing Pact. In addition:

### ViewModel Tests
- **ViewModel tests MUST use real UseCase implementations** wired to realistic fakes from `:core:testing`.
- Mocking or stubbing a UseCase in a ViewModel test is a bluff test and is forbidden.
- ViewModel tests MUST verify:
  - State transitions are correct AND meaningful to users.
  - Side effects are emitted AND contain correct data.
  - Repository state is mutated (not just ViewModel state).

### UI Tests
- Compose UI tests (if written) MUST verify that user actions produce real outcomes, not just that composables render.
- Example: Tapping "Discover" must actually trigger discovery, not just change a button color.

### Integration Challenge Tests for Features
- Every feature MUST have at least one Integration Challenge Test that exercises the full stack:
  `ViewModel (real) → UseCase (real) → Repository (fake but behaviorally equivalent) → Service (fake but behaviorally equivalent)`
- The Challenge Test must verify that a user-visible outcome is achieved (e.g., endpoint added to list, settings updated).
- If the Challenge Test passes but the real feature is broken, the test is a bluff and must be rewritten.

### Test classification (per Sixth Law clauses 1, 3, 4)

Every test in a feature module MUST be one of:

- **CHALLENGE** — primary assertion on persisted/observable user-visible state (repository row, settings entry, ViewModel state flag rendered as UI). Satisfies clause 4 acceptance.
- **VM-CONTRACT** — primary assertion on `SideEffect` emission. Verifies the screen's contract with the ViewModel; does NOT prove the rendered screen reacts. Satisfies clause 1 only at the ViewModel layer; the rendered-UI Challenge is a separate, additional gate.

Tests of either type MUST be marked with a `// CHALLENGE` or `// VM-CONTRACT` comment immediately above the `@Test` line so a reader can audit Sixth-Law compliance at a glance. Conflating the two — calling a side-effect-emission test a "Challenge" — is itself a bluff smell that the audit MUST flag.

### Rendered-UI Challenges (gap closed by SP-3a Step 6, 2026-04-30)

The project NOW has `app/src/androidTest/` wired with Compose UI test infrastructure (Hilt instrumented runner, `androidx.compose.ui.test.junit4`, `HiltTestApplication`). Every SDK-consuming feature can — and the eight C1–C8 Challenge Tests at `app/src/androidTest/kotlin/lava/app/challenges/` already do — open the real Composable, drive input through `MainActivity`, and assert on rendered output.

Operator usage (the load-bearing real-user gate per Sixth Law clause 5):

```
./gradlew :app:connectedDebugAndroidTest \
  --tests "lava.app.challenges.Challenge01AppLaunchAndTrackerSelectionTest"
```

A connected Android device or running emulator IS required for `connectedDebugAndroidTest`. Source-only compile (`./gradlew :app:compileDebugAndroidTestKotlin`) verifies the wiring without a device and is what `scripts/ci.sh --changed-only` runs in the pre-push gate. The full Challenge Test suite is part of `scripts/ci.sh --full` at tag time.

Constraints that REMAIN in force:

1. Release notes MUST NOT claim "Sixth-Law compliant" for any feature whose Challenge Test has not been operator-rehearsed on a real device per Task 5.22 of SP-3a (`.lava-ci-evidence/<TAG>/real-device-verification.md`).
2. Each Challenge Test's KDoc MUST carry the falsifiability rehearsal protocol (mutation, expected failure, revert) so the operator can execute clause 2 of the Sixth Law.
3. Older `feature/*/src/test/` ViewModel tests still tagged as `VM-CONTRACT` retain their classification — only features whose Challenge Test has been written and operator-rehearsed are upgraded to `CHALLENGE`.

This closes the constitutional debt item that was owed since the project's inception. `scripts/tag.sh` evidence-recording now spans the Android client side as well as the lava-api-go side.

### Scoped clause for SDK-consuming ViewModels (per root clauses 6.D + 6.E + SP-3a)

Every feature ViewModel that consumes `LavaTrackerSdk` (directly or via a UseCase that wraps it) MUST have a Challenge Test covering the same UI path the user takes when invoking the SDK operation. The Challenge Test:

1. **Lives at `app/src/androidTest/kotlin/lava/app/challenges/C<N>_<Name>Test.kt`** (one Compose UI test per scenario, instrumented). The pre-SP-3a "owed" gap above is now closed for SDK-consuming ViewModels — those features MUST ship with the rendered-UI Challenge alongside the ViewModel change.
2. **Falsifiability rehearsal recorded in the same PR.** Per Sixth Law clause 2, the author MUST run the test once with the underlying SDK code path deliberately broken (mutation: throw inside the feature impl, return empty from the parser, drop a capability from the descriptor) and confirm the test fails with a clear assertion message. The mutation, the failure output, and the revert MUST be captured in `.lava-ci-evidence/sp3a-challenges/C<N>-<sha>.json`.
3. **Operator real-device attestation required for tagging.** Per Sixth Law clause 5 + Seventh Law clause 3, the operator MUST execute the Challenge Test scenario on a real Android device against the real tracker (RuTracker or RuTor) before the next release tag is cut, and record the outcome in `.lava-ci-evidence/<TAG>/real-device-verification.md`. `scripts/tag.sh` will refuse to tag without this evidence (Phase 5 gate).

The eight Challenge Tests written in SP-3a Phase 5 (C1 through C8) are the founding examples — every future SDK-consuming ViewModel MUST follow the same pattern.

## Feature Module Pattern

Each feature follows Orbit MVI:
- `XxxViewModel` — `@HiltViewModel`, `ContainerHost<State, SideEffect>`
- `XxxState` — data class or sealed interface
- `XxxAction` — sealed interface for user intents
- `XxxSideEffect` — sealed interface for one-time events

Keep the pattern consistent across all features.

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
