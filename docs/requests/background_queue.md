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
