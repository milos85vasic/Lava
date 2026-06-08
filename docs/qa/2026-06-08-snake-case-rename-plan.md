# §11.4.29 CamelCase → snake_case Upstream Repo Rename Plan

**Date:** 2026-06-08
**Status:** PLAN ONLY — uncommitted, operator/main-agent executes deliberately.
**Mandate:** HelixConstitution §11.4.29 (own-org repos use snake_case names).
**Constraints honored:** §6.T.3 (no force-push, per-operation operator approval), §6.W (GitHub + GitLab parity only), §6.U (no sudo/su).

---

## 0. Executive summary — the actual problem is smaller than it looks

The 5 dep submodules are already **mounted at snake_case paths** on disk
(`submodules/doc_processor`, …) and their Go **module paths are already
lowercase logical names** (`digital.vasic.docprocessor`, …) that do NOT
embed the repo name. The CamelCase only survives in three places:

1. The **remote repo NAMES** on GitHub + GitLab (`vasic-digital/DocProcessor`, …).
2. The **`.gitmodules` `url=` entries** in the Lava parent (PascalCase).
3. A handful of **`replace … => ../<PascalCasePath>`** directives — and the
   load-bearing one (helixqa upstream `main` go.mod) is exactly why the
   helixqa pin must track `lava-pin/2026-06-08-android-executor` instead of
   `main`. The lava-pin branch already uses `../doc_processor`; only upstream
   `main` uses `../DocProcessor`.

Because the Go module identifiers do not change, **renaming the remote repos
does NOT cascade into Go import paths or `go.sum` checksums.** The rename is
almost entirely a metadata operation (repo name + `.gitmodules` url +
submodule remote URLs).

**Bonus bug found (fix in the same effort):**
`submodules/llm_orchestrator/go.mod:18` is
`replace digital.vasic.llmprovider => ../LLMProvider` but the disk path is
`llm_provider`. This works only by macOS case-insensitivity and is broken on
a case-sensitive volume. It must become `=> ../llm_provider`.

---

## (a) Mapping table

Verified via `git -C submodules/<name> remote -v`, `gh repo view`, `glab repo view`.
snake_case targets confirmed to NOT yet exist on either provider.

| submodule path | current GitHub repo | current GitLab repo | proposed snake_case |
|---|---|---|---|
| `submodules/doc_processor` | `vasic-digital/DocProcessor` (origin); also `HelixDevelopment/DocProcessor` exists | `vasic-digital/DocProcessor` | `doc_processor` |
| `submodules/llm_orchestrator` | `vasic-digital/LLMOrchestrator` (origin fetch); also `HelixDevelopment/LLMOrchestrator` exists | `vasic-digital/LLMOrchestrator` | `llm_orchestrator` |
| `submodules/llm_provider` | `vasic-digital/LLMProvider` (origin); also `HelixDevelopment/LLMProvider` exists | `vasic-digital/LLMProvider` | `llm_provider` |
| `submodules/llms_verifier` | `vasic-digital/LLMsVerifier` (origin) | `vasic-digital/LLMsVerifier` | `llms_verifier` |
| `submodules/vision_engine` | `vasic-digital/VisionEngine` (origin fetch); also `HelixDevelopment/VisionEngine` exists | `vasic-digital/VisionEngine` | `vision_engine` |
| `panoptic` (other own-org CamelCase) | `vasic-digital/Panoptic` | `vasic-digital/panoptic` (already lowercase on GitLab) | `panoptic` |

**Already snake_case / lowercase (NO rename needed):** all 16 core submodules
(`auth`, `cache`, `challenges`, `concurrency`, `config`, `containers`,
`database`, `discovery`, `http3`, `mdns`, `middleware`, `observability`,
`ratelimiter`, `recovery`, `security`, `tracker_sdk`). HelixConstitution
(`HelixDevelopment/HelixConstitution`) is constitution-owned — out of scope,
not subject to §11.4.29 lowercasing for Lava per §6.AD.1. `helixqa`
(`HelixDevelopment/helixqa` on GitHub, `helixdevelopment1/HelixQA` on GitLab)
is already lowercase on GitHub; the GitLab `HelixQA` casing is a
HelixDevelopment-org concern.

### Remote-inconsistency flags (MUST be reconciled, see §c step 0)

The 5 dep submodules have **divergent, non-§6.W-compliant remote sets** today:

