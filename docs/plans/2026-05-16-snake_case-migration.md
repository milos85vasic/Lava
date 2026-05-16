# Lowercase snake_case Migration Plan — Constitution-Compliance Phase 6

**Status:** PLAN — RESEARCH + AUDIT ONLY (no renames executed in this commit).
**Phase:** Constitution-Compliance plan Phase 6 (per `docs/plans/2026-05-15-constitution-compliance.md`).
**Authority:** HelixConstitution §11.4.29 (Lowercase-Snake_Case-Naming Mandate, 2026-05-15).
**Companion artifacts:** `scripts/audit-snake_case-references.sh` (audit tool), `tests/check-constitution/test_audit_snake_case_references.sh` (hermetic test), `docs/scripts/audit-snake_case-references.sh.md` (user guide).
**Classification:** project-specific (rename targets + reference inventory are Lava-specific; the rename-with-reference-update discipline is universal per §11.4.29).
**Inheritance:** §11.4.29 + §11.4.17 + §11.4.18 + §11.4.20 + §11.4.26 + §11.4.27 + §11.4.32 + §6.J/§6.L (anti-bluff) + §6.W (mirror policy) + Lava's §6.AD HelixConstitution Inheritance.

---

## 0. Scope statement (no-bluff)

This document is the **RESEARCH + PLAN** deliverable for Phase 6. The actual renames are NOT executed in the commit landing this plan; they are scheduled for follow-up cycles after operator review. Per §11.4.29 itself:

> "Phased execution. Comprehensive brainstorming → phase-divided plan → fine-grained tasks/subtasks with enormous detail, every change covered by every applicable test type."

The deliverables of THIS cycle are:

1. This plan document (per-submodule mapping + risk + phases + rollback).
2. `scripts/audit-snake_case-references.sh` — read-only audit producing a tab-separated baseline reference count.
3. `tests/check-constitution/test_audit_snake_case_references.sh` — hermetic test of the audit script.
4. `docs/scripts/audit-snake_case-references.sh.md` — script user guide per §11.4.18.
5. Updated Phase 6 section in `docs/plans/2026-05-15-constitution-compliance.md` referencing this plan + the audit script.

The rename execution is intentionally NOT in this cycle's diff. Per §6.L (Anti-Bluff Functional Reality Mandate, operator's standing order): renaming 17 submodules + 124 referencing files in a single commit without per-batch verification would be the textbook bluff this constitution forbids. The phased execution below sequences each rename batch so each one's anti-bluff evidence can be captured independently.

---

## 1. Current state baseline (captured 2026-05-16)

Captured by running the audit script (`scripts/audit-snake_case-references.sh`) at branch `plan/2026-05-16-snake-case-migration` HEAD. The full machine-readable output is in §10 of this document and reproducible via `bash scripts/audit-snake_case-references.sh`.

### 1.1 Top-line counts

| Metric | Count |
|---|---|
| Files containing `Submodules/` references | 124 |
| Total `Submodules/` references | 806 |
| Total bare-word `Submodules` references (incl in paths) | 829 |
| Submodules currently registered in `.gitmodules` (vasic-digital + HelixDevelopment) | 17 |
| Constitution submodule (separate, see §3) | 1 |

### 1.2 Per-submodule reference inventory

| Submodule (current name) | Target name (snake_case) | Owner org | Refs (count) | Ref-bearing files | Rename complexity |
|---|---|---|---|---|---|
| `Auth` | `auth` | vasic-digital | 16 | 9 | LOW |
| `Cache` | `cache` | vasic-digital | 36 | 15 | LOW |
| `Challenges` | `challenges` | vasic-digital | 14 | 8 | LOW |
| `Concurrency` | `concurrency` | vasic-digital | 5 | 4 | LOW |
| `Config` | `config` | vasic-digital | 10 | 6 | LOW |
| `Containers` | `containers` | vasic-digital | **370** | **63** | **HIGH** (matrix runner, emulator orchestration, gate-shaping code) |
| `Database` | `database` | vasic-digital | 8 | 5 | LOW |
| `Discovery` | `discovery` | vasic-digital | 5 | 4 | LOW |
| `HelixQA` | `helix_qa` (see §3.1) | HelixDevelopment | 13 | 6 | MEDIUM (HelixDevelopment-owned; cross-org coordination) |
| `HTTP3` | `http3` | vasic-digital | 20 | 7 | LOW |
| `Mdns` | `mdns` | vasic-digital | 14 | 7 | LOW |
| `Middleware` | `middleware` | vasic-digital | 14 | 6 | LOW |
| `Observability` | `observability` | vasic-digital | 29 | 8 | LOW |
| `RateLimiter` | `rate_limiter` | vasic-digital | 50 | 13 | MEDIUM (hyphen-equivalent + casing change) |
| `Recovery` | `recovery` | vasic-digital | 12 | 6 | LOW |
| `Security` | `security` | vasic-digital | 15 | 6 | LOW |
| `Tracker-SDK` | `tracker_sdk` | vasic-digital | **83** | **25** | **HIGH** (hyphen→underscore + composite-build Gradle includeBuild + lava-api-go go.mod replace directives) |

