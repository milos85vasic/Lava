#!/usr/bin/env bash
# scripts/check-subagent-delegation-audit.sh — CM-SUBAGENT-DELEGATION-AUDIT
# gate per HelixConstitution §11.4.x + Lava §6.AD-debt closure.
#
# When a commit message describes a SUBAGENT DISPATCH ACTIVITY (an
# agent-driven sub-task that landed work into this repo), the gate
# verifies a matching audit-trail entry exists under:
#
#   .lava-ci-evidence/subagent-dispatches/<YYYY-MM-DD>-<slug>.json
#
# Schema (minimal — see docs/scripts/check-subagent-delegation-audit.sh.md
# for the full spec):
#
#   {
#     "dispatch_date":           "YYYY-MM-DD",
#     "agent_type":              "general-purpose | Explore | ...",
#     "purpose":                 "one-line summary",
#     "dispatched_for_commit":   "SHA OR short-SHA-prefix",
#     "outcome":                 "merged | discarded | failed | pending",
#     "isolation":               "worktree | inline",   (optional)
#     "worktree_branch":         "string",              (optional)
#     "findings_count":          <int>,                 (optional)
#     "follow_ups":              [<refs>]               (optional)
#   }
#
# Trigger heuristics (intentionally narrow to avoid false-positives on
# gate-name references like "CM-SUBAGENT-DELEGATION-AUDIT"):
#
#   1. "subagent <action>" where action is: landed | dispatched | merged |
#      worktree | driven | discovered | surfaced | closed
#   2. "<action> <noun>? subagent" — dispatched X subagent / via subagent
#   3. "worktree-agent-[a-z0-9]{8,}" — Claude Code branch convention
#   4. "agent-driven" / "agent worktree" — generic subagent activity
#
# Whitelist (intentional skips to avoid false-positives):
#   - Lines starting with '> ' (markdown blockquote)
#   - Lines indented 2+ spaces (Bluff-Audit body, quoted output)
#   - Lines containing 'CM-SUBAGENT-DELEGATION-AUDIT' (gate-name self-ref)
#   - Backtick spans
#   - ~~strikethrough~~ spans
#
# Effective-from cutoff:
#   Default LAVA_SUBAGENT_AUDIT_CUTOFF=2026-05-19. Commits BEFORE this
#   date are grandfathered (no audit entry required). The cutoff exists
#   because past commits referenced subagents without an audit-trail
#   convention — backfilling 30+ entries is not the right closure
#   posture. New commits (cutoff onward) MUST include the audit entry.
#
# Usage:
#   bash scripts/check-subagent-delegation-audit.sh                   # HEAD
#   bash scripts/check-subagent-delegation-audit.sh HEAD~5..HEAD       # range
#   bash scripts/check-subagent-delegation-audit.sh '@{u}..HEAD'       # unpushed
#   LAVA_COMMIT_RANGE='HEAD~3..HEAD' bash scripts/check-subagent-delegation-audit.sh
#   LAVA_REPO_ROOT=/path bash scripts/check-subagent-delegation-audit.sh
#   LAVA_SUBAGENT_AUDIT_CUTOFF=2026-06-01 bash scripts/...
#
# Exit codes:
#   0 — no triggering commits found, OR every triggering commit has a
#       matching audit entry
#   1 — at least one triggering commit lacks an audit entry (printed
#       to stderr with the commit SHA + the trigger phrase)
#
# Classification: project-specific (the convention is universal per
# HelixConstitution; the path-prefix + cutoff date is Lava-specific).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

range="${1:-${LAVA_COMMIT_RANGE:-HEAD~0..HEAD}}"
cutoff="${LAVA_SUBAGENT_AUDIT_CUTOFF:-2026-05-19}"

# Normalize: HEAD~0..HEAD → just HEAD (single commit)
if [[ "$range" == "HEAD~0..HEAD" ]] || [[ "$range" == "HEAD" ]]; then
    mapfile -t commits < <(git rev-parse HEAD 2>/dev/null)
else
    mapfile -t commits < <(git rev-list "$range" 2>/dev/null)
fi