- `doc_processor`: clean (`origin`=GitHub vasic-digital, `gitlab`=GitLab vasic-digital). ✅
- `llm_orchestrator`: `origin` fetch=`vasic-digital`, `origin` PUSH=`HelixDevelopment`; extra `github`+`upstream` to `HelixDevelopment`. ✗ mixed-org.
- `llm_provider`: clean. ✅
- `llms_verifier`: extra `github`+`upstream` duplicating `origin`; `origin` has a stray second PUSH url to GitLab. ✗ malformed.
- `vision_engine`: `origin` fetch=`vasic-digital`, `origin` PUSH=`HelixDevelopment`; extra `github`+`upstream` to `HelixDevelopment`. ✗ mixed-org.

These predate the rename and must be normalized to the §6.W 2-remote model
(`origin`=GitHub, `gitlab`=GitLab, both `vasic-digital`) BEFORE renaming, so
the rename targets are unambiguous.

---

## (b) Blast-radius inventory (file:line evidence)

### B1. Parent-tracked files (the only files THIS repo's commits touch)

- **`.gitmodules`** — 5 dep url lines + panoptic:
  - `url = git@github.com:vasic-digital/DocProcessor.git` (doc_processor block)
  - `url = git@github.com:vasic-digital/LLMOrchestrator.git` (llm_orchestrator block)
  - `url = git@github.com:vasic-digital/LLMProvider.git` (llm_provider block)
  - `url = git@github.com:vasic-digital/LLMsVerifier.git` (llms_verifier block)
  - `url = git@github.com:vasic-digital/VisionEngine.git` (vision_engine block)
  - `url = git@github.com:vasic-digital/Panoptic.git` (panoptic block)
- **`docs/CONTINUATION.md`** — descriptive PascalCase references (§6.S doc, update text):
  - `:17` — `replace ../DocProcessor` narrative
  - `:390` — `LLMOrchestrator` narrative
  - `:430-434` — pin-index table rows naming `vasic-digital/DocProcessor`, `…/LLMOrchestrator`, `…/LLMProvider`, `…/LLMsVerifier`, `…/VisionEngine`
- **`constitution/submodules-catalogue.md`** — constitution-submodule-owned; references the names. NOT a Lava-parent commit (lives in the `constitution/` submodule git-dir). Flag to operator: update upstream in HelixConstitution if it pins repo names.

### B2. go.mod / go.sum references (verified by grep)

```
submodules/helixqa/go.mod:127   digital.vasic.docprocessor    => ../doc_processor   (lava-pin: already snake_case ✅)
submodules/helixqa/go.mod:128   digital.vasic.llmorchestrator => ../llm_orchestrator (✅)
submodules/helixqa/go.mod:129   digital.vasic.llmprovider     => ../llm_provider     (✅)
submodules/helixqa/go.mod:130   digital.vasic.llmsverifier    => ../llms_verifier/llm-verifier (✅)
submodules/helixqa/go.mod:132   digital.vasic.visionengine    => ../vision_engine    (✅)
submodules/llm_orchestrator/go.mod:18  replace digital.vasic.llmprovider => ../LLMProvider  ✗ BROKEN PATH (disk is llm_provider)
```

- helixqa **upstream `main`** go.mod (`git show origin/main:go.mod`):126-132
  uses `../DocProcessor`, `../LLMOrchestrator`, `../LLMProvider`,
  `../LLMsVerifier/llm-verifier`, `../VisionEngine` — PascalCase. This is the
  divergence the lava-pin branch fixes. Once the repos rename, the path-side
  fix can be merged upstream so `main` works on case-sensitive volumes and the
  pin can return to tracking `main`.
- **NO go.sum churn** — module paths (`digital.vasic.*`) are unchanged by a
  repo rename; checksums unaffected.
- `lava-api-go/go.mod` references only `digital.vasic.helixqa => ../submodules/helixqa` — unaffected.

### B3. Go import paths in source

**None.** Modules use logical `digital.vasic.*` identifiers, not
`github.com/owner/Repo` paths. `grep` for `vasic-digital/<Pascal>` and
`HelixDevelopment/<Pascal>` in `*.go` returned zero matches in tracked source.

### B4. Submodule-internal files (owned by the submodule repos, NOT Lava parent)

