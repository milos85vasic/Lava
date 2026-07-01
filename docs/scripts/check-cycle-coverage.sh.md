# `scripts/check-cycle-coverage.sh` — User Guide

**Last verified:** 2026-07-01 (re-verified against the committed §6.AK implementation at HEAD — flags/exit-codes/file-formats below match `scripts/check-cycle-coverage.sh`; prior: 2026-06-26 gate landing)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate) · enforces Lava `CLAUDE.md` §6.AK clause 1 (Cycle-Coverage Device Gate)

## Overview

`check-cycle-coverage.sh` is the mechanical implementation of the
`CM-CYCLE-COVERAGE-INTERSECTION` gate (§6.AK / §6.AK-debt). It refuses a
distribute unless **every CHANGELOG-claimed user-visible fix for the current
version has an EXECUTED + PASSED covering device Challenge** in the matching §6.Z
evidence file for the **same commit SHA**, recorded within the last 24 hours.

It exists to catch the class of bluff that shipped Lava-Android-1.3.12-1076
(commit `627a0d58`): the §6.Z device gate executed ONLY `Challenge00`
(cold-start) while the CHANGELOG claimed fixes to search, provider-selection, and
onboarding — a green gate that proved nothing about the shipped value.

The gate intersects two sets and requires `claim-set ⊆ executed-pass set`:

- **CLAIM-SET** — the covering Challenge declared for each CHANGELOG-claimed
  user-visible fix, read from a per-cycle `cycle-coverage-map` YAML.
- **EXECUTED-PASS SET** — Challenges that ran and PASSED on a gate-eligible device
  in the §6.Z evidence file (verdict `PASS` and `runner != host-direct`).

Any claim with no covering executed+passed Challenge → reject. It parses YAML and
evidence line-by-line with `grep`/`sed`/`bash` (no `yq` dependency).

## Usage

```bash
# Auto-detect everything (version from app/build.gradle.kts, evidence dir from channel,
# map next to the evidence file, HEAD from git, "now" from the system clock):
./scripts/check-cycle-coverage.sh --strict

# Explicit version + channel (the form firebase-distribute.sh Gate 7 uses):
./scripts/check-cycle-coverage.sh --version="1.3.13-1077" --channel=debug --strict

# Fully pinned inputs (the form the hermetic test uses — no git, no clock, no gradle):
./scripts/check-cycle-coverage.sh --version="9.9.9-9999" --channel=debug \
  --evidence-dir="<dir>" --map="<map.yaml>" \
  --head="<sha40>" --now-epoch="<epoch>"
```

### Flags

| Flag | Default | Meaning |
|---|---|---|
| `--version <name-code>` | auto-detect from `app/build.gradle.kts` (`versionName-versionCode`) | Version under test, e.g. `1.3.12-1077`. |
| `--channel <channel>` | `debug` | `debug` or `release`. Selects the evidence dir when `--evidence-dir` is not given. |
| `--evidence-dir <path>` | resolved from channel (see below) | Directory holding `<version>-test-evidence.{md,json}`. |
| `--map <path>` | auto-resolve (see below) | Path to the `cycle-coverage-map` YAML. |
| `--head <sha>` | `git rev-parse HEAD` | Expected working-tree HEAD; the evidence `commit=` must match. Overridable for hermetic tests. |
| `--now-epoch <epoch>` | `date +%s` | "Now" (Unix epoch) for the ≤24h freshness check. Overridable for determinism. |
| `--strict` | on by default (`STRICT=1`) | Reject on any uncovered claim. The flag is accepted but the behavior is the default. |
| `-h`, `--help` | — | Print usage to stderr and exit `0`. |

Environment overrides (read once at startup, lower precedence than the flags):
`LAVA_CYCLE_COVERAGE_HEAD` (initial value for `--head`) and
`LAVA_CYCLE_COVERAGE_NOW_EPOCH` (initial value for `--now-epoch`). An unknown
argument prints usage and exits `3`.

### Path resolution

- **Evidence dir** (when `--evidence-dir` is omitted): channel `release` →
  `.lava-ci-evidence/distribute-changelog/firebase-app-distribution`; any other
  channel → `.lava-ci-evidence/distribute-changelog/firebase-app-distribution-dev`.
- **Evidence file:** the first of `<EVIDENCE_DIR>/<version>-test-evidence.md` then
  `…-test-evidence.json` that exists (`.md` preferred). Missing → exit `2`.
- **Map** (when `--map` is omitted): the first of
  `<EVIDENCE_DIR>/<version>-cycle-coverage-map.yaml` then
  `.lava-ci-evidence/<version>/cycle-coverage-map.yaml`. Missing → exit `2`.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | All claims covered by executed + PASSED, same-SHA, ≤24h device rows. |
| `1` | One or more claims lack a covering executed+PASSED Challenge (missing / `FAIL` / `SKIP` / `host-direct`), OR the evidence timestamp is older than 24h. |
| `2` | Structural: evidence file or map file missing, evidence `version=` mismatch, or evidence `commit=` ≠ working-tree HEAD. |
| `3` | Internal error: unknown CLI argument, version auto-detect failure, HEAD unresolved, missing/malformed evidence `cycle-coverage:` header, unparseable timestamp, or a map declaring zero claims. |

## Input file formats

