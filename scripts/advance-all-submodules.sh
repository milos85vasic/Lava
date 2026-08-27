#!/usr/bin/env bash
# scripts/advance-all-submodules.sh — advance every submodule to its own
# upstream's latest commit (FR-015/FR-016, research.md R-005, tasks.md T052).
#
# Implements research.md R-005's 7-step per-submodule ordering, verbatim:
#
#   1. fetch the submodule's own upstream(s)
#   2. compare the currently-pinned commit to the remote default branch's HEAD
#   3. if identical -> no-op (spec Edge Case: "no newer commit available")
#   4. if different -> check the remote HEAD out into the submodule's tree
#   5. rebuild + re-run the affected test categories against the advanced
#      state; on failure DISCARD the advance (check back out to the prior
#      pin) and report the specific incompatibility, rather than recording a
#      pin bump that broke the build
#   6. REMOVED. R-005 step 6 -- "if the submodule itself carries local,
#      uncommitted modifications, commit and push those to its own
#      upstream(s)" -- is NOT implemented by this script and cannot be
#      requested of it. A submodule whose working tree is not clean is
#      REFUSED (FAILED_PRECONDITION); nothing is fetched, checked out,
#      verified or staged for it, and there is no flag, environment variable
#      or configuration that turns the refusal into a publish. See "WHY STEP 6
#      DOES NOT EXIST HERE" below.
#   7. `git add <submodule-path>` in the PARENT repository to record the pin
#
# Writes one Submodule Advance Record per submodule, conforming to
# specs/002-build-test-distribute-pipeline/contracts/submodule-advance-record.schema.json
# and data-model.md's "Submodule Advance Record" entity.
#
# Usage:
#   scripts/advance-all-submodules.sh [options] [parent-repo-path]
#
# Options (deliberately COMMAND-LINE flags, never environment variables — see
# "Why these are flags and not env vars" below):
#
#   --allow-local-path-remotes
#       Permit a submodule remote that is a plain filesystem path. Without
#       this flag such a remote is refused (REFUSED_FOREIGN_UPSTREAM) like any
#       other out-of-scope upstream. This exists ONLY so the hermetic test
#       suites can reach the fetch path at all; a filesystem path can be
#       another real repository on this machine, or an NFS/SMB/sshfs mount
#       that reaches another machine while naming no host.
#
#   --publish-local-modifications
#       REMOVED 2026-08-26. Accepted by the parser ONLY so that passing it is
#       a loud exit 2 naming the removal, never a silently-ignored argument
#       and never a fall-through to a different mode. See "WHY STEP 6 DOES NOT
#       EXIST HERE" below.
#
# With no argument, operates on the repository containing the current working
# directory (`git rev-parse --show-toplevel`). An optional first argument
# overrides which repository to operate on — this is how the hermetic test
# suite at tests/pipeline/test_advance_all_submodules.sh points it at
# disposable git fixtures without touching this repository's real submodules.
# Nothing here is hardcoded to a particular repo path, submodule name,
# branch, remote name, or host (§6.R): every one of those is resolved at
# runtime from git itself.
#
# Environment overrides (all optional except as noted):
#
#   LAVA_PIPELINE_RUN_ID
#       The pipeline run this advance belongs to. Used to derive the default
#       record directory and the default verification command. REQUIRED
#       unless BOTH LAVA_ADVANCE_RECORD_DIR and LAVA_ADVANCE_VERIFY_CMD are
#       supplied — because without a run id this script cannot honestly claim
#       to have rebuilt-and-tested anything (phase-01/phase-02 both require a
#       run id with an existing report.json).
#
#   LAVA_ADVANCE_RECORD_DIR
#       Where Submodule Advance Records are written. Default:
#       .lava-ci-evidence/pipeline-runs/<run_id>/submodule-advances
#       (relative to the parent repository root), per data-model.md.
#
#   LAVA_ADVANCE_VERIFY_CMD
#       The R-005 step-5 rebuild-and-test command, run once per advanced
#       submodule via `bash -c`, with $1 = the submodule's absolute path,
#       $2 = the parent repository root and $3 = the run id. A zero exit means
#       "the advanced state still builds and passes"; non-zero means breaking
#       change. The run id arrives as a POSITIONAL ARGUMENT, never interpolated
#       into the command text -- see the LAVA_PIPELINE_RUN_ID note below.
#       Default:
#         scripts/pipeline/phase-01-build.sh "$3" "$2" &&
#         scripts/pipeline/phase-02-test.sh  "$3" "$2"
#
#   LAVA_ADVANCE_SUBMODULES
#       Optional whitespace-separated allow-list of submodule paths (as they
#       appear in `git submodule status`). Default: every submodule.
#
# EVERY submodule this script processes leaves a Submodule Advance Record —
# root CLAUDE.md's Automated Pipeline Pin-Advance Path condition (E): "Every
# submodule processed produces a Submodule Advance Record ... 'All submodules
# advanced' without per-submodule records is not evidence."
#
# Until the T054 review this was not true: eight terminal refusal paths (a pin
# move that is not a fast-forward, an uninitialized submodule, an unreachable
# upstream, a remote whose default branch cannot be resolved, a parent index
# that cannot be staged, ...) matched none of the five enum values then
# available, and rather than assert a cause that did not occur they were left
# with NO record — loud on the console, invisible at rest. The enum was widened
# instead (FAILED_PRECONDITION, REFUSED_NOT_FAST_FORWARD,
# REFUSED_FOREIGN_UPSTREAM, REJECTED_PARENT_STAGING_FAILED), so every outcome
# is both truthful AND recorded. Four further values added during the T054
# review rounds -- REJECTED_VERIFY_MUTATED_LOCAL_WORK,
# REJECTED_SUBMODULE_STAGING_FAILED, REJECTED_SUBMODULE_COMMIT_FAILED and
# REJECTED_COMMIT_SCOPE_EXCEEDED -- named causes that could only arise inside
# step 6. With step 6 removed no code path can produce them, and a contract
# value nothing can emit is a contract that lies about what the writer does,
# so they were removed from the schema in the same change. Zero Submodule
# Advance Records existed at rest at the time of removal (measured: no
# `submodule-advances` directory exists anywhere under .lava-ci-evidence), so
# no record at rest carries a value the schema no longer admits.
#
# ONE case still cannot be recorded, honestly and by construction: a parent
# index whose gitlink for the submodule is unreadable. The record schema
# requires a commit sha (^[0-9a-f]{7,40}$) and none was ever established, so
# there is nothing to write that would not be invented. It is reported on
# stderr, counted, and reflected in the exit code.
#
# Exit codes:
#   0 - every submodule ended ADVANCED, NO_NEWER_COMMIT, or was refused for
#       want of a standing operator authorization on its own PATH
#       (REFUSED_GOVERNANCE_DENY via the allow-list). The last of those is
#       exit 0 DELIBERATELY and is not an oversight: with GOVERNANCE_ALLOW
#       naming one submodule out of twenty-five, a path-level refusal is the
#       designed steady state of every single run, and an exit code that
#       reports failure on every run is an exit code its operator learns to
#       ignore. Nothing is attempted for such a submodule, nothing is left
#       inconsistent, and a record naming the refusal exists at rest. The
#       DANGEROUS half of governance is NOT exit 0 -- see exit 1.
#   1 - at least one submodule was rejected or refused for a reason that
#       means something is wrong: a breaking change, an unclean working tree
#       (there is no flag that overrides this), an out-of-scope upstream, an
#       authorized PATH whose upstream names a repository nobody authorized,
#       an upstream identity that could not be read at all, a governance
#       certification that examined zero identities, a parent index that
#       could not be staged, or a precondition that could not be established.
#   2 - usage / configuration error (bad repo path, missing run id with no
#       overrides, unwritable record directory, a removed flag passed on the
#       command line, a corpus this run could not fully account for).
#       Nothing was attempted.
#
# WHY STEP 6 DOES NOT EXIST HERE (removed 2026-08-26, after five review
# rounds; this is the reasoning, not an apology for it):
#
#   Every one of the TWELVE distinct, fixture-proven ways this script was
#   found able to put unaudited content onto another repository's default
#   branch lived in step 6, and step 6 was reachable ONLY through
#   `--publish-local-modifications`. The rounds went 3 -> 5 -> 7 -> 9 -> 10
#   -> 11 -> 12: every round confirmed the previous round's fixes and found
#   more. Round 3 stopped enumerating ROUTES and began guarding the ARTEFACT;
#   rounds 4 and 5 then enumerated PROJECTIONS of the artefact, and the
#   eleventh was a projection nobody had listed -- 4000 bytes of payload
#   carried in the commit object's own author-name header, which reached both
#   mirrors with all five publish guards green.
#
#   The convergent instrument was tried and it FAILED: `commit-tree` inherits
#   `i18n.commitEncoding`, so the expected commit is built in the same
#   adversary-controlled config context as the real one and the poison
#   cancels out on both sides -- byte equality PASSED while attacker bytes
#   were published. Guarding had provably stopped converging.
#
#   The cost of removal was measured rather than assumed: step 6 had no
#   caller (`scripts/pipeline/phase-07-closure.sh` did not exist, and T055's
#   design states the closure phase never passes the flag); FR-000 already
#   makes the pipeline refuse to start on a dirty tree, using
#   `--ignore-submodules=none` precisely so submodule dirt is visible, so in
#   a pipeline run a dirty submodule is a PRECONDITION FAILURE, not an input;
#   and all 61 recursive submodules were clean at the time of removal. The
#   operator's own `git commit` handles the case better, with a human reading
#   the diff.
#
#   So an unclean submodule working tree is now a plain REFUSAL
#   (FAILED_PRECONDITION) with NO opt-in of any kind. Passing
#   `--publish-local-modifications` is exit 2 naming the removal -- never
#   silently ignored, and never falling through to a different mode.
#
# WHAT THIS SCRIPT CAN AND CANNOT REACH (stated here as well as enforced,
# because a safety property nobody can find is one nobody can rely on):
#
#   What it DOES: fetches, compares, checks the remote HEAD out, runs the
#   step-5 verify command, discards the advance on failure, and stages a pin
#   in the PARENT INDEX. It issues NO `git push` anywhere, to any remote, on
#   any path. It creates NO commit, in the submodule or in the parent.
#
#   What it CANNOT honestly claim: that "nothing leaves this machine". That
#   sentence stood here through five review rounds and it was FALSE, for a
#   reason that has nothing to do with step 6. The step-5 verify command is
#   an operator-supplied command line executed by `bash -c` on the DEFAULT,
#   FLAGLESS path, against a working tree checked out from an upstream
#   moments earlier -- and it inherits this host's live push credentials.
#   Measured on this host: `SSH_AUTH_SOCK` live with two loaded keys,
#   `~/.ssh/id_ed25519` readable with an empty passphrase, `gh` and `glab`
#   configs readable, 25 own-org submodule upstreams plus the parent's two
#   mirrors in reach, with no timeout and no resource bound on the
#   invocation. Scrubbing the environment does NOT close this: OpenSSH
#   resolves `~` from the passwd entry, not from `$HOME`, so a push succeeded
#   from a fully empty environment with `HOME` redirected to an empty
#   directory.
#
#   That exposure is NOT closed by removing step 6, is NOT closed by any
#   guard in this file, and is recorded here rather than papered over
#   because a false safety statement is the §6.J bluff class in prose: it is
#   a gate reporting success having examined nothing. Closing it needs the
#   verify invocation to run inside a credential-masked sandbox (bwrap is
#   present on this host and needs no privilege escalation, §6.U). That work
#   is NOT done here and is owed.
#
# Safety properties this script guarantees, and why each exists:
#
#   * It issues NO git push at all. Not to a submodule's upstream, not to the
#     parent's mirrors, not on any path, with or without any flag. This used
#     to read "every git push it issues is a plain fast-forward push"; the
#     stronger statement is now true because the only pushing code in this
#     file was step 6 and step 6 is gone. §6.T.3's prohibition on
#     history-overwriting pushes is satisfied by construction rather than by
#     careful flag selection.
#   * It NEVER commits — not in a submodule, and not in the parent repository. Step 7 stages the pin with
#     `git add` only; committing and pushing the parent is phase-07-closure's
#     job (T055), deliberately kept separate so a human review gate sits
#     between "pins staged" and "pins pushed".
#   * On any rejection it ATTEMPTS to restore the submodule to its prior
#     pinned commit. The restore is a plain (non-discarding) checkout, so git
#     can legitimately refuse it — e.g. when the rebuild-and-test step wrote
#     into a tracked file that differs between the two commits. When that
#     happens the submodule is left sitting on the REJECTED commit and the
#     parent tree is dirty; the script never overwrites the working tree to
#     force the restore. Every such case is counted and named in the run
#     summary ("not restored to the prior pin") so it cannot hide inside a
#     25-submodule run. The parent's PIN is never staged in that case.
#   * It refuses to move a pin that is not a fast-forward. A remote default
#     branch whose HEAD merely DIFFERS from the pin is not necessarily AHEAD
#     of it: this project pins submodules to side branches on purpose (root
#     CLAUDE.md records Containers pinned on `lava-pin/2026-05-07-pkg-vm`), and
#     "advancing" such a pin to origin/HEAD silently DROPS the pinned work.
#   * It NEVER destroys local work, and no longer needs a rescue-ref
#     mechanism to say so: a submodule carrying uncommitted work is refused
#     before anything is fetched or checked out, so its work is never moved,
#     never committed and never at risk. The refusal names every path it
#     found.
#   * No privilege escalation anywhere — plain git as the calling user (§6.U).
#
# Classification: project-specific (the record schema and evidence layout are
# this pipeline's; the fetch/compare/verify/discard ordering is general).

set -uo pipefail

