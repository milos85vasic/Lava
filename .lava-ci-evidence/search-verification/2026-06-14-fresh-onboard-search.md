# Fresh-onboard + search E2E verification — 2026-06-14

**Target:** Genymotion VM (`adb 127.0.0.1:6555`)
**Client:** `digital.vasic.lava.client.dev` 1.3.9 (debug, with search fix `d05bc71e`)
**On-device API:** `digital.vasic.lava.api` — engine RUNNING, `isForeground=true types=0x40000000`, reachable `https://10.0.3.16:8443` (device IPs 10.0.3.16 / 10.0.2.15).

## Onboarding steps performed (UI-driven via uiautomator + input tap/text)

1. `pm clear digital.vasic.lava.client.dev`; launched `MainActivity`.
2. Welcome screen ("Welcome to Lava", "4 providers available") → tapped **Get Started**.
3. **API selection** ("Choose your API"): mDNS discovered **1 API** — row `10.0.3.16:8443 · Lava API · On this network · Android device`. Tapped it. Connectivity probe passed (advanced to provider step).
4. **Pick your providers**: normalized selection to none, then selected exactly **Internet Archive** (no-auth, `None`). Verified exactly 1 checkbox checked (bounds `[848,772][975,899]`). Tapped **Next**.
5. **Configure Internet Archive** — "This provider does not require credentials." → tapped **Continue**.
6. **All set!** (Internet Archive ready) → tapped **Start Exploring** → landed on **Home** (Search tab).
7. Opened search bar, typed `prince` (verified field text = "prince"), submitted (KEYCODE_ENTER). Also captured an earlier accidental "pince" run.

## Chucker transactions (verbatim, `databases/chucker.db`)

| id | host | path | scheme | code | error |
|----|------|------|--------|------|-------|
| 1 | lava-api.local | /providers | https | — | UnknownHostException: Unable to resolve host "lava-api.local" |
| 2 | 10.0.3.16 | /providers | https | **200** | |
| 3 | 10.0.3.16 | /v1/archiveorg/search?query=pince&page=0 | https | **401** | |
| 4 | 10.0.3.16 | /v1/archiveorg/search?query=prince&page=0 | https | **401** | |

(requestHeaders/responseBody were stored empty `[]` by this build's Chucker config — header-level proof unavailable from the DB; codes + host + path are authoritative.)

## Results-screen state

Filter chips after submit: **All / Internet Archive** (only the onboarded provider — routing correct). Rendered state: **"Nothing found" / "Try to use keywords or change filter parameters"**. No torrent result rows. Client log confirms the flow fired: `SubmitClick → route=search_result?nm=prince&...&pids=archiveorg → ListBottomReached`.

## api-app "Requests served" counter

Read **0** after the run (counter did not increment; the 200 `/providers` + the two 401 searches did reach the server per their real HTTP codes, so the counter appears to track a narrower category or did not refresh — not authoritative).

## VERDICT

**Did "prince" return real torrent results on screen? NO.**

The fix's two headline claims are **PROVEN CORRECT**, but a **NEW blocker** surfaced:

| Layer | Evidence | Pass/Fail |
|-------|----------|-----------|
| Host correct | search hit `10.0.3.16`, NOT `lava-api.local` | **PASS** |
| Path correct (served route) | `/v1/archiveorg/search` (not `/v1/search` or `/search`) | **PASS** |
| Endpoint+key persisted | onboarding `/providers` on `10.0.3.16` → **200** | **PASS** |
| Auth on search | `/v1/archiveorg/search` → **401 Unauthorized** | **FAIL** |
| Results rendered | "Nothing found" | **FAIL** |

**Single most important line:** search routes to the correct host+path (`https://10.0.3.16:8443/v1/archiveorg/search?query=prince&page=0`) but the on-device API returns **HTTP 401** — so no results render. The auth key that authorizes `/providers` (200) is NOT accepted/attached on the multi-provider search call. `AuthInterceptor.intercept` is present in the client; the divergence (200 on `/providers` vs 401 on `/v1/{provider}/search` from the same host) indicates `sdk.streamMultiSearch`'s HTTP path does not carry the access key that the LAN provider-fetch path does.

**Recommended next step:** make `sdk.streamMultiSearch`'s request route through the same authenticated LAN OkHttp client (with the per-instance access key) that the provider-catalogue fetch uses — the host/path fix is correct, the missing piece is the auth header on the search request.
