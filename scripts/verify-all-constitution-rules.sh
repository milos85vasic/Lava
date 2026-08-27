#!/usr/bin/env bash
# scripts/verify-all-constitution-rules.sh — §11.4.32 enforcement engine.
#
# Per HelixConstitution §11.4.32 (Post-Constitution-Pull Validation
# Mandate, 2026-05-15): "Whenever a project's constitution submodule is
# fetched + pulled with any content change, the project MUST run a
# full-project + recursive-submodule validation sweep BEFORE the new
# constitution HEAD is treated as canonical for any other work."
#
# This script IS that sweep. It walks every constitution rule with a
# programmatic gate, runs each gate against the current tree, captures
# the result, emits a structured JSON attestation, and exits non-zero
# if ANY gate fails.
#
# §11.4.32 itself: "sweep's own meta-test (paired mutation §1.1) plants
# a known violation of each enforced gate and asserts sweep reports
# FAIL for the planted gate. A sweep that exits PASS without running
# every implementable gate is a §11.4.32 violation." The hermetic test
# at tests/check-constitution/test_verify_all_rules.sh is that
# meta-test.
#
# §11.4.32 is the **enforcement engine** for every other §11.4.x and
# CONST-NNN rule — without it, new rules cascade as anchors but never
# get enforced in the codebase.
#
# Usage:
#   bash scripts/verify-all-constitution-rules.sh
#   bash scripts/verify-all-constitution-rules.sh --strict     # exit 1 on any failure (default)
#   bash scripts/verify-all-constitution-rules.sh --advisory   # exit 0 even on failures (advisory mode)
#   bash scripts/verify-all-constitution-rules.sh --json-only  # emit JSON only (no stdout summary)
#
# Output:
#   stdout: per-gate summary + final verdict
#   .lava-ci-evidence/verify-all/<UTC-timestamp>.json: structured attestation
#
# Inheritance: HelixConstitution §11.4.32 (the mandate itself); §11.4.18
# (script documentation); §6.J/§6.L (anti-bluff).
# Classification: project-specific (the gate list is Lava-specific; the
# sweep-with-meta-test discipline is universal per HelixConstitution).

set -uo pipefail   # NOT -e — we want to keep running gates even when one fails

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

MODE="strict"
JSON_ONLY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)    MODE="strict"; shift ;;
        --advisory)  MODE="advisory"; shift ;;
        --json-only) JSON_ONLY=1; shift ;;
        -h|--help)   sed -n '3,30p' "$0"; exit 0 ;;
        *)           echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
    esac
done

EVIDENCE_DIR="$REPO_ROOT/.lava-ci-evidence/verify-all"
mkdir -p "$EVIDENCE_DIR"
TS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
ATTESTATION="$EVIDENCE_DIR/$TS.json"

declare -a GATE_NAMES=()
declare -a GATE_RESULTS=()
declare -a GATE_DURATIONS=()
declare -a GATE_REFERENCES=()

# §6.J anti-bluff (2026-08-23, §6.N.2 gate-shaping bluff hunt): $cmd is
# evaluated inside a SUBSHELL. It used to be evaluated in the sweep's own
# shell, where a gate command that reached `exit` terminated the whole sweep
# instead of failing one gate — every later gate silently skipped, the
# already-recorded FAIL rows discarded, no attestation written, and (for
# `exit 0`) the sweep itself exiting 0. §11.4.32: "A sweep that exits PASS
# without running every implementable gate is a §11.4.32 violation." The
# subshell also stops a gate from mutating GATE_RESULTS/GATE_NAMES and
# rewriting the verdict. Covered by
# tests/check-constitution/test_verify_all_gate_isolation.sh.
run_gate() {
    local gate_name=$1
    local rule_ref=$2
    local cmd=$3

    [[ "$JSON_ONLY" == "1" ]] || echo "==> $gate_name ($rule_ref)"
    local start_ts end_ts duration rc
    start_ts=$(date +%s)
    if ( eval "$cmd" ) >/dev/null 2>&1; then
        rc=0
    else
        rc=$?
    fi
    end_ts=$(date +%s)
    duration=$((end_ts - start_ts))

    GATE_NAMES+=("$gate_name")
    GATE_REFERENCES+=("$rule_ref")
    GATE_DURATIONS+=("$duration")
    if [[ "$rc" -eq 0 ]]; then
        GATE_RESULTS+=("PASS")
        [[ "$JSON_ONLY" == "1" ]] || echo "    ✓ PASS (${duration}s)"
    else
        GATE_RESULTS+=("FAIL")
        [[ "$JSON_ONLY" == "1" ]] || echo "    ✗ FAIL (exit=$rc, ${duration}s)"
    fi
}

