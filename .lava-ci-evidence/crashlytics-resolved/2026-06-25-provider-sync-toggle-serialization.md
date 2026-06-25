# Crashlytics closure — provider "Sync this provider" toggle SerializationException (2026-06-25)

**Crashlytics issue ID:** `eaa80c1486d2d5d7526346ece016e15a`
**App:** `digital.vasic.lava.client` (client RELEASE)
**Type:** FATAL
**Version first seen / last seen:** 1.3.11 (build code 1075)
**Priority:** P0 (every provider's Sync toggle crashes)
**State at this entry:** FIX LANDED on worktree branch — pending §6.Z on-device RELEASE verification + operator close-mark in console

---

## Stack-trace summary

```
kotlinx.serialization.SerializationException: Serializer for class 'WireToggle' is not found.
Please ensure that class is marked as '@Serializable' and that the serialization compiler plugin is applied.
  at kotlinx.serialization.SerializersKt ... (reflective serializer lookup)
  at lava.provider.config.ProviderConfigViewModel$perform$1.invokeSuspend (ProviderConfigViewModel.kt:92)
    → outbox.enqueue(SyncOutboxKind.SYNC_TOGGLE, json.encodeToString(WireToggle(providerId, next)))
```

User action: Settings → any provider → tap "Sync this provider" toggle.

---

## Root cause

`feature/provider_config/build.gradle.kts` applied only `lava.android.feature` +
`lava.android.library.compose`. It did NOT apply `lava.kotlin.serialization` — the
convention plugin (buildSrc id `kotlinSerialization` → `lava.kotlin.serialization`,
`buildSrc/build.gradle.kts:62`) that applies `org.jetbrains.kotlin.plugin.serialization`,
the compiler plugin which GENERATES the `$serializer` companion for every `@Serializable`
class.

`ProviderConfigViewModel` declares three `@Serializable` private nested wire classes
(`WireToggle:249`, `WireBinding:252`, `WireMirror:255`) and serializes them via
`json.encodeToString(...)`. Because `:core:domain` (a dependency of this module, applied
the serialization plugin) transitively exposed the `kotlinx-serialization-json` RUNTIME +
the `encodeToString` API to this module, the code **compiled**. But the serialization
COMPILER PLUGIN was never applied to `feature/provider_config` itself, so no `$serializer`
was generated for `WireToggle` / `WireBinding` / `WireMirror`. At runtime, kotlinx-serialization
fell back to reflective serializer lookup, found none, and threw
`SerializationException: Serializer for class 'WireToggle' is not found`.

The defect was latent in all three enqueue paths:
- `ToggleSync`     → `WireToggle`  (`SYNC_TOGGLE`, line 92) — the reported crash.
- `BindCredential` / `UnbindCredential` → `WireBinding` (`BINDING`, lines 117 / 123).
- `AddMirror` / `RemoveMirror`          → `WireMirror`  (`USER_MIRROR`, lines 167 / 176).

Compounding RELEASE factor: `app/proguard-rules.pro` had no kotlinx-serialization keep
rules, so the R8 RELEASE build would strip the generated `$serializer` even after the
compiler plugin is applied — a release-only failure mode distinct from the missing-plugin
debug failure.

---

## Fix

1. `feature/provider_config/build.gradle.kts` — added `id("lava.kotlin.serialization")`
   (the canonical project convention; no ad-hoc plugin config per root CLAUDE.md). This
   makes the compiler generate `$serializer` for all three wire classes.
2. `app/proguard-rules.pro` — added kotlinx-serialization keep rules: the generic
   `@Serializable` + `$serializer` consumer rules + a targeted
   `lava.provider.config.**$$serializer` / `lava.provider.config.**` keep for the
   prod-crash surface, so R8 RELEASE does not strip the generated serializers.

No production Kotlin source changed — the `@Serializable` annotations and `encodeToString`
calls were already correct. The bug was 100% in the build wiring + R8 keep rules.

---

## Validation test (§6.O.1 / §11.4.146 reproduce-first)

`feature/provider_config/src/test/kotlin/lava/provider/config/ProviderConfigWireSerializationTest.kt`
— Robolectric JVM unit test driving the REAL `ProviderConfigViewModel`. Each test fires a
real action (`ToggleSync` / `BindCredential` / `AddMirror`) with a recording `SyncOutbox`
and asserts the production `json.encodeToString(Wire*(...))` path reaches `enqueue` with a
VALID JSON payload (the user-observable "queued for sync" outcome) — NOT a crash. All three
wire classes covered.

**RED (current/broken code, serialization plugin absent):** 3 tests / 3 failures —
```
kotlinx.serialization.SerializationException: Serializer for class 'WireToggle' is not found.
kotlinx.serialization.SerializationException: Serializer for class 'WireBinding' is not found.
kotlinx.serialization.SerializationException: Serializer for class 'WireMirror' is not found.
```
assertion: "ToggleSync MUST enqueue a SYNC_TOGGLE payload — none was recorded
(serialization threw before enqueue). recorded=[]" (+ BINDING + USER_MIRROR).

**GREEN (after fix):**
- `:feature:provider_config:compileDebugKotlin` → BUILD SUCCESSFUL
- `:feature:provider_config:testDebugUnitTest --tests ...ProviderConfigWireSerializationTest`
  → BUILD SUCCESSFUL, `tests="3" skipped="0" failures="0" errors="0"`
- Full module suite (incl. pre-existing `ProviderConfigViewModelTest` 3/0/0) → BUILD SUCCESSFUL, no regression.

The RED→GREEN flip on the SAME test, driven solely by applying/removing the serialization
plugin, is the §11.4.146 / Sixth Law clause-2 falsifiability proof.

---

## Challenge test (§6.O.2) — OWED at the §6.Z device gate

The load-bearing end-to-end Challenge (drive the rendered Settings → provider → Sync toggle
on a real emulator against the RELEASE APK, confirming no crash) is OWED at the §6.Z device
gate. A JVM unit test cannot exercise R8 resource/code shrinking, so the RELEASE-variant
proof that the `proguard-rules.pro` keep rules hold is owed there. The validation test above
satisfies §6.O.1 (reproduce + regression at the unit/integration level) immediately.

---

## Crashlytics close-mark

The Firebase Console close-mark on `eaa80c1486d2d5d7526346ece016e15a` is performed by the
operator AFTER (a) this fix + tests land, (b) the §6.Z RELEASE device-gate Challenge passes —
never before, per §6.O.5 (closing before test coverage exists loses regression tracking).
