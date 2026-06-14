# Defect B — api-app Crashlytics telemetry misattribution (findings)

Date: 2026-06-14
Scope: investigation + prepared remediation (no build change applied, no rebuild,
no secret committed)
Classification: project-specific (Lava api-app Firebase wiring)

## Symptom

Crashlytics crashes and non-fatals originating from the standalone **api-app**
(`digital.vasic.lava.api` / `.api.dev`) report under the **client** Firebase app
in the Firebase console instead of the api-app's own Firebase app.

## Root cause — CONFIRMED

`api-app/google-services.json` (tracked, on disk) maps the api-app package names
to the **client app's** `mobilesdk_app_id` values. The `google-services` Gradle
plugin selects the `client[]` entry by matching `package_name`, then bakes that
entry's `mobilesdk_app_id` into the APK. Because the api-app's package_name entry
carries the client app's app id, the api-app registers with Firebase as the
client app, and all its Crashlytics/Analytics telemetry lands under the client
app id. This is the misattribution.

### Evidence (current on-disk file vs. Firebase authoritative truth)

api-app applicationId is confirmed in `api-app/build.gradle.kts`:
`digital.vasic.lava.api` (release), `+ ".dev"` suffix → `digital.vasic.lava.api.dev`
(debug).

Firebase project `lava-vasic-digital` registered Android apps (via Firebase MCP
`firebase_list_apps` / `firebase_get_sdk_config`):

| mobilesdk_app_id (suffix) | Firebase displayName | real package_name |
|---|---|---|
| `...456475e2ef4039d8cfd20a` | Lava Android | `digital.vasic.lava.client` |
| `...54ca2ca31e6c4f42cfd20a` | Lava Android (Debug) | `digital.vasic.lava.client.dev` |
| `...d57b960e955645f6cfd20a` | Lava API (release) | `digital.vasic.lava.api` |
| `...2932451e07ca80a7cfd20a` | Lava API (debug) | `digital.vasic.lava.api.dev` |

Current on-disk `api-app/google-services.json` contains:

| package_name in file | mobilesdk_app_id in file | what that app id ACTUALLY is |
|---|---|---|
| `digital.vasic.lava.api` | `...456475e2ef4039d8cfd20a` | **client release app** (WRONG) |
| `digital.vasic.lava.api.dev` | `...54ca2ca31e6c4f42cfd20a` | **client debug app** (WRONG) |

The correct app ids for the api-app are `...d57b960e955645f6cfd20a` (release) and
`...2932451e07ca80a7cfd20a` (debug) — i.e. the "Lava API (release)" / "Lava API
(debug)" Firebase apps. These match the app ids recorded in the session notes.

The two real api-app Firebase apps **exist** in the project (no `firebase_create_app`
needed).

## Remediation (prepared, not yet applied)

A corrected `google-services.json` has been generated to a non-committed staging
path so it cannot be accidentally committed:

    /tmp/api-app-google-services.corrected.json

It contains exactly two `client[]` entries:

- `digital.vasic.lava.api`     → `1:815513478335:android:d57b960e955645f6cfd20a`
- `digital.vasic.lava.api.dev` → `1:815513478335:android:2932451e07ca80a7cfd20a`

(Same `project_info`, same shared Android API key already present in the tracked
file — apiKeyId `56cefd24-...`, shared across all four apps in this project; no
new secret introduced.)

### Steps to apply

1. Copy the staged file over the tracked one:
   `cp /tmp/api-app-google-services.corrected.json api-app/google-services.json`
   (NOTE: `api-app/google-services.json` is gitignored per §6.H — it is replaced
   on disk only, never committed.)
2. Rebuild the api-app (debug first per §6.AA): the google-services plugin will
   bake `...d57b960e955645f6cfd20a` / `...2932451e07ca80a7cfd20a` into the APK.
3. Verify on device: trigger a non-fatal from the api-app and confirm it lands
   under "Lava API (release/debug)" in the Firebase console, not "Lava Android".

## GO / NO-GO

**GO.** Both correct api-app Firebase apps already exist; the corrected config is
staged at `/tmp/api-app-google-services.corrected.json` and is ready to drop in +
rebuild. No operator Firebase action (no `firebase_create_app`) is required.
