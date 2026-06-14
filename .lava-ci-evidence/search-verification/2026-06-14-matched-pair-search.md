# Matched-pair fresh-onboard + multi-provider search — on-device verification

- **Date:** 2026-06-14
- **Device:** Genymotion VM — Pixel 9 / Android 15 (`adb 127.0.0.1:6555`)
- **Fix under test:** commit `d05bc71e` — `fix(search): root-cause multi-layer search failure`
- **Client:** `digital.vasic.lava.client.dev` v1.3.9 (debug, signer `c6515422`)
- **API app:** `digital.vasic.lava.api.dev` v0.2.8-equiv (debug, signer `c6515422`) — installed via `./gradlew :api-app:installDebug`

## VERDICT: NO — "prince" did NOT return torrent results. Search row = `10.0.3.16 /v1/archiveorg/search?query=prince&page=0` → **HTTP 401**. Screen = "Nothing found".

The matched-pair signing was corrected (see below) and `/providers` now returns 200, but the
**onboarding API-selection path used (the mDNS "On your network" row) builds a key-LESS endpoint**,
so every `/v1/{provider}/search` is rejected 401. This is a real defect in the fix, not a test-setup error.

## Steps performed

1. `export ANDROID_SERIAL=127.0.0.1:6555 LAVA_REAL_DEVICE_SERIALS=127.0.0.1:6555`.
2. Initial state: only the RELEASE api-app `digital.vasic.lava.api` (v0.2.8) was installed — confirms the
   prior 401 was from a signature-mismatched pair (debug client could not read the release key provider).
3. `./gradlew :api-app:installDebug` first FAILED with `INSTALL_FAILED_DUPLICATE_PERMISSION`
   (both api-apps declare global `digital.vasic.lava.permission.READ_API_KEY`). Resolved by
   `adb uninstall digital.vasic.lava.api`, then `installDebug` → **BUILD SUCCESSFUL**, package
   `digital.vasic.lava.api.dev` present.
4. Started the debug engine: `am start -n digital.vasic.lava.api.dev/lava.api.app.MainActivity`, tapped Start.
   Verified `dumpsys activity services …/ApiEngineService` → `isForeground=true foregroundId=6778 types=0x40000000`,
   UI status "Running", access key shown on screen. Device IPs: 10.0.2.15 / 10.0.3.16 / 10.0.3.15.
5. Fresh onboard: `pm clear digital.vasic.lava.client.dev` → launch → Welcome → Get Started →
   **API selection discovered "Found 1 API: 10.0.3.16:8443" (Android device, mDNS `_lava-api-dev._tcp`)** →
   selected that row → connectivity probe PASSED → Providers (Deselect all, selected Internet Archive, a
   no-auth provider) → Configure ("does not require credentials", Continue) → "All set!" → Start Exploring → Home.
6. Search: opened the search bar, typed `prince`, KEYCODE_ENTER, waited.
7. Captured `databases/chucker.db(+wal)` via `run-as` and queried.

## Chucker transaction table (verbatim)

```
id|host|path|code
5|10.0.3.16|/v1/archiveorg/search?query=prince&page=0|401
4|10.0.3.16|/v1/archiveorg/search?query=price&page=0|401
3|10.0.3.16|/v1/archiveorg/search?query=pince&page=0|401
2|10.0.3.16|/providers|200
1|lava-api.local|/providers|   (UnknownHostException — pre-select default, expected)
```

(ids 3/4 "pince"/"price" are IME-dropped-first-char artifacts of `adb input text`; id 5 is the clean
`query=prince`. All search rows — regardless of query text — return 401, so the query corruption is cosmetic;
the auth failure is the real and stable blocker.)

## Search row analysis (id=5, `query=prince`)

- **host + path + code:** `10.0.3.16` `/v1/archiveorg/search?query=prince&page=0` → **401**
- **Lava-Auth present on the search request:** **CANNOT be confirmed from Chucker** — Chucker recorded
  `requestHeaders = []` for the search row (the `AuthInterceptor` adds the header *after* Chucker in the
  OkHttp chain, so Chucker never sees it). Presence/absence is therefore UNCONFIRMED via Chucker.
- **responseHeaders:** clean JSON 401 — `content-type: application/json; charset=utf-8`, `content-length: 24`.
- **Results screen state:** "Nothing found" / "Try to use keywords or change filter parameters" (NO torrent rows).

## Root cause (captured-evidence, from source `feature/onboarding/.../OnboardingViewModel.kt`)

The 401 is NOT a signing mismatch (signers now match: client.dev `c6515422` == api.dev `c6515422`, and
`/providers` returns 200, proving the pair is reachable and unauthenticated endpoints work). It is the
**key-less-endpoint** failure the fix commit itself flagged as KNOWN-REMAINING, and it ALSO affects the
fresh-onboard mDNS path:

- `onSelectApi(endpoint)` (line ~253) — the handler for the **"On your network" discovered row** — builds
  `Endpoint.GoApi(host, port, platform, storage)` with **NO `key`**. mDNS TXT records do not carry the secret
  key, so a discovered-network endpoint is keyless. `/v1` ops on a keyless endpoint → 401.
- Only `onOnDeviceApiReturned(host, port)` (line ~736) reads the key via the signature-protected
  ContentProvider (`apiKeyReader.invoke(...)`, MainActivity `buildApiKeyReader()` correctly targets
  `digital.vasic.lava.api.dev.keyprovider` in debug) and builds `Endpoint.GoApi(host, port, key = apiKey)`.
- The **"Open Lava API app" → "Back to Lava client" on-device handoff did NOT fire `onOnDeviceApiReturned`**
  on this device: tapping "Back to Lava client" returned to the API-selection screen with no key read, no
  auto-advance, and no `onOnDeviceApiReturned` log line — the host/port handoff extras were not delivered.

So on this device there was **no UI path that delivered a keyed endpoint to search**: the discovered-network
row is keyless by construction, and the on-device key-bearing handoff return does not fire.

## Matched-pair correctness confirmed (the part of the fix that IS working)

- Both APKs signed `c6515422` (matched debug pair).
- `/providers` → 200 (reachability + unauthenticated route OK).
- Search now targets `10.0.3.16` (the chosen API), no longer `lava-api.local` — the commit's "settings.endpoint
  persist" fix (cascade bug #1) IS verified working on-device.
- The `/v1/{provider}/search` route is served (returns 401, not 404) — the commit's "dead /v1/search route"
  fix (cascade bug #2) IS verified working on-device.

## Outstanding defect

Fresh onboard via the **mDNS discovered-network row** produces a key-less `Endpoint.GoApi` → all searches 401.
The fix commit claimed "Fresh onboard flows the key correctly", but that holds only for the on-device
ContentProvider handoff path — and that handoff's return-to-client did not trigger the key read here. The
discovered-network onboarding path needs to obtain the per-instance key (e.g. via the same signature-protected
ContentProvider when the discovered API is on-device, `platform=android`) before `/v1` search can succeed.
