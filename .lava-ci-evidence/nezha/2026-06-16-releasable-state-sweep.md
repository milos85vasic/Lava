# Releasable-State Sweep — 2026-06-16

Verification at HEAD `a88467df` (after nezha + tokyotosho commits). Read-only sweep.
Resource limits per §6.T.2: `nice -n 19`, shell/grep only.

## CRITICAL ENVIRONMENT FAULT (anti-bluff disclosure)

Partway through the sweep the host root volume `/` (which backs `/private/tmp`)
reached **100% full / ENOSPC**. The Claude Code harness writes every Bash tool
invocation's stdout/stderr to a `.output` file on that volume *before* the command
runs; once `/` filled, **every subsequent Bash call failed at the harness layer with
`ENOSPC` before executing** — including the commands that would have freed the space.

Consequence: checks 1, 2, 5 completed with real captured exit codes. Check 3
(verify-all) was launched in background and its log was captured up to line 85
(39 of ~46 sub-checks) before the volume filled and froze the writer — its final
summary line and attestation JSON were never written. Check 4 (hermetic suites)
could **not be run to a clean captured result**: the check-constitution background
runner died (exit 1, output truncated to 1 line by ENOSPC) and the pre-push +
re-run attempts were all killed by ENOSPC at the harness layer.

**This is reported honestly, NOT as green-on-skip.** Where a result is unknown, it is
marked UNKNOWN/BLOCKED, not PASS.

## Check 1 — git state

| Item | Result |
|---|---|
| `git log --oneline -5` | a88467df fix(tokyotosho)… / d6f8fdad infra(nezha) boot System / 94519eb0 infra(nezha) register node + fix 2 §6.R / 37a6ff69 Auto-commit / 754a5e37 release(1.3.10/0.2.10) |
| `git status --short` | empty → **clean tree** ✅ |

## Check 2 — scripts/check-constitution.sh

| Check | Exit code | Result |
|---|---|---|
| `bash scripts/check-constitution.sh` | **0** | **PASS** |

No VIOLATION / FAIL / ERROR lines. Final line: "Constitution check passed: 6.D + 6.E +
6.F present … §6.W remote-host boundary clean; §11.4.6 no-guessing vocabulary gate clean."

## Check 3 — scripts/verify-all-constitution-rules.sh (INTERRUPTED by ENOSPC)

Script exists. Mode: strict. Ran 39 of ~46 sub-checks before the volume filled; the
process never wrote its final tally line or the attestation JSON
(`.lava-ci-evidence/verify-all/2026-06-16T18-27-10Z.json` does NOT exist on disk).
**Overall exit code: UNKNOWN (process did not complete cleanly).**

Sub-check results captured from the partial log (real, as printed):

PASS: constitution-doc-parser, no-nested-own-org-submodules, helix-deps-manifest,
challenge-discrimination, challenge-coverage, no-hardcoded-uuid, no-hardcoded-ipv4,
no-hardcoded-hostport, fixture-freshness, inject-helix-inheritance-block-idempotent,
commit-docs-exists, subagent-delegation-audit, workable-items-sync,
hermetic-suite-firebase, hermetic-suite-ci-sh, hermetic-suite-compose-layout,
hermetic-suite-tag-helper, hermetic-suite-vm-signing, hermetic-suite-vm-distro,
hermetic-pre-push-check{4,5,6,7,8,9}_test,
hermetic-check-constitution-test_canonical_root_and_upstreams,
…test_challenge_coverage, …test_clause_6r_present_agents, …test_clause_6r_present,
…test_commit_docs_exists.

**FAIL (exit=1) — captured verbatim from the partial log:**
- `non-fatal-coverage` (§6.AC) — FAIL exit=1
- `markdown-export-sync` (CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC / §11.4.65) — FAIL exit=1
- `gitignore-coverage` (§11.4.30) — FAIL exit=1
- `canonical-root-and-upstreams` (§11.4.35 + §11.4.36) — FAIL exit=1
- `coverage-ledger` (§11.4.25) — FAIL exit=1
- `script-docs-sync` (CM-SCRIPT-DOCS-SYNC / §11.4.18) — FAIL exit=1
- `hermetic-suite-vm-images` — FAIL exit=1
- `hermetic-check-constitution-test_audit_snake_case_references` — FAIL exit=1
- `hermetic-check-constitution-test_clause_6r_inheritance` — FAIL exit=1

