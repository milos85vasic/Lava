# Master Plan — Finish Everything, Zero Issues (2026-06-14)

> Operator directive (2026-06-14): "what is left unfinished and what are (known) issues we
> (may) have, prepare comprehensive details and add it in subagents-driven working plan so
> nothing is left unfinished with zero issues when we finally finish."

This plan is **evidence-backed**, not from memory — every item traces to one of three
audits run 2026-06-14 (each a committed, file-cited findings doc):

- `docs/audits/2026-06-14-governance-debt-audit.md` (`173e9747`) — constitution/CM-gate debt
- `docs/audits/2026-06-14-search-provider-residuals-audit.md` (`e92590a7`) — search/api-app/providers
- `docs/audits/2026-06-14-product-known-issues-audit.md` (`6293ab62`) — field/Crashlytics/incidents

**Shipped baseline (done, device-verified):** client **1.3.9-1066** + api-app **0.2.9-14** —
the 5-layer search cascade fixed for **no-auth providers** (Internet Archive proven on-device,
`/v1/{provider}/search` → 200, real results). Both §6.AA stages distributed; mirrors at `357f867a`.
**Shipped builds have ZERO Crashlytics field events** (newest field = 1.3.5/0.2.6); no open crash
is on the shipped builds.

**"Definition of Done" (zero issues):** every P0+P1 item below CLOSED with captured anti-bluff
proof (device run / falsifiability RED→GREEN / field-confirm), every operator-owed decision made,
working tree clean, docs in sync. P2 + infra hardening tracked but not release-blocking.

---

## P0 — release-blocking / breaks the operator's actual report

### P0-1 · Auth-provider search (RuTracker / Kinozal) — `Auth-Token` not attached
**Status:** OPEN, code-confirmed. The operator reported search across **RuTracker + YTS + Kinozal**.
The 5-layer fix repaired the `Lava-Auth` per-instance key (so the request reaches the server), but
`ApiBackedTrackerClient.withAuth()` (`core/tracker/client/.../ApiBackedTrackerClient.kt:80-81`)
attaches **only** `Lava-Auth`, never the provider **login session** `Auth-Token`
(`provider:cookie:bb_session=…`, parsed server-side in `lava-api-go/internal/handlers/handlers.go:118`).
So `/v1/rutracker/search` arrives anonymous (`Type:"none"`) → RuTracker/Kinozal return login/empty.
YTS is curated/no-auth → unaffected. **So search is fixed for no-auth providers only.**
**Work:** (a) persist the dynamic provider login session when the user logs in (onboarding/provider-config);
(b) thread it as `Auth-Token` onto `ApiBackedTrackerClient` search/browse/topic/download requests;
(c) wire `AUTH_REQUIRED` + the login step for `RemoteTrackerDescriptor` (dynamic) providers.
**Acceptance:** fresh on-device onboard of RuTracker (+ Kinozal) with **real `.env` creds** → login OK →
search "prince" → the search request carries BOTH `Lava-Auth` + `Auth-Token` → HTTP 200 + real rows on screen.
**Anti-bluff gate:** falsifiability — drop `Auth-Token` → auth-provider search goes empty/login; restore → results.
A real end-to-end test (holder→factory→`Auth-Token` on the wire) like `ApiAuthKeyEndToEndWiringTest` for the session header.
**Stream:** auth-provider-search (code + tests, then device verify with creds). **Effort:** L. **Blocks:** "search works for the providers reported."

### P0-2 · LVA-008 — `search_input` NavBackStackEntry teardown crash
**Status:** OPEN, CRITICAL, gate-RED. Confirmed UPSTREAM androidx-navigation defect; **5 app-level fix
classes device-FALSIFIED** (nav-bump, LenientTeardownRule, nested-host move, atomic popUpTo, NavTeardownGuard).
Real impact: search-then-activity-destroy (rotate / dark-mode / locale / process-death) crashes; gates C06 + C11.
Incident: `.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json`.
**OPERATOR DECISION OWED (cannot be subagent-resolved):** either (i) file an androidx upstream repro + wait,
or (ii) accept-with-§6.AC-telemetry (record the non-fatal, ship) — the 5 in-app fixes are exhausted.
**Acceptance:** operator picks (i)/(ii); if (ii), the telemetry + a tracked-issue citation land + C06/C11 documented as upstream-blocked.
**Stream:** operator-decision → then telemetry impl. **Effort:** S (impl) once decided.

