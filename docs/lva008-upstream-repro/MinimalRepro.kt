/*
 * LVA-008 — MINIMAL REPRODUCTION (standalone, documentation-only)
 * ===============================================================
 *
 * This file is NOT wired into the Lava build. It is an attachable, copy-pasteable
 * code sample for the upstream androidx-navigation bug report (see README.md /
 * analysis.md in this directory). Drop it into a fresh Empty-Compose-Activity
 * module (e.g. `com.example.navrepro`) and run.
 *
 * WHAT IT REPRODUCES
 * ------------------
 * A single-Activity Compose app with a NESTED `NavHost` mounted from a parent
 * `NavBackStackEntry` (the canonical bottom-navigation-with-nested-graphs pattern).
 * When the user opens a child destination inside the inner host and then the
 * Activity is destroyed (rotation, or "Don't keep activities"), the framework
 * crashes during teardown:
 *
 *   java.lang.IllegalStateException: State must be at least 'CREATED' to be moved
 *       to 'DESTROYED', but was 'INITIALIZED' in component <inner NavBackStackEntry>
 *       at androidx.lifecycle.LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt:92)
 *
 * The ISE is rethrown as `RuntimeException: Unable to destroy activity` inside
 * `ActivityThread.performDestroyActivity` and crashes the process — uncatchable by
 * any in-process handler.
 *
 * ENVIRONMENT THE CRASH WAS OBSERVED ON
 * -------------------------------------
 *   androidx.navigation:navigation-compose  2.9.1 AND 2.9.8 (both crash); unfixed on 2.10.0-alpha04/alpha05
 *   androidx.lifecycle                       2.9.1 (the LifecycleRegistry that throws)
 *   Jetpack Compose BOM                      2025.06.01
 *   Kotlin 2.1.0 · AGP 8.6.1 · compileSdk/targetSdk 35 · minSdk 21
 *   Device: Genymotion Pixel 9, Android 15 / API 35, arm64-v8a (also containerized x86_64 API-34)
 *
 * app/build.gradle.kts dependency block (versions the crash was observed on):
 *
 *   dependencies {
 *       val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
 *       implementation(composeBom)
 *       implementation("androidx.activity:activity-compose:1.10.1")
 *       implementation("androidx.compose.material3:material3")
 *       implementation("androidx.compose.ui:ui")
 *       implementation("androidx.navigation:navigation-compose:2.9.1")          // crashes
 *       // implementation("androidx.navigation:navigation-compose:2.9.8")       // still crashes
 *       // implementation("androidx.navigation:navigation-compose:2.10.0-alpha05") // still crashes
 *   }
 *
 * REPRO STEPS
 * -----------
 *   1. Build + install. Enable Developer options -> "Don't keep activities".
 *   2. Launch (Home tab, List screen of the inner host).
 *   3. Tap "Open search_input" (inner host: list -> search_input).
 *   4. Press Home (or rotate). With "Don't keep activities" on, the Activity is
 *      destroyed immediately.
 *   5. Observe the crash at Activity destroy.
 *
 * EXPECTED: Activity destroys cleanly; inner search_input state restored on re-create.
 * ACTUAL:   IllegalStateException ... 'INITIALIZED' -> 'DESTROYED'; process crash.
 *
 * NON-DETERMINISM NOTE (honest): whether the inner `search_input` entry is
 * INITIALIZED vs CREATED+ at the destroy instant depends on the nested controller's
 * transition/restore timing. This is the minimal arrangement that strands an
 * INITIALIZED inner entry; if a run promotes it to CREATED first, repeat with rapid
 * background/rotation immediately after the search_input navigation, or add a second
 * inner navigation so an inner entry is mid-transition at destroy. The production app
 * reproduced it CONSISTENTLY on cold-boot device runs (3x ISE per JUnit XML).
 *
 * FAITHFULNESS: this mounts a nested NavHost from a parent NavBackStackEntry (so the
 * inner host's LifecycleOwner is the parent entry, exactly as in the failing app),
 * drives the inner host to a search_input-shaped child, then triggers Activity
 * destroy. A single flat NavHost does NOT strand an INITIALIZED child this way.
 * Deliberately stripped: Hilt, Orbit MVI, the custom NavigationController DSL, real
 * screen content, and the multi-arg search_input route — none are load-bearing.
 */

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
// Standard "bottom nav with nested graphs" pattern. The inner NavHost below is
// mounted from INSIDE the "home" graph destination's NavBackStackEntry — that
// parent entry, NOT the Activity, becomes the inner host's LifecycleOwner. That is
// the bug's pre-condition.
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
            // "home" is a GRAPH. Its single screen destination composes the INNER
            // NavHost. The inner host's LifecycleOwner is this graph's NavBackStackEntry.
            navigation(route = "home", startDestination = "home_inner") {
                composable("home_inner") { InnerNavHost() }
            }
            navigation(route = "profile", startDestination = "profile_inner") {
                composable("profile_inner") { Text("Profile tab") }
            }
        }
    }
}

// ─── INNER host: list → search_input. The search_input entry is the one that gets
// stranded INITIALIZED at Activity destroy. ───
@Composable
fun InnerNavHost() {
    val innerNav = rememberNavController()
    NavHost(
        navController = innerNav,
        startDestination = "list",
    ) {
        composable("list") { ListScreen(innerNav) }
        // search_input takes an argument exactly like the real failing destination,
        // but the argument is NOT load-bearing for the crash; the crash is about the
        // entry being INITIALIZED at host teardown.
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
