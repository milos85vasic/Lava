# `scripts/check-script-docs-sync.sh` — User Guide

**Last verified:** 2026-05-17 (1.2.30-1050 tooling cycle)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) + Lava §6.AD-debt closure (CM-SCRIPT-DOCS-SYNC)

## Overview

Bidirectional drift detector between `scripts/*.sh` and `docs/scripts/*.sh.md`. Each script MUST have a matching doc file and vice versa.

This gate exists to prevent two failure modes:

- **Undocumented scripts:** new gate or helper added without an accompanying user guide. Future operators / agents have no entry-point to understand the script's purpose, usage, exit codes, or anti-bluff falsifiability rehearsal protocol.
- **Stale docs:** scripts removed but their docs left behind. Stale docs mislead readers into believing the script exists.

## Usage

```bash
bash scripts/check-script-docs-sync.sh
```

Or with custom repo root (used by hermetic test):

```bash
LAVA_REPO_ROOT=/path/to/synthetic/repo bash scripts/check-script-docs-sync.sh
```

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Gate clean — 1:1 mapping between scripts and docs |
| 1 | At least one orphan (paths printed to stderr with remediation directive) |

## How drift is detected

1. List `scripts/*.sh` and reduce to basenames (e.g. `check-foo.sh`).
2. List `docs/scripts/*.sh.md` and reduce to basenames + strip `.md` (e.g. `check-foo.sh`).
3. Compare the two sorted lists; anything in one but not the other is reported.

The detector is line-by-line oriented (not git-diff-based), so it catches all current-state drift regardless of which commit introduced it.

## Portability

The script uses `find ... -type f | xargs -n1 basename` instead of GNU `find -printf '%f\n'` so it works on both Linux (GNU find) gate-hosts AND macOS (BSD find) developer workstations.

## §6.J anti-bluff falsifiability rehearsal

1. Remove `docs/scripts/check-no-guessing-vocabulary.sh.md`.
2. Run `bash scripts/check-script-docs-sync.sh` → expect exit 1 with the orphan script listed.
3. Restore the doc; re-run → expect exit 0.

The deliberate-removal rehearsal proves the gate detects real drift in both directions.

## Integration

Wired into `scripts/verify-all-constitution-rules.sh` as a standard gate. Pre-push hook transitively runs this via `scripts/check-constitution.sh` → `verify-all-constitution-rules.sh`.

`Classification:` project-specific (the convention is universal per HelixConstitution §11.4.18; the path layout is Lava-specific).
