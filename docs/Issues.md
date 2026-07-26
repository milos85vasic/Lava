## LVA-3 — Adopt HelixConstitution §11.4.79–106 (pin 53 commits behind)

**Status:** In progress
**Type:** Task
**Severity:** P1

Pin 208e2c8 is 53 commits behind origin/main 883ccc1. Highest-impact new clauses: §11.4.93/95/106 (workable-items SQLite DB tracked in git + md to DB sync engine), §11.4.79 (own-org submodules in CodeGraph), §11.4.85 (stress/chaos), §11.4.98, §11.4.102. Pin-bump is operator-gated; decision owed on §6.AD.3 Path B vs SQLite DB. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-5 — Rotate Firebase CI token (printed to session transcript)

**Status:** Operator-blocked
**Type:** Bug
**Operator-Block-Details:** WHAT: RuTracker CI Firebase token rotation WHY: Agent cannot rotate credentials; only the operator can run firebase logout / firebase login:ci UNBLOCK: Operator rotates the Firebase CI token (no token value ever committed, §11.4.10): [A] run "firebase logout" then "firebase login:ci" and write the new token to LAVA_FIREBASE_TOKEN in the gitignored .env (recommended); [B] mint a fresh token via "firebase login:ci --no-localhost" in a clean shell and update LAVA_FIREBASE_TOKEN in .env; [C] rotate via the Firebase console (Project Settings -> Service accounts) and update .env. Unblock signal: a Firebase distribute run authenticates with the new token and the previously-leaked token no longer authenticates. WHO: Operator
**Severity:** P0

67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json

## LVA-008 — C11 search_input NavBackStackEntry teardown crash (nested-NavHost lifecycle)

**Status:** In progress
**Type:** Bug
**Severity:** P1
**Created-By:** AI

Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json

## LVA-036 — llm_orchestrator github↔gitlab pre-existing mirror fork (non-FF) blocks §6.W convergence

**Status:** Operator-blocked
**Type:** Task
**Operator-Block-Details:** WHAT: github vs gitlab diverged at d2a2151 with unique non-doc go.mod content each; non-FF. No force-push per 6.T.3. Needs operator content-merge decision. WHY: (not enumerated) UNBLOCK: Operator chooses the content-merge resolution (no force-push, §11.4.113 merge-onto-latest-main): [A] keep github go.mod content as canonical, merge gitlab unique commits on top, push the converged SHA to both mirrors; [B] keep gitlab go.mod content as canonical, merge github unique commits on top, push the converged SHA to both mirrors; [C] hand-author the union of both go.mod files, commit the reconciliation, push that common SHA to github + gitlab. Unblock signal: github and gitlab report the same HEAD SHA for llm_orchestrator. WHO: AI
**Severity:** P2

github/master and gitlab/master diverged at d2a2151 with unique non-doc go.mod content each; LVA-030 commit landed gitlab+working-tree but github refused non-FF. Needs a content-merge decision (operator-gated, NO force-push per §6.T.3).

## LVA-079 — Video #3 — search-input chips vs results-filter chips disagree + results chip set non-deterministic run-to-run

**Status:** In progress
**Type:** Bug
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

QA video 2026-06-25 (frames 0040 vs 0060 vs 0125): input chip bar and results filter chips show different provider sets, and the results chip set CHANGES between two identical queries. 1076 fixed the INPUT chips (observeAll filtered+sorted) but the input-vs-RESULTS divergence + run-to-run instability is distinct and still open. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md issue #3.

## LVA-081 — Fetch/pull/merge latest from all submodules + build-verify (operator directive 2026-06-26)

**Status:** In progress
**Type:** Task
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

Operator directive: fetch+pull+merge latest codebase from all submodules. Frozen-by-default overridden for this cycle. Must build-verify after each bump; revert+report any submodule whose bump breaks the build.

## LVA-082 — Crashlytics read NOT available via Firebase CLI — only symbols/mappingfile upload; no issues:list; bq absent

**Status:** Queued
**Type:** Task
**Severity:** P3
**Created-By:** AI

