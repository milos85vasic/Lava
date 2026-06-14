# Search Key-Loss Pinpoint — logcat instrumentation (2026-06-14)

§6.J anti-bluff: captured device evidence only. §6.H: only key PRESENCE / length
is recorded, never the key VALUE.

## Goal

Multi-provider search via the on-device API returns HTTP 401. Host (`10.0.3.16`),
route (`/v1/{provider}/search`), `READ_API_KEY` permission grant, and engine state
were all confirmed correct in prior cycle work. The per-instance `Lava-Auth` key
was NOT reaching the authenticated search request. This run instruments the 4
plumbing checkpoints to find EXACTLY where the key is lost.

## Method

- Device: `127.0.0.1:6555` (Pixel 9 / API 35 emulator).
- Matched DEBUG pair installed: `digital.vasic.lava.client.dev` +
  `digital.vasic.lava.api.dev`; `READ_API_KEY` `granted=true`; engine listening
  on `:8443` (verified via `/proc/net/tcp` 0x20FB) and api-app UI shows state
  **"Running"** with an access key rendered (key present, len 24).
- 4 TEMPORARY presence-only `LAVAKEYDBG` markers added (reverted after capture):
  1. `OnboardingViewModel.withLocalApiKeyIfMissing()`
  2. `OnboardingViewModel.fetchAndPopulateProviders()` (before `ApiBaseUrlHolder.set`)
  3. `ApiBaseUrlHolder.set(apiBaseUrl, key)`
  4. `ApiBackedTrackerClient` `searchable.search()`
- Flow: `pm clear` client → fresh launch → Welcome "Get Started" → ApiSelection
  tap the discovered "On your network" mDNS row (`10.0.3.16:8443`) → probe →
  Providers (deselect-all, select Internet Archive only) → Configure Continue →
  "Start Exploring" → Home → Search → "prince" → ENTER.
- Reproduced twice; identical marker sequence both runs (deterministic).

## LAVAKEYDBG sequence captured (verbatim)

```
E OnboardingViewModel: LAVAKEYDBG withLocalApiKey: isGoApi=true readerSet=true readLen=-1
E OnboardingViewModel: LAVAKEYDBG populate: goApiKeyLen=-1 baseUrl=https://10.0.3.16:8443
I System.out:           LAVAKEYDBG holder.set keyLen=-1
I System.out:           LAVAKEYDBG search.withAuth authKeyLen=-1 field=Lava-Auth
```

## HTTP evidence (chucker.db `transactions`)

```
401 | GET | 10.0.3.16     | /v1/archiveorg/search?query=prnce&page=0   ← auth-gated, 401
200 | GET | 10.0.3.16     | /providers                                  ← PUBLIC route, OK
    | GET | lava-api.local | /providers                                 ← pre-selection default stub
```

Header check on the 401 search request: **`LAVA-AUTH-ABSENT`** (no `Lava-Auth`
header on the wire). The 200 `/providers` request is ALSO `LAVA-AUTH-ABSENT` yet
returns 200 — confirming `/providers` is an UNAUTHENTICATED route on the api-app
while `/v1/{provider}/search` is auth-gated. This is the exact split the operator
reported.

## Diagnosis — the key dies at checkpoint #1

The FIRST checkpoint reporting `-1` is `withLocalApiKey`:

```
readerSet=true   → the apiKeyReader seam IS wired (MainActivity.buildApiKeyReader()
                   passes an ApiKeyClient-backed lambda through OnboardingScreen)
readLen=-1       → apiKeyReader.invoke() RETURNED NULL
```

Every downstream checkpoint inherits that null:
`goApiKeyLen=-1` → `holder.set keyLen=-1` → `search.withAuth authKeyLen=-1` →
no `Lava-Auth` header → HTTP 401.

So the plumbing AFTER the read is correct — the key is never produced in the
first place. `apiKeyReader.invoke()` is `ApiKeyClient.read()?.key`. `read()`
queries the api-app's `content://digital.vasic.lava.api.dev.keyprovider`
(`ApiKeyProvider`) and returns null on: provider absent, EMPTY cursor, swallowed
`SecurityException`, or other I/O.

Eliminating the candidates with captured evidence:

