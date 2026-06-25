# UI/UX Test-Coverage Audit — Lava `:app` (client) + `:api-app`

**Date:** 2026-06-25
**Scope:** Read-only source inspection of every client `feature/*` module + the `api-app` UI, mapped against existing Compose UI Challenge Tests (`app/src/androidTest/...`, `api-app/src/androidTest/...`) and per-feature ViewModel/unit tests (`feature/*/src/test/`, `api-app/src/test/`).
**Posture:** Anti-bluff (§6.AB / §6.J). GREEN = a test that drives the production code path and asserts on user-visible state (rendered text, persisted row, behavior). WEAK = a test exists but asserts presence/classpath only, not behavior. GAP = no UI test for that surface.

> **Honesty note:** "GREEN" here means the test is *written* to assert user-visible state. Per the repo-wide §6.X-debt, the `:app` client Challenge Tests have NOT been executed on an emulator on the current darwin/arm64 host (only the api-app tests use test-tags and have a documented run path). This audit rates the *design quality* of the assertions from source, not a green run. Where execution status matters it is called out.

---

## 1. Headline coverage stats

| Metric | Value |
|---|---|
| Client feature modules | 19 |
| User-facing screens (client) | ~22 (incl. onboarding 5 steps as one wizard) |
| api-app screens | 1 (`ApiControlScreen`) |
| Distinct high-risk interactive components (toggles/switches/dialogs/text-fields) | ~30 |
| Client Challenge Tests (`Challenge*Test.kt`) | 48 files |
| api-app Challenge Tests | 5 files (C01–C05) |
| Per-feature ViewModel/unit test files (client) | 38 |
| api-app unit test files | 9 |
| Features with ≥1 GREEN behavior-driving UI test | ~9 / 19 |
| Features WEAK only (Class.forName / reachable-marker) | 5 (visited, favorites, bookmarks, category, connection) |
| Features with **GAP** (no direct UI test) | account, main, search_input, credentials_manager, rating(partial) |
| **% of screens with a behavior-asserting UI test** | **~45%** |
| **% with only WEAK / classpath UI test** | **~25%** |
| **% GAP (no UI test)** | **~30%** |

**Bottom line: coverage is thin and uneven.** Onboarding, search, topic, and the api-app control screen are genuinely well-covered. The high-risk **provider_config toggles/switches/serialization** are covered only at the **unit (ViewModel) layer** — there is **no end-to-end UI Challenge that taps a Switch and asserts persisted+re-rendered state**. Five "reachable" screens have only `Class.forName` assertions (WEAK bluffs by §6.AB.3). Several screens have no UI test at all.

---

## 2. Highest-risk gaps (toggles / serialization / state-machines)

Ranked by the crash-class risk model (toggle/switch + serialization = highest, then state-machine flows, then static rendering):

### R1 — provider_config Switches + Mirror/Clone serialization (HIGHEST — the just-found crash class)
`feature/provider_config/sections/`: `SyncSection` (`Switch` → `ToggleSync`), `AnonymousSection` (`Switch` → `ToggleAnonymous`), `MirrorsSection` (TextField → `AddMirror`/`RemoveMirror`/`ProbeMirror`), `CloneSection` + `CredentialsSection` + `RemoveCloneSection` (dialogs, bind/unbind).
- **Unit layer:** GREEN — `ProviderConfigViewModelTest` covers Finding-1 (ToggleAnonymous persistence across VM recreation), Finding-10 (ToggleSync DAO-flip race), AddMirror malformed-URL rejection + toast. Solid, falsifiability-stamped.
- **UI/Challenge layer:** **GAP.** `Challenge04ProviderRowOpensConfigTest` only opens the screen and asserts `"Sync this provider"` is *displayed* — it never `performClick()`s the Switch, never asserts the persisted toggle survives, never exercises Mirror add / Clone create / Credential bind via the rendered UI. **The toggle/serialization crash class can only be caught end-to-end here, and no UI test does it.**

