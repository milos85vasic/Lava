# Pending Completion Plan — 2026-05-04

> Drafted in response to the operator's 5th invocation of the anti-bluff
> mandate (clause 6.L) on 2026-05-04. This plan enumerates the remaining
> work owed after the 2026-05-04-onboarding-navigation incident's
> three-layer fix landed, organised by phase with fine-grained tasks and
> falsifiability rehearsal protocols. **No phase is "complete" until its
> falsifiability rehearsals are recorded in
> `.lava-ci-evidence/<phase-id>/`.**

## Anti-Bluff binding (clause 6.L, restated for the 5th time)

Every test, every Challenge Test, every CI gate added or modified in
this plan MUST do exactly one job: confirm the feature works for a real
user end-to-end on the gating matrix. CI green is necessary, NEVER
sufficient. Adding a test the author cannot execute against the gating
matrix is itself a bluff. The 2026-05-04 IA forensic-anchor sequence
(stuck spinner → broken nav → broken active-tracker → broken auth-state
bridge) is the proof that prior anti-bluff plumbing alone (6.A–6.K +
Sixth & Seventh Laws) was insufficient to evict the bluff class.
Discipline beats automation.

## Phase 1 — Persistence + Test Infrastructure

**Why first:** every later phase depends on either persistence
(restart-survival of authorized state) or test infrastructure
(onboarding-bypass for tests that don't need to traverse the full
onboarding flow). Both are small, contained, low-risk.

### Phase 1.1 — Persist `sessionSignaledState`

After the layer-3 fix, `AuthServiceImpl.sessionSignaledState` is an
in-memory `@Volatile var`. A user who picks archive.org, taps Continue,
sees the authorized Search tab, then force-quits the app, will face
"Authorization required" again on next launch because:
- `preferencesStorage.getAccount()` only knows about RuTracker accounts
- `sessionSignaledState` was lost with the process

#### Tasks

