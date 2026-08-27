# `scripts/check-no-guessing-vocabulary.sh` — User Guide

**Last verified:** 2026-08-26 (§6.J corpus floor — an unresolved or empty scan-path set now refuses instead of reporting "gate clean"; LVA vacuous-pass sweep F14)
**Inheritance:** HelixConstitution §11.4.6 (no-guessing-vocabulary mandate) + Lava §6.AD.6 (extracted gate)

## Overview

Standalone gate that scans tracked status / closure / forensic-anchor files for forbidden guessing vocabulary. Extracted from `scripts/check-constitution.sh`'s embedded block on 2026-05-17 so the gate is independently testable.

Forbidden words: `likely`, `probably`, `maybe`, `might`, `possibly`, `presumably`, `seems to`, `appears to`, `seemingly`, `apparently`, `perhaps`, `supposedly`, `conjectured`.

When a forbidden word appears in a scanned file, the gate fires unless one of these whitelist conditions matches the SAME LINE:

1. The line is prefixed with `UNCONFIRMED:` / `UNKNOWN:` / `PENDING_FORENSICS:` (any case).
2. The line contains a verbatim-quote marker: `forensic anchor` / `verbatim operator|agent|user` / `historical quote`.

These exemptions exist so:
- Hypothesis lines explicitly marked as PENDING evidence pass (tracked-task discipline).
- Forensic anchors that quote prior operator/agent output verbatim aren't flagged for the historical content's vocabulary.

## Why this gate exists

Per HelixConstitution §11.4.6: "Either prove the cause with captured evidence and state it as fact, OR explicitly mark `UNCONFIRMED:` / `UNKNOWN:` / `PENDING_FORENSICS:` with a tracked-task ID." The gate enforces this discipline at pre-push time so closure logs and forensic anchors don't drift into hedged language that would obscure what's actually known vs. assumed.

## Usage

### Default — scan production paths

```bash
bash scripts/check-no-guessing-vocabulary.sh
```

Scans:
- `.lava-ci-evidence/sixth-law-incidents/`
- `.lava-ci-evidence/crashlytics-resolved/`

Across `*.md` + `*.json` files.

### Custom scan paths (hermetic-test mode)

```bash
LAVA_NO_GUESSING_SCAN_PATHS="path1:path2:path3" bash scripts/check-no-guessing-vocabulary.sh
```

Colon-separated list. Used by `tests/check-constitution/test_no_guessing_vocabulary.sh` to scan fixture dirs without touching `.lava-ci-evidence/`.

### Custom repo root (hermetic-test mode)

```bash
LAVA_REPO_ROOT="/path/to/synthetic/repo" bash scripts/check-no-guessing-vocabulary.sh
```

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Gate clean — no forbidden vocabulary found, **and** at least one file was actually read. The verdict line now names the corpus: `§11.4.6 no-guessing vocabulary gate clean: <N> file(s) scanned across <M> director(ies).` |
| 1 | At least one violation (paths printed to stderr), **or** the scan corpus was empty / partial (added 2026-08-26 — see below) |

## 2026-08-26 update — §6.J corpus floor: "gate clean" now requires having read something

### The defect

The scan loop opened with `[[ -d "$p" ]] || continue`, which skipped a missing scan
path **in silence**. A configuration in which no path resolved therefore produced:

```
LAVA_NO_GUESSING_SCAN_PATHS=<nonexistent>  ->  "§11.4.6 no-guessing vocabulary gate clean."  exit 0
```

and so did a path that resolved but held no `*.md` / `*.json`. Both are "nothing was
learned" reported as "nothing failed" — the shape §6.J forbids, and the same shape
`scripts/check-constitution.sh`'s clause-6.H credential floor already guards against.

The silent-skip also made the gate's verdict a function of **checkout state**: a tree
where `.lava-ci-evidence/crashlytics-resolved/` happened not to exist yet scanned one
directory instead of two and said nothing about the difference.

### What the gate now refuses

The loop counts what it resolves and what it reads, and three floors run **before**
any clean verdict can be printed. All exit **1**:

