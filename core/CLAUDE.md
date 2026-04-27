# Core Modules — Agent Guide

> This constitution applies to all modules under `core/`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

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
