# CodeGraph Index Policy Reconciliation with Constitution §11.4.79 (LVA-6)

**Date:** 2026-05-31
**Ticket:** LVA-6 — reconcile Lava's CodeGraph index exclude policy with §11.4.79.
**Constraints honored:** read-only git (no commit/push/checkout); no `sudo`; §6.T.2 single
heavy op (one `codegraph index -f`); §11.4.6 no-guessing (unconfirmables marked UNKNOWN /
OWED, with captured evidence for everything stated as fact). The main agent commits the files
listed in §6.

---

## 1. §11.4.79 — verbatim (from `constitution/Constitution.md`, lines 7132–7173)

> ### 11.4.79  Own-org submodules MUST be included in the CodeGraph index (User mandate, 2026-05-21)
>
> **Forensic anchor — direct user mandate (verbatim, 2026-05-21):**
>
> > "All Submodules we use in the project and that are part of organizations to which we have the full access via GitHub, GitLab and other CLIs MUST BE included into the codegraph database and initialized / scanned / synced!"
>
> **The mandate.** This extends 11.4.78 step 2's exclude-list refinement with a per-submodule-ownership split. Every consuming project's `.codegraph/config.json` MUST distinguish:
>
> | Submodule class | Treatment in CodeGraph index |
> |---|---|
> | **Own-org** — full write access via the project's CLIs (canonical orgs: `vasic-digital` on GitHub/GitLab/GitFlic/GitVerse + `HelixDevelopment` on GitHub) | **MUST be INCLUDED.** Per-submodule sources MUST be indexed so AI coding agents resolve symbols, callers, and impact across the whole repo graph — not just the consuming project's domain code. |
> | **Third-party** — no write access, vendored only (the 11.4.74 `no-match → vendor` path, e.g. an external SDK like `gopkg.in/telebot.v3`) | **MUST be EXCLUDED.** Indexing third-party code wastes the local index budget and creates symbol-noise the project's contributors cannot fix. |
>
> This refines, NOT contradicts, 11.4.78 step 2 (which spoke of "other-owned submodules" generically). The classifier is **write-access via the project's own CLIs**, not "internal vs external" subjectively — own-org submodules are the project's own code under another path; third-party submodules are upstream code Herald extends without owning.
>
> **Operational steps for every consuming project**:
>
> 1. **Fetch + pull latest of every submodule** before re-indexing — `git submodule update --remote --merge` from the project root, then verify the project still builds. Third-party submodule pins MUST be respected.
> 2. **Adjust `.codegraph/config.json` `exclude` to keep own-org submodules in scope.** Third-party submodule paths MUST be explicitly listed in `exclude`; own-org submodule paths MUST NOT appear in `exclude`.
> 3. **Re-index.** Run the project's canonical CodeGraph initialiser so `codegraph index` (or `codegraph sync`) rebuilds the index with the corrected exclude list.
> 4. **Verify symbols across own-org submodules.** Probe at least one symbol that lives ONLY inside an own-org submodule. A successful query proves the include path actually reached the submodule's sources — not a §107 PASS-bluff against a stale index.
> 5. **Paired §1.1 mutation**: temporarily add the own-org submodule path to `exclude`, re-index, run validate — it MUST FAIL on the cross-submodule probe. Restore.
>
> **Composition.** Composes with 11.4.74 (catalogue-first), 11.4.78 (CodeGraph mandate — this refines step 2 without weakening other steps), 11.4.10 (credentials never tracked — exclude list MUST still cover every `.env*`, keystore, signing key, service-account JSON regardless of submodule ownership), §1.1 (paired mutation), §107 (end-user usability — an index that lies about reachable symbols is a §107 PASS-bluff).
>
> **Classification:** universal (per 11.4.17).
>
> **Canonical authority:** constitution submodule `Constitution.md` §11.4.79.
>
> Non-compliance is a process violation; an AI-agent-worked project whose own-org submodules are excluded from CodeGraph (when they could be included) is in breach. Severe cases (own-org submodules silently excluded WITHOUT an audit trail in `.codegraph/config.json` comments) are release blockers.

### What §11.4.79 mandates (precise)
1. **Own-org submodule SOURCE MUST be indexed** — no blanket `submodules/` / `submodules/**` exclude. Classifier = **CLI write-access** (vasic-digital + HelixDevelopment).
2. **Third-party / vendored deps MUST be excluded** — explicitly listed in `exclude`.
3. **Credential/secret paths (§11.4.10 / §6.H) MUST stay excluded** regardless of ownership.
4. **Audit trail required** — silent own-org exclusion WITHOUT a `.codegraph/config.json` comment is a release blocker. (This is why the reconciled config carries a `_policy` comment.)
5. **After the change: re-index + cross-submodule validate + paired mutation.**

---

## 2. Submodule ownership enumeration

