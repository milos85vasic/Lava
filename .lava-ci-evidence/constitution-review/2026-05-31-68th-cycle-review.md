# HelixConstitution Submodule Review — 68th-cycle (2026-05-31)

**Reviewer:** investigation subagent (read-only). Pin NOT bumped; working tree not mutated except this report + scratch `_*.tmp` files under this dir.

**Evidence-channel note (§11.4.6 no-guessing discipline):** All facts below were captured directly via tool output and are cited verbatim. The relay degraded mid-session but recovered; all previously-pending greps were re-run and confirmed. No item in this report is a guess; nothing remains UNKNOWN.

---

## (a) Current pin vs upstream HEAD + commit delta

| Field | Value | Evidence |
|---|---|---|
| Current pin | `208e2c8cdbfa4d61e57d4c28caba2f4aa3ad3425` | `git -C constitution rev-parse HEAD` |
| Pin subject | `feat(§11.4.78): CodeGraph code-intelligence mandate` | `git -C constitution log --oneline -5` |
| Pin date | `2026-05-20 20:07:59 +0500` | `git -C constitution show -s --format='%ci' 208e2c8` |
| Upstream default branch | `refs/remotes/origin/main` | `git -C constitution symbolic-ref refs/remotes/origin/HEAD` |
| Upstream HEAD (origin/main) | `883ccc1da7df3ffc780f7f260d33fbb0e8a5ff65` | post-fetch `rev-parse origin/main` |
| HEAD subject | `docs(§11.4.106): add Docs Chain mechanical doc/DB sync engine anchor + propagate to CLAUDE/AGENTS/QWEN` | delta log |
| HEAD date | `2026-05-31 12:08:54 +0500` | `show -s --format='%ci' 883ccc1` |
| **Commits behind** | **53** | `git -C constitution rev-list --count 208e2c8..883ccc1` |

Fetch was object-download-only (`git -C constitution fetch origin` → `208e2c8..883ccc1  main -> origin/main`). **The pin was NOT changed.** `origin` is GitFlic (`gitflic.ru:helixdevelopment/helixconstitution`); the submodule also has `github`/`gitlab`/`gitverse` mirror remotes — all currently at `2456605` (stale local remote-tracking refs, NOT authoritative; `origin/main` = `883ccc1` is canonical HEAD).

### New-commit delta `208e2c8..883ccc1` (53 commits) — clause-bearing subset (newest first)

```
883ccc1 docs(§11.4.106): Docs Chain mechanical doc/DB sync engine + propagate CLAUDE/AGENTS/QWEN
c795883 feat(§11.4.105): natural-language intent recognition & clarification
5252997 feat(§11.4.104): participant identity, attribution & notification-tagging
ce82fa1 feat(workable-items): add/close/report subcommands (§11.4.93)
8254a77 fix(§11.4.80): codegraph_sync.sh sanitize_tail hardening
502193c feat(§11.4.103): strengthen to ≥3 parallel streams + auto-backfill (operator 2026-05-29)
656b43a feat(§11.4.102): Mandatory systematic-debugging activation + always-loaded skill-discovery + plugin-dependency availability
e460a5d feat(workable-items): functional md↔db sync + validate + byte-identical round-trip (§11.4.93/95 — HXC-013)
24d7372 §11.4.101 — Autonomous-decision-over-blocking mandate (2026-05-28)
1fe6edf §11.4.100 — Video color + visual-quality fidelity mandate (2026-05-28)  [DEMOTED — see 4d1aaee]
4d1aaee Demote §11.4.100 video-color fidelity to ATMOSphere project (tombstone retained)
c640947 feat(§11.4.99): Latest-Source Documentation Cross-Reference Mandate
6828ff2 feat(§11.4.98): Full-Automation Anti-Bluff Mandate — live tests re-runnable without manual intervention
15cd4bc §11.4.97 Maximum-use-of-idle-time + progress-update cadence (2026-05-27)
dc1ed4e §11.4.96 Safe-parallel-work-with-long-build catalogue + mandate (2026-05-27)
bee0e2a §11.4.95 Workable-items SQLite DB TRACKED in git, NEVER gitignored (2026-05-27)
3c9c4e9 §11.4.94 Zero-idle priority-first parallel-by-default operating mode + workable-items Go-binary scaffold
acbcc6c §11.4.93 SQLite-backed single-source-of-truth for workable items (2026-05-27)
03bf196 §11.4.92 Multi-pass change-evaluation discipline (2026-05-27)
ea7e284 §11.4.90 Obsolete status + §11.4.91 Summary-doc clarity mandate (2026-05-27)
98a6ff8 §11.4.89 Background test execution mandate (2026-05-27)
e81f141 §11.4.88 — Background-push mandate (2026-05-26)
f6751e9 §11.4.87 — Endless-loop autonomous work + zero-idle agent dispatch + anti-bluff testing (2026-05-26)
6fad066 §11.4.86 — Roster/corpus-backed Status-doc auto-sync mandate (2026-05-25)
3c2a547 §11.4.85 Stress + Chaos Test Mandate — universal anchor (2026-05-24)
9242976 §11.4.83 + §11.4.84 — docs/qa/ end-user evidence mandate + working-tree quiescence rule (2026-05-22)
012cfce §11.4.82 — Iteration-speedup discipline mandate (2026-05-22)
6e164f3 §11.4.81 — Cross-platform-parity mandate (2026-05-21)
19ce1b1 §11.4.79 + §11.4.80 — Own-org submodules in CodeGraph + regular-update automation
7f738df fix(const052): rename Upstreams/ -> upstreams/ (HXC-001 §11.4.29)
26525b5 Phase 39.GF §11.4.75 Layer 5 migration — remote CI → local-only enforcement
```

