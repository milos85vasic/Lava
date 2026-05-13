# SP-4 Phase C — Trackers Screen Removal (implementation plan)

**Plan date:** 2026-05-13
**Design:** `docs/superpowers/specs/2026-05-13-sp4-phase-c-design.md`
**Parent SP:** `docs/superpowers/specs/2026-05-12-sp4-multi-provider-redesign.md`
**Prerequisite:** Phase B complete (commit `85af95b3`).

This phase is single-session executable, 7 tasks, ~25 steps. No emulator needed for the structural deletions; the C04 rewrite + falsifiability rehearsal needs a connected emulator only if executed on the gating matrix per §6.I. Operator-driven rehearsal can happen at any time before the next tag.

## Task 1 — Add `ActiveTrackerSection` to `:feature:provider_config`

Preserves the only unique capability of the deleted Trackers screen as a small affordance inside ProviderConfig.

**Files:**
- Create: `feature/provider_config/src/main/kotlin/lava/provider/config/sections/ActiveTrackerSection.kt`
- Modify: `feature/provider_config/src/main/kotlin/lava/provider/config/ProviderConfigState.kt` — add `activeTrackerId: String? = null`
- Modify: `feature/provider_config/src/main/kotlin/lava/provider/config/ProviderConfigAction.kt` — add `data object MakeActive : ProviderConfigAction`
- Modify: `feature/provider_config/src/main/kotlin/lava/provider/config/ProviderConfigViewModel.kt` — observe `sdk.activeTrackerId()` snapshot at init; handle `MakeActive` by calling `sdk.switchTracker(providerId)` + reducing the new state
- Modify: `feature/provider_config/src/main/kotlin/lava/provider/config/ProviderConfigScreen.kt` — insert `ActiveTrackerSection(state, onAction)` between `Header` and `SyncSection`

**Steps:**
- [ ] **Step 1: Add state field + action.**
- [ ] **Step 2: ViewModel wiring.** Read `sdk.activeTrackerId()` in `onCreate`; on `MakeActive` call `sdk.switchTracker(providerId)` then `reduce { state.copy(activeTrackerId = providerId) }`.
- [ ] **Step 3: Section composable.**

```kotlin
@Composable
internal fun ActiveTrackerSection(
    state: ProviderConfigState,
    onAction: (ProviderConfigAction) -> Unit,
) {
    val isActive = state.activeTrackerId != null && state.activeTrackerId == state.providerId
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppTheme.spaces.large, vertical = AppTheme.spaces.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Body(text = if (isActive) "Active provider" else "Inactive")
            BodySmall(
                text = if (isActive) "This provider is the SDK's currently-active target." else "Tap Make active to route searches here.",
                color = AppTheme.colors.outline,
            )
        }
        if (!isActive) {
            TextButton(text = "Make active", onClick = { onAction(ProviderConfigAction.MakeActive) })
        }
    }
}
```

- [ ] **Step 4: Wire into screen.**
- [ ] **Step 5: Compile.** `./gradlew :feature:provider_config:compileDebugKotlin spotlessKotlinCheck`.

## Task 2 — Delete `Challenge14TrackerSettingsOpenTest.kt`

The Trackers screen this Challenge opens is being deleted. Per §6.J, deletion (not `@Ignore`) is the correct response.

**Files:**
- Delete: `app/src/androidTest/kotlin/lava/app/challenges/Challenge14TrackerSettingsOpenTest.kt`

**Steps:**
- [ ] **Step 1: `git rm` the file.**
- [ ] **Step 2: Verify** no other Challenge references it.

## Task 3 — Remove Trackers menu entry + action/side-effect plumbing

**Files:**
- Modify: `feature/menu/src/main/kotlin/lava/menu/MenuAction.kt` — remove `TrackerSettingsClick`
- Modify: `feature/menu/src/main/kotlin/lava/menu/MenuSideEffect.kt` — remove `OpenTrackerSettings`
- Modify: `feature/menu/src/main/kotlin/lava/menu/MenuViewModel.kt` — remove `onTrackerSettingsClick()` + the action's `when` branch
- Modify: `feature/menu/src/main/kotlin/lava/menu/MenuScreen.kt` — remove `openTrackerSettings` parameter + side-effect handler + the `menuItem(text = { Text("Trackers") }, ...)` LazyList entry

**Steps:**
- [ ] **Step 1: MenuAction.kt** — delete the `TrackerSettingsClick` line + its comment.
- [ ] **Step 2: MenuSideEffect.kt** — delete the `OpenTrackerSettings` line + comment.
- [ ] **Step 3: MenuViewModel.kt** — delete `onTrackerSettingsClick()`, the `when` branch, the unused `import`.
- [ ] **Step 4: MenuScreen.kt** — delete the parameter from both `fun MenuScreen(...)` overloads + the `OpenTrackerSettings` branch in `collectSideEffect { ... }` + the `menuItem(text = { Text("Trackers") }, ...)` block. Remove the unused string resource reference if any.
- [ ] **Step 5: Compile** `:feature:menu:compileDebugKotlin :feature:menu:compileDebugUnitTestKotlin`.

## Task 4 — Remove `:feature:tracker_settings` from build graph

**Files:**
- Modify: `settings.gradle.kts` — remove `include(":feature:tracker_settings")`
- Modify: `app/build.gradle.kts` — remove `implementation(project(":feature:tracker_settings"))`
- Modify: `app/src/main/kotlin/.../navigation/MobileNavigation.kt` — remove the two `import lava.feature.tracker.settings.{addTrackerSettings,openTrackerSettings}` lines, the `addTrackerSettings(...)` block, the `openTrackerSettings = { openTrackerSettings() }` pass-through, and the `openTrackerSettings: () -> Unit` parameter from `addNestedNavigation` + `addMenu`

