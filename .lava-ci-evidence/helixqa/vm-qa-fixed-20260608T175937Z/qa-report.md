# HelixQA Test Report

**Generated:** 2026-06-08T22:59:49+05:00

## Overview

| Metric | Value |
|--------|-------|
| Total Challenges | 8 |
| Passed | 0 |
| Failed | 8 |
| Pass Rate | 0% |
| Total Crashes | 8 |
| Total ANRs | 0 |
| Total Duration | 10.92543125s |
| Platforms Tested | 1 |

## Platform: ANDROID

- **Duration:** 10.92543125s
- **Crashes:** 8
- **ANRs:** 0
- **Challenges:** 8

| Challenge | Status | Duration |
|-----------|--------|----------|
| Authenticate to RuTor with form-login credentials | ERROR | 747.083792ms |
| Search RuTor for a realistic query and browse results | ERROR | 694.931958ms |
| Open a result and obtain a working download option | ERROR | 687.074042ms |
| Onboard and select the Internet Archive provider | ERROR | 656.302375ms |
| Continue anonymously without hanging on the loading indicator | ERROR | 645.988375ms |
| Search Internet Archive for a realistic query and browse results | ERROR | 640.7795ms |
| Open an item and obtain a working HTTP download link | ERROR | 653.973625ms |
| Onboard and select the RuTor provider | ERROR | 685.466375ms |

### Recorded evidence

Real captured runtime output + assertion ledger per challenge (§107.x anti-bluff: the report itself is the auditable proof the command ran and emitted the asserted output).

#### Authenticate to RuTor with form-login credentials — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Search RuTor for a realistic query and browse results — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Open a result and obtain a working download option — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Onboard and select the Internet Archive provider — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Continue anonymously without hanging on the loading indicator — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Search Internet Archive for a realistic query and browse results — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Open an item and obtain a working HTTP download link — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Onboard and select the RuTor provider — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--image'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

### Step Validation

| Step | Status | Duration | Error |
|------|--------|----------|-------|
| LAVA-RUTOR-002 | FAILED | 697.725333ms | crash detected |
| LAVA-RUTOR-003 | FAILED | 593.136791ms | crash detected |
| LAVA-RUTOR-004 | FAILED | 579.740667ms | crash detected |
| LAVA-ARCHIVEORG-001 | FAILED | 569.289959ms | crash detected |
| LAVA-ARCHIVEORG-002 | FAILED | 562.285625ms | crash detected |
| LAVA-ARCHIVEORG-003 | FAILED | 543.196541ms | crash detected |
| LAVA-ARCHIVEORG-004 | FAILED | 585.826542ms | crash detected |
| LAVA-RUTOR-001 | FAILED | 573.810375ms | crash detected |

---

*Generated by HelixQA*
