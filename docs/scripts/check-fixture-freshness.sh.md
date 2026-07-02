# `scripts/check-fixture-freshness.sh` — User Guide

**Last verified:** 2026-07-02 (synthetic-fixture marker exemption added)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/check-fixture-freshness.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/check-fixture-freshness.sh — block stale tracker fixtures.

Per the SP-3a plan Task 5.18 + the developer guide §4 testing
requirements. Fixtures named with a YYYY-MM-DD date in the filename
are considered "fresh" if the date is <30 days old (no warning),
"stale" if 30-60 days old (warn), and "expired" if >60 days old
(block — exit non-zero).

Sixth Law clause 1 ("same surfaces the user touches") implies that
parsers must be tested against HTML that resembles what the user
actually sees today. Trackers change their HTML structure; an old
fixture is a green-test-against-stale-shape bluff.
```

## Synthetic-fixture exemption (added 2026-07-02)

The freshness rule targets **live-capture** fixtures — HTML saved from the real
tracker, whose shape drifts as the tracker changes markup (the "green test
against a stale live shape" bluff). It does **not** apply to **synthetic**
fixtures: author-authored parser-robustness inputs (empty result set, malformed
row, variable-column layout, missing fields, minimal representative rows) whose
shape is deliberately fixed to exercise one parser branch. "Refresh from the
live tracker" is meaningless for those — there is no live page that emits a
deliberately-malformed row.

A fixture is exempt from the freshness check iff it carries an in-file marker
matching `HAND-CRAFTED FIXTURE` or `lava-fixture: synthetic` (case-insensitive).
The convention already existed in-tree (e.g. rutor `search-edge-columns`,
`search-malformed`, `search-missing-fields`, `login/failure-wrong-password` open
with `<!-- HAND-CRAFTED FIXTURE … -->`); the gate now **honors** it rather than
blocking author-declared synthetics. The tiny kinozal/nnmclub unit fixtures were
given the same marker (their live verification runs via the `-PrealTrackers`
`*RealNetworkDownloadTest` crown-jewel runs).

**Anti-bluff (§6.J):** the marker is a human-auditable in-file comment — a
reviewer opens the file and confirms it is genuinely hand-crafted. Live captures
carry **no** marker and stay freshness-checked. Adding the marker to a real live
capture to dodge a refresh would be a §6.J bluff, plainly visible in the diff.
Falsifiability: delete the marker from a synthetic fixture and the gate re-blocks
it.

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/check-fixture-freshness.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
