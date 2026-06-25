# Lava Android — Video Issue Analysis (vision-analyzed)

**Source video:** `Screen_Recording_20260625_194837_Lava.mp4` (recorded 2026-06-25 19:48, copied from `nezha.local`)
**Duration:** 77.32 s · **Resolution:** 1080×2316 (H.264, 90000/1 nominal fps) · **Frames analyzed:** 155 PNGs extracted at 2 fps (frame N ⇒ timestamp `(N-1)/2` s)
**Method:** every 5th frame read end-to-end across the full journey; ambiguous regions sampled at finer granularity. Only issues actually visible in frames are reported; each cites its frame timestamp. Anti-bluff: no issue is inferred without a frame showing it.

## User journey observed
Launcher (frame 0001, ~0s) → tap Lava (frame 0005) → **Welcome to Lava** (frames 0007–0010, ~3–4.5s) → **Choose your API** (frames 0012–0017, ~5.5–8s) → **Pick your providers** (frames 0020–0030, ~9.5–14.5s; Select-all then deselect to only **YTS**) → **All set! (YTS)** (frame 0032, ~15.5s) → **Search** home (frame 0035, ~17s) → search-input with provider chips (frames 0040–0046) → typed "die hard" → **blank results** (frames 0060–0110) → **Error "Something went wrong"** (frame 0115, ~57s) → back, retype, **blank again then Error** (frames 0120–0140, ~59.5–69.5s) → back, tap "prince" → **blank results, no error** (frame 0150, ~74.5s) → history (frame 0155, ~77s).

---

## Distinct issues found: 11

Ordered by severity (Critical → Low).

### 1. [CRITICAL] Search returns ZERO results, then "Something went wrong" Error
- **Screen:** Search results.
- **Frames:** results blank `0060–0110` (~29.5–54.5s); **Error + Retry** at `0115` (~57s) and again `0140` (~69.5s). A second query ("prince") shows blank with no content and no error at `0150` (~74.5s).
- **What's wrong:** Every search in the recording fails. "die hard" renders an empty body (no list, no spinner, no "no results" empty-state) for ~25 s, then flips to the full-screen **Error / "Something went wrong, please try again later" / Retry** state. The user retries — same outcome. "prince" leaves a permanently blank screen. No search ever produced a single result row.
- **Severity:** Critical — the app's primary function (search) is unusable end-to-end.
- **KNOWN/NEW:** Partially KNOWN class (§6.L 57th cycle "search fails for anonymous-onboarded providers"; §6.L 59th "Bug 2 anonymous-only search" 3-layer cascade). The on-screen result here (universal blank→error) is consistent with that class but the recording shows it for the YTS-onboarded path → treat as **KNOWN-class, re-confirmed in production**.
- **Suspected root-cause area:** `SearchResult` ViewModel/UseCase provider resolution + the actual provider HTTP/scrape call. The error appears AFTER a long blank, suggesting a request is dispatched but fails (timeout / parse / wrong provider set). Investigate `feature/search_result` + `core/data` provider search path and the API selected (192.168.0.107:8443 Lava-API).
- **Reproduce:** Onboard only YTS → Search "die hard" → observe blank for ~25s → Error/Retry.