# ---------------------------------------------------------------------------
# The ambient git environment, neutralised (and PROVEN neutralised)
# ---------------------------------------------------------------------------
# This file invests heavily in proving that `BASH_ENV` cannot hold its NAMES —
# the safety switches, GOVERNANCE_ALLOW, the three identity functions, `gith`.
# It left git's OWN environment untouched, and git's environment does not
# redirect what this script READS: it redirects where this script WRITES.
#
# Measured, on git 2.50.1, with NO flags given and a disposable fixture:
#
#   GIT_DIR=<a repository nobody named on argv>
#     EXIT=0. The reader self-test below created TWO commits in that
#     repository, moved its branch tip off the operator's work, left its index
#     dirty with a `decoy.txt` that does not exist in its worktree, and left a
#     `refs/replace/<sha>` ref behind — the exact instrument of the NINTH
#     escape, installed by the guard written to CLOSE the ninth escape, into a
#     repository the caller never mentioned, and not cleaned up (the tidy-up
#     `rm -rf` removes an empty directory). The run then reported
#     "has no submodules — nothing to do" and exited 0 having examined nothing.
#   GIT_INDEX_FILE=<another repository's index>
#     The self-test's `git add` wrote into THAT index; the witness came back
#     MUTATED. The self-test then failed and the run exited 2 — fails closed,
#     but only after the damage.
#   GIT_NO_REPLACE_OBJECTS=1 / GIT_REPLACE_REF_BASE=refs/evil/
#     EXIT=2. Both make the UNHARDENED reader ignore replace refs too, so the
#     self-test cannot discriminate and refuses. Nothing is damaged, but a
#     benign environment variable blocks the pipeline on a guard that was
#     working — the same FALSE-refusal class the `--template=` isolation was
#     added to avoid, in the opposite direction.
#
# `git submodule foreach` exports GIT_DIR and every commit-time hook exports
# GIT_INDEX_FILE (both measured), so this is not a contrived environment; it is
# the shape a wrapper runs this script in.
#
# Every repository this script touches is named by `-C` or by an absolute path
# it computed itself. None of the names below has a legitimate role here, so
# they are removed rather than worked around.
#
# ...and `unset` is an ASSIGNMENT, which is exactly what the switch-reset check
# further down exists because a `declare -r` in BASH_ENV can defeat. Under
# `set +e` a failed `unset` is not fatal, so the run would continue believing
# it had neutralised a name that is still pointing at another repository. The
# result is therefore CHECKED, name by name, and a name that survives is fatal
# before any git command runs at all.
ADVANCE_GIT_ENV_NEUTRALISED=(
  # Where git reads and writes: the whole redirect family.
  GIT_DIR GIT_WORK_TREE GIT_COMMON_DIR GIT_INDEX_FILE
  GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_NAMESPACE
  # Config injected into EVERY git invocation, including the submodule's.
  GIT_CONFIG GIT_CONFIG_COUNT
  # The two names that decide what "a replaced object" means, i.e. whether the
  # reader self-test below can discriminate at all.
  GIT_NO_REPLACE_OBJECTS GIT_REPLACE_REF_BASE
  # Pathspec magic. Every scope guard in this file states its scope as
  # `-- <path>`; a control that defines scope must not have its meaning
  # rewritten by the environment.
  GIT_LITERAL_PATHSPECS GIT_GLOB_PATHSPECS GIT_NOGLOB_PATHSPECS GIT_ICASE_PATHSPECS
  # Belt and braces for the self-test's `--template=` isolation.
  GIT_TEMPLATE_DIR
)
# GIT_CONFIG_KEY_<n> / GIT_CONFIG_VALUE_<n> are read only when GIT_CONFIG_COUNT
# is set, so clearing the count is sufficient — but leaving the pairs in the
# environment leaves a loaded channel for anything downstream that sets a count
# of its own. They are enumerated rather than guessed at a fixed arity.
_gitcfg_pairs=()
mapfile -t _gitcfg_pairs < <(compgen -v 2>/dev/null || true)
for _v in ${_gitcfg_pairs[@]+"${_gitcfg_pairs[@]}"}; do
  if [[ "$_v" =~ ^GIT_CONFIG_(KEY|VALUE)_[0-9]+$ ]]; then
    ADVANCE_GIT_ENV_NEUTRALISED+=("$_v")
  fi
done
unset _gitcfg_pairs

# DELIBERATELY NOT in the list above, each for a stated reason rather than by
# omission — an unexplained gap in a list like this is how the eleven escapes
# happened:
#
#   GIT_CONFIG_GLOBAL / GIT_CONFIG_SYSTEM / GIT_CONFIG_NOSYSTEM
#     These NARROW which config git reads; a caller sets them to ISOLATE a run
#     from the operator's ~/.gitconfig. Unsetting them would re-admit that
#     config — a weakening dressed as hardening. Measured: no effect on this
#     script's behaviour either way.
#   GIT_CEILING_DIRECTORIES / GIT_DISCOVERY_ACROSS_FILESYSTEM
#     These only STOP repository discovery walking upwards. Unsetting them
#     WIDENS the walk, so a no-argument invocation could resolve an OUTER
#     repository the operator meant to fence off. Constraining is not
#     redirecting. Measured: no effect.
#   GIT_SSH_COMMAND / GIT_TERMINAL_PROMPT
#     Safety controls the test suites rely on to guarantee no fixture can open
#     a connection. Removing them would be removing a seatbelt.
#   GIT_AUTHOR_* / GIT_COMMITTER_*
#     They used to be left alone because they decided the IDENT BYTES of the
#     commit R-005 step 6 published. Step 6 is gone and this script now
#     creates no commit anywhere, so they decide nothing this script writes.
#     They are STILL left alone, for a different and now the only reason:
#     they are part of the environment the step-5 verify command inherits,
#     and silently changing what an operator-supplied build sees is not this
#     change's subject. They ARE neutralised inside the reader self-test,
#     whose commits must not depend on ambient ident -- and that neutralisation
#     is independently load-bearing, because a malformed `GIT_AUTHOR_DATE`
#     makes git reject the ident outright and used to turn the self-test into
#     a FALSE exit-2 refusal that blocked the pipeline.
_git_env_still_set=()
for _v in "${ADVANCE_GIT_ENV_NEUTRALISED[@]}"; do
  unset "$_v" 2>/dev/null
  [[ -z "${!_v+x}" ]] || _git_env_still_set+=("$_v")
done
if [[ "${#_git_env_still_set[@]}" -gt 0 ]]; then
  echo "advance-all-submodules: the ambient git environment could not be neutralised — ${#_git_env_still_set[@]} name(s) survived 'unset' and are still pointing git somewhere: ${_git_env_still_set[*]}. Something in the invoking environment (a readonly declaration, e.g. via BASH_ENV) is holding them. These names redirect which repository git READS and WRITES, so a run that cannot remove them cannot say which repository its own guards measured, nor which one they would have written to. Nothing was attempted." >&2
  exit 2
fi
unset _git_env_still_set

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Reuse the pipeline's existing filename sanitizer rather than re-deriving
# one: submodule paths contain "/" and must be flattened into a single
# record filename. evidence.sh is sourced for that one helper; if it is ever
# unavailable this script falls back to an identical inline implementation
# rather than failing (the library is a convenience here, not a dependency).
if [[ -r "${SCRIPT_DIR}/pipeline/lib/evidence.sh" ]]; then
  # shellcheck source=scripts/pipeline/lib/evidence.sh
  source "${SCRIPT_DIR}/pipeline/lib/evidence.sh"
fi

_advance_sanitize_name() {
  if declare -F _evidence_sanitize_test_id >/dev/null 2>&1; then
    _evidence_sanitize_test_id "$1"
    return 0
  fi
  local sanitized
  sanitized="$(printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_')"
  sanitized="$(printf '%s' "$sanitized" | sed -E 's/_{2,}/_/g; s/^_+//; s/_+$//')"
  [[ -n "$sanitized" ]] || sanitized="unnamed-submodule"
  printf '%s' "$sanitized"
}

# ---------------------------------------------------------------------------
# Argument / environment resolution
# ---------------------------------------------------------------------------

# Why these are FLAGS and not environment variables.
#
# Both switches below relax a safety boundary, so the question that decides
# their shape is: can an UNATTENDED pipeline run pick one up without anybody
# having asked for it in that run? An environment variable can — it is
# inherited wholesale from whatever the invoking shell exported, possibly
# hours earlier in an unrelated test session, and it survives every layer of
# `bash script.sh` in between. argv cannot: a phase script invokes this one
# with a fixed argument list, so a flag has to be typed at the invocation
# site, in the run that uses it. Root CLAUDE.md's Automated Pipeline
# Pin-Advance Path condition (D) makes the same distinction for force-pushing
# — an approval "an unattended pipeline cannot obtain and therefore may never
# assume". The environment is a channel through which it could assume one.
#
# ...and "argv cannot" was ASSERTED rather than CHECKED, which left one hole.
# `BASH_ENV` is itself an inherited environment variable; bash sources it
# before this script's first line, and a file containing
# `declare -r ALLOW_LOCAL_PATH_REMOTES=true` makes the reset below FAIL. Under
# `set +e` a failed assignment is not fatal, so the run continued with the
# switch stuck on and PRINTED that a flag "was given" when argv said no such
# thing. Captured at the T054 round-3 review against the then-existing
# `--publish-local-modifications`, which is now removed; the identical hole
# exists for the one remaining switch, so the check remains. The capability
# escalation is nil (anyone who can set `BASH_ENV` already has arbitrary code
# execution as this user and could type the flag), but the HONESTY defect is
# real and independent of the threat model: by this file's own standard a
# refusal that misstates its own cause is a small bluff, and an AUTHORIZATION
# that misstates its own origin is the same class.
#
# So the reset's exit status is now checked, and the final values are checked
# against what argv actually asked for. Either disagreeing is fatal before any
# submodule is touched: a switch whose value did not come from argv is a
# switch this script cannot honestly say was given.
_switch_reset_rc=0
ALLOW_LOCAL_PATH_REMOTES=false || _switch_reset_rc=1
if [[ "$_switch_reset_rc" -ne 0 || "$ALLOW_LOCAL_PATH_REMOTES" != "false" ]]; then
  echo "advance-all-submodules: the safety switch could not be reset to its default OFF state (ALLOW_LOCAL_PATH_REMOTES='${ALLOW_LOCAL_PATH_REMOTES:-<unset>}') — something in the invoking environment (a readonly declaration, e.g. via BASH_ENV) is holding it. This switch relaxes a safety boundary and is deliberately argv-only, so a value this run cannot prove came from its own command line is not one it will act on. Nothing was attempted." >&2
  exit 2
fi
# What argv asked for, kept separately so the final values can be COMPARED
# against it rather than trusted.
_argv_allow_local_path=false
REPO_PATH=""
_repo_path_given=false
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --allow-local-path-remotes)    ALLOW_LOCAL_PATH_REMOTES=true; _argv_allow_local_path=true; shift ;;
    # A REMOVED flag gets its OWN branch that EXITS, never a deletion that
    # lets it reach some other branch. Deleting an argument branch once let a
    # flag fall through to `*) shift` and silently become a repository-path
    # argument -- the flag was accepted, ignored, and the run continued in a
    # different mode than the operator asked for (LVA-120). A capability
    # removed for safety reasons must FAIL LOUDLY when requested: silence
    # here would read to the caller as "granted".
    --publish-local-modifications)
      echo "advance-all-submodules: '--publish-local-modifications' was REMOVED on 2026-08-26 and this run will not proceed. It used to permit R-005 step 6 — committing a submodule's pre-existing uncommitted work and PUBLISHING it to that submodule's own upstream default branch. Five review rounds found TWELVE distinct, fixture-proven ways for content no guard had read to reach another repository's default branch through that path, every one of them inside step 6, and guarding provably stopped converging: the convergent byte-equality instrument was itself defeated because 'git commit-tree' inherits 'i18n.commitEncoding', so the expected commit was built in the same adversary-controlled config context and the poison cancelled out on both sides. The capability is gone, not disabled: there is no flag, environment variable or configuration that restores it. A submodule whose working tree is not clean is now REFUSED and nothing is fetched, checked out, verified or staged for it. Commit, stash or remove the work yourself — with a human reading the diff — and re-run. Nothing was attempted." >&2
      exit 2
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "advance-all-submodules: unknown option '$1'. Usage: advance-all-submodules.sh [--allow-local-path-remotes] [parent-repo-path]. Nothing was attempted." >&2
      exit 2
      ;;
    *)
      if [[ "$_repo_path_given" == "true" ]]; then
        echo "advance-all-submodules: more than one repository path given ('$REPO_PATH' and '$1'). Nothing was attempted." >&2
        exit 2
      fi
      REPO_PATH="$1"; _repo_path_given=true; shift
      ;;
  esac
done
if [[ "$#" -gt 0 ]]; then
  if [[ "$_repo_path_given" == "true" || "$#" -gt 1 ]]; then
    echo "advance-all-submodules: more than one repository path given. Nothing was attempted." >&2
    exit 2
  fi
  REPO_PATH="$1"; _repo_path_given=true
fi

# The other half of the same check. A readonly `ALLOW_LOCAL_PATH_REMOTES=false`
# in the environment would make the parser's `=true` fail just as silently, so
# a flag that WAS typed would be quietly ignored and the run would refuse
# remotes it was asked to permit while reporting nothing about why. Both
# directions are the same defect — the switch not reflecting argv — and both
# are fatal here.
if [[ "$ALLOW_LOCAL_PATH_REMOTES" != "$_argv_allow_local_path" ]]; then
  echo "advance-all-submodules: a safety switch does not reflect this run's command line (--allow-local-path-remotes: argv said ${_argv_allow_local_path}, the switch reads ${ALLOW_LOCAL_PATH_REMOTES}). Something in the invoking environment is holding this name. Nothing was attempted." >&2
  exit 2
fi

if [[ -z "$REPO_PATH" ]]; then
  if ! REPO_PATH="$(git rev-parse --show-toplevel 2>/dev/null)"; then
    echo "advance-all-submodules: not inside a git repository and no repo path given" >&2
    exit 2
  fi
fi
if ! REPO_ROOT="$(cd "$REPO_PATH" 2>/dev/null && pwd)"; then
  echo "advance-all-submodules: '$REPO_PATH' is not a readable directory" >&2
  exit 2
fi
if ! git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  echo "advance-all-submodules: '$REPO_ROOT' is not a git repository" >&2
  exit 2
fi

RUN_ID="${LAVA_PIPELINE_RUN_ID:-}"
RECORD_DIR="${LAVA_ADVANCE_RECORD_DIR:-}"
VERIFY_CMD="${LAVA_ADVANCE_VERIFY_CMD:-}"

# The run id is DATA supplied by the caller's environment, and it reaches two
# places where an unvalidated value is not merely wrong but dangerous: it
# becomes a path component of RECORD_DIR, and it is handed to the step-5
# rebuild-and-test command. Both were reachable, and both were demonstrated
# against disposable fixtures during the T054 review:
#
#   * a run id of ../../../.. walked RECORD_DIR clean out of the evidence tree
#     and created a directory there.
#   * the default verify command used to be BUILT BY STRING INTERPOLATION and
#     then handed to `bash -c`. A single quote in the value closed the quoting
#     and the remainder was parsed as shell, so a run id carrying an injected
#     `exit 0` ran arbitrary commands AND made the gate exit 0 without running
#     any build at all. The pin then advanced with ZERO verification and was
#     recorded ADVANCED -- defeating the single property the whole design
#     rests on (research.md R-005 note 1: the script cannot advance a pin it
#     is unable to honestly claim to have re-verified). That is a security
#     defect and an anti-bluff defect at once: the gate reported a
#     verification it never performed.
#
# Both close the same way -- values never become syntax. The run id is
# validated as a plain token here, and passed to the verify command as a
# positional argument rather than spliced into its text.
if [[ -n "$RUN_ID" && ! "$RUN_ID" =~ ^[A-Za-z0-9._:-]+$ ]]; then
  echo "advance-all-submodules: LAVA_PIPELINE_RUN_ID contains characters outside [A-Za-z0-9._:-] — refusing to use it as an evidence-path component or to hand it to the rebuild-and-test step. Nothing was attempted." >&2
  exit 2
fi
# ...and that character class still admits the only two path-navigation tokens
# it could. No '/' can appear, so `..` cannot walk out of .lava-ci-evidence —
# but it DOES silently relocate a run's records out of its own run directory
# into the shared pipeline-runs/ parent, where the next run's records land on
# top of them. Evidence that quietly points somewhere other than where the
# caller believes is the same class of defect as no evidence at all.
if [[ "$RUN_ID" =~ ^\.+$ ]]; then
  echo "advance-all-submodules: LAVA_PIPELINE_RUN_ID is '${RUN_ID}', which is a path-navigation token rather than a run identifier — using it would relocate this run's Submodule Advance Records out of its own run directory. Nothing was attempted." >&2
  exit 2
fi

