# UPSTREAM ISSUE DRAFT — ready to file on issuetracker.google.com

> **Where to file:** <https://issuetracker.google.com/issues/new?component=409828>
> (Component: **Android Public Tracking > App Development > Jetpack (androidx) > Navigation**).
> Or use the "Create issue" link from the Navigation release-notes page
> <https://developer.android.com/jetpack/androidx/releases/navigation>.
> Filing requires a signed-in Google account — the operator files this; the text
> below is the ready-to-paste body.
>
> All external sources cited inline, each with its URL and **access date
> 2026-06-25**.

---

## Title

```
NavHost: IllegalStateException "State must be at least 'CREATED' to be moved to 'DESTROYED'" on a nested NavHost's INITIALIZED child entry at Activity destroy
```

---

## Environment

| Field | Value |
|---|---|
| Library | `androidx.navigation:navigation-compose` |
| Version reproduced on | **2.9.1**, **2.9.8** (latest stable, 2026-04-22), and **2.10.0-alpha04/alpha05** (2026-05-19) — all crash |
| `androidx.lifecycle` | 2.9.1 (`LifecycleRegistry` throws) |
| Compose BOM | 2025.06.01 |
| Kotlin | 2.1.0 |
| Android Gradle Plugin | 8.6.1 |
| compileSdk / targetSdk | 35 |
| minSdk | 21 |
| Device(s) | Pixel 9 emulator, Android 15 / API 35, arm64-v8a; containerized x86_64 API-34 emulator |
| Reproducibility | Consistent on cold-boot device runs (3 ISE per teardown) |

---

## Summary

In a single-Activity Compose app using the **bottom-navigation-with-nested-graphs**
pattern, an **inner `NavHost` is composed from inside a parent `NavBackStackEntry`**
of the outer graph. That parent entry — not the Activity — is the inner
`NavController`'s host `LifecycleOwner`.

