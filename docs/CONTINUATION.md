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

> **Last updated:** 2026-05-06, after Phase 1 of the parent
> decomposition reached the "deployed-to-thinker.local + on Firebase
> App Distribution" state.

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
| `Auth` | `af1c285` | GitHub + GitLab + origin | §6.R inheritance |
| `Cache` | `d0d513a` | GitHub + GitLab + gitlab + origin | §6.R inheritance |
| `Challenges` | `165c417` | github + gitlab + origin (+ upstream skipped) | merged P1.5 + §6.R |
| `Concurrency` | `0d22a15` | GitHub + GitLab + origin | §6.R inheritance |
| `Config` | `6df901a` | origin | §6.R inheritance |
| `Containers` | `47237ba` | github + gitlab + origin | merged P1.5-WP8 + §6.R |
| `Database` | `f65cb58` | GitHub + GitLab + origin | §6.R inheritance |
| `Discovery` | `892f253` | origin | §6.R inheritance |
| `HTTP3` | `b3dbff5` | origin | §6.R inheritance |
| `Mdns` | `c30be6d` | origin | §6.R inheritance |
| `Middleware` | `560f31a` | origin | §6.R inheritance |
| `Observability` | `1663f7e` | GitHub + GitLab + origin | §6.R inheritance |
| `RateLimiter` | `3faf7a5` | gitlab + origin | NEW: pkg/ladder primitive |
| `Recovery` | `253457b` | origin | §6.R inheritance |
| `Security` | `7bd6f90` | GitHub + GitLab + origin | §6.R inheritance |
| `Tracker-SDK` | `819443c` | origin | §6.R inheritance |

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
  enforcement is staged for future phases.

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