### R2 — api-app Copy-key + OpenClient buttons (HIGH — serialization/clipboard + cross-app launch)
`ApiControlScreen`: `CopyKeyClicked(key)` (clipboard + key string), `OpenClient` (SiblingAppLauncher intent / Firebase fallback).
- C01–C05 GREEN-drive Start/Stop/Restart (via `TAG_START`/`TAG_STOP` test tags). **GAP:** no challenge taps Copy-key or Open-client and asserts the side effect.

### R3 — search_input ProviderChipBar selection (HIGH — FilterChip toggle drives which providers get searched; prior bug: all-selected-by-default)
`SearchInputScreen` + `components/ProviderChipBar.kt` (FilterChips). **GAP — `search_input` has 0 direct challenge refs.** Search *result* flows are covered, but the chip-selection→submit boundary (a known prior defect surface) has only `SearchInputViewModelTest` at unit level.

### R4 — onboarding state-machine (MEDIUM — well covered, keep as reference pattern)
GREEN. 7 challenges (C20–C29, C37, C41, C42) + 6 unit tests cover wizard flow, back-press, anonymous provider, password masking, finish-requires-proved-provider, select-all. This is the **reference quality bar** other features should match.

### R5 — credentials / credentials_manager dialogs (MEDIUM — TextField + AlertDialog + secret handling)
`CredentialEditDialog` (TextField, save/cancel), `CredentialsManagerScreen` (TextField, dialogs). Unit tests exist (`CredentialsViewModelTest`, `CredentialsManagerViewModelTest`). UI: C47 (`CredentialsLockedSearchSurvives`) touches credentials indirectly. **WEAK/GAP** for the dialog edit→save→persist UI path and password-field masking in the manager.

### R6 — rating dialog + menu sign-out (MEDIUM — dialog state-machine)
`RatingDialog`, `MenuScreen` sign-out AlertDialog. C30(rating reachable), C24(menu sign-out) exist — C24 GREEN (sign-out flow), C30 likely WEAK (reachable).

---

## 3. Coverage matrix (feature/screen × component × test status)

Legend: **G** GREEN (behavior asserted) · **W** WEAK (presence/classpath only) · **—** GAP.

