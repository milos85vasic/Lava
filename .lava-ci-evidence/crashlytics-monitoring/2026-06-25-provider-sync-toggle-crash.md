# Crashlytics Investigation — "Sync this provider" toggle crash

**Date:** 2026-06-25
**Investigator:** crash-investigation agent (read-only; no Crashlytics issue modified, no commit)
**User report (verbatim):** "Sync this provider toggle crashes the app (settings → any provider)."
**Repro path:** Lava client → Settings → tap any provider → toggle "Sync this provider" → app crashes.

---

## 1. EXACT CRASH — real Crashlytics data (MATCH FOUND)

| Field | Value |
|---|---|
| **Project** | `lava-vasic-digital` (815513478335) |
| **App** | `digital.vasic.lava.client` (RELEASE) — App ID `1:815513478335:android:456475e2ef4039d8cfd20a` |
| **Issue ID** | `eaa80c1486d2d5d7526346ece016e15a` |
| **Variant ID** | `f840feed3b8e024c56d667005290c481` |
| **Title** | `lava.provider.config.ProviderConfigViewModel$perform$1.invokeSuspend` |
| **Exception** | `kotlinx.serialization.SerializationException` |
| **Message** | `Serializer for class 'ProviderConfigViewModel$WireToggle' is not found. Please ensure that class is marked as '@Serializable' and that the serialization compiler plugin is applied.` |
| **Blame frame** | `ProviderConfigViewModel.kt:92` (`lava.provider.config.ProviderConfigViewModel$perform$1.invokeSuspend`) |
| **error_class custom key** | `IllegalStateException` (parent of SerializationException) |
| **Affected build** | **1.3.11 (versionCode 1075)** — `firstSeenVersion == lastSeenVersion == 1.3.11`; `build_type: release` |
| **Build revision** | `40301014f73d8f40ff01d65cf089a57786c5bd9e` |
| **Event count** | 2 events / 1 impacted user / 2 sessions (last 14 days) |
| **Signal** | `SIGNAL_FRESH` — first appeared **2026-06-25** (today) |
| **First/last seen** | 2026-06-25 (issue is brand new, same day as the user report) |
| **State** | OPEN |
| **Sample device** | Samsung SM-S918B (Galaxy S23 Ultra), Android 16, ARM64, PHONE |
| **Sample event time** | 2026-06-25T16:36:22Z |
| **Console** | https://console.firebase.google.com/v1/appid/project/lava-vasic-digital/crashlytics/app/1:815513478335:android:456475e2ef4039d8cfd20a/issues/eaa80c1486d2d5d7526346ece016e15a |

### Full stack trace (from sample event `6A3D58F3...AEDBCB5_2233818428748775273`)

```
kotlinx.serialization.SerializationException: Serializer for class 'ProviderConfigViewModel$WireToggle' is not found.
Please ensure that class is marked as '@Serializable' and that the serialization compiler plugin is applied.

at kotlinx.serialization.SerializationException.<init> (SerializationException.java:51)
at kotlinx.serialization.internal.Platform_commonKt.serializerNotRegistered (Platform_common.kt:90)
at kotlinx.serialization.internal.PlatformKt.platformSpecificSerializerNotRegistered (Platform.kt:33)
at kotlinx.serialization.SerializersKt__SerializersKt.serializer (SerializersKt__Serializers.kt:134)
at kotlinx.serialization.SerializersKt.serializer (Serializers.kt:1)
at kotlinx.serialization.SerialFormatKt.encodeToString (SerialFormat.kt:113)
at lava.provider.config.ProviderConfigViewModel$perform$1.invokeSuspend (ProviderConfigViewModel.kt:92)   <-- BLAME FRAME
at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith (ContinuationImpl.kt:33)
... (coroutine + Room TransactionExecutor frames) ...
at androidx.room.TransactionExecutor.execute$lambda$1$lambda$0 (TransactionExecutor.java:38)
at java.util.concurrent.ThreadPoolExecutor.runWorker (ThreadPoolExecutor.java:1100)
at java.lang.Thread.run (Thread.java:1572)

Thread: arch_disk_io_1 (crashed)
```