**Total per-submodule references:** 714 (close to but not equal to the 806 `Submodules/` total because some references count `Submodules/` paths that don't include a specific submodule name — e.g. `Submodules/<name>/...` regex matches some bare-prefix lines).

### 1.3 Top files by `Submodules/` reference count

The rename will rewrite content in these files most heavily:

| File | Refs |
|---|---|
| `docs/superpowers/plans/2026-05-05-anti-bluff-mandate-reinforcement-group-b.md` | 77 |
| `docs/superpowers/specs/2026-04-28-sp2-go-api-migration-design.md` | 61 |
| `docs/superpowers/plans/2026-05-05-anti-bluff-mandate-reinforcement-group-a-prime.md` | 54 |
| `docs/superpowers/plans/2026-04-28-sp2-go-api-migration.md` | 43 |
| `docs/superpowers/plans/2026-05-06-phase1-api-auth.md` | 41 |
| `docs/superpowers/plans/2026-05-05-pkg-vm-image-cache.md` | 38 |
| `CLAUDE.md` | 35 |
| `.gitmodules` | 34 |
| `scripts/check-constitution.sh` | 29 |

### 1.4 Distribution by top-level directory

| Directory | Files affected |
|---|---|
| `docs/` (including superpowers specs + plans + scripts) | 41 |
| `.lava-ci-evidence/` | 27 |
| `lava-api-go/` (Go service incl. go.mod replace directives) | 20 |
| `scripts/` | 16 |
| `tools/` | 5 |
| `tests/` | 5 |
| `core/` (Lava-side) | 2 |
| `start.sh` | 1 |
| `specs/` | 1 |
| `settings.gradle.kts` | 1 |
| `CLAUDE.md` | 1 |
| `CHANGELOG.md` | 1 |
| `AGENTS.md` | 1 |
| `.gitmodules` | 1 |
| `.githooks/` | 1 |

---

## 2. LANG-spec subtree exemption per §11.4.29

§11.4.29 carves out language/technology-mandated case from the snake_case rule. The exemption applies INSIDE a language-root subtree; the submodule's root directory still follows our convention.

### 2.1 Lava-side exempt subtrees (`PascalCase` or camelCase preserved)

| Subtree | Reason | Exempt content |
|---|---|---|
| `app/src/**/*.kt` | Kotlin compiler requires `PascalCase` class files | All `*.kt` source files, all package directories (already lowercase) |
| `core/**/*.kt` | Kotlin | Same as above |
| `feature/**/*.kt` | Kotlin | Same as above |
| `app/src/main/res/drawable*/`, `mipmap*/`, etc. | Android resource directories use mandated names | `drawable-*dpi`, `mipmap-anydpi-v26`, etc. |
| `app/src/main/res/values*/strings.xml` (etc.) | Android resource value naming | XML resource files |
| `buildSrc/src/main/kotlin/*.kt` | Kotlin convention plugin file naming | `*.kts` convention plugins (e.g. `lava.android.application.gradle.kts`) |
| `lava-api-go/**/*.go` | Go source: file names are lowercase but use `_` in standard Go style — already compliant | Confirmed Go-idiomatic |
| `docker/` and `Dockerfile*` | Docker tooling convention `Dockerfile` is the canonical name | Already established |
| `Gradle wrapper`: `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Gradle-mandated naming | Already standard |
| `.git/`, `.githooks/`, `.gitmodules`, `.gitignore` | Git-mandated dotfile names | Already standard |
| `node_modules/` (if ever introduced) | npm-mandated | N/A currently |

### 2.2 Per-submodule LANG-spec roots

Each submodule's internal source-language layout is exempt inside its root directory. The submodule's **root directory itself** MUST be snake_case (the whole point of §11.4.29).

| Submodule | Primary language | Exempt internal subtrees |
|---|---|---|
| `Auth`, `Cache`, `Challenges`, `Concurrency`, `Config`, `Database`, `Discovery`, `HelixQA`, `HTTP3`, `Mdns`, `Middleware`, `Observability`, `RateLimiter`, `Recovery`, `Security`, `Tracker-SDK` | Kotlin (multi-platform / Android / JVM) | `**/*.kt`, `src/main/kotlin/**/`, `src/test/kotlin/**/`, Android `res/`, Gradle conventions |
| `Containers` | Go | `**/*.go`, `cmd/<name>/`, `pkg/<name>/` (already lowercase per Go convention) |

### 2.3 Lava root-level directories that look like they need rename — actually exempt

| Directory | Reason for exemption |
|---|---|
| `Upstreams/` | §11.4.29 specifies this directory: `install_upstreams` supports both `Upstreams/` AND `upstreams/` during migration window. Rename is OWED but the dual-support installer makes it safe to defer. |
| `Submodules/` | THE rename target of Phase 6a — listed here for completeness; the rule applies. |
| `CLAUDE.md`, `AGENTS.md`, `CHANGELOG.md`, `README.md`, `LICENSE` | Convention-mandated file names (Markdown convention + LICENSE convention) — fall under "build-tooling artefacts" / common-sense exemption. |
| `Constitution.md` (if it ever exists at Lava root — currently lives in `constitution/` submodule) | Same as above. |

---

## 3. Per-submodule target name mapping (full table)

Authoritative mapping for Phase 6b. Each rename is a separate sub-cycle.

| # | Current name | Target snake_case | Owner org | Renaming rationale | Special handling |
|---|---|---|---|---|---|
| 1 | `Auth` | `auth` | vasic-digital | Single-word PascalCase → lowercase | None |
| 2 | `Cache` | `cache` | vasic-digital | Same | None |
| 3 | `Challenges` | `challenges` | vasic-digital | Same | Cascading rename: also forwards through `Submodules/Challenges/Panoptic` nested-own-org-chain (§11.4.28 follow-up debt) |
| 4 | `Concurrency` | `concurrency` | vasic-digital | Same | None |
| 5 | `Config` | `config` | vasic-digital | Same | None |
| 6 | `Containers` | `containers` | vasic-digital | Same | **HIGH-RISK** — 370 refs across 63 files. The matrix runner, emulator orchestration, every `Submodules/Containers/pkg/...` reference, gate-shaping code in `scripts/check-constitution.sh` and `scripts/run-emulator-tests.sh`. |
| 7 | `Database` | `database` | vasic-digital | Same | None |
| 8 | `Discovery` | `discovery` | vasic-digital | Same | None |
| 9 | `HelixQA` | `helix_qa` | HelixDevelopment | CamelCase compound word → snake_case with underscore. **See §3.1 for HelixDevelopment-org considerations.** | Cross-org coordination. |
| 10 | `HTTP3` | `http3` | vasic-digital | Acronym + digit: `HTTP3` → `http3` (no underscore between letters and digits — `http_3` would arguably be more idiomatic snake_case but `http3` matches the canonical protocol name) | Decision: `http3` (consistent with `:core:network:rutracker` — single-token in source already) |
| 11 | `Mdns` | `mdns` | vasic-digital | Single-word lowercased acronym | None |
| 12 | `Middleware` | `middleware` | vasic-digital | Same | None |
| 13 | `Observability` | `observability` | vasic-digital | Same | None |
| 14 | `RateLimiter` | `rate_limiter` | vasic-digital | CamelCase compound → snake_case | Word boundary split is unambiguous |
| 15 | `Recovery` | `recovery` | vasic-digital | Same | None |
| 16 | `Security` | `security` | vasic-digital | Same | None |
| 17 | `Tracker-SDK` | `tracker_sdk` | vasic-digital | Hyphen + acronym → underscore + lowercase. **HIGH-RISK** — 83 refs across 25 files including `settings.gradle.kts` `includeBuild("Submodules/Tracker-SDK")` AND `lava-api-go/go.mod` replace directives. | Gradle composite-build path + Go module path must update together |

### 3.1 HelixQA / HelixDevelopment-org consideration

`HelixQA` is owned by `HelixDevelopment`, not `vasic-digital`. §11.4.29 applies universally — all owned-by-us submodules (any org in the operator's list) must follow snake_case. HelixDevelopment IS on the owned-org list (per §11.4.28's verbatim list: vasic-digital, HelixDevelopment, red-elf, ATMOSphere1234321, Bear-Suite, BoatOS123456, Helix-Flow, Helix-Track, Server-Factory).

**Decision: rename to `helix_qa`.** The exemption clause "third-party submodules keep upstream names" does NOT apply because HelixDevelopment is OUR org. The rename is binding.

**Upstream rename consideration.** The submodule lives at `git@github.com:HelixDevelopment/HelixQA.git` upstream. §11.4.29's reach is the local checkout path — the upstream repo name itself ALSO follows §11.4.29 universally, but renaming an upstream GitHub/GitLab repo is a separate, coordinated operation:

- The `.gitmodules` `path` field is what Lava controls — we set this to `submodules/helix_qa`.
- The `.gitmodules` `url` field points at the upstream (`HelixDevelopment/HelixQA`). The upstream itself remains at its existing URL until a separate cross-org coordination renames the upstream repo too.
- After the upstream is renamed (e.g. to `HelixDevelopment/helix_qa` or `HelixDevelopment/helix-qa` per GitHub naming convention), we update `.gitmodules` URLs and migrate. Until then, the local path is snake_case while the upstream URL remains CamelCase — this is the §11.4.29-conformant transitional state (rule applies to "directories under the parent project's working tree"; the `url` field is a network identifier, not a working-tree directory).

**Equivalent for Tracker-SDK.** Same logic: upstream is `vasic-digital/Tracker-SDK`. Local path becomes `submodules/tracker_sdk`. Upstream URL update is a follow-up coordination cycle (vasic-digital is OUR org, so we own the timing).

**Equivalent for every other vasic-digital submodule.** Same pattern — local path is canonical-snake_case; upstream URL update is per-org follow-up.

### 3.2 Constitution submodule special case (already mounted at `constitution/`)

The HelixDevelopment constitution submodule is mounted at `./constitution/` (lowercase, no underscore — single word). It is ALREADY snake_case-compliant. No rename needed. The §11.4.35 canonical-root inheritance pointer in Lava's CLAUDE.md + AGENTS.md continues to reference `constitution/CLAUDE.md`.

---

## 4. Phased execution plan

Per §11.4.29 + §11.4.20 (subagent delegation): each phase below is a separate cycle (separate commit, separate falsifiability rehearsal, separate sweep run). Phases run sequentially because each builds on the previous (e.g. Phase 6b uses the already-renamed `submodules/` path from Phase 6a).

### Phase 6.0 — Plan + audit tool (THIS commit)

**Status:** SHIPPING IN THIS COMMIT.

**Deliverables:**
- This plan document.
- `scripts/audit-snake_case-references.sh` (read-only audit tool).
- `tests/check-constitution/test_audit_snake_case_references.sh` (hermetic test).
- `docs/scripts/audit-snake_case-references.sh.md` (companion doc).
- Updated reference in `docs/plans/2026-05-15-constitution-compliance.md` Phase 6 section.

**Risk:** ZERO — read-only. No renames. Audit script + hermetic test + plan only.

**Exit criteria:**
- Audit script runs successfully on real tree.
- Hermetic test passes.
- `scripts/verify-all-constitution-rules.sh --strict` reports unchanged baseline (no new gate failures).

---

### Phase 6a — `Submodules/` → `submodules/` (the directory itself)

**Scope:** Single rename — the top-level `Submodules` directory. Cascading updates to every reference path.

**Pre-rename audit:**
1. Run `bash scripts/audit-snake_case-references.sh > before.tsv`.
2. Hardlinked `.git` backup per §9.1: `cp -al .git ../.git-backup-pre-snake-case-6a-$(date -u +%Y%m%dT%H%M%SZ)/repo.git.mirror`.

**Rename steps:**
1. `git mv Submodules submodules` (or equivalent: `git mv` each child entry — Git's `git mv` handles renamed-directory tracking automatically).
2. Update `.gitmodules`: every `path = Submodules/<X>` → `path = submodules/<X>` (17 entries).
3. Update `.git/config` `submodule.*.path` mirrored from `.gitmodules`. (Usually done by `git submodule sync`.)
4. Run `git submodule sync` + `git submodule update --init` to refresh `.git/modules` pointers.
5. Update every script/code reference:
   - `scripts/*.sh` — `Submodules/` → `submodules/` (16 files).
   - `lava-api-go/go.mod` — `../Submodules/` → `../submodules/` (replace directives).
   - `settings.gradle.kts` — `includeBuild("Submodules/Tracker-SDK")` → `includeBuild("submodules/Tracker-SDK")`.
   - `start.sh`, `tools/lava-containers/...`, `tests/...`.
   - `CLAUDE.md` (35 refs), `AGENTS.md`, `CHANGELOG.md`, `.githooks/pre-push`.
   - `docs/**/*.md` (41 files — including superpowers specs/plans).
   - `.lava-ci-evidence/**/*` (27 files — these are historical attestations; the rename in evidence files is for consistency, not for correctness).
6. Verify no remaining `Submodules/` references via audit: `bash scripts/audit-snake_case-references.sh > after.tsv && diff before.tsv after.tsv`.

**Post-rename validation:**
1. `./gradlew :app:assembleDebug` — verifies Gradle composite-build picks up the renamed path.
2. `cd lava-api-go && make build` — verifies Go module replace-directives work.
3. `bash scripts/run-emulator-tests.sh --help` (smoke) — verifies matrix runner glue.
4. `bash scripts/verify-all-constitution-rules.sh --strict` — full sweep.
5. `bash tests/check-constitution/test_audit_snake_case_references.sh` — hermetic.

**Falsifiability rehearsal:**
- Deliberately omit ONE rename (e.g. leave `scripts/check-constitution.sh` referencing `Submodules/`). Run audit — confirm it surfaces the residual reference. Restore + verify clean.

**Rollback:**
- `git checkout HEAD -- .` (working tree).
- `git reset --hard HEAD~1` (last commit).
- Restore from `.git-backup-pre-snake-case-6a-...` if `git` itself is somehow corrupted.

**Estimated effort:** 1 focused session. Commit message MUST cite the falsifiability rehearsal per §6.N.

**Operator approval required:** YES — high blast radius. Operator MUST sign off on the rename batch before push to mirrors.

---

### Phase 6b — Per-submodule snake_case renames (17 sub-cycles)

**Strategy:** ONE commit per submodule. Each commit:
1. Renames the directory.
2. Updates `.gitmodules` `[submodule "..."]` heading + `path` field.
3. Updates every reference in tracked files.
4. Runs the audit + post-rename validation.
5. Captures a per-cycle falsifiability rehearsal.

**Ordering** — risk-ascending (small blast radius first to build muscle):

| Order | Submodule | Refs | Risk |
|---|---|---|---|
| 1 | Discovery | 5 | LOW |
| 2 | Concurrency | 5 | LOW |
| 3 | Database | 8 | LOW |
| 4 | Config | 10 | LOW |
| 5 | Recovery | 12 | LOW |
| 6 | HelixQA → helix_qa | 13 | MEDIUM (cross-org) |
| 7 | Challenges | 14 | LOW |
| 8 | Mdns | 14 | LOW |
| 9 | Middleware | 14 | LOW |
| 10 | Security | 15 | LOW |
| 11 | Auth | 16 | LOW |
| 12 | HTTP3 → http3 | 20 | LOW |
| 13 | Observability | 29 | LOW |
| 14 | Cache | 36 | LOW |
| 15 | RateLimiter → rate_limiter | 50 | MEDIUM (compound name) |
| 16 | Tracker-SDK → tracker_sdk | 83 | HIGH (Gradle includeBuild + Go go.mod) |
| 17 | Containers → containers | 370 | HIGHEST (matrix runner + gate-shaping code) |

**Per-cycle pre-rename audit:**
- `bash scripts/audit-snake_case-references.sh > before-<name>.tsv`.
- `cp -al .git ../.git-backup-pre-rename-<name>-$(date -u +%Y%m%dT%H%M%SZ)/repo.git.mirror`.

**Per-cycle rename steps:**
1. `git mv submodules/<OldName> submodules/<new_name>`.
2. Update `.gitmodules`:
   - `[submodule "submodules/<OldName>"]` → `[submodule "submodules/<new_name>"]`.
   - `path = submodules/<OldName>` → `path = submodules/<new_name>`.
3. Update every reference in:
   - `scripts/*.sh` (use `git grep -l "<OldName>" | xargs sed -i '' 's|submodules/<OldName>|submodules/<new_name>|g'`).
   - `docs/**/*.md`.
   - `CLAUDE.md`, `AGENTS.md`, `CHANGELOG.md`.
   - `lava-api-go/go.mod` (if this submodule has Go replace directives).
   - `settings.gradle.kts` (if Gradle includeBuild references it).
   - `.lava-ci-evidence/**/*`.
4. Run `git submodule sync` to refresh git's internal pointers.
5. Verify: `bash scripts/audit-snake_case-references.sh > after-<name>.tsv && diff before-<name>.tsv after-<name>.tsv`.

**Per-cycle validation:**
- `./gradlew :app:assembleDebug` (if this submodule is in Gradle build).
- `cd lava-api-go && make build` (if this submodule is in Go module).
- `bash scripts/verify-all-constitution-rules.sh --strict`.

**Per-cycle falsifiability rehearsal:**
- Leave ONE reference unrenamed (e.g. in a script). Run audit — confirm it flags the residual. Restore + verify clean.

**Per-cycle commit message MUST contain:**
- `Bluff-Audit:` block per §6.N + Seventh Law clause 1.
- `Classification:` line per §11.4.17 (project-specific — the rename target IS Lava's).
- Citation of falsifiability rehearsal.

**Per-cycle push:** to both GitHub + GitLab mirrors per §6.W.

**Estimated effort:** 17 sessions (one per submodule). Some pairs may bundle (small + small) at operator's discretion; HIGH-RISK renames (Containers, Tracker-SDK, RateLimiter) MUST be their own sessions.

---

### Phase 6c — File-level renames (Lava-side)

**Scope:** Tracked files at Lava root or under non-LANG-spec subtrees that violate snake_case. Per §11.4.29 the rule applies to project files too.

**Audit step (NEW script needed in this phase):**
- `scripts/audit-non-snake-case-files.sh` — walks tracked files, lists every non-snake_case name not in the §2 exemption set.

**Expected findings (preview):**
- `docs/INCIDENT_2026-04-28-HOST-POWEROFF.md` — already snake_case in content but the `INCIDENT_` prefix is ALL-CAPS. Decision: keep as-is per "tradition" (legacy filename established by operator; rename doc-only files only when low-risk; the rule's spirit is operator-ergonomics).
- `docs/superpowers/specs/*-design.md` — already snake_case.
- `docs/superpowers/plans/*.md` — already snake_case.
- `tools/lava-containers/` — already snake_case.

**Anticipated decisions:**
- LANG-spec subtrees: exempted (see §2).
- Markdown docs at root: keep PascalCase / UPPERCASE per Markdown ecosystem convention (`README.md`, `CHANGELOG.md`, `LICENSE`, `CLAUDE.md`, `AGENTS.md`, `INCIDENT_*.md`).
- New docs (Phase 6c onwards) MUST be snake_case from inception.

**Estimated effort:** 1 session for the audit + decisions. Actual renames may be ZERO if all current files are either snake_case or in an exemption set.

---

### Phase 6d — `Upstreams/` → `upstreams/` (Lava side)

**Scope:** Rename the Lava-side `Upstreams/` directory per §11.4.29's explicit transition clause.

**Pre-requisites:**
- Confirm `install_upstreams` (host-system utility from constitution submodule) supports BOTH `Upstreams/` and `upstreams/` directory layouts. The constitution mandates this transitional dual-support — verify before renaming.
- Constitution submodule's `install_upstreams.sh` is already on operator's PATH.

**Rename steps:**
1. `git mv Upstreams upstreams`.
2. Update any script that hard-codes `Upstreams/` (currently 0 from grep; the install_upstreams utility scans both).
3. Run `install_upstreams` from repo root — verify it picks up the new lowercase directory.
4. Update `CLAUDE.md` reference (`./Upstreams/GitHub.sh` → `./upstreams/GitHub.sh`).
5. Update `CHANGELOG.md` if mentioned.

**Validation:**
- `git remote -v` — verify both GitHub + GitLab remotes still configured correctly.
- Push to both mirrors — verify multi-upstream push works.

**Estimated effort:** Half a session.

---

### Phase 6e — Mechanical gate

**Scope:** `scripts/check-snake-case-naming.sh` enforcement gate + companion hermetic test, wired into `scripts/verify-all-constitution-rules.sh`.

**Gate logic:**
- Walk all tracked files + directories.
- For each name, check it matches `^[a-z0-9_]+(\.[a-z0-9_]+)*$` OR is on the LANG-spec exemption list (`*.kt`, `*.kts`, `*.gradle.kts`, `*.go`, `*.xml`, `*.json`, `*.yaml`, `*.yml`, `*.md` for markdown, Android `drawable-*`/`mipmap-*`/etc., Gradle wrapper, dotfiles).
- Reject on violation with "non-snake_case name: <path>".

**Modes:**
- `--strict` (default): exit 1 on violation.
- `--advisory`: exit 0; print warnings.

**Companion hermetic test:**
- Plant a `BadName.txt` synthetic file — confirm gate FAILs.
- Plant a `MainActivity.kt` synthetic file — confirm gate PASSes (LANG-spec exemption).
- Confirm clean Lava tree PASSes.

**Falsifiability rehearsal:**
- Rename a snake_case file back to PascalCase. Run gate — confirm FAIL with the offender named.

**Estimated effort:** 1 session.

---

### Phase 6f — Upstream coordination (cross-org rename of repos themselves)

**Scope:** Optionally rename the upstream Git repos (`github.com/vasic-digital/Tracker-SDK` → `github.com/vasic-digital/tracker_sdk` or `tracker-sdk`; `github.com/HelixDevelopment/HelixQA` → `helix_qa` / `helix-qa`).

**Considerations:**
- GitHub convention is kebab-case (hyphens). Renaming to `tracker-sdk` would be GitHub-idiomatic; to `tracker_sdk` would be §11.4.29-idiomatic. §11.4.29 silent on the hyphen-vs-underscore for upstream repo names — likely we keep upstream URL as-is and only the LOCAL submodule path is renamed.
- Renaming an upstream Git repo breaks every clone URL anywhere. GitHub auto-redirects old URLs but only for some operations. Recommendation: do NOT rename upstream repos unless operator explicitly authorizes; the local-path rename per §11.4.29 is what's mandatory.
- If operator authorizes: coordinate per-org. `gh repo rename vasic-digital/Tracker-SDK tracker_sdk` (or use the GitHub UI). Verify GitLab mirror name updates. Update all consuming projects' `.gitmodules` `url` fields.

**Decision (pending operator):** Defer. The §11.4.29 local-path rename is what the constitution mandates; the upstream repo name is a network identifier, not a working-tree directory.

**Estimated effort:** 0-1 session (depends on operator decision).

---

## 5. Rollback plan

Every rename batch has a hardlinked `.git` backup per §9.1 (HelixConstitution + Lava's existing host-stability discipline). The backup costs near-zero disk (hardlinks).

### 5.1 Rollback for single rename batch (during the session)

1. `git reset --hard HEAD~1` if the rename was just committed.
2. `git checkout HEAD -- .` to discard uncommitted working-tree changes.
3. If git's internal state is somehow corrupt: `rm -rf .git && cp -al ../.git-backup-pre-rename-<name>-<TS>/repo.git.mirror .git`.

### 5.2 Rollback after push (more involved)

1. Operator-approved force-push of the inverse commit (per §6.T.3 — force-push requires explicit per-operation approval).
2. Re-clone consuming projects if their submodule pin points at the abandoned commit.
3. Document in `.lava-ci-evidence/sixth-law-incidents/<date>-snake_case-rename-rollback.json` per §6.M.

### 5.3 Partial rollback (some renames stick, some revert)

Use case: Phase 6a + 6b/1-3 succeed; 6b/4 breaks something. Revert ONLY 6b/4:

1. `git revert <SHA-of-6b/4>`.
2. Push the revert commit.
3. Submodule 4's local path returns to the partially-renamed state (still capital `Submodules/<OldName>` if 6b/4 was the FIRST per-submodule rename after 6a — meaning the directory is `submodules/<OldName>` after 6a but `<OldName>` was not lowered yet).

### 5.4 What MUST NOT be done during rollback

- Force-push without operator authorization per §6.T.3.
- Skip pre-push hooks with `--no-verify` per §6.T.3.
- Rewrite history via `git filter-branch` / `git filter-repo` without §9.1 backup + operator approval.

---

## 6. Risk analysis

### 6.1 Risk: Gradle composite-build path break

**Trigger:** Phase 6a OR Phase 6b/Tracker-SDK rename without updating `settings.gradle.kts` `includeBuild("Submodules/Tracker-SDK")`.

**Symptom:** `./gradlew :app:assembleDebug` fails with "Project 'Submodules/Tracker-SDK' not found" or analogous error.

**Mitigation:** `settings.gradle.kts` IS in the audit script's reference count (1 ref). The per-cycle validation step (`./gradlew :app:assembleDebug`) catches this immediately.

### 6.2 Risk: Go module replace-directive path break

**Trigger:** Phase 6a OR Phase 6b/<any> without updating `lava-api-go/go.mod` `replace` directives.

**Symptom:** `cd lava-api-go && go build` fails with "directory ../Submodules/X does not exist".

**Mitigation:** `lava-api-go/go.mod` IS in the audit script's reference count (15 refs in go.mod alone). The per-cycle validation step (`cd lava-api-go && make build`) catches this immediately.

### 6.3 Risk: Constitution checker reference break

**Trigger:** Phase 6a OR Phase 6b/<any> without updating `scripts/check-constitution.sh` (29 refs).

**Symptom:** Pre-push hook fails with stale path errors; OR pre-push hook silently passes while the rule it claims to enforce is no longer checked.

**Mitigation:** `scripts/check-constitution.sh` has THE HIGHEST per-script ref count. Per-cycle validation MUST run `bash scripts/verify-all-constitution-rules.sh --strict` — any silent pass on a stale path surfaces as a sweep regression.

### 6.4 Risk: Submodule init/sync gets confused

**Trigger:** Renaming `submodules/<X>` without `git submodule sync`.

**Symptom:** `.git/modules/Submodules/X/` (cached metadata) vs. `.git/modules/submodules/x/` (post-rename expectation) divergence; `git submodule update --init` fails with "fatal: repository '..../X' does not exist".

**Mitigation:** Per-cycle step 4 explicitly runs `git submodule sync` + `git submodule update --init`. If still confused: `rm -rf .git/modules/<stale-path>` (safe — re-fetched on next `update --init`).

### 6.5 Risk: HelixDevelopment-owned submodule cross-org coordination

**Trigger:** Phase 6b/HelixQA — local path becomes `submodules/helix_qa` while upstream URL still `HelixDevelopment/HelixQA` (PascalCase).

**Symptom:** None expected. The `path` and `url` fields in `.gitmodules` are independent. §11.4.29 governs working-tree paths, not network identifiers.

**Mitigation:** Document the transitional state in the commit message. If operator later authorizes the upstream rename, run Phase 6f in coordination with HelixDevelopment.

### 6.6 Risk: HIGH-blast-radius rename batches (Containers + Tracker-SDK)

**Trigger:** Phase 6b/Containers (370 refs) or Phase 6b/Tracker-SDK (83 refs).

**Symptom:** Higher chance of missing a reference; higher diff-volume in the commit; harder for reviewer to verify the rename is complete.

**Mitigation:**
- Run the audit script BOTH before AND after; the `diff` MUST show every old-name reference removed (the audit's tab-separated output makes this easy).
- For Containers: separate the rename of `Submodules/Containers/` itself from updates to scripts that reference it. Could be split into two sub-commits within the cycle (rename + path-update).
- For Tracker-SDK: explicitly test the Gradle composite-build path with `./gradlew :app:assembleDebug` AND Go module path with `cd lava-api-go && go build` BEFORE pushing.

### 6.7 Risk: Reference drift between rename batches

**Trigger:** Phase 6b/N renames `submodules/X`, but Phase 6b/N+1 commit lands with stale references to `submodules/X` (the renamed-old-name) somewhere.

**Symptom:** §11.4.29 violation per "reference drift after a rename is a §11.4.29 violation of equal severity to the rename itself".

**Mitigation:** The audit script (run as pre-push gate after each rename) catches this immediately. The mechanical gate from Phase 6e (when wired) will reject pushes with stale references automatically.

### 6.8 Risk: Operator-unrelated breakage during the migration window

**Trigger:** Operator's local working tree gets out of sync because of a half-completed pull (Phase 6a landed remotely; operator's checkout is pre-rename).

**Symptom:** `git status` shows `Submodules/` and `submodules/` simultaneously (case-insensitive filesystems on macOS could confuse this further).

**Mitigation:** Operator MUST run `git status` + `git submodule status` after every pull during the migration window. Document the rename batches in `CHANGELOG.md` so each rename has a discoverable record. macOS case-insensitive filesystems may need `git rm --cached Submodules` + `git submodule add` cycles in extreme cases.

---

## 7. Anti-bluff guarantees (per §11.4.29 test-coverage clause + §6.J / §6.L)

§11.4.29 explicitly mandates:

> "Test coverage of renames. Every batch of renames MUST ship with: (i) a regression test that verifies every reference to the renamed entity now resolves to the new name (no stale references left); (ii) a full CONST-050(B) test-type matrix run against the post-rename tree; (iii) anti-bluff (CONST-035) wire-evidence captured during the runtime verification."

Per-phase coverage:

### 7.1 Regression test: zero stale references

The audit script (`scripts/audit-snake_case-references.sh`) IS the regression detector. Per-phase exit criterion: `audit_after - audit_before == 0` for the renamed-from name. The hermetic test verifies the script's correctness on planted fixtures.

### 7.2 Full test-type matrix on post-rename tree

After each Phase 6b sub-cycle:
- Unit tests: `./gradlew test --rerun-tasks` (Lava) + `cd lava-api-go && make test` (Go).
- Integration: `./gradlew :app:connectedDebugAndroidTest` (Compose UI Challenge Tests on emulator — gated by Containers submodule per §6.X / §6.AE).
- Constitution sweep: `bash scripts/verify-all-constitution-rules.sh --strict`.
- Pre-push hook: implicit on push.

### 7.3 Anti-bluff wire evidence

Per-phase JSON evidence file at `.lava-ci-evidence/snake_case-migration/<phase>-<UTC-timestamp>.json` capturing:
- Before/after audit TSV output diff.
- Build verification command stdout/stderr.
- Sweep result.
- Falsifiability rehearsal output.

### 7.4 Falsifiability rehearsal (§6.J clause 2 + §6.N clauses 2-3)

Per-cycle: leave ONE reference unrenamed (e.g. inside an evidence file or a docs/superpowers/plans/ entry); run audit — confirm it flags the residual reference; restore. The Bluff-Audit: stamp in the commit body records the residual + the observed audit failure message.

---

## 8. Composition with other constitutional rules

§11.4.29 composes with:

- **§1 (four-layer floor)** — every rename batch runs pre-build + post-build + runtime + meta-test paired mutation.
- **§1.1 (paired mutation)** — falsifiability rehearsal per cycle (§7.4 above).
- **§11.4.12 (auto-generated docs sync)** — if any auto-generated doc references the old name, regenerate in the same commit.
- **§11.4.17 (universal-vs-project classification)** — rename batch commits MUST carry `Classification:` line (project-specific — target names are Lava-specific).
- **§11.4.18 (script-doc sync)** — every renamed `scripts/*.sh` requires same-commit update to `docs/scripts/*.md`.
- **§11.4.20 (subagent delegation)** — Phase 6b can run multiple sub-cycles in parallel via subagents for non-overlapping submodules.
- **§11.4.25 (coverage ledger)** — every renamed path appears in the coverage ledger when Phase 7 ships.
- **§11.4.26 (constitution-submodule update workflow)** — if a rename triggers any constitution update, follow the 7-step pipeline.
- **§11.4.27 (no-fakes-beyond-unit-tests + 100%-test-type-coverage)** — every rename batch covered by every applicable test type.
- **§11.4.28 (Submodules-As-Equal-Codebase)** — rename batches treat owned submodules as equal codebase; each gets engineering attention.
- **§11.4.32 (post-pull validation sweep)** — operator MUST run `scripts/verify-all-constitution-rules.sh` after pulling any rename batch.

Lava-specific composition:

- **§6.J / §6.L (anti-bluff)** — every rename batch's commit body carries a Bluff-Audit: stamp recording the falsifiability rehearsal.
- **§6.W (mirror policy)** — push to both GitHub + GitLab mirrors per cycle.
- **§6.S (CONTINUATION maintenance)** — `docs/CONTINUATION.md` updated in the same commit as any rename batch.
- **§6.AD-debt (HelixConstitution gate-wiring)** — the rename mechanical gate (Phase 6e) closes part of this debt.

---

## 9. Subagent delegation plan (per §11.4.20)

§11.4.20 (subagent-driven-by-default mandate): when scope is multi-step + parallelizable, the primary agent MUST delegate.

The 17 per-submodule renames in Phase 6b are textbook parallelizable IF they touch non-overlapping files. They don't always — `CLAUDE.md` is touched by EVERY rename batch — so parallel delegation requires coordination.

**Recommended delegation pattern:**

- **Phase 6a:** primary agent (high-coordination, touches everything).
- **Phase 6b/1-5 (LOW-RISK, ≤12 refs each):** primary agent or 1 subagent per cycle, sequential.
- **Phase 6b/6-13 (LOW-MEDIUM, 13-30 refs each):** primary agent OR up to 2 subagents in parallel IF they partition disjoint files (one renames `submodules/<X>` and updates `docs/superpowers/plans/<P>.md`; the other renames `submodules/<Y>` and updates `docs/superpowers/plans/<Q>.md`).
- **Phase 6b/14-17 (HIGH-RISK, ≥36 refs each):** primary agent, sequential. NO parallel delegation. These are high-coordination renames where reviewer attention matters.
- **Phase 6c, 6d, 6e:** primary agent.
- **Phase 6f:** OPERATOR decision required (cross-org upstream rename).

---

## 10. Audit script baseline output (captured 2026-05-16)

Reproducible via `bash scripts/audit-snake_case-references.sh`. The script's tab-separated output is the authoritative source for §1.

```
# Captured at branch plan/2026-05-16-snake-case-migration HEAD
NAME            REFS    FILES
Auth            16      9
Cache           36      15
Challenges      14      8
Concurrency     5       4
Config          10      6
Containers      370     63
Database        8       5
Discovery       5       4
HelixQA         13      6
HTTP3           20      7
Mdns            14      7
Middleware      14      6
Observability   29      8
RateLimiter     50      13
Recovery        12      6
Security        15      6
Tracker-SDK     83      25
TOTAL_Submodules        806     124
```

---

## 11. Open questions for operator review

1. **Phase 6f (upstream repo rename) — proceed or defer?** Current recommendation: defer. §11.4.29 governs local working-tree paths; upstream repo names are network identifiers.

2. **HelixQA local-path target: `helix_qa` or `helixqa`?** Current recommendation: `helix_qa` (snake_case requires word separator; `Helix` and `QA` are clearly two words). Alternative: `helixqa` (single token, matches `mdns` / `http3` precedent for short compound names). Decision-axis: word-boundary clarity.

3. **HTTP3 local-path target: `http3` or `http_3`?** Current recommendation: `http3` (matches the protocol's canonical name; digits inside a single token, no separator needed). Alternative: `http_3` (pedantic snake_case). Decision-axis: ergonomics vs. pedantry. The `mdns` / `nnmclub` / `gutenberg` / `rutracker` neighbors all use single-token names without internal separators.

4. **RateLimiter local-path target: `rate_limiter` or `ratelimiter`?** Current recommendation: `rate_limiter` (compound noun with clear word boundary). Alternative: `ratelimiter` (matches Go `pkg/ratelimiter` convention common in Go code). Decision-axis: snake_case fidelity vs. Go-idiomatic single-token.

5. **Container CLI subcommand paths: `pkg/emulator` etc. — already snake_case.** No action needed for inside-Containers paths; Go-native.

6. **`Submodules/Containers/cmd/distributed-build/`** (and siblings with hyphens) — Go convention permits hyphens in `cmd/<name>/` directory names. §11.4.29 LANG-spec exemption applies. Verify with the operator that Go-tooling-convention hyphens are EXEMPT from the rule. Current recommendation: EXEMPT.

7. **Order-of-operations dependency:** Phase 6a MUST land before Phase 6b. Phase 6b/17 (Containers — 370 refs) is the highest-risk rename and SHOULD land LAST in Phase 6b. Phase 6c-f follow. Confirm operator agrees with the ordering.

8. **Phase 6f-style upstream rename for `Tracker-SDK` (vasic-digital/Tracker-SDK → vasic-digital/tracker_sdk)?** Same defer recommendation as HelixQA.

---

## 12. Sign-off checklist for execution

Before Phase 6a executes (separate commit from this plan):

- [ ] Operator reviews this plan.
- [ ] Operator approves §11 open questions (or sends amendments).
- [ ] Operator approves the per-cycle ordering in §4.
- [ ] Operator reads §5 rollback plan.
- [ ] Operator reads §6 risk analysis.
- [ ] Linux x86_64 gate host available for Phase 6a + 6b/17 (Containers rename) so emulator-matrix attestation can run if §6.X-debt closes in the interim.
- [ ] `.git-backup-pre-snake-case-6a-<TS>/` directory plan confirmed (disk space, location, retention).
- [ ] CONTINUATION.md updated to reflect the migration window opening.

---

**End of plan.** This document is the authoritative reference for the entire snake_case migration program. Per §6.S, it MUST be kept in sync with reality as phases execute; per-phase commits MUST update `docs/CONTINUATION.md` AND any references herein that have shifted.
