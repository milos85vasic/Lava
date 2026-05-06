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

> **Last updated:** 2026-05-06 (evening), after Phase 1 of the parent
> decomposition reached the "deployed-to-thinker.local + on Firebase
> App Distribution" state, plus §6.S (Continuation Document Maintenance
> Mandate) added to the constitution + propagated to all 16 submodules
> + lava-api-go, plus Submodules/Challenges/Containers/ residue cleanup.

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
| Lava parent on master | 4 mirrors converged | (this commit) |
| API on thinker.local | running 2.1.0 (build 2100) (healthy) | container `lava-api-go-thinker` |
| Android Firebase | 1.2.7 (1027) distributed to testers | `lava-vasic-digital` Firebase project |
| 16 vasic-digital submodules | all pushed | see §3 below |

**Operator's TODO source-of-truth:** `docs/todos/Lava_TODOs_001.md`
(committed 2026-05-06). Contains 17 work items decomposed into 6
parent-phases.

**Parent decomposition spec for Phase 1:**
`docs/superpowers/specs/2026-05-06-phase1-api-auth-design.md` (commit
`45723b2`).

**Parent decomposition plan for Phase 1:**
`docs/superpowers/plans/2026-05-06-phase1-api-auth.md` (commit
`88a7568`).

---

## 1. What's DONE (for context — do not re-do)

### Parent-decomposition Phase 1 — API auth + security foundations

Status: **shipped to Firebase App Distribution + thinker.local**. 16
internal sub-phases all green. Bluff-Audit stamps recorded for every
test added.

| Internal sub-phase | Head | What landed |
|---|---|---|
| 1: §6.R + check-constitution | `8192403` | constitutional clause + UUID-grep gate + 3 hermetic tests |
| 2: config layer | `ef525fb` | 12 new `Config` fields + 5 helpers + 18 tests |
| 3: `pkg/ladder` upstream | `Submodules/RateLimiter@3faf7a51` + Lava `179ac43` | per-key fixed-step backoff primitive |
| 5: AuthMiddleware | `7a4cc4d` | 8 tests, constant-time hash compare |
| 6: BackoffMiddleware | `25d1217` | 5 tests, trusted-proxy XFF |
| 7: wire + integration | `2d3a1f9` | 5 §6.G integration + §6.A contract test |
| 8: transport | `b099565` | brotli + Alt-Svc + protocol metric + 3 tests + post-Ktor stale fixtures |
| 9: client crypto | `c27ce7a` | HKDF (RFC 5869), AES-GCM tamper, SigningCertProvider — 13 tests |
| 10: AuthInterceptor + Hilt | `540bd9c` | OkHttp interceptor + reflection-based provider lookup |
| 11: build-time codegen | `cad86b5` | `LavaAuthCodegen` Gradle task; gracefully skips on .env-less envs |
| 12: α-hotfix | `384ac02` | `TrackerDescriptor.apiSupported` + filter + behavioral test |
| 13: Compose UI Challenges | `48633ff` | C15 (boot) + C16 (apiSupported filter) |
| 14: distribute Gates 4+5 | `7474a39` | pepper-rotation refusal + client-name consistency |
| 15: docs | `963af09` | `docs/RELEASE-ROTATION.md` + 2 CHANGELOG entries + 2 snapshots |
| 16: version bump | `82aa2e0` | 1.2.7-1027 + 2.1.0-2100 |
| Phase 2 follow-ups | `5f9ab5d` | parseBackoffSteps + envBool + 6 new tests |
| All-submodule push | `6895948` | 16 submodules synced to all their mirrors |
| Distribute hardening | `e9470812` | /health-before-auth + LavaAuthBlobProvider visibility + thinker.local auth-env plumbing |

### Operator-visible deliverables

- **API**: `https://thinker.local:8443/{health,ready}` returns
  `{"status":"alive"}` / `{"status":"ready"}`. Auth gate fails-closed
  with `401 {"error":"unauthorized"}` on missing `Lava-Auth` header.