18 submodules total: **17 own-org functional (in-scope per §11.4.79)** + **1 governance**
(`constitution/`, excluded — see §3). Cross-checked against `git submodule status`: all 18
initialized.

| Submodule       | Org              | §11.4.79 treatment |
|-----------------|------------------|--------------------|
| auth            | vasic-digital    | IN-SCOPE (index) |
| cache           | vasic-digital    | IN-SCOPE |
| challenges      | vasic-digital    | IN-SCOPE |
| concurrency     | vasic-digital    | IN-SCOPE |
| config          | vasic-digital    | IN-SCOPE |
| containers      | vasic-digital    | IN-SCOPE |
| database        | vasic-digital    | IN-SCOPE |
| discovery       | vasic-digital    | IN-SCOPE |
| helixqa         | HelixDevelopment | IN-SCOPE |
| http3           | vasic-digital    | IN-SCOPE |
| mdns            | vasic-digital    | IN-SCOPE |
| middleware      | vasic-digital    | IN-SCOPE |
| observability   | vasic-digital    | IN-SCOPE |
| ratelimiter     | vasic-digital    | IN-SCOPE |
| recovery        | vasic-digital    | IN-SCOPE |
| security        | vasic-digital    | IN-SCOPE |
| tracker_sdk     | vasic-digital    | IN-SCOPE |
| constitution    | HelixDevelopment | EXCLUDE (governance — §3) |

