# Full Anti-Bluff Proofing Plan

**Date:** 2026-05-08
**Status:** Draft
**Author:** Agent assessment after OkHttp build fix session

---

## Assessment: NOT Bluff-Proofed (Evidence)

### Blocking (must fix before ANY release)

| # | Issue | Severity | Evidence |
|---|-------|----------|----------|
| 1 | `:core:auth:impl` test compilation failure | BLOCKER | `FakePreferencesStorage` does not implement `getDeviceId()`. `./gradlew test` fails. |
| 2 | All 16 submodules have uncommitted constitution propagation | BLOCKER | `git status` shows all 16 submodules/ as `-dirty` (modified CLAUDE.md/AGENTS.md/CONSTITUTION.md from §6.U/6.V/6.W propagation) |
| 3 | Parent repo CLAUDE.md, AGENTS.md, CONTINUATION.md have uncommitted §6.U/V/W changes | BLOCKER | Uncommitted edits sitting in working tree |
| 4 | GitFlic + GitVerse remotes still configured | §6.W VIOLATION | `git remote -v` shows `gitflic`, `gitverse`, `upstream` (gitflic) remotes |
| 5 | `origin` remote has multi-target push URLs mixing all 4 providers | §6.W VIOLATION | `origin` pushes to GitHub + GitLab + GitFlic + GitVerse simultaneously |

### High (features exist but verification is weak or absent)

| # | Issue | Severity | Evidence |
|---|-------|----------|----------|
| 6 | Challenge Tests C17-C22 never executed | HIGH | CONTINUATION.md §4.5.4 confirms they "require the §6.I emulator matrix to execute" |
| 7 | §6.I emulator matrix never run | HIGH | CONTINUATION.md §2.1 — blocked on operator hardware |
| 8 | No git tags cut for any 1.2.x release | HIGH | Last tag is Lava-Android-1.1.3-1013, current is 1.2.13-1033 |
| 9 | No real-device verification evidence for 1.2.x | HIGH | CONTINUATION.md §2.1 — requires operator hands-on |
| 10 | No recent bluff hunt executed | HIGH | Last bluff hunt not documented; §6.N requires per-phase hunt |
| 11 | Many tests may be bluffs (pass while feature broken) | HIGH | Historical pattern per §6.L — "all tests green, most features broken" has happened repeatedly |

### Medium (known but not blocking immediate safety)

| # | Issue | Severity | Evidence |
|---|-------|----------|----------|
| 12 | `/api/v1/search` route resolution needs operator verification | MED | CONTINUATION.md §4.5.1 |
| 13 | `/health` intermittent timeout + UDP buffer warning | MED | CONTINUATION.md §4.5.2 |
| 14 | Internet Archive / gutenberg provider status unclear | MED | CONTINUATION.md §4.5.3 |
| 15 | `Engine.Ktor` dead enum cascade | LOW | CONTINUATION.md §4.5.7 |
| 16 | `Endpoint.Mirror` dead LAN-IP branch | LOW | CONTINUATION.md §4.5.8 |
| 17 | `docs/todos/` untracked | LOW | CONTINUATION.md §4.5.9 |
| 18 | IPv4/host:port/schedule literal grep staged | LOW | CONTINUATION.md §4.5.10 |

---

## Phased Plan

### Phase 0: Stabilize — Fix Compilation & Commit Pending Changes

**Goal:** `./gradlew test` passes clean, all pending constitutional changes are committed, all 4 forbidden remotes removed.

**Tasks:**