| ID | Task | Falsifiability rehearsal |
|---|---|---|
| 1.1.1 | Add `getSignaledAuthState()` / `saveSignaledAuthState(name, avatarUrl)` / `clearSignaledAuthState()` to `lava.securestorage.PreferencesStorage` interface | N/A — interface addition |
| 1.1.2 | Implement in `PreferencesStorageImpl` using a separate SharedPreferences file (`signaled_auth.xml`) so it doesn't collide with the legacy account file | Mutate impl to read from wrong key → assert "signal not restored" |
| 1.1.3 | `AuthServiceImpl.signalAuthorized` writes both in-memory + persisted | Mutate to write only in-memory → kill+relaunch test fails |
| 1.1.4 | `AuthServiceImpl.getAuthState()` reads persisted state if `sessionSignaledState == null` (cold-start path) | Mutate to skip persisted-state read → kill+relaunch test fails |
| 1.1.5 | `AuthServiceImpl.logout()` clears persisted state too | Mutate to skip clearing → logout+relaunch shows authorized (regression) |
| 1.1.6 | `TestAuthService` fake updated with persistence-equivalent in-memory map (Anti-Bluff Pact's Third Law) | Drop persistence semantics from fake → divergence between real and fake |

#### Acceptance test

A new instrumentation test `Challenge00CrashSurvivalTest` (or similar):
1. Wipe app data
2. Launch → onboarding shows
3. Pick archive.org + tap Continue
4. Assert Search tab shows authorized empty state
5. `am force-stop` the app
6. Relaunch
7. Assert Search tab still shows authorized empty state (NOT Unauthorized)

**Cannot ship Phase 1.1 without this test passing on at least one AVD.**

### Phase 1.2 — `OnboardingBypassRule` for instrumentation tests

#### Why

C1-C8 + C9-C12 (the SDK-consuming Challenge Tests) currently fail
because the app starts at the OnboardingScreen and they expect the
bottom-tab nav. Two paths to fix:
- **A:** Each test traverses the real onboarding (pick provider, enter
  creds, tap Continue) before the test action. Slow, requires creds for
  FORM_LOGIN providers, makes credentials a hard prerequisite.
- **B:** A JUnit rule that pre-populates `PreferencesStorage` to mark
  `isOnboardingComplete = true` AND signals a fake authorized state
  before `MainActivity` launches. Test starts in main app.

Path B is what mature codebases use. C2 (RuTracker authenticated search)
specifically needs the real onboarding because the test's purpose IS to
verify the login flow; it uses path A. Other tests use path B.

#### Tasks

| ID | Task | Falsifiability rehearsal |
|---|---|---|
| 1.2.1 | Create `app/src/androidTest/.../OnboardingBypassRule.kt` — Hilt-injected `TestRule` that calls `preferencesStorage.setOnboardingComplete(true)` + `authService.signalAuthorized("Test")` in its `before()` hook | Mutate the rule to skip the signalAuthorized call → C1 baseline run shows Unauthorized in Search tab |
| 1.2.2 | Create a fixture provider that the rule registers: a `Test` provider with `AuthType.NONE` so signalAuthorized has a sensible name | N/A — fixture |
| 1.2.3 | Document in `feature/CLAUDE.md` that any Challenge Test that does NOT specifically test the onboarding flow MUST use this rule | N/A — doc |

## Phase 2 — C1-C8 Real-stack Rehearsal

**Why now:** After Phase 1, the bypass rule lets tests start in the main
app. Each existing C1-C8 test needs:
- Updated UI selectors to match the post-mandatory-onboarding UI
- Falsifiability rehearsal documented in
  `.lava-ci-evidence/sp3a-challenges/C<N>-<sha>.json`
- Operator real-device attestation row in the next tag's evidence file

### Phase 2.1 — Per-test redesign matrix

Each row below is a fine-grained task. Each ends with a recorded
falsifiability rehearsal.

| Test | Path | Selector audit | Mutation rehearsal | Status |
|---|---|---|---|---|
| C1 AppLaunchAndTrackerSelectionTest | path B (bypass) | "Menu" → exists; verify "Settings" + "Trackers" + "RuTracker (active)" still match real UI | drop @IntoSet on RuTorRegistrationModule → only RuTracker in list | redesign owed |
| C2 AuthenticatedSearchOnRuTrackerTest | path A (real onboarding with .env creds) | onboarding flow + login + search field + result row + "seeders" + "MB" | `RuTrackerSearch.search` throws → result row never renders | redesign owed |
| C3 AnonymousSearchOnRuTorTest | path A (anonymous toggle on RuTor) | onboarding anonymous toggle + RuTor pick + Continue + search "ubuntu" + "seeders" | drop SEARCH from RuTorDescriptor → search empty | redesign owed |
| C4 SwitchTrackerAndResearchTest | path B + tracker-settings switch | Settings → Trackers → tap RuTor + RuTracker badges | swap active-tracker mapping in registry → wrong results | redesign owed |
| C5 ViewTopicDetailTest | path B + open known topic id | topic title + description + files + magnet link icon | `RuTrackerTopic.getTopic` returns null → assertion fails | redesign owed |
| C6 DownloadTorrentFileTest | path B + tap download on a result | bencode header in downloaded file | `RuTorDownload.download` returns empty → bencode validation fails | redesign owed |
| C7 CrossTrackerFallbackAcceptTest | path B + simulate RuTracker unhealthy | fallback modal + Accept | drop CrossTrackerFallbackPolicy.shouldFallback → modal never shows | redesign owed |
| C8 CrossTrackerFallbackDismissTest | path B + same as C7 + Dismiss | modal Dismiss + assert no silent fallback | mutate `dismiss()` to silently fall back → assertion that active tracker stays unchanged fires | redesign owed |

#### Acceptance criteria for Phase 2

- All 8 redesigned tests pass on **at least Pixel_9a (latest API) AND
  CZ_API34_Phone (API 34)**.
- Per-test falsifiability rehearsal recorded in
  `.lava-ci-evidence/sp3a-challenges/C<N>-<sha>.json` with the mutation,
  observed-failure message, screenshot, revert confirmation.
- `feature/CLAUDE.md` updated to mark the C1-C8 KDocs' "operator real-
  device attestation owed" gate as **closed** for the rehearsed AVD set.

## Phase 3 — Multi-AVD Container Matrix Infrastructure

**Why:** Clause 6.I requires a multi-AVD container matrix as the
acceptance gate. Currently `scripts/run-emulator-tests.sh` boots ONE
AVD; the orchestration for multi-AVD is documented but not implemented.
Per clause 6.K-debt, this work belongs in
`Submodules/Containers/pkg/emulator/` + `cmd/emulator-matrix`. Lava-side
glue calls it.

### Phase 3.1 — Containers extension

| ID | Task | Falsifiability rehearsal |
|---|---|---|
| 3.1.1 | Create `Submodules/Containers/pkg/emulator/` package skeleton — `EmulatorMatrix` type + `RunOptions` + `MatrixResult` | N/A — types only |
| 3.1.2 | Implement `Boot(ctx, avd, runtime)` using existing `pkg/runtime` for Docker/Podman auto-detect | Pass invalid AVD → expect deterministic failure with matching error type |
| 3.1.3 | Implement `WaitForBoot(ctx, port)` — polls `getprop sys.boot_completed` via `adb` until 1 or timeout | Mutate to skip the polling loop → boot-incomplete state slips through |
| 3.1.4 | Implement `Install(apkPath)`, `RunInstrumentation(testClass)`, `Teardown()` | Each step has its own bluff-audit |
| 3.1.5 | Implement `RunMatrix(ctx, matrix Config)` — for-each AVD: cold-boot, install, run tests, collect outcome, teardown | Drop the per-AVD result aggregation → matrix returns success when only one AVD passes |
| 3.1.6 | `cmd/emulator-matrix/main.go` — CLI entrypoint reading a JSON config (AVD list, APK path, test class, evidence-dir path) | Pass empty AVD list → expect non-zero exit |

### Phase 3.2 — Lava-side glue refactor

| ID | Task | Falsifiability rehearsal |
|---|---|---|
| 3.2.1 | Refactor `scripts/run-emulator-tests.sh` to invoke `cmd/emulator-matrix` with Lava-specific config | Mutate config to point at non-existent test class → matrix fails with deterministic error |
| 3.2.2 | Update `docs/superpowers/specs/...` with the matrix's evidence schema | N/A — doc |
| 3.2.3 | Update `scripts/check-constitution.sh` to enforce: `pkg/emulator/` exists in pinned Containers + `scripts/run-emulator-tests.sh` references `cmd/emulator-matrix` + at least one passing real-container-emulator-boot test exists in Containers | Remove emulator-matrix reference from script → check fails |

### Phase 3.3 — First matrix attestation

| ID | Task |
|---|---|
| 3.3.1 | Run `./scripts/run-emulator-tests.sh --matrix` on local machine. Boot AVDs in sequence: CZ_API28 + CZ_API30 + CZ_API34 + Pixel_9a |
| 3.3.2 | Record per-AVD attestation in `.lava-ci-evidence/<next-tag>/real-device-verification.md` |
| 3.3.3 | Verify constitution check passes with the new gate |

#### Acceptance criteria for Phase 3

- Containers `pkg/emulator/` shipped + tests passing in Containers' own
  CI (`go test ./pkg/emulator/...`)
- Lava-side `scripts/run-emulator-tests.sh` is thin glue
- One full matrix run produces a valid attestation file
- `scripts/check-constitution.sh` enforces the gate

## Phase 4 — C9-C12 Verification + Provider Verified-flag Flips

**Why:** With the matrix in place, the four hidden providers can have
their `verified` flag flipped iff their Challenge Test passes on the
matrix. Per clause 6.G clause 5, this MUST happen with falsifiability
rehearsal per provider.

### Phase 4.1 — Per-provider rehearsal

| Provider | Challenge | Path | Falsifiability rehearsal |
|---|---|---|---|
| archiveorg | C11 (anonymous Continue → search → result row) | path A (no creds, AuthType.NONE) | revert OnboardingScreen.kt to dual-collector → C11 times out at the post-Continue waitUntil |
| gutenberg | C12 (anonymous Continue → search "shakespeare") | path A | `GutenbergSearch.search` returns empty → C12 times out at the result-row waitUntil |
| kinozal | C9 (FORM_LOGIN with .env creds → search "ubuntu") | path A with creds | `KinozalAuth.login` forces Unauthenticated → C9 times out at the post-login waitUntil. Note: kinozal.tv may be geofenced; record geo state in evidence |
| nnmclub | C10 (FORM_LOGIN, NEEDS .env CREDS) | **BLOCKED** — `.env` does not contain NNMCLUB_USERNAME/PASSWORD | OBTAIN CREDS first; until then NnmclubDescriptor.verified stays false, regardless of matrix readiness |

### Phase 4.2 — `ProviderVerifiedContractTest` re-pin

After each successful matrix-rehearsed flip:
- Move the descriptor from `unverifiedIds` to `verifiedIds`
- Update the per-`@Test` assertion from `assertFalse` to `assertTrue`
- Bump `verified=true` in the descriptor file

### Phase 4.3 — `releases/` artifact rebuild

For the next tag (whenever phases 1–4 are all green):
- Bump app `versionCode` 1021 → 1022, `versionName` 1.2.1 → 1.2.2 (or whatever the next number is)
- Run `./build_and_release.sh` from inside the Containers build path
  (per clause 6.K — once `pkg/emulator/`'s sibling build orchestration
  ships)
- Place artifacts in `releases/<version>/`

## Phase 5 — Submodule Mirror Symmetry

**Why:** 10 of 16 submodules lack GitFlic + GitVerse remotes. Per the
Decoupled Reusable Architecture rule + clause 6.F, every vasic-digital
submodule MUST be mirrored to all 4 upstreams. The asymmetry is itself
a constitutional violation.

### Phase 5.1 — Operator-required: create remote repos

| Action | Target |
|---|---|
| 5.1.1 | Create `vasic-digital/<NAME>` repo on GitFlic for: Challenges, Config, Containers, Discovery, HTTP3, Mdns, Middleware, RateLimiter, Recovery, Tracker-SDK |
| 5.1.2 | Create same on GitVerse |

This is operator-only — agent does not have the credentials to create
new repos under those vendors.

### Phase 5.2 — Agent-runnable: add remotes + push

After 5.1 completes:

| ID | Task | Falsifiability rehearsal |
|---|---|---|
| 5.2.1 | For each of the 10 submodules: `git remote add gitflic git@gitflic.ru:vasic-digital/<NAME>.git`; `git remote add gitverse git@gitverse.ru:vasic-digital/<NAME>.git` | N/A — config |
| 5.2.2 | For each: `git push gitflic main`; `git push gitverse main` | If remote rejects (branch protection), record + remediate |
| 5.2.3 | For each: `git push gitflic lava-pin/2026-05-04-clauses-6IJKL`; `git push gitverse lava-pin/2026-05-04-clauses-6IJKL` | Same |
| 5.2.4 | Verify with `git ls-remote gitflic` + `git ls-remote gitverse` per submodule | Must match GitHub's HEAD |

## Phase 6 — Release Tag

Cuts only after Phases 1–4 are all green AND the matrix attestation is
complete. Phase 5 (mirror symmetry) is desirable but not strictly
release-blocking — the constitutional rule says it MUST be done, but the
release-blocker gate is per clause 6.G/6.I/6.L (matrix attestation +
real-stack verification).

| ID | Task |
|---|---|
| 6.1 | Run `./scripts/ci.sh --full` — must pass cleanly |
| 6.2 | `./scripts/run-emulator-tests.sh --matrix` — produce attestation |
| 6.3 | `./scripts/tag.sh Lava-1.2.X-NNNN` — refuses without matrix attestation |
| 6.4 | Push tag to all 4 upstreams |
| 6.5 | Verify all 4 mirrors converge at the new tag SHA per clause 6.C |

## Phase totals

| Phase | Estimated effort |
|---|---|
| 1 (persistence + test infra) | 2-4 hours |
| 2 (C1-C8 redesign + rehearsal) | 4-8 hours |
| 3 (Containers `pkg/emulator/` + matrix) | 8-16 hours |
| 4 (C9-C12 verification + flips) | 4-8 hours |
| 5 (submodule mirror symmetry, post-operator-action) | 1-2 hours |
| 6 (release tag) | 30 min |

**Realistic single-session progress:** Phase 1 + a sample of Phase 2
(C1 + C3 — the AuthType.NONE-equivalent flows that don't need creds)
is achievable. Phases 3–6 are multi-session work.

## Anti-bluff acceptance for THIS plan

This plan is itself a constitutional artifact. Per clause 6.J: a plan
that lists tests but does not require their falsifiability rehearsal
would be a bluff. Each row in the per-task tables above carries an
explicit "falsifiability rehearsal" column. **A phase is incomplete if
the rehearsal column is unfilled. The plan is a contract, not a wish.**
