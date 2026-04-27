# Feature Modules — Agent Guide

> This constitution applies to all modules under `feature/`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

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

## Feature Module Pattern

Each feature follows Orbit MVI:
- `XxxViewModel` — `@HiltViewModel`, `ContainerHost<State, SideEffect>`
- `XxxState` — data class or sealed interface
- `XxxAction` — sealed interface for user intents
- `XxxSideEffect` — sealed interface for one-time events

Keep the pattern consistent across all features.

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
