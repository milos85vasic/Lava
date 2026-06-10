# HelixQA Test Report

**Generated:** 2026-06-08T23:24:57+05:00

## Overview

| Metric | Value |
|--------|-------|
| Total Challenges | 4 |
| Passed | 0 |
| Failed | 4 |
| Pass Rate | 0% |
| Total Crashes | 4 |
| Total ANRs | 0 |
| Total Duration | 2m6.333232875s |
| Platforms Tested | 1 |

## Platform: ANDROID

- **Duration:** 2m6.333232875s
- **Crashes:** 4
- **ANRs:** 0
- **Challenges:** 4

| Challenge | Status | Duration |
|-----------|--------|----------|
| Onboard and select the Internet Archive provider | ERROR | 50.078757708s |
| Continue anonymously without hanging on the loading indicator | FAILED | 24.086101083s |
| Search Internet Archive for a realistic query and browse results | FAILED | 20.515715792s |
| Open an item and obtain a working HTTP download link | FAILED | 27.620642709s |

### Recorded evidence

Real captured runtime output + assertion ledger per challenge (§107.x anti-bluff: the report itself is the auditable proof the command ran and emitted the asserted output).

#### Onboard and select the Internet Archive provider — ERROR

- **Error:** android vision run aborted: visionnav: step 2: dispatch "launch digital.vasic.lava.client.dev": exit status 127

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: step[0] desc="step 1: launch digital.vasic.lava.client.dev" verdict="needs-review"
android-vision: step[0] rationale="Current screen is the Android home launcher, not the target app; launching the Lava client app moves toward the test case's expected screen."
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | true | PASS |

#### Continue anonymously without hanging on the loading indicator — FAILED

- **Error:** FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: step[0] desc="step 1: noop" verdict="needs-review"
android-vision: step[0] rationale="The screen shows the LeakCanary \"0 Distinct Leaks\" list view with the Leaks/Heap Dumps/About bottom navigation, a stable rendered destination screen matching the expected result."
android-vision: verdict reason="FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)"
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | true | PASS |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Search Internet Archive for a realistic query and browse results — FAILED

- **Error:** FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: step[0] desc="step 1: noop" verdict="needs-review"
android-vision: step[0] rationale="The current screen shows the LeakCanary \"0 Distinct Leaks\" view with the Leaks/Heap Dumps/About navigation, a stable terminal screen matching the expected leaks-display result."
android-vision: verdict reason="FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)"
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | true | PASS |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Open an item and obtain a working HTTP download link — FAILED

- **Error:** FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: step[0] desc="step 1: noop" verdict="needs-review"
android-vision: step[0] rationale="The LeakCanary \"Leaks\" screen is fully loaded and stable, showing \"0 Distinct Leaks\" with the bottom navigation (Leaks / Heap Dumps / About), which is a coherent expected-result app screen."
android-vision: verdict reason="FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)"
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | true | PASS |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

### Step Validation

| Step | Status | Duration | Error |
|------|--------|----------|-------|
| LAVA-ARCHIVEORG-001 | FAILED | 785.633542ms | crash detected |
| LAVA-ARCHIVEORG-002 | FAILED | 829.417ms | crash detected |
| LAVA-ARCHIVEORG-003 | FAILED | 1.0168945s | crash detected |
| LAVA-ARCHIVEORG-004 | FAILED | 994.323ms | crash detected |

---

*Generated by HelixQA*
