# App Module — Agent Guide

> This constitution applies to `:app`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

The `:app` module MUST obey the root Anti-Bluff Testing Pact. In addition:

### Navigation & Integration Tests
- `:app` contains the top-level navigation graph and activity entry points.
- Instrumentation tests in `:app` (if added) MUST verify end-to-end user flows across multiple features.
- Example: User opens Menu → taps Server → discovers local proxy → sees it in the list. This flow MUST be testable.

### Manifest & Deep Link Verification
- Deep link handling (`rutracker.org` URLs) MUST be tested.
- Any change to `AndroidManifest.xml` that affects intents, permissions, or exported components MUST be accompanied by a test that validates the behavior.

## App-Specific Architecture

- `MainActivity` — Phone/tablet entry point.
- `TvActivity` — TV entry point, extends `MainActivity` with `PlatformType.TV`.
- Navigation graph wires all feature modules together.

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