These change inside each submodule's own repo, in that repo's own commits —
NOT in a Lava-parent commit. Listed so the operator updates them upstream:

- `submodules/doc_processor/{README.md, USER_GUIDE.md, helix-deps.yaml, upstreams/VasicDigitalGitHub.sh, upstreams/VasicDigitalGitLab.sh, upstreams/GitHub.sh}`
- `submodules/llm_orchestrator/{helix-deps.yaml, CONTRIBUTING.md, USER_GUIDE.md, docs/test-coverage.md, upstreams/*.sh}`
- `submodules/llm_provider/{helix-deps.yaml, upstreams/*.sh}`
- `submodules/llms_verifier/{README.md, CLEANUP_CHECKLIST.md, *_PLAN.md, SCORING_SYSTEM_DOCUMENTATION.md, …}`
- `submodules/vision_engine/*` (analogous)
- Each `helix-deps.yaml` header comment names the repo (e.g. doc_processor's
  says "vasic-digital/DocProcessor") — cosmetic; update for §11.4.31 honesty.
- The `upstreams/*.sh` scripts hardcode the PascalCase repo in push URLs —
  **functionally load-bearing inside the submodule**; must be updated or they
  push to the now-redirected old name.

### B5. CI / scripts in the parent

`grep` over `scripts/`, `Upstreams/`, `tools/` for the 5 PascalCase names:
**zero matches.** The parent's mirror scripts do not name these repos. ✅

---

## (c) Exact ordered command sequence

> Run from repo root `/Volumes/T7/Projects/Lava`. Read-only verification steps
> are safe; mutating steps (`gh repo rename`, `glab`, `git remote set-url`,
> edits, `git submodule sync`) are the deliberate execution.

### Step 0 — Pre-flight backup + remote normalization (REQUIRED first)

```bash
# 0a. §9 hardlinked .git backup before any metadata mutation
cp -al .git ".git-backup-pre-snakecase-$(date +%Y%m%d-%H%M%S)/repo.git.mirror"

# 0b. Normalize the divergent submodule remotes to the §6.W 2-remote model.
#     (Do this for llm_orchestrator, llms_verifier, vision_engine which have
#      mixed-org / malformed remotes.)
for s in llm_orchestrator vision_engine; do
  git -C submodules/$s remote remove upstream  2>/dev/null || true
  git -C submodules/$s remote remove github    2>/dev/null || true
  git -C submodules/$s remote set-url origin git@github.com:vasic-digital/$(
    case $s in llm_orchestrator) echo LLMOrchestrator;; vision_engine) echo VisionEngine;; esac
  ).git
  git -C submodules/$s remote set-url --push origin git@github.com:vasic-digital/$(
    case $s in llm_orchestrator) echo LLMOrchestrator;; vision_engine) echo VisionEngine;; esac
  ).git
done
git -C submodules/llms_verifier remote remove upstream 2>/dev/null || true
git -C submodules/llms_verifier remote remove github   2>/dev/null || true
git -C submodules/llms_verifier remote set-url --add --push origin git@github.com:vasic-digital/LLMsVerifier.git # then drop the stray gitlab push
# Verify each ends up with exactly: origin=GitHub vasic-digital, gitlab=GitLab vasic-digital
for s in doc_processor llm_orchestrator llm_provider llms_verifier vision_engine; do
  echo "== $s =="; git -C submodules/$s remote -v
done
```

> **OPERATOR APPROVAL GATE 1** — confirm the normalized remote sets before any rename.

### Step 1 — Rename the remote repos (GitHub + GitLab), one repo at a time

GitHub `gh repo rename` and GitLab path-rename both install permanent HTTP/SSH
**redirects** from the old name, so existing clones keep fetching/pushing until
their remote URL is updated. (GitHub: documented redirect. GitLab: documented
"Repository path redirect" on project path change — verify the redirect is live
with the fetch in Step 3 before deleting any old reference.)