if [[ -z "$RECORD_DIR" ]]; then
  if [[ -z "$RUN_ID" ]]; then
    echo "advance-all-submodules: LAVA_PIPELINE_RUN_ID is unset and LAVA_ADVANCE_RECORD_DIR was not supplied — cannot decide where to write Submodule Advance Records" >&2
    exit 2
  fi
  RECORD_DIR="${REPO_ROOT}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/submodule-advances"
fi

if [[ -z "$VERIFY_CMD" ]]; then
  if [[ -z "$RUN_ID" ]]; then
    echo "advance-all-submodules: LAVA_PIPELINE_RUN_ID is unset and LAVA_ADVANCE_VERIFY_CMD was not supplied — refusing to advance a pin without a real rebuild-and-test step (R-005 step 5)" >&2
    exit 2
  fi
  # $1 (the submodule path) is intentionally unused by the default command:
  # the real rebuild-and-test is whole-repository, not per-submodule.
  # $3 is the run id, supplied POSITIONALLY by the `bash -c` invocation at
  # R-005 step 5. It is deliberately NOT interpolated into this string: the
  # previous form spliced the run id between single quotes, so a value
  # containing a single quote closed the quoting and had its remainder parsed
  # as shell -- arbitrary command execution, and (worse) an injected exit 0
  # that made the verify gate certify a build it never ran.
  VERIFY_CMD='"$2"/scripts/pipeline/phase-01-build.sh "$3" "$2" && "$2"/scripts/pipeline/phase-02-test.sh "$3" "$2"'
fi

if ! mkdir -p -- "$RECORD_DIR"; then
  echo "advance-all-submodules: could not create record directory '$RECORD_DIR'" >&2
  exit 2
fi

# `mkdir -p` returns 0 for a directory that ALREADY EXISTS, whatever its mode
# is, so its success does not establish that a record can be written there.
# Without this probe an existing read-only record directory was discovered
# only at the FIRST RECORD WRITE -- by which time the submodule had already
# been fetched, checked out, verified and its pin staged in the parent index,
# while the run produced zero evidence at rest. The exit-code table above has always promised exit 2
# and "Nothing was attempted" for this condition; this makes that true.
_advance_write_probe="${RECORD_DIR}/.advance-writable-probe.$$"
if ! : > "$_advance_write_probe" 2>/dev/null; then
  echo "advance-all-submodules: record directory '$RECORD_DIR' exists but cannot be written to — refusing to fetch, advance or stage anything whose outcome could not then be recorded. Nothing was attempted." >&2
  exit 2
fi
rm -rf -- "$_advance_write_probe"

# Scratch space for the NUL-delimited `git status` capture below. A command
# substitution cannot carry NUL bytes, and a path may legitimately contain a
# newline, so the capture goes through a file.
if ! ADVANCE_TMPDIR="$(mktemp -d)"; then
  echo "advance-all-submodules: could not create a temporary working directory. Nothing was attempted." >&2
  exit 2
fi
trap 'rm -rf -- "$ADVANCE_TMPDIR"' EXIT

# ---------------------------------------------------------------------------
# The adversary-independent object reader
# ---------------------------------------------------------------------------
# Four consecutive reviews found ten ways for content to reach another
# repository through R-005 step 6, and every fix but the last was an ENTRY on
# a list of routes. The round-3 pass tried to end that by measuring the
# published ARTEFACT instead of the ways in — the right instinct, and it still
# failed twice, for one structural reason the round-4 review named exactly:
#
#   the post-commit invariant is a TREE diff, read through the repository the
#   adversary controls, while a push transmits HISTORY from the true objects.
#
# Two mechanisms exploited precisely that gap, both captured on disposable
# fixtures with EXIT=0 and outcome ADVANCED:
#
#   * `refs/replace/<sha>` substitutes one object for another ON READ. `git
#     diff` honours it (core.useReplaceRefs defaults true); the push sends the
#     REAL object, because the ref's value is the real sha. A post-commit hook
#     publishing a benign replacement showed the guard `operator.txt` while
#     the wire carried `leak.txt` — and `leak.txt`, holding a credential dump,
#     landed in the upstream default branch's TREE.
#   * a `post-commit` hook re-pointing HEAD at a `commit-tree` merge whose
#     tree is byte-identical to the honest commit's. The tree diff is empty
#     outside the audited set; the transfer carries a fabricated root commit
#     and its blob.
#
# So every measurement that GATES a publish or a pin is taken through this
# reader, which removes the two mappings the submodule's own repository can
# rewrite underneath it:
#
#   --no-replace-objects        ignore refs/replace/* entirely.
#   -c core.commitGraph=false   ignore .git/objects/info/commit-graph, a CACHE
#                               of the commit DAG that git trusts without
#                               verifying and that anything able to write the
#                               submodule can author. Reachability answers
#                               (`rev-list`, `merge-base`) come from the real
#                               objects or they are not answers.
#
# It is a function rather than a bare prefix so that no call site can be added
# and miss one, and its behaviour is PROVEN below rather than assumed.
gith() {
  git --no-replace-objects -c core.commitGraph=false "$@"
}

# ...and "the hardened reader ignores replace refs" is a CLAIM. A claim that
# gates an irreversible publish is checked, not trusted: `readonly -f gith` in
# a BASH_ENV file would leave the definition above inert exactly the way a
# `declare -r` left the safety switches inert at the round-3 review, and this
# run would then measure through the adversary's mapping while believing it
# did not. The self-test builds a throwaway repository, points a replace ref
# at a different commit, and REQUIRES the hardened reader to disagree with the
# unhardened one. If it cannot be run, or it does not discriminate, this run
# refuses before touching any submodule. A guard whose instrument is unproven
# is not a guard.
#
# The self-test repository is isolated from the operator's global git config on
# the two axes that could make it fail for a reason that has nothing to do with
# the reader: an `init.templateDir` carrying hooks, and a global
# `core.hooksPath`. Either would run somebody's `pre-commit` inside this
# throwaway repository, and a self-test that fails for an unrelated reason is a
# FALSE exit-2 refusal that blocks the pipeline — the same defect class in the
# opposite direction. Neither is set on the operator's host today (measured
# read-only), which is exactly why it must not be assumed.
#
# ...and isolation on those two axes is isolation from what a repository's
# CONFIG can do to it. It is not isolation from what git's ENVIRONMENT can do
# to it, and the environment decides something larger: WHICH REPOSITORY this
# self-test writes to at all. With `GIT_DIR` inherited, `git init` initialised
# that repository instead, `cd` succeeded into an empty directory, and every
# `git config` / `git add` / `git commit` / `git update-ref` below then
# operated on a repository the caller never named — creating two commits in
# it, moving its branch tip, dirtying its index, and leaving a
# `refs/replace/*` ref behind. That last part is the ninth escape's own
# mechanism, installed by the fix for the ninth escape. Measured with no flags
# given; `git submodule foreach` exports GIT_DIR, so it is a reachable shape.
#
# The whole self-test therefore runs inside ONE subshell whose git environment
# is neutralised FIRST — `git init` included, since that is the call the
# inherited GIT_DIR redirected. The names are removed again here rather than
# relied upon from the top of the file: this block's correctness is what the
# publish guards rest on, and it should not depend on a hundred lines of
# unrelated code in between having done its job. Removal is PROVEN, not
# assumed, for the same reason it is proven up there.
#
# GIT_AUTHOR_* / GIT_COMMITTER_* are added to the set here (and only here).
# The two `git config user.*` calls below state the intended ident, and those
# environment variables silently override config — so without removing them
# the self-test's commits carry whatever ident the caller's environment held.
# A throwaway repository built to prove one property must not vary with the
# environment in any respect at all.
_selftest_dir="${ADVANCE_TMPDIR}/reader-selftest"
_selftest_empty="${ADVANCE_TMPDIR}/reader-selftest-empty"
_reader_selftest_ok=false
mkdir -p "$_selftest_empty" 2>/dev/null
if [[ -d "$_selftest_empty" ]]; then
  (
    _st_v=""
    for _st_v in "${ADVANCE_GIT_ENV_NEUTRALISED[@]}" \
                 GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL GIT_AUTHOR_DATE \
                 GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL GIT_COMMITTER_DATE; do
      unset "$_st_v" 2>/dev/null
      [[ -z "${!_st_v+x}" ]] || exit 1
    done
    git init --quiet --template="$_selftest_empty" "$_selftest_dir" >/dev/null 2>&1 || exit 1
    cd "$_selftest_dir" 2>/dev/null || exit 1
    # ...and `cd` succeeding is not proof that git now regards THIS directory
    # as the repository. It is the last place the answer can still be wrong,
    # and it is cheap to ask: the toplevel git resolves from here must be the
    # throwaway directory this block created, or nothing below is happening
    # where this block believes it is happening.
    _st_top="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 1
    [[ "$_st_top" -ef "$_selftest_dir" ]] || exit 1
    git config core.hooksPath "$_selftest_empty" >/dev/null 2>&1 || exit 1
    git config user.email "advance-selftest@invalid" >/dev/null 2>&1 || exit 1
    git config user.name "advance selftest" >/dev/null 2>&1 || exit 1
    git config commit.gpgsign false >/dev/null 2>&1 || exit 1
    printf 'a\n' > real.txt || exit 1
    git add -- real.txt >/dev/null 2>&1 || exit 1
    git commit --quiet -m one >/dev/null 2>&1 || exit 1
    printf 'b\n' > decoy.txt || exit 1
    git add -- decoy.txt >/dev/null 2>&1 || exit 1
    git commit --quiet -m two >/dev/null 2>&1 || exit 1
    _c2="$(git rev-parse HEAD 2>/dev/null)" || exit 1
    _c1="$(git rev-parse HEAD^ 2>/dev/null)" || exit 1
    [[ -n "$_c1" && -n "$_c2" ]] || exit 1
    git update-ref "refs/replace/${_c2}" "$_c1" >/dev/null 2>&1 || exit 1
    _plain="$(git rev-parse "${_c2}^{tree}" 2>/dev/null)" || exit 1
    _hard="$(gith rev-parse "${_c2}^{tree}" 2>/dev/null)" || exit 1
    _truth="$(git --no-replace-objects rev-parse "${_c2}^{tree}" 2>/dev/null)" || exit 1
    [[ -n "$_plain" && -n "$_hard" && -n "$_truth" ]] || exit 1
    # The replace ref must actually have taken effect, or the test proves
    # nothing about the hardened reader; and the hardened reader must return
    # the object's real tree.
    [[ "$_plain" != "$_hard" ]] || exit 1
    [[ "$_hard" == "$_truth" ]] || exit 1
    exit 0
  ) && _reader_selftest_ok=true
fi
rm -rf -- "$_selftest_dir" "$_selftest_empty"
if [[ "$_reader_selftest_ok" != "true" ]]; then
  echo "advance-all-submodules: the hardened object reader could not be PROVEN to ignore refs/replace/* (its self-test did not discriminate a replaced object from the real one). Every guard standing between a build and an irreversible publish reads objects through that reader, so a reader whose behaviour is unknown makes those guards unknown too. Nothing was attempted." >&2
  exit 2
fi

# Split ONCE, into an array. `for tok in $ALLOW_LIST` was unquoted, so every
# token underwent pathname expansion against the caller's working directory:
# an allow-list of "*" was rewritten by the filesystem before it was ever
# compared. A control whose job is to NARROW a run must not have its own value
# rewritten by anything.
ALLOW_LIST_TOKENS=()
if [[ -n "${LAVA_ADVANCE_SUBMODULES:-}" ]]; then
  read -r -a ALLOW_LIST_TOKENS <<< "${LAVA_ADVANCE_SUBMODULES}"
fi

# _is_allowed <submodule-path> — exit 0 when the allow-list is empty (default:
# every submodule) or contains this path as a WHOLE whitespace-separated token.
#
# Deliberately not `grep -qw "$sub_path" <<< "$ALLOW_LIST"`: that treats the
# path as a REGEX and matches on word boundaries, where '-', '.' and '/' are
# non-word characters. Naming "submodules/auth-extra" therefore also selected
# "submodules/auth". An allow-list is a control that NARROWS a run; a narrowing
# control that can widen one is worse than no control at all.
_is_allowed() {
  local needle="$1" tok
  [[ "${#ALLOW_LIST_TOKENS[@]}" -gt 0 ]] || return 0
  for tok in "${ALLOW_LIST_TOKENS[@]}"; do
    [[ "$tok" == "$needle" ]] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Governance allow-list (default-DENY, NOT widenable by LAVA_ADVANCE_SUBMODULES)
# ---------------------------------------------------------------------------
# This pipeline may advance a submodule's pin ONLY when a standing operator
# authorization exists for that specific submodule. Everything else is refused
# without being examined, no matter how green the rebuild-and-test step is.
#
# INVERTED from a deny-list to an allow-list on 2026-08-26, on explicit
# operator decision, closing LVA-138. The prior shape was
# `GOVERNANCE_DENY=("constitution")` — a single entry — while `.gitmodules`
# declares 25 submodules. Root CLAUDE.md's Decoupled Reusable Architecture pin
# policy names 16 of them (auth, cache, challenges, concurrency, config,
# containers, database, discovery, http3, mdns, middleware, observability,
# ratelimiter, recovery, security, tracker_sdk) as pins-frozen-by-default whose
# bumps each require a deliberate per-submodule operator authorization, routes
# `constitution` through CONST-049's 7-step human pipeline, and carries the
# standing Q9 always-track-upstream waiver for `helixqa`. That left 7
# submodules — doc_processor, llm_orchestrator, llm_provider, llms_verifier,
# panoptic, superspec, vision_engine — named by NO operator decision at all,
# yet mechanically advanceable. A default-deny list can only ever be as correct
# as it is complete, and this one was 1/25 complete; a default-DENY posture is
# correct by construction instead, because a submodule nobody has ruled on
# fails closed rather than open.
#
# `helixqa` is the sole entry because it is the sole submodule carrying a
# standing authorization to track upstream unattended (root CLAUDE.md's
# operator-authorized pin-policy waiver, Phase 4-C-1 decision Q9). Adding an
# entry here is a governance act: it asserts that an operator has authorized
# unattended advancement of that submodule, and it MUST NOT be done to make a
# test or a pipeline run go green.
#
# Deliberately NOT widenable via LAVA_ADVANCE_SUBMODULES: that variable is a
# convenience for NARROWING a run, and a convenience switch must never be able
# to turn off a governance boundary. There is no test-only override either --
# the test suites exercise the allowed path by naming their fixture submodule
# `helixqa`, so this boundary has no bypass of any kind.
#
# Matching is on the path's final component, so `helixqa` and
# `submodules/helixqa` are both covered.
#
# The assignment's exit status is CHECKED and its result COMPARED against the
# literal above, for the reason the two safety switches already are (see "Why
# these are flags and not environment variables"): `declare -r
# GOVERNANCE_ALLOW=(helixqa constitution)` in a BASH_ENV file makes the
# assignment below FAIL, and under `set +e` a failed assignment is not fatal.
# Captured on a disposable fixture at the T054 round-4 review: with BASH_ENV
# widening it, a submodule named `constitution` — the one root CLAUDE.md
# routes through CONST-049's 7-step human pipeline — ADVANCED, exit 0, with no
# diagnostic whatsoever. BASH_ENV is set on the operator's host today, so this
# is a live channel and not a hypothetical one. The capability escalation is
# nil (anyone who can set BASH_ENV already has arbitrary code execution as
# this user), which is exactly the argument the round-3 pass rejected for the
# switches: a governance boundary that cannot say where its own value came
# from is not one this script may act on. This one guards a STRICTLY STRONGER
# boundary than those switches do.
_gov_allow_rc=0
GOVERNANCE_ALLOW=(
  "helixqa"
) || _gov_allow_rc=1
if [[ "$_gov_allow_rc" -ne 0 \
      || "${#GOVERNANCE_ALLOW[@]}" -ne 1 \
      || "${GOVERNANCE_ALLOW[0]:-}" != "helixqa" ]]; then
  echo "advance-all-submodules: the governance allow-list could not be set to its declared value (it reads '${GOVERNANCE_ALLOW[*]:-<unset>}', ${#GOVERNANCE_ALLOW[@]} entry/entries, assignment exit ${_gov_allow_rc}) — something in the invoking environment (a readonly declaration, e.g. via BASH_ENV) is holding the name. This list decides which submodules an operator has authorized this pipeline to advance unattended; a value this run cannot prove is its own is not one it will act on. Nothing was attempted." >&2
  exit 2
fi

# _is_governance_denied <submodule-path> — exit 0 (denied) unless this
# submodule carries a standing operator authorization. Name kept from the
# deny-list era so every call site and record outcome reads the same; the
# POLICY inverted, the question this answers did not.
_is_governance_denied() {
  local candidate="${1##*/}" allowed
  for allowed in "${GOVERNANCE_ALLOW[@]}"; do
    [[ "$candidate" == "$allowed" ]] && return 1
  done
  return 0
}

# _governance_refusal_reason <submodule-path> — the human-readable WHY, for the
# console only. GOVERNANCE_ALLOW above is the sole authority on whether a
# submodule is refused; this only sharpens the message a reviewer reads.
#
# Every branch must be a TRUE statement. The default is deliberately the
# weakest true statement rather than a specific one, so that if this table
# falls out of date with root CLAUDE.md the message degrades to
# vague-but-correct instead of confidently-wrong. A refusal that misstates its
# own cause is a small bluff, and small bluffs are how large ones get trusted.
_governance_refusal_reason() {
  case "${1##*/}" in
    constitution)
      echo "its pin is advanced only through CONST-049's 7-step human-driven pipeline (root CLAUDE.md). It holds the document set defining what this pipeline may do at all, so advancing it here would let the pipeline rewrite its own rules as a side effect"
      ;;
    auth|cache|challenges|concurrency|config|containers|database|discovery|http3|mdns|middleware|observability|ratelimiter|recovery|security|tracker_sdk)
      echo "root CLAUDE.md's Decoupled Reusable Architecture rule pins it frozen by default; each bump requires a deliberate per-submodule operator authorization, which is a human decision this pipeline cannot make for itself"
      ;;
    *)
      # The weakest statement that is TRUE BY CONSTRUCTION. GOVERNANCE_ALLOW
      # is the sole authority this function describes, and it is right above:
      # "this submodule is not in that list" cannot go stale, because the list
      # IS the thing being reported on.
      #
      # The previous wording asserted a fact about the absence of operator
      # decisions ANYWHERE on record. That claim goes false the moment an
      # operator authorizes a submodule without this table being updated —
      # which is precisely the confidently-wrong drift the comment above says
      # the default exists to avoid. It was already at its edge: of the 7
      # submodules this arm fires for, `panoptic` does appear once in root
      # CLAUDE.md, inside a §6.L historical narrative rather than a pin-policy
      # decision. Near-miss or not, a refusal that misstates its own cause is
      # the small bluff, and small bluffs are how large ones get trusted.
      #
      # It still tells the reader where an authorization WOULD live, so the
      # message is actionable without asserting anything about what exists
      # elsewhere.
      echo "it carries no standing operator authorization in this pipeline: it is absent from GOVERNANCE_ALLOW in scripts/advance-all-submodules.sh, which is the only authorization this pipeline can act on, and adding it there is a governance act requiring an operator decision recorded in root CLAUDE.md. Refusing is the only honest outcome: silence is not consent"
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Record writing
# ---------------------------------------------------------------------------