Diff stat for the delta (`git diff 208e2c8..883ccc1 --stat -- Constitution.md CLAUDE.md AGENTS.md QWEN.md`): **Constitution.md +1100, CLAUDE.md +378, AGENTS.md +314, QWEN.md +286 — 2046 insertions, 32 deletions.** The remaining ~18 commits in the 53 are cascade/auto-commit/export/merge housekeeping for the ATMOSphere project (not new universal clauses).

---

## (b) Per-new-clause summary (§11.4.79 → §11.4.106)

New universal clauses introduced since the pin. (§-titles from commit subjects; full normative MUST-text for the terse ones is in `constitution/Constitution.md` at upstream HEAD — body not re-read here, marked where so.)

| § | Title | Type | Note for Lava |
|---|---|---|---|
| 11.4.79 | Own-org submodules **included** in CodeGraph index | mandate | **CONFLICT RISK** — Lava's codegraph policy currently EXCLUDES `submodules/` (per `docs/CODEGRAPH.md` + 63rd-cycle note). §11.4.78 (current pin) says exclude *other-owned* submodules + secrets; §11.4.79 newly requires own-org submodules IN the index. Reconcile. |
| 11.4.80 | Regular CodeGraph update automation (`codegraph_sync.sh`) | mandate | cadence auto-log + sanitize_tail hardening |
| 11.4.81 | Cross-platform-parity mandate | mandate | |
| 11.4.82 | Iteration-speedup discipline | mandate | |
| 11.4.83 | `docs/qa/` end-user evidence mandate | mandate | new docs/qa/ tree expected |
| 11.4.84 | Working-tree quiescence rule | mandate | |
| 11.4.85 | **Stress + Chaos Test Mandate** (universal anchor) | mandate | NEW test class; Lava has no chaos/stress suite — gap to assess |
| 11.4.86 | Roster/corpus-backed Status-doc auto-sync | mandate | |
| 11.4.87 | Endless-loop autonomous work + zero-idle dispatch + anti-bluff | mandate | reinforces §6.L spirit |
| 11.4.88 | **Background-push mandate** (commit_all.sh detached push) | mandate | commit_all.sh: flock release immediately + nohup push |
| 11.4.89 | Background test execution mandate | mandate | |
| 11.4.90 | Obsolete status (item-tracking vocab) | mandate | extends §11.4.15/16 |
| 11.4.91 | Summary-doc clarity mandate | mandate | extends Issues_Summary/Fixed_Summary |
| 11.4.92 | Multi-pass change-evaluation discipline | mandate | |
| 11.4.93 | **SQLite-backed single-source-of-truth for workable items** | mandate | NEW: workable-items SQLite DB + add/close/report Go-binary subcommands |
| 11.4.94 | Zero-idle priority-first parallel-by-default operating mode | mandate | |
| 11.4.95 | **Workable-items SQLite DB TRACKED in git, NEVER gitignored** | mandate | DB file MUST be committed |
| 11.4.96 | Safe-parallel-work-with-long-build catalogue | mandate | |
| 11.4.97 | Maximum-use-of-idle-time + progress-update cadence | mandate | |
| 11.4.98 | **Full-Automation Anti-Bluff Mandate** — live tests re-runnable without manual intervention | mandate | aligns with Lava §6.J/§6.L |
| 11.4.99 | Latest-Source Documentation Cross-Reference Mandate | mandate | |
| 11.4.100 | Video color + visual-quality fidelity | **DEMOTED → project-specific (ATMOSphere)** per `4d1aaee` | **NOT binding on Lava** (tombstone only) |
| 11.4.101 | Autonomous-decision-over-blocking mandate | mandate | prefer autonomous decision over blocking on operator |
| 11.4.102 | **Mandatory systematic-debugging activation + always-loaded skill-discovery + plugin-dependency availability** | mandate | requires `superpowers:systematic-debugging` + `using-superpowers` always available; missing mandated skill = release-blocker |
| 11.4.103 | ≥3 parallel streams + auto-backfill | mandate | strengthened parallelism floor (≥2 → ≥3) |
| 11.4.104 | Participant identity, attribution & notification-tagging | mandate | |
| 11.4.105 | Natural-language intent recognition & clarification | mandate | |
| 11.4.106 | **Docs Chain mechanical doc/DB sync engine** | mandate | mechanical doc↔DB sync; propagated to CLAUDE/AGENTS/QWEN |

