# LVA-008 — root-cause analysis

This is the in-depth analysis backing the filing-ready report in `README.md` and the
standalone sample in `MinimalRepro.kt`. Every claim is anchored to in-repo device
evidence; nothing here is surmised (§11.4.6). Where a thing is not confirmed it is
marked `UNCONFIRMED:` explicitly.

## 1. What the bug is

`androidx.navigation:navigation-compose` crashes the process at **Activity destroy**
when a **nested `NavHost`** (mounted from a parent `NavBackStackEntry`, the bottom-nav
pattern) has a child entry that is still `INITIALIZED`:

```
java.lang.IllegalStateException: State must be at least 'CREATED' to be moved to 'DESTROYED',
    but was 'INITIALIZED' in component
    NavBackStackEntry(route=search/search_input?query={query}&categories={categories}
        &author_name={author_name}&author_id={author_id}&sort={sort}&order={order}&period={period})
  at androidx.lifecycle.LifecycleRegistry.checkLifecycleStateTransition(LifecycleRegistry.kt:92)
  at androidx.navigation.NavBackStackEntry... updateState
  at androidx.navigation.internal.NavControllerImpl ... lifecycleObserver   // host ON_DESTROY observer
```

Rethrown as `RuntimeException: Unable to destroy activity MainActivity` inside
`ActivityThread.performDestroyActivity`.

## 2. The lifecycle-ordering root cause

`NavBackStackEntry.effectiveState = min(hostLifecycleState, maxLifecycle)`, ordinals
`DESTROYED(0) < INITIALIZED(1) < CREATED(2) < STARTED(3) < RESUMED(4)`.

1. The outer `NavHost` is on a graph destination (a bottom-nav tab). That parent
   entry reaches `RESUMED` and composes the **inner `NavHost`** (start = `list`).
2. The user navigates the inner host `list -> search_input`. The inner controller now
   tracks `list` (stopped) plus `search_input`.
