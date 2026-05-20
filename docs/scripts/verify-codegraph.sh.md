# `scripts/verify-codegraph.sh` — user guide

**Revision:** 1
**Last modified:** 2026-05-20T15:30:00Z

## Overview

`scripts/verify-codegraph.sh` is the anti-bluff verification suite runner for
the **codegraph** code-intelligence integration (see `docs/CODEGRAPH.md` and
constitution §11.4.78). Per the Local-Only CI/CD constitutional constraint it
**is** the codegraph quality gate — there is no hosted-CI equivalent.

It runs the six `tests/codegraph/test_*.sh` layers in order, tees per-layer
evidence to `.lava-ci-evidence/codegraph/<UTC-timestamp>/`, writes a
`summary.md`, and exits non-zero if any layer FAILs.

## Prerequisites

- `codegraph` installed and on `PATH` (`npm install -g @colbymchenry/codegraph`).
- `.codegraph/codegraph.db` present — run `codegraph index` first if absent.
- `python3` (used to drive the MCP JSON-RPC layers).
- For the full run (layer 05): the 5 CLI agents (`claude`, `opencode`, `qwen`,
  `kimi`, `crush`) installed; un-authenticated agents are reported as documented
  SKIP gaps, never as failures.

## Usage

```bash
scripts/verify-codegraph.sh            # full run — all 6 layers, incl. agent E2E
scripts/verify-codegraph.sh --quick    # layers 01-04 + 06 only (skip slow LLM layer 05)
```

Environment overrides:
- `CODEGRAPH_EVIDENCE_DIR` — override the evidence output directory.
- `CODEGRAPH_AGENT_TIMEOUT` — per-agent timeout in seconds for layer 05 (default 240).

## The six layers

| Layer | File | Proves |
|-------|------|--------|
| 01 | `test_01_index_reality.sh` | codegraph indexed the real Lava codebase; secrets/submodules excluded |
| 02 | `test_02_query_correctness.sh` | `codegraph query` resolves real symbols to real `file:line` |
| 03 | `test_03_mcp_protocol.sh` | the MCP server returns real data over real stdio JSON-RPC |
| 04 | `test_04_agent_connectivity.sh` | all 5 CLI agents register/connect to codegraph |
| 05 | `test_05_agent_e2e.sh` | each agent provably *uses* codegraph in a real LLM turn (unforgeable node-count challenge) |
| 06 | `test_06_falsifiability.sh` | the suite FAILs when codegraph is deliberately broken, PASSes when restored |

## Edge cases

- **`--quick`** skips the slow LLM-driven layer 05 — use it for fast local
  checks; the full run is required before relying on the integration.
- **Documented gaps (SKIP):** layer 05 marks an agent SKIP when it cannot be
  run in the environment (missing credentials, exhausted quota, an
  agent-environment incompatibility). A SKIP is a documented gap, never a faked
  PASS (§6.J / §6.L); it does not fail the suite, but is reported prominently.
- **Pre-flight failure (exit 2):** if `codegraph` is not installed or
  `.codegraph/codegraph.db` is missing, the runner aborts before running any
  layer.
- **Falsifiability rehearsal** (layer 06) moves `.codegraph/codegraph.db` aside
  and restores it; a `trap` restores the DB even if interrupted.

## Internal behaviour

The runner sources nothing; each `tests/codegraph/test_*.sh` sources
`tests/codegraph/lib.sh` for shared assertions and the MCP JSON-RPC drivers.
Each layer exits 0 (all assertions pass) or 1 (any fail). The runner aggregates
PASS/FAIL/SKIP counts from the per-layer logs and writes `summary.md`. Exit
codes: `0` suite passed, `1` a layer FAILed, `2` pre-flight failed.

## Related scripts

- `tests/codegraph/lib.sh` — shared library (assertions, MCP drivers).
- `tests/codegraph/test_0[1-6]_*.sh` — the six verification layers.
- `docs/CODEGRAPH.md` — the codegraph integration reference.

## Last verified

2026-05-20 — full run: 45 pass · 0 fail · 4 documented SKIP gaps.