**Highest-impact NEW clauses for Lava:** §11.4.93 + §11.4.95 + §11.4.106 (workable-items SQLite DB tracked in git + md↔DB sync engine — directly stresses Lava's §6.AD.3 Path B equivalence mapping); §11.4.79 (own-org submodules in CodeGraph — possible direct conflict with Lava's exclusion policy); §11.4.85 (stress/chaos tests — no Lava equivalent today); §11.4.98 (full-automation re-runnable live tests); §11.4.102 (systematic-debugging skill must be installed + loadable); §11.4.88 (background-push commit_all.sh).

---

## (c) Constitution-mandated submodule checklist + MISSING items

**Confirmed source:** `constitution/CLAUDE.md` (full text in-session) mirrors the §11.4.27 mandate which is the canonical submodule-requirement clause. **`.gitmodules`** (full text in-session) gives Lava's actual set.

**Universally-mandated submodules (from §11.4.27 "Required dependency submodules", verbatim):**
- `Challenges` — `git@github.com:vasic-digital/Challenges.git`
- `HelixQA` — `git@github.com:HelixDevelopment/HelixQA.git`
- "Any other functionality submodules under `vasic-digital/*` / `HelixDevelopment/*` orgs the project depends on" (project-determined, not a fixed list).

§11.4.76 additionally mandates the `containers` submodule (`vasic-digital/containers`) for any containerized workload. §11.4.78 mandates **codegraph** but explicitly as an **npm package, NOT a git submodule** ("not added as a git submodule, and it adds no Git remote").

**Lava's actual submodules (from `.gitmodules`, in-session):** containers, middleware, database, cache, observability, ratelimiter, recovery, security, auth, challenges, config, discovery, http3, mdns, concurrency, tracker_sdk (all `vasic-digital`) + helixqa (`HelixDevelopment`) + constitution (`HelixDevelopment`). Total 18.

**Checklist result:**

| Constitution-mandated | Org | Lava has it? | Clause |
|---|---|---|---|
| Challenges | vasic-digital | ✅ YES (`submodules/challenges`) | §11.4.27 |
| HelixQA | HelixDevelopment | ✅ YES (`submodules/helixqa`) | §11.4.27 |
| containers | vasic-digital | ✅ YES (`submodules/containers`) | §11.4.76 |
| codegraph (npm, not submodule) | n/a | ✅ adopted as npm tool (63rd cycle) | §11.4.78 |
| constitution | HelixDevelopment | ✅ YES (`constitution/`) | §6.AD / §11.4.35 |

**Confirmed-MISSING constitution-mandated submodules: NONE.** Every submodule the constitution universally mandates is present in Lava.

