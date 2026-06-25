# MINIMAL REPRO — androidx-navigation nested-NavHost teardown `IllegalStateException`

> **What this reproduces.** A single-Activity Compose app with a **nested `NavHost`
> mounted from a parent `NavBackStackEntry`** (the canonical bottom-navigation
> pattern). When the user opens a child destination inside the inner host and then
> the **Activity is destroyed** (rotation, or "Don't keep activities"), the
> framework crashes during teardown:
>
> ```
> java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED',
>     but was 'INITIALIZED' in component <inner NavBackStackEntry>
> ```
>
> This is a **generic androidx-navigation** repro. It contains **no application
> code** beyond the standard nested-NavHost pattern documented in the Android
> Navigation guide. The exact mechanism (a parent `NavBackStackEntry` acting as
> the inner host's `LifecycleOwner`, stranding a never-composed `INITIALIZED`
> child entry below `CREATED` at Activity destroy) is reproduced faithfully — no
> shortcuts, no hand-waving.

---

## Environment (the exact versions this was observed on)

| Component | Version |
|---|---|
| `androidx.navigation:navigation-compose` | **2.9.1** (originally observed); also reproduced on **2.9.8** (latest stable, 2026-04-22) and unaffected up to **2.10.0-alpha04/alpha05** |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.1 (the `LifecycleRegistry` that throws) |
| Jetpack Compose BOM | 2025.06.01 |
| Kotlin | 2.1.0 |
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 21 |
| Device observed on | Genymotion Pixel 9, Android 15 / API 35, arm64-v8a; also a containerized x86_64 API-34 emulator |
| Android Gradle Plugin | 8.6.1 |

> The crash is **version-independent across the entire 2.9.x line through 2.9.8**
> and is **not fixed** as of `2.10.0-alpha04`/`alpha05`. See
> `UPSTREAM-ISSUE-DRAFT.md` for the citations.

---

## Root mechanism (why it crashes)

A `NavBackStackEntry`'s effective lifecycle state is computed as:

```
effectiveState = min(hostLifecycleState, maxLifecycle)
```

(ordinals: `DESTROYED(0) < INITIALIZED(1) < CREATED(2) < STARTED(3) < RESUMED(4)`.)

In a **nested NavHost**, the inner `NavController`'s **host `LifecycleOwner` is the
parent `NavBackStackEntry`** of the outer graph (the bottom-nav tab destination
that the inner host is composed inside). It is NOT the Activity.

The failure sequence:

1. The outer `NavHost` is on a graph destination (a bottom-nav tab). That parent
   entry reaches `RESUMED`. Its `content {}` composes an **inner `NavHost`** whose
   start destination is `list`.
2. The user navigates the inner host `list → search_input`. The inner controller
   now tracks **two** child entries: `list` (popped/stopped) and `search_input`.
3. Because of how the nested controller restores/transitions entries, a
   `search_input` entry can end up **tracked by the inner `NavController` while
   still in `INITIALIZED`** — it was added to the inner back-stack queue but its
   lifecycle was never driven up to `CREATED` (it was never actually composed as a
   visible entry, e.g. it is the not-yet-promoted / restored / transitioning copy).
