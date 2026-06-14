# Reported-issues video QA — 2026-06-14 (real device runs, no bluff)

All 4 operator-reported issues (2026-06-14) are fixed, covered by a device-runnable
Compose UI / instrumented Challenge that PASSED on the Genymotion Pixel 9 / API 35 VM
(§6.AH VM path, serial 127.0.0.1:6555), and the success video is delivered to the host
user's `$HOME` AND `$HOME/Downloads` (only PASS videos are delivered — `scripts/record-challenge-video.sh`
gates delivery on the verbatim connectedAndroidTest `BUILD SUCCESSFUL`, so a delivered
video is itself proof of a real device PASS).

| # | Issue | Fix commit | Challenge (PASS on VM) | Delivered video |
|---|---|---|---|---|
| 1 | Onboarding "Pick your providers" select-all/deselect-all | `bb357da8` | `Challenge41OnboardingSelectAllProvidersTest` | `~/Downloads/issue1-onboarding-select-all-providers.mp4` |
| 2 | Provider Configure password masking + eye toggle | `bb357da8` | `Challenge42OnboardingPasswordMaskingTest` | `~/Downloads/issue2-onboarding-password-masking-eye.mp4` |
| 3 | Search "Something went wrong" via the on-device API | `fba19372` | `Challenge44ApiSearchAuthTest` | `~/Downloads/issue3-api-search-auth-not-something-went-wrong.mp4` |
| 4 | Settings server list shows chosen online server twice | `1d0294e5` | `Challenge43ServerListNoDuplicateTest` | `~/Downloads/issue4-settings-server-list-no-duplicate.mp4` |

## Honesty notes (§6.J / §11.4.6)

- C41/C42 are rendered-UI Challenges (real production composables) — the video shows the
  on-screen select-all flip and the password bullet→plaintext→bullet toggle.
- C43 (server-list) drives a REAL in-memory Room AppDatabase + the real EndpointsRepositoryImpl
  on-device, asserting the same server added via two paths emits ONCE — the on-device
  equivalent of the committed `EndpointsRepositoryImplFilterTest` unit reproduction. Its
  video is the device-screen capture during that instrumented run (minimal UI — a logic/data
  test), with the connectedAndroidTest PASS as ground truth.
- C44 (search auth) runs an on-device MockWebServer + a REAL ApiBackedTrackerClient, proving
  search returns a real result WITH the per-endpoint Lava-Auth key and throws (HTTP 401)
  WITHOUT it — the on-device equivalent of the committed `ApiBackedTrackerClientTest` 401
  reproduction. The fully-visual onboard→search→results e2e (api-app + live network) is a
  separately-tracked flow; C44 is the deterministic on-device auth-gate proof.
- Two real anti-bluff defects were caught by ACTUALLY running these on the VM (not static
  rehearsal): the recorder script's wrong stop-signal (never captured video) and C42's
  invalid `assertDoesNotExist` masking assertion — both fixed (`c366454f`).

Each fix's falsifiability mutation was re-performed (verbatim RED → revert GREEN); see the
per-fix BUGFIXES.md entries + commit Bluff-Audit stamps.