[[ "$JSON_ONLY" == "1" ]] || {
    echo "===================================================="
    echo "§11.4.32 verify-all-constitution-rules sweep"
    echo "  mode: $MODE"
    echo "  attestation: $ATTESTATION"
    echo "===================================================="
}

# -----------------------------------------------------------------------------
# Gate registry — every constitution rule with a programmatic gate.
# Format: run_gate "<gate-name>" "<rule-ref>" "<command>"
# -----------------------------------------------------------------------------

# §6.A through §6.X + §6.AD inheritance + §6.W boundary + §11.4.6 no-guessing
run_gate "constitution-doc-parser" "§6.D/§6.E/§6.F/§6.AD/§6.W/§11.4.6/§6.AE" \
    "bash scripts/check-constitution.sh"

# §6.AC non-fatal coverage (STRICT default after queue drained)
run_gate "non-fatal-coverage" "§6.AC + HelixConstitution telemetry discipline" \
    "bash scripts/check-non-fatal-coverage.sh"

# §11.4.65 / CONST-066 Universal Markdown Export Sync (CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC).
# Backfill: 126 in-scope .md -> 252 siblings, 0 failures (CHANGELOG.md YAML-metadata
# bug fixed via --from gfm). --check-only reports 0 problems -> strict.
run_gate "markdown-export-sync" "CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC / HelixConstitution §11.4.65" \
    "LAVA_MARKDOWN_EXPORT_STRICT=strict bash scripts/check-markdown-export-sync.sh"

# §11.4.30 .gitignore + No-Versioned-Build-Artifacts (Phase 2)
run_gate "gitignore-coverage" "HelixConstitution §11.4.30" \
    "bash scripts/check-gitignore-coverage.sh"

# §11.4.28 Submodules-As-Equal-Codebase / no nested own-org chains (Phase 5)
# STRICT-flipped 2026-05-15: Phase 5-debt closed via github cascade merge
# (CONST-051(C) flat-layout enforcement removed Challenges/.gitmodules).
# Scanner now reports 0 violations on real tree.
run_gate "no-nested-own-org-submodules" "HelixConstitution §11.4.28" \
    "bash scripts/check-no-nested-own-org-submodules.sh --strict"

# §11.4.35 Canonical-Root Inheritance Clarity + §11.4.36 install_upstreams (Phase 8)
# STRICT-flipped 2026-05-15: Phase 8-debt closed (10 install_upstreams
# scripts landed via Phase 8-debt batch). Scanner now reports 16/16
# install_upstreams present + all §11.4.35 sub-checks passing.
run_gate "canonical-root-and-upstreams" "HelixConstitution §11.4.35 + §11.4.36" \
    "bash scripts/check-canonical-root-and-upstreams.sh --strict"

# §11.4.31 Submodule-Dependency-Manifest (Phase 3)
# STRICT-flipped 2026-05-15: Phase 3-debt closed (16/16 per-submodule
# helix-deps.yaml manifests landed via Phase 3-debt batch). Scanner now
# reports 16/16 manifests present + parent helix-deps.yaml well-formed.
run_gate "helix-deps-manifest" "HelixConstitution §11.4.31" \
    "bash scripts/check-helix-deps-manifest.sh --strict"

# §11.4.25 Full-Automation-Coverage Ledger (Phase 7)
# STRICT-flipped 2026-05-16 after Phase 7 waiver backfill landed
# (commit 20b3fd36 → 48 covered / 10 partial / 0 gap; the 10 partial
# rows are honest gaps for structural/glue modules without dedicated
# unit tests). Verifier hard-asserts freshness + row-coverage + schema
# integrity; the 10 partial rows do NOT trigger the verifier (it
# checks ledger health, not row-coverage thresholds).
run_gate "coverage-ledger" "HelixConstitution §11.4.25" \
    "bash scripts/check-coverage-ledger.sh --strict"