# write_advance_record <submodule_name> <old_commit> <new_commit> \
#                      <parent_pin_updated> <outcome>
#
# Builds the JSON with `jq -n` (or a python3 json.dump fallback) — never by
# interpolating into a JSON string literal — writes through a temp file, and
# re-parses the bytes on disk before reporting success. Same anti-bluff
# discipline as scripts/pipeline/lib/evidence.sh, for the same reason: never
# trust that a writer produced valid JSON just because it did not complain.
#
# `local_modifications_pushed` is a REQUIRED schema field and is emitted as
# the literal `false`. It is deliberately NOT a parameter any more. This
# script has no code path that commits or pushes anything, so a caller-supplied
# value could only ever be `false` — and a parameter whose value cannot vary is
# a channel through which a future edit could make the record claim a publish
# that did not happen. The schema pins the same fact from the other side with
# `"const": false`. The field is retained rather than dropped because
# data-model.md and root CLAUDE.md condition (E) both name it, and a consumer
# reading old and new records must not have to branch on its presence.
write_advance_record() {
  local name="$1" old="$2" new="$3" pin_updated="$4" outcome="$5"
  local out_path tmp_path
  out_path="${RECORD_DIR}/$(_advance_sanitize_name "$name").json"
  tmp_path="${out_path}.tmp.$$"

  case "$outcome" in
    ADVANCED|NO_NEWER_COMMIT|REJECTED_BREAKING_CHANGE|REJECTED_PUSH_CONFLICT|REFUSED_GOVERNANCE_DENY) ;;
    # Added at T054, closing root CLAUDE.md's Automated Pipeline Pin-Advance
    # Path condition (E). Eight terminal refusal paths used to write NO record
    # at all, because reusing one of the five values above would have asserted
    # a cause that did not occur. The honest fix is a value per cause, not a
    # nearest fit — so the enum grew rather than the truth shrinking.
    FAILED_PRECONDITION|REFUSED_NOT_FAST_FORWARD|REFUSED_FOREIGN_UPSTREAM) ;;
    REJECTED_PARENT_STAGING_FAILED) ;;
    # REMOVED 2026-08-26 with R-005 step 6, from this whitelist AND from the
    # schema enum, because every one of them named a cause that could only
    # arise while committing or pushing a submodule's local work:
    #   REJECTED_VERIFY_MUTATED_LOCAL_WORK
    #   REJECTED_SUBMODULE_STAGING_FAILED
    #   REJECTED_SUBMODULE_COMMIT_FAILED
    #   REJECTED_COMMIT_SCOPE_EXCEEDED
    # Keeping them would leave the contract describing a writer that no longer
    # exists — the same defect in the other direction as a missing value. Zero
    # Submodule Advance Records existed at rest when they were removed
    # (measured: no `submodule-advances` directory anywhere under
    # .lava-ci-evidence), so no record at rest carries one.
    #
    # REJECTED_PUSH_CONFLICT is RETAINED above and is currently UNEMITTABLE by
    # this script: no code path here issues a push. It stays because root
    # CLAUDE.md's Automated Pipeline Pin-Advance Path condition (E) names it
    # explicitly as one of four outcomes, and a schema that cannot express a
    # value the governing document names is a worse disagreement than a value
    # nothing currently emits. Removing it requires an operator amendment to
    # condition (E), which is proposed rather than taken here.
    *)
      echo "advance-all-submodules: internal error — invalid outcome '$outcome'" >&2
      return 1
      ;;
  esac

  # Schema invariant (submodule-advance-record.schema.json's allOf/if-then),
  # stated as the general rule rather than as a prefix match on REJECTED_*:
  # ADVANCED is the ONLY outcome that may claim the parent pin moved, and it
  # must claim it. Enforced here so a future edit to the control flow cannot
  # silently emit a record that says "refused" and "pin updated" at once.
  if [[ "$outcome" != "ADVANCED" && "$pin_updated" != "false" ]]; then
    echo "advance-all-submodules: internal error — outcome '$outcome' with parent_pin_updated='$pin_updated' violates the record schema" >&2
    return 1
  fi
  if [[ "$outcome" == "ADVANCED" && "$pin_updated" != "true" ]]; then
    echo "advance-all-submodules: internal error — outcome 'ADVANCED' with parent_pin_updated='$pin_updated' claims an advance that did not move the pin" >&2
    return 1
  fi

  if command -v jq >/dev/null 2>&1; then
    jq -n \
      --arg submodule_name "$name" \
      --arg old_commit "$old" \
      --arg new_commit "$new" \
      --argjson parent_pin_updated "$pin_updated" \
      --arg outcome "$outcome" \
      '{
        submodule_name: $submodule_name,
        old_commit: $old_commit,
        new_commit: $new_commit,
        local_modifications_pushed: false,
        parent_pin_updated: $parent_pin_updated,
        outcome: $outcome
      }' > "$tmp_path" || { rm -rf -- "$tmp_path"; return 1; }
  else
    python3 - "$tmp_path" "$name" "$old" "$new" "$pin_updated" "$outcome" <<'PYEOF' || { rm -rf -- "$tmp_path"; return 1; }
import json
import sys

tmp_path, name, old, new, pin_updated, outcome = sys.argv[1:7]
record = {
    "submodule_name": name,
    "old_commit": old,
    "new_commit": new,
    "local_modifications_pushed": False,
    "parent_pin_updated": pin_updated == "true",
    "outcome": outcome,
}
with open(tmp_path, "w") as handle:
    json.dump(record, handle, indent=2, ensure_ascii=False)
    handle.write("\n")
PYEOF
  fi

  if ! python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$tmp_path" >/dev/null 2>&1; then
    echo "advance-all-submodules: internal error — wrote invalid JSON to '$tmp_path'" >&2
    rm -rf -- "$tmp_path"
    return 1
  fi

  mv -- "$tmp_path" "$out_path" || { rm -rf -- "$tmp_path"; return 1; }
  printf '%s\n' "$out_path"
}

# ---------------------------------------------------------------------------
# Per-submodule helpers
# ---------------------------------------------------------------------------

# preferred_remote <submodule-dir> — the remote to treat as the submodule's
# canonical upstream: "origin" when present, else the first configured one.
# Never hardcodes a host or URL (§6.R) — it only ever names a git remote.
preferred_remote() {
  local sub="$1" remotes
  remotes="$(git -C "$sub" remote 2>/dev/null)"
  [[ -n "$remotes" ]] || return 1
  if grep -qx 'origin' <<< "$remotes"; then
    echo "origin"
    return 0
  fi
  head -n1 <<< "$remotes"
}

# is_own_repo_root <dir> — exit 0 only when <dir> is the ROOT of its own git
# work tree.
#
# `git -C <dir> rev-parse --git-dir` is NOT this test: git walks UP the
# directory tree, so an EMPTY submodule directory (the state left by a plain
# `git clone` without --recursive, or by `git submodule deinit`) resolves to
# the PARENT repository's .git and reports success. Every subsequent
# `git -C "$sub_dir" ...` — fetch, ls-remote, checkout --detach, add -A,
# commit, push — would then operate on the parent repository instead of the
# submodule. Demonstrated 2026-08-22 against a disposable fixture: the parent
# repo was detached from its branch, its working tree replaced with its own
# origin/HEAD, and the run reported outcome=ADVANCED with exit 0.
is_own_repo_root() {
  local dir="$1" here top
  [[ -d "$dir" ]] || return 1
  here="$(cd "$dir" 2>/dev/null && pwd -P)" || return 1
  top="$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null)" || return 1
  [[ -n "$top" && -d "$top" ]] || return 1
  top="$(cd "$top" 2>/dev/null && pwd -P)" || return 1
  [[ "$here" == "$top" ]]
}

# restore_pin <submodule-dir> <old-commit> — put the submodule back exactly
# where the parent's pin says it should be. Deliberately a plain checkout,
# never a discarding one: overwriting the working tree would silently throw
# away any local modification still in it, which is the operator's data.
restore_pin() {
  local sub="$1" old="$2"
  if git -C "$sub" checkout --detach --quiet "$old" 2>/dev/null; then
    return 0
  fi
  echo "  !! could not restore '$sub' to its prior pin ${old} — leaving it as-is rather than overwriting it (inspect it by hand)" >&2
  return 1
}

