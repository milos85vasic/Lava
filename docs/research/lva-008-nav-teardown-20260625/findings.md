# LVA-008 — Deep Multi-Angle Research (§11.4.150)

**Subject:** `java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED'`
(`androidx.lifecycle.LifecycleRegistryKt.checkLifecycleStateTransition`, `LifecycleRegistry.kt:92`) on the
nested-NavHost `search/search_input` `NavBackStackEntry` during `MainActivity` destroy.

**Research date / access date for all URLs below:** 2026-06-25.
**Researcher posture:** anti-bluff (§6.J / §11.4.6). Findings are stated as facts only where a cited source
supports them; everything else is marked `UNCONFIRMED:`.

**Context recap (from the incident JSON + BUGFIXES.md LVA-008):** the bottom-nav graph is mounted as a
NESTED `NavHost` from a parent `NavBackStackEntry` (`addNestedNavigation` -> `NestedMobileNavigation` ->
`NavigationHost`, `core/navigation/src/main/kotlin/lava/navigation/ui/`). The inner `NavController`'s host
`LifecycleOwner` defaults to that parent entry. A popped `search_input` entry persists `INITIALIZED`
(never composed to `CREATED`) in the controller's internal tracking; at Activity destroy the host-lifecycle
observer walks the inner back stack moving entries to `DESTROYED`, and `LifecycleRegistry` rejects
`INITIALIZED -> DESTROYED`. **6 app-level candidates device-falsified** (1) nav 2.9.1->2.9.8, (2) LenientTeardownRule,
(3) move-search-to-outer-host, (4) atomic popUpTo replace, (5) NavTeardownGuard pruning the public
`currentBackStack`, (6) Activity-scoped inner-host `LocalLifecycleOwner`.

---

## Angle 1 — Known upstream issue?

**Finding: there is a closely-related upstream bug CLASS, but NO upstream issue (open or fixed) for Lava's
EXACT scenario** (a nested-NavHost child stranded `INITIALIZED` crashing with this ISE at Activity destroy).

- **`b/244910446` "no event down from INITIALIZED"** — an `IllegalStateException` in the Lifecycle
  `LifecycleRegistry` when an entry is asked to leave the `INITIALIZED` state, reported against
  NavControllers in nested nav-graph scenarios. The issue page itself requires Google sign-in (not
  fetchable anonymously); its existence + title + subject are confirmed via the issuetracker URL and search
  indexing. This is the SAME failure family (LifecycleRegistry rejecting a transition out of `INITIALIZED`)
  but the public record does not confirm a fixed-in-version for the Activity-destroy / nested-NavHost-teardown
  variant.
  Source: https://issuetracker.google.com/issues/244910446 (accessed 2026-06-25; sign-in wall, title only).

- **Navigation 2.8.5 + 2.9.0-alpha03 — `ConcurrentModificationException` fix** (change `Ia9494...`):
  *"Fixed a `ConcurrentModificationException` that could occur when a `LifecycleObserver` attached to a
  `NavBackStackEntry` triggers a change to the back stack when the host `LifecycleOwner` such as the
  containing Activity or Fragment changes its lifecycle state."* This is the EXACT mechanism Lava hits
  (host `LifecycleOwner` changing state at teardown drives an observer that mutates the inner back stack) —
  but the symptom fixed there is a CME, NOT the `INITIALIZED -> DESTROYED` ISE. So the area was hardened in
  2.8.5/2.9.0-alpha03, but Lava's specific ISE survived that fix (consistent with the device-confirmed
  falsification of nav 2.9.1->2.9.8).
  Source: https://developer.android.com/jetpack/androidx/releases/navigation (accessed 2026-06-25).

- **Navigation 2.9.0-alpha04 (`b/375343407`)** and **2.10.0-alpha04 (`b/500945998`)** — both harden
  `NavHost` against `NullPointerException` race conditions with *predictive back*, not the
  `INITIALIZED -> DESTROYED` ISE. Not Lava's bug.
  Source: https://developer.android.com/jetpack/androidx/releases/navigation (accessed 2026-06-25).