```bash
# doc_processor
gh repo rename doc_processor --repo vasic-digital/DocProcessor
glab repo edit vasic-digital/DocProcessor --path doc_processor     # path-rename on GitLab

# llm_orchestrator
gh repo rename llm_orchestrator --repo vasic-digital/LLMOrchestrator
glab repo edit vasic-digital/LLMOrchestrator --path llm_orchestrator

# llm_provider
gh repo rename llm_provider --repo vasic-digital/LLMProvider
glab repo edit vasic-digital/LLMProvider --path llm_provider

# llms_verifier
gh repo rename llms_verifier --repo vasic-digital/LLMsVerifier
glab repo edit vasic-digital/LLMsVerifier --path llms_verifier

# vision_engine
gh repo rename vision_engine --repo vasic-digital/VisionEngine
glab repo edit vasic-digital/VisionEngine --path vision_engine

# panoptic (GitHub PascalCase → lowercase; GitLab already lowercase)
gh repo rename panoptic --repo vasic-digital/Panoptic
# glab: GitLab already vasic-digital/panoptic — no path change needed; verify only.
```

> NOTE: `glab repo edit --path` is the GitLab path-rename verb. If the installed
> `glab` lacks `--path`, fall back to the GitLab REST PUT:
> `glab api --method PUT projects/vasic-digital%2FDocProcessor -f path=doc_processor`.
> Do NOT change the GitLab **display name** (`name`) — only the **path**.

> **OPERATOR APPROVAL GATE 2** — the HelixDevelopment-org duplicates
> (`HelixDevelopment/DocProcessor`, `…/LLMOrchestrator`, `…/LLMProvider`,
> `…/VisionEngine`) ALSO exist. Decide per-repo whether they are stale and
> should be renamed/archived too, or left alone. They are NOT referenced by
> Lava's current `origin` set after Step 0, so they are non-blocking — but
> §11.4.29 applies to them if they are live own-org repos.

### Step 2 — Point each submodule's local remotes at the new names

```bash
git -C submodules/doc_processor    remote set-url origin git@github.com:vasic-digital/doc_processor.git
git -C submodules/doc_processor    remote set-url gitlab git@gitlab.com:vasic-digital/doc_processor.git
git -C submodules/llm_orchestrator remote set-url origin git@github.com:vasic-digital/llm_orchestrator.git
git -C submodules/llm_orchestrator remote set-url gitlab git@gitlab.com:vasic-digital/llm_orchestrator.git
git -C submodules/llm_provider     remote set-url origin git@github.com:vasic-digital/llm_provider.git
git -C submodules/llm_provider     remote set-url gitlab git@gitlab.com:vasic-digital/llm_provider.git
git -C submodules/llms_verifier    remote set-url origin git@github.com:vasic-digital/llms_verifier.git
git -C submodules/llms_verifier    remote set-url gitlab git@gitlab.com:vasic-digital/llms_verifier.git
git -C submodules/vision_engine    remote set-url origin git@github.com:vasic-digital/vision_engine.git
git -C submodules/vision_engine    remote set-url gitlab git@gitlab.com:vasic-digital/vision_engine.git
git -C panoptic                    remote set-url origin git@github.com:vasic-digital/panoptic.git
git -C panoptic                    remote set-url github git@github.com:vasic-digital/panoptic.git
git -C panoptic                    remote set-url gitlab git@gitlab.com:vasic-digital/panoptic.git
```

### Step 3 — Update parent `.gitmodules` + sync

```bash
# Edit .gitmodules url= lines (PascalCase → snake_case):
#   vasic-digital/DocProcessor.git    -> vasic-digital/doc_processor.git
#   vasic-digital/LLMOrchestrator.git -> vasic-digital/llm_orchestrator.git
#   vasic-digital/LLMProvider.git     -> vasic-digital/llm_provider.git
#   vasic-digital/LLMsVerifier.git    -> vasic-digital/llms_verifier.git
#   vasic-digital/VisionEngine.git    -> vasic-digital/vision_engine.git
#   vasic-digital/Panoptic.git        -> vasic-digital/panoptic.git
# (use an editor / Edit tool; paths in [submodule "..."] headers stay as-is —
#  they already match the snake_case on-disk paths.)

git submodule sync --recursive
# Verify the redirect + new URL both resolve:
for s in doc_processor llm_orchestrator llm_provider llms_verifier vision_engine; do
  echo "== $s =="; git -C submodules/$s ls-remote origin -h 2>&1 | head -1
done
```

### Step 4 — Fix the broken/stale replace path (independent bug)

```bash
# submodules/llm_orchestrator/go.mod:18
#   replace digital.vasic.llmprovider => ../LLMProvider
# ->
#   replace digital.vasic.llmprovider => ../llm_provider
# Commit inside the llm_orchestrator repo, push to both mirrors.
```

