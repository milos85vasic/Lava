# Feature Modules — Agent Guide

> This file is intended for AI coding agents working in `feature/` modules.

## Anti-Bluff Testing Pact

All feature modules are bound by the root Anti-Bluff Testing Pact. Key rules for features:

1. **ViewModel tests use real UseCases.** Never mock a UseCase. Use the real `*Impl` class with fakes from `:core:testing`.
2. **Verify real outcomes.** Assert on repository state, side-effect content, and user-visible state — not just "state changed."
3. **Integration Challenge Tests are mandatory.** Every feature must have at least one test wiring real ViewModel → real UseCase → realistic fake repositories.
4. **No bluff UI tests.** UI tests must verify functional outcomes, not just rendering.

### Anti-Bluff Functional Reality Mandate (Constitutional clauses 6.J, 6.L, 6.Q)

Inherited from root `/CLAUDE.md`. CI green is necessary, NEVER sufficient. Every test must confirm the feature actually works for an end user, end-to-end, on the gating matrix. Tests that pass against deliberately-broken production code are bluffs and must be removed. No LazyColumn inside verticalScroll — per §6.Q Compose Layout Antipattern Guard.

## Feature-Specific Notes

- `:feature:connection` — Handles endpoint selection and local network discovery. The `ConnectionsViewModel` and `MenuViewModel` both trigger `DiscoverLocalEndpointsUseCase`. Their tests MUST use the real use case to catch discovery logic bugs.
- `:feature:menu` — Auto-discovers local endpoints on init. Tests MUST verify this auto-discovery runs and produces correct side effects.

## Why Feature Tests Are Critical

Features are the user-facing layer. If a feature test is a bluff, users will encounter broken functionality. Feature Integration Challenge Tests are the last line of defense against shipping broken code.

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
