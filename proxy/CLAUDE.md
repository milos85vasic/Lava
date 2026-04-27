# Proxy Module — Agent Guide

> This constitution applies to `:proxy`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

The `:proxy` module MUST obey the root Anti-Bluff Testing Pact. In addition:

### Server-Side Tests
- Every Ktor route handler, service, and utility class MUST have unit tests.
- Tests MUST verify actual HTTP response content, status codes, and headers — not just "handler was invoked."
- mDNS advertisement (`ServiceAdvertisement`) MUST be tested to verify it registers the correct service type and properties.

### Contract Tests with Android Client
- The proxy and app form a protocol contract. Changes to either side MUST be validated against the other.
- Example: If `ServiceAdvertisement` changes its service type, there must be a test that proves the Android `LocalNetworkDiscoveryServiceImpl` still matches it.

### Docker & Deployment Tests
- The Docker build MUST be verified (at minimum compile the fat JAR and build the image locally).
- Environment variable handling (`ADVERTISE_HOST`) MUST be tested.

## Proxy Architecture

- Ktor/Netty server on port 8080.
- Scrapes rutracker.org and exposes JSON REST API.
- Uses Koin for DI.
- mDNS advertisement via JmDNS (`_lava._tcp`).

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
