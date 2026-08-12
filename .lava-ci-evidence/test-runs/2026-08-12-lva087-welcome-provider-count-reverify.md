# JVM Unit Test Attestation — LVA-087 Welcome Provider Count Re-Verification

| Field            | Value |
|------------------|-------|
| Date             | 2026-08-12 |
| HEAD SHA         | 741ac11e31091e7f9d60fcf4ce0aea30763a8302 |
| Branch           | worktree-agent-a78948c5d42a0a951 (parent: master) |
| Task             | LVA-087 — Welcome screen claims "4 providers available" but picker lists ~12 |
| Runner           | JVM (`:feature:onboarding:testDebugUnitTest` via Gradle) |
| Gradle command   | `./gradlew :feature:onboarding:testDebugUnitTest --tests "lava.onboarding.OnboardingViewModelVideoFixesTest" --no-daemon --console=plain` |

## Root cause (confirmed, no change from the 2026-06-25 finding)

`WelcomeStep` (`feature/onboarding/src/main/kotlin/lava/onboarding/steps/WelcomeStep.kt:66-70`)
renders `"$providerCount providers available"` when `providerCount != null`, else the
count-free `"Multiple content providers available"`. The count is supplied by
`OnboardingViewModel.loadProviders()`
(`feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingViewModel.kt:472`):

```kotlin
welcomeProviderCount = if (apiSelectionEnabled) null else items.size,
```

`items` is the SAME list (`sdk.listAvailableTrackers()` filtered by
`verified && apiSupported && !synthetic-clone`) that becomes `state.providers` — the exact
list the picker step renders. There is no hardcoded literal anywhere in this path; the
original "4" bug (fixed in commit `a300b3b0`, 2026-06-25) was `OnboardingScreen.kt` passing
the BUNDLED init-time `state.providers.size` to Welcome even though, in the production
`apiSelectionEnabled=true` flow, the picker list is later repopulated (~12 entries) from the
chosen API's catalogue — the count and the list are computed at different times from
different populations. The fix makes Welcome either (a) show `items.size` where that value
IS the picker's list (legacy flow — count == list by construction, dynamic, not hardcoded),
or (b) omit the number entirely when the picker's real population is not yet known
(API-selection flow, the production default) — never a stale/contradictory number.

This is an independent re-verification of the already-landed fix; no production code
change was required or made (see falsifiability rehearsal below for the only production-code
edit in this session, which was reverted).

## Baseline run (fix intact) — BUILD SUCCESSFUL

```
> Task :feature:onboarding:testDebugUnitTest
BUILD SUCCESSFUL in 1m 57s
414 actionable tasks: 414 executed
```

JUnit XML (`feature/onboarding/build/test-results/testDebugUnitTest/TEST-lava.onboarding.OnboardingViewModelVideoFixesTest.xml`):

```xml
<testsuite name="lava.onboarding.OnboardingViewModelVideoFixesTest" tests="7" skipped="0" failures="0" errors="0" ...>
  <testcase name="welcome count is null in api-selection flow so it cannot contradict the picker" ... />
  <testcase name="welcome count equals provider list size in legacy flow" ... />
  ... (5 more, all pass)
</testsuite>
```

**Verdict: 7/7 PASS, 0 failures, 0 errors.** Both LVA-087-covering tests pass against the
real `DefaultTrackerRegistry` + real `LavaTrackerSdk` + real `OnboardingViewModel` (no
mocking of internal business logic — Second Law compliant).

## §6.T.1 / Sixth Law clause 2 — Falsifiability rehearsal (performed fresh in this session)

**Mutation applied** to `feature/onboarding/src/main/kotlin/lava/onboarding/OnboardingViewModel.kt:472`
— reintroduced the EXACT historical stale literal the operator reported ("4 providers
available"):

```diff
- welcomeProviderCount = if (apiSelectionEnabled) null else items.size,
+ welcomeProviderCount = 4, // MUTATION (LVA-087 falsifiability rehearsal)
```

**Command:** `./gradlew :feature:onboarding:testDebugUnitTest --tests "lava.onboarding.OnboardingViewModelVideoFixesTest" --no-daemon --console=plain`

**Result:** `7 tests completed, 2 failed` — `BUILD FAILED`.

**Observed failure #1** (`welcome count is null in api-selection flow so it cannot contradict the picker`, `OnboardingViewModelVideoFixesTest.kt:194`):

```
java.lang.AssertionError: ApiSelection flow MUST NOT pin a premature Welcome count; was 4 expected null, but was:<4>
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.failNotNull(Assert.java:756)
	at org.junit.Assert.assertNull(Assert.java:738)
	at lava.onboarding.OnboardingViewModelVideoFixesTest$welcome count is null in api-selection flow so it cannot contradict the picker$1$1.invokeSuspend(OnboardingViewModelVideoFixesTest.kt:205)
```

**Observed failure #2** (`welcome count equals provider list size in legacy flow`, `OnboardingViewModelVideoFixesTest.kt:219`):

```
java.lang.AssertionError: legacy-flow Welcome count MUST equal the provider list the user sees next expected:<2> but was:<4>
```

Both failures localize exactly to the mutated `welcomeProviderCount` assignment and both
reproduce the reported defect shape (a fixed/stale "4" surviving into contexts where the
real list is 2 or unknown-until-later). The other 5 tests in the class (unrelated code
paths — select-all/deselect-all, discovery friendly-name, cloud-preset subtitle) were
unaffected, confirming the discrimination is localized to the mutated branch, not a
class-wide teardown artifact.

**Revert:** `git checkout` (worktree copy) restored the original conditional. Re-ran the
identical Gradle command:

```
> Task :feature:onboarding:testDebugUnitTest
BUILD SUCCESSFUL in 1m 28s
```

JUnit XML confirms `tests="7" failures="0" errors="0"` again. `git status --short` /
`git diff` on `OnboardingViewModel.kt` report no diff — the working tree is back to
the committed state.

## Verdict

- Fix: CONFIRMED CORRECT — dynamic, sourced from the same provider list the picker renders,
  never hardcoded. No production code change was needed.
- JVM-level regression test: CONFIRMED GENUINE (not a bluff) — fails when the exact
  historical defect is reintroduced, passes when it is not.
- **Outstanding per §6.AK:** this attestation covers the JVM/ViewModel layer only.
  `Challenge63WelcomeCountMatchesPickerTest`
  (`app/src/androidTest/kotlin/lava/app/challenges/Challenge63WelcomeCountMatchesPickerTest.kt`)
  is authored (device Compose UI Challenge covering the same defect on the real rendered
  screen) but, per its own header comment, has not yet been EXECUTED on a device/emulator —
  no Android emulator/device was available in this session (§6.AG/§6.AH: emulators MUST run
  in a Containers-submodule-managed container/VM, never host-direct or a live ADB device; none
  was provisioned here). LVA-087 therefore remains **JVM-verified / device-execution-pending**
  and should stay "In progress" in the workable-items tracker until C63 runs GREEN on the
  §6.Z device gate.

## Anti-Bluff Attestation

- Results read directly from JUnit XML under `feature/onboarding/build/test-results/testDebugUnitTest/`.
- The mutation targeted the exact production code path
  (`OnboardingViewModel.loadProviders`'s `welcomeProviderCount` assignment) the tests claim
  to cover — not an unrelated branch.
- No test was skipped, suppressed, or ignored.
- No code was left mutated; the working tree matches HEAD.
