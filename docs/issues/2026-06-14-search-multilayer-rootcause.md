# Search "does not work in any scenario" — consolidated 3-layer root-cause (2026-06-14)

**Status:** Layers 1 + 2 fixed and on-device-verified (search now reaches the
chosen LAN host, no longer `lava-api.local`). Layer 3 root-caused; matched-pair
on-device verification (fresh-onboard search returns RESULTS) **in progress**.
1.3.9 is client-only and is **not shipped** until that gate is GREEN.

**Fix commit:** `d05bc71e`. **Operator report:** "search still does not work in
any scenario" (1.3.8-1065).

**No-guessing note (§11.4.6):** every claim below is anchored to git truth (the
`d05bc71e` diff + the incident JSON
`.lava-ci-evidence/sixth-law-incidents/2026-06-14-sse-search-v1search-unserved-bluff.json`)
or marked `VERIFYING` / `OWED`. Nothing here is asserted as "fixed" beyond what is
on-device-proven.

---

## 1. The cascade (three independent bugs, one symptom)

A single user-visible failure ("search returns nothing / error in any scenario")
was the surface of three independent defects stacked on the GoApi search path.
Each had to be fixed; fixing only one would still leave search broken.

### Layer 1 — onboarding never wrote `settings.endpoint`

`OnboardingViewModel.onSelectApi` persisted the chosen API to:
- the Room `Endpoint` list (`endpointsRepository.add(endpoint)`), and
- `ApiBaseUrlHolder` (the dynamic-SDK / `GET /providers` seam),

but **never** to `settings.endpoint`. The home search resolves its target host
from `SettingsRepository.getSettings().endpoint` via
`NetworkApiRepositoryImpl.endpoint()` — a DIFFERENT store from the two above. It
stayed at the default `Endpoint.GoApi("lava-api.local")`, which does not resolve
on the LAN → `UnknownHostException`. So `/providers` worked (it reads
`ApiBaseUrlHolder`) while search died (it reads `settings.endpoint`).

**Fix:** `onSelectApi` now invokes `SetEndpointUseCase(endpoint)` (nullable
injected seam, real impl bound by Hilt; null default preserves
direct-construction tests).
`RepopulateProvidersOnStartupUseCase.reconcileActiveEndpoint()` heals existing
installs whose onboarding pre-dated this fix — when `settings.endpoint` is the
orphan default, it adopts the persisted Room GoApi.

**On-device proof:** post-fix, search reaches `10.0.3.16`, no longer
`lava-api.local`.

### Layer 2 — GoApi search routed to `GET /v1/search`, a route NO backend serves

`SearchResultViewModel`'s multi-provider dispatch sent every GoApi search to
`observeSseSearch` → `GET {base}/v1/search`. That route is registered by **no
backend** (see §3). Result: 404 for every GoApi user, regardless of host or auth.

**Fix:** route GoApi multi-provider search to `observeStreamMultiSearch` →
`sdk.streamMultiSearch` → `GET /v1/{provider}/search` (the served path; carries
the per-instance `Lava-Auth` key + the permissive-LAN OkHttp client wired in
1.3.8 via `ApiBaseUrlHolder`). The dispatch change appears in both
`searchClicked`-class entry points in `SearchResultViewModel`.

### Layer 3 — per-instance `Lava-Auth` key null on the VM (VERIFYING)

After Layers 1+2, `/v1/{provider}/search` returned 401 on the Genymotion VM
because the per-instance key read off the variant-aware key ContentProvider was
null. Root cause: a **debug-client / release-api-app SIGNATURE MISMATCH** — the
key ContentProvider is signature-permission-protected, and the variant-aware
reader reads the `.dev` api-app, which was not installed on the VM during this
run. This is **not a production bug for a matched (same-signature)
client/api-app pair**. Matched-pair on-device verification (a same-signature
client + api-app, fresh onboard, search returns RESULTS) is **in progress** and
is the load-bearing gate before 1.3.9 ships.

---

## 2. The two search dispatch paths

`SearchResultViewModel` chooses a search transport per the current filter +
endpoint:

| Path | Transport | HTTP route | Status |
|------|-----------|------------|--------|
| `observePagingData` | paging (legacy) | `GET /search` | served (legacy/standalone) |
| `observeSseSearch` | SSE consumer | `GET /v1/search` | **DEAD — no backend serves it** |
| `observeStreamMultiSearch` | `sdk.streamMultiSearch` (client-side fan-out) | `GET /v1/{provider}/search` | **served by both backends — the working path** |

