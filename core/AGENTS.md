# Core Modules — Agent Guide

> This file is intended for AI coding agents working in `core/` modules.

## Module Structure

- `core:models` — Pure Kotlin data classes. No Android dependency. Must be serializable.
- `core:common` — Pure Kotlin utilities. No Android dependency.
- `core:domain` — UseCases and business logic. Pure Kotlin (depends on `data` api, not impl).
- `core:data` — Repository implementations and data services. Android-dependent.
- `core:database` — Room entities, DAOs, and schemas.
- `core:testing` — Shared test fakes, rules, and utilities for ALL modules.

## Anti-Bluff Testing Pact

All core modules are bound by the root Anti-Bluff Testing Pact. Key rules for core:

1. **UseCase tests use real implementations.** Never mock a UseCase in a downstream test.
2. **Repository fakes enforce real constraints.** If Room would throw on duplicate insert, the fake must throw too.
3. **Service fakes simulate real protocols.** NsdManager returns `_lava._tcp.local.` — the fake must too.
4. **Every UseCase has an Integration Challenge Test.** Real impl, real fakes, verified side effects.

## Consequences of Bluff Tests in Core

A bluff test in `core:domain` or `core:data` is especially dangerous because:
- Every `feature:*` module depends on core.
- A bluff UseCase test means ALL downstream ViewModel tests are also bluffing (even if they use the real UseCase, the UseCase itself is broken).
- Core bugs affect the entire app. Core tests must therefore be the most rigorous.
