# LVA-008 — upstream androidx-navigation bug report (filing-ready)

> **Status:** filing-ready bug report for the AndroidX issue tracker.
> **Where to file:** <https://issuetracker.google.com/issues/new?component=409828>
> (Component: *Android Public Tracking → App Development → Jetpack (androidx) → Navigation*),
> or the "Create issue" link on
> <https://developer.android.com/jetpack/androidx/releases/navigation>.
> Filing needs a signed-in Google account — the operator files; the body below is ready to paste.

## Provenance / non-duplication note (read first)

A prior, equivalent upstream package was authored on the off-master commit
**`71bee48c`** (`docs/issues/upstream/lva-008-androidx-navigation/UPSTREAM-ISSUE-DRAFT.md`
+ `MINIMAL-REPRO.md`), held back from `master` for tag-time docs-sync. That package
is **not present on the master working tree**, and it embeds its repro code inside
markdown (there is no standalone compilable `.kt`). This directory **consolidates**
the verified substance of that package and **adds** what it lacked:

- `MinimalRepro.kt` — a single, standalone, compile-correct Kotlin source file (the
  attachable code sample, not embedded in prose).
- `analysis.md` — the in-depth root-cause analysis with this session's falsified-fix ledger.

Nothing here was copied from speculation. Every version, route, stack frame, and
falsified hypothesis is taken from in-repo device evidence (cited below). This is a
consolidation/extension of an existing artifact, not a fresh duplicate claim.

---

## Title (paste into the tracker)

```
NavHost: IllegalStateException "State must be at least 'CREATED' to be moved to 'DESTROYED'"
on a nested NavHost's INITIALIZED child entry at Activity destroy
```

## Environment

| Field | Value |
|---|---|
| Library | `androidx.navigation:navigation-compose` |
| **Reproduces on** | **2.9.1** AND **2.9.8** (latest stable, 2026-04-22); also unfixed on **2.10.0-alpha04/alpha05** (2026-05-19) |
| `androidx.lifecycle` | 2.9.1 — `LifecycleRegistry` is what throws |
| Compose BOM | 2025.06.01 |
| Kotlin | 2.1.0 · AGP 8.6.1 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 21 |
| Device | **Genymotion Pixel 9, Android 15 / API 35, arm64-v8a** (serial `127.0.0.1:6555`); also reproduced on a containerized x86_64 API-34 emulator |
| Reproducibility | Consistent on cold-boot device runs — **3× ISE per teardown** in the JUnit XML, on both affected flows (C06 download, C11 search) |

## Summary

Single-Activity Compose app using the **bottom-navigation-with-nested-graphs**
pattern. An **inner `NavHost` is composed from inside a parent `NavBackStackEntry`**
of the outer graph — so that parent entry, **not the Activity**, is the inner
`NavController`'s host `LifecycleOwner`.

The user navigates the inner host to a child route (in our app, `search/search_input?query=…`)
and then the **Activity is destroyed** (rotation / "Don't keep activities" / process
death / config change). During teardown the framework crashes:

```
java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED',
    but was 'INITIALIZED' in component NavBackStackEntry(... route=search_input ...)
    at androidx.lifecycle.LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt:92)
```

The exception is rethrown as `RuntimeException: Unable to destroy activity` inside
`ActivityThread.performDestroyActivity` and **crashes the process** — it is not
catchable by any in-process handler.

### Exact production stack (verbatim from device evidence)

```
Process crashed — Unable to destroy activity MainActivity
Caused by: java.lang.IllegalStateException:
    State must be at least 'CREATED' to be moved to 'DESTROYED', but was 'INITIALIZED'
    in component NavBackStackEntry(route=search/search_input?query={query}&categories={categories}
        &author_name={author_name}&author_id={author_id}&sort={sort}&order={order}&period={period})
  at androidx.lifecycle.LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt:92)
  at androidx.lifecycle.LifecycleRegistry.moveToState(LifecycleRegistry.kt)
  at androidx.navigation.NavBackStackEntry... updateState
  at androidx.navigation.internal.NavControllerImpl ... lifecycleObserver  // host ON_DESTROY observer
```

## Why it happens