Before the fix, GoApi multi-provider search took the **dead** `observeSseSearch`
→ `/v1/search` branch. After the fix it takes `observeStreamMultiSearch` →
`/v1/{provider}/search`, fanning out per provider client-side.

---

## 3. Served-route facts (verified across the Go codebase)

- `internal/router/router.go` (embedded api-app + shared via `internal/mobile`):
  serves `/providers`, `/v1/:provider/{op}`, `/jackett/search`, `/health/ready`.
- The standalone `lava-api-go`: serves legacy `/search` and `/v1/{provider}/search`.
- **Neither registers `/v1/search`.** That is why `observeSseSearch` 404'd for
  every GoApi user.

---

## 4. The endpoint-store split (why Layer 1 was invisible)

There are **three** distinct places "the active API" is recorded, and the search
path reads a different one than onboarding wrote:

1. **Room `Endpoint` table** — the user's saved endpoint list (`endpointsRepository`).
   Holds host/platform/storage; the per-instance auth key is NOT a column here.
2. **`settings.endpoint`** — a single serialized active `Endpoint` inside settings,
   stored/decoded via `EndpointConverter`. This serialized form **carries the
   per-instance `Lava-Auth` key**. `NetworkApiRepositoryImpl.endpoint()` (the
   search/network host resolver) reads THIS.
3. **`ApiBaseUrlHolder`** — an in-memory active-base-URL + key holder consumed by
   the dynamic SDK clients (`ApiBackedTrackerClient`) + `GET /providers`.

Onboarding wrote (1) and (3) but not (2) → search read a stale (2). The
key-bearing nature of (2) via `EndpointConverter` is also why an existing-install
heal that adopts the keyless Room row (1) yields a 401 (see §6).

---

## 5. The key-flow (how the per-instance Lava-Auth key reaches a request)

`ApiKeyClient` (variant-aware ContentProvider reader) → `goApi.key` →
`setEndpoint` persists the GoApi WITH its key into `settings.endpoint` (via
`EndpointConverter`) AND `ApiBaseUrlHolder.set(url, key)`. The GoApi branch of
`NetworkApiRepositoryImpl` then turns `host:port + key` into a permissive-LAN
client + `withKeyOverride` request, so `/v1/{provider}/search` carries
`Lava-Auth: <key>`. The key lives in (2) and (3) above — never in the Room row
(1). When the ContentProvider read returns null (signature mismatch, §1 Layer 3),
the key is absent and `/v1` ops 401.

---

## 6. Open follow-ups (OWED / VERIFYING)

- **Layer-3 matched-pair on-device verify (VERIFYING).** Same-signature client +
  api-app, fresh onboard, confirm search returns RESULTS. Gate for shipping 1.3.9
  (client-only). api-app unchanged at 0.2.8-12.
- **Existing-install key-restore (OWED).** Installs that do NOT re-onboard get a
  keyless healed endpoint → `/v1` ops 401, because the key lives only in
  `settings.endpoint`/`ApiBaseUrlHolder` and the old onboarding never wrote it,
  and the heal adopts the keyless Room row. Fresh onboard flows the key correctly.
- **Dormant SSE path (OWED).** `observeSseSearch` + `applySseError` +
  `SseConnectivityTelemetryTest` + `SearchResultRetryTest` still cover the
  unserved `/v1/search` SSE consumer. Either implement a server-side `/v1/search`
  SSE aggregator (api-app + standalone) and revive the path, or remove the dead
  client SSE consumer + its remaining tests. Not shipping-blocking for 1.3.9.

---

## 7. Tests + evidence

- **Reproduction tests (real-stack, falsifiability-rehearsed, on the REAL path):**
  - `OnboardingViewModelDynamicProvidersTest` — `selecting an API persists it as
    the active settings endpoint the search path reads` (Layer 1).
  - `RepopulateProvidersOnStartupUseCaseTest` — `cold start heals a stale orphan
    active endpoint to the onboarded server in the list` (heal path).
- **Bluff removed:** `SearchResultSseErrorRetryTest` (served `/v1/search` over a
  mock no backend serves). Incident JSON:
  `.lava-ci-evidence/sixth-law-incidents/2026-06-14-sse-search-v1search-unserved-bluff.json`.
- **On-device Chucker evidence:** `GET https://10.0.3.16/providers` → 200; `GET
  https://lava-api.local/search?query=prince…` → `UnknownHostException`
  (api-app "Requests served: 0"). Surfaced by the HelixQA video-QA agent; traced
  via the flow-db `Endpoint` row + `chucker.db` transactions.
- **§6.T.4 BUGFIXES entry:** `docs/issues/fixed/BUGFIXES.md` — "search does not
  work in any scenario — 3-layer cascade (2026-06-14)".
