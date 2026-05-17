# `scripts/check-no-guessing-vocabulary.sh` — User Guide

**Last verified:** 2026-05-17 (1.2.30-1050 tooling cycle)
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
| 0 | Gate clean (no forbidden vocabulary found) |
| 1 | At least one violation (paths printed to stderr) |

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
