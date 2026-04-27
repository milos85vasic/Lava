# Core Modules — Agent Guide

> This constitution applies to all modules under `core/`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

Every core module MUST obey the root Anti-Bluff Testing Pact. In addition:

### UseCase Tests
- Every public UseCase MUST have unit tests that exercise the **real implementation** (`*Impl`) wired to realistic fakes.
- UseCase tests MUST verify **repository side effects** (data actually persisted) and **cross-module interactions**, not just return values.
- If a UseCase branches on repository state, both branches MUST be tested with the real repository fake.

### Repository Fakes
- `Test*Repository` fakes live in `:core:testing`. They MUST be kept in sync with real repository behavior.
- When a real repository adds a constraint (e.g., Room `@PrimaryKey`, `UNIQUE` index, `FOREIGN KEY` cascade), the matching fake MUST be updated in the same commit.
- A fake that diverges from reality is a bluff fake and is forbidden.

### Service Fakes
- `Test*Service` fakes MUST simulate real-world protocol behaviors:
  - Network timeouts
  - mDNS service-type suffixes (e.g., `.local.`)
  - Partial / malformed responses
- If the real service has a behavior that affects parsing logic, the fake MUST expose it.

### Integration Challenge Tests for Core
- For every new UseCase, write at least one test that wires it to real fakes and verifies end-to-end behavior.
- Example: `DiscoverLocalEndpointsUseCaseImpl` tested with `TestLocalNetworkDiscoveryService` + `TestEndpointsRepository` + `TestSettingsRepository` — verifying that discovery → add → set-active actually mutates repository state.
