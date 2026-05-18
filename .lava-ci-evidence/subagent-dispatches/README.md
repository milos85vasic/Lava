# `.lava-ci-evidence/subagent-dispatches/` — Subagent Delegation Audit Trail

Per Lava §6.AD-debt closure (CM-SUBAGENT-DELEGATION-AUDIT, landed 2026-05-18).

## Purpose

Per HelixConstitution §11.4.x + Lava §6.AD-debt: when a commit message describes a SUBAGENT DISPATCH ACTIVITY (an agent-driven sub-task that landed work into this repo via the Agent tool, Task delegation, or worktree-based isolation), an audit-trail entry MUST exist here naming the agent, the purpose, and the outcome.

This makes subagent work discoverable + auditable. Without it, a future operator (or future session) reading `git log` sees mentions of agent-X / worktree-agent-Y but can't reconstruct context.

## File naming

```
<YYYY-MM-DD>-<short-slug>.json
```

Example: `2026-05-18-helixqa-option1-wiring.json`

The date is the dispatch date (when the subagent was invoked, not when the commit landed — often within hours of each other).

## Schema

```json
{
  "dispatch_date":           "YYYY-MM-DD",
  "agent_type":              "general-purpose | Explore | code-reviewer | plugin-validator | ...",
  "purpose":                 "one-line summary of what the subagent was tasked with",
  "dispatched_for_commit":   "SHA OR 8-char short-SHA-prefix",
  "outcome":                 "merged | discarded | failed | pending",

  "isolation":               "worktree | inline",                              // optional
  "worktree_branch":         "worktree-agent-XXXXXXX",                         // optional
  "findings_count":          0,                                                 // optional
  "follow_ups":              ["issue/url/SHA"]                                 // optional
}
```

Minimum required fields: `dispatch_date`, `agent_type`, `purpose`, `dispatched_for_commit`.

## When entries are required (post-cutoff 2026-05-19)

YES create an entry when:
- A subagent landed work into the tree (merge from `worktree-agent-X`, dispatched-then-merged work).
- A subagent-driven discovery surfaced a finding referenced in the commit.
- The commit body says "subagent landed", "dispatched X agent", "spawned X-wave agent", or mentions a `worktree-agent-[hex]+` branch.

NO entry needed when:
- The commit only mentions the gate's name `CM-SUBAGENT-DELEGATION-AUDIT` without describing a dispatch.
- The trigger phrase appears inside backticks or `~~strikethrough~~` (whitelisted).
- The work was direct-authored without subagent involvement.

## Gate enforcement

`scripts/check-subagent-delegation-audit.sh` scans commit bodies + verifies a matching entry exists for each post-cutoff trigger. See `docs/scripts/check-subagent-delegation-audit.sh.md` for full spec.

`scripts/verify-all-constitution-rules.sh` runs the gate as part of the comprehensive sweep. Pre-push hooks transitively run via `scripts/check-constitution.sh`.

## Pre-cutoff (before 2026-05-19) — grandfathered

The ~30+ subagent-referencing commits in the 30 days before 2026-05-18 are GRANDFATHERED — they don't need backfill entries. The cutoff exists because retroactive backfill is impractical + the audit-trail convention was being established by this gate's landing.

## Classification

Project-specific (path layout + cutoff date are Lava-specific; the audit-trail mandate itself is universal per HelixConstitution).