| # | Task | Details | Verification |
|---|------|---------|-------------|
| 0.1 | Fix `:core:auth:impl` `FakePreferencesStorage.getDeviceId()` | Add the missing method override to the test fake | `./gradlew :core:auth:impl:test` passes |
| 0.2 | Commit all 16 submodule constitution propagation | In each submodule: `git add CLAUDE.md AGENTS.md CONSTITUTION.md` (as present), commit, push to GitHub + GitLab | `git submodule status` shows clean, all 16 pushed to both mirrors |
| 0.3 | Update parent submodule pins to match committed submodule HEADs | `git add submodules/*` after submodule commits | `git diff --cached submodules/` shows new hashes |
| 0.4 | Commit parent CLAUDE.md + AGENTS.md + CONTINUATION.md §6.U/V/W changes | Pending uncommitted edits in parent working tree | `git status` clean |
| 0.5 | Remove GitFlic remote | `git remote remove gitflic` | `git remote -v` shows no gitflic |
| 0.6 | Remove GitVerse remote | `git remote remove gitverse` | `git remote -v` shows no gitverse |
| 0.7 | Remove upstream (GitFlic dup) remote | `git remote remove upstream` | `git remote -v` shows no upstream |
| 0.8 | Fix `origin` remote to push only to GitHub + GitLab | Reset `origin` to single URL, or add separate `github`/`gitlab` push targets | `git remote -v` shows only 2 providers |
| 0.9 | Update scripts/tag.sh and Upstreams/ scripts to use only 2 remotes | Remove GitFlic/GitVerse references | `grep -r 'gitflic\|gitverse' scripts/ Upstreams/` returns empty |
| 0.10 | Push parent to both remotes | `git push github master && git push gitlab master` | Both converge to same SHA |
| 0.11 | Verify SHA convergence across both remotes | `for r in github gitlab; do echo "$r: $(git ls-remote $r master | awk '{print $1}')"; done` | Both report identical SHA |

**Exit criteria:** `./gradlew test` passes. `git status` clean. `git remote -v` shows only GitHub + GitLab. All 16 submodules committed and pushed. No forbidden remotes.

---

### Phase 1: Anti-Bluff Audit of All Existing Tests

**Goal:** EVERY test file verified to catch real defects — not pass while features are broken.

**Tasks:**

| # | Task | Details | Verification |
|---|------|---------|-------------|
| 1.1 | Inventory all test files | `find . -name '*Test.kt' -o -name '*Test.java' -o -name '*_test.go' | sort` | Complete list documented |
| 1.2 | Random sample 5 test files, run falsifiability rehearsal (Seventh Law clause 5) | For each: (a) read the test, (b) deliberately break the production code it claims to cover, (c) confirm test FAILS with clear assertion message, (d) revert mutation, (e) document result | 5 files × fail-confirmed in `.lava-ci-evidence/bluff-hunt/2026-05-08.json` |
| 1.3 | Check every test for §6.J clause 3 compliance (primary assertion on user-visible state) | Grep for `verify ` patterns, `assertEquals` on internal state, mock assertions as primary | List of tests that need refactoring |
| 1.4 | Refactor identified bluff tests | Replace mock-only assertions with real-stack, user-visible state assertions | Bluff-Audit stamp on every refactored test commit |
| 1.5 | Check for forbidden test patterns (Seventh Law clause 4) | `mockk<SUT>` inside `*Test.kt`, `@Ignore` without tracking issue, tests that build SUT but never invoke methods | List of violations, fix or document |
| 1.6 | Verify all Challenge Tests (C1-C22) have falsifiability rehearsal protocols in their KDoc | Read each Challenge Test file | Every file has documented break-and-confirm-fail procedure in KDoc |

**Exit criteria:** All test files audited. Minimum 5 bluff-hunt mutations confirmed to cause test failures. Zero forbidden patterns. All Challenge KDOCs document falsifiability protocol.

---

### Phase 2: Fix All Known Issues

**Goal:** Every issue in §4.5 of CONTINUATION.md is either fixed (with regression test) or explicitly documented as deferred with a tracking issue.

**Tasks:**

| # | Task | Details | Verification |
|---|------|---------|-------------|
| 2.1 | Fix Challenge Tests C1-C16 if they exhibit bluff behavior | Run each Challenge, deliberately break feature, confirm failure | Each Challenge has documented falsifiability rehearsal |
| 2.2 | Execute C17-C22 on available device/emulator | Run each new provider Challenge Test | All 6 pass or documented failures |
| 2.3 | Verify `/api/v1/search` route resolution | `curl -fsSk 'https://thinker.local:8443/v1/rutracker/search?q=test'` | Returns 200 with search results |
| 2.4 | Document UDP buffer fix for operator | `sudo sysctl -w net.core.rmem_max=7340032` per CONTINUATION.md §4.5.2 | Operator note added to docs/ |
| 2.5 | Verify Internet Archive + gutenberg provider status | Test via API call or Challenge Test | Provider works or documented limitation |
| 2.6 | Clean up `Engine.Ktor` dead enum (if touching relevant code) | Remove enum value + prune exhaustive when branches | Compiles without `Ktor` references |
| 2.7 | Clean up `Endpoint.Mirror` dead branch (if touching relevant code) | Prune dead routing branch | Compiles clean |
| 2.8 | Commit or gitignore `docs/todos/` | Operator decision | Tracked properly |

