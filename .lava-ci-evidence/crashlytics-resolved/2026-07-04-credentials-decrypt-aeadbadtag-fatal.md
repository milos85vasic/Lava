# Crashlytics closure — CredentialsCrypto.decrypt AEADBadTagException FATAL

**Issue ID:** `4e11da491ed4854f8d6390e53a09d91f`
**Console:** https://console.firebase.google.com/v1/appid/project/lava-vasic-digital/crashlytics/app/1:815513478335:android:456475e2ef4039d8cfd20a/issues/4e11da491ed4854f8d6390e53a09d91f
**Error type:** FATAL · **firstSeen/lastSeen:** 1.3.12 · **events/users:** 2 / 1 · **state:** OPEN (close-mark owed after this fix ships)
**App:** `digital.vasic.lava.client` (release) — `1:815513478335:android:456475e2ef4039d8cfd20a`

## Stack-trace summary

```
javax.crypto.AEADBadTagException: error:1e000065:Cipher functions:OPENSSL_internal:BAD_DECRYPT
  at lava.credentials.crypto.CredentialsCrypto.decrypt   (CredentialsCrypto.kt:56 — cipher.doFinal(ct))
  ← lava.credentials.CredentialsEntryRepositoryImpl.decode (CredentialsEntryRepositoryImpl.kt:134)
  ← observe(): dao.observeAll().map { rows.map(::decode) }   (Flow.map operator)
  ← collected by ProviderConfigViewModel / CredentialsManagerViewModel → main-thread Looper → FATAL
```

## Root-cause analysis (CONFIRMED — code read + reproduce-first)

`CredentialsCrypto.decrypt` calls `cipher.doFinal(ct)` on an AES-GCM ciphertext. When a
stored credential row's ciphertext cannot be authenticated (wrong derived key OR corrupted /
tampered bytes), GCM tag verification fails and throws `AEADBadTagException` (a
`java.security.GeneralSecurityException` subclass).

`CredentialsEntryRepositoryImpl.observe()` already had a 1.3.10 fix that gracefully handles the
*locked-key-holder* `IllegalStateException` (emit empty list). But it **deliberately let
`AEADBadTagException` propagate**, with a code comment ASSUMING "a ViewModel coroutine-scope
handler would turn it into a non-fatal." The Crashlytics record proves that assumption FALSE —
it surfaced as a real **FATAL** app crash on device (1.3.12). `decode()` had no per-row guard, so
a single undecryptable row crashed the whole app (and would have hidden every other valid
credential even if it hadn't crashed).

This is the §6.J class: a documented assumption ("this becomes a non-fatal") that was never
verified against a real device, and was wrong.

## Fix

`CredentialsEntryRepositoryImpl` now decodes each row through `decodeOrNull(entity)`:
`observe()` uses `rows.mapNotNull(::decodeOrNull)` and `get()` uses `?.let(::decodeOrNull)`.
`decodeOrNull` catches **narrowly** — `GeneralSecurityException` (the GCM tag / wrong-key /
tamper failure) and `IllegalArgumentException` (the too-short-payload `require` in
`CredentialsCrypto.decrypt`) — records a §6.AC non-fatal (`recordNonFatal`, no secret material
per §6.H — only row id, operation, error class), and returns `null` so the corrupt row is
skipped. Everything else (locked-holder `IllegalStateException`, the `unknown secret kind`
programmer error, serialization errors) still propagates unchanged — non-corruption bugs are NOT
silently swallowed. This achieves the 1.3.10 author's STATED intent (non-fatal telemetry) without
the FATAL side effect: one bad row can neither crash the app nor hide the others.

## Validation test (reproduce-first, §6.T.1)

`core/credentials/src/test/kotlin/lava/credentials/CredentialsEntryRepositoryImplTest.kt`
→ `observe skips a corrupt-ciphertext row and still returns the valid rows — no crash`

Real stack: real `CredentialsEntryRepositoryImpl` + real `CredentialsCrypto` + real
`Json`; only the Room `CredentialsEntryDao` boundary is a behavioural fake (`FakeDao`). Writes a
valid row and a second row whose stored ciphertext's last byte is flipped (breaking the GCM tag —
exactly the on-device corruption), then asserts `observe().first()` returns only the valid row.

- **RED** (against pre-fix `rows.map(::decode)`): `javax.crypto.AEADBadTagException at CredentialsEntryRepositoryImplTest.kt:175` — 6 tests completed, 1 failed. Reproduces the production FATAL.
- **GREEN** (after fix): `BUILD SUCCESSFUL` — 6 tests pass; corrupt row skipped, valid row survives.
- **§6.AC scanner:** `Kotlin catch blocks lacking telemetry / opt-out: 0` (STRICT) — the new catch blocks record `recordNonFatal`.

## Challenge Test (§6.O clause 2) — OWED, device-gate-blocked

An end-to-end Compose UI Challenge that seeds a corrupt credential row and drives the
provider-config / search screen without crashing is OWED. It is device-gate-blocked on the
current dev host (§6.AH — no in-container KVM). The reproduce-first Integration test above (real
repository + real crypto, only the DAO faked per Second/Fourth Law) is the load-bearing
regression proof until the device Challenge can run on the nezha Linux/KVM gate host.

## Fix commit

This commit (see `git log` for the SHA that adds this log + the fix + the test).

**Console close-mark:** OWED after this fix is distributed and verified — per §6.O clause 5 the
dashboard close-mark happens AFTER the fix ships + test coverage is in place, never before.
