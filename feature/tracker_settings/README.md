# :feature:tracker_settings

The "Settings → Trackers" screen. Lets the user pick the active
tracker, view per-tracker mirror health, add custom mirrors, and
probe a mirror on demand. Added in SP-3a Phase 4 (Tasks 4.9 – 4.19).

## UI surface

- **Tracker selector list** — one row per registered tracker
  (RuTracker, RuTor in 1.2.0). Tapping a row sets it as the active
  tracker; the row carries an "Active" pill when selected.
- **Mirror list section** (per tracker) — bundled defaults +
  user-added customs, with a colored health dot per row
  (HEALTHY = green, DEGRADED = yellow, UNHEALTHY = red, UNKNOWN = grey).
- **Add custom mirror button + dialog** — protocol picker (HTTPS /
  HTTP), URL input, priority field. Validation pre-flight: URL parse,
  duplicate-check against the bundled set, scheme/protocol match.
- **Probe-now icon** — kicks off a one-shot health probe outside the
  15-min schedule, useful for verifying a freshly-added mirror.

Compose components live under
`src/main/kotlin/lava/feature/tracker_settings/components/`:

| Component                  | File                                           |
|----------------------------|------------------------------------------------|
| `TrackerSelectorList`      | `components/TrackerSelectorList.kt`            |
| `MirrorListSection`        | `components/MirrorListSection.kt`              |
| `HealthIndicator`          | `components/HealthIndicator.kt`                |
| `AddCustomMirrorDialog`    | `components/AddCustomMirrorDialog.kt`          |

The top-level composable is
`TrackerSettingsScreen.kt`. Navigation entry lives in
`TrackerSettingsNavigation.kt` (`addTrackerSettings()` / `openTrackerSettings()`),
wired from `:feature:menu` (Settings → Trackers row).

## MVI shape (Orbit)

```kotlin
data class TrackerSettingsState(
    loading: Boolean,
    activeTrackerId: String,
    availableTrackers: List<TrackerDescriptor>,
    mirrorHealthByTracker: Map<String, List<MirrorState>>,
    customMirrors: Map<String, List<MirrorUrl>>,
    showAddMirrorDialog: Boolean,
    addMirrorTargetTracker: String?,
    error: String?,
)

sealed class TrackerSettingsAction {
    data object Load
    data class SwitchActive(trackerId: String)
    data class OpenAddMirrorDialog(trackerId: String)
    data object DismissAddMirrorDialog
    data class AddCustomMirror(trackerId, url, priority, protocol)
    data class RemoveCustomMirror(trackerId, url)
    data class ProbeNow(trackerId: String)
}

sealed class TrackerSettingsSideEffect {
    data class ShowToast(message: String)
}
```

`TrackerSettingsViewModel` (`@HiltViewModel`,
`ContainerHost<TrackerSettingsState, TrackerSettingsSideEffect>`)
talks to:

- `LavaTrackerSdk` — to switch active tracker and observe descriptors.
- `MirrorHealthRepository` — to read the per-mirror state map.
- `UserMirrorRepository` — to read/add/remove custom mirrors.
- A one-shot probe entry point on `MirrorHealthCheckWorker` (via the
  `LavaMirrorManagerHolder`).

No per-tracker module is imported here — the ViewModel sees only
`:core:tracker:client` and `:core:tracker:api`.

## Navigation entry

```kotlin
// :feature:menu uses the navigation extensions exposed by this module.
context(NavigationGraphBuilder)
addTrackerSettings()

// From the menu screen:
onClick = { navController.openTrackerSettings() }
```

## Test discipline

`TrackerSettingsViewModelTest` exercises every action through real
`UserMirrorRepository` + in-memory `MirrorHealthRepository` fakes. No
mocking of `LavaTrackerSdk`, `UserMirrorRepository`, or any class in
`lava.feature.tracker.settings.*`.

Per the Anti-Bluff Pact (Sixth + Seventh Laws):

- VM-CONTRACT vs CHALLENGE: this module's ViewModel test is
  classified VM-CONTRACT (asserts on emitted state + side effects);
  the rendered-UI Challenge for "Settings → Trackers" is **C1
  (App launch + tracker selection)** in
  `app/src/androidTest/kotlin/lava/app/challenges/Challenge01AppLaunchAndTrackerSelectionTest.kt`.
- Bluff-Audit stamp required on every test commit (Seventh Law
  clause 1).
- Adding a new action MUST be accompanied by either an extension to
  C1 or a new Challenge Test (e.g. C9 if Add-Custom-Mirror gains a
  user-visible workflow not covered by C1).
- Real-device attestation for the `tracker_settings` UI ships as part
  of the C1 evidence file
  (`.lava-ci-evidence/Lava-Android-1.2.0-1020/challenges/C1.json`),
  status `PENDING_OPERATOR` until the operator runs the real-device
  check.

> See also: Sixth Law and Seventh Law in root `CLAUDE.md`;
> `feature/CLAUDE.md` scoped clause for SDK-consuming ViewModels (the
> rule that requires a Compose UI Challenge Test for every feature
> that consumes `LavaTrackerSdk`); SDK developer guide §5–§7
> ([`docs/sdk-developer-guide.md`](../../docs/sdk-developer-guide.md))
> for the mechanical compliance gates.