### 2. [CRITICAL] Onboarded provider (YTS) is NOT the provider set used by Search
- **Screen:** Onboarding "All set!" vs Search input vs Search results.
- **Frames:** onboarding configured **only YTS** (`0030` checkbox, `0032` "All set! YTS"). But the search-INPUT chip bar shows **RuTracker / RuTor / Internet Archive / Gutenberg** (`0040`, `0045`, `0090`, `0120`). The search-RESULTS filter chips show yet another set: **All / torrentdownloads / kinozal / yts** (`0060`) and later **All / torrentdownloads / archiveorg / kinozal / ru…** (`0125–0130`).
- **What's wrong:** Three different provider sets across three surfaces, none of which is "just YTS" as configured. The user's onboarding choice is not honored, and providers never selected/configured (RuTracker, RuTor, Gutenberg, Internet Archive, kinozal, archiveorg) appear as active search filters.
- **Severity:** Critical — directly causes Issue #1 (searching providers the user never configured/authenticated → failures) and breaks the onboarding contract.
- **KNOWN/NEW:** **KNOWN** (§6.L 57th "Search selects all providers as filters, even ones not configured during onboarding! These shall be unselected by default!"; §6.L 59th "Bug 2/Bug 3" search chip-bar onboarded-only). Re-confirmed still broken in this build.
- **Suspected root-cause area:** `SearchInputViewModel` provider-default resolution + `ProviderConfigRepository.observeAll()` filtering by `searchEnabled && isEnabled`; the chip set must derive from onboarded+enabled providers, and the input chips vs result chips must agree.
- **Reproduce:** Onboard only YTS → open Search → observe chip bar lists RuTracker/RuTor/Internet Archive/Gutenberg instead of YTS.

