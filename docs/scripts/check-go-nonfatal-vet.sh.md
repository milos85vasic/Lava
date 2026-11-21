# `scripts/check-go-nonfatal-vet.sh` — User Guide

**Last verified:** 2026-11-20 (§11.4.32 verify-all sweep, CM-SCRIPT-DOCS-SYNC gate fix)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/check-go-nonfatal-vet.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/check-go-nonfatal-vet.sh — §6.AC Go non-fatal telemetry vet check

Runs go vet with a custom analyzer that checks for error-swallow patterns
lacking observability.RecordNonFatal/RecordWarning calls or // no-telemetry: comments.

This is the mechanical enforcement for §6.AC on the Go side (lava-api-go).
The bash-based check-non-fatal-coverage.sh does the same check but this
provides IDE/editor integration via go vet.

§6.AC Comprehensive Non-Fatal Telemetry Mandate (added 2026-05-14, 28th
§6.L invocation): every catch / error / fallback / unexpected-state path
on every distributable artifact MUST record a non-fatal telemetry event
OR explicitly opt out via `// no-telemetry: <reason>`.

This is a go vet check (placeholder for future custom analyzer). The bash
scanner in check-non-fatal-coverage.sh does the actual §6.AC detection for Go.
When a proper go vet analyzer is implemented, this will be replaced with:
go vet -vettool=$(which lava-go-nonfatal-analyzer) ./...

Scope:
  - .go files under lava-api-go/internal, lava-api-go/cmd

Acceptance: gate runs as advisory until all violations are addressed.
Once the violations queue is drained, this script can be promoted to
pre-push enforcement.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/check-go-nonfatal-vet.sh` — the script itself
- `scripts/check-non-fatal-coverage.sh` — the bash-based scanner (primary enforcement)
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AC (Comprehensive Non-Fatal Telemetry Mandate)