- **Navigation 2.9.0-alpha01** changed behavior so that *"attempting to use a NavController that has been
  previously DESTROYED will now cause an IllegalStateException"* — adjacent, not the same site.
  Source: https://developer.android.com/jetpack/androidx/releases/navigation (accessed 2026-06-25).

**Angle-1 verdict:** No fixed-in-version exists for Lava's exact ISE through nav 2.9.8 / 2.10.0-alpha04.
The bug family (`INITIALIZED`-transition + host-lifecycle-observer-mutating-backstack-at-teardown) is
known to androidx and partly mitigated (CME fix), confirming the mechanism is an upstream robustness gap —
`LifecycleRegistry` aborts the process instead of tolerating `INITIALIZED -> DESTROYED` during host teardown.

---

## Angle 2 — Public-API workaround the 6 candidates did NOT try

**Finding: ALL 6 falsified candidates preserved the "nested `NavHost` mounted from a parent
`NavBackStackEntry`" architecture. The CURRENT officially-recommended bottom-nav pattern does NOT mount a
nested `NavHost` from a parent entry at all — it uses a SINGLE `NavHost` whose bottom-nav tabs are
top-level destinations, switched with `saveState = true` / `restoreState = true` + `launchSingleTop`
(multiple-back-stack API).** That architecture has no "parent `NavBackStackEntry` is the inner host's
`LifecycleOwner`" relationship — so the precise mechanism that strands an `INITIALIZED` child at host
destroy structurally cannot arise.

- Official guidance + multiple current write-ups describe the single-`NavHost` multiple-back-stack recipe
  (`navController.navigate(tab) { popUpTo(graph.findStartDestination().id){ saveState = true };
  launchSingleTop = true; restoreState = true }`) as the way to give each bottom tab its own back stack
  WITHOUT a child `NavHost`.
  Sources:
  https://developer.android.com/jetpack/androidx/releases/navigation (accessed 2026-06-25);
  https://iamnaran.github.io/posts/multiple-backstack-compose/ (accessed 2026-06-25);
  https://medium.com/@ahmed.ally2/scoping-hilt-viewmodels-to-the-navigation-back-stack-in-jetpack-compose-1d961e94654a
  (accessed 2026-06-25).

- Note vs. **candidate 3** ("move search to the outer host"): candidate 3 moved ONE destination but KEPT
  the nested bottom-nav `NavHost`, so the crash merely relocated to the outer `search_input` entry — the
  parent-entry-as-inner-host-`LifecycleOwner` relationship still existed. The Angle-2 candidate is
  architecturally different: it REMOVES the nested host entirely (no `addNestedNavigation`), so there is no
  inner controller and no parent entry acting as its host lifecycle owner.

- **Smaller sibling lead (distinct from candidate 4):** a kotlinlang `#compose` Slack thread reports a
  sibling `IllegalStateException` triggered by *rapid* navigation with `popUpTo()` + `launchSingleTop`, and
  that removing EITHER `popUpTo` OR `launchSingleTop` resolves it. Lava's `search_input` pop pattern
  (`popBackStack(); openSearchResult()` with single-top semantics) is in this family. Candidate 4 added an
  ATOMIC `popUpTo` replace; this lead is the OPPOSITE direction — drop the single-top/`popUpTo` combination
  on the `search_input -> search_result` transition so a duplicate `INITIALIZED` `search_input` entry is
  never minted.
  Source: https://slack-chats.kotlinlang.org/t/508906/i-m-sometimes-getting-an-illegalstateexception-when-rapidly-
  (accessed 2026-06-25).

**Angle-2 verdict:** A concrete, documented, UNTRIED app-level public-API candidate exists — collapse the
nested bottom-nav `NavHost` into the outer `NavHost` (single-graph multiple-back-stack). This is a
public-API architecture change, not a private/reflection hack like the falsified candidate 5.

