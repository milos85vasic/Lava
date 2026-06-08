# LLMOrchestrator cross-org mirror divergence — reconciliation plan

**Date:** 2026-06-08
**Scope:** `submodules/llm_orchestrator` gitdir only. READ-ONLY investigation. No push / merge / force performed.
**Investigator note:** All facts below are from actual `git` output, not surmise (§11.4.6).

---

## 1. Remote map

`git -C submodules/llm_orchestrator remote -v`:

| Remote | Fetch URL | Push URL |
|---|---|---|
| `origin` | `git@github.com:vasic-digital/LLMOrchestrator.git` | `git@github.com:HelixDevelopment/LLMOrchestrator.git` |
| `gitlab` | `git@gitlab.com:vasic-digital/LLMOrchestrator.git` | `git@gitlab.com:vasic-digital/LLMOrchestrator.git` |
| `github` | `git@github.com:HelixDevelopment/LLMOrchestrator.git` | `git@github.com:HelixDevelopment/LLMOrchestrator.git` |
| `upstream` | `git@github.com:HelixDevelopment/LLMOrchestrator.git` | `git@github.com:HelixDevelopment/LLMOrchestrator.git` |

**Interpretation:**
- **§6.W pin targets (what Lava consumes):** the vasic-digital pair — `origin` *fetch* (`github.com/vasic-digital`) + `gitlab` (`gitlab.com/vasic-digital`). The local `master` tracks `[origin/master]`.
- **Cross-org HelixDevelopment mirror:** `github` and `upstream` are BOTH `github.com/HelixDevelopment/LLMOrchestrator.git` (duplicate remotes pointing at the same URL).
- **MISCONFIGURATION (forensic finding):** `origin` has a **split URL** — fetch = `vasic-digital`, push = `HelixDevelopment`. This is almost certainly the cause of the original session's confusion: a `git push origin` would have pushed to HelixDevelopment (the diverged side) while `git fetch`/tracking reads from vasic-digital. This split is itself worth flagging to the operator (see §6).

Local branch state: `* master a484f7d [origin/master: ahead 1]` — clean working tree (no uncommitted changes).

---

## 2. Divergence facts (from `git rev-list --left-right --count` + `git merge-base`)

| Fact | Value |
|---|---|
| Local `master` (vasic-digital pin) | `a484f7dd` |
| `upstream/master` (HelixDevelopment) | `80ead875968906bfe71f874d2de51cd7b6c760d7` |
| Commits local has, upstream lacks | **1** (`a484f7d`) |
| Commits upstream has, local lacks | **1** (`80ead87`) |
| Common ancestor (`merge-base`) | `d2a2151` (`cascade: chore(1.1.8-dev-rc1 cascade)…`) |
| Relationship | **True divergence** — NOT a fast-forward in either direction |
| Histories | **Related** (shared ancestor exists — NOT unrelated histories) |
| `upstream/main` (separate branch) | `bde3643` — not involved in this reconciliation |

**Conclusion:** this is a clean 1-vs-1 fork off a shared base. Neither side is a superset of the other; each carries one independent commit.

---

## 3. What each side contains

### Local `a484f7d` (vasic-digital side — the Lava pin)
```
chore(deps): add §11.4.31 helix-deps.yaml dependency manifest
 helix-deps.yaml | 32 ++++++++++++++++++++++++++++++++  (1 file, +32)
```
Adds the helix-deps.yaml manifest (the work from this session). `helix-deps.yaml` is **ABSENT** on the HelixDevelopment side.

### Upstream `80ead87` (HelixDevelopment side)
```
chore(go.mod): remove dead llmprovider replace + stray analysis artifact (Wave4 W4E)
 go.mod                 | 2 --
 submodule-analysis.txt | 8 --------   (2 files, -10)
```
Dated 2026-06-04 (4 days older than the local commit). Removes a **dead `replace digital.vasic.llmprovider => ../LLMProvider`** directive from `go.mod:18` and deletes a stray `submodule-analysis.txt`. Commit body asserts `go build`/`go vet`/`go mod verify` all green.

**Verified on the local (vasic-digital) side, this cleanup is still UN-applied:**
- `go.mod:18` on local `master` **still contains** `replace digital.vasic.llmprovider => ../LLMProvider`.
- `submodule-analysis.txt` is **still PRESENT** on local `master`.

So the HelixDevelopment commit is **genuine, useful, not-yet-superseded cleanup work** — not a stale fork. It should be preserved, not orphaned.

### Verdict
Neither side is stale or a superset. The two commits are **complementary** (disjoint files, both wanted). The correct outcome is a UNION of both.

---

## 4. Recommended reconciliation — OPTION (a): merge HelixDevelopment into local, then push to all three

