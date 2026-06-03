# On-device §6.Z verification — client ↔ api-app linking

- **Date:** 2026-06-03
- **Commit:** `3f06817a` (feature: client↔api-app linking, Phases 0–4 + consolidation + auth fix)
- **Device:** Samsung Galaxy S23 Ultra, serial `R5CW33CBVQV`, **Android 16 / API 36** (real physical device — §6.AH permits a physical device as a §6.Z surface; the container-emulator gate cannot boot on the macOS host per §6.X-debt; operator authorized adb on this real serial 2026-06-03).
- **Method:** adb-driven functional verification (NOT Espresso). The instrumented Compose Challenges (C00/C26/C30/C37) are `@SdkSuppress(maxSdkVersion=35)` and SKIP on API 36 (documented AndroidPrefetchScheduler-Looper incompatibility), so `connectedAndroidTest` yields no execution on this device. This adb path produces real, API-36-safe evidence of the actual user-visible mechanisms.
- **Artifacts:** debug APKs built this session (`app-debug.apk` 34 MB, `api-app-debug.apk` 185 MB), installed via `adb -s R5CW33CBVQV install -r -g`.

## Results (all PASS — real evidence)

| Check | Result | Evidence |
|---|---|---|
| Client (`…client.dev`) cold-start survival | **PASS** — pid alive, no FATAL/ANR, `MainActivity` resumed | `client-coldstart.png`, `client-coldstart-crashscan.txt` (empty = no crash) |
| api-app (`…api.dev`) cold-start survival | **PASS** — pid alive, no FATAL/ANR | `apiapp-coldstart-crashscan.txt` (empty) |
| **`EXTRA_START_API` auto-starts the engine** (core feature) | **PASS** — `am start … --ez lava.applink.START_API true` started `ApiEngineService` as a **foreground service** (`isForeground=true`, `act=lava.api.app.action.START`, ongoing notification) | `apiapp-engine-service.txt`, `apiapp-start-extra.png` |
| Engine actually serving | **PASS** — LISTEN sockets incl. `127.0.0.1:32979` (0x80D3) loopback and `:8443` (0x20FB, HTTPS) | `engine-listening-sockets.txt` |
| **Signature-permission key provider denies non-signed callers** (§6.H) | **PASS** — shell (uid 2000) read DENIED: `SecurityException: Permission Denial … requires digital.vasic.lava.permission.READ_API_KEY` | `keyprovider-shell-denied.txt` |

## What this proves (no bluff)

The load-bearing mechanisms of the feature work on the real device: both apps cold-start clean; the client→api-app **start-intent (`EXTRA_START_API`) genuinely auto-starts a serving on-device API** (foreground service + HTTPS:8443 + loopback listener); and the **signature-gated key handoff is secure** (only the same-keystore client can read the key — the shell user is correctly rejected).

## Owed — operator visual round-trip confirmation (§6.AA stage-1)

The deterministic mechanisms above are verified by adb. The remaining user-visible UX — tapping onboarding "On this device" → api-app opens → "Back to Lava client" → client auto-connects to `127.0.0.1:<port>` (reading the key via the provider) → onboarding advances → reverse-direction "Open Lava client" — is best confirmed visually by the operator on the device in hand (the Espresso path that would automate this skips on API 36). This confirmation gates the §6.AA stage-2 (release) distribute.