NOTE: several of these wrappers are known to run in **advisory mode** per the §6.AF-debt
/ §6.AE-debt / Phase-7 ledger notes in CLAUDE.md (e.g. coverage-ledger, canonical-root,
markdown-export). Whether these 9 FAILs are advisory-WARN (overall sweep still exits 0)
or strict-hard-fail could NOT be determined because the sweep never printed its overall
verdict. **This needs a clean re-run once disk space is restored.**

## Check 4 — hermetic test suites (BLOCKED by ENOSPC)

`tests/check-constitution/` (24 `test_*.sh` + check_constitution_test.sh) and
`tests/pre-push/` (check{4..9}_test.sh) both EXIST. Direct standalone runs could not
produce captured results — the background runner output was truncated to 1 line and all
foreground re-runs failed at the harness layer with ENOSPC.

Partial signal ONLY from the verify-all wrapper (Check 3 above): the 6 pre-push checks
and ~5 of the check-constitution tests it wraps PASSed there, but
`test_audit_snake_case_references` and `test_clause_6r_inheritance` FAILed (exit 1) there,
and `hermetic-suite-vm-images` FAILed. **Standalone per-file PASS/FAIL: UNKNOWN — needs
clean re-run.**

## Check 5 — §6.R scanners

| Scanner | Exit code | Result |
|---|---|---|
| scan-no-hardcoded-ipv4.sh | **0** | **PASS** |
| scan-no-hardcoded-hostport.sh | **0** | **PASS** |
| scan-no-hardcoded-uuid.sh | **0** | **PASS** |
| scan-no-removelast-seqcoll.sh | **0** | **PASS** |

## Tally (confirmed, captured exit codes only)

| Group | PASS | FAIL | UNKNOWN/BLOCKED |
|---|---|---|---|
| git tree clean | 1 | 0 | 0 |
| check-constitution.sh | 1 | 0 | 0 |
| §6.R scanners (4) | 4 | 0 | 0 |
| verify-all overall verdict | 0 | 0 | 1 (interrupted) |
| verify-all sub-checks | 30 (partial) | 9 (partial) | ~7 (never ran) |
| hermetic suites standalone | 0 | 0 | 30 (blocked) |

**Confirmed clean PASS: 6** (git tree + check-constitution + 4 scanners).
**Confirmed sub-check FAILs inside verify-all: 9** (advisory-vs-strict status UNKNOWN).
**Blocked/unknown: verify-all overall verdict + all standalone hermetic suites.**

## Releasability verdict

**NOT CONFIRMED releasable.** The 6 standalone gates that DID run are clean, but the
authoritative verify-all sweep was interrupted by a host disk-full condition before it
could report its overall verdict, and 9 of its sub-checks FAILed (exit 1) — at least some
may be advisory, but that was not verifiable. The hermetic test suites could not be run
standalone at all. A clean re-run is REQUIRED after freeing space on `/`:
`df -h / && <clear caches> && bash scripts/verify-all-constitution-rules.sh`.

## Action item (host)

Root volume `/` is at 100%. Free space before any further CI/tag work; the Local-Only
CI/CD apparatus cannot run while `/private/tmp` is full.

---

## Re-run 2026-06-16 (disk healthy)

Disk pressure cleared. Full sweep re-run with REAL captured exit codes. **A material
finding emerged that overrides the framing of the task: the working HEAD is no longer the
intended releasable commit `6060b237`.**

### HEAD has moved off `6060b237` onto a fixture-polluted state

- Task expected HEAD = `6060b237`. **Actual HEAD = `0b6b65d0` ("fixture").**
- `6060b237` IS still an ancestor of HEAD, but **19 commits sit on top of it on `master`**,
  all synthetic test fixtures. Subjects include: `0b6b65d0 fixture`,
  `2025526c docs: clarified that scripts/never-existed.sh is just an example pattern`,
  `ba9a67fd closure: archived ~~docs/old-removed-design.md~~ as obsolete`,
  `1e897285 claim: see evidence at .../2026-99-99-phantom.json`, plus 15 commits literally
  titled `fixture`. 38 `fixture` commits are reachable from HEAD overall.