# §6.AB Challenge discrimination (Layer 1 marker + Layer 2 body)
run_gate "challenge-discrimination" "§6.AB Anti-Bluff Test-Suite Reinforcement" \
    "bash scripts/check-challenge-discrimination.sh"

# §6.AE per-feature Challenge coverage (STRICT default)
run_gate "challenge-coverage" "§6.AE Comprehensive Challenge Coverage Mandate" \
    "bash scripts/check-challenge-coverage.sh"

# §6.R no-hardcoded-{uuid,ipv4,host:port}
run_gate "no-hardcoded-uuid" "§6.R No-Hardcoding Mandate" \
    "bash scripts/scan-no-hardcoded-uuid.sh"
run_gate "no-hardcoded-ipv4" "§6.R No-Hardcoding Mandate" \
    "bash scripts/scan-no-hardcoded-ipv4.sh"
run_gate "no-hardcoded-hostport" "§6.R No-Hardcoding Mandate" \
    "bash scripts/scan-no-hardcoded-hostport.sh"

# §6.U + §6.H credential / sudo-su patterns (already in check-constitution.sh
# but invoke the dedicated checks too; redundancy is intentional — multiple
# enforcement points per §11.4.32 design)
run_gate "fixture-freshness" "§6.D Behavioral Coverage / fixture-staleness" \
    "bash scripts/check-fixture-freshness.sh"

# §6.AD inheritance pointer-block presence (subset of constitution-doc-parser
# but exposed as a separate gate so a partial pass is detectable)
# Wraps the inheritance-pointer-block-presence check from check-constitution.sh
run_gate "inject-helix-inheritance-block-idempotent" "§6.AD-debt item 1 — inheritance propagation" \
    "bash scripts/inject-helix-inheritance-block.sh | tail -1 | grep -q 'added=0'"

run_gate "script-docs-sync" "CM-SCRIPT-DOCS-SYNC / HelixConstitution §11.4.18" \
    "bash scripts/check-script-docs-sync.sh"

run_gate "commit-docs-exists" "CM-COMMIT-DOCS-EXISTS / HelixConstitution §11.4.x (last 5 commits)" \
    "LAVA_COMMIT_RANGE='HEAD~5..HEAD' bash scripts/check-commit-docs-exists.sh"

run_gate "subagent-delegation-audit" "CM-SUBAGENT-DELEGATION-AUDIT / HelixConstitution §11.4.x (last 5 commits)" \
    "LAVA_COMMIT_RANGE='HEAD~5..HEAD' bash scripts/check-subagent-delegation-audit.sh"

# CM-WORKABLE-ITEMS-SYNC §11.4.93/95 — canonical workable-items binary
# (constitution/scripts/workable-items/, keyed LVA-N). Validates the SQLite SSoT
# at docs/workable_items.db, asserts DB↔Markdown (docs/Issues.md/docs/Fixed.md)
# in-sync, and asserts the DB is git-tracked (§11.4.95). Replaced the retired
# CM-LVA-TICKETS-SYNC gate (bespoke tools/lava-tickets/, migrated 2026-05-31 —
# docs/tickets/MIGRATION-TO-CANONICAL.md). Strict: the DB+docs ship in-sync.
run_gate "workable-items-sync" "CM-WORKABLE-ITEMS-SYNC / HelixConstitution §11.4.93/95" \
    "bash scripts/check-workable-items.sh"

# §11.4.65 Universal Markdown Export Sync (CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC).
# Every non-source-code .md MUST have synced .html + .pdf siblings. The gate
# (scripts/check-markdown-export-sync.sh) greps in-scope .md and fails on any
# missing/stale sibling. §6.AF-debt §11.4.65 item closed 2026-05-31; backfill
# landed 126 in-scope docs, 0 problems at landing.

