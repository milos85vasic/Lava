# Product Known-Issues + Field-State Audit — 2026-06-14

| | |
|---|---|
| Revision | 1 |
| Created | 2026-06-14 |
| Author | claude-opus-4-8 (READ-ONLY audit subagent) |
| HEAD | `357f867` release(1.3.9/0.2.9) |
| Mirrors | GitHub + GitLab both at `357f867` (CONVERGED) |
| Method | Crashlytics MCP (`crashlytics_get_report`), incident JSONs, doc grep, `git status` |

This is an evidence-cited audit of the broader PRODUCT known-issues / field
state / open incidents / doc-sync gaps so the master plan can address them.
Per §11.4.6: every row is fact-with-evidence or explicitly marked UNCONFIRMED.

## Prioritized known-issues table

| # | Issue | Status | Severity | Source-of-truth (evidence) | Action needed |
|---|---|---|---|---|---|
| 1 | **LVA-008 — NavBackStackEntry `search_input` teardown crash** | **OPEN** (gate-RED) | **CRITICAL** | `.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json` | Upstream androidx-navigation defect. 5 app-level fix classes device-FALSIFIED (nav-bump, LenientTeardownRule, nested-host move, atomic popUpTo, NavTeardownGuard). Real user-impact: search-then-activity-destroy (rotate/dark-mode/locale/process-death) crashes. Gates C06 (download) + C11 (archiveorg search). **Operator decision owed:** (a) file androidx minimal repro, OR (b) accept-with-§6.AC-telemetry + distribute. |
| 2 | **RuTracker password rotation OWED** (historical git-history leak) | **OPEN** (operator action) | **HIGH (security)** | `2026-05-17-credentials-historical-leak-h-violation.json` + `2026-05-13-tracker-credentials-in-git-history.json` | Live surface redacted in `d7d4572a`; working tree clean (`git ls-files \| grep` empty). BUT per §6.H clause 6 the **operator must still rotate** the RuTracker (+RuTor/Kinozal/NNMClub) password — leaked creds remain valid at rutracker.org for anyone who pulled an older commit. History purge (force-push, §6.T.3) also OWED pending explicit operator authorization. |
| 3 | **issue3 search-results video — now PRODUCIBLE, OWED re-record** | **OPEN** (deliverable) | **MEDIUM** | `.lava-ci-evidence/video-analysis/2026-06-14-issue-videos-rerecord.md`; CONTINUATION §0 (search FIXED in 1.3.9-1066) | The 3 delivered videos (issue1/2/4) analyzer-PASS but were recorded against **1.3.8-1065 where search was still broken**, so issue3 was honestly NOT produced. Search is now FIXED (1.3.9-1066). issue3 (real "prince" results render) is now reproducible and should be re-recorded. **None of the 4 issue videos are in `~/Downloads`** (only unrelated `HelixCode_TUI_…mp4`). Operator asked repeatedly for the 4 success-videos in `~/Downloads` → still UNDELIVERED. |
| 4 | **2 FGS api-app FATAL issues still firing** (`9ba8502e…` + `b9baeaede…`) | **OPEN** (Crashlytics) | **MEDIUM** | `crashlytics_get_report` topIssues, app `456475e2…` | Both FATAL, `firstSeen=lastSeen=0.2.6`, **SIGNAL_FRESH** (first appeared in window). `ForegroundServiceStartNotAllowedException` / `ForegroundServiceDidNotStopInTimeException` on `ApiEngineService` dataSync. Closure logs exist (`2026-06-14-apiapp-fgs-datasync-budget.md`). Per §6.O: need console close-mark once the 0.2.9 fix is confirmed in the field. **No 0.2.9-14 events yet** (newest api-app field version is 0.2.6-10) → cannot yet close-mark; field-confirm first. |
| 5 | ProviderCatalogRepository HTTP-401 NON_FATAL (`47b000d5…`) | OPEN (Crashlytics, stale ver) | LOW | `crashlytics_get_report`; closure `2026-06-13-providers-catalogue-401-auth-gated.md` | 6 events / 1 user, `firstSeen 1.3.4 / lastSeen 1.3.5`. Auth-gated catalogue 401 — addressed by the 1.3.9 key-handoff fix. Field-confirm on 1.3.9 then close-mark. |
| 6 | OkHttp CertPath NON_FATAL (`042b9b61…`) | OPEN (Crashlytics, stale ver) | LOW | `crashlytics_get_report` | 1 event, `1.3.3` only. Self-signed TLS trust-anchor; expected for LAN self-signed cert. Monitor; likely benign. |
| 7 | §6.X / §6.AH darwin-arm64 emulator gap (no in-container KVM/HVF) | OPEN (debt) | MEDIUM (process) | `2026-05-13-emulator-container-darwin-arm64-gap.json`; §6.AH-debt | Container/VM emulator path does not boot on this macOS host. Gate runs use Genymotion VM (§6.AH path). Standing debt; not a field-crash. |
| 8 | Genymotion surface-render timeout (PENDING_FORENSICS) | RESOLVED-with-workaround | LOW (env) | `2026-06-09-genymotion-surface-render-timeout.json` | Resolved by waking VM screen (`KEYCODE_WAKEUP` + `svc power stayon true`). PENDING_FORENSICS only on the durable GPU-accel fix; operational workaround in place. |
| 9 | emulator-boot-offline (2026-06-03) PENDING_FORENSICS | OPEN (forensics) | LOW (env) | `2026-06-03-emulator-boot-offline.json` | Host-direct+HVF macOS emulator wedge that birthed §6.AH. Superseded operationally by Genymotion path. |