- These commits plant deliberate constitutional-violation seeds, e.g.
  `core/data/src/main/kotlin/lava/data/Net.kt` = `const val HOST = "10.10.20.30"` (a real
  §6.R hardcoded-IPv4 violation), sibling `Docs.kt`/`Loop.kt` with doc-range/loopback
  exempt addresses, phantom evidence-file references, fake `~~strikethrough~~` closure
  markers, references to non-existent scripts.
- `git status --short` shows only `m constitution` + `m submodules/containers` (submodule
  pointers dirty); the planted `.kt` fixtures are COMMITTED at HEAD, not working-tree edits.

### Captured results at actual HEAD `0b6b65d0`

| Check | Exit | Result |
|---|---|---|
| 1. git tree (HEAD=`0b6b65d0`, NOT `6060b237`) | — | **FAIL (wrong HEAD)** |
| 2. check-constitution.sh | 0 | PASS (no violation/fatal lines) |
| 3. verify-all-constitution-rules.sh (STRICT, ran to completion) | 1 | **FAIL — 15 PASS / 39 FAIL of 54** |
| 4. hermetic test loop (28 files) | — | **10 PASS / 18 FAIL** |
| 5a. scan-no-hardcoded-ipv4.sh | 1 | **FAIL** (flags planted `Net.kt`) |
| 5b. scan-no-hardcoded-hostport.sh | 1 | **FAIL** (1 planted violation) |
| 5c. scan-no-hardcoded-uuid.sh | 0 | PASS (planted addrs are exempt ranges) |
| 5d. scan-no-removelast-seqcoll.sh | 0 | PASS |

verify-all ran in default `--strict` mode (confirmed: `MODE="strict"` default,
`scripts/verify-all-constitution-rules.sh:28,50`); the 39 failures are STRICT hard-fails,
not advisory WARNs. Attestation written:
`.lava-ci-evidence/verify-all/2026-06-16T19-43-07Z.json`.

### 18 hermetic FAILs (standalone loop)

test_audit_snake_case_references, test_canonical_root_and_upstreams,
test_challenge_coverage, test_clause_6r_inheritance, test_clause_6r_present_agents,
test_clause_6r_present, test_commit_docs_exists, test_covenant_114_propagation,
test_coverage_ledger, test_emulator_runner_tag, test_gitignore_coverage,
test_helix_deps_manifest, test_helixqa_wiring, test_no_guessing_vocabulary,
test_no_hardcoded_hostport, test_no_hardcoded_ipv4, test_script_docs_sync,
test_verify_all_rules — each fails its `test_live_repo_passes` assertion because the live
tree carries planted fixtures.

### VIOLATION text (verbatim, representative)

```
6.R VIOLATION: hardcoded IPv4 literals in tracked source:
core/data/src/main/kotlin/lava/data/Net.kt:1:const val HOST = "10.10.20.30"
  → Move to .env (gitignored) or a JSON config file; read via config layer.
```

```
FAIL test_live_repo_passes: scanner flagged the live tree
```

### Verdict: NOT RELEASABLE

Total at actual HEAD: **clean PASS = check-constitution + uuid-scan + seqcoll-scan (3);
FAIL = verify-all (39/54), hermetic loop (18/28), ipv4-scan, hostport-scan, + wrong-HEAD.**

Specific blockers:
1. **Wrong HEAD.** Working `master` HEAD is `0b6b65d0` (fixture-polluted), not the intended
   releasable `6060b237`. 19 synthetic fixture commits were layered on top of the intended
   commit after the task was framed.
2. **Real §6.R violation committed at HEAD:** `core/data/src/main/kotlin/lava/data/Net.kt`
   hardcoded IPv4 `10.10.20.30`.
3. verify-all STRICT exits 1 (39 hard-failed gates) and 18/28 hermetic tests fail — all
   traced to the planted fixtures correctly tripping the gates (the gates are working as
   designed; the tree is the problem).

**The gates themselves are HEALTHY** — they detect every planted defect. The blocker is
that the verification target moved off the clean commit. To assert releasable state, the
sweep must be re-run after `master` is reset/cleaned back to `6060b237` (or the 19 fixture
commits are dropped). At the clean `6060b237`, check-constitution + scanners were
previously clean (prior section); a clean verify-all run there is still REQUIRED to confirm.
