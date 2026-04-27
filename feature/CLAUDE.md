# Feature Modules — Agent Guide

> This constitution applies to all modules under `feature/`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

Every feature module MUST obey the root Anti-Bluff Testing Pact. In addition:

### ViewModel Tests
- **ViewModel tests MUST use real UseCase implementations** wired to realistic fakes from `:core:testing`.
- Mocking or stubbing a UseCase in a ViewModel test is a bluff test and is forbidden.
- ViewModel tests MUST verify:
  - State transitions are correct AND meaningful to users.
  - Side effects are emitted AND contain correct data.
  - Repository state is mutated (not just ViewModel state).

### UI Tests
- Compose UI tests (if written) MUST verify that user actions produce real outcomes, not just that composables render.
- Example: Tapping "Discover" must actually trigger discovery, not just change a button color.

### Integration Challenge Tests for Features
- Every feature MUST have at least one Integration Challenge Test that exercises the full stack:
  `ViewModel (real) → UseCase (real) → Repository (fake but behaviorally equivalent) → Service (fake but behaviorally equivalent)`
- The Challenge Test must verify that a user-visible outcome is achieved (e.g., endpoint added to list, settings updated).
- If the Challenge Test passes but the real feature is broken, the test is a bluff and must be rewritten.

## Feature Module Pattern

Each feature follows Orbit MVI:
- `XxxViewModel` — `@HiltViewModel`, `ContainerHost<State, SideEffect>`
- `XxxState` — data class or sealed interface
- `XxxAction` — sealed interface for user intents
- `XxxSideEffect` — sealed interface for one-time events

Keep the pattern consistent across all features.
