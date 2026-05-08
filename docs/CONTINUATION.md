# Lava — Work Continuation Index

**Purpose:** this single file is the source-of-truth for resuming the
project's work across any CLI session. A fresh agent reads this file
first, locates the active phase, and continues from there. Everything
ahead of HEAD is recorded; everything behind HEAD is in `git log`.

**Maintenance:** every release tag, every phase completion, every
operator directive that changes scope MUST update this file in the
same commit so the index stays trustworthy. Stale state in this file
is itself a §6.J spirit issue — the file claims a guarantee, the
repo has drifted, the agent acts on the claim.

> **Last updated:** 2026-05-08 20:10 UTC, after Phase 1 of the
> Full Anti-Bluff Proofing Plan — all 264 test files audited for
> §6.J compliance (zero violations), 5 bluff-hunt mutations confirmed
> failing, all 22 Challenge Tests now have falsifiability rehearsal
> KDoc protocols. Uncommitted RuTrackerDescriptor bluff mutation
> fixed. Now executing Phase 2 (dead code cleanup, known issue fixes).

> **§6.S binding:** this file is constitutionally load-bearing per
> root `CLAUDE.md` §6.S. Every commit that changes phase status,
> lands a new spec/plan, bumps a submodule pin, ships a release
> artifact, discovers or resolves a known issue, or implements an
> operator scope directive MUST update this file in the SAME
> COMMIT. The §0 "Last updated" line MUST track HEAD. Stale
> CONTINUATION.md is itself a §6.J spirit issue under §6.L's
> repeated mandate. `scripts/check-constitution.sh` enforces
> presence + structure (§0, §7, §6.S clause + inheritance).

---

## 0. Quick orientation (read this first)

| Surface | Current state | Pin |
|---|---|---|
| Lava parent on master | 2 mirrors (GitHub + GitLab) at current HEAD | (current SHA) |
| API on thinker.local | running 2.3.2 (build 2302) (healthy) | container `lava-api-go-thinker` |
| Android Firebase | 1.2.13 (1033) distributed to testers | `lava-vasic-digital` Firebase project |
| 16 vasic-digital submodules | all pushed | see §3 below |

**All 6 parent-decomposition phases complete.** The original TODO spec
(`docs/todos/Lava_TODOs_001.md`) has been fully implemented across
Phases 1-6 with the Yole+Boba 8-palette theme system as the final
deliverable. Git tags have NOT been cut — see §2 for the remaining
gate steps.

---

## 1. What's DONE (for context — do not re-do)

### Phase 0 — Stabilize Build & Enforce §6.W (2026-05-08)

**Status: COMPLETE. Commits: 9ed7bca (OkHttp fix), 55a702c (Phase 0).**

| Task | Status |
|------|--------|
| Fix `:core:auth:impl` FakePreferencesStorage.getDeviceId() | ✓ |
| Commit 16 submodule constitution propagation (§6.U/V/W) | ✓ |
| Remove GitFlic, GitVerse, upstream remotes | ✓ |
| Fix `origin` remote to GitHub+GitLab only | ✓ |
| Update `scripts/tag.sh` DEFAULT_REMOTES to 2-mirror | ✓ |
| Delete `Upstreams/GitFlic.sh`, `GitVerse.sh` | ✓ |
| Add GitLab remotes to 7 submodules lacking them | ✓ |
| Push parent to both mirrors + verify SHA convergence | ✓ (55a702c on both) |

### Phase 1 — Full Anti-Bluff Audit (2026-05-08)

**Status: COMPLETE.** All 264 test files audited for §6.J clause 3 compliance (zero violations). 5 bluff-hunt mutations confirmed to cause test failures. All 22 Challenge Tests now have formal falsifiability rehearsal protocols in KDoc. Uncommitted RuTrackerDescriptor bluff mutation from Phase 1.4 reverted.