Operator asked to use Firebase CLI to pull Crashlytics. Verified Firebase CLI 14.17.0 exposes only crashlytics:symbols:upload + mappingfile:* (NO issue/non-fatal read). bq CLI absent. Crashlytics dashboard read requires console or a BigQuery export not configured. Fallback: in-repo §6.AC telemetry + known crash tickets; operator to paste console items for full triage. §11.4.6 honest record.

## LVA-083 — Video #1 — Search returns ZERO results then 'Something went wrong' Error (primary function unusable)

**Status:** In progress
**Type:** Bug
**Severity:** P0
**Created-By:** AI
**Assigned-To:** AI

QA video 2026-06-25 frames 0060-0140: every search fails (blank ~25s then Error/Retry; 'prince' stays blank). KNOWN-class (anonymous/provider-mismatch). CODE-FIX landed in 1076 (SearchInputViewModel observeAll filtered+sorted + loading/empty state) but 1076 NOT yet distributed/device-verified. Pending §6.Z gate. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #1.

## LVA-084 — Video #2 — Onboarded provider (YTS) is NOT the provider set used by Search; unconfigured providers active as filters

**Status:** In progress
**Type:** Bug
**Severity:** P0
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0030 vs 0040/0060: onboarded only YTS but search used RuTracker/RuTor/IA/Gutenberg etc. KNOWN (§6.L 57th/59th). CODE-FIX in 1076 (chips from ProviderConfigRepository.observeAll() searchEnabled&&isEnabled). Pending §6.Z device verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #2.

## LVA-085 — Video #4 — Provider id labels shown raw/lowercased ('torrentdownloads','archiveorg','kinozal','yts') in results filter chips

**Status:** In progress
**Type:** Bug
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0060/0125/0130: results chips render internal provider key not displayName. KNOWN-class (§6.L 60th displayLabel). CODE-FIX in 1076 (friendly chip names). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #4.

## LVA-086 — Video #5 — No empty-state and no loading indicator on search results (perceived hang)

**Status:** In progress
**Type:** Bug
**Severity:** P1
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0060-0110: pure blank ~25s, no spinner/skeleton/no-results; 'prince' stays blank with no error. NEW. CODE-FIX in 1076 (loading/empty state branches). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #5.

## LVA-087 — Video #6 — Welcome claims '4 providers available' but picker lists ~12

