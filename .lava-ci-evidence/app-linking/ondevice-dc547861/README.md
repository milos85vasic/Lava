# On-device fix verification — Bug A + Bug B (client ↔ api-app linking)

- **Date:** 2026-06-04
- **Commit:** `dc547861` (fix: on-device API reachability + idempotent engine start)
- **Device:** Samsung Galaxy S23 Ultra, `R5CW33CBVQV`, Android 16 / API 36 (real physical device; adb-driven + uiautomator-driven, API-36-safe — the Espresso Challenges skip on API 36).
- **Artifacts:** rebuilt debug APKs (`app-debug.apk`, `api-app-debug.apk`) installed via `adb -s R5CW33CBVQV install -r -g`.

## Results (both operator-reported bugs FIXED — real evidence)

| Bug | Before (operator-reported) | After (this verification) | Evidence |
|---|---|---|---|
| **A** — "API did not respond" selecting the on-device API | discovered row showed doubled port `192.168.31.119:8443:8443` (GoApi.host carried embedded port → invalid connect target) | discovered row shows **`192.168.31.119:8443`** (single); tapping it **connects + advances to "Pick your providers"** — no "did not respond" | `discovered-host-after-fix.txt`, `apiselection-after-fix.png`, `bugA-selection-result.txt` ("Pick your providers"), `bugA-post-selection.png` |
| **B** — "listen 0.0.0.0:8443; bind: address already in use" re-opening the API app | every EXTRA_START_API relaunch re-bound the listener → crash | force-stop → start → **start again**: **no** real bind error (`address already in use`/`EADDRINUSE`/`listen…bind`), engine **still serving health=200** | `bugB-doublestart-binderrors.txt` (empty), `bugB-engine-still-serving.txt` (health=200) |

## How verified

- **Bug A:** launched the client `MainActivity` (explicit, to bypass the LeakCanary debug launcher), tapped "Get Started" → API-selection step via uiautomator; read the rendered discovered-row text (`192.168.31.119:8443` — single port); tapped the row; the wizard advanced to the Providers step ("Pick your providers") with no failure banner.
- **Bug B:** `am force-stop` → `am start … --ez lava.applink.START_API true` (engine binds) → a second identical `am start` (the "Open Lava API app" relaunch path); logcat shows no real bind error and the engine still answers `https://127.0.0.1:8443/health` 200.

Both fixes additionally carry falsifiable unit regression tests (`OnboardingViewModelTest` bare-host assertion; `ApiEngineControllerTest` idempotent-start) — see commit `dc547861` Bluff-Audit stamps. The earlier `OnboardingViewModelTest` had encoded Bug A as correct (a §6.J bluff), now corrected.