## Field-state summary (Crashlytics)

- **MCP available + authenticated** — `crashlytics_get_report` returned live data.
- The appId `1:815513478335:android:456475e2ef4039d8cfd20a` is a **single combined
  Crashlytics app** receiving BOTH client (`1.3.x`) AND api-app (`0.2.x` /
  `digital.vasic.lava.api`) events. The `.api` (`d57b960e…`) and `.api.dev`
  (`2932451e…`) appIds returned **no results** (empty) — telemetry routes to the
  combined app.
- **Shipped builds 1.3.9-1066 / 0.2.9-14 (and 1067/15) have ZERO field events**
  in the last 7 days — newest field versions are **client 1.3.5-1062** and
  **api-app 0.2.6-10**. The shipped fixes have NOT yet been exercised by field
  users. **No OPEN crash is attributed to the shipped versions** — all OPEN
  issues are on older versions (≤1.3.5 / ≤0.2.6).
- The 2 FGS FATALs (#4) are the only FATAL issues; both stale at 0.2.6.

## §6.H credential state (OWED operator actions)

| Leak | Committed? | Rotated? | Owed |
|---|---|---|---|
| RuTracker `nobody85perfect`/`ironman1985` (+RuTor/Kinozal/NNMClub) | Was in git history; redacted `d7d4572a`; working tree clean | **NO — rotation OWED** | Operator: rotate passwords (§6.H cl.6). Optional: history purge + force-push (§6.T.3 auth). |
| Firebase CI token echo (67th cycle) | Never committed (transcript-only) | **YES — RESOLVED** | None (operator rotated in 68th cycle). |

## Doc-sync / §6.S state

- `docs/CONTINUATION.md` §0 **Last updated: 2026-06-14** — **CURRENT** with HEAD
  (`357f867`, 2026-06-14). Compliant.
- `CHANGELOG.md` has entries for the distributed `1.3.9-1066` + `0.2.9-14`. The
  §6.Y post-distribute bump to `1067 / 15` correctly has **no** CHANGELOG entry
  (bump-first, nothing published at 1067/15 yet) — §6.Y/§6.P compliant.
- No CLAUDE.md ↔ AGENTS.md ↔ QWEN.md drift surfaced in this pass (not exhaustively diffed — UNCONFIRMED beyond §0/CHANGELOG).

## Working-tree noise (`git status` — 18 entries)

| Path | Kind | Recommendation |
|---|---|---|
| `core/apiengine/src/main/resources/api-source.hash` | 1-line regen drift (M) | Commit with next build or `git checkout` if spurious |
| `submodules/tracker_sdk` | submodule pointer moved (M) | Decide: intended pin bump (commit) vs accidental (reset) — pins are frozen by default per Decoupled-Reusable-Arch |
| `.lava-ci-evidence/stress-chaos/jackett/*.json` (5) | §11.4.85 regen noise (M, 16±/16∓) | Commit or revert; non-load-bearing churn |
| `.lava-ci-evidence/challenge-video/2026…/` (6 dirs, untracked) | raw challenge recordings | Curate-evidence-only per §11.4.128 (raw should be gitignored) |
| `.lava-ci-evidence/video-analysis/2026-06-14-rerecord/*.mp4` (4, untracked) | raw issue videos | Same — curate, don't commit raw mp4 |
| `.lava-ci-evidence/search-verification/2026-06-14-results-screen.png` (untracked) | search-fix proof screenshot | Curated evidence — may commit |

## Top operator-owed actions (for the master plan)

1. **DECIDE LVA-008** (#1) — accept-with-§6.AC-telemetry + distribute, OR file androidx repro. This is the single CRITICAL gate blocker.
2. **ROTATE RuTracker (+RuTor/Kinozal/NNMClub) credentials** (#2) — standing §6.H clause-6 obligation, still OWED.
3. **RE-RECORD issue3 search-results video on 1.3.9-1066 + DELIVER all 4 to `~/Downloads`** (#3) — search now works; the deliverable is producible and still undelivered.
4. **Field-confirm + console close-mark the 2 FGS FATALs + the 401 NON_FATAL** (#4/#5) once 0.2.9/1.3.9 reach the field (no events yet).
