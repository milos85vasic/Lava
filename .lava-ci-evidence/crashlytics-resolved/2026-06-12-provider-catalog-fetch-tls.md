# Crashlytics closure — provider-catalogue fetch fails the self-signed-LAN TLS handshake (Defect A)

**Issue ID:** `042b9b611cf1521141ec8d31dbc55b74`
**App:** `digital.vasic.lava.client` (Firebase app `1:815513478335:android:456475e2ef4039d8cfd20a`), project `lava-vasic-digital`
**Console:** https://console.firebase.google.com/v1/appid/project/lava-vasic-digital/crashlytics/app/1:815513478335:android:456475e2ef4039d8cfd20a/issues/042b9b611cf1521141ec8d31dbc55b74
**Type:** NON_FATAL · **firstSeen/lastSeen:** 1.3.3 (1060) · **state at audit:** OPEN
**Sample event:** `6A2AC47F01160001545E43969ECE56AB_2228588612143866047`, 2026-06-11T14:22:01Z, Samsung **SM-S918B (Galaxy S23 Ultra), Android 16**, build `1.3.3 (1060)` release, `customKeys.error = provider_catalog_fetch_failed`.

## Stack-trace summary

```
javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException:
  Trust anchor for certification path not found.
  at okhttp3.internal.connection.RealConnection.connectTls (RealConnection.kt:379)
  Caused by: android.security.net.config.NetworkSecurityTrustManager.checkServerTrusted
  → TrustManagerImpl.verifyChain  (the system trust store has no chain to the
    api-app's self-signed LAN cert)
```

Breadcrumb: `session_start → screen_view{MainActivity}` (the onboarding wizard) on a `Firebase Background Thread` — i.e. the dynamic-provider-discovery catalogue fetch.

## Root-cause analysis

The on-device api-app (`digital.vasic.lava.api`) serves `GET /providers` over a
**self-signed LAN cert** (per `lava-api-go/scripts/gen-cert.sh`) and behind the
`Lava-Auth` header gate. `ProviderCatalogRepository` injected the **unqualified
strict-TLS `OkHttpClient`** (system trust store only) and sent **no auth key**, so
every real-device fetch died at the TLS handshake (`CertPathValidatorException`)
before the request was even sent. The repository captured that into
`Result.failure`; `OnboardingViewModel.fetchAndPopulateProviders` therefore fell
back to the compiled-in bundled descriptors, and `loadProviders()` filtered them
to `verified && apiSupported` = exactly **4** (rutracker / rutor / archiveorg /
gutenberg). That is the user-reported "only 4 providers in onboarding" symptom.

The working pattern already existed one module over:
`NetworkApiRepositoryImpl.getApi()` routes `Endpoint.GoApi` through the
`@Named("lan")` permissive-TLS client + `withKeyOverride(endpoint.key, authFieldName)`.
The catalogue path simply never adopted it.

## Fix

`ProviderCatalogRepository` now injects `@Named("lan") lanHttpClient` +
`@Named("authFieldName") authFieldName`; `fetchProviders(apiBaseUrl, authKey)`
attaches the chosen endpoint's per-instance key via a `withAuthKey` interceptor
(mirrors the impl's `withKeyOverride`). `FetchProvidersUseCase` and
`OnboardingViewModel.fetchAndPopulateProviders` thread `Endpoint.GoApi.key`.

## Validation test (regression-immunity)

`core/data/src/test/kotlin/lava/data/provider/ProviderCatalogRepositoryTest.kt`
— rewritten to cross a **real self-signed-HTTPS** `MockWebServer` (okhttp-tls
`HeldCertificate`) whose dispatcher **requires** the `Lava-Auth` header:

- `fetchOverSelfSignedTlsWithAuthSucceeds` — permissive client + key → success,
  parsed descriptors incl. the API-only Jackett indexer, header asserted on the wire.
- `strictClientFailsTheHandshake` — the **codified pre-fix mutation**: the strict
  client against the same self-signed server → `Result.failure`.
- `missingAuthKeyIsRejectedByTheGate` — no key → 401 → failure (auth load-bearing).

Result: 6/6 green. The prior test used **plain-HTTP** MockWebServer + a vanilla
client and so never crossed the boundary that mattered — the bluff that let this
ship (§6.J Sixth Law clause 1).

## Challenge Test (end-to-end)

`OnboardingViewModelDynamicProvidersTest` drives the REAL VM → FetchProvidersUseCase
→ ProviderCatalogRepository → registry populate over MockWebServer, asserting the
API-only provider appears in `state.providers` (green). The on-device rendered
Challenges C39/C40 still owe a real run with a Jackett-backed api-app (Defect B).

## Fix commit

`<this commit SHA>` (lands on the §6.Y-bumped 1.3.4-1061 cycle).

## HONEST scope note (Defect B still open)

Defect A makes the client correctly fetch **whatever the api-app serves**. The
on-device api-app currently serves only its NATIVE providers — the "many more via
Jackett" the operator expects requires **embedded Jackett** in `lava-api-go`
(Defect B, owed next). Do not redistribute claiming a large provider list until B
lands.

## Console close-mark

Pending — close-mark in the Firebase Console only after this commit + tests land
(per §6.O, coverage lands before the dashboard close-mark).
