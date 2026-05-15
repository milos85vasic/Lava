# `scripts/check-non-fatal-coverage.sh` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See `scripts/check-non-fatal-coverage.sh` for canonical behavior. This stub exists so the `CM-SCRIPT-DOCS-SYNC` pre-push gate (`.githooks/pre-push` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

```
scripts/check-non-fatal-coverage.sh — §6.AC mechanical enforcement.

Scans Kotlin + Go production code for catch blocks (or fallback paths)
that lack a recordNonFatal/recordWarning call AND lack an explicit
`// no-telemetry: <reason>` opt-out comment. Closes §6.AC-debt.

§6.AC Comprehensive Non-Fatal Telemetry Mandate (added 2026-05-14, 28th
§6.L invocation): every catch / error / fallback / unexpected-state path
on every distributable artifact MUST record a non-fatal telemetry event
OR explicitly opt out via `// no-telemetry: <reason>`.

This is a bash-based scanner (lightweight; pre-Detekt). Detekt rule
remains an open option for §6.AC-debt closure cycle 2.

Scope:
  - .kt files under app/src/main, core/*/src/main, feature/*/src/main
  - .go files under lava-api-go/internal, lava-api-go/cmd

Excluded:
  - test sources (*Test.kt, *_test.go) — tests use catches for assertions, not for telemetry
  - generated code (build/, generated/)
  - constitution submodule
  - HelixConstitution-domain submodules (per §6.AD inheritance — they self-enforce)

Detection:
  - Kotlin: every `} catch (` block whose body lacks `recordNonFatal`,
    `recordWarning`, OR `// no-telemetry:` (within 5 lines of the catch)
  - Go: every `if err != nil {` block, every `defer recover() {` block
    (heuristic; subset of paths but better than nothing)

Acceptance: gate runs as advisory until all violations are addressed.
Once the violations queue is drained, this script can be promoted to
pre-push enforcement. Set LAVA_NONFATAL_STRICT=1 to fail on any
violation; default behavior is WARN-only.
```

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- `scripts/check-non-fatal-coverage.sh` — the script itself
- `docs/helix-constitution-gates.md` — gate inventory
- HelixConstitution `Constitution.md` §11.4.18 (the mandate)
- Lava `CLAUDE.md` §6.AD (HelixConstitution Inheritance)