### 1. cycle-coverage-map (`<vname>-<code>-cycle-coverage-map.yaml`)

A small YAML block. The gate reads it line-by-line and only consumes two keys:

```yaml
version: "1.3.12-1077"
claims:
  - bullet: "Search now returns real results"
    covering_challenge: "Challenge58SearchReturnsResults"
  - bullet: "Search filters follow onboarded providers"
    covering_challenge: "Challenge59SearchUsesOnboardedProviders"
```

- Each `covering_challenge:` value is a **required claim**.
- The preceding `bullet:` (if any) is used only for human-readable FAIL messages.
- A claim whose `covering_challenge` is empty/absent is itself an uncovered claim
  (→ exit `1`) — that is the "CHANGELOG claims a fix but no Challenge was written"
  case the 1076 incident is about.
- A map with **zero** `covering_challenge:` entries is malformed (→ exit `3`).
- Surrounding single/double quotes on values are stripped before matching.

(Other keys such as `covers_issues:` may appear per the spec but are NOT read by
the gate; only `covering_challenge:` values are matched.)

### 2. §6.Z evidence file (`<vname>-<code>-test-evidence.{md,json}`)

The §6.Z device-gate evidence file must carry machine-readable result lines the
gate consumes (the per-cycle requirement §6.AK adds, written by the cycle author
alongside the prose evidence). Two line kinds:

```
cycle-coverage: version=1.3.12-1077 commit=<sha40> channel=debug timestamp=2026-06-26T12:00:00Z
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
```

- Exactly **one** `cycle-coverage:` header line carries `version=` / `commit=` /
  `timestamp=` (ISO-8601, e.g. `2026-06-26T12:00:00Z`). A missing header, or one
  missing any of those three fields, is exit `3`.
- One `challenge:` line per executed Challenge, with its `verdict=`
  (`PASS` | `FAIL` | `SKIP`) and the device `runner=`.
- A claim counts as **covered** only when a `challenge:` line whose `fqn=`
  **contains** the map's `covering_challenge` name has `verdict=PASS` **and**
  `runner != host-direct` (host-direct rows are not gate-eligible per §6.AH).
- Matching is substring: the map carries short names (`Challenge58Search…`),
  the evidence carries full FQNs (`lava.app.challenges.Challenge58Search…Test`).

Structural checks on the header: the evidence `version=` must equal the requested
`--version` (else exit `2`); the evidence `commit=` must match the working-tree
HEAD, tolerant of short-vs-long SHAs by prefix compare (else exit `2`); the
`timestamp=` must be within 24h of `--now-epoch` (else exit `1`).

## How it is wired

- **`scripts/firebase-distribute.sh` — Phase 1 Gate 7 (§6.AK):** after the
  §6.P/§6.AA/§6.Z Phase-1 gates, the distribute script invokes this gate for the
  version+channel being distributed (`--evidence-dir="$CHANGELOG_DIR"`,
  `--strict`) and **refuses the distribute** on a non-zero exit (`1` uncovered
  claim · `2` evidence/map missing/stale/wrong-SHA · `3` internal). It gates both
  the client and the api-app via the app-resolved changelog dir, with no bypass
  flag. See `docs/scripts/firebase-distribute.sh.md` for the Gate 7 detail.
- **`.githooks/pre-push` — Check 10 (per spec §5.4):** the spec defines a pre-push
  Check 10 that runs this gate (`--strict`) when a pushed commit advances a
  `.lava-ci-evidence/distribute-changelog/<channel>/last-version` pointer, so a
  version-advance (signalling "ready to distribute") cannot be pushed without the
  covering device evidence.

## Constitutional binding (§6.AK clause 1)

> No artifact may be distributed unless EVERY CHANGELOG-claimed user-visible fix
> for the current version has an EXECUTED + PASSED covering device Challenge in the
> matching §6.Z evidence file for the SAME commit SHA.

The CHANGELOG's user-visible bullets are the claim-set; the gate's executed-PASS
Challenge set MUST be a superset of the claim-set's covering Challenges. A
`Challenge00`-only (cold-start-only) evidence file NEVER satisfies this gate when
the cycle claims flow/UI fixes — that is exactly the 1076 incident this gate
exists to evict.

## Maintenance

When this script is modified, update this document in the same commit
(`CM-SCRIPT-DOCS-SYNC` / `.githooks/pre-push` Check 9 require it). Per §11.4.18 the
documentation MUST stay in sync with the codebase. The gate's own hermetic test is
`tests/cycle-coverage/test_cycle_coverage.sh` (spec §5.2 case matrix: positive +
six negatives); keep its fixtures in lockstep with any change to the file formats
or exit-code contract above.

## Cross-references

- `scripts/check-cycle-coverage.sh` — the script itself
- `tests/cycle-coverage/test_cycle_coverage.sh` — hermetic falsifiability test
- `docs/superpowers/specs/2026-06-26-ak-cycle-coverage-spec.md` — the §6.AK spec (gate contract §5.1, test matrix §5.2, hook §5.4)
- `docs/scripts/firebase-distribute.sh.md` — Phase 1 Gate 7 wiring
- `docs/helix-constitution-gates.md` — gate inventory
- Lava `CLAUDE.md` §6.AK (Cycle-Coverage Device Gate) + §6.AK-debt
- HelixConstitution `Constitution.md` §11.4.18 (script documentation mandate)
