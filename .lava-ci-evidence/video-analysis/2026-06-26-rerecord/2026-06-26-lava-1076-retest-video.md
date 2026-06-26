# Lava Android 1.3.12-1076 RETEST — Video Issue Analysis (vision-analyzed)

**Source video:** `Screen_Recording_20260626_122718_Lava.mp4` (recorded 2026-06-26 12:27 local / 09:27Z — AFTER the 09:14Z Firebase distribute of 1076, so this IS the distributed 1076 build), copied from `nezha.local:/run/media/milosvasic/DATA4TB/Downloads/Incoming/Video_Recordings/`.
**Duration:** 2:19 (139s) · 1080×2316 · 278 PNG frames @ 2 fps (frame N ⇒ (N-1)/2 s).
**Operator verdict:** "Practically almost nothing has been fixed!!! Same issues over and over." CONFIRMED below.

## CONFIRMED STILL-BROKEN on the distributed 1076 (the operator is right)

### A. [CRITICAL] Search STILL fails — "Something went wrong, please try again later"
- Frames: search input chips Kinozal.tv/Rutracker/Torrentdownloads/Yts (~f0225); search "1080p" → blank body w/ tiny loading dot (~f0250); **Error + Retry** (~f0275, ~137s).
- This is video issue #1 from 2026-06-25, UNFIXED. The 1076 CHANGELOG claimed "search works again" — it does NOT for the user. The search request to the selected API (on-device api-app engine at 192.168.0.107, or providers rutracker/torrentdownloads/yts) fails.
- Root cause NOT yet obtained — needs the §6.AC Crashlytics readout (http_status/base_url_host/error) OR on-device adb logcat OR engine-side reproduction. Tracked: LVA-083 REOPENED.

### B. [HIGH] Results filter chips render RAW LOWERCASE provider IDs
- Frame ~f0250: results chips read `rutracker`, `torrentdownloads`, `yts` (raw ids) — NOT display names (Rutracker/TorrentDownloads/YTS). The INPUT chips (~f0225) DO show capitalized names; the RESULTS chips do not.
- This is video issue #4, UNFIXED in the RESULTS surface. The 1076 "#4 friendly chip names" fix touched the INPUT chips only.
- **Self-inflicted §6.AB bluff:** this session's LVA-079 "fix" made the results chips render the sorted raw `providerIds`, and its JVM test asserted on the id LIST, never on the rendered DISPLAY LABEL — so it passed while the user still sees `rutracker`. Tracked: LVA-085 REOPENED + the LVA-079 test is a discrimination bluff to rewrite.

### C. [HIGH] Search-input chips vs results-filter chips DISAGREE
- Input (~f0225): Kinozal.tv, Rutracker, Torrentdownloads, Yts. Results (~f0250): All, rutracker, torrentdownloads, yts — **Kinozal.tv missing**.
- Video issue #3, UNFIXED. Tracked: LVA-079 REOPENED.

## Device-test reproduction (thinker containerized-KVM, 1076)
- `Challenge52SearchInputProviderChipSelectionTest` FAIL, `Challenge48ProviderSyncToggleSurvivesAndPersistsTest` FAIL — BOTH crash identically: `IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED'` at `Activity.performDestroy` on nested routes (`search/search_input`, `provider_config/{provider_id}`) = **LVA-008 nav-teardown crash**, systemic across nested routes.
- `Challenge41/Challenge20/Challenge16/Challenge47` PASS.
- Evidence: `.lava-ci-evidence/1076-repro/`.
- Note: LVA-008 fires at instrumentation activity-destroy; this video shows NO crash-to-launcher, so its USER-facing impact is UNCONFIRMED here — but it crashes any device test that navigates a nested route at teardown, which is why the gate (had it run these) would have blocked the ship.

## What this proves (root cause of "tests green, features broken")
The §6.Z gate that authorized the 1076 distribute executed ONLY Challenge00CrashSurvivalTest (cold-start). The Challenges covering search/provider/chips (C52, C48, ...) were NEVER executed and FAIL on the build. Births §6.AK Cycle-Coverage Device Gate. Incident: `.lava-ci-evidence/sixth-law-incidents/2026-06-26-c00-only-gate-shipped-broken-flows.json`.

## What does work (1076)
- Forum browsing loads real RuTracker categories (~f0120). Onboarding flows (C20/C41 PASS). Provider config screen renders (~f0200). Credentials passphrase flow reachable (~f0080/f0160).