- **APK**: 1.2.7 (1027) on Firebase App Distribution under project
  `lava-vasic-digital`. Operator's tester email received the invite.

---

## 2. What's BLOCKED ON OPERATOR ACTION (cannot proceed autonomously)

These items need the operator's environment / hardware / decisions
that an agent cannot make alone. They are NOT autonomous-resumable;
they require the operator to do the steps OR explicitly grant
authorization.

### 2.1 Phase 16 release-tagging chain

| Step | Command | Blocker |
|---|---|---|
| §6.I emulator matrix gate | `bash scripts/run-emulator-tests.sh --avds=Pixel_API28,Pixel_API30,Pixel_API34,Pixel_APIlatest,Tablet_API34 --tests=lava.app.challenges --concurrent=1 --output=.lava-ci-evidence/Lava-Android-1.2.7-1027/real-device-verification.md` | Requires real Android emulator/device hardware |
| Real-device manual smoke-test | login + search + browse + download on a Pixel-class device against thinker.local | Operator hands-on |
| Tag Android | `bash scripts/tag.sh Lava-Android-1.2.7-1027` | Refuses without matrix evidence |
| Tag API | `bash scripts/tag.sh Lava-API-Go-2.1.0-2100` | Refuses without matrix evidence |

### 2.2 Operator's `.env` rotation hygiene

The first auto-rotation generated:
- `LAVA_AUTH_CURRENT_CLIENT_NAME = android-1.2.7-1027`
- `LAVA_AUTH_HMAC_SECRET = <auto-generated 32 bytes>` (gitignored .env)
- `LAVA_AUTH_OBFUSCATION_PEPPER = <auto-generated 32 bytes>` (gitignored .env)

Per `docs/RELEASE-ROTATION.md`: every release rotates pepper. The
next release MUST start by appending a new pepper. The `pepper-history.sha256`
file (under `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/`)
will refuse re-use.

---

## 3. Submodule pin index (for reference)

All 16 `vasic-digital` submodules are at HEAD on `main`. Bumping a
pin is a deliberate operator action; never auto-update.

| Submodule | Pin | Mirrors | Notes |
|---|---|---|---|
| `Auth` | `6213c61` | GitHub + GitLab + origin | §6.R + §6.S inheritance |
| `Cache` | `ea3b376` | GitHub + GitLab + gitlab + origin | §6.R + §6.S inheritance |
| `Challenges` | `f7d336d` | github + gitlab + origin (+ upstream skipped) | merged P1.5 + §6.R + §6.S; Containers/ residue removed |
| `Concurrency` | `5b5a858` | GitHub + GitLab + origin | §6.R + §6.S inheritance |
| `Config` | `45a915b` | origin | §6.R + §6.S inheritance |
| `Containers` | `84c381c` | github + gitlab + origin | merged P1.5-WP8 + §6.R + §6.S |
| `Database` | `1ce46f9` | GitHub + GitLab + origin | §6.R + §6.S inheritance |
| `Discovery` | `5348c7d` | origin | §6.R + §6.S inheritance |
| `HTTP3` | `7fec2d8` | origin | §6.R + §6.S inheritance |
| `Mdns` | `e7839fa` | origin | §6.R + §6.S inheritance |
| `Middleware` | `c877ef9` | origin | §6.R + §6.S inheritance |
| `Observability` | `aff0931` | GitHub + GitLab + origin | §6.R + §6.S inheritance |
| `RateLimiter` | `1127b11` | gitlab + origin | NEW: pkg/ladder primitive + §6.R + §6.S |
| `Recovery` | `b4b8771` | origin | §6.R + §6.S inheritance |
| `Security` | `d45b458` | GitHub + GitLab + origin | §6.R + §6.S inheritance |
| `Tracker-SDK` | `3d31ea3` | origin | §6.R + §6.S inheritance |

