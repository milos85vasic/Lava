# Crashlytics closure — CredentialsKeyHolder locked FATAL (2026-06-24)

**Crashlytics issue ID:** `58a1335272bc4ee06595bda6302a670a`
**App:** `digital.vasic.lava.client` (client RELEASE)
**Type:** FATAL
**Events / users:** 5 events / 1 user
**Version first seen / last seen:** 1.3.10 / 1.3.10 (build code 1067)
**Priority:** P0
**State at this entry:** FIX LANDED — pending on-device verification + operator close-mark in console

---

## Stack-trace summary

```
java.lang.IllegalStateException: credentials key holder is locked — prompt user for passphrase first
  at lava.credentials.session.CredentialsKeyHolder.require (CredentialsKeyHolder.java:23)
  at lava.credentials.di.CredentialsModule.provideCredentialsKeyProvider$lambda$2 (CredentialsModule.java:77)
  at lava.credentials.CredentialsEntryRepositoryImpl.decode (CredentialsEntryRepositoryImpl.kt:78)
  at lava.credentials.CredentialsEntryRepositoryImpl$observe$$inlined$map$1$2.emit (CredentialsEntryRepositoryImpl.java:51)
  at androidx.room.coroutines.FlowUtil$createFlow$$inlined$map$1$2.emit (FlowUtil.java:223)
  at [Room Flow continuation chain]
  at android.os.Looper.loop (Looper.java:392)
  at android.app.ActivityThread.main (ActivityThread.java:10346)   ← MAIN THREAD CRASH
```

Breadcrumb preceding crash: `lava_search_submit: mumy`, then `lava_search_submit: prince` — the user
was actively running searches when the DAO emitted a DB row update to the credentials Flow observer.

---

## Root cause

`CredentialsKeyHolder.require()` (submodules/security, line 23) is called unconditionally inside
`CredentialsEntryRepositoryImpl.decode()` (the `map` operator of `observe()`'s Room Flow).
When the key holder is in the **locked** state (passphrase not yet entered, key evicted after
backgrounding, or fresh app start before authentication), `require()` throws
`IllegalStateException("credentials key holder is locked …")`.

That exception escapes the `Flow.map` operator, is carried by the Room coroutine infrastructure
onto the **main-thread Looper** (via the ViewModel's `combine` collector running on
`Dispatchers.Main`), and kills the process.

The locked state is **expected and normal** — it represents the window between app launch and
the user opening the credentials screen for the first time. It is not a corruption or a
programmer error inside `decode`. Crashing in that state is the bug.

---

## Affected files

| File | Change |
|------|--------|
| `core/credentials/src/main/kotlin/lava/credentials/CredentialsEntryRepositoryImpl.kt` | `observe()` now wraps `keyProvider()` in `runCatching`; locked-ISE → emits `emptyList()` + §6.AC `recordWarning`; all other throwables re-thrown |
| `core/credentials/src/test/kotlin/lava/credentials/CredentialsEntryRepositoryImplTest.kt` | New `@Test fun observe emits empty list when key holder is locked — no crash on search path` (line 119) |

---

## Fix description

`CredentialsEntryRepositoryImpl.observe()` (lines 60-89 in the fixed file) now calls
`runCatching { keyProvider() }` once per DAO emission before attempting any decryption:

- **onSuccess**: decrypts all rows as before (`rows.map(::decode)`).
- **onFailure — `isLockedKeyHolderError(t)` returns true** (type is `IllegalStateException` AND
  message starts with `"credentials key holder is locked"`): emits `emptyList()` so downstream
  collectors (ProviderConfigViewModel, CredentialsManagerViewModel) degrade gracefully to
  "no credentials visible"; also calls `analytics?.recordWarning(…)` (§6.AC non-fatal telemetry).
- **onFailure — anything else** (genuine `AEADBadTagException`, corrupted ciphertext, unexpected
  ISE from Room, programmer error inside `decode`): re-throws so the non-fatal channel still
  captures these as actionable issues.

The `isLockedKeyHolderError` predicate matches on type + message prefix (not catch-all ISE) so
unrelated `IllegalStateException`s are never silenced.

---

## Validation test (unit level)

`core/credentials/src/test/kotlin/lava/credentials/CredentialsEntryRepositoryImplTest.kt`
— `observe emits empty list when key holder is locked — no crash on search path` (line 119):

1. A `CredentialsEntryRepositoryImpl` with the real key writes one encrypted row to a `FakeDao`.
2. A second repo instance is constructed with a `lockedKeyProvider` that throws
   `IllegalStateException("credentials key holder is locked — prompt user for passphrase first")`
   — exactly the message `CredentialsKeyHolder.require()` produces.
3. `lockedRepo.observe().first()` is collected on the test thread.
4. Primary assertion (§6.L clause 3 — user-visible state): `result.isEmpty()` is `true` — no
   exception escapes, no crash, empty credentials list returned.

**Falsifiability rehearsal (Seventh Law clause 1):**
Removing the `isLockedKeyHolderError` guard (reverting `observe()` to call `keyProvider()` directly)
causes `observe().first()` to throw `IllegalStateException: credentials key holder is locked…`.
Test fails: `"Expected empty list when key holder is locked, got: <exception>"`.
Mutation reverted → test GREEN.

---

## Challenge Test (end-to-end level)

`app/src/androidTest/kotlin/lava/app/challenges/Challenge47CredentialsLockedSearchTest.kt`

**Status: OWED** — authored in a parallel stream this cycle; the file is not yet present in the
working tree at this closure-log commit. Per §6.O.2 and §6.Z clause 3, the Challenge Test is
required before the distribute for 1.3.11-1073 is marked complete. The unit test above is the
current validation gate; the Challenge Test is the load-bearing end-to-end acceptance gate.

---

## §6.O.5 Console close-mark protocol

Per §6.O clause 5, the Firebase Console close-mark is the LAST step:
1. Fix commit lands with this closure log ✅ (working-tree changes, uncommitted)
2. Challenge C47 authored + executed on the §6.I matrix ← OWED
3. Build 1.3.11-1073 distributed via §6.AA two-stage (debug → operator verify → release)
4. Operator verifies no recurrence in Crashlytics after tester usage
5. Open Firebase Console → Crashlytics → issue `58a1335272bc4ee06595bda6302a670a` → Close issue
   — paste this closure log path in the close-comment:
   `.lava-ci-evidence/crashlytics-resolved/2026-06-24-credentials-keyholder-locked.md`

**DO NOT close-mark before on-device verification of 1.3.11-1073.**

---

## Constitutional bindings

- **§6.O** — closure log, validation test, Challenge Test (OWED) all documented
- **§6.AC** — `recordWarning` added to the locked-path so the operator can observe frequency in production without it being an actionable crash
- **§6.J / §6.L** — fix targets root cause (locked-state throw escaping onto main thread), not symptom; unit test verifies user-visible state (empty list, no crash)
- **§6.T.4** — entry appended to `docs/issues/fixed/BUGFIXES.md`
- **Ships in:** 1.3.11-1073