**Breadcrumb just before crash:** `screen_view { firebase_screen_class: MainActivity }`.
**Logs just before crash:** two `WARN: credentials key holder locked — observe() emitting empty list` entries (unrelated; from `core:credentials` observe path).

---

## 2. EXACT PRODUCTION CODE PATH (cross-referenced against source)

File: `feature/provider_config/src/main/kotlin/lava/provider/config/ProviderConfigViewModel.kt`

The user taps the "Sync this provider" Switch → fires `ProviderConfigAction.ToggleSync` → `perform()` intent:

```kotlin
ProviderConfigAction.ToggleSync -> {
    val current = toggleDao.get(providerId)?.enabled ?: false
    val next = !current
    toggleDao.upsert(ProviderSyncToggleEntity(providerId, next))     // line 91 — DB write SUCCEEDS
    outbox.enqueue(SyncOutboxKind.SYNC_TOGGLE, json.encodeToString(WireToggle(providerId, next)))  // line 92 — CRASHES HERE
}
```

- Line 91 `toggleDao.upsert(...)` runs on the Room `TransactionExecutor` thread (`arch_disk_io_1`) — matches the crashed thread + Room frames in the trace. The toggle value is actually persisted.
- Line 92 calls `json.encodeToString(WireToggle(providerId, next))`. `json` is `Json { ignoreUnknownKeys = true }` (companion, line 275). `encodeToString` is the **reified** overload (import `kotlinx.serialization.encodeToString`, line 10) → resolves the serializer for `WireToggle` reflectively at runtime.
- `WireToggle` is declared at line 249 **with** `@kotlinx.serialization.Serializable` and **is** `private`:
  ```kotlin
  @kotlinx.serialization.Serializable
  private data class WireToggle(val providerId: String, val enabled: Boolean)
  ```

The annotation is present in source, yet the runtime serializer is missing. Why ↓.

---

## 3. ROOT-CAUSE HYPOTHESIS (high confidence — corroborated by build config)

The `@Serializable` annotation alone does NOT generate a serializer. The kotlinx-serialization **Gradle compiler plugin** must be applied to the module so the compiler synthesizes `WireToggle.$serializer`. Two compounding defects:

### Defect A (primary) — the serialization plugin is NOT applied to `feature/provider_config`
`feature/provider_config/build.gradle.kts` plugins block:
```kotlin
plugins {
    id("lava.android.feature")
    id("lava.android.library.compose")
}
```
Neither `lava.kotlin.serialization` nor `org.jetbrains.kotlin.plugin.serialization` is applied. The `lava.android.feature` convention plugin (`buildSrc/.../AndroidFeatureConventionPlugin.kt`) applies only `lava.android.library` + `lava.android.hilt` + context-receivers + Orbit — **no serialization plugin**. Therefore `WireToggle.$serializer`, `WireBinding.$serializer`, `WireMirror.$serializer` are **never code-generated**. `Json.encodeToString` falls back to the reflective registry, finds nothing, and throws `SerializationException`.

> Note: `core:sync` provides `SyncOutbox` / `SyncOutboxKind`; the `Wire*` payload classes live **inside the feature ViewModel**, in a module that does not compile serializers for them.

### Defect B (secondary, why release-only) — R8 strips the serializer even if it existed
The crash custom keys show `build_type: release`. The release build runs R8/code-shrinking:
`app/build.gradle.kts` buildTypes.release uses `postprocessing { isRemoveUnusedCode = true; isOptimizeCode = true }` (R8 active). The `isMinifyEnabled = false` at line 195 is the **debug** block — debug does NOT shrink, which is exactly why the crash is **release-only** (dev app `...cfd20a` shows NO sync-toggle crash in the same window — see §5).
`app/proguard-rules.pro` contains **no kotlinx-serialization keep rules** (no `-keepclassmembers class **$$serializer { *; }`, no `-keep,includedescriptorclasses class ...$serializer`). Even after Defect A is fixed, R8 could strip the generated `$serializer` / `Companion.serializer()` reflective entry points without keep rules. The Firebase keep rules added 2026-05-05 do not cover serialization.

