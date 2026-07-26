# Commit/Push Pipeline — Dedicated Hook-Validation Script (2026-07-26)

## What happened

Routine `git push` on the main repo was effectively blocked: the git
`pre-push` hook (`.githooks/pre-push`, wired via `core.hooksPath`) ran the
full `scripts/ci.sh --changed-only` gradle gate (Spotless + unit tests +
strict constitutional scans + hermetic bash suites) on **every** push —
many minutes per invocation — and the §11.4.32
`verify-all-constitution-rules.sh` sweep stage was terminating the gate
run early (see diagnosis below), so pushes were rejected outright.

## Diagnosis (2026-07-26)

- **Layer 1** of the hook (Seventh Law commit-message/pattern checks:
  Bluff-Audit stamps, mock-the-SUT, gate-shaping-file stamps,
  §6.Y/§6.Z/§6.AK evidence checks, script-doc sync, classification
  lines) **passed** for the two pending commits (`b587bf6a`,
  `f1a2c362`) — verified by simulating the hook with the real push range
  on stdin with Layer 2 stripped.
- **Layer 2** (`scripts/ci.sh --changed-only`) ran ~8 minutes and its
  output ended at the `§11.4.32 verify-all-constitution-rules sweep
  (STRICT)` stage without the trailing PASS lines. A full-output rerun
  of the sweep (54 gates) showed **52 PASS / 2 FAIL** — the two failing
  gates were `markdown-export-sync` (§11.4.65: missing/stale
  `.html`/`.pdf` siblings for 9 in-scope docs) and `coverage-ledger`
  (§11.4.25: stale `unit_test_count` rows for `submodules/containers`
  and `submodules/helixqa`). Both were fixed on 2026-07-26 by
  regenerating the siblings (`scripts/sync-markdown-exports.sh
  --regenerate-all`, 247/247 ok) and the ledger
  (`scripts/generate-coverage-ledger.sh`); both gates re-verified PASS.
  The earlier "BUILD SUCCESSFUL" gradle lines were green; the blocker
  was the sweep, not the build or the tests.
- Conclusion: the gate logic itself is sound, but wiring it as an
  automatic git hook made every push slow and, when the sweep failed,
  impossible. That violates the operator mandate that the commit/push
  mechanism must be **always unblocked**.

## Resolution — the mandatory approach

Per operator directive (2026-07-26): *"We MUST have dedicated script to
run hook validations, and commit and push mechanism ALWAYS unblocked!"*

1. **Hooks disconnected from git.** `core.hooksPath` is unset in the
   main repo (no submodule ever had one). `.githooks/pre-push` is kept
   **unmodified** — its checks are not deleted, only decoupled from the
   `git push` code path. `--no-verify` is never used.
2. **Dedicated script is the single entrypoint.**
   [`scripts/commit-push-all.sh`](../scripts/commit-push-all.sh) runs
   the pipeline: submodule sync → commit → **hook validations as an
   explicit stage** (the unmodified `.githooks/pre-push` logic fed the
   real push range; Layer 1 always, Layer 2 unless
   `LAVA_SYNC_SKIP_CI=1`) → push to **all** upstreams (`github` +
   `gitlab`, §2.1) → recursive green verification.
3. **Direct `git push` of the main repo is no longer the workflow.**
   Use the script. It is idempotent and safe to re-run.

## Constitutional anchor

This approach is mandated universally by the HelixConstitution submodule
anchor **§11.4.234 — Dedicated hook-validation script; commit/push
mechanism always unblocked** (added 2026-07-26, classification:
universal). Consumers bind their own script path, upstream list, and
gate commands as DATA per §11.4.35; Lava's binding is
`scripts/commit-push-all.sh` with remotes `github` + `gitlab` and the
Layer-2 gate `scripts/ci.sh --changed-only`.

## References

- Script: `scripts/commit-push-all.sh`
- Script doc: `docs/scripts/commit-push-all.sh.md`
- Hook (preserved, disconnected): `.githooks/pre-push`
- CI gate: `scripts/ci.sh`
- Constitution: `constitution/Constitution.md` §11.4.234, §2, §2.1