# _remote_url_class <remote-url> — classify one configured remote for root
# CLAUDE.md's Automated Pipeline Pin-Advance Path condition (C):
#
#   "advances only submodules whose configured upstreams are vasic-digital/*
#    or HelixDevelopment/* on GitHub or GitLab."
#
# Three classes:
#   OWN_ORG     a GitHub or GitLab URL under vasic-digital or HelixDevelopment
#   LOCAL_PATH  a path on this filesystem. It is the only remote shape a
#               hermetic fixture can have, and a suite that cannot reach the
#               fetch path cannot prove the fetch path is safe. It is NOT
#               safe by virtue of naming no host: a local path can be another
#               REAL repository on this machine, or an NFS/SMB/sshfs mount
#               that reaches another one, and this script FETCHES from every
#               configured remote — so it is an object-injection surface.
#               Gated behind --allow-local-path-remotes for that reason.
#   FOREIGN     anything else — another host, or another org on GitHub/GitLab
#               (a personal fork, a scratch mirror). Refused, never skipped:
#               a silently skipped mirror is a §6.C divergence.
#
# The host and org names are policy CONSTANTS, not connection addresses: this
# function opens no socket and builds no URL. They live here for the same
# reason GOVERNANCE_ALLOW does — the value IS the governance decision.
_remote_url_class() {
  local url="$1" host="" path="" org="" rest before
  case "$url" in
    file://*)
      printf 'LOCAL_PATH'; return 0
      ;;
    *://*)
      rest="${url#*://}"
      rest="${rest#*@}"
      host="${rest%%/*}"
      host="${host%%:*}"
      path="${rest#*/}"
      ;;
    *:*)
      before="${url%%:*}"
      # A colon inside something that already looks like a filesystem path is
      # not an scp-style host separator.
      if [[ -z "$before" || "$before" == */* || "$before" == "." || "$before" == ".." ]]; then
        printf 'LOCAL_PATH'; return 0
      fi
      host="${before#*@}"
      path="${url#*:}"
      ;;
    *)
      printf 'LOCAL_PATH'; return 0
      ;;
  esac

  host="$(printf '%s' "$host" | tr 'A-Z' 'a-z')"
  path="${path#/}"
  path="${path%.git}"
  org="$(printf '%s' "${path%%/*}" | tr 'A-Z' 'a-z')"

  case "$host" in
    github.com|www.github.com|ssh.github.com|gitlab.com|www.gitlab.com|altssh.gitlab.com) ;;
    *) printf 'FOREIGN'; return 0 ;;
  esac
  case "$org" in
    vasic-digital|helixdevelopment) printf 'OWN_ORG' ;;
    *) printf 'FOREIGN' ;;
  esac
}

# _remote_repo_name <remote-url> — the repository NAME a remote URL points at,
# lowercased and without any .git suffix, or the empty string when the URL has
# no path component to read one from.
#
# Used by the governance boundary, not by the scope check: GOVERNANCE_ALLOW
# names a repository an operator has authorized, and matching that name only
# against the SUBMODULE PATH's final component means the authorization travels
# with a directory label rather than with the thing authorized. Both attacks
# the T054 re-review demonstrated exploit exactly that — a submodule at
# `vendor/x/helixqa`, or at `submodules/helixqa` pointing at a different
# repository, was advanced on the strength of its directory name alone.
#
# The name is read from an `<org>/<repo>` path and from nothing else. Taking
# the LAST path segment was the round-4 review's last recorded residual: it
# accepted `vasic-digital/sub/helixqa` and `helixdevelopment/a/b/c/helixqa` as
# naming the authorized repository, so the boundary rested on a substring
# rather than on an identity. Measured read-only on this repository when this
# landed: all 25 own-org remote URLs are flat `<org>/<repo>`, so requiring
# exactly two components costs production nothing. Anything else yields the
# empty string, which every caller already treats as UNREADABLE and therefore
# REFUSES — unknown refuses here, as it does everywhere else in this file.
_remote_repo_name() {
  local url="$1" path="" org="" repo=""
  case "$url" in
    *://*)
      path="${url#*://}"
      path="${path#*@}"
      path="${path#*/}"
      ;;
    *:*)
      path="${url#*:}"
      ;;
    *)
      path="$url"
      ;;
  esac
  path="${path#/}"
  path="${path%/}"
  path="${path%.git}"
  # Exactly two non-empty components, or nothing.
  [[ "$path" == */* ]] || return 0
  org="${path%%/*}"
  repo="${path#*/}"
  [[ -n "$org" && -n "$repo" && "$repo" != */* ]] || return 0
  printf '%s' "$(printf '%s' "$repo" | tr 'A-Z' 'a-z')"
}

# The three functions above and GOVERNANCE_ALLOW are the whole identity
# boundary, and every one of them can be held from OUTSIDE this file:
# `readonly -f _remote_url_class` in a BASH_ENV file leaves an attacker's
# definition in place and this file's redefinition inert, silently, under
# `set +e`. That is the same channel the round-4 review proved open against
# GOVERNANCE_ALLOW. So the boundary proves itself on known inputs before any
# submodule is examined: two URLs that must classify FOREIGN and OWN_ORG, a
# repository name that must be read, a name that must be REFUSED because it is
# not an `<org>/<repo>`, and both directions of the allow-list. A failure here
# means the identity gate is not the one this file defines, whatever it says
# about itself afterwards.
_identity_selftest_fail=""
[[ "$(_remote_url_class 'git@some-other-host.invalid:vasic-digital/helixqa.git')" == "FOREIGN" ]] \
  || _identity_selftest_fail="${_identity_selftest_fail} _remote_url_class(foreign-host)"
[[ "$(_remote_url_class 'git@github.com:vasic-digital/helixqa.git')" == "OWN_ORG" ]] \
  || _identity_selftest_fail="${_identity_selftest_fail} _remote_url_class(own-org)"
[[ "$(_remote_url_class 'git@github.com:someone-else/helixqa.git')" == "FOREIGN" ]] \
  || _identity_selftest_fail="${_identity_selftest_fail} _remote_url_class(foreign-org)"
[[ "$(_remote_repo_name 'git@github.com:vasic-digital/HelixQA.git')" == "helixqa" ]] \
  || _identity_selftest_fail="${_identity_selftest_fail} _remote_repo_name(flat)"
[[ -z "$(_remote_repo_name 'git@github.com:vasic-digital/sub/helixqa.git')" ]] \
  || _identity_selftest_fail="${_identity_selftest_fail} _remote_repo_name(nested-must-be-unreadable)"
if _is_governance_denied "helixqa"; then
  _identity_selftest_fail="${_identity_selftest_fail} _is_governance_denied(allowed-entry-was-denied)"
fi
if ! _is_governance_denied "some-submodule-nobody-authorized"; then
  _identity_selftest_fail="${_identity_selftest_fail} _is_governance_denied(unauthorized-was-allowed)"
fi
if [[ -n "$_identity_selftest_fail" ]]; then
  echo "advance-all-submodules: the identity boundary did not behave as this file defines it on known inputs (failed:${_identity_selftest_fail}). Something in the invoking environment is holding these names — a readonly function or array, e.g. via BASH_ENV — so the gate that decides WHICH repository may receive an unattended publish is not the gate written here. Nothing was attempted." >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------

# The exit status of the enumeration is checked BEFORE its output is parsed.
# `git submodule status` fails (exit 128) on a malformed .gitmodules, an
# unreadable index, and several other real conditions — and its stderr used to
# be discarded, so zero parsed rows read as "this repository has no
# submodules". A no-match must mean "nothing was LEARNED", never "nothing
# FAILED": reporting "nothing to do" + exit 0 there hands the caller a green
# verdict for a set of submodules that was never examined at all.
# stdout and stderr are captured SEPARATELY. Merging them put any diagnostic
# git emits on a SUCCESSFUL run into the very stream that is then sed'd and
# awk'd for submodule paths, so a warning line could be parsed as a submodule
# path. Separating them costs nothing and removes the hazard outright.
SUBMODULE_STATUS_RC=0
SUBMODULE_STATUS_RAW="$(git -C "$REPO_ROOT" submodule status 2>"${ADVANCE_TMPDIR}/submodule-status.err")" \
  || SUBMODULE_STATUS_RC=$?
SUBMODULE_STATUS_ERR="$(cat "${ADVANCE_TMPDIR}/submodule-status.err" 2>/dev/null)"
if [[ "$SUBMODULE_STATUS_RC" -ne 0 ]]; then
  echo "advance-all-submodules: could not enumerate submodules in '$REPO_ROOT' — 'git submodule status' exited ${SUBMODULE_STATUS_RC}. Nothing was attempted. git said:" >&2
  printf '%s\n' "$SUBMODULE_STATUS_ERR" | sed 's/^/  /' >&2
  exit 2
fi

mapfile -t SUBMODULE_PATHS < <(
  printf '%s\n' "$SUBMODULE_STATUS_RAW" \
    | sed -E 's/^[[:space:]]*[-+U]?//' \
    | awk 'NF >= 2 { print $2 }'
)

# ---------------------------------------------------------------------------
# The corpus floor: `.gitmodules` is the authority on what this repository HAS
# ---------------------------------------------------------------------------
# `"has no submodules — nothing to do"` used to `exit 0` here with zero
# records, having never once asked the repository what it declares. The
# enumeration above comes from `git submodule status`, which reads the INDEX,
# and the index is a thing the environment can point elsewhere. Measured, on a
# fixture parent whose `.gitmodules` declares one submodule:
#
#   GIT_INDEX_FILE=<a path with no index file> -> git reads an EMPTY index
#     EXIT=0, records=0, "'.../parent' has no submodules — nothing to do"
#
# Every commit-time git hook exports GIT_INDEX_FILE, so any invocation of this
# script (or of the pipeline) from inside one produced a green, evidence-free,
# zero-record run. For phase-07-closure.sh, `exit 0` + `0 advanced` is
# indistinguishable from "everything was already current".
#
# ...and the ZERO case is only the loudest projection of the defect, not the
# defect. A floor that fires at exactly zero is a floor with one stair. The
# same fixture with the gitlink for ONE of two declared submodules dropped from
# the index — no environment variable involved at all, just
# `git rm --cached -- submodules/other` —
#
#   EXIT=0, records=1, "1 submodule(s) examined", "0 rejected/failed"
#
# reports a clean run over a corpus that lost half its members and never names
# the one it did not look at. So the floor is stated over the SET, not over its
# size: every path `.gitmodules` declares must appear in the enumeration.
#
# `.gitmodules` is read as a FILE, with sed, never through git. That is the
# whole point — it is a second, independent authority, and the two disagreeing
# is itself the signal. Reading it through the same git that produced the
# enumeration would make the cross-check agree with itself by construction.
#
# This cannot false-refuse a healthy tree or a fresh clone, and both were
# measured rather than assumed: this repository reports 25 declared / 25
# enumerated, and a clone with NO `git submodule update --init` still
# enumerates every declared submodule (uninitialised ones are listed with a
# `-` prefix, which the sed above already strips). A submodule being REMOVED
# (`git rm -- <path>`) drops it from the worktree `.gitmodules` and from the
# index in the same operation, so that workflow stays green too — which is
# exactly why HEAD's copy of `.gitmodules` is deliberately NOT consulted here:
# it would refuse a legitimate in-progress removal.
ADVANCE_DECLARED_PATHS=()
if [[ -f "${REPO_ROOT}/.gitmodules" ]]; then
  while IFS= read -r _decl; do
    # `.gitmodules` is git-config format: strip surrounding whitespace, an
    # optional quoted form, a trailing slash and a leading './' so that a
    # cosmetic difference cannot masquerade as a missing submodule.
    _decl="${_decl%"${_decl##*[![:space:]]}"}"
    _decl="${_decl#\"}"; _decl="${_decl%\"}"
    _decl="${_decl#./}"; _decl="${_decl%/}"
    [[ -n "$_decl" ]] || continue
    for _seen in ${ADVANCE_DECLARED_PATHS[@]+"${ADVANCE_DECLARED_PATHS[@]}"}; do
      [[ "$_seen" == "$_decl" ]] && continue 2
    done
    ADVANCE_DECLARED_PATHS+=("$_decl")
  done < <(sed -n 's/^[[:space:]]*path[[:space:]]*=[[:space:]]*//p' "${REPO_ROOT}/.gitmodules" 2>/dev/null)
fi

_declared_unenumerated=()
for _decl in ${ADVANCE_DECLARED_PATHS[@]+"${ADVANCE_DECLARED_PATHS[@]}"}; do
  _decl_found=false
  for _known in ${SUBMODULE_PATHS[@]+"${SUBMODULE_PATHS[@]}"}; do
    if [[ "$_decl" == "$_known" ]]; then
      _decl_found=true
      break
    fi
  done
  [[ "$_decl_found" == "true" ]] || _declared_unenumerated+=("$_decl")
done

if [[ "${#_declared_unenumerated[@]}" -gt 0 ]]; then
  echo "advance-all-submodules: '${REPO_ROOT}/.gitmodules' declares ${#ADVANCE_DECLARED_PATHS[@]} submodule(s) but 'git submodule status' enumerated ${#SUBMODULE_PATHS[@]}, and ${#_declared_unenumerated[@]} declared path(s) are absent from the enumeration. This run would report an outcome for a corpus that is missing members it was never going to look at, which is the vacuous-pass shape — a gate reporting success having examined nothing, or having examined only part. Nothing was attempted." >&2
  echo "  declared in .gitmodules but NOT enumerated:" >&2
  for _decl in "${_declared_unenumerated[@]}"; do
    # Name the cause that OCCURRED, not the likeliest one. Three produce this
    # state and they need different actions, and a refusal that misstates its
    # own cause is a small bluff by this file's own standard — one this very
    # check committed when it first landed, reporting "the parent INDEX is
    # missing its gitlink" for a path whose gitlink was present and whose NAME
    # the enumeration had truncated.
    _decl_head="${_decl%%[[:space:]]*}"
    _decl_truncated=false
    if [[ "$_decl" == *[[:space:]]* ]]; then
      for _known in ${SUBMODULE_PATHS[@]+"${SUBMODULE_PATHS[@]}"}; do
        [[ "$_known" == "$_decl_head" ]] && { _decl_truncated=true; break; }
      done
    fi
    if [[ "$_decl_truncated" == "true" ]]; then
      echo "      ${_decl} — declared WITH WHITESPACE, and the enumeration yielded only '${_decl_head}'. 'git submodule status' prints '<sha> <path> (<describe>)' and this script splits that on whitespace, so a path containing a space is silently truncated to its first word and every later step would operate on a DIFFERENT path than the one declared. This is the enumeration losing the name, not the index losing the gitlink. Rename the submodule to a whitespace-free path." >&2
    elif [[ -e "${REPO_ROOT}/${_decl}" ]]; then
      echo "      ${_decl} — the path exists in the worktree but is absent from the enumeration, so the parent INDEX is missing its gitlink (e.g. 'git rm --cached' without removing the .gitmodules entry). Restore it with: git -C '${REPO_ROOT}' add -- '${_decl}'" >&2
    else
      echo "      ${_decl} — neither enumerated nor present in the worktree. The parent index and .gitmodules disagree about this submodule; reconcile them before advancing anything." >&2
    fi
  done
  echo "  → 'git submodule status' reads the INDEX, and the index is a thing the environment can redirect. If this is unexpected, check that the invoking shell exports none of GIT_DIR / GIT_INDEX_FILE / GIT_WORK_TREE (this script now removes them, so a survivor is a readonly declaration) and that '${REPO_ROOT}' is the repository you meant." >&2
  exit 2
fi

if [[ "${#SUBMODULE_PATHS[@]}" -eq 0 ]]; then
  # Nothing was declared and nothing was enumerated: the two independent
  # authorities agree, so "no submodules" is a MEASUREMENT rather than the
  # absence of one. One more independent reading before that is claimed — the
  # parent index's own gitlinks. A repository holding gitlinks it declares
  # nowhere has submodules this script cannot advance (there is no URL to
  # advance them from) AND would have gone unexamined in silence, which is the
  # same vacuous shape one level down.
  #
  # HONESTLY BOUNDED, because an unreachable guard described as a live one is
  # itself a small bluff: on git 2.50.1 this reading has never been observed to
  # fire, and could not be made to. That state does not reach here — `git
  # submodule status` refuses it first, with `fatal: no submodule mapping found
  # in .gitmodules for path '<path>'` and exit 128, which the enumeration's own
  # exit-status guard above turns into exit 2 and "Nothing was attempted"
  # (measured on a fresh clone of a parent whose .gitmodules was deleted while
  # its gitlink was kept). This stays as a BACKSTOP for an enumeration that
  # answers 0 instead of 128; it is not the defence that is doing the work
  # today, and it is not described as one.
  _gitlink_rc=0
  _gitlinks="$(gith -C "$REPO_ROOT" ls-files -s 2>/dev/null | awk '$1 == "160000" { print $4 }')" || _gitlink_rc=$?
  if [[ "$_gitlink_rc" -ne 0 ]]; then
    echo "advance-all-submodules: could not read the parent index of '${REPO_ROOT}' to confirm it holds no submodule gitlinks ('git ls-files -s' exited ${_gitlink_rc}). An empty corpus that could not be CONFIRMED empty is not an empty corpus. Nothing was attempted." >&2
    exit 2
  fi
  _gitlink_count=0
  [[ -n "$_gitlinks" ]] && _gitlink_count="$(printf '%s\n' "$_gitlinks" | awk 'NF { n++ } END { print n+0 }')"
  if [[ "$_gitlink_count" -gt 0 ]]; then
    echo "advance-all-submodules: '${REPO_ROOT}/.gitmodules' declares no submodules and none was enumerated, but the parent index holds ${_gitlink_count} submodule gitlink(s): $(printf '%s ' $_gitlinks). Reporting 'nothing to do' would be a clean verdict over pins nobody examined. Nothing was attempted." >&2
    exit 2
  fi

  # ...and now it can be SAID from evidence, and written down. A console line
  # that vanishes with the terminal is not evidence at rest, and root
  # CLAUDE.md's Automated Pipeline Pin-Advance Path condition (E) is explicit
  # that an unrecorded outcome is not one. The filename cannot collide with a
  # Submodule Advance Record: _advance_sanitize_name strips leading
  # underscores, so no submodule path can ever sanitize to `_corpus`.
  _corpus_record="${RECORD_DIR}/_corpus.json"
  if command -v jq >/dev/null 2>&1; then
    jq -n \
      --arg repo_root "$REPO_ROOT" \
      --arg gitmodules "$( [[ -f "${REPO_ROOT}/.gitmodules" ]] && echo present || echo absent )" \
      --arg examined_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
      '{ repo_root: $repo_root,
         outcome: "CORPUS_EMPTY_CONFIRMED",
         gitmodules: $gitmodules,
         declared_submodules: 0,
         enumerated_submodules: 0,
         index_gitlinks: 0,
         examined_at: $examined_at,
         basis: "three independent readings agree that this repository has no submodules: .gitmodules declares none, git submodule status enumerated none, and the parent index holds no gitlinks. This file exists so that an empty corpus is evidence at rest rather than a console line." }' \
      > "${_corpus_record}.tmp.$$" 2>/dev/null \
      && mv -f -- "${_corpus_record}.tmp.$$" "$_corpus_record" 2>/dev/null \
      || rm -f -- "${_corpus_record}.tmp.$$" 2>/dev/null
  else
    {
      printf '{\n'
      printf '  "repo_root": "%s",\n' "$REPO_ROOT"
      printf '  "outcome": "CORPUS_EMPTY_CONFIRMED",\n'
      printf '  "gitmodules": "%s",\n' "$( [[ -f "${REPO_ROOT}/.gitmodules" ]] && echo present || echo absent )"
      printf '  "declared_submodules": 0,\n'
      printf '  "enumerated_submodules": 0,\n'
      printf '  "index_gitlinks": 0,\n'
      printf '  "examined_at": "%s"\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      printf '}\n'
    } > "$_corpus_record" 2>/dev/null || true
  fi

  echo "advance-all-submodules: '$REPO_ROOT' has no submodules — .gitmodules declares 0, the enumeration found 0, and the parent index holds 0 gitlink(s); all three agree. Nothing to do."
  echo "advance-all-submodules: 0 submodule(s) examined"
  echo "Corpus record: ${_corpus_record}"
  exit 0
fi

# Every allow-list token must name a submodule this repository actually has.
# A typo selected NOTHING, and the run then reported "0 advanced, 0 already
# current, 0 rejected/failed" with zero records and exit 0 — a gate reporting
# success having examined nothing, which is the vacuous-pass family this
# project has recorded around fifty times. An allow-list naming nothing that
# exists is a configuration error, not an empty-but-successful run.
if [[ "${#ALLOW_LIST_TOKENS[@]}" -gt 0 ]]; then
  _unmatched_tokens=()
  for _tok in "${ALLOW_LIST_TOKENS[@]}"; do
    _tok_found=false
    for _known in "${SUBMODULE_PATHS[@]}"; do
      if [[ "$_tok" == "$_known" ]]; then
        _tok_found=true
        break
      fi
    done
    [[ "$_tok_found" == "true" ]] || _unmatched_tokens+=("$_tok")
  done
  if [[ "${#_unmatched_tokens[@]}" -gt 0 ]]; then
    echo "advance-all-submodules: LAVA_ADVANCE_SUBMODULES names ${#_unmatched_tokens[@]} path(s) this repository has no submodule for: ${_unmatched_tokens[*]}. Such a token selects nothing, and a run that examined nothing must never read as a clean run. Nothing was attempted." >&2
    echo "  submodules present: ${SUBMODULE_PATHS[*]}" >&2
    exit 2
  fi
fi

FAILURES=0
ADVANCED_COUNT=0
NOOP_COUNT=0
# Governance refusals are their OWN bucket. They were counted in none: a
# submodule refused for want of a standing operator authorization was reported
# as neither advanced, nor already-current, nor rejected, so
# "0 advanced, 0 already current, 0 rejected/failed" was the summary of a run
# in which a submodule had been refused — the same text as a run in which
# everything was already current. An outcome line that does not account for
# every submodule it says it examined is a partial statement presented as a
# whole one.
GOVERNANCE_REFUSED=0
UNRESTORED=0
UNRESTORED_PATHS=()
# How many submodules this run actually LOOKED AT. Reported separately from
# the outcome counts, because "0 advanced, 0 already current, 0 rejected"
# is the same text for "every submodule was already current" and for "no
# submodule was ever examined".
EXAMINED_COUNT=0

# restore_or_count <submodule-dir> <old-commit> <submodule-path> — restore the
# prior pin and, when git refuses, record that this submodule was left on the
# rejected commit so the run summary can say so.
restore_or_count() {
  if restore_pin "$1" "$2"; then
    return 0
  fi
  UNRESTORED=$((UNRESTORED + 1))
  UNRESTORED_PATHS+=("$3")
  return 1
}

# note_record_failure <submodule-path> — a Submodule Advance Record that could
# not be written leaves the outcome invisible at rest. Never swallowed.
note_record_failure() {
  echo "  !! failed to write the Submodule Advance Record for '$1' — this outcome has no evidence at rest" >&2
}

# refuse_and_record <submodule-path> <old-commit> <new-commit> <outcome> —
# end this submodule's processing with a recorded outcome and count the
# failure. Exists because root CLAUDE.md's Automated Pipeline Pin-Advance Path
# condition (E) says "Every submodule processed produces a Submodule Advance
# Record ... 'All submodules advanced' without per-submodule records is not
# evidence" — and eight terminal paths used to print to stderr and return,
# leaving the outcome loud on the console and INVISIBLE at rest. A refusal
# nobody can find afterwards is indistinguishable from a refusal that never
# happened.
#
# The trailing `false` is not a shortcut: a refusal never moves the parent
# pin, and write_advance_record enforces that independently.
refuse_and_record() {
  local sp="$1" old="$2" new="$3" outcome="$4"
  if ! write_advance_record "$sp" "$old" "$new" false "$outcome" >/dev/null; then
    note_record_failure "$sp"
  fi
  FAILURES=$((FAILURES + 1))
}

for sub_path in "${SUBMODULE_PATHS[@]}"; do
  if ! _is_allowed "$sub_path"; then
    continue
  fi
  EXAMINED_COUNT=$((EXAMINED_COUNT + 1))

  sub_dir="${REPO_ROOT}/${sub_path}"
  echo "==> ${sub_path}"

  # THE PIN IS THE PARENT'S INDEX GITLINK. Read once, here, and used for every
  # downstream decision: the fast-forward guard, the record's old_commit, and
  # restore_pin's target.
  #
  # It used to be read from the submodule's own `git rev-parse HEAD`, which is
  # a DIFFERENT reference whenever `git submodule status` shows a leading '+'.
  # Two consequences, both demonstrated against disposable fixtures at T054:
  #
  #   * A deliberately-pinned side-branch commit S was silently DROPPED. With
  #     the working tree left at an ancestor A, `merge-base --is-ancestor A B`
  #     passed, the pin moved to B, and S was no longer reachable from the
  #     staged pin — precisely the `lava-pin/2026-05-07-pkg-vm` case the guard
  #     further down says it exists to prevent. The record's old_commit named
  #     A, so the evidence at rest also misstated what was advanced FROM.
  #   * A run that ended UNRESTORED leaves the submodule parked on the
  #     REJECTED commit. The next run read that as the pin and reported
  #     NO_NEWER_COMMIT / exit 0 / a green record for a submodule whose pin
  #     had never moved.
  #
  # The governance branch below already did this correctly, and its comment
  # already explained why (an uninitialized submodule's HEAD resolves to the
  # PARENT's HEAD). This is that same read, hoisted so every branch shares it.
  pinned_commit="$(git -C "$REPO_ROOT" ls-files -s -- "$sub_path" 2>/dev/null | awk '{print $2; exit}')"

  # Governance deny-list is checked FIRST — before fetch, before comparing
  # commits, before anything. A denied submodule is not "considered and
  # rejected", it is never examined at all. Its current pin is recorded as
  # both old_commit and new_commit because no candidate commit was ever looked
  # up; claiming otherwise would be inventing a fact this script did not
  # establish.
  if _is_governance_denied "$sub_path"; then
    # ..."and NOT examined" is what this line used to say, while the summary
    # below counted this same submodule as one of the "N submodule(s)
    # examined". Both senses of the word were defensible and the two lines
    # flatly contradicted each other, which is worse than either. The line now
    # says what actually did and did not happen.
    echo "  == governance: NOT advancing, and its upstream was never contacted — nothing was fetched, checked out, verified, committed or pushed for it. This pipeline advances a submodule only when a standing operator authorization exists for it; ${sub_path} has none, because $(_governance_refusal_reason "$sub_path")."
    if [[ -z "$pinned_commit" ]]; then
      echo "  !! could not read '${sub_path}''s pin from the parent index — refusing to invent one for the record. This run is a failure." >&2
      FAILURES=$((FAILURES + 1))
      continue
    fi
    # NOT `|| true`: every other record-write failure is already covered by the
    # failure this loop iteration counts for its own reason, but a governance
    # refusal has no such backstop — swallowing it produced exit 0 with zero
    # evidence that the refusal ever happened.
    if ! write_advance_record "$sub_path" "$pinned_commit" "$pinned_commit" false "REFUSED_GOVERNANCE_DENY" >/dev/null; then
      note_record_failure "$sub_path"
      FAILURES=$((FAILURES + 1))
    else
      GOVERNANCE_REFUSED=$((GOVERNANCE_REFUSED + 1))
    fi
    continue
  fi

  # From here on EVERY terminal outcome writes a Submodule Advance Record
  # (condition (E)), and every record needs a commit sha the schema accepts.
  # An unreadable gitlink is the ONE case that genuinely cannot be recorded:
  # there is no sha to put in it, and inventing one is the bluff the schema's
  # ^[0-9a-f]{7,40}$ pattern exists to catch.
  if [[ ! "$pinned_commit" =~ ^[0-9a-f]{7,40}$ ]]; then
    echo "  !! could not read '${sub_path}''s pin from the parent index (got '${pinned_commit}') — there is nothing to compare against, and no record can be written because the schema requires a commit sha and none was established. NOTHING was attempted for it. This run is a failure." >&2
    FAILURES=$((FAILURES + 1))
    continue
  fi
  old_commit="$pinned_commit"

  if ! is_own_repo_root "$sub_dir"; then
    echo "  !! '${sub_path}' is not the root of its own git work tree: an uninitialized submodule (run 'git submodule update --init'), a leftover directory, or a symlink pointing out of the tree. NOTHING was attempted for it — git's upward .git discovery would otherwise make every git command below operate on the PARENT repository. This run is a failure." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi

  # The working tree must be sitting exactly on the pin. R-005 step 2 says
  # "compare the currently-pinned commit", not "compare HEAD": an advance that
  # starts from an unknown commit is not an advance, it is a guess. This also
  # refuses the leftover state an earlier UNRESTORED run leaves behind,
  # instead of mistaking it for a clean starting point.
  head_commit="$(git -C "$sub_dir" rev-parse HEAD 2>/dev/null)"
  if [[ -z "$head_commit" ]]; then
    echo "  !! could not resolve '${sub_path}''s HEAD — its working-tree state cannot be established, so it cannot be compared to the pin. Skipping, and this run is a failure." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi
  if [[ "$head_commit" != "$old_commit" ]]; then
    echo "  !! '${sub_path}''s working tree is at ${head_commit} but the parent pins ${old_commit} ('git submodule status' shows a leading '+'). That is either a hand checkout or the residue of an earlier run that could not be restored. Advancing from a commit the parent does not pin would move the pin from somewhere nobody chose — and would let a rejected commit read as the pin on the next run. REFUSING: nothing was fetched, checked out or staged. Restore it with: git -C '${sub_path}' checkout --detach ${old_commit}" >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi

  # Captured BEFORE anything is touched: whether this submodule carries local
  # work, and -- just as load-bearing -- WHICH paths, because the refusal
  # below names them and a refusal that cannot say what it found is one the
  # operator cannot act on.
  #
  # -z because a path may contain a newline. -uall so an untracked DIRECTORY
  # is expanded into the files it holds AT THIS MOMENT instead of collapsing
  # to `dir/`, which would re-admit anything the build later wrote into it.
  # The exit status is checked: an unreadable index must not read as "this
  # submodule carries no local work" -- that is a thing not learned, not a
  # thing found to be absent.
  local_status_rc=0
  git -C "$sub_dir" status --porcelain -z -uall > "${ADVANCE_TMPDIR}/status.z" 2>/dev/null \
    || local_status_rc=$?
  if [[ "$local_status_rc" -ne 0 ]]; then
    echo "  !! 'git status' exited ${local_status_rc} — whether '${sub_path}' carries local modifications could not be established, so neither answer can be claimed. Skipping, and this run is a failure." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi

  LOCAL_MOD_PATHS=()
  while IFS= read -r -d '' _entry; do
    [[ -n "$_entry" ]] || continue
    LOCAL_MOD_PATHS+=("${_entry:3}")
    # A rename/copy entry is followed by a SECOND NUL-terminated field naming
    # the original path; both sides belong to the operator's change.
    if [[ "${_entry:0:1}" == "R" || "${_entry:0:1}" == "C" \
       || "${_entry:1:1}" == "R" || "${_entry:1:1}" == "C" ]]; then
      IFS= read -r -d '' _orig || break
      [[ -n "$_orig" ]] && LOCAL_MOD_PATHS+=("$_orig")
    fi
  done < "${ADVANCE_TMPDIR}/status.z"

  has_local_mods=false
  [[ "${#LOCAL_MOD_PATHS[@]}" -gt 0 ]] && has_local_mods=true

  # --- THE CLEAN-WORKING-TREE PRECONDITION: a refusal with no opt-in -------
  #
  # An unclean submodule working tree is REFUSED. There is no flag, no
  # environment variable and no configuration that turns this into a publish;
  # `--publish-local-modifications` was removed on 2026-08-26 and passing it
  # is exit 2 before this loop is ever reached.
  #
  # WHY IT IS A REFUSAL AND NOT AN OPT-IN. Captured against a disposable
  # fixture: a single stray editor swapfile, `.notes.swp`, left in the
  # submodule was committed and published to another repository's default
  # branch, unattended, outcome ADVANCED, exit 0. Nobody asked for that. The
  # `-uall` capture is deliberately total, so ANY untracked byte -- a
  # swapfile, a .DS_Store, a build artefact predating the run -- used to arm
  # the publish path. Five review rounds then found TWELVE distinct ways for
  # content no guard had read to reach an upstream through that path, and the
  # count went UP at every round; the flag was removed rather than guarded a
  # sixth time.
  #
  # Four reasons this is the deliberate choice and not the timid one:
  #
  #   1. It converts a lucky fact into a checked precondition. A safety
  #      property that holds because of what a working tree happens to contain
  #      is not a property, it is a coincidence with good timing.
  #   2. It fails CLOSED. Refusing costs a run that a human then completes on
  #      purpose; proceeding cost an irreversible publish to a repository this
  #      run was never asked to write to.
  #   3. It is the same reasoning root CLAUDE.md's condition (D) already
  #      applies to force-pushing: an approval "an unattended pipeline cannot
  #      obtain and therefore may never assume". Publishing an operator's
  #      uncommitted work is not a history rewrite, but it is equally
  #      irreversible and equally un-approvable by an unattended process.
  #   4. It costs nothing operationally. FR-000 already makes the pipeline
  #      refuse to start on a dirty tree, with `--ignore-submodules=none` so
  #      submodule dirt is visible; in a pipeline run a dirty submodule is a
  #      precondition failure, not an input.
  #
  # The refusal happens BEFORE the fetch, so an unclean submodule's upstream
  # is never even contacted.
  if [[ "$has_local_mods" != "true" ]]; then
    echo "  working tree clean — ${sub_path} may be examined. Everything this run may do for it (fetch, checkout, verify, stage the parent pin) is local; this script issues no push and creates no commit, so nothing it does here can reach ${sub_path}'s upstream(s)."
  else
    echo "  !! '${sub_path}' has ${#LOCAL_MOD_PATHS[@]} uncommitted path(s) in its working tree. This script does not carry local modifications forward — R-005 step 6 was removed on 2026-08-26 after five review rounds found twelve fixture-proven ways for unaudited content to reach another repository's default branch through it. There is no flag that overrides this. REFUSING: nothing was fetched, checked out, verified or staged for this submodule, and its upstream was never contacted. Your work is untouched where it is. Commit, stash or remove the paths below — with a human reading the diff — and re-run. Paths:" >&2
    for _p in "${LOCAL_MOD_PATHS[@]}"; do
      echo "     - ${_p}" >&2
    done
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi

  if ! remote="$(preferred_remote "$sub_dir")"; then
    echo "  !! no git remote configured — cannot advance, and this run is a failure" >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi

  # --- root CLAUDE.md condition (C): own-org upstreams only ---------------
  # "advances only submodules whose configured upstreams are vasic-digital/*
  #  or HelixDevelopment/* on GitHub or GitLab. §6.W is not relaxed: no new
  #  remote, no other provider, no externally-maintained upstream is advanced
  #  automatically."
  #
  # There was no such check at all. Checked HERE — before the fetch, over
  # EVERY configured remote, not just the preferred one — because this script
  # fetches from all of them: a personal fork or a scratch mirror sitting in a
  # submodule clone would otherwise inject objects into the tree this run pins
  # for every other clone. It used to be able to RECEIVE an unattended publish
  # too, demonstrated against a fixture at T054; that route is gone with the
  # publish path, the injection route is not. Refused, never skipped: a
  # silently skipped mirror is a §6.C divergence.
  foreign_remotes=()
  # How many remotes this scan actually READ. The gate below used to fire only
  # when `foreign_remotes` was non-empty, so ZERO iterations read as "every
  # configured upstream is in scope" — a gate certifying a set it never looked
  # at, which is the vacuous-pass family this repository has ~50 recorded
  # instances of. Demonstrated at the T054 re-review with a PATH shim that made
  # this one `git remote` return rc 0 with no output: the submodule was
  # certified, then fetched, advanced and staged. Pointedly, the PUSH loop
  # received exactly this guard in the same fix pass and this scan did not.
  remotes_examined=0
  # Every OWN_ORG remote's repository name, for the governance identity check
  # after the scan.
  own_org_repo_names=()
  # LOCAL_PATH remotes carry NO repository identity, so they contribute no
  # name. Counted separately rather than left implicit, so the identity gate
  # below can tell "there were no identities to check" apart from "the scan
  # never ran" — the two are indistinguishable from an empty array alone.
  local_path_identities=0
  remote_scan_rc=0
  git -C "$sub_dir" remote > "${ADVANCE_TMPDIR}/remotes-scan.txt" 2>/dev/null \
    || remote_scan_rc=$?
  if [[ "$remote_scan_rc" -ne 0 ]]; then
    echo "  !! could not enumerate '${sub_path}''s remotes ('git remote' exited ${remote_scan_rc}) — whether every configured upstream is in scope could not be established, and an upstream that cannot be certified must not be fetched from. Skipping, and this run is a failure." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi
  while IFS= read -r _r; do
    [[ -n "$_r" ]] || continue
    remotes_examined=$((remotes_examined + 1))
    # FOUR forms are classified, and any one being out of scope refuses:
    #   fetch/effective — `git remote get-url`, AFTER url.<base>.insteadOf.
    #   fetch/raw       — `remote.<n>.url`, BEFORE that rewrite.
    #   push/effective  — `git remote get-url --push --all`, i.e. every
    #                     destination git would actually PUSH to, after
    #                     remote.<n>.pushurl and url.<base>.pushInsteadOf.
    #   push/raw        — `remote.<n>.pushurl` as literally configured.
    # An own-org URL redirected to a foreign host is caught by the effective
    # forms; a foreign URL redirected to something innocuous is caught by the
    # raw ones.
    #
    # The push forms were ABSENT, and that was a real hole rather than a
    # theoretical one. This scan used to read only the two fetch forms, and
    # neither is the push destination: git resolves that from
    # `remote.<n>.pushurl` and `url.<base>.pushInsteadOf`, visible only
    # through `git remote get-url --push`. Two captures at the T054 round-3
    # review: the operator's uncommitted work published to a second repository
    # this scan never examined (exit 0, outcome ADVANCED), and a genuine
    # connection ATTEMPT to a host this scan classifies FOREIGN, stopped only
    # by the fixture's blocked transport. The configuration pattern is present
    # in this repository today — `constitution`'s origin fetches from
    # github.com/HelixDevelopment and pushes to gitflic.ru, a provider §6.W
    # names explicitly forbidden — and only that submodule's separate
    # governance denial keeps it out of reach here.
    #
    # `--all` rather than bare `--push`: pushurl is multi-valued and git
    # pushes to EVERY value, while bare `--push` prints only the first.
    # Classifying the first and pushing to the rest is the same hole again.
    _r_url="$(git -C "$sub_dir" remote get-url "$_r" 2>/dev/null)"
    _r_url_raw="$(git -C "$sub_dir" config --get "remote.${_r}.url" 2>/dev/null)"
    if [[ -z "$_r_url" && -z "$_r_url_raw" ]]; then
      foreign_remotes+=("${_r} (URL unreadable — an upstream that cannot be identified cannot be certified in scope)")
      continue
    fi
    _urls_to_class=("$_r_url")
    # The raw form is only a SECOND thing to check when an insteadOf rewrite
    # actually made it different; otherwise it is the same string and would
    # report the same remote twice.
    [[ -n "$_r_url_raw" && "$_r_url_raw" != "$_r_url" ]] && _urls_to_class+=("$_r_url_raw")
    # The push destinations, read through a FILE with the exit status checked
    # rather than through a process substitution whose status is invisible: a
    # push-URL read that FAILED must not be indistinguishable from one that
    # found nothing to add, because "nothing to add" is exactly what an
    # unexamined push destination looks like.
    _push_url_rc=0
    {
      git -C "$sub_dir" remote get-url --push --all "$_r" 2>/dev/null || _push_url_rc=$?
      git -C "$sub_dir" config --get-all "remote.${_r}.pushurl" 2>/dev/null
    } > "${ADVANCE_TMPDIR}/push-urls.txt"
    if [[ "$_push_url_rc" -ne 0 ]]; then
      foreign_remotes+=("${_r} (its PUSH destination could not be read — 'git remote get-url --push --all' exited ${_push_url_rc} — and a destination that cannot be identified cannot be certified in scope)")
      continue
    fi
    while IFS= read -r _pu; do
      [[ -n "$_pu" ]] || continue
      _already=false
      for _seen in "${_urls_to_class[@]}"; do
        if [[ "$_seen" == "$_pu" ]]; then _already=true; break; fi
      done
      [[ "$_already" == "true" ]] || _urls_to_class+=("$_pu")
    done < "${ADVANCE_TMPDIR}/push-urls.txt"
    for _u in "${_urls_to_class[@]}"; do
      [[ -n "$_u" ]] || continue
      case "$(_remote_url_class "$_u")" in
        OWN_ORG)
          own_org_repo_names+=("$(_remote_repo_name "$_u")")
          ;;
        LOCAL_PATH)
          # A filesystem path was PERMITTED here on the reasoning that "no
          # third party can receive an unattended publish". That reasoning is
          # incomplete, and the T054 re-review proved it incomplete against a
          # fixture: a local path can be another REAL repository on this
          # machine — which is exactly the thing condition (C) exists to
          # protect — and it can equally be an NFS/SMB/sshfs mount that
          # reaches another machine while naming no host. Captured at the
          # time: an unauthorised same-filesystem repo received the operator's
          # work, outcome ADVANCED, exit 0. That particular route is gone with
          # the publish path; the reasoning is not. This script still FETCHES
          # from every configured remote, so such a remote remains an
          # object-injection surface whose objects end up under the pin this
          # run stages for every other clone of the parent.
          #
          # It is kept reachable only because a hermetic fixture can have no
          # other remote shape, and a suite that cannot reach the fetch path
          # cannot prove the fetch path is safe — and it is kept behind a
          # COMMAND-LINE flag rather than an environment variable so a stale
          # export from an earlier test session cannot re-open it for an
          # unattended run. Measured on this repository at the time this
          # landed: zero LOCAL_PATH remotes exist across all 25 submodules, so
          # closing this costs production nothing.
          if [[ "$ALLOW_LOCAL_PATH_REMOTES" != "true" ]]; then
            foreign_remotes+=("${_r} -> ${_u} (a filesystem path: it may be another real repository on this machine, or a network mount that reaches another machine while naming no host. Pass --allow-local-path-remotes only for disposable fixtures)")
          else
            local_path_identities=$((local_path_identities + 1))
          fi
          ;;
        *) foreign_remotes+=("${_r} -> ${_u}") ;;
      esac
    done
  done < "${ADVANCE_TMPDIR}/remotes-scan.txt"
  # A scan that read NOTHING has established nothing. `git remote` exiting 0
  # with empty output is not "no out-of-scope upstreams found", it is "the
  # upstream set was not learned" — and this submodule reached this point
  # BECAUSE preferred_remote found at least one remote a moment ago, on a
  # separate invocation whose exit status it discards. The two disagreeing is
  # itself the signal.
  if [[ "$remotes_examined" -eq 0 ]]; then
    echo "  !! the own-org scope check read ZERO remotes for '${sub_path}' ('git remote' exited 0 with no output), yet a remote was resolved a moment earlier — so whether every configured upstream is in scope was never established. A gate that passes having examined nothing is not a gate. REFUSING: nothing was fetched or advanced for this submodule." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi
  if [[ "${#foreign_remotes[@]}" -gt 0 ]]; then
    echo "  !! ${#foreign_remotes[@]} configured remote(s) fall outside the own-org scope root CLAUDE.md's Automated Pipeline Pin-Advance Path condition (C) allows (GitHub or GitLab, under vasic-digital or HelixDevelopment). This script FETCHES from EVERY configured remote, so an out-of-scope one is an object-injection surface whose objects would end up under the pin this run stages for every other clone. REFUSING — nothing was fetched or advanced for this submodule:" >&2
    for _f in "${foreign_remotes[@]}"; do
      echo "     - ${_f}" >&2
    done
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "REFUSED_FOREIGN_UPSTREAM"
    continue
  fi

  # --- governance, second half: WHICH REPOSITORY, not which directory ------
  # GOVERNANCE_ALLOW names a repository an operator authorized this pipeline to
  # advance unattended. The check at the top of this loop matches it against
  # the submodule PATH's final component, so the authorization travels with a
  # directory label. Two attacks in the T054 re-review exploited precisely
  # that: a submodule whose path ends in an allowed name but whose upstream is
  # a DIFFERENT repository, and the same trick at a nested path. Condition (C)
  # constrains the remote to own-org, which bounds the blast radius, but
  # "some own-org repository" is not "the repository an operator authorized".
  #
  # So the path name is now NECESSARY and no longer SUFFICIENT: every own-org
  # remote's repository name must ALSO be an allowed entry. Deliberately
  # conjunctive rather than a replacement — dropping the path-name key would
  # move the whole boundary onto a URL, which is edited in .gitmodules just as
  # freely as a directory name is, and would leave the fixtures (whose bare
  # repos are filesystem paths, not named repositories) unable to exercise the
  # allowed path at all without weakening the check they exercise.
  #
  # LOCAL_PATH remotes carry no repository identity to check and are reachable
  # only under --allow-local-path-remotes, so they are governed by the path
  # name alone; that is a test-mode surface, not a production one.
  #
  # THIS GATE ARRIVED WITHOUT A FLOOR, and it was the only new gate in its fix
  # pass that did — the (C) scan and the (then-existing) push loop both got
  # one. Zero
  # iterations read as "every upstream names an authorized repository", which
  # is the vacuous-pass family this file refuses everywhere else. Captured at
  # the T054 round-3 review: a submodule at `vendor/x/helixqa` whose only
  # remote is a filesystem path leaves `own_org_repo_names` empty, and it
  # ADVANCED with this gate having examined nothing. An unparseable name was
  # `continue`d for the same reason — unknown read as OK, where everywhere
  # else in this file unknown refuses.
  identities_examined=0
  mismatched_upstreams=()
  unparseable_upstreams=()
  # `"${arr[@]:-}"` on an EMPTY array expands to ONE empty word, not zero, so
  # counting inside such a loop would report one examined identity for a
  # submodule that had none — the exact miscount this floor exists to prevent.
  # The length is therefore tested first.
  if [[ "${#own_org_repo_names[@]}" -gt 0 ]]; then
    for _n in "${own_org_repo_names[@]}"; do
      identities_examined=$((identities_examined + 1))
      if [[ -z "$_n" ]]; then
        unparseable_upstreams+=("(a URL with no readable repository name)")
        continue
      fi
      if _is_governance_denied "$_n"; then
        mismatched_upstreams+=("$_n")
      fi
    done
  fi
  # LOCAL_PATH remotes carry no repository identity, so a submodule reached
  # only through filesystem paths legitimately yields zero names. That case is
  # gated by --allow-local-path-remotes, which is a test-mode opt-in; the
  # floor therefore fires only when this run is NOT in that mode, where zero
  # names means the identity question was never actually asked.
  if [[ "$identities_examined" -eq 0 && "$local_path_identities" -eq 0 ]]; then
    echo "  == governance: NOT advancing '${sub_path}'. Its path's final component carries a standing operator authorization, but the identity check read ZERO upstream repository names AND zero identity-less remotes — so WHICH repository that authorization would be spent on was never established, and the scan that certified the remote set a moment ago disagrees. A gate that passes having examined nothing is not a gate. Refusing is the only honest outcome: silence is not consent." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "REFUSED_GOVERNANCE_DENY"
    continue
  fi
  # The remaining zero-identity case: every remote is a filesystem path, which
  # is reachable ONLY under --allow-local-path-remotes. The authorization then
  # rests on the directory label alone, exactly what the paragraph above says
  # it must not rest on. It is not refused, because a hermetic fixture has no
  # other remote shape and a suite that cannot reach the fetch path cannot
  # prove the fetch path is safe — but it is STATED, every time, so that a run
  # certified on a directory name is never silently indistinguishable from one
  # certified on a repository identity.
  if [[ "$identities_examined" -eq 0 ]]; then
    echo "  == governance: '${sub_path}' was certified on its PATH NAME ALONE — all ${local_path_identities} of its remotes are filesystem paths carrying no repository identity, so the identity half of the check examined nothing. This is the --allow-local-path-remotes test-mode surface; it is not a production certification."
  fi
  if [[ "${#unparseable_upstreams[@]}" -gt 0 ]]; then
    echo "  == governance: NOT advancing '${sub_path}'. ${#unparseable_upstreams[@]} of its own-org upstream(s) have no readable repository name (e.g. 'git@github.com:vasic-digital/.git'), so whether they name the authorized repository cannot be decided. An unreadable identity is not a matching one. Refusing is the only honest outcome: silence is not consent." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "REFUSED_GOVERNANCE_DENY"
    continue
  fi
  if [[ "${#mismatched_upstreams[@]}" -gt 0 ]]; then
    echo "  == governance: NOT advancing '${sub_path}'. Its path's final component carries a standing operator authorization, but its upstream(s) name ${#mismatched_upstreams[@]} repository/repositories that do not: ${mismatched_upstreams[*]}. An authorization is for a REPOSITORY, not for a directory label, and a submodule may be moved or renamed into an authorized-looking path without any operator having ruled on what it actually points at. Refusing is the only honest outcome: silence is not consent." >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "REFUSED_GOVERNANCE_DENY"
    continue
  fi

  # --- R-005 step 1: fetch the submodule's own upstream(s) ----------------
  # `--all` fetches EVERY configured remote and exits non-zero if any one of
  # them fails, so a partial failure is caught here rather than passing for a
  # clean fetch. git's own diagnostic is kept and printed: it names which
  # remote failed and why, which the old message could not — it always named
  # the preferred remote even when a different mirror was the one that failed.
  if ! fetch_err="$(git -C "$sub_dir" fetch --all --quiet 2>&1)"; then
    echo "  !! 'git fetch --all' failed (at least one remote is unreachable or rejected the fetch) — skipping, and this run is a failure. git said:" >&2
    printf '%s\n' "$fetch_err" | sed 's/^/     /' >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi

  # --- R-005 step 2: compare the pin to the remote default branch's HEAD --
  # The remote's own HEAD symref is the source of truth for "default branch"
  # — never a hardcoded branch name (§6.R), since the submodules in this
  # project do not all use the same default branch name.
  symref_out="$(git -C "$sub_dir" ls-remote --symref "$remote" HEAD 2>/dev/null)"
  default_branch="$(awk '/^ref:/ { sub(/^refs\/heads\//, "", $2); print $2; exit }' <<< "$symref_out")"
  new_commit="$(awk '$2 == "HEAD" { print $1; exit }' <<< "$symref_out")"

  if [[ -z "$default_branch" || -z "$new_commit" ]]; then
    echo "  !! could not resolve remote '${remote}''s default branch or HEAD — skipping, and this run is a failure" >&2
    refuse_and_record "$sub_path" "$old_commit" "$old_commit" "FAILED_PRECONDITION"
    continue
  fi

  # --- R-005 step 3: identical -> no-op ----------------------------------
  if [[ "$new_commit" == "$old_commit" ]]; then
    echo "  already at ${remote}/${default_branch} HEAD (${old_commit}) — no newer commit, nothing to do"
    write_advance_record "$sub_path" "$old_commit" "$new_commit" false "NO_NEWER_COMMIT" >/dev/null \
      || { echo "  !! failed to write the Submodule Advance Record" >&2; FAILURES=$((FAILURES + 1)); }
    NOOP_COUNT=$((NOOP_COUNT + 1))
    continue
  fi

  echo "  ${old_commit} -> ${new_commit} (${remote}/${default_branch})"

  # Prove the fetch actually landed the object before trying to check it out.
  if ! gith -C "$sub_dir" cat-file -e "${new_commit}^{commit}" 2>/dev/null; then
    echo "  !! ${new_commit} is not present locally after fetch — the fetch reported success but the object the remote HEAD names is not here, so nothing can be checked out or verified. REFUSING." >&2
    # Was REJECTED_BREAKING_CHANGE written with `|| true`: it asserted a
    # breaking change nobody observed (nothing was ever built), and it was the
    # one record-write failure this script swallowed silently.
    refuse_and_record "$sub_path" "$old_commit" "$new_commit" "FAILED_PRECONDITION"
    continue
  fi

  # A remote HEAD that DIFFERS from the pin is not necessarily AHEAD of it.
  # This project pins submodules to side branches deliberately (root CLAUDE.md
  # records Containers pinned on `lava-pin/2026-05-07-pkg-vm`), and an upstream
  # can also revert or rewrite its default branch. Moving the pin to a commit
  # that does not contain the pinned commit DROPS the pinned work while
  # recording "ADVANCED" — the record would assert an advance that did not
  # happen. Checked BEFORE anything is checked out.
  #
  # `$old_commit` here is the PARENT INDEX GITLINK, not the submodule's HEAD.
  # That distinction is the whole point of this guard: read from HEAD, a
  # submodule left checked out at any ancestor made the ancestry test pass and
  # the deliberately-pinned side-branch commit was silently dropped.
  # Read through `gith`. `merge-base --is-ancestor` answers from the object
  # graph, so `refs/replace/*` and a hand-authored commit-graph both change its
  # answer — captured at the round-4 review: a graft-style replace ref made an
  # UNRELATED root report as an ancestor of the remote HEAD, inverting the one
  # guard whose whole purpose is to stop deliberately-pinned side-branch work
  # being dropped.
  if ! gith -C "$sub_dir" merge-base --is-ancestor "$old_commit" "$new_commit" 2>/dev/null; then
    echo "  !! ${remote}/${default_branch} HEAD (${new_commit}) is not a fast-forward from the pinned commit (${old_commit}): the pin does not lag that branch, it points at something the branch does not contain. Moving it would DROP the pinned commit. REFUSING — nothing was checked out, the pin is untouched." >&2
    refuse_and_record "$sub_path" "$old_commit" "$new_commit" "REFUSED_NOT_FAST_FORWARD"
    continue
  fi

  # --- R-005 step 4: check the new commit out ----------------------------
  if ! git -C "$sub_dir" checkout --detach --quiet "$new_commit" 2>/dev/null; then
    echo "  !! could not check out ${new_commit} (local changes in the way?) — advance discarded" >&2
    restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
    write_advance_record "$sub_path" "$old_commit" "$new_commit" false "REJECTED_BREAKING_CHANGE" >/dev/null \
      || note_record_failure "$sub_path"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # --- R-005 step 5: rebuild + re-run the affected test categories -------
  echo "  verifying the advanced state..."
  # The exit status is captured and COMPARED. A verify command that was killed
  # (OOM killer, `timeout -s KILL`, operator interrupt) exits >= 128 and has
  # established nothing whatsoever about the advanced state — it is not
  # evidence of a breaking change. The record schema has no outcome that says
  # so (a T054 review item), so the distinction is at least stated in the run
  # log rather than being flattened into a clean-looking verdict.
  VERIFY_RC=0
  # $3 = the run id, passed as DATA in argv. It is never interpolated into
  # $VERIFY_CMD: a run id containing a single quote used to close the quoting
  # in the default command string and have the remainder parsed as shell, so
  # an injected `exit 0` made this gate report a verification it never ran.
  bash -c "$VERIFY_CMD" "lava-advance-verify" "$sub_dir" "$REPO_ROOT" "$RUN_ID" || VERIFY_RC=$?
  if [[ "$VERIFY_RC" -ne 0 ]]; then
    verify_note="exit status ${VERIFY_RC}"
    if [[ "$VERIFY_RC" -ge 128 ]]; then
      verify_note="${verify_note} — KILLED by signal $((VERIFY_RC - 128)); a timeout or an OOM kill is not evidence of a breaking change, nothing was learned about the advanced state"
    fi
    echo "  !! rebuild-and-test did not pass against ${new_commit} (${verify_note}) — advance discarded, prior pin ${old_commit} restored" >&2
    restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
    write_advance_record "$sub_path" "$old_commit" "$new_commit" false "REJECTED_BREAKING_CHANGE" >/dev/null \
      || note_record_failure "$sub_path"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # --- R-005 step 6: REMOVED ---------------------------------------------
  #
  # There is deliberately no code here. R-005 step 6 -- committing the
  # submodule's own pre-existing uncommitted work and pushing it to that
  # submodule's upstream default branch -- was removed on 2026-08-26, along
  # with the `--publish-local-modifications` flag that was its only entry.
  #
  # 607 lines of staging, fingerprint comparison, tree comparison, index and
  # HEAD checks, commit construction, five publish guards (PARENTAGE, TREE,
  # OBJECTS, HISTORY, MESSAGE), push-time destination re-certification,
  # per-mirror push, mirror-divergence reporting and rescue-ref preservation
  # stood here. Every one of the TWELVE fixture-proven escapes the five T054
  # review rounds found lived inside them, the count rose at every round
  # (3 -> 5 -> 7 -> 9 -> 10 -> 11 -> 12), and the eleventh -- 4000 bytes
  # carried in the commit object's own author-name header -- passed all five
  # guards green and reached both mirrors. Guarding had provably stopped
  # converging, so the capability was removed rather than guarded again.
  #
  # A submodule that reaches this point has a CLEAN working tree, proven
  # above, because an unclean one was refused before the fetch. So there is
  # nothing local to carry forward, by construction, and the run proceeds
  # straight to step 7.
  #
  # `final_commit` still exists and still means "the commit this run measured
  # and will pin". It is now always exactly the fetched upstream commit, and
  # step 7's HEAD read-back below is what proves that is still what HEAD says
  # after the step-5 verify command has run.
  final_commit="$new_commit"

  # --- R-005 step 7: record the new pin in the PARENT repository ---------
  #
  # `git add -- <submodule-path>` records the submodule's CURRENT HEAD, not
  # `$final_commit`. That is the third head of the tenth escape: a hook that
  # moved HEAD after the guards ran had the parent pin staged at a commit the
  # Submodule Advance Record does not name, while the record and the console
  # both reported the honest one. A record that misstates what the run did is
  # the bluff class this file polices everywhere else, and here it also pins
  # every other clone of this repository to an unexamined commit.
  #
  # Checked BEFORE staging, so a mismatch stages nothing.
  head_at_pin_time="$(gith -C "$sub_dir" rev-parse HEAD 2>/dev/null)" || head_at_pin_time=""
  if [[ -z "$head_at_pin_time" || "$head_at_pin_time" != "$final_commit" ]]; then
    echo "  !! '${sub_path}''s HEAD reads '${head_at_pin_time:-<unreadable>}' but this run measured ${final_commit}. Something moved HEAD after the step-5 verify command was invoked. Staging the parent pin now would record a commit no guard in this run examined, under a record naming a different one. REFUSING: nothing was staged in the parent." >&2
    restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
    write_advance_record "$sub_path" "$old_commit" "$final_commit" false "REJECTED_PARENT_STAGING_FAILED" >/dev/null \
      || note_record_failure "$sub_path"
    FAILURES=$((FAILURES + 1))
    continue
  fi
  if ! stage_err="$(git -C "$REPO_ROOT" add -- "$sub_path" 2>&1)"; then
    echo "  !! could not stage the new pin in the parent repository — advance discarded. git said: ${stage_err//$'\n'/ ; }" >&2
    # No rescue ref is created or needed here. This script creates no commit,
    # so there is never local work of its own making that a restore could
    # strand; and a submodule that carried the OPERATOR's uncommitted work was
    # refused before the fetch, so its work was never moved in the first place.
    # Recorded as REJECTED_PARENT_STAGING_FAILED. It used to be recorded as
    # REJECTED_BREAKING_CHANGE with a printed apology for being "the closest
    # value the enum offers" — a record that names a cause nobody observed is
    # a bluff whatever the console says alongside it, so the enum gained the
    # value instead.
    restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
    if ! write_advance_record "$sub_path" "$old_commit" "$final_commit" false "REJECTED_PARENT_STAGING_FAILED" >/dev/null; then
      note_record_failure "$sub_path"
    fi
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # ...and the staged entry is READ BACK, because the check above measured a
  # ref and this reads the artefact. `git ls-files -s` prints
  # `<mode> <object> <stage>\t<path>` and the object for a gitlink IS the pin.
  # A gate that asserts what it asked for, and never what it got, is the shape
  # this whole file was rewritten to stop.
  staged_pin="$(git -C "$REPO_ROOT" ls-files -s -- "$sub_path" 2>/dev/null | awk 'NR==1 { print $2 }')" || staged_pin=""
  if [[ "$staged_pin" != "$final_commit" ]]; then
    echo "  !! the parent index now pins '${sub_path}' at '${staged_pin:-<unreadable>}', not at the ${final_commit} this run measured and recorded. NOTHING here can be reported as a completed advance. The parent is NOT committed by this script, so the staged entry is recoverable by hand:  git -C '${REPO_ROOT}' restore --staged -- '${sub_path}'" >&2
    write_advance_record "$sub_path" "$old_commit" "$final_commit" false "REJECTED_PARENT_STAGING_FAILED" >/dev/null \
      || note_record_failure "$sub_path"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  echo "  ADVANCED to ${final_commit}; parent pin staged"
  # ADVANCED_COUNT moves only when the record really landed. It used to be
  # incremented unconditionally right after note_record_failure, so a single
  # submodule was reported as both "1 advanced" and "1 rejected/failed".
  if write_advance_record "$sub_path" "$old_commit" "$final_commit" true "ADVANCED" >/dev/null; then
    ADVANCED_COUNT=$((ADVANCED_COUNT + 1))
  else
    note_record_failure "$sub_path"
    FAILURES=$((FAILURES + 1))
  fi