**Net:** Defect A is sufficient on its own to cause this crash. Defect B explains why it manifests only on the release variant and must be fixed too to prevent a regression once A is fixed.

---

## 4. PRECISE REPRODUCE CONDITIONS

- **Build variant:** RELEASE only (R8 active). The debug `.dev` build does not minify and is not affected (confirmed: no matching event on the dev app).
- **State:** Settings → open any provider's config screen → tap the "Sync this provider" Switch.
- **Trigger:** the `ToggleSync` branch reaching line 92 `json.encodeToString(WireToggle(...))`. The DB upsert on line 91 succeeds first (so the toggle value is persisted before the crash); the crash is on the outbox-enqueue serialization on the Room executor thread.
- **Universality:** any provider, any toggle direction (on→off or off→on), because the missing serializer is a compile-time/runtime-registry gap, not data-dependent. First tap on a release device crashes.
- **Same-file siblings at equal risk (latent):** `WireBinding` (lines 117, 123) on Bind/Unbind Credential, and `WireMirror` (lines 167, 176) on Add/Remove Mirror — same `json.encodeToString` of a `@Serializable` class in the same plugin-less module. They will crash identically when their actions are exercised on a release build. (`WireBinding` is NOT currently in Crashlytics for this window — fewer users reach Assign-credential; but it is the same defect.)

---

## 5. ANTI-BLUFF / SCOPE NOTES (real API data only)

- DEV app (`1:815513478335:android:54ca2ca31e6c4f42cfd20a`) top issues, last 14 days: only `f76025cd...` (NavBackStackEntry lifecycle ISE on the search route) — **no** provider-sync-toggle crash. This corroborates the release-only R8 hypothesis.
- RELEASE app top FATAL issues, last 14 days, also surfaced (for completeness, NOT the reported crash):
  - `58a1335272bc4ee06595bda6302a670a` — `CredentialsKeyHolder.require` ISE "credentials key holder is locked" (1.3.10), 5 events.
  - `9ba8502ee0ba0d1fdd03987650b8acf8` / `b9baeaede585...` — `ApiEngineService` foreground-service dataSync timeout (api app 0.2.6).
  - `c7c8cccad09f72bd7bb95455226109b8` — Compose nested-scroll ISE (LazyColumn in verticalScroll), 1.2.3→1.3.10.
- The reported crash maps to issue `eaa80c1486d2d5d7526346ece016e15a` **exactly** — title, blame frame (`ProviderConfigViewModel.kt:92`), and the `ToggleSync` code path all align. This is NOT a "closest match"; it is the crash.

---

## 6. SUGGESTED FIX DIRECTION (for §11.4.146 reproduce-first test + fix — not applied here)

1. Apply the kotlinx-serialization plugin to `feature/provider_config` (e.g. add `id("lava.kotlin.serialization")` to its plugins block) so `WireToggle`/`WireBinding`/`WireMirror` serializers are generated. (Defect A — necessary + sufficient.)
2. Add kotlinx-serialization keep rules to `app/proguard-rules.pro` so R8 retains generated `$serializer` reflective entry points. (Defect B — release-hardening.)
3. Reproduce-first test (§11.4.146 / Sixth Law clause 2): a release-variant / R8-equivalent unit test that calls the real `ToggleSync` path and asserts the outbox row is enqueued with the correct serialized JSON (`{"providerId":"...","enabled":true}`), which fails before the fix with the exact `SerializationException`. Falsifiability rehearsal: remove the serialization plugin → test must throw the documented exception.
4. Consider moving the `Wire*` DTOs into `core:sync` (which already owns `SyncOutbox`) where a serialization plugin is appropriately applied, rather than nesting them in a plugin-less feature ViewModel.

(No code change, no commit, no Crashlytics mutation performed by this investigation per task constraints.)
