# `scripts/run-helixqa-challenges.sh` — User Guide

**Last verified:** 2026-05-16 (HelixQA integration Option 1 — shell wiring)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + Lava §6.AE (Comprehensive Challenge Coverage) + §6.J (Anti-Bluff Functional Reality) + §6.X (Container-Submodule Emulator Wiring — see "container vs host runner" below)
**Classification:** project-specific (the per-script wiring choices are Lava-specific; the host-vs-container delegation is universal per §6.X)

## Overview

Thin Lava-side wrapper around HelixQA's 11 Challenge scripts shipped at `Submodules/HelixQA/challenges/scripts/`. Invokes them in sequence, captures per-script stdout/stderr to log files, writes a roll-up attestation JSON, and reports per-script PASS / FAIL / SKIP outcomes.

Implements **Option 1** from `docs/plans/2026-05-16-helixqa-integration-design.md` — shell-level wiring with NO modification to HelixQA. Future cycles MAY:

- Wrap invocations inside the Containers submodule's runtime per §6.X (open question #1 in the integration design — currently runs on the **HOST**).
- Add Go-package linking for Group A packages (Option 2 in the design).
- Migrate Compose UI Challenge Tests to use HelixQA as backend (Option 3 in the design — deferred indefinitely).

## The 11 wired scripts

Canonical list (matches `HELIXQA_SCRIPTS` array in the script body):

| # | Script | What it checks (HelixQA's own framing) |
|---|---|---|
| 1 | `anchor_manifest_challenge.sh` | `docs/behavior-anchors.md` rows + per-anchor file/symbol resolution |
| 2 | `bluff_scanner_challenge.sh` | scanner self-test + tree-wide bluff scan |
| 3 | `chaos_failure_injection_challenge.sh` | chaos-engineering harness |
| 4 | `ddos_health_flood_challenge.sh` | DDoS-resilience health-endpoint flooding |
| 5 | `host_no_auto_suspend_challenge.sh` | host suspend / hibernate / sleep targets masked + sleep.conf override (Linux-only) |
| 6 | `mutation_ratchet_challenge.sh` | go-mutesting kill-rate ratchet |
| 7 | `no_suspend_calls_challenge.sh` | source-tree scan for forbidden host-power-management invocations |
| 8 | `scaling_horizontal_challenge.sh` | horizontal-scaling load test |
| 9 | `stress_sustained_load_challenge.sh` | sustained-load stress test |
| 10 | `ui_terminal_interaction_challenge.sh` | terminal UI interaction (limited Lava applicability) |
| 11 | `ux_end_to_end_flow_challenge.sh` | end-to-end UX flow harness |

## Usage

```bash
# Run ALL 11 HelixQA Challenge scripts; evidence to dated dir
bash scripts/run-helixqa-challenges.sh

# Run only specific scripts (comma-separated basenames without .sh)
bash scripts/run-helixqa-challenges.sh --only host_no_auto_suspend_challenge,no_suspend_calls_challenge

# Custom evidence dir (e.g. inside a parent runner's dir)
bash scripts/run-helixqa-challenges.sh --evidence-dir .lava-ci-evidence/myrun/helixqa

# Halt the loop on first non-zero exit (default: continue)
bash scripts/run-helixqa-challenges.sh --stop-on-fail

# Suppress stdout summary (machine consumption only)
bash scripts/run-helixqa-challenges.sh --json-only
```

## Inputs

| Arg | Description |
|---|---|
| `--evidence-dir <dir>` | Output directory (default: `.lava-ci-evidence/helixqa-challenges/<UTC-timestamp>/`) |
| `--only NAME1,NAME2,...` | Run only the listed scripts (basename without `.sh`); errors if none match |
| `--continue-on-fail` | Keep running scripts after a FAIL (default behavior) |
| `--stop-on-fail` | Halt the loop on first FAIL (useful for triage of cascading failures) |
| `--json-only` | Suppress the human-readable stdout summary; only write the attestation JSON |

