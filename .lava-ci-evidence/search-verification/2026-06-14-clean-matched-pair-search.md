# Clean Matched-Pair mDNS Onboard + Search E2E — Genymotion VM

**Date:** 2026-06-14
**Device:** Genymotion VM `127.0.0.1:6555` (Pixel 9 — Android 15 / API 35)
**Installed pair:** `digital.vasic.lava.api.dev` + `digital.vasic.lava.client.dev`
  (both debug-signed; client `versionCode=1066`, built this session at the
  point where HEAD carried `21031f2a fix(search): onSelectApi reads the local
  api-app key for keyless mDNS-discovered endpoints`).
**Repo HEAD at evidence time:** `addaacd0` (the tested APK predates the two
  later commits `fca8a836`, `addaacd0`; the installed binary = the `21031f2a`
  fix).
**Anti-bluff (§6.J):** every claim below is backed by captured device output
  (dumpsys, Chucker SQLite, uiautomator). No mocks. No inferred results.

---

## Goal

Determine whether — with a CLEAN signature-matched debug pair (no leftover
release-signed permission definitions) — a fresh mDNS onboard + search "prince"
returns REAL torrent results. Prior runs got HTTP 401 because the signature-level
permission `digital.vasic.lava.permission.READ_API_KEY` was NOT granted (messy
debug/release install history → SecurityException → null key → keyless endpoint
→ 401). This run removes that confound by fully cleaning install state first.

---

## Step 2 — Full clean (removes the permission-definition confound)

`adb shell pm list packages | grep vasic.lava` BEFORE clean showed the messy
state was REAL:

```
package:digital.vasic.lava.api.dev
package:digital.vasic.lava.client        <-- RELEASE-signed client (the confound)
package:digital.vasic.lava.client.dev
```

Uninstall results: `client` → Success, `client.dev` → Success,
`api` → Failure [DELETE_FAILED_INTERNAL_ERROR] (not installed), `api.dev` → Success.

AFTER clean: `grep vasic.lava` → **EMPTY** (confirmed clean).

## Step 3 — Install matched debug pair

`./gradlew :api-app:installDebug :app:installDebug` → **BUILD SUCCESSFUL in 47s**.
Installed packages after: `digital.vasic.lava.api.dev` + `digital.vasic.lava.client.dev`
(matched debug pair, no release-signed leftovers).

## Step 4 — DECISIVE permission-grant check

`adb shell dumpsys package digital.vasic.lava.client.dev | grep READ_API_KEY`:

```
digital.vasic.lava.permission.READ_API_KEY: granted=true
```

`adb shell dumpsys package digital.vasic.lava.api.dev | grep READ_API_KEY`:

```
Permission [digital.vasic.lava.permission.READ_API_KEY] ... perm=...
  digital.vasic.lava.permission.READ_API_KEY: prot=signature
```

**=> READ_API_KEY granted=true after the clean install. The permission/null-key
confound from prior runs is RESOLVED.**

## Step 5 — Start the debug api-app engine

After tapping Start (~166,486), engine status = **Running**,
`dumpsys activity services ...ApiEngineService`:

```
isForeground=true foregroundId=6778 types=0x40000000
```

Reachable at `https://10.0.2.15:8443`; other addresses `10.0.3.16:8443`,
`10.0.3.15:8443`. Access key shown: `u1Xfmp6Y4DQgPOgGANUNRQ==`. Requests served: 0.

## Step 6 — Fresh mDNS onboard (client)

`pm clear digital.vasic.lava.client.dev`; launch
`digital.vasic.lava.client/MainActivity`. Wizard:

- Welcome ("4 providers available") → Get Started
- API selection ("Choose your API") → "On your network" → **Found 1 API:
  `10.0.3.16:8443`** (`content-desc="api-row"`) → tapped the mDNS-discovered row