A `NavBackStackEntry`'s effective state is `min(hostLifecycleState, maxLifecycle)`
(`DESTROYED(0) < INITIALIZED(1) < CREATED(2) < STARTED(3) < RESUMED(4)`). In a nested
host, a child `search_input` entry can be **tracked by the inner `NavController`
while still `INITIALIZED`** (added to the back-stack queue / transitioning / restored,
but never driven up to `CREATED`). At Activity destroy, the parent
`NavBackStackEntry` (the inner host's `LifecycleOwner`) collapses **directly to
`DESTROYED`**, and the inner controller's host-lifecycle observer attempts to move
the `INITIALIZED` child straight to `DESTROYED`. `LifecycleRegistry` rejects this —
**there is no lifecycle event that moves an owner down from `INITIALIZED`**
(`ON_DESTROY` requires the owner to be at least `CREATED`).

This belongs to the umbrella `INITIALIZED`-has-no-downward-event family
(**b/244910446**, **b/178029606**), surfaced here specifically via the
**nested-host + Activity-destroy** path that strands an inner entry `INITIALIZED`.

## Why it is test-environment-surfaced but a genuine lifecycle-ordering bug

We hit it in instrumentation because `ActivityScenario`/`connectedAndroidTest` does
a **rapid create → drive UI → destroy** of `MainActivity`, which reliably destroys
the Activity while the inner `search_input` entry is still `INITIALIZED`. That is a
test *trigger*, not a test *artifact*:

- The stranded `search_input` entry persists `INITIALIZED` for the **whole app
  session** after the composed instance was popped — it is not created by the test.
- The destroy path the test exercises is the **same** path a real user crosses on
  rotation, dark-mode toggle, locale change, "Don't keep activities", or low-memory
  process death after using search. Crashlytics **confirms it live in the field**
  on shipped builds (FATAL; 4 events / 4 users on 1.3.11-1075).
- It reproduces on a clean cold-boot device, version-independently through 2.9.8 and
  into the 2.10 alpha line.

So: the test merely makes a real lifecycle-ordering defect deterministic.

## Minimal reproduction

See **`MinimalRepro.kt`** in this directory — a complete, standalone single-Activity
file with no app-specific dependencies (generic androidx-navigation only). Outline:

- Outer `NavHost` = bottom-nav scaffold; tab destinations are **graphs**
  (`navigation(route="home"){ composable("home_inner"){ InnerNavHost() } }`).
- `InnerNavHost` = inner `NavHost` (`list → search_input?query={query}`), composed
  from inside the `home` graph's `NavBackStackEntry`.

**Steps:** enable Developer options → "Don't keep activities" → launch → tap
"Open search_input" (inner `list → search_input`) → press Home / rotate → **crash at
destroy**. (Rotation, dark-mode, locale change, and process death trigger the same
path.) See `MinimalRepro.kt`'s header KDoc for the full repro/expected/actual and the
honest non-determinism note.

## Expected vs actual

- **Expected:** Activity destroys cleanly; inner `search_input` state is saved and
  restored on re-creation.
- **Actual:** `IllegalStateException: State must be at least 'CREATED' to be moved to
  'DESTROYED'` on the inner `search_input` entry; process crash.

## Workarounds tried and device-FALSIFIED (please do not re-suggest)

Every app-level / public-API avenue was implemented and then device-verified to
**still crash** (or proven structurally impossible). Full evidence paths in
`analysis.md`.

1. Bump nav `2.9.1 → 2.9.8` (latest stable). Identical crash.
2. Bump nav to `2.10.0-alpha04/alpha05`. Identical crash.
3. `TestRule` / try-catch around teardown. **Impossible** — rethrown as
   `RuntimeException("Unable to destroy activity")` in
   `Instrumentation.callActivityOnDestroy` → `ActivityThread.performDestroyActivity`;
   kills the process, never propagates synchronously.
4. Move the destination to the **OUTER** `NavHost`. Crash **moved** to the outer
   `search_input` entry but **still fired** — disproving "nesting is strictly
   required"; the bug is intrinsic to an entry left `INITIALIZED`.
5. Atomic-replace nav (`popUpTo(current){inclusive=true}` + `navigate()` as one
   transaction). Identical crash.
6. Host `LifecycleEventObserver` that on `ON_STOP` force-advances every
   `INITIALIZED` entry in `navController.currentBackStack.value` to `CREATED`. Still
   crashes — the phantom entry is **not in the public `currentBackStack` StateFlow**;
   it lives in controller internals (`@RestrictTo`, reflection-only, not shipped).
7. Re-point the inner host's `LocalLifecycleOwner` to the Activity (keep
   `LocalViewModelStoreOwner` + `LocalSavedStateRegistryOwner` on the parent entry).
   Device-verified — **still crashes** identically.
