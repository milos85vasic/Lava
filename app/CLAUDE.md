# App Module — Agent Guide

> This constitution applies to `:app`. See root `CLAUDE.md` and `AGENTS.md` for project-wide conventions.

## Anti-Bluff Testing Pact (Submodule Law)

The `:app` module MUST obey the root Anti-Bluff Testing Pact. In addition:

### Navigation & Integration Tests
- `:app` contains the top-level navigation graph and activity entry points.
- Instrumentation tests in `:app` (if added) MUST verify end-to-end user flows across multiple features.
- Example: User opens Menu → taps Server → discovers local proxy → sees it in the list. This flow MUST be testable.

### Manifest & Deep Link Verification
- Deep link handling (`rutracker.org` URLs) MUST be tested.
- Any change to `AndroidManifest.xml` that affects intents, permissions, or exported components MUST be accompanied by a test that validates the behavior.

## App-Specific Architecture

- `MainActivity` — Phone/tablet entry point.
- `TvActivity` — TV entry point, extends `MainActivity` with `PlatformType.TV`.
- Navigation graph wires all feature modules together.
