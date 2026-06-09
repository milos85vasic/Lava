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

## LVA-6 — §11.4.79 reconcile codegraph index policy (own-org submodules IN index)

**Status:** Queued
**Type:** Task
**Severity:** P2

§11.4.79 (new) requires own-org submodules IN the codegraph index; Lava currently EXCLUDES submodules/ per docs/CODEGRAPH.md + 63rd-cycle policy. Reconcile .codegraph config + docs when pin is bumped. **Source:** self-discovered — .lava-ci-evidence/constitution-review/2026-05-31-68th-cycle-review.md

## LVA-008 — C11 search_input NavBackStackEntry teardown crash (nested-NavHost lifecycle)

**Status:** Queued
**Type:** Bug
**Severity:** P1
**Created-By:** AI

Challenge11ArchiveOrgAnonymousSearchTest crashes the app PROCESS at activity-destroy: IllegalStateException 'State must be at least CREATED to be moved to DESTROYED' on the inner search/search_input entry. Root-caused (2026-06-08): the inner nested NavController's host is the outer addNestedNavigation NavBackStackEntry, driven to DESTROYED out from under the inner controller while search_input is still INITIALIZED. FALSIFIED on device: nav 2.9.1->2.9.8, LenientTeardownRule (uncatchable process death), atomic popUpTo replace. Feature WORKS (result row renders pre-teardown). Candidate fixes ranked in incident JSON (inner NavHost Activity-scoped LifecycleOwner; move search to outer NavHost; ON_STOP pop). Forensics: .lava-ci-evidence/sixth-law-incidents/2026-06-08-navbackstackentry-teardown-crash-2.9.1-incomplete.json

## LVA-025 — v1 captcha login sends the answer under the wrong form-field name (captcha login can never succeed)

**Status:** Queued
**Type:** Bug
**Created-By:** AI

internal/handlers/v1 LoginOpts has only CaptchaCode (used as the answer) but rutracker needs CaptchaCode=dynamic-field-NAME (cap_code_<sid>) + CaptchaValue=answer. The adapter sets both to the answer (self-acknowledged TODO provider.go:221). Needs LoginOpts + OpenAPI model change (CaptchaValue field) — deferred. Found by parallel Go bug-hunt.

## LVA-026 — v1 captcha response hardcodes image/png, discards upstream Content-Type

**Status:** Queued
**Type:** Task
**Created-By:** AI

internal/handlers/v1/captcha.go serves c.Data(200, image/png, ...) but rutracker.FetchCaptcha captures the real Content-Type, dropped by the adapter (no ContentType field on provider.CaptchaImage). Minor (most decoders sniff). Found by parallel Go bug-hunt.

## LVA-028 — Nnmclub search publishDate dropped (date column present + parseable)

**Status:** Queued
**Type:** Bug
**Created-By:** AI

NnmclubSearchParser reads row.select('td') but never maps cells[4] (ISO yyyy-MM-dd date) into TorrentItem.publishDate → Nnmclub results have no date. Found by parallel Kotlin tracker bug-hunt. (Also UNCONFIRMED: nnmclub/kinozal parseSize Latin-only regex may miss Cyrillic units in real HTML — kinozal already handles both as of LVA-027.)

## LVA-029 — isLocalHost() fc/fd false-positive misclassifies public hosts as IPv6 unique-local

**Status:** Queued
**Type:** Bug
**Created-By:** AI

core/models HostUtils.isLocalHost runs the fc00::/7 unique-local check (startsWith fc/fd + take(4) hex in 0xfc00..0xfdff) on ANY host string without requiring an IPv6 literal, so a DNS host like fcba.example.com / fdcdn.net is routed as LAN (http://host:8080) instead of https://host/forum/ → no green dot, every request fails. Fix: gate on contains(':') before the hex parse. Found by parallel core/network bug-hunt.

## LVA-030 — 6 recently-added submodules missing §6.R inheritance pointer (pre-existing §6.AD-debt)

**Status:** Queued
**Type:** Task
**Created-By:** AI

doc_processor/helixqa/llm_orchestrator/llm_provider/llms_verifier/vision_engine CLAUDE.md lack the §6.R No-Hardcoding inheritance block; scripts/check-constitution.sh full-run exits 1 on them. Pre-existing from the 5-dep + helixqa adoption; orthogonal to the 60e2d66 constitution bump. Each needs a submodule commit + push + parent pin bump (helixqa is always-track-upstream per Q9). Surfaced by full check-constitution during the constitution-bump cycle.

