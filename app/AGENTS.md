# App Module — Agent Guide

> This file is intended for AI coding agents working in `:app`.

## Anti-Bluff Testing Pact

`:app` is bound by the root Anti-Bluff Testing Pact. Key rules:

1. **End-to-end flows are testable.** If a user flow spans multiple features, there must be a way to test it (instrumentation test or integration test).
2. **Manifest changes need tests.** Deep links, intent filters, permissions — all must be verified.
3. **No untested entry points.** `MainActivity`, `TvActivity`, `Application` — initialization logic must be testable or documented with manual verification steps.

### Anti-Bluff Functional Reality Mandate (Constitutional clauses 6.J, 6.L, 6.Q)

Inherited from root `/CLAUDE.md`. CI green is necessary, NEVER sufficient. Every test must confirm the feature actually works for an end user, end-to-end, on the gating matrix. No LazyColumn inside verticalScroll — per §6.Q Compose Layout Antipattern Guard.

### §6.R — No-Hardcoding Mandate (inherited 2026-05-06, per §6.F)

See root `/CLAUDE.md` §6.R. No connection address, port, header field name, credential, key, salt, secret, schedule, algorithm parameter, or domain literal in tracked source code.

### §6.S — Continuation Document Maintenance Mandate (inherited 2026-05-06, per §6.F)

See root `/CLAUDE.md` §6.S. Every state-changing commit MUST update `docs/CONTINUATION.md` in the same commit.

### §6.T — Universal Quality Constraints (inherited 2026-05-06, per §6.F)

See root `/CLAUDE.md` §6.T. Reproduction-Before-Fix, Resource Limits, No-Force-Push, and Bugfix Documentation apply verbatim.

## Build & Release

- Debug APK: `./gradlew :app:assembleDebug`
- Release APK: `./gradlew :app:assembleRelease`
- Release is signed with `keystores/release.keystore` (credentials from `.env`).

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
