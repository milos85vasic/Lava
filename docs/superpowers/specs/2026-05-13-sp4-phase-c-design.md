# SP-4 Phase C — Trackers Screen Removal (detailed design)

**Spec date:** 2026-05-13
**Parent SP:** `docs/superpowers/specs/2026-05-12-sp4-multi-provider-redesign.md`
**Phase B (commit 85af95b3) prerequisite:** complete.
**Status:** detailed-design locked. Implementation plan in `docs/superpowers/plans/2026-05-13-sp4-phase-c-implementation.md`.

## Goal (from parent SP-4 design)

> **Phase C — Trackers screen removal.** Replace the screen with a multi-provider summary OR delete from the navigation graph. C04-C08 Challenges remapped or rewritten honestly.

## Current state (post Phase B, commit `85af95b3`)

The `:feature:tracker_settings` module (the Trackers screen) was the v1.2.x home for per-provider management:

| Trackers screen capability | Where it lives now |
|---|---|
| Active-tracker radio selector | only on Trackers screen — `TrackerSelectorList` |
| Per-tracker mirror health table | duplicated by `:feature:provider_config` `MirrorsSection` |
| "Add custom mirror" dialog | duplicated by `MirrorsSection` add-row |
| "Probe now" | duplicated by `MirrorsSection` per-mirror Probe button |
| Per-mirror Remove (user-added) | duplicated by `MirrorsSection` |

Per the SP-4 design, **multi-provider parallel search (Phase D)** removes the concept of a single "active tracker" — `LavaTrackerSdk.multiSearch` fans out to every selected provider. Once Phase D lands, the active-tracker radio is obsolete by construction.

**Net result:** of the five Trackers-screen capabilities above, four are already provided by ProviderConfig (Phase B). The fifth (active-tracker radio) is a v1.2.x-transitional concept that goes away in Phase D.

## Design decision: delete, don't replace

The Phase C scope in the parent SP-4 design offered two paths (multi-provider summary OR deletion). The locked decision is **deletion** for these reasons:

1. **No unique surface.** Every Phase B `:feature:provider_config` screen renders the same per-provider mirror table the Trackers screen does. A "multi-provider summary" screen would duplicate the Menu's existing provider list, which already shows every provider with health-dot-equivalent state (color dot, auth icon).
2. **§6.J anti-bluff.** Adding a summary screen that duplicates Menu's provider list would be theatre — two screens showing the same data via two code paths. Bug fixed in one would silently rot in the other.
3. **Phase D obsolescence.** Even a thin summary screen would be obsolete two phases later. Building it just to delete it is waste.
4. **Smaller blast radius.** Deletion is a structural simplification; replacement is an additional surface to maintain. Per the operator's no-half-implementations rule, simplification is preferred.

The single transitional capability — active-tracker switching — moves to a `Make active provider` button inside `:feature:provider_config`. It's a one-line affordance, not a screen. Removed entirely when Phase D ships.

## Affected surfaces

### Files to delete

- `feature/tracker_settings/` — entire module (build.gradle.kts, manifest, all .kt sources, tests, resources)
- `app/src/androidTest/kotlin/lava/app/challenges/Challenge14TrackerSettingsOpenTest.kt` — the screen this Challenge opens is gone; the test becomes vacuous

### settings.gradle.kts

Remove `include(":feature:tracker_settings")`.

### app/build.gradle.kts

Remove `implementation(project(":feature:tracker_settings"))`.

### app/src/main/kotlin/.../navigation/MobileNavigation.kt

Remove:
- `import lava.feature.tracker.settings.addTrackerSettings`
- `import lava.feature.tracker.settings.openTrackerSettings`
- The `addTrackerSettings(...)` registration block
- The `openTrackerSettings = { openTrackerSettings() }` parameter pass-through
- The `openTrackerSettings: () -> Unit` parameter from `addNestedNavigation` + `addMenu`

### feature/menu/src/main/kotlin/lava/menu/MenuAction.kt

Remove `data object TrackerSettingsClick : MenuAction`.

### feature/menu/src/main/kotlin/lava/menu/MenuSideEffect.kt

Remove `data object OpenTrackerSettings : MenuSideEffect`.

### feature/menu/src/main/kotlin/lava/menu/MenuViewModel.kt

Remove `onTrackerSettingsClick()` private method + the `is MenuAction.TrackerSettingsClick -> onTrackerSettingsClick()` branch.

### feature/menu/src/main/kotlin/lava/menu/MenuScreen.kt

Remove:
- `openTrackerSettings: () -> Unit = {},` parameter (and from the inner overload)
- The `is MenuSideEffect.OpenTrackerSettings -> openTrackerSettings()` case in `collectSideEffect`
- The `menuItem(text = { Text("Trackers") }, onClick = { onAction(MenuAction.TrackerSettingsClick) })` LazyList entry

### feature/menu/src/test/kotlin/lava/menu/MenuViewModelTest.kt

No tests directly exercise `TrackerSettingsClick` (verified by grep). No changes needed.

### Add "Make active provider" affordance to `:feature:provider_config`

Inside `ProviderConfigScreen`, after `Header` section and before `SyncSection`, add an `ActiveTrackerSection` that:

- Shows `Active provider: rutracker.org` line indicating the SDK's current active tracker
- Shows a `Make active` button when `state.providerId != state.activeTrackerId`
- Button → `ProviderConfigAction.MakeActive` → ViewModel calls `sdk.switchTracker(providerId)` → re-reads `state.activeTrackerId`

This is the only Trackers-screen capability that needed a home; ProviderConfig is the natural home since the user is already looking at the provider they want to make active.

**Marker for Phase D:** the `ActiveTrackerSection` is removed when Phase D's multi-provider parallel search ships (it makes `activeTrackerId` semantically meaningless). Recorded as Phase D pre-work.

## Challenge Tests inventory

| Challenge | Current scope (post Phase 1 audit, commit `85af95b3`) | Phase C impact |
|---|---|---|
| C04 SwitchTrackerAndResearch | "Menu tab → 'Trackers' entry reachable" (intentionally shallow; nav-compose 2.9.0 lifecycle race) | Rewrite: assert Menu tab reachable + click a provider row → ProviderConfig renders + `Make active` button visible/hidden correctly. Shallow OK — the rewrite removes the deleted-screen assertion. |
| C05 ViewTopic | "Search tab reachable" (shallow; nav-compose race) | No change — never touched TrackerSettings |
| C06 DownloadTorrent | "Forum tab reachable" (shallow) | No change |
| C07 CrossTrackerFallbackAccept | "Topics tab reachable" (shallow; fault-injection-seam owed) | No change |
| C08 CrossTrackerFallbackDismiss | "All four bottom-tab labels present" (shallow) | No change |
| C14 TrackerSettingsOpen | Opens Trackers screen + asserts top-bar title rendered | **Delete entirely** — the screen is gone. |

C04 rewrite preserves the spirit (Menu reachable + provider interaction works) without requiring the deleted screen.

## §6.Q regression protection

The deleted `:feature:tracker_settings/src/test/.../TrackerSelectorListLazyColumnRegressionTest.kt` is a per-feature structural test against the nested-scroll antipattern. The §6.Q protection itself is mechanical via the constitution checker. We delete the file with the rest of the module. The pattern guard is preserved structurally:

- `:feature:provider_config`'s `ProviderConfigScreen` uses `Column(verticalScroll)` with no nested LazyColumn at root level (verified at Phase B commit time).
- Should a future Phase C-style screen-level test be needed, the credentials_manager already has its own LazyColumn-in-Box-fillMaxSize pattern as a reference.

No follow-up §6.Q test is owed by this phase.

## Anti-bluff posture

Per §6.J / §6.L / §6.Q the phase MUST:

1. **Falsifiability rehearsal for C04 rewrite.** Mutate the production code path C04 hits (e.g., remove the Menu provider row's `onClick` handler so taps go nowhere) and confirm the rewritten C04 fails with a clear assertion message. Record in commit body. The mutation MUST target the production path the rewrite claims to verify (the provider-row click handler that now routes to ProviderConfig), not an unrelated branch.
2. **Bluff-Audit stamps** for every modified `*Test.kt` file in this phase.
3. **No `@Ignore` on C14.** Deletion, not silencing. A skipped test is the bluff pattern §6.J calls out.
4. **§6.S CONTINUATION update** in the same commit as the deletions.
5. **Build-verify** before commit: `./gradlew :feature:menu:compileDebugKotlin :feature:provider_config:compileDebugKotlin spotlessKotlinCheck testDebugUnitTest`.

## Out of scope for Phase C

- **`ActiveTrackerSection` Compose UI Challenge.** Phase D removes this section. A Challenge Test gated on its existence would be a §6.J bluff against the upcoming removal — write it only if Phase D's start is more than one cycle out.
- **Migration prompt.** Users on an older app version who had the Trackers screen bookmarked: no in-app migration prompt is needed — the Menu provider rows are the discoverable replacement, and the CHANGELOG entry documents the move.
- **`feature:tracker_settings` git history preservation.** The deletion removes the files but the history stays in `git log`; no archive subtree needed.

## Operator's invariants reasserted

- §6.Q: ProviderConfig already complies; no new nested-scroll surfaces introduced.
- §6.R: no new hardcoded literals; the deletions only remove existing config-free code.
- §6.S: CONTINUATION updated in the implementation commit.
- §6.W: pushed to both mirrors in lockstep.
- §6.P: any artifact-shipping commit (Phase C may bump version) bumps versionCode strictly + carries CHANGELOG entry.

## Implementation plan

See `docs/superpowers/plans/2026-05-13-sp4-phase-c-implementation.md` — 7 tasks, ~25 steps, single-session executable.

## Forensic anchor

The Trackers screen shipped in SP-3a Phase 4 (commit cluster around Task 4.19) when the multi-tracker SDK first introduced runtime tracker switching. It served its purpose for the v1.2.x cycle. With Phase B's per-provider config screen and Phase D's upcoming multi-provider parallel search, the screen's role is fully absorbed elsewhere. Phase C is the structural cleanup that closes the v1.2.x "active tracker" mental model in favor of the SP-4 "multiple providers, each independently configured, all queried in parallel" model.