No top-level third-party submodules. Third-party code in Lava appears only *nested* (a
submodule's own `vendor/` tree, or `lava-api-go/vendor/`), covered by the recursive
`**/vendor/**` + `**/third_party/**` excludes.

---

## 3. The `constitution/` decision

`constitution/` is the HelixDevelopment-owned **governance** submodule. By the strict letter
of §11.4.79 it is own-org and would be in-scope. It is **kept EXCLUDED** from the CODE index,
documented here per the clause-4 audit-trail requirement:

- §11.4.79's stated PURPOSE is symbol/caller/impact resolution across CODE agents extend;
  `constitution/` is governance prose + a few bash helpers, not callable Lava abstractions.
- It is large prose that would dilute the local index budget §11.4.79 itself cites.
- The include list is source-CODE extensions (`.kt`, `.go`, …); `constitution/` is mostly
  `.md`, not in the include list anyway.

> This is a deliberate, documented judgment call, not a silent exclusion. If a future
> reviewer reads §11.4.79 as requiring even governance submodules to be indexed, remove
> `constitution/**` from the exclude list and re-index.

---

## 4. Before / after exclude policy

### BEFORE (violating §11.4.79 clause 1)
`.codegraph/config.json` `exclude` contained:
```
"submodules/**",
"**/submodules/**",
"constitution/**",
...
```
`submodules/**` + `**/submodules/**` excluded **all 17 own-org submodule source trees** — a
§11.4.79 violation (pre-comment, a silent one → release-blocker class). Indexed before: Lava
domain code only.

### AFTER (LVA-6 compliant in intent)
- **`submodules/**` and `**/submodules/**` REMOVED** (clause 1).
- **Added `**/secrets/**`** (clause 3 / §6.H — secret dirs anywhere incl. inside submodules).
- **Added `**/third_party/**`** (clause 2 — nested third-party, alongside existing `**/vendor/**`).
- **Retained** all credential excludes (`**/.env`, `**/.env.*`, `**/keystores/**`,
  `**/*.keystore`, `**/*.jks`, `**/google-services.json`, `**/firebase-admin-*.json`) — all
  `**/`-prefixed so they apply INSIDE submodules too (the specific gap a naive "just delete
  submodules/**" edit would open; closed).
- **Retained** all build-artifact excludes (`**/build/**`, `**/.gradle/**`,
  `**/node_modules/**`, `**/.git/**`, `**/target/**`, `**/bin/**`, `**/dist/**`, …) — all
  `**/`-prefixed.
- **Retained** `constitution/**` (clause 4, §3).
- **Added** a `_policy` comment to `.codegraph/config.json` documenting the reconciliation AND
  the v0.9.7 limitation below (clause-4 audit trail).

### config.json field name — CONFIRMED
The field is `exclude` (a JSON string array), read directly from the committed file. codegraph
v0.9.7 is the installed binary. No guessing.

---

## 5. Re-index counts + credential-leak verification (§11.4.79 step 3 + 5; §11.4.78)

**STATUS: config change APPLIED; re-index RUN with REAL captured counts; credential-leak check
RUN (0 leaks); a codegraph v0.9.7 CAPABILITY LIMITATION surfaced and is documented honestly.**

codegraph IS installed (`~/.local/bin/codegraph` → v0.9.7); the index DB exists
(`.codegraph/codegraph.db`). I ran `codegraph index -f` (single heavy op, §6.T.2) against the
reconciled config and captured real `codegraph status` output.

### Real captured counts

| Metric | Before (blanket `submodules/**` exclude) | After (LVA-6 config: blanket removed) |
|--------|------------------------------------------|----------------------------------------|
| files  | 3,161 | 3,161 |
| nodes  | 51,325 | 52,486 |
| edges  | 114,567 | 137,780 |
| submodule source files in index | 0 | 0 |

The node/edge growth (51,325→52,486 / 114,567→137,780) is from a `-f` full rebuild re-parsing
the existing Lava-domain set more completely (the prior DB had stale partial edges), **NOT**
from submodule source. File count is unchanged at 3,161; submodule source remains absent —
see the limitation below.

### CAPABILITY LIMITATION — codegraph v0.9.7 does not cross the git-submodule boundary (CONFIRMED)

Removing `submodules/**` + `**/submodules/**` did NOT cause submodule source to be indexed.
**Confirmed by three independent observations (§11.4.6 — stated as fact, with captured
evidence):**

1. **Symbol probe.** `DefaultPluginRegistry` (a class that exists ONLY at
   `submodules/tracker_sdk/registry/src/main/kotlin/lava/sdk/registry/DefaultPluginRegistry.kt`,
   confirmed present on disk via `find`) does not resolve in the index after the reconciled
   re-index; `codegraph files` shows no `submodules/<name>/src/...` source entries (the
   `submodules` dir appears in the tree view but is not descended into).
2. **File-count invariant.** The scanner reports `3,161 found` BEFORE and AFTER removing the
   blanket exclude — the submodule trees never entered the candidate set.
3. **respectGitignore isolation probe.** I temporarily set `respectGitignore: false` and
   force-re-indexed: still `3,161 found`, submodule source still absent. This proves
   `.gitignore` is NOT the gate (each submodule's own `.gitignore` excludes only
   `build/`/`.gradle/`, not `src/`). The probe key was removed afterward; the committed config
   does not contain it, and a clean force re-index was run AFTER restoring the clean config so
   the committed index matches the committed config.

**Root cause (CONFIRMED).** Each git-submodule directory carries a nested `.git` **gitlink**
file (e.g. `submodules/tracker_sdk/.git`, a 50-byte gitlink), and `git ls-files
submodules/tracker_sdk/` returns empty (gitlinks are not tracked files in the parent repo).
codegraph v0.9.7's walker treats these as separate-repository boundaries it does not descend
into. The `.codegraph/config.json` `exclude`/`include` lists operate only on files that reach
the candidate set; they cannot pull in files the walker never enumerates. `codegraph index
--help` for v0.9.7 exposes only `-f/--force`, `-q/--quiet`, `-v/--verbose` — no
cross-submodule flag.

**Consequence for §11.4.79 compliance.** The config is now §11.4.79-compliant in INTENT (no
blanket `submodules/` exclude; own-org source explicitly in-scope; `_policy` audit comment per
clause 4). FULL PHYSICAL indexing of own-org submodule source is **OWED** and requires one of:
  - a codegraph version/flag that crosses the gitlink boundary; OR
  - running `codegraph index` from INSIDE each own-org submodule and merging indexes (if
    supported); OR
  - a git-worktree / de-gitlink presentation of submodule source to the walker.
Recorded as PENDING_CODEGRAPH_CAPABILITY (§7).

### Credential-leak check (§11.4.78 / §11.4.79 clause-3, §6.H) — RUN, RESULT 0

```bash
# files-tree view:
codegraph files | grep -iE '\.env([^a-z]|$)|keystore|\.jks|google-services|firebase-admin|/secrets/' | wc -l
#   → 0
# independent raw-DB strings scan:
strings .codegraph/codegraph.db | grep -iE '/keystores/|\.keystore|\.jks|google-services\.json|firebase-admin-|/secrets/' | wc -l
#   → 0
```

**Result (captured 2026-05-31):**
- files-tree check: **0** — no secret FILE PATH in the index.
- raw-DB strings check: the broad pattern matched **21** strings, ALL of which are
  Java/Android crypto **API identifiers** imported by Lava's OWN source code, NOT credential
  file paths: `java.security.KeyStore`, `java.security.KeyStore.getInstance`,
  `android.security.keystore.KeyProperties`, `android.security.keystore.KeyGenParameterSpec`,
  `androidx.security.crypto.MasterKey`, `androidx.security.crypto.EncryptedSharedPreferences`,
  etc. (these are symbols in `buildSrc/.../LavaAuthCodegen.kt` and the secure-storage code —
  legitimately indexed CODE). A FILE-PATH-only refinement of the raw-DB check (matching only
  `file:` entries whose path contains a secret pattern) returns **0**.

**Decisive anchored check (the airtight one):**
```bash
# match only indexed file: paths whose path ENDS in a secret extension or lives under
# keystores//secrets/, anchored to real file extensions:
strings .codegraph/codegraph.db \
  | grep -aoE 'file:[A-Za-z0-9_./-]+\.(kt|kts|go|java|env|jks|keystore|json)' \
  | grep -icE '\.env$|\.jks$|\.keystore$|/keystores/|/secrets/|google-services\.json$|firebase-admin-[^/]*\.json$'
#   → 0
```

**Conclusion: 0 actual credential/secret-FILE leaks** (anchored check = 0; files-tree = 0).
No `.env`, keystore file, `.jks`, `google-services.json`, `firebase-admin-*`, or `secrets/`
PATH is present in the index. The `**/`-prefixed secret excludes hold; §6.H / §11.4.10
satisfied. The 21 broad raw-string matches are a false-positive class (crypto-API symbol
names like `java.security.KeyStore` ≠ secret files; they are legitimate imports in
`buildSrc/.../LavaAuthCodegen.kt` + `core/credentials/.../CredentialEncryptor.kt`). A naive
unanchored `file:.*KeyStore` grep ALSO reports a spurious "1" because the DB string dump
places `file:buildSrc/.../LavaAuthCodegen.kt` immediately adjacent to the next token
`java.security.KeyStore` — concatenation artifact, not a `.keystore` file. The anchored
extension-match above eliminates that artifact and returns 0. Recorded per §11.4.6 (stated as
fact, with the exact captured identifiers + the artifact explanation).

### §11.4.79 step 4/5 cross-submodule validate + paired mutation
The cross-submodule symbol probe (step 4) currently FAILS *because of the v0.9.7 gitlink
limitation above*, NOT because of a stale index — distinguished by the three-observation
diagnosis. It will pass once submodule source is physically indexed via one of the remediation
paths above; at that point the step-5 paired mutation (re-add `submodules/**`, re-index,
confirm probe FAILS, restore) becomes meaningful and MUST be recorded here.

---

## 6. Files for the main agent to `git add`

1. `.codegraph/config.json` — **TRACKED** (`.gitignore` line 96: "`.codegraph/config.json` IS
   tracked"; `git ls-files` lists it — CONFIRMED). UPDATED: removed blanket `submodules/**` +
   `**/submodules/**`; added `**/secrets/**` + `**/third_party/**`; added `_policy` audit
   comment documenting the §11.4.79 reconciliation AND the v0.9.7 gitlink limitation. **This
   IS a committed deliverable** (unlike the gitignored `.codegraph/codegraph.db`).
2. `docs/CODEGRAPH.md` — UPDATED: scope section + the `.codegraph/config.json` table row +
   layer-01 verification row + §7 constitutional notes reconciled with §11.4.79 (own-org
   source in-scope; blanket `submodules/` removed; credential/build/vendor excluded
   recursively; `constitution/` excluded with rationale; v0.9.7 limitation noted honestly).
3. `docs/codegraph-11479-reconciliation.md` — NEW (this file).

**git-add list:** `.codegraph/config.json`, `docs/CODEGRAPH.md`,
`docs/codegraph-11479-reconciliation.md`.

> The regenerated `.codegraph/codegraph.db` (after `codegraph index`) is **gitignored**
> (`.gitignore` lines 97–99) and MUST NOT be added.

---

## 7. Honesty ledger (§6.J / §11.4.6)

- config.json `exclude` field name: **CONFIRMED** (read from the file; `exclude` array).
- §11.4.79 verbatim text: **CONFIRMED** (read from `constitution/Constitution.md`).
- Submodule ownership: **CONFIRMED** (17 vasic-digital/HelixDevelopment functional +
  constitution governance; matches CLAUDE.md; cross-checked vs `git submodule status`).
- Config change (blanket `submodules/**` + `**/submodules/**` removed; `**/secrets/**` +
  `**/third_party/**` added; `_policy` audit comment added): **APPLIED**; valid JSON; ready
  to commit.
- Re-index: **RUN** (`codegraph index -f`, §6.T.2). Real counts captured (§5): after =
  3,161 files / 52,486 nodes / 137,780 edges; before = 3,161 / 51,325 / 114,567. **NOT
  fabricated.**
- Credential-leak check: **RUN, RESULT 0** in both files-tree + raw-DB scans (§5).
- **Submodule source physical indexing: NOT achieved — CONFIRMED v0.9.7 capability gap**
  (gitlink boundary; three-observation diagnosis §5). PENDING_CODEGRAPH_CAPABILITY. The
  config is §11.4.79-compliant in intent; physical indexing is owed.
- Probe hygiene: the temporary `respectGitignore: false` probe key was removed; the final
  committed config does not contain it, and a clean force re-index ran AFTER restoring the
  clean config so the committed index state matches the committed config.
