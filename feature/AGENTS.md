# Feature Modules — Agent Guide

> This file is intended for AI coding agents working in `feature/` modules.

## Anti-Bluff Testing Pact

All feature modules are bound by the root Anti-Bluff Testing Pact. Key rules for features:

1. **ViewModel tests use real UseCases.** Never mock a UseCase. Use the real `*Impl` class with fakes from `:core:testing`.
2. **Verify real outcomes.** Assert on repository state, side-effect content, and user-visible state — not just "state changed."
3. **Integration Challenge Tests are mandatory.** Every feature must have at least one test wiring real ViewModel → real UseCase → realistic fake repositories.
4. **No bluff UI tests.** UI tests must verify functional outcomes, not just rendering.

## Feature-Specific Notes

- `:feature:connection` — Handles endpoint selection and local network discovery. The `ConnectionsViewModel` and `MenuViewModel` both trigger `DiscoverLocalEndpointsUseCase`. Their tests MUST use the real use case to catch discovery logic bugs.
- `:feature:menu` — Auto-discovers local endpoints on init. Tests MUST verify this auto-discovery runs and produces correct side effects.

## Why Feature Tests Are Critical

Features are the user-facing layer. If a feature test is a bluff, users will encounter broken functionality. Feature Integration Challenge Tests are the last line of defense against shipping broken code.
