# HelixQA Wiring Plan — Lava Android app on the Genymotion VM

| | |
|---|---|
| Revision | 1 |
| Created | 2026-06-08 |
| Last modified | 2026-06-08 |
| Status | active |

## Table of contents

- [1. Scope](#1-scope)
- [2. Claude Code as the LLM + Vision provider (BridgedCLIProvider)](#2-claude-code-as-the-llm--vision-provider-bridgedcliprovider)
- [3. Pointing an autonomous session at the Lava app on the Genymotion VM](#3-pointing-an-autonomous-session-at-the-lava-app-on-the-genymotion-vm)
- [4. Test banks consumed by the session](#4-test-banks-consumed-by-the-session)
- [5. What is still MISSING (concrete task list)](#5-what-is-still-missing-concrete-task-list)
- [6. Constraints honoured](#6-constraints-honoured)

## 1. Scope

This document specifies how to drive a real HelixQA autonomous QA session against
the **Lava Android app** (`digital.vasic.lava.client.dev`) installed on the
**Genymotion VM reachable at adb `127.0.0.1:6555`**, using **Claude Code** as the
LLM + Vision provider. It also enumerates the concrete gaps that must be closed
before a fully autonomous, end-to-end provider-multiplexed run is possible.

The Lava-owned test banks this session consumes live at
`lava-api-go/qa/banks/*.yaml` (seven banks; see §4).

> The HelixQA submodule itself (`submodules/helixqa`) is **not modified** by any
> step here — all configuration is supplied via env vars, CLI flags, and the
> Lava-owned banks directory.

## 2. Claude Code as the LLM + Vision provider (BridgedCLIProvider)

HelixQA already ships the bridge: `submodules/helixqa/pkg/llm/bridge_provider.go`
defines `BridgedCLIProvider`, which shells out to a CLI LLM tool with
`--json --print <prompt>` (plus `--model` and `--image` when set) and parses the
JSON `result` / `content` / `text` field of the response.

### 2.1 How discovery works (zero config)

The `autonomous` subcommand auto-discovers the bridge in
`cmd/helixqa/main.go` (the "Bridged CLI model discovery" block, ~line 526). It
iterates a fixed list — `{claude, qwen-coder, opencode}` — and for each runs
`osexec.LookPath(bin)`. If the binary is on `$PATH`, it is wrapped via
`llm.NewBridgedCLIProvider(cliPath, name, "")` and added to the provider pools.

Consequences for Claude Code:

- **No env var or flag is required to enable the bridge.** Putting the `claude`
  binary on `$PATH` is sufficient — discovery is by `LookPath("claude")`.
- **Vision is automatic for Claude.** `BridgedCLIProvider.SupportsVision()`
  returns `true` only when `cliName == "claude"` (bridge_provider.go ~line 133),
  so the discovered `claude` provider is added to BOTH the chat pool and the
  vision pool. `Vision()` writes each screenshot to a temp PNG and passes it via
  `--image <path>` (bridge_provider.go ~line 183–223). This is exactly the
  screenshot-analysis path the autonomous VisionEngine needs.
- **Model** is left empty (`""`), so the `--model` flag is omitted and the
  `claude` CLI uses its configured default. To pin a model, the bridge would need
  a non-empty `model` — see the gap in §5 (the discovery list hardcodes `""`).

### 2.2 Exact invocation the bridge issues

For a chat turn the bridge runs (bridge_provider.go `buildArgs`):

```
claude --json --print "<concatenated prompt>"
```

For a vision turn (screenshot analysis):

```
claude --json --print "<prompt>" --image /tmp/helixqa-vision-XXXX.png
```

Per-invocation timeout defaults to 120 s (`defaultBridgeTimeout`).

### 2.3 Pre-flight checklist

```bash
command -v claude                 # MUST resolve — discovery is LookPath("claude")
claude --json --print "ping"      # MUST return JSON with a result/content/text field
```

If `claude --json --print` does not emit a JSON object, the bridge falls back to
treating stdout as plain text (`parseResponse`), which still works for chat but is
lower-fidelity; prefer a `claude` build that honours `--json`.

## 3. Pointing an autonomous session at the Lava app on the Genymotion VM

### 3.1 Inputs

| Input | Value | Supplied via |
|---|---|---|
| App package | `digital.vasic.lava.client.dev` | env `HELIX_ANDROID_PACKAGE` |
| Device serial | `127.0.0.1:6555` (Genymotion) | env `HELIX_ANDROID_DEVICE` |
| Platforms | `android` | flag `--platforms android` |
| Project root | repo root (feature map from docs) | flag `--project` |
| Env file | `.env` (gitignored, real secrets) | flag `--env` |
| Output dir | `qa-results/` | flag `--output` |
| LLM + Vision | Claude Code on `$PATH` | auto-discovery (§2) |

The env-var names are read directly in `cmd/helixqa/main.go`:
`AndroidPackage: os.Getenv("HELIX_ANDROID_PACKAGE")` (~line 662) and
`AndroidDevice: os.Getenv("HELIX_ANDROID_DEVICE")` (~line 660). The autonomous
flags (`--project`, `--platforms`, `--env`, `--timeout`, `--coverage-target`,
`--output`, `--report`, `--curiosity`, `--curiosity-timeout`) are defined in the
`autonomous` flagset (~line 404).

### 3.2 Connect adb to the Genymotion VM

Genymotion exposes adb over TCP. Connect the host's adb server to it first so the
serial `127.0.0.1:6555` appears in `adb devices`:

```bash
adb connect 127.0.0.1:6555
adb devices                       # MUST list 127.0.0.1:6555 as 'device' (not 'offline')
adb -s 127.0.0.1:6555 shell pm list packages | grep digital.vasic.lava.client.dev
```

If the package is not installed, install the debug APK first:

```bash
adb -s 127.0.0.1:6555 install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3.3 The `.env` (real secrets — gitignored per §6.H / §11.4.10)

Create a `.env` at the location passed to `--env`. It MUST NOT be committed. Real
tracker credentials are referenced BY NAME in the banks and read at runtime here:

```dotenv
# Device / app targeting
HELIX_ANDROID_DEVICE=127.0.0.1:6555
HELIX_ANDROID_PACKAGE=digital.vasic.lava.client.dev

# Tracker credentials consumed by the per-provider banks (NEVER hardcoded in YAML)
RUTRACKER_USERNAME=...
RUTRACKER_PASSWORD=...
RUTOR_USERNAME=...
RUTOR_PASSWORD=...
KINOZAL_USERNAME=...
KINOZAL_PASSWORD=...
NNMCLUB_USERNAME=...
NNMCLUB_PASSWORD=...
# archiveorg + gutenberg are auth_type NONE — no credentials.

# LLM + Vision: none required — Claude Code is auto-discovered on $PATH (§2).
```

### 3.4 Launch command

```bash
cd /Volumes/T7/Projects/Lava
adb connect 127.0.0.1:6555

submodules/helixqa/bin/helixqa autonomous \
  --project . \
  --platforms android \
  --env ./.env \
  --timeout 2h \
  --coverage-target 0.9 \
  --output qa-results/ \
  --report markdown,html,json
```

Outputs land under `qa-results/` (report + tickets + videos), suitable as the
`docs/qa/<run-id>/` end-user evidence required by §11.4.83.

### 3.5 The session's 4 phases (what actually happens)

Per the HelixQA README, the autonomous session runs: (1) **Setup** — model
selection + feature map from `--project` docs + spawn agents + init VisionEngine;
(2) **Doc-Driven Verification** — workers verify documented features against the
running app, capturing screenshots/video; (3) **Curiosity-Driven Exploration**;
(4) **Report & Cleanup**. The NavigationEngine's Android `ActionExecutor` drives
the device over adb (taps/swipes/screencap); Claude (vision) interprets each
screenshot to decide the next action and to verify the `expected` of each bank step.

## 4. Test banks consumed by the session

Seven Lava-owned banks (validated byte-valid for the HelixQA loader — see the
parse evidence in the accompanying report), all `platforms: [android]`:

| Bank | Provider | auth_type | Download deliverable verified |
|---|---|---|---|
| `lava-rutracker-journey.yaml` | rutracker | CAPTCHA_LOGIN | torrent file / magnet |
| `lava-rutor-journey.yaml` | rutor | FORM_LOGIN | torrent file / magnet |
| `lava-kinozal-journey.yaml` | kinozal | FORM_LOGIN | torrent file / magnet |
| `lava-nnmclub-journey.yaml` | nnmclub | FORM_LOGIN | torrent file / magnet |
| `lava-archiveorg-journey.yaml` | archiveorg | NONE | HTTP file link (no torrent/magnet) |
| `lava-gutenberg-journey.yaml` | gutenberg | NONE | torrent file or HTTP file |
| `lava-onboarding-navigation.yaml` | (cross-cutting) | — | cold-start / gating / nav |

Auth types and download capabilities are taken from the real descriptors under
`core/tracker/<provider>/.../<Provider>Descriptor.kt`.

To list them through the real binary once it is buildable (§5):

```bash
submodules/helixqa/bin/helixqa list --banks lava-api-go/qa/banks/ --platform android
```

## 5. What is still MISSING (concrete task list)

These are the concrete gaps between "banks exist + bridge exists" and "a fully
autonomous provider-multiplexed run executes against the Genymotion VM". None of
them are solved by this document; each is an actionable task.

1. **HelixQA binary is not buildable from the current submodule checkout.**
   `submodules/helixqa/go.mod` contains **unresolved git conflict markers**
   (`<<<<<<< HEAD` / `=======` / `>>>>>>>` at lines 124/131/138) in its `replace`
   block, and its own-org module deps (`LLMsVerifier`, `LLMOrchestrator`,
   `VisionEngine`, `DocProcessor`, `challenges`, `containers`, ...) are not
   initialized at this checkout. **Task:** resolve the conflict + `git submodule
   update --init` the nested deps in a controlled bump of the helixqa pin (a
   deliberate submodule-pin update, performed in the submodule itself per the
   Decoupled-Reusable-Architecture pin policy — out of scope for this Lava-side
   change), then `cd submodules/helixqa && make build` to produce `bin/helixqa`.
   Until then, neither `helixqa list` nor `helixqa autonomous` can run.

2. **Navigator adapter for Lava-specific screens (Q6).** The autonomous
   `pkg/navigator` Android `ActionExecutor` drives generic adb taps/swipes and
   relies on Vision to locate controls. Lava's Compose screens expose specific
   semantics (onboarding step controls, provider rows, the captcha field, the
   torrent vs magnet download affordances). **Task:** define a Lava navigator
   adapter / semantics map (resource-ids or Compose `testTag`s for: provider list
   rows, login username/password/captcha fields, search input, result row, topic
   download + magnet controls) so the navigator can act deterministically instead
   of vision-guessing, with vision as the fallback. This is the open "Q6 navigator
   adapter" item.

3. **Session config for the bridge + device is not templated.** There is no
   committed `.env.example` enumerating `HELIX_ANDROID_DEVICE`,
   `HELIX_ANDROID_PACKAGE`, and the per-tracker credential VAR NAMES for a Lava
   autonomous run. **Task:** add a `lava-api-go/qa/.env.example` (placeholders
   only, no secrets — §6.R/§6.H) documenting every variable §3.3 lists, plus a
   `--model` pin decision for the bridge (the discovery block currently hardcodes
   model `""`; pinning a Claude model requires either a small upstream change to
   the discovery list or an env-driven override — itself a HelixQA-side task).

4. **Per-provider multiplexing / orchestration is not wired.** A single
   `helixqa autonomous` invocation points at one app + one feature map; it does
   not iterate the six provider banks as distinct credentialed journeys, nor reset
   app state (`pm clear`) between providers. **Task:** add a Lava-side thin runner
   (e.g. `lava-api-go/qa/run-provider-matrix.sh`) that, per provider:
   `adb connect 127.0.0.1:6555` → `pm clear digital.vasic.lava.client.dev` →
   export that provider's credential vars → invoke the session (or `helixqa run`)
   scoped to that provider's bank → collect `qa-results/<provider>/`. This is the
   multiplexing layer that turns seven banks into one matrix run.

5. **adb-over-TCP reachability gate.** The session assumes
   `127.0.0.1:6555` is in `adb devices` as `device`. **Task:** add a pre-flight
   step to the runner (#4) that runs `adb connect` + asserts the serial is online
   and the package is installed, failing loud (not silently testing nothing) per
   the anti-bluff posture.

6. **Download-payload verification is described, not yet asserted programmatically.**
   The banks specify "non-empty bencoded torrent" / "magnet:?xt=urn:btih:<hash>" /
   "HTTP 200 non-empty payload" as the `expected`. **Task:** ensure the navigator
   (or a post-step hook) actually captures the produced torrent bytes / magnet URI
   / HTTP file and validates the shape — so a green step proves a real working
   download, not merely that a button was tappable (§6.G / §11.4.69 sink-side
   evidence).

## 6. Constraints honoured

- **§6.R / §6.H / §11.4.10** — no credentials in any tracked file. The banks and
  this doc reference credential VARIABLE NAMES only; real values live in the
  gitignored `.env` loaded at runtime.
- **submodules/helixqa unmodified** — all configuration is external (env, flags,
  Lava-owned banks). The build blocker in §5.1 is reported honestly, not worked
  around by editing the submodule.
- **CONST-046** — banks describe structure + intent prose; they do not hardcode
  the exact user-facing literal strings the app must display as assertion-equality
  values.

## Sources verified

- `submodules/helixqa/pkg/llm/bridge_provider.go` — bridge invocation, vision gating.
- `submodules/helixqa/cmd/helixqa/main.go` — autonomous flags + env var names + bridge discovery block.
- `submodules/helixqa/README.md` — bank schema, autonomous 4-phase model, `list`/`autonomous` CLI.
- `core/tracker/<provider>/.../{<Provider>Descriptor}.kt` — per-provider id, displayName, authType, capabilities.