### Step 5 — Submodule-internal upstream/doc updates (each in its own repo)

For each of the 5 dep repos (and panoptic), inside the submodule:
- update `upstreams/*.sh` push URLs to the new lowercase repo name,
- update `helix-deps.yaml` header comment + any `ssh_url`/`org` field,
- update README/docs PascalCase repo references,
- commit + push to `origin` (GitHub) and `gitlab` (GitLab) — §6.W parity.

### Step 6 — helixqa upstream realignment (optional but closes the root cause)

Once the repos are snake_case, the path-side fix (`../doc_processor` etc.)
that lives on `lava-pin/2026-06-08-android-executor` can be merged into
helixqa `main` (a normal PR, no force-push). After that the Lava helixqa pin
can return to tracking `main` and the §11.4.29 OWED item + the lava-pin
workaround both close. Update `docs/CONTINUATION.md` §0/§3 accordingly.

### Step 7 — Parent commit (Lava)

In ONE Lava-parent commit (branch off master first per commit policy):
- `.gitmodules` url updates,
- the 6 submodule gitlink pins (if any submodule SHA advanced from Step 4/5),
- `docs/CONTINUATION.md` updates (pin table rows, §11.4.29 OWED → DONE, helixqa pin note),
- then push to GitHub + GitLab, verify §6.C 2-mirror convergence.

---

## (d) Risk & sequencing assessment

### Independent vs cascading

- **The repo renames are mutually independent** — no module-path changes, so
  renaming `doc_processor` does not ripple into `llm_orchestrator` etc. They
  can be done in any order.
- **The only intra-Go cascade** is `llm_orchestrator → llm_provider` via the
  `replace ../LLMProvider` line — but that is a *path* fix (Step 4), not a
  *module-id* change, and is already correct on the helixqa side. Fix it
  regardless; it is currently broken on case-sensitive volumes.
- **Sequencing that matters:** Step 0 (remote normalization) MUST precede
  Step 1 (rename) so the rename targets are unambiguous; Step 1 MUST precede
  Steps 2–3 (local URL updates rely on the new names existing + redirecting).
  Within Step 1, GitHub-then-GitLab order is interchangeable.

### Redirect safety

- GitHub `gh repo rename` → permanent redirect; old clones keep working. LOW risk.
- GitLab path-rename → documented path redirect; **verify with a live fetch
  (Step 3) before relying on it.** MEDIUM risk (display name vs path confusion;
  only change `path`, never `name`).

### What MUST be operator-approved (§6.T.3 / protected-branch boundary)

1. **GATE 1** — the Step-0 remote-set normalization (removes `upstream`/`github`
   remotes; rewrites `origin` push URLs). Confirm before proceeding.
2. **GATE 2** — disposition of the duplicate `HelixDevelopment/*` repos
   (rename/archive/leave). §11.4.29 applies if they are live own-org repos.
3. **Any `glab api --method PUT`** path-rename fallback — a write API call;
   needs explicit approval.
4. **helixqa `main` merge (Step 6)** — touches a protected branch on an
   own-org repo via PR. Normal PR, NO force-push; still operator-approved
   per §6.T.3 because it changes a protected branch.
5. **Panoptic GitHub rename** — confirm panoptic is in-scope for §11.4.29 (it
   is an own-org `vasic-digital` repo; GitLab side is already lowercase).

### Non-risks (explicitly verified)

- **No `go.sum` churn** — module identifiers unchanged. No `go mod tidy` needed
  for the rename itself (Step 4's path fix does not change checksums either).
- **No Go import-path edits** — zero `github.com/owner/Repo` import references.
- **No parent CI/script edits** — `scripts/`, `Upstreams/`, `tools/` name none
  of these repos.
- **No force-push anywhere** — all operations are renames, URL rewrites, normal
  commits, and a normal PR. §6.T.3 satisfied.

### Recommended execution order (summary)

Step 0 (backup + normalize, GATE 1) → Step 1 (rename GitHub+GitLab, GATE 2) →
Step 2 (submodule remote URLs) → Step 3 (.gitmodules + sync + verify) →
Step 4 (llm_orchestrator path fix) → Step 5 (submodule-internal doc/upstream
updates) → Step 6 (helixqa main realignment, GATE for protected branch) →
Step 7 (single Lava-parent commit + 2-mirror push).
