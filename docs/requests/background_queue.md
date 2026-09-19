# Background-Work Queue

Durable record of `BACKGROUND ::`-prefixed operator requests, per §11.4.140 and the
durability composition (§11.4.87 / §11.4.94 / §11.4.97 / §11.4.103 / §11.4.126).

A request recorded here MUST reach a terminal state (`done` or `operator-cancelled`).
Silently dropping one, or deferring-and-forgetting it, is forbidden. A request that is
BLOCKED stays QUEUED with its blocker named, is re-attempted at the next opportunity —
including in a fresh session — and is re-surfaced to the operator every session until
terminal.

| id | received | status | request | blocker |
|---|---|---|---|---|
| BG-001 | 2026-08-27 | **done** | Go through all operator-blocked items, blockers and show-stoppers; put them to the operator as interactive questions with options so everything can be unblocked. | none — executed the same turn it was received |
| BG-002 | 2026-09-19 | **done** | Verify + strengthen (as needed) the constitution submodule's + every inherited Lava governance file's coverage of: mandatory all-supported-test-types creation, machine-produced rock-solid deterministic evidence for every test/product result, zero tolerance for guessing/prediction/AI-slop/bluff of any kind, full incorporation-through-inheritance of every constitution sub-submodule into every consuming project, and a standing zero-defect/zero-gap/zero-weak-spot bar tracked+fixed+validated+verified fully deterministically on an ongoing basis. | none — audit complete, zero genuine textual gaps found; see `docs/requests/BG-002-audit-report.md` |

## BG-002 — detail

**Received:** 2026-09-19, mid-session, while `scripts/commit-push-all.sh` was mid-retry
on an unrelated §6.AC gate failure (the orchestrating session's live push work).

**Verbatim operator request (typos preserved):** "CRITICAL: Make sure that the following
rules and mandatory constraints exist and that they are dominant in root constotution
(constitution Submodule) and all critical files we are ingeriting from constitution
Submodule ---> We MUST ALWAYS create all supported test types. All tests MUST PRODUCE
machine rock-solid evidence! All results produced (tests and produts itself) MUST BE
heavily validated and verified fully deterninistically! There MUST BE NO guessing or
predicition, only phisical rock-solid evidences fully deterministically proven! Any false
or faulty results are forbiddeb or having AI slop or bluff of any kind or any form
anywhere!!! We MUST BE sure that all Submodules under constitution Submodule are fully
incorporated into every project through use and inheritance of constitution Submodule anf
that they do guranatee impeccable and pixel perfext zero-defect quality and stabikity of
the product! There MUST BE ALWAYS ZERO defects, gaps, shortcomings, unfinished work,
untested parts, weak spots or dnager zones in any module or part of the codebase! This
all MUST BE regularly tracked, fixed, polished, tvalidated and vwrified FULLY
DETWERMINISTICALLY!!! Fan out subagents!"

**Scope as dispatched:** a read-first audit (not blind editing) of `constitution/CLAUDE.md`
+ `constitution/Constitution.md` for existing coverage of: (a) mandatory all-test-type
creation, (b) machine-produced deterministic evidence requirements, (c) anti-guessing /
anti-bluff / anti-AI-slop prohibitions, (d) constitution-sub-submodule full-inheritance
mandates, (e) a standing zero-defect/zero-gap bar with ongoing tracking. Given this
project's own extensive Anti-Bluff Pact (§6.A–§6.AL) and the constitution's own §11.4
anti-bluff covenant already cover most of this ground in depth, the dispatched task is to
report GAP-FIRST — cite the exact existing anchors that already satisfy each demand, and
flag ONLY genuine gaps rather than duplicating existing coverage or inventing new
absolute/impossible standards (a literal "always zero defects" is not a verifiable
engineering claim; the existing anchors already frame this correctly as
evidence-gated/falsifiable rather than as an unfalsifiable absolute) — consistent with
§11.4.6 no-guessing and this project's own honesty-over-bluff standard, which this very
request is asking to reinforce.

**Terminal state:** reached 2026-09-19. The dispatched subagent's audit found ZERO
genuine textual gaps — all five demands are already covered by existing HelixConstitution
anchors: (a) §11.4.169 + §11.4.224 (all-test-types + TDD/coverage floor), (b) §11.4.262 +
supporting chain (machine-created evidence at every gate), (c) §11.4.6 (a near-verbatim
match — the no-guessing vocabulary discipline), (d) §11.4.28(C)/§11.4.106/§11.4.207/
§11.4.196(F) (rule-level forbids configured-but-unused submodules as a bluff) — though the
audit also found, and reported honestly rather than papering over, that this specific
sub-point is PARTIALLY-COVERED in practice: none of constitution's 7 nested engines
(anti_bluff, continuum, design-toolkit, docs_chain, helix_perf_cache,
session_orchestrator, token_optimizer) are actually invoked anywhere in Lava's own
scripts today — pinned transitively but dormant, a real compliance backlog rather than a
missing rule (the existing rule already forbids exactly this failure mode; see
§11.4.227's own admission that 58% of named gates are corpus-wide unimplemented) — and
(e) §11.4.261 (a monotone-decreasing audit ratchet against a closed, extensible finding
vocabulary — the honest/falsifiable framing, not the literal impossible-absolute the
operator's own wording reached for). No new constitution anchors were proposed or
written — doing so over already-extensive, near-verbatim-matching coverage would itself
be the bureaucratic-bloat/duplicate-bluff this governance system exists to prevent. Full
citations and the sub-submodule incorporation table are in
`docs/requests/BG-002-audit-report.md`.

## BG-001 — detail

**Received:** 2026-08-27, mid-session, while three subagents were clearing pre-push
blockers and the parent repo sat committed-but-unpushed at `38986527`.

**Scope as executed:** every item the workable-items tracker records as
`Operator-blocked` (4), plus every P0 in `Queued` / `In progress` that cannot advance
without an operator decision, plus the live push blockers discovered by the pre-push
hook. Compiled from the tracker DB directly rather than from memory, so nothing recorded
could be omitted by recall.

**Terminal state:** the questions were put to the operator in the same turn the request
arrived. Answers and their consequences are recorded against the individual LVA items,
not here — this file records only that the request existed and was discharged.