**Exit criteria:** All HIGH and MEDIUM issues resolved or explicitly deferred with tracking. CONTINUATION.md §4.5 updated.

---

### Phase 3: Emulator Matrix Execution (§6.I)

**Goal:** All Challenge Tests pass on the mandated emulator matrix inside containers.

**Tasks:**

| # | Task | Details | Verification |
|---|------|---------|-------------|
| 3.0 | Fetch latest `vasic-digital/Containers` submodule | Update pin to latest commit for emulator support | `git submodule update --init submodules/containers` |
| 3.1 | Build emulator containers for API 28, 30, 34, latest | Per Containers submodule tooling | Each container boots and shows `adb devices` |
| 3.2 | Build emulator containers for phone, tablet, TV form factors | Per §6.I matrix requirements | Each form factor boots |
| 3.3 | Execute Challenge Tests C1-C22 on each matrix combination | `scripts/run-emulator-tests.sh --avds=...` | JSON evidence per combination |
| 3.4 | Document per-AVD attestation rows | Record in `.lava-ci-evidence/Lava-Android-<version>/real-device-verification.md` | Matrix coverage table complete |
| 3.5 | Falsifiability rehearsal on each AVD class | Break feature, confirm Challenge fails on each Android version class | Per-version failure evidence |

**Exit criteria:** All 22 Challenge Tests pass on all matrix combinations. Attestation document written with per-AVD rows. Falsifiability rehearsals documented.

---

### Phase 4: Full Bluff Hunt (Seventh Law clause 5)

**Goal:** Systematic verification that every test class actually catches real defects.

**Tasks:**

| # | Task | Details | Verification |
|---|------|---------|-------------|
| 4.1 | Select 5 random test files + 2 production code files from gate-shaping code | Per §6.N: sample includes `scripts/tag.sh` helpers, `scripts/check-constitution.sh`, `submodules/containers/pkg/emulator/` | Selection documented |
| 4.2 | For each file: mutate production code, confirm test fails | Follow Seventh Law clause 5 protocol | Each mutation documented in `.lava-ci-evidence/bluff-hunt/2026-05-08.json` |
| 4.3 | For any test that PASSES when production code is broken: classify as bluff, record incident | Per Seventh Law clause 6 (Bluff Discovery Protocol) | Incident recorded in `.lava-ci-evidence/sixth-law-incidents/` |
| 4.4 | Fix all discovered bluffs | Rewrite test to catch the real defect | Bluff-Audit stamp on fix commit |

**Exit criteria:** 5+ production mutations cause test failures. Zero bluffs found, or all bluffs fixed and documented.

---

### Phase 5: Full CI Gate & Real-Device Verification (Seventh Law clause 2-3)

**Goal:** Complete the pre-tag evidence pack.

**Tasks:**

| # | Task | Details | Verification |
|---|------|---------|-------------|
| 5.0 | Operator action: hardware/device access | Connect Android device or authorize emulator matrix run | Device visible via `adb devices` |
| 5.1 | Run full CI gate | `./scripts/ci.sh --full` | All gates pass, evidence written to `.lava-ci-evidence/` |
| 5.2 | Real-device manual smoke test | Login + search + browse + download on real device against thinker.local | Recorded in real-device-attestation.json |
| 5.3 | Take 3+ screenshots or video | Document user-visible behavior per Seventh Law clause 3 | Screenshots hashed and referenced |
| 5.4 | Pepper rotation | Append new pepper per `docs/RELEASE-ROTATION.md` | `.lava-ci-evidence/distribute-changelog/firebase-app-distribution/pepper-history.sha256` updated |
| 5.5 | Verify pre-tag evidence pack complete | Check all required files: `ci.sh.json`, `challenges/C{1..22}.json` at VERIFIED, `mirror-smoke/`, `bluff-audit/`, `real-device-verification.md` | `scripts/tag.sh` (dry-run) does not refuse |

**Exit criteria:** Full evidence pack populated. Real-device verification documented.

---

### Phase 6: Rebuild API + Client, Redistribute, Reboot

**Goal:** Shippable artifacts with constitutional compliance.

**Tasks:**

