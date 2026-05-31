# Compose UI Challenge Coverage Investigation — Operator test-focus #4

Date: 2026-05-31
Host: darwin/arm64 (compile-verify only; no emulator matrix run)
Investigator: subagent (read-only git; main agent commits)

## Headline finding: NO GENUINE COVERAGE GAP — no new Challenges authored

The §6.AE per-feature coverage scanner reports **0 uncovered** feature modules.
All 19 feature modules already have Challenge coverage. Authoring "new"
Challenges here would be a §6.J bluff (duplicate coverage + wrong-surface
assertions). Three drafted-then-deleted tests are documented below as a
transparency record. **The deliverable of this pass is the finding itself.**

---

## 1. §6.AE coverage-scan result (`scripts/check-challenge-coverage.sh`)

Verbatim:

```
==> §6.AE per-feature Challenge coverage scan
    Feature modules: 19
    Covered (by direct import / marker / heuristic): 18
    Exempted (pre-wired-but-unreachable per ledger): 1
    Uncovered: 0
    Exemptions ledger: .lava-ci-evidence/challenge-coverage-exemptions.md
      AE-exempt: account
    ✓ every feature module has at least one Challenge
```

- 18 modules covered by direct Challenge tests.
- 1 module (`account`) covered by a documented exemption: it is pre-wired-but-
  unreachable dead code (no caller anywhere in `app/`/`feature/`/`core/` per
  `.lava-ci-evidence/challenge-coverage-exemptions.md`). Per §6.J, writing a
  Challenge against dead code is theatre — the ledger is the correct posture.

### Existing per-feature Challenges (closed in the 31st §6.L cycle)

The features I initially assumed were gaps already have dedicated Challenges:

| Feature | Existing Challenge file |
|---------|-------------------------|
| rating | `Challenge30RatingDialogReachableTest.kt` |
| visited | `Challenge31VisitedScreenReachableTest.kt` |
| favorites | `Challenge32FavoritesScreenReachableTest.kt` |
| bookmarks | `Challenge33BookmarksScreenReachableTest.kt` |
| category | `Challenge34CategoryNavigationReachableTest.kt` |
| connection | `Challenge35ConnectionItemReachableTest.kt` |
| account | EXEMPT (ledger entry — unreachable) |

The task brief's assumed gap list (bookmarks/favorites/visited/account/rating)
was already closed before this pass. UNKNOWN at brief-time; CONFIRMED now via
the scanner + the exemption ledger.

---

## 2. Drafted-then-deleted bluff tests (transparency record)

I initially drafted three Challenge tests before running the scanner to
completion. They were DELETED because they are bluffs by construction:

| Drafted file (deleted) | Why it was a bluff |
|------------------------|--------------------|
| `Challenge36FavoritesRenderTest.kt` | (a) duplicates C32; (b) asserts on `onNodeWithTag("favorites_screen")` — a test tag that does NOT exist in `FavoritesScreen.kt` (it renders a bare `Box`, no `.testTag`). The assertion would fail on a real device → guaranteed-fail / wrong-surface bluff. (c) wrong import `lava.app.MainActivity` (real package is `digital.vasic.lava.client.MainActivity`) → did not compile. |
| `Challenge37VisitedRenderTest.kt` | duplicates C31; asserts on `visited_screen` tag absent from `VisitedScreen.kt` (bare `when`, no `.testTag`); same wrong MainActivity import → did not compile. |
| `Challenge38AccountRenderTest.kt` | targets `account`, a documented §6.AE exemption (unreachable dead code). Writing a Challenge against it violates the ledger's own §6.J rationale. Asserts on absent `account_screen` tag; wrong import → did not compile. |

Initial compile attempt (recording the failure for the audit trail):

```
> Task :app:compileDebugAndroidTestKotlin FAILED
e: ...Challenge36FavoritesRenderTest.kt:11:17 Unresolved reference 'MainActivity'.
e: ...Challenge37VisitedRenderTest.kt:11:17 Unresolved reference 'MainActivity'.
e: ...Challenge38AccountRenderTest.kt:11:17 Unresolved reference 'MainActivity'.
BUILD FAILED in 24s
```

All three deleted. The §6.J-correct response to "no gap exists" is to author
nothing, not to manufacture coverage.

---

## 3. Compile-verify result (post-removal baseline)

Command (run per §6.T.2, `--max-workers=2`):

```
./gradlew :app:compileDebugAndroidTestKotlin --console=plain --max-workers=2
```

Verbatim tail:

```
> Task :app:compileDebugAndroidTestKotlin UP-TO-DATE

BUILD SUCCESSFUL in 1m 4s
640 actionable tasks: 1 executed, 636 up-to-date
```

The androidTest source set compiles clean. The existing 37 Challenge classes
(C00–C36) are unaffected.

---

## 4. Honest scope (§6.Z / §6.X)

This pass produced NO new Challenge source. The existing Challenges remain
authored + compile-verified; their EXECUTION against a device/emulator is owed
to the §6.X host-direct+HVF gate run (the operator's separate run) per §6.Z —
source compilation is necessary, never sufficient for gate-completeness.

## 5. Recommendation

No git changes to Challenge `.kt` files are needed from this pass. The only
artifact is this evidence file. If §6.AE coverage is genuinely desired beyond
the 0-uncovered baseline, the next real target is lifting the `account`
exemption — but that requires first WIRING a real account screen into
navigation (a feature change, out of scope for a test-authoring pass), at which
point a Challenge MUST land in the same commit per the ledger's UNBLOCK clause.