### 3. [HIGH] Inconsistent provider sets between search-input chips and search-results chips
- **Screen:** Search input vs Search results.
- **Frames:** input chips = RuTracker/RuTor/Internet Archive/Gutenberg (`0040`,`0120`); results chips = torrentdownloads/kinozal/yts (`0060`) THEN torrentdownloads/archiveorg/kinozal/ru… (`0125`). The results chip set even CHANGES between two searches of the same query.
- **What's wrong:** The two screens disagree on which providers are in scope, and the results screen's own chip set is non-deterministic across invocations of the identical query.
- **Severity:** High — confuses scope, signals the provider-selection state is not a single source of truth.
- **KNOWN/NEW:** **NEW** (the input-vs-results divergence and run-to-run instability of the results chip set is a distinct observation from #2's onboarding mismatch).
- **Suspected root-cause area:** results-screen filter chips built from response-returned providers rather than the requested set; race in which providers respond first. Align both surfaces to the configured provider list.
- **Reproduce:** Search same term twice; compare results filter chip order/content (`0060` vs `0125`).

### 4. [HIGH] Provider id labels shown lowercased / unspaced ("torrentdownloads", "archiveorg", "kinozal", "yts")
- **Screen:** Search results filter chips.
- **Frames:** `0060`, `0125`, `0130` — chips read `torrentdownloads`, `archiveorg`, `kinozal`, `yts`, `ru…` (raw ids), whereas elsewhere the same providers are shown as proper display names ("TorrentDownloads", "Internet Archive", "Kinozal.tv", "YTS", "RuTracker").
- **What's wrong:** The results chips render the internal provider key instead of the human display label — the same class as the §6.L 60th "underscore-in-subtitle" `displayLabel()` bug, here manifesting as lowercased/concatenated ids.
- **Severity:** High (polish/correctness; user-facing wrong text).
- **KNOWN/NEW:** **KNOWN-class** (§6.L 60th `displayLabel()` title-casing; §6.AB white-icon/underscore class). NEW surface (results filter chips specifically).
- **Suspected root-cause area:** results filter chip composable uses `descriptor.id` instead of `descriptor.displayName`/`displayLabel()`.
- **Reproduce:** Run any search → look at results filter chips.

### 5. [HIGH] No empty-state and no loading indicator on search results
- **Screen:** Search results.
- **Frames:** `0060–0110` — pure black body for ~25 s with no spinner, no skeleton, no "Searching…", no "No results". Only after that does the Error state appear (`0115`).
- **What's wrong:** During the in-flight period the UI gives zero feedback; the user cannot tell if it's loading, empty, or hung. (The "prince" search at `0150` stays blank with no error at all.)
- **Severity:** High UX defect (perceived hang / §6.AB "rendering presence vs correctness" / state-machine completeness gap).
- **KNOWN/NEW:** **NEW** (distinct from the Error itself — this is the missing loading/empty state that precedes/replaces it).
- **Suspected root-cause area:** `SearchResultState` lacks/!renders `Loading` and `Empty` branches; the "prince" path suggests a state where neither Loading, Content, Empty, nor Error is rendered (stuck Idle).
- **Reproduce:** Search anything → observe blank with no spinner; search "prince" → blank with no error indefinitely.

### 6. [MEDIUM] Welcome screen claims "4 providers available" but provider list has ~12
- **Screen:** Welcome / Pick your providers.
- **Frames:** "4 providers available" at `0007`/`0010`/`0015` (Welcome). The picker (`0020`,`0025`,`0030`) lists at least: TorrentDownloads, Internet Archive, Kinozal.tv, BitSearch, NNM-Club, RuTracker, Nyaa, Torrents-CSV, YTS, Tokyo Toshokan, The Pirate Bay (~11–12).
- **What's wrong:** The "4 providers available" count is wrong vs the actual selectable provider count.
- **Severity:** Medium (misleading copy on first-run screen).
- **KNOWN/NEW:** **NEW**.
- **Suspected root-cause area:** Welcome screen provider-count source (hardcoded `4`? or a stale/filtered count) vs the onboarding descriptor registry. Bind the count to the real descriptor list size.
- **Reproduce:** Fresh launch → Welcome shows "4 providers available" → Get Started → count the picker rows (>4).

### 7. [MEDIUM] "Choose your API" lists a "lava.app:7777" cloud preset that looks like a hardcoded literal
- **Screen:** Choose your API (Cloud / remote server).
- **Frames:** `0012`,`0015` — under "Cloud / remote server" a preset row `lava.app:7777 — Lava API · On this network`.
- **What's wrong:** UNCONFIRMED root cause, but a literal `host:port` preset (`lava.app:7777`) is surfaced in the UI. If it is a hardcoded literal in source it would violate §6.R no-hardcoding; if it is a discovered/configured preset it is fine. Additionally it is labeled "On this network" while being a cloud/remote preset, which is inconsistent.
- **Severity:** Medium (potential §6.R violation + mislabeled).
- **KNOWN/NEW:** **NEW** (UNCONFIRMED whether literal is hardcoded — needs source check of the API-selection screen / preset list).
- **Suspected root-cause area:** API-selection screen preset list; verify `lava.app:7777` comes from config/.env, not a string literal; fix the "On this network" label for remote presets.
- **Reproduce:** Onboarding → Choose your API → see `lava.app:7777` preset row.

### 8. [MEDIUM] mDNS-discovered API shows raw IP `192.168.0.107:8443` with no friendly name
- **Screen:** Choose your API (On your network).
- **Frames:** `0012`,`0015` — "Found 1 API: 192.168.0.107:8443 / Lava API · On this network · Android device".
- **What's wrong:** The discovered endpoint is presented as a bare IPv4:port. Cosmetic/UX (not a hardcoded-literal violation since it's runtime-discovered), but combined with later search failures it is worth confirming the selected API actually serves results.
- **Severity:** Medium (UX; also a lead for Issue #1 — is the selected Lava-API reachable/serving?).
- **KNOWN/NEW:** **NEW** (UX observation).
- **Suspected root-cause area:** API-selection display formatting; and verify the chosen Lava-API endpoint health relative to the search failures.
- **Reproduce:** Choose your API screen shows the raw IP row.

### 9. [LOW] Onboarding picker shows providers with "Captcha Login" / "Form Login" mixed with "None" without guidance
- **Screen:** Pick your providers.
- **Frames:** `0020`,`0025` — RuTracker "Captcha Login", NNM-Club/Kinozal.tv "Form Login", others "None". Selecting "Select all" (`0022`) checks captcha/form-login providers that then require credentials the user hasn't supplied — feeding the search-failure path.
- **What's wrong:** "Select all" enables auth-requiring providers silently; combined with #2 this likely contributes to search errors for providers needing login/captcha. No warning that selecting them requires credentials.
- **Severity:** Low/Medium (UX + contributes to #1).
- **KNOWN/NEW:** **NEW** (UX observation; related to known anonymous-vs-auth provider handling).
- **Suspected root-cause area:** onboarding "Select all" should not silently enable auth-required providers, or must route them to credential entry.
- **Reproduce:** Pick providers → Select all → providers needing captcha/login get checked.

### 10. [LOW] App launched from a co-mingled launcher icon set (app-ID hygiene, UNCONFIRMED)
- **Screen:** Android launcher.
- **Frames:** `0001` (launcher grid), `0005` (Lava in "Suggested apps").
- **What's wrong:** UNCONFIRMED — the recording shows a single "Lava" icon being launched; I cannot see two co-installed Lava builds (debug `.dev` + release) in the captured frames. The known app-ID co-mingling concern is NOT visually confirmed here. Flagged only so the fix cycle verifies debug/release app-IDs are distinct and not both surfaced as "Lava".
- **Severity:** Low.
- **KNOWN/NEW:** **KNOWN-concern, UNCONFIRMED in video** (app-ID co-mingling).
- **Suspected root-cause area:** `applicationIdSuffix .dev` + launcher label; verify debug and release do not both label "Lava".
- **Reproduce:** Not reproduced in video; check installed packages on device.

### 11. [LOW] No crash/ANR observed in the recording (negative finding — recorded for completeness)
- **Screens:** all.
- **Frames:** entire `0001–0155`.
- **What's wrong:** Nothing — the app did NOT crash, ANR, or show a white/monochrome-icon screen in this recording. The brand logo renders correctly red (`0007`,`0010`). The previously-known LVA-008 search→back nav-teardown crash and the Sync-toggle SerializationException crash were NOT triggered on screen here (the user backed out of search results to history at `0080`/`0145` without a crash; no provider Sync toggle was exercised).
- **Severity:** N/A (informational).
- **KNOWN/NEW:** Records that **LVA-008 (search→back crash)**, the **Sync-toggle crash (ProviderConfigViewModel.kt:92)**, and the **§6.AB white-icon** defects were NOT reproduced in this video. They remain open per their tickets but are out of scope for THIS recording.

---

## Top 5 most severe
1. **#1 [CRITICAL]** Search returns zero results then "Something went wrong" — primary function unusable (`0060–0140`).
2. **#2 [CRITICAL]** Onboarded provider (YTS) not the set used by Search; un-configured providers active as filters (`0030` vs `0040`/`0060`).
3. **#3 [HIGH]** Search-input chips vs results chips disagree AND results chip set is non-deterministic run-to-run (`0040` vs `0060` vs `0125`).
4. **#4 [HIGH]** Provider ids shown raw/lowercased ("torrentdownloads", "archiveorg") instead of display names (`0060`,`0125`).
5. **#5 [HIGH]** No loading indicator and no empty-state on search results — perceived hang; "prince" stays blank with no error (`0060–0110`,`0150`).

## KNOWN vs NEW summary
- **KNOWN / KNOWN-class (re-confirmed in production):** #1 (anonymous/provider-mismatch search-failure class), #2 (onboarding-vs-search provider mismatch, §6.L 57th/59th), #4 (`displayLabel()` raw-id class, §6.L 60th), #10 (app-ID co-mingling — UNCONFIRMED in video).
- **NEW:** #3 (input/results chip divergence + run-to-run instability), #5 (missing loading/empty state), #6 ("4 providers available" count wrong), #7 (`lava.app:7777` preset literal — UNCONFIRMED hardcoded), #8 (raw IP display), #9 (Select-all enables auth providers silently).
- **Not reproduced in this video (remain open elsewhere):** LVA-008 search→back crash, Sync-toggle SerializationException crash, §6.AB white-icon.

## Notes / honesty
- Frame timestamps assume uniform 2 fps extraction (frame N ⇒ `(N-1)/2` s); the device clock advanced 19:47→19:48 across the recording, consistent with ~77 s.
- Items marked UNCONFIRMED (#7 literal, #8 reachability, #10 app-ID) require a source/device check the video alone cannot settle.
