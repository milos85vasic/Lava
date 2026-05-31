# `scripts/check-lva-tickets.sh`

**Gate ID:** `CM-LVA-TICKETS-SYNC`
**Constitutional basis:** HelixConstitution §11.4.93 (workable-items SQLite DB is the single source of truth) + §11.4.95 (the DB is TRACKED in git, NEVER gitignored) + §11.4.106 (generated tracker docs MUST be byte-identical to a fresh generation FROM the DB) + §11.4.33/34 (type-aware closure + reopen-attribution schema guards) + §11.4.18 (script docs) + §6.A (real-binary contract) + §6.J/§6.L (anti-bluff).
**Classification:** project-specific (the LVA key + Lava's `docs/tickets/` doc set are Lava-specific; the §11.4.93/95/106 mandates are universal per HelixConstitution).

## Purpose

Proves the LVA workable-items ticket system (built at `tools/lava-tickets/`, DB + docs at `docs/tickets/`) is internally consistent:

1. **DB exists and is NOT gitignored** (§11.4.95). `docs/tickets/tickets.db` must be present and `git check-ignore` must report it is *not* ignored.
2. **Generated docs are byte-identical to the DB** (§11.4.106). The gate runs the REAL `lava-tickets verify` binary; a stale or hand-edited `Issues.md` / `Fixed.md` / `Issues_Summary.md` / `Fixed_Summary.md` makes `verify` exit non-zero, which fails the gate.
3. **Schema-integrity triggers are intact** (§11.4.33/34). The gate queries `sqlite_master` for the load-bearing triggers (`trg_closure_status_typeaware`, `trg_closure_status_typeaware_update`, `trg_reopen_attribution`, `trg_ticket_render_id`, `trg_operator_blocked_details`). A DB with any of these dropped has had its anti-bluff guards stripped and fails the gate.

The gate does **not** re-implement `verify` in bash — it asserts the actual binary's exit code (§6.A real-binary contract). If the binary is missing it is built via `go build` (no sudo, `GOMAXPROCS=2` per §6.T.2).

## Usage

```bash
scripts/check-lva-tickets.sh              # strict (default) — exit 1 on violation
scripts/check-lva-tickets.sh --advisory   # exit 0 even on violation
LAVA_LVA_TICKETS_STRICT=0 scripts/check-lva-tickets.sh   # env form of advisory
```

## Modes

| Mode | Behavior |
|------|----------|
| `--strict` (default) | exit 1 on any violation |
| `--advisory` | exit 0 even on violation (incremental adoption) |
| `LAVA_LVA_TICKETS_STRICT=0` | env form of `--advisory` |

## Overrides (used by the hermetic test)

| Env var | Default | Purpose |
|---------|---------|---------|
| `LAVA_LVA_TICKETS_DB` | `docs/tickets/tickets.db` | point the gate at a fixture DB |
| `LAVA_LVA_TICKETS_OUT` | dir of the DB | tracker-docs directory to verify against |
| `LAVA_LVA_TICKETS_TOOLDIR` | `tools/lava-tickets` | location of the Go module + `bin/` |
| `LAVA_REPO_ROOT` | repo root | synthetic repo root |

## What it checks

| Check | Constitutional anchor | Failure mode |
|-------|----------------------|--------------|
| DB exists | §11.4.93 | `tickets.db` missing |
| DB not gitignored | §11.4.95 | `tickets.db` matched by `.gitignore` |
| Byte-identical round-trip | §11.4.106 | a tracker `.md` differs from a fresh `gen` |
| Schema triggers present | §11.4.33/34 | a closure/reopen guard trigger dropped |

## Tooling

- **Go toolchain** — to build / run the `lava-tickets` binary (pure `modernc.org/sqlite`, no CGO). Present on this host.
- **sqlite3** — for the `sqlite_master` trigger query. Discovered on `PATH`, then common Homebrew + Android `platform-tools` locations. Present on this host. If genuinely absent the trigger check degrades honestly (records a warning) rather than faking a PASS (§6.J).
- **No sudo, no network** (§6.U).

## Falsifiability

`tests/check-lva-tickets/test_check_lva_tickets.sh` builds throwaway DB + tracker fixtures with the real binary + real sqlite3 and asserts the gate FAILS on each deliberate mutation:

- corrupted tracker `.md` → FAIL (§11.4.106); regenerated → PASS again
- dropped `trg_reopen_attribution` → FAIL (§11.4.34)
- dropped `trg_closure_status_typeaware` → FAIL (§11.4.33)
- DB gitignored in a tiny throwaway git repo → FAIL (§11.4.95); un-ignored → PASS again
- DB missing → FAIL (§11.4.93)
- advisory mode (flag + env) → exit 0 even on violation

A gate that passes on any of these mutations is a §6.J bluff.

## Sweep wiring

Registered in `scripts/verify-all-constitution-rules.sh` as an **advisory** gate (`scripts/check-lva-tickets.sh --advisory`) per the project's build-advisory-then-strict-flip convention. The strict-flip is a follow-up once the baseline is confirmed stable across sessions.

## Inheritance

Applies recursively per §6.AD; the §11.4.93/95/106 mandates are universal, the LVA doc set is project-specific.
