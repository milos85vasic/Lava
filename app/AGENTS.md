# App Module — Agent Guide

> This file is intended for AI coding agents working in `:app`.

## Anti-Bluff Testing Pact

`:app` is bound by the root Anti-Bluff Testing Pact. Key rules:

1. **End-to-end flows are testable.** If a user flow spans multiple features, there must be a way to test it (instrumentation test or integration test).
2. **Manifest changes need tests.** Deep links, intent filters, permissions — all must be verified.
3. **No untested entry points.** `MainActivity`, `TvActivity`, `Application` — initialization logic must be testable or documented with manual verification steps.

## Build & Release

- Debug APK: `./gradlew :app:assembleDebug`
- Release APK: `./gradlew :app:assembleRelease`
- Release is signed with `keystores/release.keystore` (credentials from `.env`).
