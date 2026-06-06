# Constitution submodule update — 2026-06-06 (STREAM CONST)

Processed per CONST-049 + §11.4.71 + CONST-055. No remote pushes performed (subagent constraint).

## Pin movement

| | SHA |
|---|---|
| Old pin | `d90ab87` (d90ab873513605537040617d8d7508bdda01f25c) |
| New pin | `7734c04` (7734c04a184af9838a4d717f10c7fea1072a6d75) |

Clean fast-forward `d90ab87..7734c04` (7 upstream commits, 2026-06-03 .. 2026-06-06). Confirmed all 5 remotes (gitflic/github/gitlab/gitverse + origin) were at `7734c04` at fetch time. Pin bumped on Lava parent in commit `057d1d2b` (explicit path `-- constitution`, message via `-F`).

## New universal anchors (§11.4.114 – §11.4.127)

| Anchor | Purpose (one line) |
|---|---|
| §11.4.114 | Last-known-good-tag regression isolation — diff/bisect broken state vs last KNOWN-GOOD tag first; surgical forward-fix over wholesale revert. Refines §11.4.102 Phase 1. |
| §11.4.115 | RED-baseline-on-the-broken-artifact + polarity-switch — RED test reproduces defect on CURRENT pre-fix artifact, then single RED_MODE flag flips same test to GREEN-guard. Refines §11.4.43. |
| §11.4.116 | Real-time conductor↔autonomous-test-framework sync channel — append-only JSONL event stream + atomically-rewritten status snapshot; every verdict carries its evidence path. |
| §11.4.117 | CV/OCR pixel-oracle fallback for non-introspectable UIs — drive+assert via pixels (template-match + ROI OCR) when accessibility tree is blank. Refines §11.4.48/.52/.107. |
| §11.4.118 | Discovery-pressure to confirm known-issue-set completeness — fixing reported set is necessary-not-sufficient; provable enumerated discovery coverage, never bare "found nothing else". |
| §11.4.119 | Single-resource-owner partitioning for parallel hardware testing — exactly ONE stream owns each exclusive resource; others read-only. Refines §11.4.58/.103. |
| §11.4.120 | Fix-breaks-its-own-gate reconciliation — gate FAIL is correct signal; rewrite gate to assert NEW mechanism + update its mutation; never fake-pass, never revert the fix. |
| §11.4.121 | No-commit-while-build-writes-tracked-artifacts — defer commit to build completion so partial/stale artifacts never land in VCS. Build-output analogue of §11.4.84. |
| §11.4.122 | No-silent-removal of any existing end-user capability (app/component/service/package/feature/driver/module/library/asset) without interactively asking the operator (§11.4.66) + explicit keep-or-remove decision. Silent removal = release blocker. |
| §11.4.123 | Rock-solid-proof-or-deep-research mandate — every reported issue/fix/completion 100% validated with captured proof; metadata-only / config-only / absence-of-error / grep-without-runtime PASS forbidden; when unsure how to validate, MUST first deep-web-research an evidence-producing method. |
| §11.4.124 | Dead/unwired-code investigate-before-remove — git-history proof of original wiring + how it became dead before any removal; "zero importers ⇒ dead ⇒ delete" is a §11.4.6 guess; removal must be its OWN separate commit citing git-history evidence. |
| §11.4.125 | Code-review-agent gate before pre-build + main build — after batch work and BEFORE pre-build sweep + artifact build, dispatch dedicated code-review subagent(s) for multi-layer review (quality/safety/will-it-really-work/bluff-capable-test detection); findings fixed before build proceeds. No `--skip-code-review` escape hatch. |
| §11.4.126 | Default autonomous-loop working mode from FIRST prompt — endless fully-autonomous loop engages on first prompt, no per-session handshake; continues until validated+published tag OR queue empty; stops only on explicit operator STOP / empty scope / §12 host-safety. Capstone promoting §11.4.87 to always-on. |
| §11.4.127 | Session-handoff resumption-prompt mandate — when a fresh session is needed/requested, ALWAYS prepare a ready-to-paste, moment-valid resumption prompt (SHORT + FULL variants) pointing to live handoff docs (.remember/remember.md + docs/CONTINUATION.md §12.10), embedding live-state anchors (HEAD/build IDs/PIDs/evidence paths) + binding constraints. |