**Status:** In progress
**Type:** Bug
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0007/0010/0015 vs 0020-0030. NEW. CODE-FIX in 1076 (#6 count bound to real descriptor list). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #6.

## LVA-088 — Video #7 — 'Choose your API' shows 'lava.app:7777' preset + mislabeled 'On this network'

**Status:** In progress
**Type:** Bug
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0012/0015. 1076 investigation: NOT a §6.R hardcoding violation (config-driven preset). 'On this network' label for a cloud/remote preset still worth confirming. Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #7.

## LVA-089 — Video #8 — mDNS-discovered API shows raw IP 192.168.0.107:8443 with no friendly name

**Status:** In progress
**Type:** Bug
**Severity:** P2
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0012/0015. NEW UX. CODE-FIX in 1076 (discovered-API friendly name). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #8.

## LVA-090 — Video #9 — Onboarding 'Select all' silently enables auth-requiring (Captcha/Form Login) providers

**Status:** In progress
**Type:** Bug
**Severity:** P3
**Created-By:** AI
**Assigned-To:** AI

QA video frames 0020-0025. NEW UX, contributes to #1. CODE-FIX in 1076 (#9 select-all handling). Pending §6.Z verification. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #9.

## LVA-091 — Video #10 — App-ID co-mingling (debug .dev + release both labeled 'Lava') — UNCONFIRMED in video

**Status:** Queued
**Type:** Bug
**Severity:** P3
**Created-By:** AI

QA video frames 0001/0005: single Lava icon launched; co-mingling NOT visually confirmed. Needs on-device package check (applicationIdSuffix .dev + launcher label). OPEN/UNCONFIRMED. Source: .lava-ci-evidence/video-analysis/2026-06-25-lava-issues-video.md #10.

## LVA-009 — lava-api-go source-hash manifest stale after go.mod replace additions

**Status:** Queued
**Type:** Bug

Bug P0 lava-api-go: TestSourceHash_ManifestMatchesLive fails because the embedded source-hash manifest does not match current lava-api-go source after adding replace directives for docprocessor, llmorchestrator, llmprovider, visionengine. Rebuild via build-cshared.sh and commit refreshed manifest.

## LVA-010 — lava-api-go TestVersionBinaryContract crashes Go compiler on modernc.org/sqlite

**Status:** Queued
**Type:** Bug

Bug P0 lava-api-go: TestVersionBinaryContract_MatchesVersionPackage fails with a runtime GC panic inside the Go compiler while compiling modernc.org/sqlite/lib. This appears to be a transient runtime resource issue or a toolchain bug with Go 1.26.0 plus modernc.org/sqlite v1.53.0. Needs isolation, resource-limit tuning, and possibly a toolchain SDK bump.

## LVA-011 — lava-api-go missing replace directives for HelixQA transitive local modules

**Status:** Queued
**Type:** Bug

Bug P0 lava-api-go: submodules/helixqa depends on digital.vasic.docprocessor, llmorchestrator, llmprovider, visionengine. lava-api-go/go.mod did not replace these local vasic-digital modules, so go mod tidy and tests failed with network resolution errors. Replace directives were added; go mod tidy succeeded.

## LVA-012 — 6.R scanner flags generated modernc-libc vendored files

**Status:** Queued
**Type:** Bug

Bug P1 lava-api-go: scripts/scan-no-hardcoded-uuid.sh, ipv4.sh, and hostport.sh flagged lava-api-go/third_party/modernc-libc generated/vendored files containing UUID mailing-list IDs and IPv4-looking section numbers. Added file-level exemption matching the submodules/ exemption pattern.

## LVA-013 — Missing 6.Z device evidence for client 1080 and api-app 24

**Status:** Queued
**Type:** Task

Task P0 Android: the 1080 client and 24 api-app cycles were distributed without per-AVD containerized emulator evidence. Need to execute the covering Challenge matrix, generate real-device-verification rows, and backfill the evidence files.

## LVA-014 — Device gate durable fixes for AVD name, WaitForBoot liveness, matrix images

**Status:** Queued
**Type:** Task

Task P0 Android/Containers: emulator matrix runner still suffers from AVD-name collisions, WaitForBoot liveness gaps, and stale/out-of-sync container images. Port fixes from containers submodule and validate on the gating matrix.

## LVA-015 — Crashlytics nested-scroll fatal unresolved

**Status:** Queued
**Type:** Bug

Bug P0 Android: Crashlytics issue for nested-scroll fatal in tracker settings is still open. Add regression Challenge and structural test, then close the issue with a closure log.

## LVA-016 — Submodule upstream divergence

**Status:** Queued
**Type:** Task

Task P0 infra: llm_orchestrator (14), llm_provider (11), llms_verifier (36), security (6), vision_engine (20) are behind upstream. Evaluate changes, bump pins deliberately, and run cross-repo tests.

## LVA-017 — CONTINUATION.md stale after gap sweep

**Status:** Queued
**Type:** Task

Task P1 docs: docs/CONTINUATION.md last-updated line and open-issue list are stale relative to the 2026-07-26 gap sweep. Update banner and append new findings.

## LVA-018 — Missing script docs per 11.4.18

**Status:** Queued
**Type:** Task

Task P1 docs: several scripts introduced in recent cycles lack external user guides under docs/scripts/<name>.md. Audit all scripts and backfill docs.

## LVA-019 — Coverage ledger partial/gap overlap and missing per-release ledgers

**Status:** Queued
**Type:** Task

Task P1 process: coverage ledger has partial/gap overlap and lacks per-release ledger snapshots. Normalize the registry and add release-attestation ledgers.

