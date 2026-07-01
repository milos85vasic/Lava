# `scripts/run-helixqa-provider-qa.sh`

**Revision:** 2
**Last modified:** 2026-06-30T00:00:00Z
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + Lava §6.AD (HelixConstitution Inheritance). Constitutional bindings of the script itself: §6.AE (per-provider Challenge coverage), §6.AH (device in VM/container — no host-direct), §11.4.3 (topology SKIP, never a fake PASS), §11.4.27 (HelixQA full coverage), §11.4.87/§11.4.98 (autonomous, no manual intervention).

## Overview
Thin Lava glue that runs **HelixQA** against the Lava Android app for a given provider
(or all), feeding the matching Lava test bank, using the **`claude` CLI as the LLM/Vision
model bridge** (HelixQA's `BridgedCLIProvider` — no API key needed). It is the per-provider
companion to `scripts/run-genymotion-challenges.sh`.

## Prerequisites
- HelixQA buildable: `submodules/helixqa` + its own-org dep submodules
  (see `docs/qa/helixqa-dependency-submodules.md`, added 2026-06-08). `go build ./cmd/helixqa`.
- Lava banks present under `lava-api-go/qa/banks/`:
  - default mode resolves `lava-<provider>-journey.yaml`;
  - `--matrix` resolves `lava-<provider>-matrix-journey.yaml` instead.
- `go` (1.24+), `adb`, and the `claude` CLI on `PATH`.
- **A reachable device** — the Genymotion VM (default `127.0.0.1:6555`) booted first, or a
  physical device with the Lava app (`digital.vasic.lava.client.dev`) installed. Per §6.AH the
  device MUST be a VM/container emulator, never a host-direct one.

## Usage
```bash
# explicit per-provider bank run (default mode)
scripts/run-helixqa-provider-qa.sh --provider rutor --serial 127.0.0.1:6555

# matrix-journey bank for a provider (the broader matrix variant)
scripts/run-helixqa-provider-qa.sh --provider archiveorg --matrix

# every provider, sequentially
scripts/run-helixqa-provider-qa.sh --all

# doc-driven autonomous QA session instead of explicit banks
scripts/run-helixqa-provider-qa.sh --provider kinozal --autonomous
```

## Inputs (flags)
| Flag | Default | Description |
|---|---|---|
| `--provider <id>` | — (required unless `--all`) | One of the PROVIDERS set (below). |
| `--all` | — | Run every provider bank sequentially (non-zero exit if any fails). |
| `--serial <s>` | `$LAVA_REAL_DEVICE_SERIALS` or `127.0.0.1:6555` (the Genymotion VM) | adb device serial to target. |
| `--autonomous` | off (`helixqa run --banks`) | Use `helixqa autonomous` (doc-driven session) instead of the explicit per-provider bank. |
| `--matrix` | off (`journey`) | Resolve banks as `lava-<prov>-matrix-journey.yaml` instead of the default `lava-<prov>-journey.yaml`. |

### PROVIDERS set
`rutracker`, `rutor`, `iptorrents`, `kinozal`, `nnmclub`, `archiveorg`, `gutenberg`
(the `PROVIDERS` array in the script; `--all` iterates exactly this list). A provider with no
matching bank file is `SKIP`ped (honest, not a failure).

### Environment overrides
- `LAVA_QA_PACKAGE` — app package id (default `digital.vasic.lava.client.dev`).
- `LAVA_REAL_DEVICE_SERIALS` — default adb serial when `--serial` is omitted.

## Exit codes (anti-bluff)
- `0` — QA run completed.
- `1` — QA run reported failures (one or more banks failed).
- `2` — **honest BLOCKED**: no adb device in `device` state (or `adb` missing). NOT a pass
  (§6.AH/§11.4.3). Boot the Genymotion VM / attach a device, then re-run.
- `3` — helixqa build failed (check the dep submodules).
- `64` — usage error (unknown flag, or neither `--provider` nor `--all` given).

## Internal behaviour
1. Parse flags; require `--provider <id>` or `--all`.
2. Honest device gate first — `adb connect <serial>` + check for a `device`-state entry; exit 2 if none.
3. Build the `helixqa` binary to a temp path (`GOMAXPROCS=2 go build ./cmd/helixqa`); print its version.
4. Confirm the `claude` bridge is on `PATH` (warn if absent — autonomous mode then lacks the LLM/Vision bridge).
5. Per provider, resolve the bank (`lava-<prov>-<journey|matrix-journey>.yaml`); `SKIP` if absent, else:
   `helixqa run --banks <bank> --platform android --device <serial> --package <pkg>
   --output .lava-ci-evidence/helixqa/<provider>/<run-id>/`
   (or `helixqa autonomous --project <root> --platforms android --env .env --output …` in `--autonomous` mode).

## Outputs
Per-run evidence under `.lava-ci-evidence/helixqa/<provider>/<run-id>/` (run id is a UTC
timestamp suffixed with the provider).

## Verified
- `bash -n` parse: clean (§11.4.67).
- No-device gate: exits 2 with the BLOCKED message when no VM is booted (honest BLOCKED, §6.AH/§11.4.3).

## Related
- `docs/qa/helixqa-wiring-plan.md` — bridge-discovery facts + verbatim `helixqa list` proof.
- `docs/qa/helixqa-dependency-submodules.md` — the own-org deps that make the binary buildable.
- `scripts/run-genymotion-challenges.sh` — the Genymotion Compose-Challenge device path.
- `scripts/autonomous-qa/run-matrix.sh` — the backend×provider autonomous-QA matrix orchestrator.

_Last verified: 2026-06-30 (revision 2 — documents `--matrix`, the full 7-provider PROVIDERS set incl. `iptorrents`/`gutenberg`, the `--serial` default, exit code 64, and the per-script constitutional bindings)._
