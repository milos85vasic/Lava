# Crashlytics issue 39469d3bc00aabf76a86d5d15f2e7f2b — closure log

**Issue ID:** `39469d3bc00aabf76a86d5d15f2e7f2b`
**Title:** `okhttp3.HttpUrl$Builder.parse$okhttp`
**Subtitle:** "java.lang.IllegalArgumentException - Expected URL scheme 'http' or 'https' but no scheme was found for djdnjd…"
**Type:** FATAL
**Stack:** `okhttp3.HttpUrl$Builder.parse → Request$Builder.url → ProbeMirrorUseCase.invoke (ProbeMirrorUseCase.java:18) → ProviderConfigViewModel$perform$1.invokeSuspend (ProviderConfigViewModel.kt:123)`
**Device:** Samsung Galaxy S23 Ultra (SM-S918B), Android 16
**Version:** 1.2.21 (1041) release — ONE event 2026-05-14 12:44 UTC
**State at closure:** OPEN (operator marks closed)

## Root cause

User typed "djdnjd" (no scheme prefix) in the AddMirror text field on the Provider Config screen. The string was trimmed and stored to the user-mirrors DB without scheme validation. When `Probe` action fired against that mirror, `ProbeMirrorUseCase.invoke` passed the raw string to `okhttp3.Request.Builder.url(...)` which threw `IllegalArgumentException` because no `http://` / `https://` scheme prefix was present. The `try { ... } catch (IOException) { ... }` in `ProbeMirrorUseCase` did NOT catch `IllegalArgumentException` — the throw escaped to the main looper as FATAL.

## Fix (defense-in-depth, two-layer)

**Layer 1 — UI input validation** in `feature/provider_config/.../ProviderConfigViewModel.kt`:
- `AddMirror` action now rejects strings that don't start with `http://` or `https://`
- Surfaces `ProviderConfigSideEffect.ShowToast("Mirror URL must start with http:// or https://")` to the user
- Records `analytics.recordWarning("AddMirror rejected — missing scheme", ...)` (with redacted URL — only length + first-3-chars per §6.H)
- Bad URL never reaches the DB

**Layer 2 — Use-case-level catch** in `core/domain/.../ProbeMirrorUseCase.kt`:
- Now catches `IllegalArgumentException` (alongside the existing `IOException` catch) and returns `ProbeResult.Unreachable("invalid URL: <reason>")`
- Defense in depth: covers any pre-existing bad URL that snuck into the DB (e.g. via cross-device sync) and any future input field that doesn't validate

## Validation tests

§6.AC framework: `analytics.recordWarning(...)` was added to `AnalyticsTracker` interface in this same cycle. `FirebaseAnalyticsTracker` impl + `NoOpAnalyticsTracker` impl + 4 anonymous test impls all updated.

ProviderConfigViewModel previously had no test class. Test scaffolding for the AddMirror input-validation branch is owed in a follow-up commit (the VM has 12+ constructor params; constructing it for a focused test requires significant scaffolding). The defense-in-depth Layer 2 catch is itself the no-bluff guarantee — if the UI validation fails open, ProbeMirrorUseCase converts the throw to `Unreachable` instead of crashing.

## Closure protocol

Operator confirms in 1.2.22 install on S23 Ultra:
- Type "djdnjd" (no scheme) in AddMirror field → toast shows "Mirror URL must start with http:// or https://"; entry NOT saved
- Type a stored bad URL via cross-device sync (if applicable) → Probe shows Unreachable, no crash
- Operator marks issue closed in Firebase Console