| Task | Status | Evidence |
|------|--------|----------|
| 1.1 Inventory all test files | ✓ | 264 files identified |
| 1.2 Random 5 falsifiability rehearsals | ✓ | 5/5 fail when production mutated |
| 1.3 §6.J clause 3 compliance scan | ✓ | `.lava-ci-evidence/compliance/2026-05-08-phase1-dot3-compliance-report.md` |
| 1.4 Bluff hunt evidence | ✓ | `.lava-ci-evidence/phase1-dot4-bluff-hunt-report.md` |
| 1.5 Forbidden pattern check | ✓ | Zero violations documented |
| 1.6 Challenge KDoc falsifiability protocols | ✓ | All 22 Challenges have formal protocols |

### Parent-decomposition Phases 1-6 — ALL SHIPPED

Status: **all 6 phases complete. 7 Firebase distributions delivered
(1.2.8-1028 through 1.2.13-1033). API versions 2.2.0-2200 through
2.3.2-2302. Git tags have NOT been cut** (see §2).

| Phase | What shipped | Key commits |
|---|---|---|
| 1 | API auth + security (16 sub-phases) | `8192403` .. `e9470812` |
| 2a+2b | Multi-provider streaming search + 6 providers all apiSupported=true | `19dbff7` .. `c4ec6c4` |
| 3 | Onboarding wizard, bug-fixed + 16 anti-bluff ViewModel tests | `4dd6ea0` |
| 4 | Sync expansion: device identity, Sync Now, 4 sync categories | `2060d9a` .. `0d57170` |
| 5 | UI/UX polish: multi-provider header, color themes (Ocean/Forest/Sunset), About dialog, credentials redesign, result filtering, nav-bar audit | `3fc66b6` .. `5879a85` |
| 6 | Crashlytics tracking, distribution prep, infrastructure fixes | (shipped across multiple commits) |

### Additional deliveries after Phase 3 bug-fix (post-4dd6ea0)

| What | Details |
|---|---|
| Docker auth env vars | `docker-compose.yml` passes LAVA_AUTH_* to api-go container (commit `ab6325f`) |
| Yole+Boba 8-palette theme system | Replaced red default with Yole semantic color foundation + 8 Boba-project accent palettes. `PaletteContractTest` enforces contract. |
| PaletteTokens | Token system defining 8 named palettes, `PaletteContractTest` ensures all tokens resolve. |
| AppColors refactor | 469→150 lines, clean separation of palette tokens from semantic roles |
| Firebase distributions | 1.2.8-1028 → 1.2.13-1033 (7 incremental releases) |

### Operator-visible deliverables

- **API**: `https://thinker.local:8443/{health,ready}` returns
  `{"status":"alive"}` / `{"status":"ready"}`. Auth gate fails-closed
  with `401 {"error":"unauthorized"}` on missing `Lava-Auth` header.
  `GET /v1/search?q=...&providers=...` SSE endpoint. Version 2.3.2 (2302).
- **APK**: 1.2.13 (1033) on Firebase App Distribution under project
  `lava-vasic-digital`. Yole+Boba 8-palette theme system, onboarding
  wizard, sync expansion, all 6 providers active.
- **Color themes**: Yole+Boba 8 palettes selectable in Settings. |

---

## 2. What's BLOCKED ON OPERATOR ACTION (cannot proceed autonomously)

These items need the operator's environment / hardware / decisions
that an agent cannot make alone. They are NOT autonomous-resumable;
they require the operator to do the steps OR explicitly grant
authorization.

### 2.1 Release-tagging chain (versions 1.2.13-1033 + 2.3.2-2302)

Current HEAD (e9b7f89) has no git tags. The last tags are
`Lava-Android-1.1.3-1013` and `Lava-API-Go-2.0.7-2007`. Tagging
requires the full §6.I evidence pack.

| Step | Command | Blocker |
|---|---|---|
| §6.I emulator matrix gate | `bash scripts/run-emulator-tests.sh --avds=Pixel_API28,Pixel_API30,Pixel_API34,Pixel_APIlatest,Tablet_API34 --tests=lava.app.challenges --concurrent=1 --output=.lava-ci-evidence/Lava-Android-1.2.13-1033/real-device-verification.md` | Requires real Android emulator/device hardware |
| Real-device manual smoke-test | login + search + browse + download on a Pixel-class device against thinker.local | Operator hands-on |
| Tag Android + API | `bash scripts/tag.sh` | Refuses without matrix evidence |
| Pepper rotation | Per `docs/RELEASE-ROTATION.md`: append new pepper before tagging | Manual |

