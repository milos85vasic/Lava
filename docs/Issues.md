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

**Status:** In progress
**Type:** Bug
**Severity:** P1
**Created-By:** AI

Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json

## LVA-036 — llm_orchestrator github↔gitlab pre-existing mirror fork (non-FF) blocks §6.W convergence

**Status:** Operator-blocked
**Type:** Task
**Severity:** P2

github/master and gitlab/master diverged at d2a2151 with unique non-doc go.mod content each; LVA-030 commit landed gitlab+working-tree but github refused non-FF. Needs a content-merge decision (operator-gated, NO force-push per §6.T.3).

## LVA-067 — persist per-row providerId on favorite/visited rows so their topics can route to HTTP_DOWNLOAD

**Status:** In progress
**Type:** Task
**Severity:** P3

LVA-052 scoped favorites/visited topic downloads to the active-tracker default because the favorite/visited Room rows store no provider id; add a providerId column + migration so an archiveorg/gutenberg favorite routes to HTTP_DOWNLOAD.

## LVA-070 — thread source providerId through favorite/visited write+read path to complete HTTP_DOWNLOAD routing

**Status:** Queued
**Type:** Task
**Severity:** P3

LVA-067 laid the providerId Room column; complete it by threading providerId through ToggleFavoriteUseCase/AddLocalFavoriteUseCase/GetTopicUseCase + the Topic/TopicPage models (write) and TopicModel + favorites/visited side-effects to openTopic(id,providerId) (read), mirroring search_result.

## LVA-071 — inject SseClient + base-URL into SearchResultViewModel.observeSseSearch for hermetic MockWebServer testing

**Status:** Queued
**Type:** Task
**Severity:** P3

observeSseSearch internally constructs the SseClient with a hardcoded https base; inject it so the full SSE error to Error to retry path is hermetically testable against a MockWebServer.

## LVA-072 — §6.AC telemetry on mid-process embed TLS cert rotation (LVA-068 swap is silent)

**Status:** Queued
**Type:** Task
**Severity:** P3

LVA-068 rotates the embed leaf mid-process but the swap is silent; emit a RecordWarning on rotation with feature/operation/old+new NotAfter/IP-SANs context (no secrets per §6.H). A wave-10 attempt was discarded because its falsifiability rehearsal was inconclusive and the SourceHash contract failed; redo with a test that fails when the actual rotation-path RecordWarning call is removed.