| # | Task | Details | Verification |
|---|------|---------|-------------|
| 6.1 | Rebuild Go API | `cd lava-api-go && make build` | Binary at `lava-api-go/bin/lava-api-go` |
| 6.2 | Rebuild Go API Docker image | `cd lava-api-go && make image` | Image `lava-api-go:dev` |
| 6.3 | Rebuild Android APK (debug + release) | `./gradlew :app:assembleDebug :app:assembleRelease` | APKs in `app/build/outputs/apk/` |
| 6.4 | Rebuild proxy fat JAR | `./gradlew :proxy:buildFatJar` | JAR at `proxy/build/libs/app.jar` |
| 6.5 | Copy artifacts to `releases/` | `./build_and_release.sh` | `releases/<version>/` populated |
| 6.6 | Cut git tag (Android) | `scripts/tag.sh --app android` | Tag `Lava-Android-<vname>-<vcode>` pushed to both mirrors |
| 6.7 | Cut git tag (API) | `scripts/tag.sh --api go` | Tag `Lava-API-Go-<vname>-<vcode>` pushed to both mirrors |
| 6.8 | Distribute via Firebase App Distribution | `scripts/firebase-distribute.sh` | New version on Firebase with changelog |
| 6.9 | Verify post-push SHA convergence | Check both mirrors report same tag SHA | Converged |
| 6.10 | Reboot API container | `./stop.sh && ./start.sh` | `curl https://thinker.local:8443/health` returns 200 |
| 6.11 | Verify API functional after reboot | Smoke test: search, auth, health | All endpoints respond correctly |

**Exit criteria:** Git tags cut on both mirrors. APK on Firebase with proper versionCode and changelog. API container restarted and healthy.

---

## Constitutional Reinforcement (if needed after audit)

After phases 1-6, if any gaps are found in the existing constitutional clauses (§6.J, §6.L, Sixth Law, Seventh Law), this section documents the amendments.

| # | Clause | Current state | Amendment needed? |
|---|--------|--------------|-------------------|
| A | §6.J (Anti-Bluff Functional Reality) | Present in CLAUDE.md + AGENTS.md | Verify during Phase 1 |
| B | §6.L (Operator's Standing Order) | Present in CLAUDE.md + AGENTS.md | Verify during Phase 1 |
| C | Sixth Law (Real User Verification) | Present in CLAUDE.md + AGENTS.md | Verify during Phase 1 |
| D | Seventh Law (Anti-Bluff Enforcement) | Present in CLAUDE.md + AGENTS.md | Verify during Phase 1 |

The user's mandate — "execution of tests and Challenges MUST guarantee the quality, the completion and full usability by end users of the product" — is already codified in §6.J and §6.L. Phase 1 will determine if the mechanical enforcement is adequate.

---

## Timeline Estimate

| Phase | Effort | Dependencies | Can be parallelized? |
|-------|--------|-------------|---------------------|
| 0 | ~2-3 hours | None | Submodule commits can run in parallel |
| 1 | ~4-6 hours | Phase 0 (clean build needed) | Test audit + bluff hunt in parallel |
| 2 | ~3-4 hours | Phase 1 (must know which tests are real) | Task 2.3 (API verify) overlaps |
| 3 | ~4-8 hours | Phase 2, Containers submodule, operator hardware | Matrix cells in parallel |
| 4 | ~2-3 hours | Phase 1 (fresh audit after fixes) | Bluff hunt independent of phase 3 |
| 5 | ~1-2 hours | Phases 1-4, operator device/hardware | Manual steps (operator) |
| 6 | ~2-3 hours | Phase 5 | Builds in parallel (API + Android) |
| **Total** | **~18-29 hours** | | |

**Parallelizable groups:**
- Phase 0.1 (auth test fix) is independent of 0.2-0.9 (submodule/git cleanup)
- Phase 1 tasks can run in parallel across multiple agents
- Phase 3 matrix cells are embarrassingly parallel
- Phase 6.1+6.2 (Go build) and 6.3+6.4 (Android build) can run simultaneously

---

## Fallback

If operator hardware/emulator access is unavailable:
- **Phase 3** is replaced by: "Execute C17-C22 on any available device, document gaps for emulator matrix"
- **Phase 5.2** (real-device smoke test) is replaced by: "Document which steps require operator hands-on"
- **Phase 6.6-6.7** (tag cutting) uses `--no-evidence-required` as a dry run, with the caveat that tagging is NOT complete without the evidence pack