### 2.2 Operator's `.env` rotation hygiene

The first auto-rotation generated:
- `LAVA_AUTH_CURRENT_CLIENT_NAME = android-1.2.13-1033`
- `LAVA_AUTH_HMAC_SECRET = <auto-generated 32 bytes>` (gitignored .env)
- `LAVA_AUTH_OBFUSCATION_PEPPER = <auto-generated 32 bytes>` (gitignored .env)

Per `docs/RELEASE-ROTATION.md`: every release rotates pepper. The
pepper history at `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/pepper-history.sha256`
contains all 7 distribution cycles (1.2.8 through 1.2.13). The next
release (tag) MUST start by appending a new pepper. Re-use is refused.

---

## 3. Submodule pin index (for reference)

All 16 `vasic-digital` submodules are at HEAD on `main`. Bumping a
pin is a deliberate operator action; never auto-update.

| Submodule | Pin | Mirrors | Notes |
|---|---|---|---|---|
| `Auth` | `6213c61` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Cache` | `ea3b376` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Challenges` | `f7d336d` | GitHub + GitLab | merged P1.5 + §§6.R-6.W inheritance |
| `Concurrency` | `5b5a858` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Config` | `45a915b` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Containers` | `84c381c` | GitHub + GitLab | merged P1.5-WP8 + §§6.R-6.W inheritance |
| `Database` | `1ce46f9` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Discovery` | `5348c7d` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `HTTP3` | `7fec2d8` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Mdns` | `e7839fa` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Middleware` | `c877ef9` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Observability` | `aff0931` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `RateLimiter` | `1127b11` | GitHub + GitLab | pkg/ladder primitive + §§6.R-6.W inheritance |
| `Recovery` | `b4b8771` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Security` | `d45b458` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |
| `Tracker-SDK` | `3d31ea3` | GitHub + GitLab | §§6.R, 6.S, 6.U, 6.V, 6.W inheritance |

**Internal-to-submodule nested submodules:** `Submodules/Challenges` has a nested `Panoptic` submodule that Challenges' own Go code in `pkg/panoptic/` depends on. Per the operator's "no submodules at multiple depths in the project root" directive, Lava itself has only flat root-level Submodules — but Challenges (consumed as a black-box vasic-digital submodule) self-manages its own internal dependencies, including Panoptic. This is acceptable: the depth restriction applies at Lava's tracking surface, not recursively into each submodule's internal organization.

---

## 4. PHASES 2-6 OF THE PARENT DECOMPOSITION — ALL COMPLETE

All phases 2 through 6 have been implemented and shipped via Firebase
App Distribution (7 versions: 1.2.8-1028 through 1.2.13-1033). The
original TODO specs have been fully delivered. See §1 above for the
summary of each phase.

No further parent-decomposition work remains. Future work should
either (a) cut git tags (requires §6.I evidence pack), (b) start new
feature work via the brainstorming → spec → plan → execute cycle, or
(c) address remaining open items (emulator matrix, Challenge Tests).

---

## 4.5 Known issues + bugs (carried forward)

These are real defects discovered during Phase 1's first deploy. None
are release-blocking — the API serves and the auth gate fails-closed
correctly — but each is a real Phase-2-or-later work item.

### 4.5.1 `/api/v1/search` route resolution

**Discovered:** 2026-05-06.

**Symptom (historical):** path `/v1/search` returned 404 because the
route was registered as `/v1/:provider/search`, not `/v1/search`.
Phase 2 was supposed to add per-provider routes and flip
`apiSupported = true`. Phase 2+2b commits shipped 6-provider support
— **needs operator verification** that the route now resolves
correctly against the running API (`curl -fsSk
'https://thinker.local:8443/v1/rutracker/search?q=test'`).

### 4.5.2 `/health` intermittent timeout + quic-go UDP buffer warning

**133s timeout** (observed 2026-05-06 once): likely HTTP/3→HTTP/2
negotiation stall at the curl layer. Container HEALTHCHECK via
`healthprobe` never exhibited this.

**UDP buffer warning** (persistent at boot):
```
failed to sufficiently increase receive buffer size
(was: 208 kiB, wanted: 7168 kiB, got: 416 kiB).
```
**Fix:** `sudo sysctl -w net.core.rmem_max=7340032` on the host.

### 4.5.3 Internet Archive / gutenberg — provider status

Phase 2b (2026-05-07) flipped `apiSupported=true` on all 6 providers.
The α-hotfix "hide" of Internet Archive is superseded.
**Status unclear:** verify whether the one-time unsupported-provider
dialog for existing installs was implemented. Check `MainActivity` +
`Preferences` for `unsupportedProviderDialogShown` flag.

### 4.5.4 Challenges + emulator: C17-C22 remain unexecuted

Challenge Tests C17 (archiveorg search), C18 (gutenberg), C19
(multi-provider), C20 (full onboarding flow), C21 (Welcome screen),
C22 (anonymous provider auto-advance) are written and compile but
require the §6.I emulator matrix to execute.

### 4.5.5 Submodules/Challenges has untracked `Containers/` dir leftover from upstream merge

**Discovered:** 2026-05-06 after merging upstream Challenges
into Lava-side post-§6.R-inheritance. The merged-in commit
`abe62cb chore(P1.5-T03.02): dedup Containers in Challenges`
deleted the `Containers/` subdir from Challenges (canonical at
meta-repo root), but the local clone still has the directory on
disk because git doesn't auto-delete files that were already
present locally before the dedup commit's merge.

**Symptom:** `git status` inside Submodules/Challenges shows
`?? Containers/` (untracked). Doesn't affect builds; cosmetic noise.

**Action:** operator runs `rm -rf Submodules/Challenges/Containers`
once. Not blocking; tracked here so a future cleanup pass picks it
up.

### 4.5.6 Mirror model reduced to 2-mirror (GitHub + GitLab) per §6.W

**Resolved 2026-05-08.** Per constitutional clause §6.W, only GitHub and
GitLab are permitted as Git remotes. GitFlic and GitVerse are removed from
all remotes. The 4-mirror model is replaced by 2-mirror. All submodules
now target GitHub + GitLab.

### 4.5.7 `Engine.Ktor` enum cascade tail (post-Ktor cleanup low priority)

**Symptom:** the `Engine.Ktor` enum value lingers in client-side
exhaustive `when` branches. It's dead code — the Ktor :proxy was
deleted in 2.0.12. Removal would require an Android version bump
for cosmetic-only cleanup.

**Action:** Phase 5 (UI/UX polish) is the natural place. Tracked.

### 4.5.8 `Endpoint.Mirror` LAN-IP routing branch (post-Ktor cleanup low priority)

Same shape as 4.5.8. Dead path post-Ktor; no triggering producer in
tree. Phase 5 cleanup target.

### 4.5.9 `docs/todos/` directory is untracked

**Discovered:** persistent throughout Phase 1. The directory contains
`Lava_TODOs_001.md` (the operator's source-of-truth TODO doc) but is
NOT committed to git.

**Action:** operator decides whether to commit (canonical TODO history)
or keep gitignored (working scratch). Currently `.gitignore` does
NOT list `docs/todos/`, so it's just untracked. Phase 6 documentation
pass should resolve.

### 4.5.10 IPv4 / host:port / schedule / algorithm-parameter literal grep is staged

**Discovered:** Phase 1 §6.R implementation deliberately deferred
non-UUID literal classes. The §6.R clause body in CLAUDE.md
acknowledges this with the "Enforcement status (2026-05-06)" line.

**Action:** Phase 6 deliverable. Add IPv4 grep, host:port grep,
schedule literal grep with carefully-scoped exemptions (incident
docs, design specs, plans, test fixtures).

---

## 5. Operator-flagged follow-up items (small, queued)

Items the operator or reviewers flagged but didn't gate on; pick up
opportunistically when a related phase touches the area.

- **OkHttp logging audit:** ensure `NetworkLogger` does NOT include
  the `Lava-Auth` header value. The constitutional clause in
  `core/CLAUDE.md` exists but isn't grep-enforced yet.
- **Submodules/RateLimiter mirror count:** only 2 mirrors today
  (gitlab + origin/github). Lava parent has 4 mirrors. Decoupled
  Reusable Architecture rule: every vasic-digital submodule SHOULD
  mirror to GitFlic + GitVerse too. Phase 6 documentation pass is a
  natural place to add the remaining 2 mirrors.
- **`Endpoint.Ktor` enum cascade** (post-Ktor cleanup tail): low
  priority, would require Android version bump for cosmetic-only
  cleanup. Tracked but not blocking.
- **`Endpoint.Mirror` LAN-IP routing branch** in
  `NetworkApiRepositoryImpl`: dead path post-Ktor; same trigger
  conditions as above.

---

## 6. Constitutional debt + memory anchors

- **§6.K-debt** (Containers extension): RESOLVED 2026-05-07 per
  `CLAUDE.md` note (Group C-pkg-vm + image-cache spec landed).
- **§6.N-debt** (pre-push hook enforcement of §6.N.1.2 + §6.N.1.3):
  RESOLVED 2026-05-05 evening per `CLAUDE.md` note (Group A-prime
  spec landed).
- **§6.L** (Anti-Bluff Functional Reality Mandate): the operator has
  invoked this 15 TIMES as of 2026-05-08 (the 15th invocation opened
  this session: "Onboarding wizard is full of bugs ... Theme colors /
  color schema is terrible ... Lots bluffing here!"). Every test added
  MUST satisfy the Sixth Law clauses 1-5 + the Seventh Law's
  Bluff-Audit stamp + the §6.J primary-on-user-visible-state assertion.
  No exception.
- **§6.R** (No-Hardcoding Mandate): added 2026-05-06 in Phase 1.
  Every value (URLs, ports, header names, credentials, schedules,
  algorithm parameters) comes from `.env` or generated config.
  Pre-push grep enforces UUID literals; IPv4/host:port/schedule
  enforcement is staged for future phases (see §4.5.11).
- **§6.S** (Continuation Document Maintenance Mandate): added
  2026-05-06. THIS file (`docs/CONTINUATION.md`) is
  constitutionally load-bearing. Every commit that changes
  tracked state MUST update this file in the SAME COMMIT.
  Stale CONTINUATION.md is itself a §6.J spirit issue.
  `scripts/check-constitution.sh` enforces (1) file present,
  (2) §0 "Last updated" line, (3) §7 RESUME PROMPT, (4) §6.S
  clause in CLAUDE.md, (5) §6.S inheritance in 16 submodules
  + lava-api-go.
- **§6.T** (Universal Quality Constraints): added 2026-05-06
  from ../HelixCode constitution mining. Four sub-points:
  - §6.T.1 Reproduction-Before-Fix (HelixCode CONST-014)
  - §6.T.2 Resource Limits for Tests & Challenges (CONST-011)
  - §6.T.3 No-Force-Push (CONST-043)
  - §6.T.4 Bugfix Documentation in `docs/issues/fixed/BUGFIXES.md`
    (CONST-012). §6.O extends this for Crashlytics issues; §6.T.4
    covers the rest.
  All four apply recursively. Submodules MAY tighten but MUST NOT
  relax.

---

## 7. RESUME PROMPT

Paste the following into a new CLI agent session to continue this
work. The agent needs no scrollback — everything it needs is in this
file plus the spec/plan/CLAUDE.md set referenced from it.

```
Continue Lava project work. Read these in order before doing anything:

  1. /run/media/milosvasic/DATA4TB/Projects/Lava/docs/CONTINUATION.md
  2. /run/media/milosvasic/DATA4TB/Projects/Lava/CLAUDE.md
  3. /run/media/milosvasic/DATA4TB/Projects/Lava/docs/todos/Lava_TODOs_001.md