# Hermetic test suites (each suite's own paired-mutation contracts).
#
# §6.J anti-bluff (2026-08-22, §6.N.2 gate-shaping bluff hunt): a suite whose
# run_all.sh is deleted, renamed, or merely loses its exec bit used to be
# SKIPPED SILENTLY — the gate vanished from the registry and the sweep still
# reported a clean "N PASS / 0 FAIL" with no signal that N had shrunk. Losing a
# test suite is exactly the drift this sweep exists to catch, so the missing
# suite is now registered as a FAIL row instead of disappearing.
# The suite list is an ARRAY so the registry floor below can derive its
# expectation from it rather than carrying a hardcoded number that would go
# stale the moment a suite is added or removed (LVA vacuous-pass sweep F15).
declare -a HERMETIC_SUITES=(
    tests/firebase tests/ci-sh tests/compose-layout tests/tag-helper
    tests/vm-images tests/vm-signing tests/vm-distro
)
for suite in "${HERMETIC_SUITES[@]}"; do
    if [[ -x "$suite/run_all.sh" ]]; then
        run_gate "hermetic-suite-$(basename $suite)" "§11.4 anti-bluff hermetic test suite" \
            "bash $suite/run_all.sh"
    else
        run_gate "hermetic-suite-$(basename $suite)" "§11.4 anti-bluff hermetic test suite" \
            "echo 'MISSING or non-executable: $suite/run_all.sh' >&2; false"
    fi
done

# Flat-layout hermetic suites. Same §6.J reasoning as above: a glob that matches
# nothing used to register nothing, so an emptied tests/pre-push/ read as
# "no pre-push tests failed" instead of "no pre-push tests ran".
prepush_registered=0
for t in tests/pre-push/check*_test.sh; do
    [[ -f "$t" ]] || continue
    bn=$(basename "$t" .sh)
    run_gate "hermetic-pre-push-$bn" "§11.4 anti-bluff hermetic pre-push test" \
        "bash $t"
    prepush_registered=$((prepush_registered + 1))
done
if [[ "$prepush_registered" -eq 0 ]]; then
    run_gate "hermetic-pre-push-suite-present" "§11.4 anti-bluff hermetic pre-push test" \
        "echo 'NO tests/pre-push/check*_test.sh matched — the pre-push hermetic suite is empty' >&2; false"
fi

cc_registered=0
for t in tests/check-constitution/test_*.sh tests/check-constitution/check_constitution_test.sh; do
    [[ -f "$t" ]] || continue
    # SKIP test_verify_all_rules.sh inside the sweep — it calls the sweep
    # recursively, which would create infinite recursion. The meta-test
    # is invoked separately (by the operator OR by ci.sh's tests/check-
    # constitution flat-layout walker).
    [[ "$(basename "$t")" == "test_verify_all_rules.sh" ]] && continue
    bn=$(basename "$t" .sh)
    run_gate "hermetic-check-constitution-$bn" "§11.4 anti-bluff hermetic constitution test" \
        "bash $t"
    cc_registered=$((cc_registered + 1))
done
if [[ "$cc_registered" -eq 0 ]]; then
    run_gate "hermetic-check-constitution-suite-present" "§11.4 anti-bluff hermetic constitution test" \
        "echo 'NO tests/check-constitution/ test files matched — the suite is empty' >&2; false"
fi

# -----------------------------------------------------------------------------
# Aggregate + emit attestation JSON.
# -----------------------------------------------------------------------------

