# Phase 6f — Upstream Repo Rename Execution Plan

**Status:** DEFERRED (operator decision Q1: defer upstream renames; perform Lava-side local renames only in Phase 6a + 6b).
**Authority:** HelixConstitution §11.4.29 (Lowercase-Snake_Case-Naming Mandate, 2026-05-15) — local-path target is binding immediately; upstream-repo-name target is binding eventually.
**Companion artefacts:** `docs/plans/2026-05-16-snake_case-migration.md` (parent plan), `.gitmodules` (post-6a+6b URLs still point at CamelCase upstream names — this plan executes the migration that lets us rewrite those URLs).
**Classification:** project-specific (the per-repo rename commands ARE Lava-specific; the cross-org coordination discipline is universal per §11.4.29 + §6.W).
**Inheritance:** §11.4.29 + §6.W (mirror policy — GitHub + GitLab only) + §6.AD (HelixConstitution inheritance) + §6.T.3 (no-force-push).

---

## 0. Scope statement (no-bluff)

This document enumerates the **34 upstream-rename operations** required to bring the upstream-repo NAMES in line with the local-path snake_case names produced by Phase 6a + Phase 6b. **THIS AGENT DOES NOT EXECUTE these renames.** Phase 6a + 6b's commits will land with `.gitmodules` `url =` fields STILL referencing the CamelCase upstream URLs:

```
url = git@github.com:vasic-digital/Tracker-SDK.git
url = git@github.com:HelixDevelopment/HelixQA.git
url = git@github.com:vasic-digital/RateLimiter.git
... etc
```