Then check the git state vs the CONTINUATION.md "Last updated" line.
If new commits exist on master beyond what CONTINUATION.md describes,
trust the commits and update CONTINUATION.md before proceeding.

Active state per CONTINUATION.md §1:
  - Phase 0 (Stabilize + §6.W) COMPLETE. Commits: 9ed7bca, 55a702c.
  - Phase 1 (Full Anti-Bluff Audit) COMPLETE. All 264 tests clean,
    5 bluff-hunt mutations confirmed, all 22 Challenge KDocs have
    falsifiability protocols. RuTrackerDescriptor mutation reverted.
  - Now executing Phase 2: Fix Known Issues.
  - Full plan at docs/superpowers/specs/2026-05-08-full-anti-bluff-proofing-plan.md

Your default next action:
  - Execute Phase 2: Fix Known Issues.
    2.6 Clean up Engine.Ktor dead enum + exhaustive when branches
    2.7 Clean up Endpoint.Mirror dead LAN-IP routing branch
    2.8 Resolve docs/todos/ tracking decision (commit or gitignore)
    2.4 Document UDP buffer fix for operator

Do NOT re-run any phase — they are committed + pushed + deployed.
The git log is the authoritative record.

Phase 3+ tasks require operator hardware/API access:
  - 2.2: Execute C17-C22 (requires device/emulator)
  - 2.3: Verify /api/v1/search (requires running API)
  - 2.5: Verify Internet Archive/gutenberg (requires API)
  - Phase 3+: Emulator matrix, real-device verification, tagging