total_gates=${#GATE_NAMES[@]}

# §6.J anti-bluff floor (added 2026-08-22, §6.N.2 gate-shaping bluff hunt).
# Every gate above is registered conditionally: the hermetic-suite loops skip
# a suite whose run_all.sh is absent or has lost its exec bit, and the two
# flat-layout `for t in <glob>` loops register nothing when the glob matches
# no file. If enough of those conditions go false at once the sweep reaches
# this point with an EMPTY gate array and then reports
# `0 PASS / 0 FAIL (of 0 total)`, writes `"all_passed": true`, and exits 0 in
# STRICT mode — a clean verdict from a sweep that examined nothing. §11.4.32
# calls exactly that out: "A sweep that exits PASS without running every
# implementable gate is a §11.4.32 violation." A healthy tree registers 50+
# gates, so this floor cannot fire on a real run.
if [[ "$total_gates" -eq 0 ]]; then
    echo "§11.4.32 VIOLATION: the sweep registered ZERO gates." >&2
    echo "  → A PASS verdict from an empty gate set asserts nothing." >&2
    echo "  → Check that the hermetic suites under tests/ are present and" >&2
    echo "    that their run_all.sh files are still executable." >&2
    exit 2
fi

# -----------------------------------------------------------------------------
# §6.J DERIVED registry floor (added 2026-08-26, LVA vacuous-pass sweep F15).
#
# The `-eq 0` floor above is a floor with one stair. 32 of the 58 gates a healthy
# tree registers come from globs guarded only by that zero-check, so a registry
# shrunken from 58 to 32 — or to 1 — reaches the clean-verdict path untouched:
#
#   REPRO (verbatim excerpt of :277-296, registry forced to 32) -> exit 0
#   REPRO (same excerpt, registry forced to 1)                  -> exit 0
#
# and the sweep then writes "all_passed": true over a registry that lost 26
# gates. The comment above even states the expectation ("A healthy tree
# registers 50+ gates") — documented, not enforced.
#
# The expectation is DERIVED, never hardcoded, because a hardcoded number goes
# stale the moment a gate is added or removed and a stale floor is this same
# defect wearing a different mask. Each contributor is derived from its own
# source of truth:
#
#   fixed run_gate call sites  <- this script itself (column-0 `run_gate "`)
#   hermetic suite gates       <- ${#HERMETIC_SUITES[@]} (the array above)
#   tests/pre-push gates       <- the git index (the repo's own declaration)
#   tests/check-constitution   <- the git index, minus the deliberately skipped
#                                 test_verify_all_rules.sh (recursion guard)
#
# The two glob loops each register a stand-in FAIL row when they match nothing,
# so their contribution is at least 1 — hence the `(( x < 1 ))` clamps.
#
# `|| true` inside the braces: `git ls-files` exits 128 outside a repository and
# under `set -e` that would abort here with no message at all. Degrading to 0
# lets the clamps hold the floor at its structural minimum instead.
_verify_all_self="${BASH_SOURCE[0]}"
_expected_fixed="$(awk '/^run_gate "/{n++} END{print n+0}' "$_verify_all_self" 2>/dev/null || echo 0)"
# The self-derivation must not be allowed to fail quietly: an unreadable
# ${BASH_SOURCE[0]} would return 0 and LOWER the floor by 19, which is the very
# defect this floor exists to close, merely relocated into the floor itself.
if [[ "$_expected_fixed" -eq 0 ]]; then
    echo "§11.4.32 VIOLATION: could not derive the fixed-gate expectation from this script." >&2
    echo "  → Examined: '${_verify_all_self}' — found 0 column-0 'run_gate \"' call sites." >&2
    echo "  → Expected: a non-zero count; this script has always carried unconditional" >&2
    echo "    run_gate call sites, so 0 means the file could not be read rather than that" >&2
    echo "    the gates are gone." >&2
    echo "  → Cause distinguished: not a shrunken registry — a broken derivation. Falling" >&2
    echo "    through would silently lower the registry floor by exactly those gates." >&2
    echo "  → Do: invoke this script by path (bash scripts/verify-all-constitution-rules.sh)" >&2
    echo "    rather than via stdin, and re-run." >&2
    exit 2
fi
_expected_suites=${#HERMETIC_SUITES[@]}
_expected_prepush="$(
  { git ls-files -- 'tests/pre-push/check*_test.sh' 2>/dev/null || true; } |
  awk 'END{print NR+0}'
)"
_expected_cc="$(
  { git ls-files -- 'tests/check-constitution/test_*.sh' \
                    'tests/check-constitution/check_constitution_test.sh' 2>/dev/null || true; } |
  awk '!/\/test_verify_all_rules\.sh$/{n++} END{print n+0}'
)"
(( _expected_prepush < 1 )) && _expected_prepush=1
(( _expected_cc < 1 )) && _expected_cc=1
_expected_gates=$(( _expected_fixed + _expected_suites + _expected_prepush + _expected_cc ))