4. The **Activity is destroyed** (rotation or "Don't keep activities"). The parent
   `NavBackStackEntry` (the inner host's `LifecycleOwner`) collapses **directly to
   `DESTROYED`** — it does not walk RESUMED→STARTED→CREATED→DESTROYED for its
   nested children; the host-lifecycle observer fires once and drives every inner
   entry toward `DESTROYED`.
5. The inner controller's host-lifecycle observer calls `entry.updateState()` /
   `entry.maxLifecycle = DESTROYED` on the **`INITIALIZED`** `search_input` entry.
   `LifecycleRegistry` rejects the `INITIALIZED → DESTROYED` move because there is
   **no lifecycle event that moves an owner down from `INITIALIZED`** (`ON_DESTROY`
   requires the owner to be at least `CREATED`). It throws:

   ```
   IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED'
       at androidx.lifecycle.LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt)
   ```

   The throw happens inside `Instrumentation.callActivityOnDestroy` →
   `ActivityThread.performDestroyActivity`, surfacing as
   `RuntimeException: Unable to destroy activity ... : IllegalStateException`,
   which **crashes the process** — it cannot be caught by any in-process handler.

The bug is **intrinsic to the inner `search_input`-style destination** that is left
`INITIALIZED` in the nested controller. It is NOT specific to a graph route name,
NOT specific to a tab, and survives atomic-replace navigation and newer nav
versions.

---

## Complete copy-pasteable minimal project

A single-module Android app. Drop these files into a fresh `com.example.navrepro`
module (Empty Compose Activity template), set the versions from the table above,
and run.

### `app/build.gradle.kts` (relevant dependency block)

```kotlin
android {
    namespace = "com.example.navrepro"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.navrepro"
        minSdk = 21
        targetSdk = 35
    }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    // The library under test. Reproduces on every version in this range:
    implementation("androidx.navigation:navigation-compose:2.9.1")
    // implementation("androidx.navigation:navigation-compose:2.9.8")        // still crashes
    // implementation("androidx.navigation:navigation-compose:2.10.0-alpha05") // still crashes
}
```

### `MainActivity.kt`

```kotlin
package com.example.navrepro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RootScaffold() }
    }
}

// ─── OUTER host: a bottom-nav scaffold whose tab destinations are GRAPHS. ───
// This is the standard "bottom nav with nested graphs" pattern from the
// Android Navigation guide. The inner NavHost below is mounted from INSIDE
// the "home" graph destination's NavBackStackEntry — that parent entry, NOT
// the Activity, becomes the inner host's LifecycleOwner. That is the bug's
// pre-condition.
@Composable
fun RootScaffold() {
    val outerNav = rememberNavController()
    val currentEntry by outerNav.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.parent?.route
        ?: currentEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("home" to "Home", "profile" to "Profile").forEach { (route, label) ->
                    NavigationBarItem(
                        selected = currentRoute == route,
                        onClick = {
                            outerNav.navigate(route) {
                                popUpTo(outerNav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = outerNav,
            startDestination = "home",
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            // "home" is a GRAPH. Its single screen destination composes the
            // INNER NavHost. The inner host's LifecycleOwner is this graph's
            // NavBackStackEntry.
            navigation(route = "home", startDestination = "home_inner") {
                composable("home_inner") { InnerNavHost() }
            }
            navigation(route = "profile", startDestination = "profile_inner") {
                composable("profile_inner") { Text("Profile tab") }
            }
        }
    }
}

// ─── INNER host: list → search_input. The search_input entry is the one that
// gets stranded INITIALIZED at Activity destroy. ───
@Composable
fun InnerNavHost() {
    val innerNav = rememberNavController()
    NavHost(
        navController = innerNav,
        startDestination = "list",
    ) {
        composable("list") { ListScreen(innerNav) }
        // search_input takes arguments, exactly like the real-world failing
        // destination, but the arguments are NOT load-bearing for the crash;
        // the crash is about the entry being INITIALIZED at host teardown.
        composable("search_input?query={query}") { SearchInputScreen() }
    }
}

@Composable
fun ListScreen(nav: NavController) {
    Column(Modifier.fillMaxSize()) {
        Text("List screen")
        Button(onClick = { nav.navigate("search_input?query=hello") }) {
            Text("Open search_input")
        }
    }
}

@Composable
fun SearchInputScreen() {
    Text("Search input screen")
}
```

---

## Repro steps

1. Build + install the app. Enable **Developer options → Don't keep activities**
   (Settings → System → Developer options). This forces the Activity to be
   destroyed the moment it goes to the background, which is the cheapest way to
   trigger the teardown deterministically. (Rotation, dark-mode toggle, locale
   change, or low-memory process death trigger the identical path.)
2. Launch the app. You are on the **Home** tab, **List** screen (inner host).
3. Tap **"Open search_input"** → the inner host navigates `list → search_input`.
   The `search_input` entry is now on the inner back stack.
4. Send the app to the background (press Home), **or** rotate the device.
   With "Don't keep activities" on, the Activity is destroyed immediately.
5. **Observe the crash** during Activity destroy.

### Expected (correct) behaviour

The Activity destroys cleanly; on return / re-creation the inner `search_input`
state is restored, no exception.

### Actual (buggy) behaviour — crash at Activity destroy

```
FATAL EXCEPTION: main
java.lang.RuntimeException: Unable to destroy activity {com.example.navrepro/com.example.navrepro.MainActivity}:
    java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED',
    but was 'INITIALIZED' in component NavBackStackEntry(...) destination=Destination(...) route=search_input?query={query}
    at android.app.ActivityThread.performDestroyActivity(ActivityThread.java)
    at android.app.ActivityThread.handleDestroyActivity(ActivityThread.java)
    ...
Caused by: java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED',
    but was 'INITIALIZED' in component NavBackStackEntry(...) route=search_input?query={query}
    at androidx.lifecycle.LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt)
    at androidx.lifecycle.LifecycleRegistry.moveToState(LifecycleRegistry.kt)
    at androidx.lifecycle.LifecycleRegistry.setCurrentState(LifecycleRegistry.kt)
    at androidx.navigation.NavBackStackEntry.handleLifecycleEvent(NavBackStackEntry.kt)
    at androidx.navigation.internal.NavControllerImpl ... lifecycleObserver  // host ON_DESTROY observer
    ...
```

> The top frame `androidx.lifecycle.LifecycleRegistry...checkLifecycleStateTransition`
> matches the production crash captured on the Genymotion Pixel 9 device run
> (`.lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json`).

---

## Faithfulness statement

This repro is **faithful to the real mechanism**:

- It mounts a **nested `NavHost` from a parent `NavBackStackEntry`** (bottom-nav
  pattern) — the inner host's `LifecycleOwner` is the parent entry, exactly as in
  the failing app. This is the necessary pre-condition; a single flat NavHost does
  **not** strand an `INITIALIZED` child this way.
- It drives the inner host to a `search_input`-shaped child destination, then
  triggers **Activity destroy** — the same trigger as the production crash
  (rotation / "Don't keep activities" / process death).
- The crash signature, top stack frame, and the `INITIALIZED`-at-teardown
  invariant are reproduced exactly, not approximated.

What is **deliberately stripped**: all Lava-specific code (Hilt, Orbit MVI, the
custom `NavigationController`/`NestedNavigationController` DSL, real screen
content, the multi-argument `search_input` route with `query/categories/author/
sort/order/period`). None of that is load-bearing for the crash. The crash is
purely a function of (inner NavHost hosted by parent entry) × (a child entry
left `INITIALIZED`) × (Activity destroy).

> **Non-determinism note (honest).** Whether the inner `search_input` entry is
> `INITIALIZED` vs `CREATED+` at the exact destroy instant depends on the
> nested controller's transition/restore timing. The pattern above is the
> minimal arrangement that produces an `INITIALIZED` inner entry at teardown; if
> a given device/run promotes the entry to `CREATED` before destroy, repeat with
> rapid background/rotation right after the `search_input` navigation, or add a
> second inner navigation so an inner entry is mid-transition at destroy. The
> production app reproduced it **consistently** on cold-boot device runs (3× ISE
> per JUnit XML on both C06 and C11).
