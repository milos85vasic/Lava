# §6.AE.1 Challenge Coverage Exemptions

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, 31st §6.L invocation)
**Inheritance:** Lava `CLAUDE.md` §6.AE.1 + §6.AE.6 + HelixConstitution §11.4 (anti-bluff)

## Purpose

§6.AE.1 mandates that every feature module under `feature/*/` MUST have at least one Challenge Test. This document is the operator-visible exemption ledger for features that are PRE-WIRED-BUT-NOT-YET-USER-REACHABLE — the source code exists in the module but no caller anywhere in `app/`, `feature/`, or `core/` invokes it. Per §6.J ("tests must guarantee the product works for end users"), writing a Challenge against unreachable code is theatre — the test would target dead code; passing tells the operator nothing useful.

The scanner `scripts/check-challenge-coverage.sh` reads this ledger and treats `// AE-exempt: <feature-name>` lines (anywhere in the file) as explicit coverage exemptions for the named feature.

Each exemption MUST cite:
- WHAT — the feature module name
- WHY — what makes it unreachable today (no caller in any user-facing flow)
- WHEN — the cycle when this status was confirmed
- UNBLOCK — the condition under which the exemption lifts (a real caller is added → Challenge MUST be written before the calling commit lands)

## Active exemptions

### account

// AE-exempt: account
- **WHAT:** `feature/account` (`AccountItem.kt`, `AccountViewModel.kt`, `AccountAction.kt`, `AccountSideEffect.kt`)
- **WHY:** No caller anywhere in `app/`, `feature/`, or `core/` invokes `AccountViewModel`, `AccountItem`, or any other public symbol from this module. Verified via `grep -rln "AccountViewModel\|AccountItem" app feature core 2>/dev/null | grep -v build` returning ONLY the feature/account/ source files themselves (zero external references). The module appears to be pre-wired infrastructure for a future feature (likely user-account management or "My Account" screen) that has not yet been wired into any user-facing navigation.
- **WHEN:** 2026-05-15 (31st §6.L cycle, §6.AE.1 mandate's first-pass coverage scan)
- **UNBLOCK:** any commit that adds a real caller (e.g. a navigation entry that opens an Account screen, a menu item that triggers an account action) MUST land a Challenge Test in the same commit. The exemption MUST be removed from this file in that same commit. Pre-push reviewer enforcement until the §6.AE strict-mode flip wires this as a mechanical gate.

## Removed exemptions (audit trail)

(none yet)

## Scanner integration

`scripts/check-challenge-coverage.sh` recognizes `// AE-exempt: <name>` markers in this file (or in any file under `.lava-ci-evidence/`). Per the §6.AE.1 mandate, exemption is the LAST RESORT — direct Challenge coverage is the default. An exempted feature that becomes user-reachable WITHOUT the exemption being lifted is a §6.J spirit violation.

## Cross-references

- Lava `CLAUDE.md` §6.AE (Comprehensive Challenge Coverage + Container/QEMU Matrix Mandate)
- `scripts/check-challenge-coverage.sh` (the scanner that reads this ledger)
- `docs/scripts/check-challenge-coverage.sh.md` (scanner user guide)
