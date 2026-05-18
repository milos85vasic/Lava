#!/usr/bin/env bash
# Tests for scripts/check-subagent-delegation-audit.sh —
# CM-SUBAGENT-DELEGATION-AUDIT gate.
#
# Each fixture creates a synthetic git repo with controlled commit
# bodies + optional audit entries, asserts the scanner returns
# expected exit code + orphan listing.
#
# Falsifiability rehearsal per §6.J clause 2: fixture 3 deliberately
# commits a subagent-trigger phrase AFTER the cutoff WITHOUT an audit
# entry; scanner MUST reject.
#
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-subagent-delegation-audit.sh"

if [[ ! -x "$SCANNER" ]]; then
    echo "FAIL: scanner not executable at $SCANNER"
    exit 1
fi

scaffold() {
    local dir="$1"
    git -C "$dir" init -q -b master 2>/dev/null
    git -C "$dir" config user.email "test@example.com"
    git -C "$dir" config user.name "Test"
    git -C "$dir" config commit.gpgsign false
}

commit_with_date() {
    local dir="$1" date="$2" body="$3"
    GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date" \
        git -C "$dir" commit --allow-empty -q -m "$body"
}

# -----------------------------------------------------------------------------
# Test 1: commit with NO subagent triggers → exit 0
# -----------------------------------------------------------------------------
test_no_trigger_passes() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_with_date "$f" "2026-06-01T12:00:00" "feat: add foo button to bar screen"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "0 subagent-trigger"; then
        echo "PASS test_no_trigger_passes"
    else
        echo "FAIL test_no_trigger_passes: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 2: pre-cutoff trigger is grandfathered → exit 0
# -----------------------------------------------------------------------------
test_pre_cutoff_grandfathered() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_with_date "$f" "2026-05-10T12:00:00" "merge: §6.N bluff-hunt dispatched 5th-wave agent
Cherry-picked from worktree-agent-a7fb4719890c3b3ae"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "1 grandfathered"; then
        echo "PASS test_pre_cutoff_grandfathered"
    else
        echo "FAIL test_pre_cutoff_grandfathered: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 3: post-cutoff trigger WITHOUT audit entry → exit 1 (orphan)
# -----------------------------------------------------------------------------
test_post_cutoff_orphan_rejected() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_with_date "$f" "2026-06-15T12:00:00" "merge: subagent landed Phase 7 waiver backfill"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "subagent landed"; then
        echo "PASS test_post_cutoff_orphan_rejected"
    else
        echo "FAIL test_post_cutoff_orphan_rejected: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 4: post-cutoff trigger WITH matching audit entry → exit 0
# -----------------------------------------------------------------------------
test_post_cutoff_with_audit_passes() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_with_date "$f" "2026-06-15T12:00:00" "merge: subagent landed Phase 7 waiver backfill"
    local sha; sha=$(git -C "$f" rev-parse HEAD); local short="${sha:0:8}"
    mkdir -p "$f/.lava-ci-evidence/subagent-dispatches"
    cat > "$f/.lava-ci-evidence/subagent-dispatches/2026-06-15-phase7-waiver.json" <<EOF
{
  "dispatch_date": "2026-06-15",
  "agent_type": "general-purpose",
  "purpose": "Phase 7 waiver backfill",
  "dispatched_for_commit": "$short",
  "outcome": "merged"
}
EOF
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "0 orphans"; then
        echo "PASS test_post_cutoff_with_audit_passes"
    else
        echo "FAIL test_post_cutoff_with_audit_passes: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 5: gate-name self-reference (CM-SUBAGENT-DELEGATION-AUDIT) → exit 0
# (must NOT trigger on the gate's own name being mentioned)
# -----------------------------------------------------------------------------
test_gate_name_self_reference_not_triggered() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_with_date "$f" "2026-06-15T12:00:00" "docs: clarify CM-SUBAGENT-DELEGATION-AUDIT semantics
Adds a paragraph to the user guide explaining when the gate fires"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -q "0 subagent-trigger"; then
        echo "PASS test_gate_name_self_reference_not_triggered"
    else
        echo "FAIL test_gate_name_self_reference_not_triggered: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 6: worktree-agent-X branch-convention trigger → matched as post-cutoff
# -----------------------------------------------------------------------------
test_worktree_agent_branch_triggers() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_with_date "$f" "2026-06-15T12:00:00" "merge: HelixQA wiring landed
Source branch: worktree-agent-acf4e7fbd8c45f852 (5-commit fast-forward)"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -q "worktree-agent"; then
        echo "PASS test_worktree_agent_branch_triggers"
    else
        echo "FAIL test_worktree_agent_branch_triggers: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 7: backtick-quoted trigger phrase is whitelisted → exit 0
# -----------------------------------------------------------------------------
test_backtick_trigger_skipped() {
    local f; f=$(mktemp -d); scaffold "$f"
    commit_with_date "$f" "2026-06-15T12:00:00" "docs: explain that \`subagent landed\` is the canonical trigger phrase"
    local out rc
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_backtick_trigger_skipped"
    else
        echo "FAIL test_backtick_trigger_skipped: rc=$rc out=$out"; rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# -----------------------------------------------------------------------------
# Test 8: real-repo sanity check at HEAD
# -----------------------------------------------------------------------------
test_real_repo_passes() {
    local out rc
    out=$(bash "$SCANNER" 2>&1); rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_real_repo_passes"
    else
        echo "FAIL test_real_repo_passes: rc=$rc out=$out"; exit 1
    fi
}

test_no_trigger_passes
test_pre_cutoff_grandfathered
test_post_cutoff_orphan_rejected
test_post_cutoff_with_audit_passes
test_gate_name_self_reference_not_triggered
test_worktree_agent_branch_triggers
test_backtick_trigger_skipped
test_real_repo_passes

echo "All 8 subagent-delegation-audit gate tests PASSED"