| Condition | Message |
|---|---|
| No configured scan path resolved to a directory | `VIOLATION: NO scan path resolved to a directory.` — every configured path is listed with `(MISSING)` |
| Paths resolved but zero `*.md` / `*.json` files were read | `VIOLATION: the scan read ZERO files.` — resolved directories are listed as `(present, 0 matching files)`, unresolved ones separately |
| Some paths resolved and some did not | `VIOLATION: the scan examined a PARTIAL corpus.` — the missing paths are named |

The first floor distinguishes its cause from the environment: if the paths came from
`LAVA_NO_GUESSING_SCAN_PATHS` this is a **configuration error**, and the remedy is to
correct that variable (colon-separated, relative to the repo root) or unset it to fall
back to the built-in defaults; if they are the **built-in defaults**, then either this
is not a Lava checkout or `LAVA_REPO_ROOT` points elsewhere — and the message prints
the resolved root so the reader can see which.

The partial-corpus floor exists because a floor that fires only at exactly zero is a
floor with one stair. Its remedy line offers both honest options: restore the missing
path, **or** narrow `LAVA_NO_GUESSING_SCAN_PATHS` to exactly the set you intend to
audit — so the gate's claim matches the corpus it actually read.

### Effect on the hermetic test

`tests/check-constitution/test_no_guessing_vocabulary.sh` drives this gate through
`LAVA_NO_GUESSING_SCAN_PATHS` against fixture directories. Fixtures must now point at
directories that exist and contain at least one `*.md` or `*.json` — a fixture path
that was quietly skipped before will now fail the gate, which is the point.

### §6.J falsifiability rehearsal of the floor itself

1. `LAVA_NO_GUESSING_SCAN_PATHS=/nonexistent/a:/nonexistent/b bash
   scripts/check-no-guessing-vocabulary.sh` → expect **exit 1**, `NO scan path
   resolved`, both paths listed `(MISSING)`, and the configuration-error branch of the
   diagnosis. Before this change: exit 0, `gate clean`.
2. `mkdir -p /tmp/empty-scan && LAVA_NO_GUESSING_SCAN_PATHS=/tmp/empty-scan ...` →
   expect **exit 1**, `the scan read ZERO files`.
3. Run with no override in the Lava tree → expect exit 0 and a verdict line naming the
   file and directory counts actually scanned.

## Integration

`scripts/check-constitution.sh` (line ~575) delegates to this script. The pre-push hook invokes `check-constitution.sh` which transitively runs this gate.

## §6.J anti-bluff falsifiability rehearsal

1. Add a tracked file under `.lava-ci-evidence/sixth-law-incidents/` with a line like: `The root cause likely involves a race condition`.
2. Run `bash scripts/check-no-guessing-vocabulary.sh` → expect exit 1 + the file listed in stderr.
3. Prepend `UNCONFIRMED:` to the line. Re-run → expect exit 0.
4. Remove the test file.

The deliberate-break rehearsal proves the gate fires on real violations and that the whitelist works.

## Hermetic test

`tests/check-constitution/test_no_guessing_vocabulary.sh` — 7 fixtures covering:

1. Clean fixture passes
2. Forbidden word without whitelist → reject
3. `UNCONFIRMED:` whitelist passes
4. `PENDING_FORENSICS:` whitelist passes
5. Verbatim-quote exemption (line-scoped) passes
6. Multiple scan paths via colon separator
7. Real-repo sanity check

Run the test:

```bash
bash tests/check-constitution/test_no_guessing_vocabulary.sh
```

## Why intentionally NOT scanned

The gate intentionally does NOT scan `CLAUDE.md` / `AGENTS.md` / `CONSTITUTION.md` because those documents must DESCRIBE the forbidden vocabulary as part of the mandate itself. The gate exists for future status reports / closure logs / commit-template files, not the rule's own text.

`Classification:` project-specific (gate's content list is universal per HelixConstitution §11.4.6 but scan-path defaults are Lava-specific).
