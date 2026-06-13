# Crashlytics closure — `47b000d5` provider-catalogue fetch HTTP 401 (onboarding)

**Issue ID:** `47b000d54ff647802df7577ca12a1741`
**Type:** NON_FATAL
**App:** client release (`1:815513478335:android:456475e2ef4039d8cfd20a`)
**Scope (real Firebase data, 30d):** 3 events / 1 user, Samsung Galaxy S23 Ultra /
Android 16, first = last seen **1.3.4-1061**.
**Snapshot:** `.lava-ci-evidence/crashlytics-resolved/2026-06-13-crashlytics-fleet-snapshot.md`
**Operator real-device report (2026-06-13):** "choose discovered API 192.168.0.107:8443 →
providers screen shows 'Couldn't reach the selected API — showing bundled providers'".

## Stack-trace summary

Non-fatal recorded by `ProviderCatalogRepository.fetchProviders` at
`ProviderCatalogRepository.kt:112` — the `error("provider discovery failed: HTTP
${response.code} …")` call — with `response.code == 401` for
`GET https://192.168.0.107:8443/providers`. `OnboardingViewModel`
`fetchAndPopulateProviders` catches the resulting failure and surfaces
`PROVIDER_CATALOG_FALLBACK_NOTICE` → the wizard shows the bundled providers.

## Root-cause analysis (CONFIRMED — not a guess)

This is the layer revealed AFTER the Defect-A TLS fix (`0deb54e7`, same 1.3.4 build):
- 1.3.3 — the fetch died at the **TLS handshake** against the self-signed LAN cert
  (`042b9b61`, `CertPathValidatorException`).
- 1.3.4 — Defect-A routed the fetch through the permissive-LAN client, so the request
  now **reaches** the api-app's `Lava-Auth` gate — which rejects it with **HTTP 401**.

The deeper root cause: the provider catalogue (`GET /providers`) was registered AFTER
`engine.Use(auth.GinMiddleware())` in `lava-api-go/internal/router/router.go`, i.e. it
was auth-gated. During ONBOARDING the client fetches the catalogue from an API it has
just discovered on the LAN, at which point it holds **no pre-shared `Lava-Auth` key**
with that API (onboarding is precisely what would establish the relationship). The
Crashlytics agent's independent analysis reached the same conclusion: "the onboarding
fetch runs before an `Endpoint.GoApi` is linked → unauthenticated by construction".
Therefore the correct fix is NOT to make onboarding find a key — it is to recognise the
catalogue as **public, non-sensitive metadata** (provider ids, capabilities, authType,
baseUrls — no credentials, no user data) and serve it without auth, exactly like
`/health` + `/ready`. Per-provider operations under `/v1/:provider/…` stay fully gated.

## Fix

`lava-api-go/internal/router/router.go`: moved `engine.GET("/providers", …)` to BEFORE
the auth-middleware chain (alongside `/health`/`/ready`). The standalone binary and the
embedded api-app share the exact `router.Build`, so the one edit fixes both surfaces
(DRY — a divergent embed router would be a §6.J bluff vector). Client-side, the success
path remains the populate-from-API path; the fallback banner now only appears on a
genuine fetch failure.

**Fix commits:** `9ae9ab90` (server fix + unit test) · `132d1b07` (real-binary e2e
boundary) · `99e5893a` + `6ce100bc` (client full-flow tests).

## Validation + Challenge tests (regression immunity, §6.O + Fifth Law)

- **Server unit** — `TestBuild_ProvidersOpenWithFullAuthChain`
  (`lava-api-go/internal/router/router_config_wiring_test.go`): unauth `GET /providers`
  with the full auth chain mounted → 200 (not 401). Falsifiability: move registration
  back behind auth → 401 (the bug).
- **Server real-binary e2e** — `TestProviders_PublicCatalogue_PerProviderStillGated`
  (`lava-api-go/tests/contract/providers_public_auth_boundary_test.go`): real
  `router.Build` + full auth chain + real native registry; unauth `/providers`→200+catalogue,
  unauth `/v1/rutracker/search`→401, authed `/v1/…`→crosses the gate. Falsifiability
  (re-performed by the main stream): gate `/providers` → sub-test A `401 {"error":"unauthorized"}`.
- **Client full-flow** — `OnboardingViewModelDynamicProvidersTest`
  (`feature/onboarding/src/test/.../OnboardingViewModelDynamicProvidersTest.kt`): success →
  shows EXACTLY the API's set, bundled provider the API lacks is ABSENT (replace-not-merge),
  and `providerCatalogNotice == null` (NO fallback banner); failure (500) → banner present +
  bundled list non-blank. Falsifiability: drop `populateFrom` → replace test RED;
  success-emits-banner → assertNull RED.

## Device verification

§6.Z device gate on Genymotion Pixel 9 / API 35 (§6.AH VM path, serial 127.0.0.1:6555),
exact 1.3.6-1063 debug build: C00 cold-start canary GREEN (`BUILD SUCCESSFUL`). Onboarding
gate set (C26/C25/C20) recorded under `.lava-ci-evidence/genymotion/1.3.6-1063-*`.

## Console close-mark

Owed to the operator (interactive Firebase Console action) AFTER the 1.3.6-1063 build is
distributed and observed clean — the test coverage above lands first per §6.O.