### P0-3 · macOS emulator container/VM path (§6.AH / §6.X-debt) — gates run host-direct
**Status:** OPEN, BLOCKING (governance audit #1). `containerized.go:19-21` hard-requires `/dev/kvm`;
`scripts/run-challenge-matrix.sh:215` resolves macOS→`host-direct`. Every device gate this session ran
host-direct (a §6.AH violation — emulators must run in a container/VM). Blocks the §6.AE matrix / §6.Z
release canary / tag attestation from being constitution-clean on the operator's darwin/arm64 host.
**Work:** add a no-KVM/TCG (or QEMU full-system) runner to `submodules/containers/pkg/emulator` that boots on macOS;
flip `run-challenge-matrix.sh`/`run-release-canary.sh` to `--runner=containerized|vm` with NO host-direct fallback.
**Acceptance:** the C00 canary boots + passes via the container/VM runner on macOS; attestation row `runner: containers-submodule`.
**Anti-bluff gate:** a real boot inside the container/VM + a captured per-AVD attestation (not host-direct).
**Stream:** containers-submodule (Go, cross-repo pin bump). **Effort:** L.

---

## P1 — high (correctness / operator-requested deliverables)

### P1-1 · Watchable issue-videos → `~/Downloads` (operator asked repeatedly)
**Status:** UNDELIVERED. The product audit confirms **none of the 4 issue videos are in `~/Downloads`**.
issue1/2/4 were re-recorded analyzer-PASS earlier but against 1.3.8 (search broken), so **issue3 (search)
was honestly not producible** — search NOW WORKS, so the full set is producible. (Note: an earlier stream
reported delivering issue1/2/4; the audit says they're absent — **verify + (re)deliver**, don't assume.)
**Work:** re-record paced real-app walkthroughs for issue1 (select-all), issue2 (password eye), issue3
(**search "prince" → real results**, now possible), issue4 (server-list de-dup) on the VM; validate each
through HelixQA `recording-analyzer` (liveness PASS); copy the PASS set to `$HOME/Downloads/` + `$HOME/`.
**Acceptance:** 4 analyzer-PASS mp4s present in `~/Downloads` (verified `ls` + analyzer verdict committed to evidence).
**Anti-bluff gate:** analyzer liveness PASS per video + the on-screen content matches the named flow.
**Stream:** video (VM). **Effort:** M.

### P1-2 · YTS / curated search — on-device verification
**Status:** UNVERIFIED on-device (only `archiveorg` was run). Curated = no-auth, so the transport fix should
apply, but unproven. **Acceptance:** on-device onboard YTS → search → real rows (or honest "provider returns
nothing for this query" with a different query that has hits). **Stream:** device-verify (folds into P0-1's run). **Effort:** S.

### P1-3 · api-app-uninstalled → silent 401 ("Something went wrong")
**Status:** OPEN. If the api-app is uninstalled, `ApiKeyClient.read()` → null → keyless → silent 401 with only
the generic error; no user-visible recovery prompt. **Work:** detect the keyless/uninstalled state on a search
failure → surface an actionable prompt ("Start/install the on-device API" or re-onboard). **Acceptance:** device
repro (uninstall api-app, search) → actionable UI, not a bare error. **Stream:** client UX + telemetry. **Effort:** M.

### P1-4 · Remote LAN api-app → wrong-key (`withLocalApiKeyIfMissing`)
**Status:** OPEN, **no covering tests** (`OnboardingViewModel.kt:382`). The helper reads the LOCAL api-app key,
which is wrong for a remote/non-loopback api endpoint → 401. **Work:** only read the local key when the endpoint
is the local on-device api-app (host == loopback/own-IP); for remote endpoints, don't attach the local key.
Add tests. **Acceptance:** unit test — remote GoApi endpoint keyless → helper does NOT attach the local key
(falsifiability: attach-anyway → wrong-key assertion fails). **Stream:** onboarding code + tests. **Effort:** S.

### P1-5 · RuTracker credential rotation (security, §6.H) — OPERATOR-OWED
**Status:** OWED. Historical git-history leak (`nobody85perfect`/`ironman1985` + RuTor/Kinozal/NNMClub);
live surface redacted (`d7d4572a`), working tree clean — but per §6.H clause 6 the **operator must rotate** the
RuTracker (+ siblings) passwords; the git-history purge (§6.T.3 force-push) is also pending operator authorization.
(The separate 67th-cycle Firebase-token echo leak is RESOLVED.) **Acceptance:** operator rotates creds + updates
`.env`; optional history purge with explicit authorization. **Stream:** operator action (+ I can prep the history-purge plan). **Effort:** operator.

---

## P2 — hardening / fidelity / debt (not release-blocking)

| id | item | source | acceptance | effort |
|---|---|---|---|---|
| P2-1 | **L4 `READ_API_KEY` variant-suffix** (`${apiKeyPermission}` placeholder both apps) so debug+release never collide → test VM exercises the production grant path | residuals #5 | manifests+build.gradle, clean-install grant test | S-M |
| P2-2 | **§6.AC telemetry comprehensive** — client key-read failures are logcat-only (not `recordWarning`→Crashlytics); api-app empty-cursor `query()` records nothing; Go side has zero non-fatal enforcement | residuals #8, gov §6.AC-debt | `recordNonFatal`/`recordWarning` on the error paths + lint | M |
| P2-3 | **§6.AB-debt** Detekt/go-vet per-feature anti-bluff completeness rule | gov | rule + hermetic test | M |
| P2-4 | **§6.Z-debt residual** — runtime Gate 6 INSIDE `firebase-distribute.sh` (SHA/24h/BUILD-SUCCESSFUL), not only pre-push | gov | gate + `tests/firebase/` | M |
| P2-5 | **2 FGS Crashlytics issues** (`9ba8502e`+`b9baeaede`) console close-mark — AFTER 0.2.9 field-confirm | product #4 | field-confirm 0.2.9 no-FGS → operator close-marks | operator |
| P2-6 | **§6.AF chaos/stress (LVA-7)** beyond phase-1 + wire into verify-all | gov | runnable chaos suite | M |
| P2-7 | **Server-side `/v1/search` SSE aggregator** — OPTIONAL (client fan-out sufficient); Go has an unused `MultiSearchHandler.GetMultiSearch` | residuals #6 | decision: implement or delete the dead Go route | S |
| P2-8 | **§6.AI** thin-index CLAUDE.md restructure + token harness; device-recorder layout; action-prefix LAYER-2 verify | gov | per-clause | S-L |

---

## Doc-correction (cheap, do first — the docs lie about being behind)

The governance audit found **9 debts marked OPEN in `CLAUDE.md` that are actually CLOSED** (§6.Y-debt pre-push
Check 6 live; §6.AA-debt default flipped `firebase-distribute.sh:46`; §6.AD scanners exist + verify-all-wired;
§6.AE strict-flip `check-challenge-coverage.sh:39`; `CM-COVERAGE-LEDGER` strict; LVA-6 codegraph submodules;
§6.AI action-prefix hook). **`docs/helix-constitution-gates.md` is stale (2026-05-16).**
**Work:** correct the stale "open" markers in `CLAUDE.md` + refresh `helix-constitution-gates.md`.
**Acceptance:** every debt marker in CLAUDE.md matches verified reality. **Effort:** S. **Stream:** docs.

## Working-tree hygiene

`git status` carries 18 entries: `core/apiengine/.../api-source.hash` (1-line regen), `submodules/tracker_sdk`
pointer moved (decide intended-bump vs reset — pins frozen by default), 5 `.lava-ci-evidence/stress-chaos/jackett/*.json`
(regen churn), untracked raw challenge-video + video-analysis mp4 dirs (curate/gitignore per §11.4.128, never commit raw).
**Work:** commit/reset the intended bits, gitignore the raw recordings. **Effort:** S.

---

## Operator-owed decisions (CANNOT be subagent-resolved — surface + wait)
1. **LVA-008** (P0-2): file androidx upstream repro OR accept-with-telemetry + distribute.
2. **RuTracker credential rotation** (P1-5) + optional git-history purge authorization (§6.T.3 force-push).
3. **FGS Crashlytics console close-marks** (P2-5) after 0.2.9 field-confirm.
4. **Auth-provider login UX** (P0-1): confirm the intended login surface for dynamic auth providers
   (onboarding step vs provider-config) before the implementation locks the flow.

---

## Execution shape (subagents-driven, parallel where non-conflicting)
- **Wave 1 (parallel, non-conflicting):** doc-correction (docs) ‖ P1-4 remote-key guard+tests (onboarding) ‖
  P2-1 L4 permission-suffix (manifests) ‖ working-tree hygiene. All small, disjoint file sets.
- **Wave 2 (P0 code):** P0-1 auth-provider `Auth-Token` threading (the headline) — code + tests, then a single
  device-verify run that ALSO covers P1-2 YTS + P1-1 issue3 video (one VM session). P0-3 containers-runner in
  parallel (cross-repo, Go).
- **Wave 3 (hardening):** P2-2 telemetry ‖ P2-3 Detekt ‖ P2-4 distribute gate ‖ P2-6 chaos.
- **Gate before "finished":** all P0+P1 closed with captured proof; operator decisions made; a final clean
  build + full device gate (preferably via the P0-3 container runner) + a release if auth-provider search shipped.

**Anti-bluff invariant (every item):** no item is "done" without captured physical evidence — a device run
showing the user-visible outcome, OR a falsifiability RED→GREEN with the assertion message, OR a field-confirm.
"Compiles / test green against a mock" is never sufficient (this cycle removed 5 such bluffs).
