# Field Monitor Heartbeat 01 — 2026-06-14 (overnight watch on tonight's double-release)

Real Firebase Crashlytics data pulled via the Firebase MCP (project
`lava-vasic-digital`). Pull timestamp: **2026-06-14 (overnight)**.
Window queried: **2026-06-12 00:00Z → 2026-06-14 23:59Z** (≈2 days).
ANALYSIS + EVIDENCE ONLY — no production code changed.

Per §11.4.6: proven facts stated as facts; anything unconfirmed marked
`UNCONFIRMED:` / `PENDING_FORENSICS:`.

Tonight's shipped builds under watch: client **1.3.6 / 1063**, **1.3.7 / 1064**;
api-app **0.2.6 / 10**, **0.2.7 / 11**.

## Verdict

**FIELD CLEAN — NO NEW DATA for the tonight-shipped versions.**

Zero crash / ANR / non-fatal events from **1.3.6 (1063)**, **1.3.7 (1064)**,
**0.2.6 (10)**, or **0.2.7 (11)**. None of those four version codes appears in any
Crashlytics version report. Expected this soon after distribute (testers asleep /
not yet installed). It is NOT a gap. No NEW field regression from tonight's builds.

The only live issue across the fleet is the SAME already-known Defect-B auth-gate
401 (`47b000d5`), last-seen on **1.3.5** — confirmed NOT migrated onto 1.3.6/1.3.7.

## Per-variant real data

### client RELEASE (`digital.vasic.lava.client` — `…456475e2…`)

`topVersions` (FATAL+ANR+NON_FATAL), 2-day window 2026-06-12 → 2026-06-14:

| Version | Events |
|---------|--------|
| 1.3.5 (1062) | 3 |
| 1.3.4 (1061) | 3 |
| 1.3.3 (1060) … 1.2.3 (1023) | 0 |
| **1.3.6 (1063)** | **ABSENT — not in report (0 sessions ingested)** |
| **1.3.7 (1064)** | **ABSENT — not in report (0 sessions ingested)** |

`topIssues` (2-day window) returned exactly **one** issue:

| Issue id | Title / top frame | Events/Users | first→last ver | State | Class |
|----------|-------------------|--------------|----------------|-------|-------|
| `47b000d54ff647802df7577ca12a1741` | `ProviderCatalogRepository$fetchProviders` — `IllegalStateException: provider discovery failed: HTTP 401 for https://192.168.0.107:8443/providers` | 6 / 1 | **1.3.4 → 1.3.5** | OPEN | **OLD/known — Defect-B auth-gate 401.** Not on 1.3.6/1.3.7. |

ANRs: NONE. FATALs in-window: NONE.

**Sample-event verification** (5 events pulled via `crashlytics_list_events`,
window 2026-06-13 12:00Z → 2026-06-14): ALL are `version: 1.3.4 (1061)` or
`1.3.5 (1062)`, `build_type: release`, device **samsung SM-S918B (Galaxy S23
Ultra), Android 16**, ARM64, `customKeys.error = provider_catalog_fetch_failed`,
blameFrame `ProviderCatalogRepository.kt:112`. Latest `eventTime` = **2026-06-13
17:51:55Z** (no events after that timestamp; none on 1.3.6/1.3.7). `get_issue`
confirms `firstSeenVersion: 1.3.4`, `lastSeenVersion: 1.3.5`. Single impacted
installation (`installationUuid B5579AE9…`). **NOT a tonight regression.**

Source confirmation (analysis-only, no edit): `core/data/.../ProviderCatalogRepository.kt`
line 112 — the `error("provider discovery failed: HTTP ${...} for $url")` in the
`if (!response.isSuccessful)` branch. Blame frame and source agree (per prior
2026-06-13 monitor; unchanged).

### client DEBUG (`digital.vasic.lava.client.dev` — `…54ca2ca3…`)
`topIssues` returned **no results**. Debug builds are not distributed to testers —
EXPECTED, not a gap.

### api-app RELEASE (`digital.vasic.lava.api` — `…d57b960e…`)
**HTTP 404** ("Requested entity was not found") — Crashlytics has NO data for this
app yet. Same as the 2026-06-13 monitor + baseline. The tonight releases
(0.2.6/10, 0.2.7/11) have produced no events. HONEST, not a gap.

### api-app DEBUG (`digital.vasic.lava.api.dev` — `…2932451e…`)
**HTTP 404** — same as api-app release. No Crashlytics data yet. HONEST, not a gap.

## Cross-check against prior monitor (`2026-06-13-post-1.3.7-field-monitor.md`)

| Issue / variant | Prior (2026-06-13 eve) | Now (2026-06-14 overnight) | Delta |
|-----------------|------------------------|----------------------------|-------|
| `47b000d5` (Defect-B 401) | 6/1, first 1.3.4 → last 1.3.5 | 6/1, first 1.3.4 → last 1.3.5 | **UNCHANGED** — same counts, same last-seen 1.3.5, latest event still 2026-06-13 17:51Z |
| client.dev | no results | no results | Unchanged |
| api release | 404 / no data | 404 / no data | Unchanged |
| api.dev | 404 / no data | 404 / no data | Unchanged |
| 1.3.6 / 1.3.7 / 0.2.6 / 0.2.7 | absent (0 events) | absent (0 events) | Unchanged — still no field sessions |

**No NEW issue appeared relative to the prior monitor.** Counts are byte-stable; no
new events ingested overnight (expected — testers asleep).

## Honest disposition

- **0 NEW crashes/ANRs/non-fatals from 1.3.6, 1.3.7, 0.2.6, or 0.2.7.** Those four
  version codes have produced **zero field events** — Crashlytics has not ingested a
  session from them yet.
- The single live issue (`47b000d5`) is the OLD/known Defect-B auth-gate 401, last
  seen on **1.3.5**, verified by 5 sample events (all 1.3.4/1.3.5) + `get_issue`
  first/last-seen. NOT a tonight regression.
- api-app variants: HTTP 404 = no Crashlytics data yet (freshly wired). HONEST.
- **No false "all clear":** the lack of 1.3.6/1.3.7/0.2.6/0.2.7 data is reported as
  the expected too-soon-after-distribute state, not as proof of stability. Stability
  of the tonight builds remains **UNCONFIRMED** until testers run them and the
  surface reports.

**Recommended follow-up:** re-run this heartbeat in 12–48h once testers have
installed the new builds; only then will 1.3.6/1.3.7/0.2.6/0.2.7 stability be
field-proven.