All new anchors are mirrored into the constitution submodule's `CLAUDE.md` / `AGENTS.md` / `QWEN.md` + regenerated `.html`/`.pdf`/`.docx` exports (§11.4.65).

## Post-pull verify-all sweep (CONST-055)

`scripts/verify-all-constitution-rules.sh` (STRICT mode), constitution pin `7734c04`:

```
Sweep complete: 36 PASS / 14 FAIL (of 50 total)
Attestation: .lava-ci-evidence/verify-all/2026-06-06T16-51-00Z.json
```

### Pre-pull baseline comparison (regression check)

Last pre-pull sweep: `2026-06-03T05-58-33Z.json`, older pin `34a9772` → **13 FAIL / 50**.

- **All 13 pre-pull failures persist post-pull** (constitution-doc-parser, coverage-ledger, gitignore-coverage, hermetic-check-constitution-check_constitution_test, hermetic-check-constitution-test_gitignore_coverage, hermetic-check-constitution-test_no_hardcoded_uuid, hermetic-check-constitution-test_script_docs_sync, hermetic-suite-vm-images, markdown-export-sync, no-hardcoded-hostport, no-hardcoded-uuid, non-fatal-coverage, script-docs-sync).
- **Single +1 delta: `hermetic-check-constitution-test_audit_snake_case_references`** — sub-test `test_blast_radius_ordering_sanity` flags post-rename CamelCase regression for `Concurrency=4 Containers=4 RateLimiter=3` references. This is the Phase-6 snake_case-migration tracking test, triggered by a **CONCURRENT STREAM's `submodules/containers` edit** (working-tree shows `M submodules/containers`, `M core/navigation/build.gradle.kts`, new untracked test files). NOT caused by the constitution pin bump.

### Determination

**The pull was a clean fast-forward AND the sweep did NOT fail on any gate introduced by the constitution change.** The new anchors §11.4.114–127 are enforced by `CM-COVENANT-114-1NN-PROPAGATION` gates that the upstream commits explicitly mark as "gate-code = separate work item" — those gates are NOT yet implemented in Lava, so they cannot fire. Per CONST-049/CONST-055, the pin bump proceeds. The 13 carried-over + 1 new-delta failures are pre-existing / concurrent-stream-owned, outside STREAM CONST's scope.

## New mandatory constraints Lava must newly satisfy (follow-up items for main stream)

The new anchors are binding via §6.AD inheritance immediately. Mechanical gate-wiring is OWED (upstream marks gate-code as separate work items). Candidate follow-ups for the main stream to schedule:

1. **§11.4.114–127 propagation gates** — wire `CM-COVENANT-114-114 .. 114-127-PROPAGATION` (verify literal `11.4.1NN` present across consumer fleet governance docs) + the recommended per-family gates (e.g. `CM-NO-SILENT-COMPONENT-REMOVAL`, `CM-ROCK-SOLID-PROOF-OR-RESEARCH`, `CM-DEAD-CODE-INVESTIGATE-BEFORE-REMOVE`, `CM-CODE-REVIEW-GATE-BEFORE-BUILD`). Track under §6.AF-debt analogue.
2. **§11.4.122 (no-silent-component-removal)** — operationally binding NOW: no app/component/service/feature may be removed from Lava's shipped surface without an AskUserQuestion + explicit operator keep-or-remove decision. Composes the existing §6.J/§6.L anti-bluff posture.
3. **§11.4.124 (dead-code investigate-before-remove)** — any dead/unwired-code removal needs git-history proof + its own separate commit.
4. **§11.4.125 (code-review-agent gate before build)** — dispatch dedicated code-review subagent(s) after batch work and BEFORE the pre-build sweep + artifact build. Relevant to the completeness-program build/distribute flow.
5. **§11.4.126 (default autonomous-loop)** — already the operative working mode; documents that the loop engages from the first prompt without a handshake.
6. **§11.4.127 (session-handoff resumption-prompt)** — composes the existing MEMORY.md `lva-fresh-session-handoff` + docs/CONTINUATION.md discipline; ensure a moment-valid resumption prompt is produced on handoff.

None of these require STREAM CONST action beyond this pin bump + findings record.