| Feature / Screen | Key interactive components | UI-test? | Unit-test? | Use-cases covered | Status |
|---|---|---|---|---|---|
| **onboarding** (Welcome/ApiSelection/Configure/Providers/Summary) | TextFields, Switch (anon), provider toggles, nav buttons, back-press | **G** (C20–29,37,41,42) | G (6 files) | full wizard, back, anon, password mask, finish-gate, select-all | **GREEN** |
| **provider_config** (`ProviderConfigScreen`) | **Switch ×2 (Sync/Anon)**, TextField (mirror), AlertDialogs (clone/remove/bind) | **W** (C04 opens screen, asserts label only) | **G** (toggle persist, AddMirror) | open-screen render; toggle persist (unit only) | **WEAK-UI / GAP for toggle-click E2E** |
| **search** (`SearchScreen`) | search box, nav | **G** (C02,03,09–12,38,40,44,46) | G | authed/anon search many providers | **GREEN** |
| **search_result** (`SearchResultScreen` + Filter* + CrossTrackerFallbackModal) | FilterChips, dropdowns, fallback modal accept/dismiss | **G** (C07,08,16,39,43) | G (6 files) | fallback accept/dismiss, api-filter, dedupe, streaming, retry, cancel | **GREEN** |
| **search_input** (`SearchInputScreen` + ProviderChipBar) | **FilterChip provider selection**, text field, submit | **—** | G (VM + nav roundtrip) | chip default-selection (unit only) | **GAP (UI)** |
| **topic** (`TopicScreen`) | download button, add-comment TextField, dialog | **G** (C05,06,37) | G (3 files) | view detail, download .torrent, add comment | **GREEN** |
| **login** (`LoginScreen`/`ProviderLoginScreen`/`OnboardingScreen`) | TextFields, Switch, submit, captcha | **G** (C02,36) | G (4 files) | login, service-unavailable accurate msg | **GREEN** |
| **menu** (`MenuScreen`) | nav rows, sign-out AlertDialog | **G** (C24 sign-out) | G (3 files) | sign-out flow, observe settings, sibling-app launch | **GREEN** |
| **topic/connection** (`ConnectionItem`/`ConnectionsList`) | TextField, dialog | **W** (C35 reachable) | G (ConnectionsVM) | item reachable (classpath) | **WEAK** |
| **category** (`CategoryScreen`) | dialog, list nav | **W** (C34 nav reachable) | G | navigation reachable | **WEAK** |
| **forum** (`ForumScreen`) | list nav | partial **G** (C34?) | G | tree nav (unit) | **WEAK/GREEN-partial** |
| **bookmarks** (`BookmarksScreen`) | list, item actions | **W** (C33 reachable) | G | screen reachable (classpath) | **WEAK** |
| **favorites** (`FavoritesScreen`) | list, item actions | **W** (C32 reachable) | G | screen reachable (classpath) | **WEAK** |
| **visited** (`VisitedScreen`) | list, item nav | **W** (C31 Class.forName) | G | classpath-only | **WEAK** |
| **rating** (`RatingDialog`) | dialog buttons | **W** (C30 reachable) | G | dialog reachable | **WEAK** |
| **credentials** (`CredentialsScreen`/`CredentialEditDialog`) | TextField, AlertDialog save/cancel, secret field | partial (C47 indirect) | G | locked-search survives | **WEAK/GAP for edit-dialog** |
| **credentials_manager** (`CredentialsManagerScreen`) | TextField, dialogs | **—** | G | none direct | **GAP (UI)** |
| **account** (`AccountItem`) | dialog | **—** | G (AccountVM) | none direct | **GAP (UI)** |
| **main** (`MainScreen`) | host/nav scaffold | **—** (covered transitively by launch challenges) | G (MainVM) | transitive only | **GAP (direct)** |
| **api-app** (`ApiControlScreen`) | Start/Stop/Restart buttons, **Copy-key**, **Open-client** | **G** (C01–05 drive Start/Stop/Restart) | G (5 VM tests + status labels) | boot/serve/stop/restart/notif/embed-hash | **GREEN (Copy/OpenClient = GAP)** |

---

## 4. Prioritized gap list

1. **provider_config toggle/serialization E2E UI** (R1) — tap Sync Switch → assert persisted + re-rendered; tap Anonymous Switch; AddMirror via TextField → assert row/toast in rendered UI; Clone dialog create→confirm; Credential bind→unbind. *Crash class. No UI test exists.*
2. **search_input ProviderChipBar** (R3) — toggle provider chips → submit → assert only-selected providers searched (prior bug surface).
3. **api-app Copy-key + Open-client** (R2) — drive both buttons, assert clipboard set / launch side effect.
4. **5 WEAK "reachable" screens** (visited, favorites, bookmarks, category, connection) — upgrade from `Class.forName` to render-and-interact: open screen, assert empty-state OR list rows, tap an item, assert nav/side-effect.
5. **credentials / credentials_manager dialogs** — edit-dialog TextField→save→persist; password masking in manager.
6. **account, main, rating** — direct render + primary-action tests (account dialog; main scaffold renders nav; rating submit persists rating).

---

## 5. Dispatch plan — 6 parallelizable waves

Each wave is independent (touches disjoint feature modules + disjoint `Challenge*Test.kt` files), so all 6 can run as parallel gap-filling agents. Every new test MUST: drive the real screen, primary-assert on user-visible state (§6.AB clause 1 rendering-correctness + clause 3 state-machine + clause 2 gating), and carry a §6.AB.3 non-crashing FALSIFIABILITY REHEARSAL block.

