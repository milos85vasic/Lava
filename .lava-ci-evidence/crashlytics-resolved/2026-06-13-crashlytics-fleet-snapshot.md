# Crashlytics Fleet Snapshot — 2026-06-13 (operator continuous-monitoring directive)

Real data pulled via the Firebase MCP (project `lava-vasic-digital`). Window
**2026-05-14 → 2026-06-13** (30 days). Report = `topIssues`, split by error type
(FATAL / ANR / NON_FATAL). Pull timestamp: 2026-06-13T18:34Z.

Current build state at pull time: **client `1.3.6` (code 1063)**, **api-app `2.3.28`
(code 2328)**. This snapshot is ANALYSIS + EVIDENCE ONLY — no production code was
changed (a parallel build was running). Per §11.4.6: proven facts are stated as
facts; anything not directly confirmed is marked `UNCONFIRMED:` / `PENDING_FORENSICS:`.

## Firebase apps enumerated (real app IDs from `firebase_list_apps`)

| Variant | packageName | appId |
|---------|-------------|-------|
| Lava Android (client release) | `digital.vasic.lava.client` | `1:815513478335:android:456475e2ef4039d8cfd20a` |
| Lava Android (Debug) | `digital.vasic.lava.client.dev` | `1:815513478335:android:54ca2ca31e6c4f42cfd20a` |
| Lava API (release) | `digital.vasic.lava.api` | `1:815513478335:android:d57b960e955645f6cfd20a` |
| Lava API (debug) | `digital.vasic.lava.api.dev` | `1:815513478335:android:2932451e07ca80a7cfd20a` |

## Per-variant result

### client RELEASE (`…456475e2…`) — the only variant with data

**ANRs:** NONE in the window (report returned no results). Good.

**FATAL crashes (2 — both OLD, fixed-in-code, NOT recurring on 1.3.x):**

| Issue id | Title / top frame | Events/Users | first=last ver | State | Class |
|----------|-------------------|--------------|----------------|-------|-------|
| `40a62f97a5c65abb56142b4ca2c37eeb` | `MainActivity$onCreate` — `IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported` (painterResource on a `<layer-list>`) | 5 / 2 | 1.2.19 | OPEN | **(b) OLD, fixed-in-code** — §6.Z forensic anchor; closure log `2026-05-14-welcome-layerlist-painter-crash.md`. Console close-mark owed (operator action). |
| `39469d3bc00aabf76a86d5d15f2e7f2b` | `okhttp3.HttpUrl$Builder.parse` — `IllegalArgumentException: Expected URL scheme … no scheme was found for djdnjd…` | 1 / 1 | 1.2.21 | OPEN | **(b) OLD, fixed-in-code** — closure log `2026-05-14-okhttp-url-scheme-djdnjd.md`. Console close-mark owed. |

**NON_FATALs (5):**

| Issue id | Title / top frame | Events/Users | first=last ver | State | Class |
|----------|-------------------|--------------|----------------|-------|-------|
| `7df61fdba64f9928b067624d6db395ca` | `kotlinx.coroutines.JobCancellationException` — "StandaloneCoroutine was cancelled" | 8 / 1 | 1.2.21 | OPEN | **(c→b) telemetry-noise, fixed-in-code** — broad `catch(Exception){recordNonFatal}` recorded normal coroutine cancellation. Closure logs `2026-06-13-jobcancellation-catch-site-rethrow.md` + `2026-05-14-jobcancellation-nonfatal-noise-filter.md` (rethrow CancellationException). Not recurring on 1.3.x. |
| `47b000d54ff647802df7577ca12a1741` | `ProviderCatalogRepository$fetchProviders` — `IllegalStateException: provider discovery failed: HTTP 401 for https://192.168.0.107:8443/providers` | 3 / 1 | **1.3.4** | OPEN | **(a) NEW — see root-cause below.** The next layer after the Defect-A TLS fix; auth-gate 401 on the real-device catalogue fetch. |
| `6519b4906645e4cb269fc80dd5562e34` | `rutracker GetCurrentProfileUseCase.parseUserId` — "user-id not found — guest or stale selectors" | 2 / 1 | 1.2.22 | OPEN | **(b) instrumented telemetry / fixed-in-code** — closure log `2026-06-13-rutracker-parseuserid-guest-page.md`. Not recurring on 1.3.x. |
| `042b9b611cf1521141ec8d31dbc55b74` | `okhttp connectTls` — `CertPathValidatorException: Trust anchor … not found` | 1 / 1 | 1.3.3 | OPEN | **(b) OLD, fixed-in-code** — Defect-A; FIXED at 1.3.4 (commit `0deb54e7`, `ProviderCatalogRepositoryTest` crosses real self-signed TLS). Closure log `2026-06-12-provider-catalog-fetch-tls.md`. Console close-mark owed. |
| `3937b7f08628bce3fd1b1c7064274f76` | `SearchResultViewModel$observeSseSearch` — `IllegalStateException: SSE error … Unable to resolve host "lava-api.local"` | 1 / 1 | 1.3.0 | OPEN | **(b) instrumented telemetry / fixed-in-code** — mDNS host-resolve graceful handling. Closure log `2026-06-13-sse-host-resolve-telemetry-severity.md`. Not recurring on 1.3.x. |