3. Because of how the nested controller restores/transitions entries, a
   `search_input` entry can be **tracked while still `INITIALIZED`** — queued/restored
   but never driven up to `CREATED` (the not-yet-promoted / restored / transitioning
   copy that survives after the user's composed instance was popped).
4. The **Activity is destroyed**. The parent `NavBackStackEntry` (the inner host's
   `LifecycleOwner`) collapses **directly to `DESTROYED`** — it does not walk its
   nested children RESUMED->STARTED->CREATED->DESTROYED.
5. The inner controller's host observer calls `updateState()` /
   `maxLifecycle = DESTROYED` on the `INITIALIZED` `search_input` entry.
   `LifecycleRegistry.checkLifecycleStateTransition` rejects it: **there is no
   lifecycle event that moves an owner down from `INITIALIZED`** — `ON_DESTROY`
   requires at least `CREATED`. It throws.

This is the `INITIALIZED`-has-no-downward-event invariant (umbrella b/244910446,
sibling b/178029606), surfaced via the **nested-host + Activity-destroy** path.

## 3. Why it surfaces in tests but is a real bug (not a test artifact)

Instrumentation (`ActivityScenario` / `connectedAndroidTest`) does a rapid
create -> drive -> **destroy** of `MainActivity`, which reliably destroys the Activity
while the inner `search_input` entry is still `INITIALIZED`. That is the *trigger*,
not the *cause*:

- The stranded entry persists `INITIALIZED` for the **whole session** after the
  composed instance is popped — confirmed by the candidate-2 experiment (section 4 item 4).
- The same destroy path is crossed by real users on rotation / dark-mode / locale
  change / "Don't keep activities" / process death after using search.
- **Crashlytics confirms it live in the field**: FATAL, 4 events / 4 users on
  1.3.11-1075 (per `docs/CONTINUATION.md`).
- It reproduces on clean cold-boot device runs, version-independently.

So the test environment makes a genuine lifecycle-ordering defect deterministic.

## 4. Falsified-fix ledger (8 device-verified dead ends)

All speculative changes were REVERTED per section 6.T.1. Evidence under
`.lava-ci-evidence/genymotion/` and the per-candidate gate dirs.

| # | Hypothesis | Result | Evidence |
|---|---|---|---|
| 1 | Bump nav `2.9.1 -> 2.9.8` | FALSIFIED — identical ISE | `.lava-ci-evidence/genymotion/c11-nav298-r2-20260608T165908Z/` |
| 2 | Bump nav `2.10.0-alpha04/05` | FALSIFIED — identical ISE | release-notes diff (no fix listed) |
| 3 | `LenientTeardownRule` / try-catch (walk cause chain) | **Structurally impossible** — rethrown as `RuntimeException("Unable to destroy activity")` in `Instrumentation.callActivityOnDestroy` -> `ActivityThread.performDestroyActivity`; process death, never propagates synchronously | `.lava-ci-evidence/genymotion/c11-fix-20260608T171332Z/` |
| 4 | Move route to the OUTER NavHost | FALSIFIED — crash **moved** to the outer `search_input` (route `search_input`, no `search/` prefix) but **still fired** -> disproves "nesting strictly required"; bug is intrinsic to an `INITIALIZED` entry | `.lava-ci-evidence/genymotion/lva008-cand2-*` |
| 5 | Atomic-replace nav (`popUpTo(current){inclusive=true}` + `navigate()`) | FALSIFIED — entry still tracked `INITIALIZED` at host-destroy | `.lava-ci-evidence/genymotion/c11-atomicfix-r2-20260608T172702Z/` |
| 6 | `NavTeardownGuard`: on `ON_STOP` force-advance `INITIALIZED` entries in `currentBackStack.value` to `CREATED` | FALSIFIED — the phantom entry is **not in the public `currentBackStack` StateFlow**; it lives in `@RestrictTo` internals (back-queue / transitions-in-progress / saved-state-restore), reflection-only, not shipped | `.lava-ci-evidence/genymotion/lva008-teardownguard-*` |
| 7 | Activity-scoped inner `LocalLifecycleOwner` (keep ViewModelStore/SavedStateRegistry on parent entry) | FALSIFIED on the section 6.Z gate 2026-06-25 — C06 + C11 both reproduce 3x ISE on build 1074 @ `1310a922`; reverted | `.lava-ci-evidence/1074-gate/{GATE-SUMMARY.json,C06,C11}` |
| 8 | `launchSingleTop` dedupe on the search_input navigation | FALSIFIED — C06 + C11 FAIL | `.lava-ci-evidence/lva008-cand8-gate/{c06,c11}/` |

Latest re-confirmation: `.lava-ci-evidence/1076-repro/` — `Challenge52` (search chips)
and `Challenge48` (sync toggle) both crash with the identical ISE, showing the defect
is **systemic across nested routes** (`search/search_input` *and* `provider_config`),
firing at instrumentation activity-destroy.

## 5. Why each class of "fix" fails (the structural reasons)

- **AndroidX version bump fails** because no released `navigation-compose` (through
  2.9.8 / 2.10.0-alpha05) changes the `INITIALIZED -> DESTROYED` rejection in the
  nested host-observer walk. The fix has to land upstream.
- **`onException` / `LenientTeardownRule` / any TestRule fails** because the ISE is
  raised on the **main-thread Looper** inside `ActivityThread.performDestroyActivity`
  and rethrown as `RuntimeException("Unable to destroy activity")`. It is never an
  exception the JUnit statement chain or a `Thread.UncaughtExceptionHandler` can
  intercept synchronously — the instrumentation process simply dies
  ("Instrumentation run failed due to Process crashed"). It is, by construction,
  test-side uncatchable.
- **App-level NavHost guards fail** because the stranded entry is not reachable
  through any public API at the moment teardown runs (item 6 — not in
  `currentBackStack`; in `@RestrictTo` internals). A reflection-based prune would be
  fragile and break across nav releases, so it is not shipped.

## 6. Candidate real fixes

1. **AndroidX upstream fix (correct fix).** `NavControllerImpl`'s host-lifecycle
   observer should no-op (or first drive up to `CREATED`) when asked to move an
   `INITIALIZED` nested entry to `DESTROYED`, instead of calling `LifecycleRegistry`
   with an illegal transition. File the report in `README.md` (+ attach
   `MinimalRepro.kt`); track against b/244910446.
2. **AndroidX Test Orchestrator process-isolation (test-side mitigation only).**
   Running each test in its own process would contain the process-death so one
   teardown crash does not poison the run — but it does **not fix the user-facing
   crash**, and the Orchestrator/services APKs are **NOT cached offline** on the gate
   host, so it cannot be adopted under the Local-Only CI/CD constraint without a
   network fetch the operator must authorize. Mitigation, not a fix.
3. **Single-NavHost refactor (app-side, large blast radius).** Collapse the nested
   `search` graph so `search_input` lives on one flat host, eliminating the
   parent-entry-as-LifecycleOwner pre-condition. This is the only app-side avenue not
   yet fully shipped; the candidate-7 "single-NavHost collapse" partial was
   device-tested (`.lava-ci-evidence/lva008-cand7-gate/`) and C06/C11 still FAILED for
   that partial, so a *correct* full refactor would have to remove **every** nested
   host on the failing paths — high risk, operator-gated.

## 7. Current disposition

- Accepted as an **upstream androidx-navigation defect**; instrumented in production
  via `NavTeardownCrashReporter` (chained uncaught-exception handler in
  `LavaApplication.onCreate`) which tags the known ISE with section 6.AC Crashlytics
  context (`feature=navigation`, `operation=activity-teardown`, `screen=search_input`,
  `lva_id=LVA-008`) before the process dies — it instruments, never swallows.
- The upstream report (this directory) is ready to file; the operator files it
  (Google sign-in required).
- Workable item **LVA-008** remains OPEN until an upstream fix or the full
  single-NavHost refactor lands and C06 + C11 pass on the device gate.
