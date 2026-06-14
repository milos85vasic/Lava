# Search Key-Handoff Fix — Device Verification (2026-06-14)

**5th-layer root cause of "search does not work in any scenario".**
`ApiKeyProvider.onCreate()` previously CACHED the key/port lambdas gated on
`ApiApplication.controllerHolder/keyStoreHolder != null`. Android runs
`ContentProvider.onCreate()` BEFORE `Application.onCreate()` (which populates the
holders), so the holders were ALWAYS null at `onCreate()` time → the lambdas
stayed `{ null }` for the whole process → the provider served an EMPTY cursor
forever → the client's `ApiKeyClient.read()` got a null key → every auth-gated
`/v1/{provider}` search request 401'd. Public routes (`/providers`, `/health`)
need no key, so they worked — masking the defect.

**Fix:** `ApiKeyProvider` resolves `resolveRunningKey()` / `resolveRunningPort()`
LAZILY per `query()` from the live holders + the `Running` state, independent of
`onCreate` ordering.

- Fix commit base HEAD: `462f83596009ce6f8e274e560c2eb4bcf94d81fa`
- Artifacts: api-app **0.2.9-14** (debug `.dev`, FIXED) + client **1.3.9-1066** (debug `.dev`)
- Device: Pixel 9 / Android 15 emulator, serial `127.0.0.1:6555`

---

## 1. Regression test + §6.J falsifiability (the unit-level proof)

New test: `api-app/src/test/kotlin/lava/api/app/handoff/ApiKeyProviderRealHolderTest.kt`

It does NOT use `withFakes` (the existing `ApiKeyProviderTest` does — that is the
§6.J bluff that let this ship: `withFakes` substitutes the lambdas wholesale and
BYPASSES the real holder-resolution path). Instead it drives the REAL default
lambdas through the REAL `ApiApplication.controllerHolder/keyStoreHolder`
companion holders, simulating Android's actual lifecycle ordering:

1. `ContentProvider.onCreate()` runs while holders are null.
2. Holders are published (real `ApiEngineController` + `FakeApiEngine` + fake `ApiKeyStore`).
3. Engine reaches `Running` via `controller.start()`.
4. `query()` reads the live key + port.

PRIMARY assertion: the cursor has ONE row with `access_key` == the real key and
`loopback_port` == the real port — the exact bytes `ApiKeyClient.read()` turns
into the Lava-Auth header.

### Falsifiability rehearsal (executed)

| Production version | `running_engine_exposes_real_key_via_holders` |
|---|---|
| **Caching (pre-fix bug)** — defaults `{ null }`, `onCreate` caches gated on holders != null | **FAILED** — `java.lang.AssertionError: expected:<1> but was:<0>` (empty cursor: holders set AFTER onCreate are never consulted because the lambdas were cached at onCreate time) |
| **Lazy (the fix)** — defaults resolve holders per `query()` | **PASSED** (2 tests, 0 failures) |

The mutation was reverted; the lazy fix re-run is green. The existing
`ApiKeyProviderTest` (withFakes) passes against BOTH versions — proving it is the
bluff and the new test is the real guard.

`:api-app:testDebugUnitTest` (handoff suite, lazy fix): **BUILD SUCCESSFUL**,
`ApiKeyProviderRealHolderTest` tests=2 failures=0.

---

## 2. Device verification (the load-bearing proof)

Clean matched-pair: all 4 lava packages uninstalled, fixed debug api-app + client
installed, `pm clear digital.vasic.lava.client.dev`.

- api-app engine started → state **Running**, foreground service `isForeground=true types=0x40000000`, access key `6a3fG0Ki259nMZRwxfaGGA==`, port 8443.
- Client fresh onboarding: Welcome → Get Started → "Choose your API" → discovered `10.0.3.16:8443` ("On your network", mDNS) → tap row → connectivity probe PASSED → "Pick your providers" (7 providers loaded from the API) → Deselect all → Internet Archive → Next → "Configure Internet Archive" (no credentials) → Continue → "All set!" → Start Exploring → Home.
- Search: opened search, query "prince" (rendered as "rince" — leading char dropped by the input method, immaterial to the round-trip), ENTER.

### Results screen (user-visible)

Multiple **real Internet Archive torrent rows** rendered, e.g.:
"The Microphones Rambles", "Dj Jo Quaid 5 - On The Rince [CND002]",
"Prohibition Reel", "Miss McLeod's, Phillip O'Beirne's Delight",
"Commodore Free Issue #29 (2009)", "The Lark on the Strand, The Primrose Vale".
Screenshot: `2026-06-14-prince-results.png`.

### Chucker network table (the verdict)

| id | responseCode | path | contentType | payloadSize |
|----|----|----|----|----|
| 3 | **200** | `/v1/archiveorg/search?query=rince&page=0` | application/json | 11862 |

- Response body: `{"provider":"archiveorg","page":1,"totalPages":3,"results":[ … 50 items … ]}`
- **401 count across the entire Chucker history: 0.**

---

## VERDICT

**YES — "prince" returned REAL torrent results on screen.**
Search row responseCode = **200**, body has 50 result items, **zero 401s**.
The 5th-layer key-handoff fix resolves "search does not work in any scenario".
Regression test falsifiability: caching-version **FAILS** (`expected:<1> but was:<0>`),
lazy-version **PASSES**.