---

## Angle 3 — Has anyone hit this exact crash?

**Finding: no public report (StackOverflow / GitHub / Medium) matches Lava's EXACT signature** (this ISE
string + nested `NavHost` from a parent entry + bottom nav + Activity destroy). Adjacent reports exist:

- `google/accompanist#879` — "Crashes with nested Nav Graph" — nested-graph crash family, accompanist
  material nav, not the identical ISE site.
  Source: https://github.com/google/accompanist/issues/879 (accessed 2026-06-25).
- `google/accompanist#1487` — "You cannot access the NavBackStackEntry's ViewModels after the
  NavBackStackEntry is destroyed" — a related race where material-nav uses an already-destroyed entry;
  different exception text.
  Source: https://github.com/google/accompanist/issues/1487 (accessed 2026-06-25).
- `raamcosta/compose-destinations#306` — "Navigating outside of the nav graph and coming back breaks all
  but the root navController" — nested-controller lifecycle breakage; different symptom.
  Source: https://github.com/raamcosta/compose-destinations/issues/306 (accessed 2026-06-25).
- kotlinlang Slack — rapid `popUpTo` + `launchSingleTop` ISE (see Angle 2).

**Angle-3 verdict:** UNCONFIRMED that any third party has the identical crash; the closest public matches
all share the "nested controller + lifecycle race" shape but none is the same line/transition. This
supports filing a fresh, specific androidx minimal repro (none appears to exist for this exact variant).

---

## Angle 4 — Is it genuinely structural-upstream?

**Verdict: PARTIALLY REFUTE the incident's "no clean app-level public-API fix exists" conclusion.**

- The incident is CORRECT that the mechanism is an upstream `LifecycleRegistry`/androidx-navigation
  robustness gap (Angle 1): the framework aborts the process on `INITIALIZED -> DESTROYED` at host teardown
  instead of tolerating it, and no nav version through 2.9.8 / 2.10.0-alpha04 fixes the exact site.
- The incident's conclusion is correct ONLY WITHIN the nested-`NavHost`-from-a-parent-entry architecture —
  which is exactly what all 6 falsified candidates kept. It does NOT account for the architectural-REMOVAL
  option (Angle 2): the officially-recommended single-`NavHost` multiple-back-stack pattern removes the
  structural precondition (a parent `NavBackStackEntry` serving as the inner controller's host
  `LifecycleOwner`). That is a public-API, app-level change and it is UNTRIED.
- Therefore: **structural-upstream CONFIRMED as the root mechanism**, AND **a NEW untried app-level
  candidate IDENTIFIED** (architecture change, not a patch). The two are compatible: even if the new
  candidate clears Lava's gate, the upstream ISE should still be reported, because any consumer using the
  common nested-`NavHost`-bottom-nav pattern is exposed.

---

## RECOMMENDED NEXT ACTION (two-pronged)

### Primary — TRY Candidate 7: collapse the nested bottom-nav `NavHost` into a single outer `NavHost`

Remove `addNestedNavigation` / `NestedMobileNavigation`'s child `NavHost`; promote each bottom-nav tab's
graph to a top-level destination of the SINGLE outer `MobileNavigation` `NavHost`; switch tabs with the
multiple-back-stack API instead of an inner controller. No parent entry then owns an inner controller's
lifecycle, so a popped `search_input` cannot be stranded `INITIALIZED` under a parent that collapses
straight to `DESTROYED`.

