## LVA-3 — Adopt HelixConstitution §11.4.79–106 (pin 53 commits behind)

**Status:** In progress
**Type:** Task
**Severity:** P1

Pin 208e2c8 is 53 commits behind origin/main 883ccc1. Highest-impact new clauses: §11.4.93/95/106 (workable-items SQLite DB tracked in git + md to DB sync engine), §11.4.79 (own-org submodules in CodeGraph), §11.4.85 (stress/chaos), §11.4.98, §11.4.102. Pin-bump is operator-gated; decision owed on §6.AD.3 Path B vs SQLite DB. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-4 — LVA workable-items ticket system (SQLite DB + Go CLI + md/HTML/PDF/DOCX export)

**Status:** In progress
**Type:** Feature
**Severity:** P1

HelixConstitution §11.4.93/95/106 materialization. Go CLI (modernc.org/sqlite, no CGO) with init/add/update/close/reopen/gen/verify/import/export. Operator directive §6.L 68th invocation, key prefix LVA. Superseded by migration to the canonical constitution binary (docs/tickets/MIGRATION-TO-CANONICAL.md). **Source:** operator-report — docs/tickets/DESIGN.md

## LVA-5 — Rotate Firebase CI token (printed to session transcript)

**Status:** Operator-blocked
**Type:** Bug
**Severity:** P0

67th-cycle: the LAVA_FIREBASE_TOKEN default-expansion printed the token to the session transcript (NOT committed to git). Operator MUST rotate per §6.H clause 6 (firebase logout; firebase login:ci). **Source:** self-discovered — .lava-ci-evidence/sixth-law-incidents/2026-05-20-firebase-token-echo-leak.json

## LVA-008 — C11 search_input NavBackStackEntry teardown crash (nested-NavHost lifecycle)

**Status:** Queued
**Type:** Bug
**Severity:** P1
**Created-By:** AI

Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json

## LVA-036 — llm_orchestrator github↔gitlab pre-existing mirror fork (non-FF) blocks §6.W convergence

**Status:** Operator-blocked
**Type:** Task
**Severity:** P2

github/master and gitlab/master diverged at d2a2151 with unique non-doc go.mod content each; LVA-030 commit landed gitlab+working-tree but github refused non-FF. Needs a content-merge decision (operator-gated, NO force-push per §6.T.3).

## LVA-052 — Wire HTTP_DOWNLOAD into the topic/download UI (consume HttpDownloadableTracker, write artifact to disk)

**Status:** Queued
**Type:** Feature
**Severity:** P3

HTTP_DOWNLOAD UI wiring. A full impl exists on branch worktree-agent-ad695086c8735d60a (commit c8951eee) but it re-derived LVA-044 substrate → conflicts with master's landed LVA-044; needs a clean re-apply of the LVA-052 net-new files (HttpDownloadSource/DownloadHttpFileUseCase/DownloadService.downloadHttpFile/SDK.downloadHttpFile) on top of master. Topic-screen Compose routing (provider id through nav) still OWED.

## LVA-054 — Audit codebase for removeLast/removeFirst/getFirst/getLast SequencedCollection calls that crash on Android <API35

**Status:** Queued
**Type:** Task
**Severity:** P1

LVA-053 class: Kotlin stdlib these desugar to java.util.* methods absent below API35. Sweep all modules + add a lint/detekt guard.

## LVA-055 — run-test-pg.sh integration list omits ./internal/storage (Postgres legs skipped by canonical harness)

**Status:** Queued
**Type:** Task
**Severity:** P3

scripts/run-test-pg.sh runs only ./internal/cache + ./tests/integration; add ./internal/storage so its Postgres legs run in the canonical harness.