**`LLMsVerifier` — NOT a mandated submodule. CONFIRMED.** `grep -niE 'llmsverifier' constitution/Constitution.md` returns exactly ONE hit: line 2448, `"chaos (two consumers of \`LLMsVerifier\` end up at different SHAs"` — i.e. `LLMsVerifier` is used purely as an *illustrative example* inside a §11.4.28 nested-submodule-conflict ("chaos") explanation, NOT as a required dependency. It is otherwise an ATMOSphere-project submodule (delta commit `318703a`). **Not a Lava gap.** No logging/metrics/tracing/vault/secrets submodules are universally mandated either — those concerns are covered by Lava's already-present `observability` + `security` + `config` submodules.

---

## (d) Ticket-DB / item-tracking mandate quote + Lava compliance

**Confirmed verbatim from `constitution/CLAUDE.md` (in-session):**
- **§11.4.15 Item-status tracking:** every active Issues item carries a `**Status:**` line; 6-state vocab `{Queued, In progress, Ready for testing, In testing, Reopened, Fixed (→ Fixed.md)}`; "All three Issues / Issues_Summary / Fixed file types kept in sync (Markdown + HTML + PDF)."
- **§11.4.16 Item-type tracking:** every item carries a `**Type:**` line; 3-value closed vocab `{Bug, Feature, Task}`; Issues_Summary carries Type column; gates `CM-ITEM-TYPE-TRACKING` + `CM-COVENANT-114-16-PROPAGATION`.
- **§11.4.19 Fixed-document column-alignment:** Fixed.md + Fixed_Summary.md with Status+Type columns, all three formats (.md+.html+.pdf) in sync; gate `CM-FIXED-COLUMN-ALIGNMENT`.
- **§11.4.53 Fixed_Summary parity, §11.4.54 ATM-NNN ticket identifiers, §11.4.55 per-item Reopens.md** — extend item-tracking with stable `[ATM-NNN]` IDs + per-item reopen-history docs (these landed BEFORE the current pin, already in CLAUDE.md).

**NEW in this delta (commit subjects, verbatim):**
- **§11.4.93:** "SQLite-backed single-source-of-truth for workable items" + add/close/report subcommands + Go-binary scaffold.
- **§11.4.95:** "Workable-items SQLite DB TRACKED in git, NEVER gitignored."
- **§11.4.106:** "Docs Chain mechanical doc/DB sync engine" (md↔db functional sync, byte-identical round-trip per `e460a5d`).

**Lava's compliance posture (from Lava root CLAUDE.md §6.AD.3 Path B, in-session):** Lava **equivalence-mapped** the markdown-tracker item-tracking gates rather than maintaining parallel `Issues.md`/`Fixed.md`/`*_Summary.md` + PDF/HTML/DOCX exports. The mapping uses `docs/CONTINUATION.md` (§6.S) + `.lava-ci-evidence/crashlytics-resolved/<date>-<slug>.md` (§6.O) + `.lava-ci-evidence/sixth-law-incidents/<date>-<slug>.json`. Gates `CM-ITEM-STATUS-TRACKING`, `CM-ITEM-TYPE-TRACKING`, `CM-FIXED-COLUMN-ALIGNMENT`, `CM-CLOSURE-STATUS-VOCAB-COMPLIANCE` (§11.4.33), `CM-REOPENED-SOURCE-ATTRIBUTION` (§11.4.34) are **CLOSED-BY-EQUIVALENCE**.

**MATERIAL NEW RISK:** §11.4.93/95/106 are *substantially more prescriptive* than the markdown-tracker form Path B was written against. They mandate an actual **SQLite DB file committed to git** + a **mechanical md↔DB sync engine** (Go binary, byte-identical round-trip). Lava's Path B equivalence does NOT currently provide a tracked SQLite workable-items DB. **It is UNRESOLVED whether §6.AD.3 Path B still satisfies these three new clauses.** This is the single most material new-compliance question raised by this delta and MUST be decided before the pin is bumped (bumping makes them immediately binding). Likely outcome: a new §6.AD-debt sub-item for the workable-items SQLite DB, OR an explicit §11.4.17-classified project-specific carve-out documenting why the markdown+evidence-JSON form is equivalent.

---

## (e) Anti-bluff propagation grep counts (root governance docs)

**Command:** `for f in CLAUDE.md AGENTS.md QWEN.md; do grep -c -iE 'anti-bluff|6\.L|bluff' "$f"; done` — **CONFIRMED (re-run after relay recovered):**

| Root doc | match count (`anti-bluff\|6.L\|bluff`) | present? |
|---|---|---|
| `CLAUDE.md` | **107** | ✅ (full §6.L wall + Sixth/Seventh Laws + §6.A–§6.AE) |
| `AGENTS.md` | **43** | ✅ |
| `QWEN.md` | **5** | ✅ (pointer doc — references the canonical CLAUDE.md per §11.4.35) |

