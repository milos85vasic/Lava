# `scripts/run-helixqa-provider-qa.sh`

**Revision:** 1
**Last modified:** 2026-06-08T00:00:00Z

## Overview
Thin Lava glue that runs **HelixQA** against the Lava Android app for a given provider
(or all), feeding the matching Lava test bank, using the **`claude` CLI as the LLM/Vision
model bridge** (HelixQA's `BridgedCLIProvider` — no API key needed). It is the per-provider
companion to `scripts/run-genymotion-challenges.sh`.

## Prerequisites
- HelixQA buildable: `submodules/helixqa` + its 5 own-org dep submodules
  (`doc_processor`, `llm_orchestrator`, `llm_provider`, `llms_verifier`, `vision_engine` — added
  2026-06-08; see `docs/qa/helixqa-dependency-submodules.md`). `go build ./cmd/helixqa` → `v0.2.0`.
- Lava banks present: `lava-api-go/qa/banks/lava-<provider>-journey.yaml` (7 banks, 32 cases).
- `go` (1.24+), `adb`, and the `claude` CLI on `PATH`.
- **A reachable device** — the Genymotion VM at `127.0.0.1:6555` (boot it first) or a physical
  device with the Lava app (`digital.vasic.lava.client.dev`) installed.

## Usage
```bash
# explicit per-provider bank run (default mode)
scripts/run-helixqa-provider-qa.sh --provider rutor --serial 127.0.0.1:6555
# every provider
scripts/run-helixqa-provider-qa.sh --all
# doc-driven autonomous QA session instead of explicit banks
scripts/run-helixqa-provider-qa.sh --provider kinozal --autonomous
```

## Exit codes (anti-bluff)
- `0` — QA run completed.
- `2` — **honest BLOCKED**: no adb device in `device` state (or `adb` missing). NOT a pass
  (§6.AH/§11.4.3). Boot the Genymotion VM / attach a device, then re-run.
- `3` — helixqa build failed (check the dep submodules).
- `1` — QA run reported failures.

## Internal behaviour
1. Honest device gate first — `adb connect <serial>` + check for a `device`-state entry; exit 2 if none.
2. Build the `helixqa` binary to a temp path; print its version.
3. Confirm the `claude` bridge is on `PATH` (warn if absent).
4. Per provider: `helixqa run --banks <bank> --platform android --device <serial> --package <pkg>
   --output .lava-ci-evidence/helixqa/<provider>/<run-id>/` (or `helixqa autonomous --project <root>
   --platforms android --env .env --output …` in `--autonomous` mode).

## Verified
- `bash -n` parse: clean (§11.4.67).
- No-device gate: exits 2 with the BLOCKED message (proven 2026-06-08, no VM booted).

## Related
- `docs/qa/helixqa-wiring-plan.md` — bridge-discovery facts + verbatim `helixqa list` proof.
- `docs/qa/helixqa-dependency-submodules.md` — the 5 own-org deps that make the binary buildable.
- `scripts/run-genymotion-challenges.sh` — the Genymotion Compose-Challenge device path.

_Last verified: 2026-06-08._