if [[ "$total_gates" -lt "$_expected_gates" ]]; then
    echo "§11.4.32 VIOLATION: the sweep registered a SHRUNKEN gate registry." >&2
    echo "  → Examined: ${total_gates} gate(s)" >&2
    echo "  → Expected: ${_expected_gates}, derived (never hardcoded) as:" >&2
    printf '        %2d  fixed run_gate call sites   (column-0 %s in this script)\n' \
        "$_expected_fixed" "'run_gate \"'" >&2
    printf '        %2d  hermetic suite gates        (${#HERMETIC_SUITES[@]})\n' "$_expected_suites" >&2
    printf '        %2d  tests/pre-push gates        (git ls-files, min 1)\n' "$_expected_prepush" >&2
    printf '        %2d  tests/check-constitution    (git ls-files minus the recursion-guard skip, min 1)\n' "$_expected_cc" >&2
    echo "  → Missing: $(( _expected_gates - total_gates )) gate(s). Registered names:" >&2
    printf '        %s\n' "${GATE_NAMES[@]}" >&2
    echo "  → Cause distinguished:" >&2
    if [[ "$(ls -d tests/pre-push/check*_test.sh 2>/dev/null | wc -l)" -eq 0 ]] ||
       [[ "$(ls -d tests/check-constitution/test_*.sh 2>/dev/null | wc -l)" -eq 0 ]]; then
        echo "      at least one flat-layout glob matched NOTHING in the working tree while" >&2
        echo "      the git index still declares those tests — working-tree drift (a" >&2
        echo "      deletion, a bad checkout, or a partial clone), not a shrunken suite." >&2
        echo "      Do: git checkout -- tests/pre-push tests/check-constitution" >&2
    else
        echo "      the globs resolve, so the shortfall is in the fixed call sites or the" >&2
        echo "      hermetic suites — a suite whose run_all.sh lost its exec bit still" >&2
        echo "      registers a FAIL row, so a MISSING row means a gate was removed from" >&2
        echo "      this script without the expectation moving with it." >&2
        echo "      Do: compare the names above against the last attestation under" >&2
        echo "      .lava-ci-evidence/verify-all/ and restore the missing gate." >&2
    fi
    echo "  → A PASS over ${total_gates} of ${_expected_gates} gates asserts nothing about the other" >&2
    echo "    $(( _expected_gates - total_gates )); §11.4.32: \"A sweep that exits PASS without running" >&2
    echo "    every implementable gate is a §11.4.32 violation.\"" >&2
    exit 2
fi
# END-OF-BLOCK §6.J DERIVED registry floor (regression-harness sentinel)

pass_count=0
fail_count=0
for r in "${GATE_RESULTS[@]}"; do
    if [[ "$r" == "PASS" ]]; then
        pass_count=$((pass_count + 1))
    else
        fail_count=$((fail_count + 1))
    fi
done

# JSON construction (manual — POSIX-portable)
{
    echo "{"
    echo "  \"sweep_timestamp\": \"$TS\","
    echo "  \"sweep_mode\": \"$MODE\","
    echo "  \"sweep_constitution_pin\": \"$(cd constitution && git rev-parse HEAD 2>/dev/null || echo unknown)\","
    echo "  \"total_gates\": $total_gates,"
    echo "  \"pass_count\": $pass_count,"
    echo "  \"fail_count\": $fail_count,"
    echo "  \"all_passed\": $([[ "$fail_count" -eq 0 ]] && echo true || echo false),"
    echo "  \"gates\": ["
    for i in "${!GATE_NAMES[@]}"; do
        comma=$([[ $i -lt $((total_gates - 1)) ]] && echo "," || echo "")
        printf '    {"name": "%s", "rule_ref": "%s", "result": "%s", "duration_seconds": %s}%s\n' \
            "${GATE_NAMES[$i]}" "${GATE_REFERENCES[$i]}" "${GATE_RESULTS[$i]}" "${GATE_DURATIONS[$i]}" "$comma"
    done
    echo "  ]"
    echo "}"
} > "$ATTESTATION"

[[ "$JSON_ONLY" == "1" ]] || {
    echo ""
    echo "===================================================="
    echo "Sweep complete: $pass_count PASS / $fail_count FAIL (of $total_gates total)"
    echo "Attestation: $ATTESTATION"
    echo "===================================================="
}

if [[ "$fail_count" -gt 0 ]]; then
    if [[ "$MODE" == "strict" ]]; then
        [[ "$JSON_ONLY" == "1" ]] || echo "STRICT mode — exiting 1 due to $fail_count failed gate(s)." >&2
        exit 1
    else
        [[ "$JSON_ONLY" == "1" ]] || echo "ADVISORY mode — exit 0 despite $fail_count failed gate(s)."
    fi
fi
exit 0