8. `launchSingleTop` dedupe on the search_input navigation. Device-falsified (C06/C11
   FAIL, `.lava-ci-evidence/lva008-cand8-gate/`).

Conclusion from (1)–(8): **no clean app-level public-API fix exists**; the defect is
in androidx-navigation's handling of `INITIALIZED` nested entries at host teardown.

## Related / candidate-duplicate issues (all accessed 2026-06-25)

- **b/244910446** — "no event down from INITIALIZED" (umbrella family) — <https://issuetracker.google.com/issues/244910446>
- **b/178029606** — "no event down from INITIALIZED" (sibling) — <https://issuetracker.google.com/issues/178029606>
- **google/accompanist#1487** — NavBackStackEntry ViewModels after destroy — <https://github.com/google/accompanist/issues/1487>
- **android/compose-samples#664** — Jetsnack bottom-nav fast-switch crash — <https://github.com/android/compose-samples/issues/664>

This report adds value by isolating the **nested-host + Activity-destroy** trigger
and providing a generic minimal repro that strands an inner entry `INITIALIZED`
deterministically.

## The ask

1. **Confirm** whether `NavControllerImpl`'s host-lifecycle observer should no-op
   (or first drive up to `CREATED`) when asked to move an `INITIALIZED` nested entry
   to `DESTROYED`, rather than calling `LifecycleRegistry` with an illegal
   `INITIALIZED → DESTROYED` transition.
2. **Fix** so destroying the Activity / parent `NavBackStackEntry` does not crash
   when an inner `NavHost` has an `INITIALIZED` child — destroy it cleanly the way
   `CREATED+` entries are.
3. If this is a **dup of b/244910446**, link it and indicate the **target fix
   version** — release notes do not list a fix through 2.9.8 or 2.10.0-alpha05.

## In-repo evidence paths (Lava-internal; sanitize/optional when filing)

- Incident JSON (verbatim crash signature + falsified-hypothesis ledger):
  `.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json`
- Device-gate JUnit XML showing 3× `IllegalStateException` per teardown:
  `.lava-ci-evidence/1074-gate/` (C06, C11) and `.lava-ci-evidence/lva008-cand7-gate/`, `.lava-ci-evidence/lva008-cand8-gate/`
- Latest baseline runs: `.lava-ci-evidence/genymotion/full-baseline-runnerfix-20260626/`, `.lava-ci-evidence/genymotion/hyp-nav298-c05/`
- 1076 reproduction (C52 search chips + C48 sync toggle both crash identically):
  `.lava-ci-evidence/1076-repro/`
- §6.AK incident (C00-only gate shipped broken flows):
  `.lava-ci-evidence/sixth-law-incidents/2026-06-26-c00-only-gate-shipped-broken-flows.json`
- Bugfix log: `docs/issues/fixed/BUGFIXES.md` (LVA-008 entry)
- Prior off-master upstream package: commit `71bee48c`,
  `docs/issues/upstream/lva-008-androidx-navigation/{UPSTREAM-ISSUE-DRAFT,MINIMAL-REPRO}.md`

## Sources (all accessed 2026-06-25)

- Navigation release notes (stable 2.9.8 / 2026-04-22; latest 2.10.0-alpha05 / 2026-05-19): <https://developer.android.com/jetpack/androidx/releases/navigation>
- AndroidX versions index: <https://developer.android.com/jetpack/androidx/versions>
- `navigation-compose` on Maven Central: <https://mvnrepository.com/artifact/androidx.navigation/navigation-compose>
- b/244910446: <https://issuetracker.google.com/issues/244910446>
- b/178029606: <https://issuetracker.google.com/issues/178029606>
- accompanist#1487: <https://github.com/google/accompanist/issues/1487>
- compose-samples#664: <https://github.com/android/compose-samples/issues/664>
- NavBackStackEntry lifecycle explainer (`effectiveState = min(hostLifecycleState, maxLifecycle)`): <https://medium.com/@anteprocess/android-navbackstackentrys-lifecycle-in-jetpack-compose-navigation-d596fcc29fc4>