When the user navigates the inner host to a child destination (in our app, a
`search_input` route) and then the **Activity is destroyed** (rotation, "Don't keep
activities", or process death), the framework crashes during teardown:

```
java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED',
    but was 'INITIALIZED' in component NavBackStackEntry(... route=search_input ...)
    at androidx.lifecycle.LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt)
```

The exception is rethrown as `RuntimeException: Unable to destroy activity` inside
`ActivityThread.performDestroyActivity` and **crashes the process** — it is not
catchable by any in-process handler.

### Why it happens

A `NavBackStackEntry`'s effective state is `min(hostLifecycleState, maxLifecycle)`
(`DESTROYED < INITIALIZED < CREATED < STARTED < RESUMED`). In a nested host, a
child `search_input` entry can be **tracked by the inner `NavController` while still
in `INITIALIZED`** (added to the back-stack queue / transitioning / restored, but
never driven up to `CREATED`). At Activity destroy, the parent `NavBackStackEntry`
collapses **directly to `DESTROYED`**, and the inner controller's host-lifecycle
observer attempts to move the `INITIALIZED` child straight to `DESTROYED`.
`LifecycleRegistry` rejects this because **there is no lifecycle event that moves an
owner down from `INITIALIZED`** (`ON_DESTROY` requires at least `CREATED`).

This is the same `INITIALIZED`-has-no-downward-event invariant tracked in the
related issue **[b/244910446 — "no event down from INITIALIZED"](https://issuetracker.google.com/issues/244910446)**
(accessed 2026-06-25), surfaced here specifically via the **nested-host + Activity-destroy**
path, which leaves an inner entry stranded `INITIALIZED`.

---

## Minimal reproduction

A complete, copy-pasteable single-Activity project is attached as
**`MINIMAL-REPRO.md`** (no app-specific dependencies — generic
androidx-navigation only). Outline:

- Outer `NavHost` = bottom-nav scaffold; tab destinations are **graphs**
  (`navigation(route = "home") { composable("home_inner") { InnerNavHost() } }`).
- `InnerNavHost` = inner `NavHost` (`list → search_input?query={query}`), composed
  from inside the `home` graph's `NavBackStackEntry`.

**Steps:** enable "Don't keep activities" → launch → tap "Open search_input"
(inner `list → search_input`) → background/rotate the app → **crash at destroy**.

---

## Expected vs actual

- **Expected:** Activity destroys cleanly; inner `search_input` state is saved and
  restored on re-creation.
- **Actual:** `IllegalStateException: State must be at least 'CREATED' to be moved
  to 'DESTROYED'` on the inner `search_input` `NavBackStackEntry`; process crash.

---

## Workarounds already tried and FALSIFIED (please do not re-suggest)

We exhausted every app-level / public-API avenue. Each was implemented and then
**device-verified to still crash** (or proven structurally impossible):

1. **Bump nav version 2.9.1 → 2.9.8** (latest stable, 7 patch releases). Identical
   crash. No 2.9.x version fixes it.
2. **Bump nav to 2.10.0-alpha04/alpha05.** Identical crash. Not fixed in the 2.10
   alpha line either.
3. **A `TestRule` / try-catch around teardown.** Impossible to catch: the ISE is
   rethrown as `RuntimeException("Unable to destroy activity")` inside
   `Instrumentation.callActivityOnDestroy` → `ActivityThread.performDestroyActivity`
   and kills the process; it never propagates synchronously through any in-process
   handler.
4. **Move the failing destination to the OUTER `NavHost`** (eliminate the nested
   host for that route). The crash **moved** to the outer `search_input` entry but
   **still fired** — confirming the bug is intrinsic to a `search_input`-style entry
   left `INITIALIZED`, not strictly to nesting.
5. **Atomic-replace navigation** — `popUpTo(current){inclusive=true}` +
   `navigate()` as a single transaction instead of `popBackStack(); navigate()`.
   Identical crash; the entry is still tracked `INITIALIZED` at host-destroy.
6. **A host `LifecycleEventObserver` that, on `ON_STOP` (before the controller's
   `ON_DESTROY` walk), force-advances every `INITIALIZED` entry in
   `navController.currentBackStack.value` to `CREATED`.** Still crashes: the phantom
   `INITIALIZED` `search_input` entry is **not in the public `currentBackStack`
   `StateFlow`** at `ON_STOP` — it lives in the controller's internal
   back-queue / transitions-in-progress / saved-state-restore set, unreachable
   without `@RestrictTo` reflection (which we will not ship).
7. **Re-point the inner host's `LocalLifecycleOwner` to the Activity** (Activity-scoped
   inner host instead of parent-entry-scoped), keeping `LocalViewModelStoreOwner` +
   `LocalSavedStateRegistryOwner` on the parent entry. Device-verified — **still
   crashes** identically on the inner `search_input` entry at Activity destroy.

The conclusion from (1)–(7): there is **no clean app-level public-API fix**; the
defect is in androidx-navigation's handling of `INITIALIZED` nested entries at host
teardown.

---

## Related / candidate-duplicate issues

- **[b/244910446 — "no event down from INITIALIZED"](https://issuetracker.google.com/issues/244910446)**
  (accessed 2026-06-25) — the umbrella `INITIALIZED`-has-no-downward-lifecycle-event
  family this crash belongs to.
- **[b/178029606 — "no event down from INITIALIZED"](https://issuetracker.google.com/issues/178029606)**
  (accessed 2026-06-25) — sibling report of the same invariant.
- **[google/accompanist#1487](https://github.com/google/accompanist/issues/1487)**
  — "You cannot access the NavBackStackEntry's ViewModels after the
  NavBackStackEntry is destroyed" (accessed 2026-06-25): the same nested-entry
  lifecycle-teardown class, ViewModel-access variant.
- **[android/compose-samples#664](https://github.com/android/compose-samples/issues/664)**
  — "Jetsnack crashes when fast switching between two bottom nav destinations"
  (accessed 2026-06-25): the bottom-nav + `popUpTo` + `launchSingleTop` trigger
  noted in the public discussion.

We believe this report adds value by isolating the **nested-host + Activity-destroy**
trigger and providing a generic minimal repro that strands an inner entry
`INITIALIZED` deterministically.

---

## The ask

1. **Confirm** whether `NavControllerImpl`'s host-lifecycle observer should
   no-op (or first drive up to `CREATED`) when asked to move an `INITIALIZED`
   nested entry to `DESTROYED`, rather than calling `LifecycleRegistry` with an
   illegal `INITIALIZED → DESTROYED` transition.
2. **Fix** so that destroying the Activity (or the parent `NavBackStackEntry`) does
   not crash when an inner `NavHost` has an `INITIALIZED` child entry — destroy it
   cleanly, the way `CREATED+` entries are destroyed.
3. If this is a **known dup of b/244910446**, please link it and indicate the
   **target fix version** — the public release notes
   (<https://developer.android.com/jetpack/androidx/releases/navigation>, accessed
   2026-06-25) do not list a fix through **2.9.8** or **2.10.0-alpha05**.

---

## Attachments to upload when filing

- `MINIMAL-REPRO.md` (this directory) — the complete copy-pasteable project +
  repro steps + expected/actual.
- The JUnit XML from the device gate run showing 3× `IllegalStateException` per
  teardown (Lava-internal evidence at `.lava-ci-evidence/1074-gate/`; sanitize /
  optional).

---

### Sources (all accessed 2026-06-25)

- Navigation release notes (stable 2.9.8 / 2026-04-22; latest 2.10.0-alpha05 / 2026-05-19): <https://developer.android.com/jetpack/androidx/releases/navigation>
- AndroidX versions index: <https://developer.android.com/jetpack/androidx/versions>
- `navigation-compose` on Maven Central (version list): <https://mvnrepository.com/artifact/androidx.navigation/navigation-compose>
- b/244910446 — "no event down from INITIALIZED": <https://issuetracker.google.com/issues/244910446>
- b/178029606 — "no event down from INITIALIZED": <https://issuetracker.google.com/issues/178029606>
- accompanist#1487 — NavBackStackEntry ViewModels after destroy: <https://github.com/google/accompanist/issues/1487>
- compose-samples#664 — bottom-nav fast-switch crash: <https://github.com/android/compose-samples/issues/664>
- NavBackStackEntry lifecycle explainer (effectiveState = min(hostLifecycleState, maxLifecycle)): <https://medium.com/@anteprocess/android-navbackstackentrys-lifecycle-in-jetpack-compose-navigation-d596fcc29fc4>
