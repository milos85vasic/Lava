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