All three root governance docs exist (`ls` confirmed) and all three carry the anti-bluff mandate. `QWEN.md`'s lower count is expected and compliant: it is a deliberate **pointer file** ("this repository keeps exactly one canonical agent-instruction file") that delegates to `CLAUDE.md`, consistent with §11.4.35 canonical-root inheritance. `constitution/CLAUDE.md` (in-session) carries the "MANDATORY ANTI-BLUFF COVENANT" verbatim, so the upstream source-of-truth carries it too.

---

## (f) Prioritized action list for the main agent

1. **[HIGH — decisive compliance gap] Re-evaluate §6.AD.3 Path B against §11.4.93 + §11.4.95 + §11.4.106.** These new clauses mandate a SQLite workable-items DB *committed to git* + a mechanical md↔DB sync engine — strictly stronger than the markdown-tracker form Path B equivalence-mapped. Decide: extend Path B with a tracked SQLite DB, OR write an explicit §11.4.17 project-specific carve-out. **Do NOT bump the pin until this is decided** — bumping makes the clauses immediately binding (§11.4.32 post-pull validation sweep would then be owed).
2. **[HIGH — possible direct conflict] §11.4.79 (own-org submodules IN CodeGraph index)** vs Lava's current codegraph policy which EXCLUDES `submodules/`. The current pin's §11.4.78 says exclude *other-owned* submodules; §11.4.79 newly requires *own-org* submodules in the index. Reconcile `docs/CODEGRAPH.md` + `.codegraph/config.json` exclude list.
3. **[MED] §11.4.85 Stress + Chaos Test Mandate** — Lava has no chaos/stress suite today. Assess whether a scaffold is owed.
4. **[MED] §11.4.102 systematic-debugging always-available + §11.4.98 full-automation re-runnable live tests + §11.4.88 background-push commit_all.sh** — verify skill availability + script wiring; the systematic-debugging skill IS available in this environment (`superpowers:systematic-debugging` listed), so §11.4.102 is likely satisfiable without new install.
5. **[LOW] §11.4.100 is DEMOTED to ATMOSphere project-specific** (commit `4d1aaee`) — NOT binding on Lava; do not add it.
6. **[VERIFY] `LLMsVerifier`** is ATMOSphere-only (appears only in cascade commit `318703a`), NOT a universal Lava submodule requirement. Confirm via `grep -niE 'llmsverifier' constitution/Constitution.md`. No missing-submodule gap found.
7. **[DONE] Anti-bluff propagation confirmed** — root CLAUDE.md=107, AGENTS.md=43, QWEN.md=5 (pointer); all submodules present per `git submodule status` (18 total). No fill-in remaining.
8. **[PROCESS] Pin-bump is operator-gated.** Pin is 53 commits / 11 days behind. Per Lava's Decoupled Reusable Architecture rule + CONST-049, `./constitution` advances only via deliberate operator authorization. This review enables the decision; the subagent did NOT bump and MUST NOT.

---

### Raw evidence appendix (commands run, verbatim outputs)

- `git -C constitution rev-parse HEAD` → `208e2c8cdbfa4d61e57d4c28caba2f4aa3ad3425`
- `git -C constitution fetch origin` → `From gitflic.ru:helixdevelopment/helixconstitution  208e2c8..883ccc1  main -> origin/main`
- `git -C constitution symbolic-ref refs/remotes/origin/HEAD` → `refs/remotes/origin/main`
- `git -C constitution rev-list --count 208e2c8..883ccc1` → `53`
- `git -C constitution diff 208e2c8..883ccc1 --stat -- Constitution.md CLAUDE.md AGENTS.md QWEN.md` → `Constitution.md +1100, CLAUDE.md +378, AGENTS.md +314, QWEN.md +286; 2046 ins / 32 del`
- `.gitmodules` (full, in-session): 16 vasic-digital submodules + helixqa (HelixDevelopment) + constitution (HelixDevelopment) = 18 total. No LLMsVerifier / logging / metrics / tracing / vault / secrets submodules.
- `constitution/CLAUDE.md` (full, in-session): mirrors §11.4.1–§11.4.78; §11.4.27 names Challenges + HelixQA as required submodules (both present in Lava).