### client DEBUG (`…54ca2ca3…`)
NO data (report returned no results). Debug builds are not distributed to testers —
expected, not a gap.

### api-app RELEASE (`…d57b960e…`)
**HTTP 404** ("Requested entity was not found") — Crashlytics has NO data for this
app yet. HONEST: api-app Crashlytics was only just wired (`:core:analytics-firebase`
→ api-app, commit `07f83eef`) and no Crashlytics-instrumented api-app build has been
distributed. NOT a gap — the wiring is new; the surface will report once distributed.

### api-app DEBUG (`…2932451e…`)
**HTTP 404** — same as api-app release. No Crashlytics data yet (freshly wired,
undistributed). HONEST, not a gap.

## NEW actionable issue — root-cause analysis (grounded in the real stack frame)

### `47b000d54ff647802df7577ca12a1741` — provider catalogue fetch returns HTTP 401

**Real evidence (sample event `6A2D791C…_2229350271126263904`):**
- Device: Samsung **SM-S918B (Galaxy S23 Ultra), Android 16**, release build **1.3.4 (1061)**, ARM64.
- `customKeys.error = provider_catalog_fetch_failed`, `build_type = release`.
- Breadcrumbs: `session_start → screen_view{MainActivity}` (onboarding wizard) on `Firebase Background Thread #0`.
- buildStamp revision `88db9b744c8f6e6c62e1f8b9af8c940b96e6ea51`.
- blameFrame: **`ProviderCatalogRepository.kt:112`**.

**Confirmed against production source** (`core/data/src/main/kotlin/lava/data/provider/ProviderCatalogRepository.kt`):
- **Line 112** is exactly `error("provider discovery failed: HTTP ${response.code} for $url")` inside the `if (!response.isSuccessful)` branch of `fetchProviders` (line numbers verified by Read). The blame frame and the source agree.
- Lines 100–107: `fetchProviders(apiBaseUrl, authKey)` attaches the `Lava-Auth` header **only when `authKey != null`** (`lanHttpClient.withAuthKey(authKey, authFieldName)`); when `authKey == null` it uses the bare LAN client (no auth header).

**Relationship to Defect-A (PROVEN):** This issue first appeared in **1.3.4** — the
SAME build that shipped the Defect-A TLS fix (commit `0deb54e7`). In 1.3.3 the fetch
died at the TLS handshake (`CertPathValidatorException`, issue `042b9b61`) BEFORE the
request reached the server. The 1.3.4 fix made the permissive-LAN TLS handshake
succeed, so the request now reaches the api-app's `Lava-Auth` gate — which rejects it
with **HTTP 401**. The TLS fix peeled back one layer and revealed the auth-gate layer
underneath. This is consistent with the "Defect B still open" HONEST scope note at the
bottom of the Defect-A closure log.

**Root-cause hypothesis (PENDING_FORENSICS — two candidates, neither yet eliminated):**
- `UNCONFIRMED:` **Candidate 1 — null/absent authKey on the onboarding path.** The
  catalogue fetch runs during onboarding (`MainActivity` breadcrumb) before the user
  has selected/linked an `Endpoint.GoApi` instance, so `OnboardingViewModel`
  may pass `authKey = null` → no `Lava-Auth` header → api-app gate returns 401. The
  `if (authKey != null)` branch at line 107 means a null key produces an
  unauthenticated request by construction.
- `UNCONFIRMED:` **Candidate 2 — stale/mismatched per-instance key.** A key WAS
  attached but does not match the api-app instance at `192.168.0.107:8443` (key
  rotated / belongs to a different instance) → 401.
  To eliminate: confirm whether `OnboardingViewModel.fetchAndPopulateProviders`
  resolves `Endpoint.GoApi.key` before the onboarding catalogue fetch, and whether
  that key is present on the device at the time of the call. Requires the
  onboarding call-site source + on-device `ApiKeyStore` state. NOT changed here
  (analysis-only) — left for the remediation cycle.

**Severity:** Low user-impact (3 events / 1 user, single LAN device, NON_FATAL — the
error contract captures it into `Result.failure` and onboarding falls back to bundled
descriptors, never a blank screen). But it is **functionally actionable**: a 401 on the
catalogue fetch means the user still sees only the bundled providers, not the full
api-app catalogue — the same user-visible symptom class as Defect-A. firstSeen=lastSeen
1.3.4; `UNCONFIRMED:` whether 1.3.5/1.3.6 already changed the auth-key threading — not
re-triggered on a real device in-window, so its current status on HEAD is unproven.

## Honest disposition summary

- **0 ANRs.** 2 FATALs (both OLD — 1.2.19, 1.2.21 — already fixed-in-code, not
  recurring on 1.3.x; console close-marks owed to operator).
- **5 non-fatals:** 4 are OLD/fixed-in-code or instrumented-telemetry with closure
  logs already on disk and not recurring on 1.3.x; **1 is NEW + actionable**
  (`47b000d5`, the post-TLS-fix auth 401).
- api-app (both variants) HTTP 404 = no Crashlytics data yet (freshly wired,
  undistributed) — recorded HONESTLY, NOT invented.
- No false "all clear": every issue enumerated with its real event count, version,
  state, and disposition.
