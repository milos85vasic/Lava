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