This is the §11.4.29-conformant TRANSITIONAL state: the local working-tree path is snake_case (the rule's reach); the network identifier (URL) is the upstream's choice and is updated in a separate cross-org coordination cycle.

Per §6.AD.3 + §6.T.3 (no force-push without explicit operator approval per-operation), executing the renames in this document requires:

1. **Per-operation operator approval** for the inverse force-push that mirror-rename triggers.
2. **Confirmation that no other consumer of any submodule** has open work-in-progress that would break by the rename. Each of the 17 submodules has at least one downstream consumer (Lava itself; potentially other vasic-digital projects).
3. **Operator-led execution** since these renames cross 2 orgs (vasic-digital + HelixDevelopment) and 2 mirrors (GitHub + GitLab) per the §6.W mirror policy.

---

## 1. Enumeration of 34 upstream-rename operations

Each rename has TWO sub-operations (one per mirror per §6.W). The mapping:

### 1.1 vasic-digital org (16 repos × 2 mirrors = 32 operations)

| # | Current name | Target name | Org | GitHub command | GitLab command |
|---|---|---|---|---|---|
| 1 | `Auth` | `auth` | vasic-digital | `gh repo rename auth --repo vasic-digital/Auth` | `glab repo rename --new-name auth --repo vasic-digital/Auth` |
| 2 | `Cache` | `cache` | vasic-digital | `gh repo rename cache --repo vasic-digital/Cache` | `glab repo rename --new-name cache --repo vasic-digital/Cache` |
| 3 | `Challenges` | `challenges` | vasic-digital | `gh repo rename challenges --repo vasic-digital/Challenges` | `glab repo rename --new-name challenges --repo vasic-digital/Challenges` |
| 4 | `Concurrency` | `concurrency` | vasic-digital | `gh repo rename concurrency --repo vasic-digital/Concurrency` | `glab repo rename --new-name concurrency --repo vasic-digital/Concurrency` |
| 5 | `Config` | `config` | vasic-digital | `gh repo rename config --repo vasic-digital/Config` | `glab repo rename --new-name config --repo vasic-digital/Config` |
| 6 | `Containers` | `containers` | vasic-digital | `gh repo rename containers --repo vasic-digital/Containers` | `glab repo rename --new-name containers --repo vasic-digital/Containers` |
| 7 | `Database` | `database` | vasic-digital | `gh repo rename database --repo vasic-digital/Database` | `glab repo rename --new-name database --repo vasic-digital/Database` |
| 8 | `Discovery` | `discovery` | vasic-digital | `gh repo rename discovery --repo vasic-digital/Discovery` | `glab repo rename --new-name discovery --repo vasic-digital/Discovery` |
| 9 | `HTTP3` | `http3` | vasic-digital | `gh repo rename http3 --repo vasic-digital/HTTP3` | `glab repo rename --new-name http3 --repo vasic-digital/HTTP3` |
| 10 | `Mdns` | `mdns` | vasic-digital | `gh repo rename mdns --repo vasic-digital/Mdns` | `glab repo rename --new-name mdns --repo vasic-digital/Mdns` |
| 11 | `Middleware` | `middleware` | vasic-digital | `gh repo rename middleware --repo vasic-digital/Middleware` | `glab repo rename --new-name middleware --repo vasic-digital/Middleware` |
| 12 | `Observability` | `observability` | vasic-digital | `gh repo rename observability --repo vasic-digital/Observability` | `glab repo rename --new-name observability --repo vasic-digital/Observability` |
| 13 | `RateLimiter` | `ratelimiter` | vasic-digital | `gh repo rename ratelimiter --repo vasic-digital/RateLimiter` | `glab repo rename --new-name ratelimiter --repo vasic-digital/RateLimiter` |
| 14 | `Recovery` | `recovery` | vasic-digital | `gh repo rename recovery --repo vasic-digital/Recovery` | `glab repo rename --new-name recovery --repo vasic-digital/Recovery` |
| 15 | `Security` | `security` | vasic-digital | `gh repo rename security --repo vasic-digital/Security` | `glab repo rename --new-name security --repo vasic-digital/Security` |
| 16 | `Tracker-SDK` | `tracker_sdk` | vasic-digital | `gh repo rename tracker_sdk --repo vasic-digital/Tracker-SDK` | `glab repo rename --new-name tracker_sdk --repo vasic-digital/Tracker-SDK` |

### 1.2 HelixDevelopment org (1 repo × 2 mirrors = 2 operations)

| # | Current name | Target name | Org | GitHub command | GitLab command |
|---|---|---|---|---|---|
| 17 | `HelixQA` | `helixqa` | HelixDevelopment | `gh repo rename helixqa --repo HelixDevelopment/HelixQA` | `glab repo rename --new-name helixqa --repo HelixDevelopment/HelixQA` |

**Total: 17 repos × 2 mirrors = 34 upstream-rename operations.**

---

## 2. Pre-execution checklist (operator)

Before invoking ANY of the 34 commands above:

- [ ] All Lava local commits referencing the OLD upstream names have landed + pushed (Phase 6a + 6b complete, `.gitmodules` updated to new local paths but URLs still point at OLD upstream repo names).
- [ ] All downstream consumers of each `vasic-digital/*` repo audited — at minimum, every project that lists the repo in their own `.gitmodules` is identified. Lava itself is the primary consumer; check for any other vasic-digital projects.
- [ ] All open PRs against the OLD-named repos noted — rename will redirect them to the new URL but contributors with local clones will need to update their git remotes manually.
- [ ] Confirmation that GitHub + GitLab BOTH will redirect old URLs to new URLs (both providers do; the redirect is server-side, no action required after rename).
- [ ] `.git-backup-pre-phase6f-<UTC-timestamp>/repo.git.mirror` hardlinked backup created per §9.1 (HelixConstitution + Lava's §6.T.3 discipline).

## 3. Execution sequence (operator-led)

For each of the 17 submodules, in the order listed above:

1. **Rename on GitHub:** invoke the GitHub command from §1.
2. **Verify the rename succeeded:** `gh repo view vasic-digital/<new-name>` returns the expected metadata.
3. **Rename on GitLab:** invoke the GitLab command from §1.
4. **Verify the rename succeeded:** `glab repo view vasic-digital/<new-name>` returns the expected metadata.
5. **Wait 60 seconds** for any automated mirror-sync workflows to converge. (Lava does not use hosted CI per the Local-Only CI/CD rule, but external mirror-sync may exist.)

After all 34 operations are complete, perform §4 (Lava-side URL update).

## 4. Lava-side `.gitmodules` URL update (this agent CAN execute after operator authorization)

Once all 34 upstream renames have completed AND the operator authorizes the follow-up commit:

1. **Update `.gitmodules`** — change every `url =` field to the new repo name. Example transformations:
   - `url = git@github.com:vasic-digital/Tracker-SDK.git` → `url = git@github.com:vasic-digital/tracker_sdk.git`
   - `url = git@github.com:HelixDevelopment/HelixQA.git` → `url = git@github.com:HelixDevelopment/helixqa.git`
2. **Update each submodule's `Upstreams/GitHub.sh` + `Upstreams/GitLab.sh`** — these scripts hard-code the upstream repo name. Each of the 17 submodules has these scripts in its own root; each needs updating.
3. **Run `git submodule sync`** in every consumer project to refresh internal git pointers.
4. **Operator final-verification:** `git submodule status` on a fresh clone successfully checks out every submodule via the new URL.
5. **Commit + push** with the operator-approved §6.T.3 force-push if needed (typically not needed — submodule URL updates are normal commits).

## 5. Forensic-anchor evidence

Each rename operation MUST be evidenced in:

- `.lava-ci-evidence/phase6f-upstream-renames/<UTC-timestamp>/per-op.json` containing: timestamp, command-invoked, mirror, repo-old-name, repo-new-name, `gh`/`glab` output, success/failure.

If any rename fails partially (e.g. GitHub succeeds but GitLab fails), the partial-state MUST be diagnosed + recovered before proceeding. Per §6.M-style classification.

## 6. Rollback plan

GitHub + GitLab both REDIRECT old URLs to new URLs server-side. Rolling back a rename:

1. Re-rename on each mirror: `gh repo rename Tracker-SDK --repo vasic-digital/tracker_sdk` (the reverse).
2. Update consumer projects' `.gitmodules` URLs back to the old names.
3. Wait for redirect propagation (typically immediate but may take up to 60 seconds).

The rollback is non-destructive — git history is preserved across renames; only the network identifier changes.

## 7. Why this is deferred (Q1 decision rationale)

The operator's Q1 decision for the snake_case migration:

> "Phase 6f UPSTREAM rename for ALL 17 owned-by-us — DEFER actual upstream renames; document execution steps for operator. Lava-side rename ALL paths now."

The rationale:

- Lava-side local-path rename is mechanical + reversible within Lava's git history.
- Upstream rename is mechanical but breaks every clone URL anywhere on the operator's network. GitHub/GitLab redirects mitigate this BUT (a) some operations (e.g., GraphQL API queries) may not follow redirects, (b) consumer projects' CI configurations (none for Lava per Local-Only-CI/CD; but third parties may exist) need updating.
- Decoupling the two-phase migration lets the local-path change ship + soak before the upstream-rename's broader blast radius is triggered.
- Per §6.AD.3 + §6.T.3, executing 34 force-push-adjacent operations requires per-operation operator approval — a separate session.

## 8. Sign-off checklist for execution (operator)

- [ ] Operator reviews this plan.
- [ ] Operator approves the 34-operation list.
- [ ] Operator approves the ordering (any order is safe; alphabetical is recommended for auditability).
- [ ] `.git-backup-pre-phase6f-<TS>/` hardlinked backup created.
- [ ] All downstream-consumer projects identified + their maintainers notified (if any beyond Lava).
- [ ] Operator schedules a single contiguous session to execute all 34 operations + the `.gitmodules` URL update commit in lockstep (avoids prolonged drift between local-path and upstream-name).

---

**End of plan.** This document is the authoritative reference for Phase 6f execution. Until executed, Lava's `.gitmodules` will reference CamelCase upstream URLs while local paths are snake_case — a §11.4.29-conformant transitional state.