done

echo "---"
# The examined count is its own line, and it comes first, because
# "0 advanced, 0 already current, 0 rejected/failed" is the SAME text for
# "every submodule was already current" and for "no submodule was ever looked
# at". A gate that reports success having examined nothing is the vacuous-pass
# shape this project has recorded around fifty times.
echo "advance-all-submodules: ${EXAMINED_COUNT} submodule(s) examined"
echo "advance-all-submodules: ${ADVANCED_COUNT} advanced, ${NOOP_COUNT} already current, ${GOVERNANCE_REFUSED} refused for want of a standing operator authorization, ${FAILURES} rejected/failed, ${UNRESTORED} not restored to the prior pin"
if [[ "$UNRESTORED" -gt 0 ]]; then
  echo "advance-all-submodules: THESE SUBMODULES ARE LEFT ON A REJECTED COMMIT and the parent tree is dirty for them — inspect and restore by hand:" >&2
  for u in "${UNRESTORED_PATHS[@]}"; do
    echo "  - ${u}" >&2
  done
fi
echo "Submodule Advance Records: ${RECORD_DIR}"

# Belt and braces for the allow-list validation above: this repository HAS
# submodules (the enumeration guard proved that) and every allow-list token
# matched one, so at least one must have been examined. Asserted rather than
# assumed, because the cost of being wrong is a green verdict over an empty
# set.
if [[ "$EXAMINED_COUNT" -eq 0 ]]; then
  echo "advance-all-submodules: internal error — this repository has ${#SUBMODULE_PATHS[@]} submodule(s) but NONE was examined. Refusing to report an outcome for a set that was never looked at." >&2
  exit 2
fi

# ...and "N examined" is only half a statement if the outcome line accounts for
# fewer than N. A submodule that falls through every bucket is invisible in the
# summary exactly the way an unexamined one is, and the reader of the summary
# cannot tell the two apart — which is the same defect as the corpus floor
# above, one level down. FAILURES may exceed one per submodule (several paths
# count more than one), so this is a floor, not an equality.
_accounted=$(( ADVANCED_COUNT + NOOP_COUNT + GOVERNANCE_REFUSED + FAILURES ))
if [[ "$EXAMINED_COUNT" -gt "$_accounted" ]]; then
  echo "advance-all-submodules: internal error — ${EXAMINED_COUNT} submodule(s) were examined but the outcome line accounts for only ${_accounted} (${ADVANCED_COUNT} advanced + ${NOOP_COUNT} already current + ${GOVERNANCE_REFUSED} governance-refused + ${FAILURES} rejected/failed). A submodule that lands in no bucket is indistinguishable in this summary from one that was never looked at. Refusing to present a partial account as a whole one." >&2
  exit 2
fi

if [[ "$FAILURES" -gt 0 ]]; then
  exit 1
fi
exit 0