### Wave 1 — provider_config toggle + serialization E2E (HIGHEST RISK)
- `Challenge48ProviderConfigSyncTogglePersistsTest` — open ProviderConfig, `performClick` the "Sync this provider" `Switch`, assert checked-state flips AND (recreate/return) the persisted value is reflected.
- `Challenge49ProviderConfigAnonymousTogglePersistsTest` — same for Anonymous switch.
- `Challenge50ProviderConfigAddMirrorUiTest` — type a valid URL in the mirror TextField, submit, assert the mirror row renders; type malformed URL, assert corrective toast + no row.
- `Challenge51ProviderConfigCloneDialogTest` — open clone dialog, enter name+URL, confirm, assert cloned provider appears.
- Falsifiability: revert each toggle handler to no-op; assert the new test fails on the re-render assertion.

### Wave 2 — search_input chip selection (HIGH RISK, prior defect surface)
- `Challenge52SearchInputProviderChipSelectionTest` — render SearchInput, assert default-selected set matches onboarded providers (NOT all), toggle a chip off, submit, assert the de-selected provider is excluded from the search dispatch.
- Falsifiability: hardcode `selectedProviders = availableProviders`; assert test fails.

### Wave 3 — upgrade 5 WEAK reachable screens to behavior tests
- Rewrite C31 (visited), C32 (favorites), C33 (bookmarks), C34 (category), C35 (connection) from `Class.forName` → `createAndroidComposeRule`, navigate to the screen, assert empty-state OR ≥1 list row renders, tap an item, assert the resulting nav/side-effect (user-visible).
- Falsifiability: break the nav entry; assert the render/empty-state assertion fails.

### Wave 4 — api-app Copy-key + Open-client UI
- `Challenge06ApiAppCopyKeyTest` — boot+start, tap Copy on the access key, assert clipboard contains the key (or the confirmation side effect renders).
- `Challenge07ApiAppOpenClientTest` — tap "Open Lava client", assert the `LaunchClient` side effect intent is produced (installed vs Firebase fallback path).
- Falsifiability: make `CopyKeyClicked` a no-op; assert test fails.

### Wave 5 — credentials + credentials_manager dialogs
- `Challenge53CredentialEditDialogSavePersistsTest` — open edit dialog, type into username/password TextFields, save, assert the credential persists and re-renders; assert password field is masked.
- `Challenge54CredentialsManagerAddDeleteTest` — add a credential via dialog, assert it lists; delete it, assert it's gone.
- Falsifiability: drop the save handler; assert test fails.

### Wave 6 — account, main, rating direct render + primary action
- `Challenge55AccountScreenRendersAndActsTest` — render account, trigger its primary dialog/action, assert user-visible outcome.
- `Challenge56MainScaffoldRendersNavTest` — assert MainScreen renders the bottom-nav entries (not transitive-only).
- `Challenge57RatingDialogSubmitPersistsTest` — open rating dialog, select a rating, submit, assert persisted/side-effect (upgrade C30 from reachable→behavior).
- Falsifiability per test as above.

---

## 6. Notes for the dispatching stream

- **Onboarding (C20–29 etc.), search/search_result, topic, login, menu, api-app Start/Stop** are the GREEN reference patterns — copy their assertion style (`onNodeWithText(...).performClick()` → `waitUntil` → `assertIsDisplayed`/persisted-row check), NOT the `Class.forName` anti-pattern from C31–C35.
- All new client Challenge Tests share the §6.X-debt execution caveat: source-written + scanner-verified on darwin/arm64, EXECUTED on a Linux x86_64 + KVM gate-host (or per §6.AH container/VM path). State this honestly in each test's KDoc.
- The unit (ViewModel) layer is comparatively strong (38 client + 9 api-app test files). The gap is overwhelmingly at the **end-to-end rendered-UI layer**, which is exactly where the toggle/serialization crash class lives.
