# Final matched-pair e2e search verification — "prince" via fresh mDNS onboard

- **Date:** 2026-06-14
- **HEAD:** `21031f2a0e244e202bffc1670bbbd55d0e9e2182`
- **Device:** Genymotion VM "Pixel 9 - 15" (Android 15 / API 35), adb `127.0.0.1:6555`
- **Pair:** matched DEBUG client `digital.vasic.lava.client.dev` + DEBUG api-app `digital.vasic.lava.api.dev` (same debug keystore)
- **api-app engine:** RUNNING — `dumpsys ApiEngineService` → `isForeground=true foregroundId=6778 types=0x40000000`. Device IPs `10.0.2.15`, `10.0.3.15`, `10.0.3.16`. Access key shown in api-app UI: `GvTBuSE3MG+esce6IN48lg==`.

## VERDICT

**NO — "prince" returned NO real torrent results.** The results screen rendered **"Nothing found"** (no torrent titles / result rows). The search HTTP call returned **401 Unauthorized** with an empty body. The two key-delivery fixes did NOT fix the matched-pair search on a DEBUG build.

- Search row: **host `10.0.3.16`, path `/v1/archiveorg/search?query=prince&page=0`, responseCode `401`**, response body **empty** (no result items). `content-length: 24` on the 401.
- Results screen state: **"Nothing found" / "Try to use keywords or change filter parameters"** — zero result rows.
- api-app **"Requests served: 0"** (the search was rejected at the auth layer; the served counter never incremented).

## Steps executed

1. `installDebug` of the fixed client → `Installed on 1 device. BUILD SUCCESSFUL`. Confirmed `digital.vasic.lava.client.dev` present.
2. Confirmed DEBUG api-app engine RUNNING (foreground service, `types=0x40000000`).
3. `pm clear digital.vasic.lava.client.dev` → fresh onboard. Welcome → Get Started.
4. **API selection screen** showed the mDNS-discovered row: `10.0.3.16:8443` — "Lava API · On this network · Android device" under "On your network" (`content-desc="api-row"`). Tapped it → **connectivity probe PASSED** → advanced to "Pick your providers" (probe would have blocked on 401 pre-fix; it now connects — the host/route fix works).
5. Providers: Deselect all, selected only **Internet Archive** (checkbox confirmed `checked="true"`), Next.
6. Configure Internet Archive ("does not require credentials") → Continue → "All set!" (IA configured) → Start Exploring → Home.
7. Opened search (top-right search icon), typed `prince`, KEYCODE_ENTER, waited ~12s.

## Verbatim Chucker transaction table (`/tmp/chk3.db`)

```
id|host         |path                                          |responseCode|err
4 |10.0.3.16    |/v1/archiveorg/search?query=prince&page=0     |401         |
3 |10.0.3.16    |/v1/archiveorg/search?query=rince&page=0      |401         |   (first attempt; "p" dropped by input race — retried as id 4)
2 |10.0.3.16    |/providers                                    |200         |
1 |lava-api.local|/providers                                   |            |java.net.UnknownHostException:
```

Search row (id 4): `responseCode=401`, `responseBody=` (empty). Response headers: `content-type: application/json; charset=utf-8`, `content-length: 24`.

Note: `/providers` (id 2) at `10.0.3.16` returned **200** during onboarding, but `/v1/archiveorg/search` (id 4) returns **401** — so the endpoint is reachable; only the authenticated search route fails. Request headers are recorded empty `[]` by Chucker (captured before the auth interceptor stage).

## ROOT CAUSE (next-layer fix)

The new key-delivery helper reads the **RELEASE** ContentProvider authority on a **DEBUG** build.

`feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingViewModel.kt`:

```kotlin
// line 382 (onSelectApi → withLocalApiKeyIfMissing) AND line 772 (startup variant)
apiKeyReader?.invoke(AppLinkContract.API_RELEASE_PACKAGE + ".keyprovider")
```

This hardcodes the release suffix `.keyprovider`, querying `digital.vasic.lava.api.keyprovider`. But the running api-app is the DEBUG variant whose ContentProvider authority is `digital.vasic.lava.api.dev.keyprovider` (per `api-app/.../handoff/ApiKeyProvider.kt:34-35`). The query hits a non-existent authority → returns null → endpoint stays **keyless** → search 401.

The correct DEBUG-aware suffix already exists elsewhere and must be applied here too:

- `app/.../MainActivity.kt:224` — `val suffix = if (BuildConfig.DEBUG) ".dev.keyprovider" else ".keyprovider"`
- `app/.../StartupProvidersModule.kt:74` — same pattern.

**Fix:** make `withLocalApiKeyIfMissing()` (and the startup variant at line 772) use the same `BuildConfig.DEBUG ? ".dev.keyprovider" : ".keyprovider"` suffix, instead of the hardcoded `".keyprovider"`. After that, the matched DEBUG pair's per-instance key (`GvTBuSE3MG+esce6IN48lg==`) will be read from the correct debug authority and attached to the search request.

Captured device evidence only — no bluffs.
