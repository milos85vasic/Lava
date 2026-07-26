# `scripts/autonomous-qa/lib-subsets.sh` — User Guide

**Last verified:** 2026-07-26 (LVA-018 script-docs backfill cycle)
**Inheritance:** HelixConstitution §11.4.18 (script docs), §11.4.128 (evidence layout)
**Classification:** project-specific

## Overview

Sourceable library that emits every **non-empty subset** of the matrix
providers as the autonomous-QA provider-mix matrix. Operator decision
(2026-06-30): broaden to ALL backed providers = {client-onboardable} ∩
{API-backed} — **EXHAUSTIVE**, all 31 subsets × backends × queries.

Plan: `docs/superpowers/plans/2026-06-29-autonomous-qa-backend-provider-matrix.md`.

## The provider set

```bash
QA_PROVIDERS=(rutracker nnmclub kinozal archiveorg gutenberg)
```

The ids MUST match `ProviderSpec.forId()` in Challenge70 + the tracker
descriptors. The set is the intersection of:

- **client-onboardable** — providers with a native bundled descriptor under
  `core/tracker/*` (in the production onboarding flow the picker is repopulated
  from the chosen API's catalogue, which REPLACES the bundled set — so a native
  provider is onboardable iff the API also vends it), and
- **API-backed** — the ids the live lava-api-go `/providers` endpoint vends.

The intersection EXCLUDES `rutor` (native but NOT in `/providers`) and
`iptorrents` (Jackett-only, enabled separately via `lib-jackett.sh`).

## Output format

`qa_emit_subsets` prints all 2ⁿ − 1 non-empty subsets, one per line:

```
<csv>|<slug>|<statehash>
```

- `csv` — comma-joined provider ids (passed to Challenge70 `qa_providers`)
- `slug` — dash-joined provider ids (evidence dir name)
- `statehash` — short sha1 of the csv (state-hash per the §11.4.128 layout)

## Usage

```bash
# As a library (what run-matrix.sh does)
source scripts/autonomous-qa/lib-subsets.sh
mapfile -t SUBSET_LINES < <(qa_emit_subsets)

# Directly, to inspect the matrix (31 lines expected)
bash scripts/autonomous-qa/lib-subsets.sh
```

## Exit codes

- `0` — always (pure enumeration; no failure modes)

## Companion files

- `scripts/autonomous-qa/run-matrix.sh` — the consumer
- `app/src/androidTest/kotlin/lava/app/challenges/` — Challenge70 provider matrix test
