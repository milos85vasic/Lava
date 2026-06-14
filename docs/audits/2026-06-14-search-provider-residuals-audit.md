# Search / api-app / Provider Residuals Audit (2026-06-14)

**Scope:** residual, unverified, and OWED items around search, onboarding, the
api-app, and providers AFTER the 5-layer search fix shipped (client 1.3.9-1066 +
api-app 0.2.9-14). Read-only audit; evidence-cited per §11.4.6 (no guessing —
claims are anchored to source or marked `UNVERIFIED` / `OWED`).

**Headline (the question the master plan must answer):** On-device search is
PROVEN only for **Internet Archive** (`archiveorg`, `AuthType=AuthNone`). It is
**NOT proven for RuTracker / YTS / Kinozal**. The 5-layer fix is provider-agnostic
for the *transport + Lava-Auth key delivery*, but auth-required providers need a
SECOND credential (the provider login session, delivered via the `Auth-Token`
header) that the dynamic `ApiBackedTrackerClient` search path **does not attach
today**. See Item 1 — it is the largest gap and a likely P0 for "search works for
the providers the operator originally reported."

---

## Residuals table

| # | Item | Status | Sev | What's needed | Acceptance criteria |
|---|------|--------|-----|---------------|---------------------|
| 1 | **Auth-provider search: `Auth-Token` provider credential not attached on dynamic `/v1/{provider}/search`** | OWED + UNVERIFIED on-device | **P0** | `ApiBackedTrackerClient.withAuth()` attaches ONLY the `Lava-Auth` per-instance key (`ApiBackedTrackerClient.kt:80-81`); it never sends the `Auth-Token` provider-login header. Go `parseCredentials(c)` reads `Auth-Token` (`handlers.go:118`, `multiprovider.go:90`) → for an auth provider the server gets `Type:"none"` (anonymous) → rutracker/kinozal `Search` runs token-less → upstream returns login/empty. Need: (a) persist the provider login session from the dynamic login flow, (b) attach it as `Auth-Token: {provider}:cookie:...` on the `streamMultiSearch`/`ApiBackedTrackerClient` search request, (c) wire `AUTH_REQUIRED` capability + the provider-login step for dynamic (`RemoteTrackerDescriptor`) providers. | On a real device, fresh onboard of RuTracker (or Kinozal) + login with real creds → `streamMultiSearch` → `GET /v1/rutracker/search` carries BOTH `Lava-Auth` and a non-empty `Auth-Token: rutracker:cookie:...` → HTTP 200 + real result rows render. Falsifiability: drop the `Auth-Token` attach → search returns empty/401 for the auth provider. |
| 2 | **YTS / curated-provider search on-device** | UNVERIFIED | **P1** | YTS is a curated provider (no auth). Transport fix applies, but it was never run on-device end-to-end — only `archiveorg` was. Confirm the dynamic descriptor for YTS declares `SEARCH`, routes to `/v1/yts/search`, and renders rows. | On a real device, onboard YTS → search → real YTS rows render, `/v1/yts/search` → 200. |
| 3 | **Existing-install key-restore: api-app uninstalled** | OWED | **P1** | `ApiKeyClient.read()` returns null when the api-app provider is absent (`ApiKeyClient.kt:65-71`) → keyless endpoint → `/v1` ops 401 until re-onboard. The L3 cold-start restore (`21031f2a`) heals a keyless mDNS endpoint only when the key is still readable; an uninstalled api-app yields a silent keyless state with no user-visible recovery prompt. | App detects null key on a GoApi endpoint at search time → surfaces a re-onboard / "API app not running" prompt instead of a bare "Something went wrong" 401. Test asserts the user-visible recovery path. |
| 4 | **Remote (non-local) LAN api-app: local ApiKeyClient key is wrong for a remote endpoint** | OWED + UNVERIFIED | **P1** | `withLocalApiKeyIfMissing` (OnboardingViewModel:382, **no covering tests** per codegraph) reads the LOCAL on-device api-app key. For a remote/LAN api-app instance (a different device's api-app), the local key does not authenticate the remote endpoint → 401. The key-restore + onboarding key-attach paths assume the active GoApi is the co-installed local api-app. | A remote LAN api-app endpoint either (a) carries its own per-instance key from the mDNS/discovery record, or (b) is explicitly out-of-scope and the local-key read is gated on "endpoint host == loopback". Test covers the remote-endpoint case. |
| 5 | **`READ_API_KEY` permission name variant-suffix (L4)** | OWED (MEDIUM / QA-fidelity) | **P2** | Per `docs/issues/2026-06-14-readapikey-permission-variant-analysis.md` §2c: the permission name is a fixed literal in both manifests (`api-app/.../AndroidManifest.xml:11-13,93`; `app/.../AndroidManifest.xml:9`) while every other cross-app id is variant-suffixed. Mixed debug+release co-install → `INSTALL_FAILED_DUPLICATE_PERMISSION` → grant denied (test-VM artifact). Production matched-pair is SAFE (§2a/d). Fix: make the name a `${apiKeyPermission}` manifest placeholder + per-variant value + BuildConfig (§6.R) on BOTH apps. | After the suffix, debug defines `…dev.READ_API_KEY` / release `…READ_API_KEY`; a mixed-install VM grants each its own permission. Manifest + `app`/`api-app` `build.gradle.kts` placeholders updated; no source literal (§6.R). |
| 6 | **Dead `/v1/search` SSE — server-side aggregator** | CLOSED (consumer removed) / OPTIONAL feature | **P2** | The dead `observeSseSearch` → `/v1/search` consumer was removed (`addaacd0`); no backend serves `/v1/search`. The Go server DOES have a `MultiSearchHandler` SSE aggregator (`search.go:114` `GetMultiSearch`), but the client uses client-side `streamMultiSearch` fan-out, which is sufficient. A server-side aggregator is an efficiency optimization, not a correctness gap. | Decision only: either wire the existing `GetMultiSearch` SSE route + a client consumer (efficiency), or document client-side fan-out as the intended design. No correctness requirement. |
| 7 | **Watchable issue-videos re-record (operator task #24)** | OWED | **P1** | Prior 4 "success videos" FAILED HelixQA `recording-analyzer` liveness (issue3=1 frame, issue4=1.7s, issue1/2 frozen) — instrumentation recordings finish <1s then idle. issue3 (search) could NOT be recorded because search was broken; **now search works**, so a paced real-app search e2e walkthrough + the refreshed set are owed to `~/Downloads`. Evidence: `.lava-ci-evidence/video-analysis/2026-06-14-issue-videos.md`. | 4 paced real-app walkthrough videos (incl. issue3 = on-device search returning rows) PASS HelixQA `recording-analyzer` liveness + delivered to `~/Downloads`. |
| 8 | **§6.AC telemetry coverage gap on the key/search error paths** | OWED (PARTIAL) | **P2** | Telemetry exists on the client `ApiKeyClient.read()` paths (logcat-only `Log.w`, NOT `recordNonFatal`/`recordWarning` → not in Crashlytics — `ApiKeyClient.kt:48,63,69`). The api-app `ApiKeyProvider.query()` empty-cursor path (`ApiKeyProvider.kt:101-107`) records NOTHING when it serves an empty cursor (the exact L5 defect surface). The Go multi-search per-provider error is `no-telemetry` by design (SSE is the surface). Gap: the client key-read failures should route through `AnalyticsTracker.recordWarning` (not just logcat) so a field recurrence of the keyless-endpoint 401 is visible in Crashlytics; the api-app empty-cursor should record a non-fatal. | Client `ApiKeyClient` null/denied/empty-cursor reads call `recordWarning(...)` with non-secret attrs (§6.H); api-app empty-cursor `query()` records a non-fatal. Both visible in Crashlytics. |
| 9 | **`apiKeyReader` no-arg refactor ripple + `currentEndpointIsGoApi` removal** | DONE (verify clean) | **P2** | `addaacd0` dropped the ignored `apiKeyReader` authority param + the dead SSE path. `MainActivity.buildApiKeyReader` returns `{ -> client.read()?.key }` (no authority arg — the §6.J meta-lesson in the root-cause doc §8). Confirm no remaining call sites pass an authority that is silently ignored, and that the `currentEndpointIsGoApi` removal left no stale branch. | grep/codegraph confirms zero call sites threading an ignored authority; no dead `currentEndpointIsGoApi` references. |
| 10 | **L5 real-holder regression test (retire the `withFakes` bluff)** | OWED | **P1** | The `withFakes` seam (`ApiKeyProvider.kt:135`) bypassed the real `onCreate`→holder lifecycle where the L5 bug lived (root-cause doc §8 + §6.J). A real-holder regression test (`running_engine_exposes_real_key_via_holders` / `engine_not_running_exposes_empty_cursor_via_holders` appear in codegraph) must drive the real lifecycle ordering, no `withFakes`. Confirm it exists and is falsifiability-rehearsed (caching-version FAILS, lazy-version PASSES). | A regression test drives the production `onCreate` ordering (no `withFakes`); RED on the caching variant, GREEN on lazy. Bluff-Audit stamp in commit. |

---

## Evidence index

| Claim | Cite |
|---|---|
| Client search attaches only `Lava-Auth`, not `Auth-Token` | `core/tracker/client/.../ApiBackedTrackerClient.kt:80-81,87-100,214-224` |
| Go reads provider creds from `Auth-Token` header | `lava-api-go/internal/handlers/v1/handlers.go:118-124`; `lava-api-go/internal/auth/multiprovider.go:35-93` |
| Go search runs provider with parsed creds (anonymous if no `Auth-Token`) | `lava-api-go/internal/handlers/v1/search.go:32-75,192` |
| rutracker provider is `AuthCaptchaLogin` | `lava-api-go/internal/rutracker/provider.go:59` |
| rutracker Kotlin search needs a token | `core/tracker/rutracker/.../feature/RuTrackerSearch.kt:31` |
| On-device search verified only for Internet Archive | `docs/CONTINUATION.md:14`; `.lava-ci-evidence/search-verification/2026-06-14-fresh-onboard-search.md` |
| L4 permission variant analysis | `docs/issues/2026-06-14-readapikey-permission-variant-analysis.md` |
| L5 root cause + `withFakes` bluff lesson | `docs/issues/2026-06-14-search-multilayer-rootcause.md` §6,§8; `api-app/.../handoff/ApiKeyProvider.kt:57-108` |
| `withLocalApiKeyIfMissing` has no covering tests | codegraph blast-radius (`OnboardingViewModel.kt:382`, 1 caller, no tests) |
| Client key-read telemetry is logcat-only | `app/.../handoff/ApiKeyClient.kt:48,63,69` |
| Issue-videos liveness failure | `.lava-ci-evidence/video-analysis/2026-06-14-issue-videos.md`; `docs/CONTINUATION.md:20` |
| Dead `/v1/search` removed; server SSE aggregator exists | `docs/issues/2026-06-14-search-multilayer-rootcause.md` §2; `lava-api-go/internal/handlers/v1/search.go:114` |
