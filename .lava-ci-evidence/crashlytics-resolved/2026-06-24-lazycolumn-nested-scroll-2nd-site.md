# §6.O Closure Log — P1 LazyColumn-in-verticalScroll FATAL (2nd site)

**State:** RESOLVED — FIX LANDED (ships in 1.3.11-1073).
(An earlier draft said "OWED" — a stale-`git diff` race artifact written by a parallel doc stream
before the fix stream's changes were visible; corrected here. The fix IS in the working tree and the
§6.Q scanner passes clean.)

## Crashlytics issue
- ID: `c7c8cccad09f…` (partial in the triage doc; full ID via the Console), client RELEASE.
- Type: FATAL — `IllegalStateException: Vertically scrollable component was measured with an infinite
  maximum height constraint`.
- Impact: 2 events, firstSeen 1.2.3 → lastSeen 1.3.10 — a SECOND, independent nested-scroll site that
  survived the 2026-05-05 TrackerSettings fix (site #1: `2026-05-05-tracker-settings-nested-scroll.md`).

## Root cause (confirmed, file:line)
`feature/search_input/src/main/kotlin/lava/search/input/SearchInputScreen.kt:96` — a `LazyList(
contentPadding = …)` (wraps `LazyColumn`) called with **no `modifier` argument** inside a plain
`Column { ProviderChipBar(); LazyList(...) }` in the Scaffold content lambda. The Column has no
`fillMaxSize`/`weight`, so it propagated **unbounded height** to the LazyList → Compose's measure
protocol throws the infinite-max-height `IllegalStateException` (§6.Q antipattern).

## Fix (landed)
`SearchInputScreen.kt:96` — added `modifier = Modifier.weight(1f)` as the LazyList's first argument, so
the Column gives it all remaining height after `ProviderChipBar` takes its intrinsic size — bounding
the lazy layout and eliminating the crash.

## Validation (mechanical gate, reproduce-first)
`tests/compose-layout/test_no_nested_scroll_antipattern.sh` gained **CHECK 2**: flags any `LazyList(`
call site whose first named argument is not `modifier =` (excluding the `fun LazyList(` definition).
- Scanner FAIL before fix: `FAIL §6.Q-2 … SearchInputScreen.kt:96: LazyList( has no 'modifier =' first argument`
- Scanner PASS after fix: `[compose-layout] OK: no nested-scroll antipattern detected in feature/ + core/ + app/src/main/`

Bluff-Audit:
```
Mutation: removed `modifier = Modifier.weight(1f)` from SearchInputScreen.kt:96 LazyList
Observed-Failure: FAIL §6.Q-2 … SearchInputScreen.kt:96: LazyList( has no 'modifier =' first argument
Reverted: yes
```
The scanner now confirms **no other nested-scroll sites remain** across `feature/`, `core/`, `app/src/main/`.

## §6.O.2 Challenge
The structural scanner (CHECK 2) is the load-bearing mechanical gate for this antipattern class; the
release cold-start canary on the Genymotion VM exercises app launch including SearchInput's host graph.
A dedicated SearchInput-render Challenge is a reasonable follow-up; the structural gate already prevents
the class from recurring.

## §6.O.5
Operator close-marks the Crashlytics issue in the Console after on-device verification of 1.3.11-1073.