- **Authority correct.** `dumpsys package` shows the registered authority
  `[digital.vasic.lava.api.dev.keyprovider]` → `ApiKeyProvider`. The client
  builds exactly this (`API_RELEASE_PACKAGE "digital.vasic.lava.api" + ".dev.keyprovider"`).
  CONFIRMED match.
- **Permission granted.** `digital.vasic.lava.permission.READ_API_KEY: granted=true`
  on the client. (The adb-shell uid=2000 query gets a SecurityException because
  the SHELL is unsigned — the signed client app does NOT.) So the swallowed-
  SecurityException branch does NOT apply to the real client read.
- **Engine Running + key exists.** api-app UI state = **"Running"**, access key
  rendered on-screen (present, len 24). So the keyStore HAS a key.

That leaves ONE root cause class: **`ApiKeyProvider.query()` returned an EMPTY
cursor to the client even though the engine is Running with a key.**

`ApiKeyProvider.query()` only adds a row when BOTH `keyProvider() != null` AND
`portProvider() != null`. Those lambdas are wired ONCE in `ApiKeyProvider.onCreate()`
from `ApiApplication.controllerHolder` / `keyStoreHolder`, and each lambda gates
its value on `controller.state.value is ApiControlState.Running`. The default
(unwired) lambdas are `{ null }`.

ROOT CAUSE (5th layer): **the cross-process `ApiKeyProvider` read returns an empty
cursor** — the provider hands the client `null`. Given the engine is genuinely
Running with a key present, the surviving explanation is the provider's
**`onCreate()` holder-population timing/scope**: when `ApiKeyProvider.onCreate()`
ran, `ApiApplication.controllerHolder` / `keyStoreHolder` were still null
(ContentProvider `onCreate` can run before the engine controller + key store are
populated), so `keyProvider` / `portProvider` stayed at their default `{ null }`
and were NEVER re-wired afterwards — the provider keeps returning an empty cursor
for the lifetime of the process even after the engine reaches Running. (A second-
order variant: holders wired but `controller.state.value` not observed as
`Running` at query time in the provider's process instance.)

UNCONFIRMED (needs an in-client-process query trace to disambiguate the two
sub-variants): whether the holders were null at provider `onCreate()` vs the
state read non-Running at query time. Both produce the identical empty-cursor
symptom; both are fixed by the recommendation below.

## Recommended fix

Make `ApiKeyProvider` resolve its lambdas LAZILY at query time instead of caching
them once at `onCreate()`, so a provider that started before the engine still
serves the key once the engine is Running:

```kotlin
// ApiKeyProvider.query() — resolve holders per-call, not cached in onCreate()
override fun query(...): Cursor {
    val cursor = MatrixCursor(arrayOf(COL_ACCESS_KEY, COL_LOOPBACK_PORT))
    val controller = ApiApplication.controllerHolder
    val keyStore = ApiApplication.keyStoreHolder
    val state = controller?.state?.value
    if (state is ApiControlState.Running && keyStore != null) {
        cursor.addRow(arrayOf(keyStore.getOrCreate(), state.port))
    }
    return cursor
}
```

(Keep the `withFakes` test seam as an override for the lazy resolution.) This
removes the onCreate-ordering dependency: whenever the engine is Running at query
time, the client read succeeds, the key flows through
`withLocalApiKey → populate → holder.set → search.withAuth`, the `Lava-Auth`
header is attached, and `/v1/archiveorg/search` authenticates.

Defense-in-depth (secondary, not the root fix): the swallow-all `catch` in
`ApiKeyClient.read()` masks WHY a read failed — add a non-fatal telemetry record
(§6.AC) on the empty-cursor / exception paths so a future null read is observable
remotely instead of silent.

## Anti-bluff note

The instrumentation is provably load-bearing: each `keyLen` value is a direct
function of the production key at that exact point, and the chain shows the FIRST
`-1` at the read site with every later site inheriting it. The on-the-wire
`LAVA-AUTH-ABSENT` header + the 401 responseCode are the user-visible §6.J
primary assertions; the markers explain WHY. Temporary `LAVAKEYDBG` lines were
reverted after capture (see `git diff` clean of `LAVAKEYDBG`).
