# HelixQA Test Report

**Generated:** 2026-06-09T00:09:11+05:00

## Overview

| Metric | Value |
|--------|-------|
| Total Challenges | 4 |
| Passed | 0 |
| Failed | 4 |
| Pass Rate | 0% |
| Total Crashes | 4 |
| Total ANRs | 0 |
| Total Duration | 1m48.609797167s |
| Platforms Tested | 1 |

## Platform: ANDROID

- **Duration:** 1m48.609797167s
- **Crashes:** 4
- **ANRs:** 0
- **Challenges:** 4

| Challenge | Status | Duration |
|-----------|--------|----------|
| Continue anonymously without hanging on the loading indicator | FAILED | 21.233491291s |
| Search Internet Archive for a realistic query and browse results | FAILED | 24.335179042s |
| Open an item and obtain a working HTTP download link | FAILED | 29.025915083s |
| Onboard and select the Internet Archive provider | FAILED | 31.022124125s |

### Recorded evidence

Real captured runtime output + assertion ledger per challenge (§107.x anti-bluff: the report itself is the auditable proof the command ran and emitted the asserted output).

#### Continue anonymously without hanging on the loading indicator — FAILED

- **Error:** FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: step[0] desc="step 1: noop" verdict="needs-review"
android-vision: step[0] rationale="The screen shows LeakCanary's main \"0 Distinct Leaks\" view with the Leaks/Heap Dumps/About navigation bar fully loaded, which is a stable expected app result screen."
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
android-vision: step[0] rationale="The app has loaded to a stable, fully-rendered LeakCanary screen showing \"0 Distinct Leaks\" with the bottom navigation (Leaks / Heap Dumps / About) visible, which satisfies reaching the app's expected result screen."
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
android-vision: step[0] rationale="The screen shows LeakCanary's \"0 Distinct Leaks\" Leaks view with the Leaks/Heap Dumps/About navigation bar — a stable, fully-rendered result screen matching the expected leaks-list state."
android-vision: verdict reason="FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)"
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | true | PASS |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Onboard and select the Internet Archive provider — FAILED

- **Error:** FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: step[0] desc="step 1: noop" verdict="needs-review"
android-vision: step[0] rationale="The onboarding Welcome screen rendered fully — colored Lava logo (not a white placeholder), \"Welcome to Lava\", \"4 providers available\", and an enabled \"Get Started\" button — which is the expected post-launch onboarding result."
android-vision: verdict reason="FAIL: zero-screen-delta across all steps (actions produced no observable change — goal match is unearned)"
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | true | PASS |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

### Step Validation

| Step | Status | Duration | Error |
|------|--------|----------|-------|
| LAVA-ARCHIVEORG-002 | FAILED | 536.887292ms | crash detected |
| LAVA-ARCHIVEORG-003 | FAILED | 598.544458ms | crash detected |
| LAVA-ARCHIVEORG-004 | FAILED | 639.381875ms | crash detected |
| LAVA-ARCHIVEORG-001 | FAILED | 813.74ms | crash detected |

---

*Generated by HelixQA*
