# `scripts/check-script-docs-sync.sh` — User Guide

**Last verified:** 2026-08-26 (§6.J corpus floor — `0 scripts ↔ 0 docs (1:1)` and a partial corpus now refuse; LVA vacuous-pass sweep F12)
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
| 0 | Gate clean — 1:1 mapping, **and** the corpus actually examined matches what the git index declares. The verdict names both: `gate clean: <N> scripts ↔ <M> docs (1:1) — corpus floor <N>/<M> (git-index-derived) satisfied.` |
| 1 | At least one orphan (paths printed to stderr with remediation directive), **or** a corpus directory was absent, **or** the gate examined an empty or partial corpus (added 2026-08-26 — see below) |

## How drift is detected

1. List `scripts/*.sh` and reduce to basenames (e.g. `check-foo.sh`).
2. List `docs/scripts/*.sh.md` and reduce to basenames + strip `.md` (e.g. `check-foo.sh`).
3. Compare the two sorted lists; anything in one but not the other is reported.

The detector is line-by-line oriented (not git-diff-based), so it catches all current-state drift regardless of which commit introduced it.

## Portability

The script uses `find ... -type f | xargs -n1 basename` instead of GNU `find -printf '%f\n'` so it works on both Linux (GNU find) gate-hosts AND macOS (BSD find) developer workstations.

## 2026-08-26 update — §6.J corpus floor: `0 ↔ 0` is no longer a 1:1 pass

### The defect

Two empty-corpus routes reported success:

| Route | Old output | Old exit |
|---|---|---|
| `scripts/` or `docs/scripts/` absent | `skipping — scripts/ or docs/scripts/ missing` | 0 |
| Both present, both empty | `gate clean: 0 scripts ↔ 0 docs (1:1).` | 0 |

The second is the sharper bluff: **0 == 0 satisfies the 1:1 invariant perfectly**, so
the gate printed a positive verdict having compared nothing. "Nothing was learned"
reported as "nothing failed" is the shape §6.J forbids — the same shape the clause-6.H
credential floor in `scripts/check-constitution.sh` already guards against.

Because this gate is what makes `CM-SCRIPT-DOCS-SYNC` mean anything, an empty-corpus
pass silently retired §11.4.18 enforcement for the whole run.

### What the gate now refuses

Three refusals, all **exit 1**:

1. **A corpus directory is absent** — replaces the old exit-0 skip. States
   `Examined: 0 scripts, 0 docs`, and prints `present` / `MISSING` for each of the two
   directories so the reader can see which one is gone.
2. **Zero scripts compared against zero docs** — runs *before* the clean verdict,
   because that verdict is the thing that must not be reachable over an empty corpus.
   The message says so explicitly: `'0 scripts ↔ 0 docs (1:1)' is vacuously true and
   asserts nothing (§6.J)`.
3. **A partial corpus** — `the gate examined a PARTIAL corpus`, with every declared-
   but-absent path named individually. As the in-source comment puts it: a floor that
   only fires at exactly zero is a floor with one stair; 2 of 60 scripts present would
   otherwise pass as cleanly as 60 of 60.

### Why the expectation is derived from the git index

`Expected` comes from `git ls-files -- scripts` and `git ls-files -- docs/scripts`,
filtered to depth-correct `*.sh` and `*.sh.md` entries — never a literal count. A
hardcoded number goes stale the moment a script is added or removed, and a stale floor
is this same defect wearing a different mask.

That derived count is also what lets each refusal **distinguish its cause**: if the
index declares these paths and the working tree lacks them, this is working-tree drift
(a deletion, a partial checkout) and the remedy is `git checkout -- scripts docs/scripts`;
if the index declares neither, this is not a Lava checkout or `LAVA_REPO_ROOT` points
elsewhere, and the remedy is to set the root correctly. Two situations, two remedies,
never one generic failure.

Two implementation notes for anyone editing this block:

- The counts use `awk`, not `grep -c`. `grep -c` exits 1 on a zero count, which under
  `set -e` inside a pipeline is its own hazard.
- `git ls-files` is wrapped in `{ ...; } || true`. Outside a repository it exits 128,
  and under `set -euo pipefail` that would abort the script with **no message at all** —
  fail-closed, but with a diagnosis so empty it sends the reader nowhere. Degrading to a
  declared count of 0 lets the not-a-checkout branch say what actually happened.

### Note for hermetic-test authors

`LAVA_REPO_ROOT=/path/to/synthetic` still works, but a synthetic tree must now contain
at least one `scripts/*.sh` and one matching `docs/scripts/*.sh.md`. A fixture with two
empty directories used to pass; it now fails on floor 2, correctly, because it asserts
nothing.

### §6.J falsifiability rehearsal of the floor itself

1. `LAVA_REPO_ROOT=$(mktemp -d) bash scripts/check-script-docs-sync.sh` (an empty
   directory) → expect **exit 1**, `a corpus directory is ABSENT`, and the
   not-a-checkout branch of the diagnosis. Before this change: exit 0.
2. `mkdir -p $T/scripts $T/docs/scripts` in that same tree, re-run → expect **exit 1**,
   `the gate compared ZERO scripts against ZERO docs`. Before this change: exit 0 with
   `gate clean: 0 scripts ↔ 0 docs (1:1)`.
3. In the real tree, `mv scripts/ci.sh /tmp/hold` → expect **exit 1**, `examined a
   PARTIAL corpus`, with `scripts/ci.sh` named as missing. Restore → exit 0.

The original orphan-detection rehearsal below still applies unchanged, and exercises the
other direction (real 1:1 drift over a full corpus).

## §6.J anti-bluff falsifiability rehearsal

1. Remove `docs/scripts/check-no-guessing-vocabulary.sh.md`.
2. Run `bash scripts/check-script-docs-sync.sh` → expect exit 1 with the orphan script listed.
3. Restore the doc; re-run → expect exit 0.

The deliberate-removal rehearsal proves the gate detects real drift in both directions.

## Integration

Wired into `scripts/verify-all-constitution-rules.sh` as a standard gate. Pre-push hook transitively runs this via `scripts/check-constitution.sh` → `verify-all-constitution-rules.sh`.

`Classification:` project-specific (the convention is universal per HelixConstitution §11.4.18; the path layout is Lava-specific).
