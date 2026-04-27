# Proxy Module — Agent Guide

> This file is intended for AI coding agents working in `:proxy`.

## Anti-Bluff Testing Pact

`:proxy` is bound by the root Anti-Bluff Testing Pact. Key rules:

1. **Route tests verify real responses.** Assert on JSON body, status code, content-type — not just execution.
2. **mDNS contract is tested.** `ServiceAdvertisement` must have tests verifying service type, properties, and port.
3. **Client-server contract is preserved.** Any change to the API schema or discovery protocol must be tested against the Android client's expectations.
4. **No untested environment handling.** `ADVERTISE_HOST`, auth tokens, and config parsing must be tested.

## Build Commands

```bash
./gradlew :proxy:buildFatJar    # → proxy/build/libs/app.jar
./build_and_push_docker_image.sh # Build + push Docker image
```

## Docker

- Base: `openjdk:17.0.1-jdk-slim`
- Port: `8080`
- Entrypoint: `java -jar /app/app.jar`

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