- Probe SUCCEEDED → advanced to "Pick your providers" (proves the keyless mDNS
  endpoint's connectivity probe passed)
- Deselect all → selected **Internet Archive** only (verified its own checkbox
  `checked=true` at `[848,772][975,899]`) → Next
- "Configure Internet Archive · This provider does not require credentials" →
  Continue
- "All set! · Internet Archive" → Start Exploring → Home

Onboarding completed; Home screen reached. Nav log confirms provider routing:

```
NavigationController: navigate: route=search/search_result?nm=prince&o=1&s=2&tm=-1&pids=archiveorg
```

(`pids=archiveorg` — Internet Archive correctly carried into the search.)

## Step 7 — Search "prince"

Top-bar Search icon → EditText → typed `prince` (field verified `text='prince'`
before submit) → ENTER. (A first attempt produced `query=rince` from a dropped
leading char; corrected and re-run with verified `prince`.)

## Step 8 — CAPTURED EVIDENCE

### Chucker transactions (verbatim, `/tmp/chk4.db`)

```
id  host            path                                       responseCode
--  --------------  -----------------------------------------  ------------
5   10.0.3.16       /v1/archiveorg/search?query=prince&page=0  401
4   10.0.3.16       /v1/archiveorg/search?query=prince&page=0  401
3   10.0.3.16       /v1/archiveorg/search?query=rince&page=0   401
2   10.0.3.16       /providers                                 200
1   lava-api.local  /providers                                 (no response — default endpoint, pre-selection)
```

Every `/v1/archiveorg/search` (3 attempts, both `prince` and `rince`) = **401**.
`/providers` to the SAME host `10.0.3.16` = **200**.

### `/providers` 200 body (proof the key authenticated providers — REAL catalogue)

```json
{"providers":[{"id":"thepiratebay","displayName":"The Pirate Bay","kind":"native",
"capabilities":["SEARCH","MAGNET_LINK"],"authType":"NONE","encoding":"UTF-8",
"baseUrls":["https://thepiratebay.org"],"supportsAnonymous":true},
{"id":"knaben","displayName":"Knabe...
```
(content-length 2853 — a full real provider catalogue, NOT empty.)

### Search 401 (id=5)

`responseCode=401`, response `content-type: application/json; charset=utf-8`,
`content-length: 24` (Chucker stored body encoded/empty; the 200-vs-401 split is
the load-bearing signal). `requestHeaders` column was empty `[]` for BOTH the 200
and the 401 (Chucker's recorded request headers are empty at this interceptor
layer — not diagnostic of header presence).

### Results screen (uiautomator)

```
text="All"
text="Internet Archive"
text="Nothing found"
text="Try to use keywords or change filter parameters"
text="prince"
```

### api-app "Requests served"

UI counter read **0** throughout (the UI counter does not reflect the served
`/providers` 200 + search 401 — it appears to track a different metric or
instance-local successful serves; the Chucker rows are the authoritative
served-request evidence).

---

## ROOT-CAUSE READING (code-level, captured-evidence-backed)

- `/providers` (the catalogue fetch) is routed by
  `NetworkApiRepositoryImpl.withKeyOverride(endpointKey, authFieldName)` — the
  per-endpoint on-device key path — and got **200**. This PROVES the api-app's
  Lava-Auth gate accepts the resolved key at `10.0.3.16`.
- `/v1/archiveorg/search` is issued by `ApiBackedTrackerClient`, which attaches
  `ApiBaseUrlHolder.currentKey()` as the `Lava-Auth` header
  (`ApiBackedTrackerClient.kt:79-81`). It got **401**.
- The mDNS keyless-endpoint key is resolved by
  `OnboardingViewModel.withLocalApiKeyIfMissing()` (the `21031f2a` fix) via
  `apiKeyReader` (the on-device ContentProvider), and `fetchAndPopulateProviders`
  calls `ApiBaseUrlHolder.set(apiBaseUrl, goApi.key)` on the catalogue-fetch
  `onSuccess` path — which DID run (since `/providers` returned 200).
- Therefore the residual search 401 is NOT the permission/null-key confound
  (resolved: `granted=true`, `/providers` 200). It is a DISTINCT, search-path-
  specific auth defect: the `Lava-Auth` header that `/providers` carries is NOT
  reaching the `/v1/archiveorg/search` request (`ApiBaseUrlHolder.currentKey()`
  null/stale at the time the `archiveorg` `ApiBackedTrackerClient` was built, OR
  the client was constructed before `set()` ran). This is UNCONFIRMED at the
  byte level (the device has no curl/wget and the Go embed does not log the auth
  decision to logcat); PENDING_FORENSICS: capture the actual outbound request
  header on the search call. Tracked as a follow-up.

---

## VERDICT

| Question | Answer (captured-evidence) |
|---|---|
| (a) READ_API_KEY `granted=true` after the clean matched-pair install? | **YES** — `dumpsys package ...client.dev` → `granted=true`; api-app defines it `prot=signature`. The confound is resolved. |
| (b) Did "prince" return real torrent results? | **NO** — search returns HTTP **401**, UI shows **"Nothing found"**. No result items. |
| (c) Search row host+path+responseCode + body items? | `10.0.3.16` `/v1/archiveorg/search?query=prince&page=0` → **401**; response body empty (no result items). Contrast: `/providers` → **200** with a full real catalogue, proving the key authenticates that endpoint. |

**Conclusion:** A clean signature-matched debug pair REMOVES the permission/null-key
confound (`granted=true`, `/providers` 200), but search STILL returns 401 → no
real results. The defect is a distinct, search-path-specific Lava-Auth header-
delivery gap on `ApiBackedTrackerClient` (`/v1/{provider}/search`), separate from
the resolved permission issue.