Constitutional bindings still in force (do not relax):
  §6.J / §6.L (Anti-Bluff Functional Reality Mandate)
  §6.Q (Compose Layout Antipattern Guard)
  §6.R (No-Hardcoding Mandate)
  §6.G (End-to-End Provider Operational Verification)
  §6.I (Multi-Emulator Container Matrix as Real-Device Equivalent)
  §6.K (Builds-Inside-Containers Mandate)
  §6.O (Crashlytics-Resolved Issue Coverage Mandate)
  §6.P (Distribution Versioning + Changelog Mandate)
  §6.N (Bluff-Hunt Cadence Tightening)
  §6.U (No sudo/su Mandate)
  §6.V (Container Emulators Mandate)
  §6.W (GitHub + GitLab Only Remote Mandate)

The operator's standing order is preserved verbatim in
CLAUDE.md §6.L. Read it.
```

---

## 8. House-keeping the agent should keep doing

These are habits the in-flight Phase-1 agent established that
future agents should preserve:

1. **Commit messages carry Bluff-Audit stamps for every test class
   added or modified.** Pre-push hook rejects commits without them.
2. **Every commit must have `Co-Authored-By: Claude Opus 4.7
   (1M context) <noreply@anthropic.com>`** as the trailer.
3. **Push to both Lava parent mirrors (GitHub + GitLab)** after every commit chain
   that closes a logical unit. After every push, confirm convergence
   with `for r in github gitlab; do echo "$r:
   $(git ls-remote $r master | awk '{print $1}' | head -1)"; done`.
4. **Submodule pushes are explicit per submodule** to whatever
   remotes that submodule has (varies — see §3 above). Never use
   `git submodule foreach git push` blindly; some submodules have
   `upstream` (fork-source) which is NOT a write target.
5. **Update this CONTINUATION.md** in the same commit as any
   completion-state change (phase done, new spec/plan written,
   submodule pin bumped, distribute artifact shipped).
6. **The autonomous loop ends** when the next forward step requires
   operator-environment access (real device, real keystore secrets,
   Firebase token, ssh credentials) OR operator decision-making
   (brainstorming next phase scope, tagging, choosing a UI direction).
   At that point, summarize state + ask the operator the specific
   next-step question.
