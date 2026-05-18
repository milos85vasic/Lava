# `scripts/check-subagent-delegation-audit.sh` — User Guide

**Last verified:** 2026-05-18 (1.2.30-1050 tooling cycle)
**Inheritance:** HelixConstitution §11.4.x (subagent-delegation-audit-trail mandate) + Lava §6.AD-debt closure (CM-SUBAGENT-DELEGATION-AUDIT — THIRD and FINAL §6.AD-debt item)

## Overview

When a commit message describes a SUBAGENT DISPATCH ACTIVITY (an agent-driven sub-task that landed work into this repo via the Agent tool or worktree-based delegation), the gate verifies a matching audit-trail entry exists under:

```
.lava-ci-evidence/subagent-dispatches/<YYYY-MM-DD>-<short-slug>.json
```

The audit trail makes subagent activity discoverable + auditable: which agent, when, for what purpose, with what outcome. Without it, a future operator (or future session) reading `git log` sees mentions of subagents but can't reconstruct context.

## Audit-entry schema

```json
{
  "dispatch_date":           "YYYY-MM-DD",
  "agent_type":              "general-purpose | Explore | code-reviewer | plugin-validator | code-modernization:legacy-analyst | ...",
  "purpose":                 "one-line summary of what the subagent was tasked with",
  "dispatched_for_commit":   "SHA OR 8-char short-SHA-prefix",
  "outcome":                 "merged | discarded | failed | pending",

  "isolation":               "worktree | inline",                           // optional
  "worktree_branch":         "worktree-agent-XXXXXXX (if isolation=worktree)", // optional
  "findings_count":          0,                                              // optional
  "follow_ups":              ["issue/url/SHA"]                              // optional
}
```

Minimum required fields: `dispatch_date`, `agent_type`, `purpose`, `dispatched_for_commit`.

## Usage

```bash
# Default — scan HEAD only
bash scripts/check-subagent-delegation-audit.sh

# Range
bash scripts/check-subagent-delegation-audit.sh HEAD~5..HEAD

# Unpushed (recommended pre-push)
bash scripts/check-subagent-delegation-audit.sh '@{u}..HEAD'

# Env-var alternatives
LAVA_COMMIT_RANGE='HEAD~3..HEAD' bash scripts/check-subagent-delegation-audit.sh
LAVA_REPO_ROOT=/path bash scripts/check-subagent-delegation-audit.sh
LAVA_SUBAGENT_AUDIT_CUTOFF=2026-06-01 bash scripts/check-subagent-delegation-audit.sh
```

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | No triggering commits found, OR every triggering commit has a matching audit entry |
| 1 | At least one post-cutoff triggering commit lacks an audit entry; commit SHA + trigger phrase printed to stderr |

## What counts as a "subagent dispatch" trigger

The scanner uses **action-paired** patterns to avoid false-positives on commits that merely mention "agent" or "subagent" in unrelated prose (e.g., the gate's own name `CM-SUBAGENT-DELEGATION-AUDIT`).

Triggering patterns (case-insensitive):

1. `subagent <action>` where action ∈ {landed, dispatched, merged, worktree, driven, discovered, surfaced, closed, spawned}
2. `<action> [article|count] (sub)agent` — e.g. `dispatched the agent`, `merged a subagent`, `spawned 5th-wave agent`
3. `worktree-agent-[a-z0-9]{8,}` — Claude Code branch convention (very specific, 8+ hex chars)
4. `agent-driven` — generic subagent-activity prose
5. `agent worktree` — generic
6. `agent (landed|merged|surfaced|closed|finished)` — passive-voice action

## Whitelist conditions (intentional skips)

- **Blockquote lines** (lines starting with `> `).
- **4+ space indented lines** (heavy quoted-output / code-block-like prose; bullet continuations at 2-space indent still scanned).
- **Lines containing `CM-SUBAGENT-DELEGATION-AUDIT`** (gate-name self-reference).
- **Backtick spans** (` `code` `).
- **`~~strikethrough~~` spans**.

## Effective-from cutoff

Default `LAVA_SUBAGENT_AUDIT_CUTOFF=2026-05-19`. Commits BEFORE this date are grandfathered (no audit entry required). The cutoff exists because past commits (~30+ in last 30 days) referenced subagents without an audit-trail convention; backfilling all of them is not the right closure posture for this gate.

Override the cutoff to enforce on a different date.

## §6.J anti-bluff falsifiability rehearsal

1. Make a post-cutoff commit with a subagent trigger phrase:
   ```bash
   git commit --allow-empty -m "merge: subagent landed Phase X waiver backfill"
   ```
2. Run `bash scripts/check-subagent-delegation-audit.sh` → expect exit 1 with the commit SHA + trigger.
3. Either (a) create the audit entry under `.lava-ci-evidence/subagent-dispatches/<date>-*.json` with `dispatched_for_commit: <short-sha>`, OR (b) `git commit --amend` removing the trigger.
4. Re-run → expect exit 0.

The deliberate-orphan rehearsal proves the gate fires on real audit gaps.

## Integration

- Wired into `scripts/verify-all-constitution-rules.sh` as a standard gate (default scope: `HEAD~5..HEAD`).
- Pre-push hooks transitively run via `scripts/check-constitution.sh`.
- Recommended pre-push scope: `@{u}..HEAD` (all unpushed commits).

## Hermetic test

`tests/check-constitution/test_subagent_delegation_audit.sh` — 8 fixtures:

1. No subagent triggers → pass
2. Pre-cutoff trigger → grandfathered → pass
3. Post-cutoff trigger without audit entry → reject (exit 1)
4. Post-cutoff trigger with matching audit entry → pass
5. Gate-name self-reference (no other trigger) → pass
6. worktree-agent-X branch-convention → trigger → reject (post-cutoff, no audit)
7. Backtick-quoted trigger phrase → skipped → pass
8. Real-repo HEAD sanity check → pass

Run:
```bash
bash tests/check-constitution/test_subagent_delegation_audit.sh
```

## When to create an audit entry

**Required (post-cutoff):** any commit whose body describes one of:
- A subagent landed work into the tree (merge from `worktree-agent-X`, dispatched-then-merged work, agent-driven discovery).
- A subagent surfaced a finding that's referenced in the commit (e.g., `subagent surfaced 3 bluffs`).

**Not required:**
- Commits that mention the gate's name `CM-SUBAGENT-DELEGATION-AUDIT` without describing a dispatch.
- Commits that backtick-quote a trigger phrase as a literal string.
- Commits authored by the operator directly (no subagent involvement).

## When not to use a subagent (and avoid this gate)

For routine tasks the main agent can complete in <3 tool calls, subagent dispatch is overhead. The gate exists to make NON-routine dispatches discoverable, not to encourage every task to spawn an agent.

`Classification:` project-specific (the convention is universal per HelixConstitution; the audit-dir path + cutoff date are Lava-specific).