**Why (a) and not (b)/(c):**
- **(b) `-s ours`** would orphan `80ead87`'s real go.mod/cleanup work — wrong, because that cleanup is genuine and still un-applied locally. Rejected.
- **(c) treat HelixDevelopment as an independent fork** — abandons a legitimate cleanup commit and leaves the vasic-digital pin carrying a dead `replace` directive the upstream already fixed. Rejected.
- **(a) merge** — preserves BOTH commits, is **conflict-free** (verified), and is the §6.T.3-compliant non-force path. The `-s ours` 4-mirror reconciliation pattern is reserved for "sibling mirror has diverged AND its content is superseded"; here the content is NOT superseded.

**Conflict-free confirmation (read-only):** `git merge-tree --write-tree master upstream/master` exited 0 and produced a clean tree (`9fed01f…`) with **no conflict markers**. The merge touches disjoint files (`helix-deps.yaml` added by us vs `go.mod`/`submodule-analysis.txt` cleaned by them), so it merges cleanly.

### Exact commands (to run AFTER operator approval — see §5)

```bash
cd submodules/llm_orchestrator

# 0. Safety: confirm clean tree + current SHAs
git status --porcelain          # expect empty
git rev-parse master            # expect a484f7dd…
git rev-parse upstream/master   # expect 80ead875…

# 1. Merge the HelixDevelopment commit into local master (non-FF, no force)
git merge --no-ff upstream/master \
  -m "merge: reconcile HelixDevelopment cross-org mirror (80ead87 go.mod cleanup) into vasic-digital master"
# expected result: clean merge tree 9fed01f…, no conflicts

# 2. Verify build still green after the merge (the upstream commit claims green)
go build ./... && go vet ./... && go mod verify

# 3. Push the merge to ALL THREE mirror endpoints so they converge on the SAME SHA
git push gitlab   master          # gitlab.com/vasic-digital
git push origin   master          # NOTE: origin PUSH url = HelixDevelopment (split-URL!) — see §6
git push github   master          # github.com/HelixDevelopment (explicit)
# For the vasic-digital GitHub endpoint, origin's FETCH url is vasic-digital but its
# PUSH url is HelixDevelopment. To push the vasic-digital GitHub mirror explicitly,
# the operator must either fix origin's push URL or add a dedicated remote first
# (see §6 — this is an operator decision, not a blind push).

# 4. Confirm convergence (§6.C 2-mirror / cross-org parity)
git ls-remote gitlab   refs/heads/master
git ls-remote github   refs/heads/master
git ls-remote origin   refs/heads/master   # fetch url = vasic-digital github
# all three SHOULD report the same merge SHA after the pushes
```

**Note on the parent index:** the merge advances the submodule HEAD from `a484f7dd` to the new merge SHA. Per this task's constraints I did NOT touch the parent index. If/when the operator approves the merge+push, whether to re-pin the Lava parent to the merge SHA is a SEPARATE decision (the Q9 always-track-upstream waiver covers HelixQA only, not LLMOrchestrator — LLMOrchestrator is a frozen-by-default pin, so a parent re-pin is a deliberate operator action).

---

## 5. OPERATOR-APPROVAL GATE (explicit)

The following require explicit operator authorization BEFORE execution — none were done in this read-only pass:

1. **The merge itself (`git merge --no-ff upstream/master`).** It alters local `master` history (advances HEAD to a merge commit). Compatible + conflict-free, but still a history-affecting action on a branch that feeds the Lava pin.
2. **Every push (`git push gitlab/origin/github master`).** Per §6.T.3, no push without explicit per-operation approval. Authorization for one push does not extend to the others.
3. **The `origin` split-URL situation.** `origin` fetch=vasic-digital, push=HelixDevelopment. The operator must decide how to push the **vasic-digital GitHub** mirror — fix `origin`'s push URL back to vasic-digital, or add a dedicated `github-vd` remote. Pushing blindly to `origin` would land on HelixDevelopment, not vasic-digital. This is a configuration decision, not a mechanical step.
4. **Re-pinning the Lava parent** to the merge SHA (frozen-by-default pin; deliberate operator action; NOT covered by the HelixQA Q9 waiver).

No `-s ours` is recommended, so there is no orphan-commit scenario to approve. If the operator instead preferred Option (b)/(c) (NOT recommended), that WOULD orphan `80ead87` and would need explicit approval to discard that cleanup work.

---

## 6. Additional forensic flag for the operator

The `origin` remote has a **split fetch/push URL** (fetch = `github.com/vasic-digital`, push = `github.com/HelixDevelopment`). Combined with duplicate `github`/`upstream` remotes both pointing at HelixDevelopment, this remote layout is the most likely root cause of the original "push to vasic-digital succeeded but HelixDevelopment rejected" confusion this session. Recommend the operator normalize the remote set (one clear remote per mirror endpoint) as a follow-up so future pushes are unambiguous.