**Steps:**
- [ ] **Step 1: settings.gradle.kts.**
- [ ] **Step 2: app/build.gradle.kts.**
- [ ] **Step 3: MobileNavigation.kt.** Both the `addTrackerSettings` registration AND the menu-pass-through chain.
- [ ] **Step 4: Compile :app** — `./gradlew :app:compileDebugKotlin` (operator env required for .env / keystore; skip on agent sessions).

## Task 5 — Delete `feature/tracker_settings/` directory

**Files:**
- Delete: `feature/tracker_settings/` (recursive — build.gradle.kts, manifest, all sources + tests + resources)

**Steps:**
- [ ] **Step 1: `git rm -r feature/tracker_settings/`**.
- [ ] **Step 2: Verify** `grep -rln "lava.feature.tracker.settings\|:feature:tracker_settings\|TrackerSettingsScreen" .` returns nothing in tracked sources. Hits in `docs/`, `CHANGELOG.md`, `docs/CONTINUATION.md`, `.lava-ci-evidence/` are historical record and stay.

## Task 6 — Rewrite C04 against the new Menu → ProviderConfig path

Per §6.J + §6.N, the rewrite ships with a falsifiability rehearsal.

**Files:**
- Modify: `app/src/androidTest/kotlin/lava/app/challenges/Challenge04SwitchTrackerAndResearchTest.kt`

**Steps:**
- [ ] **Step 1: Rename file.** New name: `Challenge04ProviderRowOpensConfigTest.kt`. Class name matches.
- [ ] **Step 2: New scope.** Drive: launch → Menu tab → tap first provider row → assert `ProviderConfig` screen renders with the provider's displayName visible in the AppBar title + at least one of the new sections ("Sync this provider", "Mirrors", "Make active" affordance when not-active) is visible.
- [ ] **Step 3: Falsifiability rehearsal.** Mutate `MenuViewModel.onOpenProviderConfig(providerId)` to NOT emit `MenuSideEffect.OpenProviderConfig(providerId)` (e.g., emit `Unit` or comment out the postSideEffect line). Run the rewritten C04. Confirm it fails with a clear `expected node containing 'Sync this provider' was not found` style assertion. Record the mutation + observed failure verbatim in commit body. Revert. Re-run → PASS.
- [ ] **Step 4: KDoc update** with the new falsifiability rehearsal protocol per the project's Challenge Test convention.

## Task 7 — Update CONTINUATION.md + commit + push

**Files:**
- Modify: `docs/CONTINUATION.md` — §0 "Last updated" + per-task ledger + state delta

**Steps:**
- [ ] **Step 1: CONTINUATION update.** Update §0 with the Phase C delivery (Trackers screen deleted, `ActiveTrackerSection` added, C04 rewritten, C14 deleted).
- [ ] **Step 2: Local CI gate.** `bash scripts/check-constitution.sh` (must pass) + the spotless+test runs.
- [ ] **Step 3: Commit** with body containing:
  - One Bluff-Audit stamp per modified `*Test.kt` file (just C04 here).
  - Note Phase C closure references the design doc + plan paths.
  - `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>` trailer.
- [ ] **Step 4: Push both mirrors.**

```bash
for r in github gitlab; do git push "$r" master; done
for r in github gitlab; do echo "$r: $(git ls-remote $r master | awk '{print $1}' | head -1)"; done
echo "HEAD: $(git rev-parse HEAD)"
```

Verify SHA convergence.

## Risk register

| Risk | Mitigation |
|---|---|
| Tests in the deleted `feature/tracker_settings/src/test/` are referenced by external tooling | Verified by grep: only `feature/tracker_settings` itself + Challenge14 reference these. C14 deleted in Task 2; the rest is internal to the deleted module. |
| The single test class `TrackerSelectorListLazyColumnRegressionTest` (§6.Q regression coverage) goes away with the module | Acceptable: the §6.Q rule is mechanically enforced via `scripts/check-constitution.sh` checks already in place; the per-feature test was an additional structural test for one screen, not the constitutional gate. |
| ActiveTrackerSection becomes dead code at Phase D | Documented as Phase D pre-work: delete the section when `LavaTrackerSdk.activeTrackerId()` is removed. Recorded in the design doc. |
| Persisted-state references to `tracker_settings` route | None: the route was always in-process navigation only; no deep links or Room rows reference it. |
| `:app:compileDebugKotlin` fails in agent sessions without `.env` + `keystores/debug.keystore` | Same operator-env limitation as Phase B Task 16+17+18. The :feature:tracker_settings + :feature:menu + :feature:provider_config + spotless gates all pass without `.env`. Operator runs the full :app compile in their env before tag. |

## Deliverable summary

A single commit (or 2-3 if grouping by domain) that:

1. Removes the entire Trackers screen feature module.
2. Adds the `ActiveTrackerSection` affordance to ProviderConfig.
3. Removes the Menu's Trackers entry + plumbing.
4. Removes the Trackers route from the navigation graph.
5. Rewrites C04 against the new path; deletes C14.
6. Updates CONTINUATION.md.
7. Lands on both mirrors with verified SHA convergence.

Estimated session time: 30-60 minutes of execution + the falsifiability-rehearsal Gradle round for C04 (~3-5 minutes against the gating matrix when the operator runs it on the emulator). The structural deletions are the bulk; the affordance addition is the only feature work.