if [[ ${#commits[@]} -eq 0 ]] || [[ -z "${commits[0]}" ]]; then
    echo "CM-SUBAGENT-DELEGATION-AUDIT: no commits in range '$range' — skipping"
    exit 0
fi

audit_dir="$REPO_ROOT/.lava-ci-evidence/subagent-dispatches"
mkdir -p "$audit_dir" 2>/dev/null

orphan_count=0
declare -a orphan_reports=()
triggering_count=0
grandfathered_count=0

# Narrow trigger patterns — require action-paired context, not bare word.
# The conservative principle: trigger ONLY on phrases that clearly
# describe a subagent dispatch (an agent-driven sub-task that LANDED
# work), not on commits that merely mention "agent" or "subagent" in
# unrelated prose (e.g., "user agent", "the gate's name CM-SUBAGENT-…").
declare -a TRIGGER_PATTERNS=(
    'subagent[[:space:]]+(landed|dispatched|merged|worktree|driven|discovered|surfaced|closed|spawned)'
    '(dispatched|dispatching|spawned|landed|merged|via)[[:space:]]+(a[[:space:]]+|the[[:space:]]+|new[[:space:]]+|[0-9]+[a-z-]*[[:space:]]+)?(sub)?agent'
    'worktree-agent-[a-z0-9]{8,}'
    'agent-driven'
    '\bagent worktree\b'
    'agent[[:space:]]+(landed|merged|surfaced|closed|finished)'
)

for sha in "${commits[@]}"; do
    [[ -z "$sha" ]] && continue
    body=$(git log -1 --format=%B "$sha" 2>/dev/null)
    [[ -z "$body" ]] && continue
    commit_date=$(git log -1 --format=%cs "$sha" 2>/dev/null)

    # Strip whitelist-applicable lines + spans
    # Note: we do NOT skip 2-space-indented lines here (unlike
    # commit-docs-exists) because bullet-continuation lines often
    # carry the subagent-action verb that is the trigger. The
    # CM-SUBAGENT-DELEGATION-AUDIT line-skip handles gate-name
    # self-reference false-positives.
    stripped=$(printf '%s\n' "$body" | awk '
        /^> / { next }
        /^    / { next }
        /CM-SUBAGENT-DELEGATION-AUDIT/ { next }
        {
            line = $0
            gsub(/`[^`]*`/, "", line)
            gsub(/~~[^~]*~~/, "", line)
            print line
        }
    ')

    # Check if any trigger pattern matches
    triggered=false
    triggered_phrase=""
    for pat in "${TRIGGER_PATTERNS[@]}"; do
        match=$(echo "$stripped" | grep -iE "$pat" | head -1 | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//' | head -c 100)
        if [[ -n "$match" ]]; then
            triggered=true
            triggered_phrase="$match"
            break
        fi
    done
    [[ "$triggered" == "false" ]] && continue
    ((triggering_count++))

    # Effective-from gate
    if [[ "$commit_date" < "$cutoff" ]]; then
        ((grandfathered_count++))
        continue
    fi

    # Look for an audit entry that references this commit's SHA
    # (full or short prefix). Permissive grep across all *.json under audit_dir.
    short_sha="${sha:0:8}"
    if find "$audit_dir" -maxdepth 1 -name '*.json' -print0 2>/dev/null \
        | xargs -0 grep -l "$short_sha" 2>/dev/null \
        | head -1 \
        | grep -q .
    then
        continue
    fi

    orphan_reports+=("commit ${sha:0:8} ($commit_date) — trigger: \"$triggered_phrase\"")
    ((orphan_count++))
done

if [[ $orphan_count -gt 0 ]]; then
    echo "CM-SUBAGENT-DELEGATION-AUDIT VIOLATION (cutoff=$cutoff):" >&2
    echo "  Commits describe subagent-dispatch activity but lack an audit entry" >&2
    echo "  under .lava-ci-evidence/subagent-dispatches/:" >&2
    printf '    %s\n' "${orphan_reports[@]}" >&2
    echo "" >&2
    echo "  → Create '$audit_dir/<YYYY-MM-DD>-<short-slug>.json' with at" >&2
    echo "    least: dispatch_date, agent_type, purpose, dispatched_for_commit." >&2
    echo "    See docs/scripts/check-subagent-delegation-audit.sh.md for schema." >&2
    exit 1
fi

echo "CM-SUBAGENT-DELEGATION-AUDIT gate clean: ${#commits[@]} commit(s) scanned, $triggering_count subagent-trigger, $grandfathered_count grandfathered (pre-$cutoff), 0 orphans."