## Outputs

```
<evidence-dir>/
├── helixqa-attestation.json          # roll-up: pin, runner, totals, per-script rows
├── anchor_manifest_challenge.log     # per-script stdout+stderr
├── bluff_scanner_challenge.log
├── chaos_failure_injection_challenge.log
├── ddos_health_flood_challenge.log
├── host_no_auto_suspend_challenge.log
├── mutation_ratchet_challenge.log
├── no_suspend_calls_challenge.log
├── scaling_horizontal_challenge.log
├── stress_sustained_load_challenge.log
├── ui_terminal_interaction_challenge.log
└── ux_end_to_end_flow_challenge.log
```

### Attestation JSON shape

```json
{
  "timestamp": "2026-05-16T03:52:15Z",
  "helixqa_pin": "403603dbd47b971549709bd9f2efa30dca810097",
  "helixqa_runner": "host",
  "helixqa_runner_caveat": "§6.X container-wrapping owed in future cycle per integration-design Open Q#1",
  "total_scripts": 11,
  "pass_count": 5,
  "fail_count": 2,
  "skip_count": 4,
  "halted_early": false,
  "all_passed": false,
  "scripts": [
    {"name": "anchor_manifest_challenge.sh", "result": "FAIL", "exit_code": 1, "duration_seconds": 0, "log": "anchor_manifest_challenge.log"},
    ...
  ]
}
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | All invoked scripts returned 0 (or `SKIP` exit 2). Zero `FAIL` outcomes. |
| 1 | One or more invoked scripts returned non-zero exit other than the SKIP-canonical 2. Real defect surfaced by HelixQA OR `--stop-on-fail` halted the loop. |
| 2 | Invalid arguments (`--only` matched no scripts; unknown flag). |
| 3 | Missing dependency — `Submodules/HelixQA` directory absent, OR the canonical 11-script wired list drifted from what the pin actually ships. |

The 0/1/2/3 split is **anti-bluff load-bearing**: exit 0 means scripts genuinely ran with no failures; exit 3 means we honestly cannot claim to have run them. Per §6.J we never silently mask a precondition gap as "success".

## Per-script SKIP / FAIL semantics

The wrapper treats each script's own exit codes as authoritative and classifies:

- `exit 0` → **PASS** — script reports success
- `exit 2` → **SKIP** — script's documented "scanner missing / precondition unmet" exit code (per `no_suspend_calls_challenge.sh` convention)
- any other non-zero → **FAIL** — real defect OR Linux-only check failing on macOS/other host

**Many of the 11 HelixQA scripts are LINUX-SPECIFIC** (they invoke `systemctl`, read `/etc/systemd/`, parse `journalctl`). On macOS or any host without systemd, these will FAIL — not because Lava is broken, but because the HelixQA author wrote them assuming a Linux host. **This is the honest behavior** — per §6.J the wrapper does NOT mis-classify "wrong-OS" as PASS or as SKIP without operator intervention. Operators on macOS hosts SHOULD use `--only` to filter to portable scripts (`anchor_manifest_challenge`, `bluff_scanner_challenge`, `mutation_ratchet_challenge` — when the HelixQA-internal scanner directory is present).

A future cycle (per integration-design Open Q#1) MAY wrap invocations inside the Containers submodule's Linux runtime so every script runs in a Linux container regardless of host OS. **Owed**.

## Wiring into `scripts/run-challenge-matrix.sh`

The §6.AE Challenge matrix runner accepts an opt-in `--include-helixqa` flag that invokes this wrapper as a sibling step BEFORE the AVD matrix runs:

```bash
bash scripts/run-challenge-matrix.sh --include-helixqa
```

The flag is **OFF by default** so existing matrix invocations (operator iterations, prior CI flows) are unaffected. When enabled:

- HelixQA wrapper runs on the host FIRST (host independent of AVD matrix)
- Wrapper evidence lands at `<matrix-evidence-dir>/helixqa/`
- A non-zero HelixQA exit is folded into the matrix runner's final exit code (matrix exit dominates if both fail; HelixQA exit promotes matrix-0 to 1 if HelixQA reports FAIL)
- HelixQA does NOT run inside the §6.X-debt darwin/arm64 gate-host skip — it runs regardless because it doesn't need KVM

## Anti-bluff posture

Per `docs/plans/2026-05-16-helixqa-integration-design.md` §"6.J anti-bluff posture":

1. **Each invoked script MUST actually be invokable.** The wrapper's pre-flight rejects (exit 3) if the wired list drifts from the actual pin contents.
2. **Each result MUST be from a real execution.** Per-script log files are written from the actual subprocess output — captured stdout+stderr go to `<evidence-dir>/<script>.log`. A future audit can verify the log content corresponds to the script's actual behavior (the hermetic test `tests/check-constitution/test_helixqa_wiring.sh` asserts the fixture's stdout string appears in the produced log).
3. **Per §6.AC non-fatal telemetry:** SKIP exits do NOT cause the wrapper itself to FAIL but ARE recorded distinctly from PASS. The attestation JSON's `skip_count` is operator-visible so a regression that flips many PASSes to SKIPs is detectable.
4. **Per §6.L:** the wrapper exists; HelixQA-emitted FAILs are real defects to triage; HelixQA-emitted SKIPs are honest acknowledgments of precondition gaps — NOT false-pass coverage.

## Hermetic test

`tests/check-constitution/test_helixqa_wiring.sh` — 4 fixtures:

| Fixture | Asserts |
|---|---|
| `test_passes_when_helixqa_present_and_all_green` | Wrapper exit 0; attestation reports 11/0/0; per-script log files exist + contain the fixture stdout (proving subprocess actually ran) |
| `test_fails_when_script_missing` | Wrapper exit 3 (wiring drift); error message names the missing script |
| `test_skip_mode_when_helixqa_absent` | Wrapper exit 3 (missing submodule); error message includes the `git submodule update --init Submodules/HelixQA` remediation command; no attestation written |
| `test_fail_classified_correctly` | Wrapper exit 1 when one script returns 1; attestation marks that script as FAIL (not SKIP — anti-bluff classification) |

Run: `bash tests/check-constitution/test_helixqa_wiring.sh`

## Open questions (owed to future cycles)

Per `docs/plans/2026-05-16-helixqa-integration-design.md`:

1. **Container vs host runner** (§6.X integration): HelixQA scripts currently run on the host. Per §6.X they should ultimately run inside `Submodules/Containers/cmd/emulator-matrix --runner=containerized`. **Owed**.
2. **Real-deps vs stub gating**: `bluff_scanner_challenge.sh` runs `go-mutesting` which needs Go + project modules. Currently runs against HelixQA's own self-test fixtures. Owed: decide whether to execute against `lava-api-go` real or stub.
3. **Evidence-directory boundary**: HelixQA scripts may emit their own evidence to `<helixqa-root>/evidence/<run-id>/`. This wrapper currently redirects only stdout/stderr to its own log files. Owed: full evidence redirection.
4. **§6.W mirror policy**: HelixQA scripts may push artifacts or invoke external services. Lava's §6.W bounds remotes to GitHub + GitLab. Owed: per-script remote audit.

## Cross-references

- `docs/plans/2026-05-16-helixqa-integration-design.md` — design doc (Option 1 chosen this cycle; Option 2 + Option 3 deferred)
- `docs/scripts/run-challenge-matrix.sh.md` — the `--include-helixqa` flag added in this same commit
- `docs/helix-constitution-gates.md` — `CM-HELIXQA-WIRING` gate row (added in this commit)
- `Submodules/HelixQA/CLAUDE.md` — HelixQA's own anti-bluff covenant
- `Submodules/HelixQA/README.md` — HelixQA framework overview
- Lava `CLAUDE.md` §6.AE + §6.J + §6.X + §6.AD (HelixConstitution inheritance)
