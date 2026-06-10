# HelixQA Test Report

**Generated:** 2026-06-08T22:37:49+05:00

## Overview

| Metric | Value |
|--------|-------|
| Total Challenges | 8 |
| Passed | 0 |
| Failed | 8 |
| Pass Rate | 0% |
| Total Crashes | 8 |
| Total ANRs | 0 |
| Total Duration | 11.939747792s |
| Platforms Tested | 1 |

## Platform: ANDROID

- **Duration:** 11.939747792s
- **Crashes:** 8
- **ANRs:** 0
- **Challenges:** 8

| Challenge | Status | Duration |
|-----------|--------|----------|
| Continue anonymously without hanging on the loading indicator | ERROR | 988.399083ms |
| Search Internet Archive for a realistic query and browse results | ERROR | 736.925292ms |
| Open an item and obtain a working HTTP download link | ERROR | 686.511417ms |
| Onboard and select the RuTor provider | ERROR | 692.57475ms |
| Authenticate to RuTor with form-login credentials | ERROR | 728.92225ms |
| Search RuTor for a realistic query and browse results | ERROR | 717.936292ms |
| Open a result and obtain a working download option | ERROR | 705.486666ms |
| Onboard and select the Internet Archive provider | ERROR | 699.192458ms |

### Recorded evidence

Real captured runtime output + assertion ledger per challenge (§107.x anti-bluff: the report itself is the auditable proof the command ran and emitted the asserted output).

#### Continue anonymously without hanging on the loading indicator — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

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

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

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

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

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

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

```
android-vision: serial=127.0.0.1:6555
android-vision: launch=shell monkey -p digital.vasic.lava.client.dev 1
android-vision: verdict reason=""
```

| Assertion | Target | Expected | Actual | Result |
|-----------|--------|----------|--------|--------|
| vision-goal-reached | session.GoalReached | true | false | FAIL |
| vision-screen-changed | session.ScreenChanged | true | false | FAIL |

#### Authenticate to RuTor with form-login credentials — ERROR

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

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

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

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

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

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

- **Error:** android vision run aborted: visionnav: step 1: provider decide: visionnav: LLMProvider: backend "bridge-claude" vision call: bridge-cli claude: exit status 1: error: unknown option '--json'

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
| LAVA-ARCHIVEORG-002 | FAILED | 717.252416ms | crash detected |
| LAVA-ARCHIVEORG-003 | FAILED | 604.140916ms | crash detected |
| LAVA-ARCHIVEORG-004 | FAILED | 589.292417ms | crash detected |
| LAVA-RUTOR-001 | FAILED | 629.421333ms | crash detected |
| LAVA-RUTOR-002 | FAILED | 626.429583ms | crash detected |
| LAVA-RUTOR-003 | FAILED | 636.694416ms | crash detected |
| LAVA-RUTOR-004 | FAILED | 788.780833ms | crash detected |
| LAVA-ARCHIVEORG-001 | FAILED | 583.430458ms | crash detected |

---

*Generated by HelixQA*