**Internal-to-submodule nested submodules:** `Submodules/Challenges` has a nested `Panoptic` submodule that Challenges' own Go code in `pkg/panoptic/` depends on. Per the operator's "no submodules at multiple depths in the project root" directive, Lava itself has only flat root-level Submodules — but Challenges (consumed as a black-box vasic-digital submodule) self-manages its own internal dependencies, including Panoptic. This is acceptable: the depth restriction applies at Lava's tracking surface, not recursively into each submodule's internal organization.

---

## 4. PHASES 2-6 OF THE PARENT DECOMPOSITION — NOT STARTED

These are the rest of the work in `docs/todos/Lava_TODOs_001.md`. Each
phase requires its own brainstorm → spec → plan → execute cycle, same
shape as Phase 1 (which produced
`docs/superpowers/specs/2026-05-06-phase1-api-auth-design.md` +
`docs/superpowers/plans/2026-05-06-phase1-api-auth.md`).

### 4.1 Phase 2 — Multi-provider streaming search

**Closes:** the alice-bug class (operator's report: "Search 'alice' on
Internet Archive returns 'Something went wrong'"). Phase 1's α-hotfix
HID Internet Archive from onboarding; Phase 2 RE-ENABLES it by adding
working API routes.

**Scope:**
- Per-provider routing in `lava-api-go`: `/v1/{providerId}/search`,
  `/v1/{providerId}/browse`, `/v1/{providerId}/topic/:id`,
  `/v1/{providerId}/download/:id` for archiveorg, gutenberg, kinozal,
  nnmclub (and any future tracker)
- Streaming results decision: SSE / WebSocket / gRPC streaming — pick
  one in the brainstorm
- Per-result provider label in client UI (currently results have no
  provider attribution)
- Multi-select provider filter in search input
- The 4 tracker descriptors flip `apiSupported = false → true` as
  their routes ship (one descriptor per task; not all in one batch)
- Challenge Tests for each newly-enabled provider (real-stack,
  authenticated against the lava-api-go routes)
- Cross-backend parity test extension (rutracker handler vs new
  per-provider handlers; existing
  `lava-api-go/tests/parity/` is the gate)

**TODO sections covered:** "Search request from client(s) app(s)",
"Receiving results in client(s) app(s)" (real-time event-stream),
plus the alice-bug fix.

**Operator decisions needed in brainstorm:**
- Streaming transport (SSE / WS / gRPC streaming)
- Per-result label rendering (chip vs prefix vs icon)
- Order of provider re-enablement (which descriptor flips first)

### 4.2 Phase 3 — First-run onboarding wizard

**Scope:**
- Multi-step wizard: pick providers → configure each in turn → test
  connection → finish (per TODO: "Client app(s) first run0" section)
- Cannot exit without ≥1 working provider
- Anonymous-access path for providers with `supportsAnonymous = true`
- Per-provider connection-test step that surfaces clear errors
- "Finish" enabled only after ≥1 provider is configured + tested

**Files affected:** mostly `feature/login/`, `feature/credentials/`,
`feature/onboarding/` (may need a new feature module), navigation
graph in `:app`.

**Operator decisions needed:** wizard layout (single screen with
phased content vs multi-screen NavController flow), test-connection
UX (progress indicator, retry, skip), force-pick-at-least-one-provider
behavior on system-back press.

### 4.3 Phase 4 — Sync subsystem expansion

**Scope:**
- "Sync Now" button on Favorites + Bookmarks (currently sync is
  passive)
- New sync categories: Provider Credentials, History, all selected
  providers
- Device-unique identity so re-install on same device pulls prior
  state
- Pull-on-fresh-install when backend is detected: skip onboarding if
  ≥1 provider exists in the pulled state and connection-test passes
- Per-category sync settings (frequency, on-cellular toggle)

**TODO sections covered:** "Sync now button" + "Additional sync options".

**Operator decisions needed:** sync transport for the new categories
(reuse Firebase Realtime DB, or extend lava-api-go with sync
endpoints), conflict resolution (last-write-wins vs CRDTs vs
prompt-user), debounce policy for Sync Now.

### 4.4 Phase 5 — UI/UX polish bundle

**Scope:**
- Menu screen multi-provider header (currently shows ONE logged-in
  provider; should show every signed-in provider with username +
  sign-out per row)
- Color themes: port palettes from `../Boba` and `../MeTube`; user
  picks from a dropdown; selection persists across app restarts
- About dialog: append `versionCode` to displayed version (e.g.
  "Version: 1.2.7 (1027)")
- Credentials screen UI redesign — "ugly UI and terrible UX" per
  operator; FAB overlap with 3-button nav on new Android
- Result-list filtering by provider
- Nav-bar overlap audit on every screen with FABs

**TODO sections covered:** "Polishing UI / UX of the Menu screen",
"Additional color themes / color schemes", "About dialog extension",
"Credentials screen", "Search result filtering on client(s) app(s)".

**Operator decisions needed:** color palette set + names (Boba +
MeTube discovery), theme-picker UI (radio list vs dropdown vs swatch
grid), Credentials screen redesign mock.

### 4.5 Phase 6 — Testing sweep + final distribution + docs

**Scope:**
- Real-emulator full-automation across major Android versions per the
  matrix in §6.I (already designed; this phase RUNS it as the
  acceptance gate, not just per-feature)
- Crashlytics non-fatal tracking expansion: every Phase-2..5 user
  flow gets per-step `recordException` so the Crashlytics console
  can show drop-off points
- Diagrams + graphs (operator's request: "Create proper diagrams and
  graphs with all other relevant materials project needs")
- User guide + manual updates
- Final operator green-light + tag the release

**TODO section covered:** "Testing" + the closing paragraph "extend
and improve all existing documentation, all user guides and manuals,
create proper diagrams and graphs".

---

## 4.5 Known issues + bugs (in-flight, not blocking distribution)

These are real defects discovered during Phase 1's first deploy. None
are release-blocking — the API serves and the auth gate fails-closed
correctly — but each is a real Phase-2-or-later work item.

### 4.5.1 `/api/v1/search` returns 404 even with valid `Lava-Auth` header

**Discovered:** 2026-05-06 during the first authenticated curl test
against thinker.local.

**Symptom:** with a Lava-Auth header carrying the active UUID:
- `GET /search?q=ubuntu` → 401 (auth gate fires; header validation
  fails; the route registration does pass authentication when given a
  valid header but in the test the path was wrong)
- `GET /v1/search?q=ubuntu` → 404
- `GET /api/search?q=ubuntu` → 404
- `GET /api/v1/search?q=ubuntu` → 404

**Root cause hypothesis:** the route registration in
`lava-api-go/internal/handlers/v1/handlers.go` uses `group.GET("/search",
search.GetSearch)` where `group` is `router.Group("/v1/:provider")`.
So the actual path is `/v1/{provider-id}/search`, not `/v1/search`.
Phase 1's α-hotfix HID Internet Archive from onboarding so the bug
doesn't surface in normal flow, but the underlying routing inconsistency
is real and is the Phase 2 work.

**Phase 2 deliverable:** add `/v1/{providerId}/search`,
`/v1/{providerId}/browse`, `/v1/{providerId}/topic/:id`,
`/v1/{providerId}/download/:id` for archiveorg, gutenberg, kinozal,
nnmclub. Each tracker descriptor flips `apiSupported = true` as its
routes ship.

### 4.5.2 `/health` had an intermittent 133s timeout once during distribute verification

**Discovered:** 2026-05-06 during the post-distribute verification
sweep. The first `curl -fsSk https://thinker.local:8443/health` hung
for 133 seconds before returning. A second curl seconds later
returned `{"status":"alive"}` immediately.

**Root cause hypothesis:** HTTP/3 vs HTTP/2 negotiation timeout under
the QUIC `failed to sufficiently increase receive buffer size` warning
(see 4.5.3 below). The TLS handshake may have stalled on the UDP path
before falling back to TCP.

**Mitigation:** the container's `HEALTHCHECK` directive (which uses
the bundled `healthprobe` binary, not curl) has not exhibited this —
container reports `(healthy)` consistently. The intermittent curl
hang appears to be at the curl/HTTP-3-client layer, not the API.

**Action:** track but don't gate — observe in future distribute
sessions. If reproducible, file a forensic anchor under
`.lava-ci-evidence/sixth-law-incidents/` and root-cause via
systematic-debugging skill.

### 4.5.3 quic-go UDP receive buffer warning on thinker.local boot

**Discovered:** 2026-05-06 in the api-go boot logs:

```
2026/05/06 18:21:25 failed to sufficiently increase receive buffer size
(was: 208 kiB, wanted: 7168 kiB, got: 416 kiB).
See https://github.com/quic-go/quic-go/wiki/UDP-Buffer-Sizes for details.
```

**Symptom:** non-fatal warning. HTTP/3 listener still binds; clients
can still connect via QUIC. But the small buffer means high-throughput
QUIC traffic could drop packets under load.

**Fix:** thinker.local's host kernel needs `sysctl -w
net.core.rmem_max=7340032` (and persist via `/etc/sysctl.d/`). Either
the operator does this manually OR `deployment/thinker/thinker-up.sh`
gains a sysctl-tune step.

**Action:** Phase 2 or Phase 6 operations work. Track in §6.M
host-stability discipline if it becomes a Class II resource-pressure
event.

### 4.5.4 The Phase 1 alice-bug fix is a HIDE, not a FIX

**Discovered:** 2026-05-06 — explicit in the Phase 1 design.

**Symptom:** Internet Archive used to appear in onboarding + crash
with "Something went wrong" on search. The α-hotfix in Phase 12
(commit `384ac02`) added `TrackerDescriptor.apiSupported` and filters
the user-facing list. Internet Archive now shows `apiSupported = false`
and is hidden — the user never sees it on a fresh install.

**This is not the actual fix.** The Internet Archive provider still
has no API support; existing installs that already had it selected
also see a one-time dialog (per the Phase 12 plan, though the dialog
implementation itself is queued for Phase 5 since it touches
MainActivity + PreferencesStorage).

**Real fix:** Phase 2 ships per-provider routing in lava-api-go and
flips `apiSupported = true` on each provider as routes land.

### 4.5.5 The Phase 12 α-hotfix dialog is not yet implemented

**Discovered:** during Phase 12 implementation; descoped to keep that
phase tight.

**Symptom:** existing installs (before 1.2.7) that had Internet Archive
or another `apiSupported = false` provider already selected don't get
a one-time "this provider is not currently supported" dialog. The
provider just stops working for them silently after upgrade.

**Phase 2 OR Phase 5 deliverable:** wire the dialog through
`MainActivity.onCreate()` + a `Preferences` boolean flag
`unsupportedProviderDialogShown`. The dialog dismisses to
`Settings → Trackers`. Phase 12's plan included this; the
implementation itself was deferred.

### 4.5.6 Submodules/Challenges has untracked `Containers/` dir leftover from upstream merge

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

### 4.5.7 `Submodules/RateLimiter` only mirrors to 2 of the 4 expected upstreams

**Discovered:** 2026-05-06 during Phase 3 (pkg/ladder upstream
contribution). The submodule has remotes `gitlab` + `origin`
(github.com:vasic-digital/RateLimiter) but no GitFlic or GitVerse.

**Symptom:** new pkg/ladder commits land on GitHub + GitLab but not
on GitFlic or GitVerse. Inconsistent with Lava parent's 4-mirror
model.

**Phase 6 deliverable:** add gitflic + gitverse remotes to
`Submodules/RateLimiter` (and audit all other submodules for the
same gap; Config + Discovery + HTTP3 + Mdns + Middleware + Recovery
+ Tracker-SDK each have only `origin`). The Decoupled Reusable
Architecture rule says submodules SHOULD mirror to the same set.

### 4.5.8 `Engine.Ktor` enum cascade tail (post-Ktor cleanup low priority)

**Symptom:** the `Engine.Ktor` enum value lingers in client-side
exhaustive `when` branches. It's dead code — the Ktor :proxy was
deleted in 2.0.12. Removal would require an Android version bump
for cosmetic-only cleanup.

**Action:** Phase 5 (UI/UX polish) is the natural place. Tracked.

### 4.5.9 `Endpoint.Mirror` LAN-IP routing branch (post-Ktor cleanup low priority)

Same shape as 4.5.8. Dead path post-Ktor; no triggering producer in
tree. Phase 5 cleanup target.

### 4.5.10 `docs/todos/` directory is untracked

**Discovered:** persistent throughout Phase 1. The directory contains
`Lava_TODOs_001.md` (the operator's source-of-truth TODO doc) but is
NOT committed to git.

**Action:** operator decides whether to commit (canonical TODO history)
or keep gitignored (working scratch). Currently `.gitignore` does
NOT list `docs/todos/`, so it's just untracked. Phase 6 documentation
pass should resolve.

### 4.5.11 IPv4 / host:port / schedule / algorithm-parameter literal grep is staged

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
  invoked this 14 TIMES. Every test added to Phases 2-6 MUST satisfy
  the Sixth Law clauses 1-5 + the Seventh Law's Bluff-Audit stamp +
  the §6.J primary-on-user-visible-state assertion. No exception.
- **§6.R** (No-Hardcoding Mandate): added 2026-05-06 in Phase 1.
  Every value (URLs, ports, header names, credentials, schedules,
  algorithm parameters) comes from `.env` or generated config.
  Pre-push grep enforces UUID literals; IPv4/host:port/schedule
  enforcement is staged for future phases (see §4.5.11).
- **§6.S** (Continuation Document Maintenance Mandate): added
  2026-05-06 in this commit. THIS file (`docs/CONTINUATION.md`)
  is constitutionally load-bearing. Every commit that changes
  tracked state MUST update this file in the SAME COMMIT.
  Stale CONTINUATION.md is itself a §6.J spirit issue.
  `scripts/check-constitution.sh` enforces (1) file present,
  (2) §0 "Last updated" line, (3) §7 RESUME PROMPT, (4) §6.S
  clause in CLAUDE.md, (5) §6.S inheritance in 16 submodules
  + lava-api-go.

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

Active state per CONTINUATION.md §0:
  - Parent-decomposition Phase 1 of 6 is SHIPPED (API on thinker.local
    + Android 1.2.7-1027 on Firebase App Distribution).
  - Phases 2-6 are NOT STARTED — they need their own brainstorm → spec
    → plan → execute cycles per the superpowers skill chain.

Your default next action:
  - Confirm the system state matches §0 of CONTINUATION.md (run
    git log --oneline -5, curl -fsSk https://thinker.local:8443/health,
    and git ls-remote on the 4 mirrors).
  - Then ask the operator:
      "Phase 1 of the parent decomposition is shipped. Phases 2-6
       remain (multi-provider streaming, onboarding wizard, sync
       expansion, UI/UX polish, testing+distribution sweep). Which
       phase do you want to brainstorm next?"
  - When the operator picks one, invoke the superpowers:brainstorming
    skill scoped to that phase.

Do NOT re-run any of Phase 1's 16 sub-phases — they are committed +
pushed + deployed. The git log + the mirror convergence are the
authoritative record.

Constitutional bindings still in force (do not relax):
  §6.J / §6.L (Anti-Bluff Functional Reality Mandate, 14× invoked)
  §6.R (No-Hardcoding Mandate, added Phase 1)
  §6.G (End-to-End Provider Operational Verification)
  §6.I (Multi-Emulator Container Matrix as Real-Device Equivalent)
  §6.K (Builds-Inside-Containers Mandate)
  §6.O (Crashlytics-Resolved Issue Coverage Mandate)
  §6.P (Distribution Versioning + Changelog Mandate)
  §6.N (Bluff-Hunt Cadence Tightening)

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
3. **Push to all 4 Lava parent mirrors** after every commit chain
   that closes a logical unit. After every push, confirm convergence
   with `for r in github gitlab gitflic gitverse; do echo "$r:
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