Sketch (tab switch on the single outer controller):

    // BottomNavigation onClick -> single outer NavController, no nested NavHost:
    fun NavController.navigateTopLevel(tabGraphRoute: String) {
        navigate(tabGraphRoute) {
            popUpTo(graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // search_input -> search_result stays WITHIN the same single graph; the existing
    // popBackStack(); openSearchResult() works, but now there is no inner host whose
    // ON_DESTROY walk strands the entry.

Falsifiability rehearsal (device-gated, no JVM equivalent — process death at real Activity destroy):
re-introduce the nested `NavHost` (restore `addNestedNavigation`); on-device C06 + C11 must crash again with
the identical ISE; collapse to the single host and the crash must not recur. Verify on the Linux/KVM
gate-host (C00 + C06 + C11), per §6.Z, before any distribute.

`UNCONFIRMED:` that Candidate 7 clears the gate — it removes the documented structural precondition, but
like every prior candidate it is a hypothesis until C06 + C11 pass on the device gate. It is a LARGE
refactor (the whole bottom-nav graph wiring) and must preserve nested-graph ViewModel scoping + tab state
restoration; budget for regression of tab back-stack behavior.

**Optional cheaper pre-check (Candidate 8, try first, ~1 file):** on the `search_input -> search_result`
transition, drop the `launchSingleTop`/`popUpTo`-single-top combination (Angle 2 Slack lead) so a duplicate
`INITIALIZED` `search_input` entry is never minted. Distinct from the falsified candidate 4 (which ADDED an
atomic `popUpTo` replace); this REMOVES the single-top minting path. Low cost, device-gate the same way; if
it clears C06 + C11 it avoids the Candidate-7 refactor. `UNCONFIRMED:` may not suffice if the stranded entry
also comes from nested `saveState`/`restoreState` rather than the pop+navigate.

### Parallel — FILE an androidx minimal repro

Independent of whether Candidate 7/8 clears Lava's gate, file an issuetracker bug (component
*Jetpack > Navigation*) with a minimal repro: an outer `NavHost` -> a destination hosting a NESTED `NavHost`
(bottom-nav) -> a child graph with a destination that is `popBackStack()`-ed then a sibling `navigate()`-ed
(single-top) so it is left `INITIALIZED`, then destroy the Activity ("Don't keep activities" ON or a
config-change rotate). Expected: process crash `IllegalStateException: State must be at least 'CREATED' to be
moved to 'DESTROYED'` at `LifecycleRegistry.checkLifecycleStateTransition`. Reference `b/244910446` and the
2.8.5/2.9.0-alpha03 CME fix (`Ia9494...`) as the adjacent same-area work. Repro proves nav <= 2.9.8 /
2.10.0-alpha04. This is the §11.4.150-honest channel for the structural-upstream half of the verdict.

---

## Sources (all accessed 2026-06-25)

- https://issuetracker.google.com/issues/244910446 — "no event down from INITIALIZED" (sign-in wall; title/subject only)
- https://developer.android.com/jetpack/androidx/releases/navigation — Navigation release notes (2.8.5 / 2.9.0-alpha03 CME fix; 2.9.0-alpha04 + 2.10.0-alpha04 predictive-back NPE; 2.9.0-alpha01 DESTROYED-controller ISE)
- https://iamnaran.github.io/posts/multiple-backstack-compose/ — single-NavHost multiple-back-stack bottom-nav recipe
- https://medium.com/@ahmed.ally2/scoping-hilt-viewmodels-to-the-navigation-back-stack-in-jetpack-compose-1d961e94654a — back-stack-scoped ViewModels with multiple back stacks
- https://saurabhjadhavblogs.com/jetpack-compose-bottom-navigation-nested-navigation-solved — single-NavHost-with-visibility-control variant
- https://slack-chats.kotlinlang.org/t/508906/i-m-sometimes-getting-an-illegalstateexception-when-rapidly- — rapid popUpTo + launchSingleTop ISE; remove either to resolve
- https://github.com/google/accompanist/issues/879 — crashes with nested nav graph
- https://github.com/google/accompanist/issues/1487 — NavBackStackEntry ViewModels after destroy
- https://github.com/raamcosta/compose-destinations/issues/306 — nested controller lifecycle breakage
