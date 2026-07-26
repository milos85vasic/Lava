# `scripts/commit-push-all.sh`

Dedicated commit + push pipeline that runs the pre-push hook logic as an
**explicit stage** instead of a git hook.

## Why it exists (2026-07-26)

The git `pre-push` hook (`.githooks/pre-push`, wired via
`core.hooksPath=.githooks`) was blocking routine commit/push work: its
Layer 2 stage runs the full `scripts/ci.sh --changed-only` gradle gate
(Spotless + unit tests + strict constitutional scans) on **every** push,
taking many minutes per invocation and making routine pushes effectively
unusable.

Per operator directive, the hook is **disconnected** from git:

```bash
git config --unset core.hooksPath   # main repo
```

`.githooks/pre-push` itself is kept unmodified — this script invokes its
logic explicitly, so no gate is lost; it just no longer fires
automatically inside `git push`.

## What it does

| Stage | Action |
|-------|--------|
| 1 | Fetch + fast-forward every initialized submodule to its default-branch tip; also inits + syncs the constitution nested submodules (`anti_bluff`, `continuum`, `session_orchestrator`, `token_optimizer`). helixqa's 27 third-party mirrors under `tools/opensource/` are intentionally left uninitialized (reference mirrors, not build inputs). |
| 2 | Commits any dirty main-repo state (including updated submodule pins) with the given message. |
| 3 | Runs the pre-push hook checks against the exact push range — Layer 1 always (Seventh Law commit-message / forbidden-pattern checks); Layer 2 (`scripts/ci.sh --changed-only`) unless `LAVA_SYNC_SKIP_CI=1`. |
| 4 | Pushes the main repo to **every** configured upstream (`github` + `gitlab`) and any submodule that has unpushed commits to all of its remotes. |
| 5 | Verifies: main repo + all submodules clean, zero unpushed commits anywhere (recursive). Prints `GREEN:` on success. |

## Usage

```bash
scripts/commit-push-all.sh "commit message"      # full pipeline
LAVA_SYNC_SKIP_CI=1 scripts/commit-push-all.sh "msg"   # Layer 1 only
LAVA_SYNC_NO_SUBMODULE_PULL=1 scripts/commit-push-all.sh "msg"
```

Idempotent — safe to re-run; each stage no-ops when there is nothing to do.

## Stage contracts

- Every stage function returns 0 when its checks pass — including via an
  explicit `return 0`, never an incidental exit status (a trailing
  `[ ... ] && echo` under `set -e` once killed the pipeline after a
  fully-green validation stage; fixed 2026-07-26 in `725b066a`).
- The pipeline NEVER pushes when any validation stage fails.

## Relationship to the constitution

The Local-Only CI/CD rule requires the *gate* to run; it does not require
the gate to be wired as a git hook. This script preserves every check and
makes execution explicit and resumable. `--no-verify` is never used.
