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
#   6. if the submodule itself carries local, uncommitted modifications,
#      commit and push those to its own upstream(s) (§6.W two-mirror scope)
#   7. `git add <submodule-path>` in the PARENT repository to record the pin
#
# Writes one Submodule Advance Record per submodule, conforming to
# specs/002-build-test-distribute-pipeline/contracts/submodule-advance-record.schema.json
# and data-model.md's "Submodule Advance Record" entity.
#
# Usage:
#   scripts/advance-all-submodules.sh [parent-repo-path]
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
#       submodule via `bash -c`, with $1 = the submodule's absolute path and
#       $2 = the parent repository root. A zero exit means "the advanced
#       state still builds and passes"; non-zero means breaking change.
#       Default:
#         scripts/pipeline/phase-01-build.sh <run_id> <repo> &&
#         scripts/pipeline/phase-02-test.sh  <run_id> <repo>
#
#   LAVA_ADVANCE_SUBMODULES
#       Optional whitespace-separated allow-list of submodule paths (as they
#       appear in `git submodule status`). Default: every submodule.
#
# Not every failure can be expressed as a Submodule Advance Record: the
# record schema's `outcome` enum has five values, and several conditions this
# script must refuse on (a pin move that is not a fast-forward, an
# uninitialized submodule, an unreachable upstream, a remote whose default
# branch cannot be resolved, a parent index that cannot be staged) match none
# of them. Rather than record one of the five and assert a cause that did not
# occur, those refusals are reported on stderr, counted in the run summary and
# reflected in the exit code, and left WITHOUT a record. Closing that gap needs
# a schema change, which is a T054 review item — see
# docs/scripts/advance-all-submodules.sh.md.
#
# Exit codes:
#   0 - every submodule ended ADVANCED or NO_NEWER_COMMIT.
#   1 - at least one submodule was REJECTED_BREAKING_CHANGE /
#       REJECTED_PUSH_CONFLICT, or could not be processed at all.
#   2 - usage / configuration error (bad repo path, missing run id with no
#       overrides, unwritable record directory). Nothing was attempted.
#
# Safety properties this script guarantees, and why each exists:
#
#   * Every git push it issues is a plain fast-forward push. It never passes
#     any history-overwriting or hook-bypassing flag to git (§6.T.3 +
#     FR-016). A push that cannot fast-forward is a REJECTED_PUSH_CONFLICT,
#     full stop — the refusal IS the correct outcome, never something to
#     work around.
#   * It NEVER commits in the parent repository. Step 7 stages the pin with
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
#   * It NEVER destroys local work. If it had to commit local modifications
#     before a push that then failed, the resulting commit is preserved under
#     refs/lava-advance-rescue/<id> in the submodule before HEAD is moved
#     back, so the work stays reachable (not reflog-only).
#   * No privilege escalation anywhere — plain git as the calling user (§6.U).
#
# Classification: project-specific (the record schema and evidence layout are
# this pipeline's; the fetch/compare/verify/discard ordering is general).

set -uo pipefail

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

REPO_PATH="${1:-}"
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
  VERIFY_CMD='"$2"/scripts/pipeline/phase-01-build.sh '"'${RUN_ID}'"' "$2" && "$2"/scripts/pipeline/phase-02-test.sh '"'${RUN_ID}'"' "$2"'
fi

if ! mkdir -p -- "$RECORD_DIR"; then
  echo "advance-all-submodules: could not create record directory '$RECORD_DIR'" >&2
  exit 2
fi

ALLOW_LIST="${LAVA_ADVANCE_SUBMODULES:-}"

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
  [[ -n "$ALLOW_LIST" ]] || return 0
  for tok in $ALLOW_LIST; do
    [[ "$tok" == "$needle" ]] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Governance deny-list (default-deny, NOT overridable by the allow-list)
# ---------------------------------------------------------------------------
# Some submodules' pins are governed by a separate, human-driven process and
# must never be advanced by an unattended pipeline run, no matter how green
# the rebuild-and-test step is.
#
# `constitution` is the canonical case. Root CLAUDE.md line 299 states:
# "The ./constitution/ submodule itself remains pinned + advanced only per
# CONST-049's 7-step pipeline." That submodule holds the document set defining
# what this pipeline is permitted to do at all, so letting the pipeline advance
# it would let the pipeline rewrite its own rules as a side effect of a routine
# closure phase.
#
# Added 2026-08-21, BEFORE this script had ever been run against a real
# submodule: until then it defaulted to every submodule and contained no
# mention of `constitution` whatsoever. Demonstrated by
# tests/pipeline/test_advance_all_submodules.sh case 5, whose pre-fix run
# recorded outcome=ADVANCED with parent_pin_updated=true against a fixture
# submodule named `constitution`.
#
# Deliberately NOT overridable via LAVA_ADVANCE_SUBMODULES: an allow-list is a
# convenience for narrowing a run, and a convenience switch must not be able to
# turn off a governance boundary. Matching is on the path's final component, so
# both `constitution` and `submodules/constitution` are covered.
GOVERNANCE_DENY=(
  "constitution"
)

# _is_governance_denied <submodule-path> — exit 0 if denied.
_is_governance_denied() {
  local candidate="${1##*/}" denied
  for denied in "${GOVERNANCE_DENY[@]}"; do
    [[ "$candidate" == "$denied" ]] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Record writing
# ---------------------------------------------------------------------------

# write_advance_record <submodule_name> <old_commit> <new_commit> \
#                      <local_modifications_pushed> <parent_pin_updated> <outcome>
#
# Builds the JSON with `jq -n` (or a python3 json.dump fallback) — never by
# interpolating into a JSON string literal — writes through a temp file, and
# re-parses the bytes on disk before reporting success. Same anti-bluff
# discipline as scripts/pipeline/lib/evidence.sh, for the same reason: never
# trust that a writer produced valid JSON just because it did not complain.
write_advance_record() {
  local name="$1" old="$2" new="$3" pushed="$4" pin_updated="$5" outcome="$6"
  local out_path tmp_path
  out_path="${RECORD_DIR}/$(_advance_sanitize_name "$name").json"
  tmp_path="${out_path}.tmp.$$"

  case "$outcome" in
    ADVANCED|NO_NEWER_COMMIT|REJECTED_BREAKING_CHANGE|REJECTED_PUSH_CONFLICT|REFUSED_GOVERNANCE_DENY) ;;
    *)
      echo "advance-all-submodules: internal error — invalid outcome '$outcome'" >&2
      return 1
      ;;
  esac

  # Schema invariant (submodule-advance-record.schema.json's allOf/if-then):
  # a rejection MUST NOT claim the parent pin was updated. Enforced here so
  # a future edit to the control flow cannot silently emit a record that
  # says "rejected" and "pin updated" at the same time.
  if [[ "$outcome" == REJECTED_* && "$pin_updated" != "false" ]]; then
    echo "advance-all-submodules: internal error — outcome '$outcome' with parent_pin_updated='$pin_updated' violates the record schema" >&2
    return 1
  fi

  if command -v jq >/dev/null 2>&1; then
    jq -n \
      --arg submodule_name "$name" \
      --arg old_commit "$old" \
      --arg new_commit "$new" \
      --argjson local_modifications_pushed "$pushed" \
      --argjson parent_pin_updated "$pin_updated" \
      --arg outcome "$outcome" \
      '{
        submodule_name: $submodule_name,
        old_commit: $old_commit,
        new_commit: $new_commit,
        local_modifications_pushed: $local_modifications_pushed,
        parent_pin_updated: $parent_pin_updated,
        outcome: $outcome
      }' > "$tmp_path" || { rm -rf -- "$tmp_path"; return 1; }
  else
    python3 - "$tmp_path" "$name" "$old" "$new" "$pushed" "$pin_updated" "$outcome" <<'PYEOF' || { rm -rf -- "$tmp_path"; return 1; }
import json
import sys

tmp_path, name, old, new, pushed, pin_updated, outcome = sys.argv[1:8]
record = {
    "submodule_name": name,
    "old_commit": old,
    "new_commit": new,
    "local_modifications_pushed": pushed == "true",
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

# save_rescue_ref <submodule-dir> <commit-sha> — keeps <commit-sha> reachable
# under refs/lava-advance-rescue/<id> so that moving HEAD away from it can
# never lose the operator's work. Echoes the ref name on success.
save_rescue_ref() {
  local sub="$1" sha="$2" ref
  ref="refs/lava-advance-rescue/$(date -u +%Y%m%dT%H%M%SZ)-$$"
  if git -C "$sub" update-ref "$ref" "$sha" 2>/dev/null; then
    echo "$ref"
    return 0
  fi
  return 1
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

# remote_default_branch <submodule-dir> <remote> — that remote's OWN default
# branch, read from its HEAD symref. Resolved PER REMOTE, never once and then
# reused: two §6.W mirrors of the same repository can disagree about the
# default branch name, and pushing the preferred remote's branch name at a
# mirror that uses a different one creates a stray branch there while the
# mirror's real default receives nothing.
remote_default_branch() {
  git -C "$1" ls-remote --symref "$2" HEAD 2>/dev/null \
    | awk '/^ref:/ { sub(/^refs\/heads\//, "", $2); print $2; exit }'
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
SUBMODULE_STATUS_RC=0
SUBMODULE_STATUS_RAW="$(git -C "$REPO_ROOT" submodule status 2>&1)" || SUBMODULE_STATUS_RC=$?
if [[ "$SUBMODULE_STATUS_RC" -ne 0 ]]; then
  echo "advance-all-submodules: could not enumerate submodules in '$REPO_ROOT' — 'git submodule status' exited ${SUBMODULE_STATUS_RC}. Nothing was attempted. git said:" >&2
  printf '%s\n' "$SUBMODULE_STATUS_RAW" | sed 's/^/  /' >&2
  exit 2
fi

mapfile -t SUBMODULE_PATHS < <(
  printf '%s\n' "$SUBMODULE_STATUS_RAW" \
    | sed -E 's/^[[:space:]]*[-+U]?//' \
    | awk 'NF >= 2 { print $2 }'
)

if [[ "${#SUBMODULE_PATHS[@]}" -eq 0 ]]; then
  echo "advance-all-submodules: '$REPO_ROOT' has no submodules — nothing to do"
  exit 0
fi

FAILURES=0
ADVANCED_COUNT=0
NOOP_COUNT=0
UNRESTORED=0
UNRESTORED_PATHS=()

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

for sub_path in "${SUBMODULE_PATHS[@]}"; do
  if ! _is_allowed "$sub_path"; then
    continue
  fi

  sub_dir="${REPO_ROOT}/${sub_path}"
  echo "==> ${sub_path}"

  # Governance deny-list is checked FIRST — before fetch, before comparing
  # commits, before anything. A denied submodule is not "considered and
  # rejected", it is never examined at all. Its current pin is recorded as
  # both old_commit and new_commit because no candidate commit was ever looked
  # up; claiming otherwise would be inventing a fact this script did not
  # establish.
  if _is_governance_denied "$sub_path"; then
    # The pin is read from the PARENT's index, not from the submodule's HEAD:
    # the index gitlink IS the pin, it is always a valid sha, and it is
    # readable even when the submodule is not initialized. Reading HEAD inside
    # an uninitialized submodule directory returns the PARENT repository's
    # HEAD, which would put a real-looking but entirely wrong sha in the
    # record; the previous `|| echo "unknown"` fallback wrote a value the
    # record schema's ^[0-9a-f]{7,40}$ pattern rejects outright.
    denied_pin="$(git -C "$REPO_ROOT" ls-files -s -- "$sub_path" 2>/dev/null | awk '{print $2; exit}')"
    echo "  == governance deny-list: NOT advancing. This submodule's pin is governed by a separate human-driven process (see root CLAUDE.md's CONST-049 note), not by this pipeline."
    if [[ -z "$denied_pin" ]]; then
      echo "  !! could not read '${sub_path}''s pin from the parent index — refusing to invent one for the record. This run is a failure." >&2
      FAILURES=$((FAILURES + 1))
      continue
    fi
    # NOT `|| true`: every other record-write failure is already covered by the
    # failure this loop iteration counts for its own reason, but a governance
    # refusal has no such backstop — swallowing it produced exit 0 with zero
    # evidence that the refusal ever happened.
    if ! write_advance_record "$sub_path" "$denied_pin" "$denied_pin" false false "REFUSED_GOVERNANCE_DENY" >/dev/null; then
      note_record_failure "$sub_path"
      FAILURES=$((FAILURES + 1))
    fi
    continue
  fi

  if ! is_own_repo_root "$sub_dir"; then
    echo "  !! '${sub_path}' is not the root of its own git work tree: an uninitialized submodule (run 'git submodule update --init'), a leftover directory, or a symlink pointing out of the tree. NOTHING was attempted for it — git's upward .git discovery would otherwise make every git command below operate on the PARENT repository. This run is a failure." >&2
    FAILURES=$((FAILURES + 1))
    continue
  fi

  old_commit="$(git -C "$sub_dir" rev-parse HEAD 2>/dev/null)"
  if [[ -z "$old_commit" ]]; then
    echo "  !! could not resolve HEAD — skipping, and this run is a failure" >&2
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # Captured BEFORE anything is touched: R-005 step 6's input condition.
  local_mods="$(git -C "$sub_dir" status --porcelain 2>/dev/null)"
  has_local_mods=false
  [[ -n "$local_mods" ]] && has_local_mods=true

  if ! remote="$(preferred_remote "$sub_dir")"; then
    echo "  !! no git remote configured — cannot advance, and this run is a failure" >&2
    FAILURES=$((FAILURES + 1))
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
    FAILURES=$((FAILURES + 1))
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
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # --- R-005 step 3: identical -> no-op ----------------------------------
  if [[ "$new_commit" == "$old_commit" ]]; then
    echo "  already at ${remote}/${default_branch} HEAD (${old_commit}) — no newer commit, nothing to do"
    write_advance_record "$sub_path" "$old_commit" "$new_commit" false false "NO_NEWER_COMMIT" >/dev/null \
      || { echo "  !! failed to write the Submodule Advance Record" >&2; FAILURES=$((FAILURES + 1)); }
    NOOP_COUNT=$((NOOP_COUNT + 1))
    continue
  fi

  echo "  ${old_commit} -> ${new_commit} (${remote}/${default_branch})"

  # Prove the fetch actually landed the object before trying to check it out.
  if ! git -C "$sub_dir" cat-file -e "${new_commit}^{commit}" 2>/dev/null; then
    echo "  !! ${new_commit} is not present locally after fetch — rejecting the advance" >&2
    write_advance_record "$sub_path" "$old_commit" "$new_commit" false false "REJECTED_BREAKING_CHANGE" >/dev/null || true
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # A remote HEAD that DIFFERS from the pin is not necessarily AHEAD of it.
  # This project pins submodules to side branches deliberately (root CLAUDE.md
  # records Containers pinned on `lava-pin/2026-05-07-pkg-vm`), and an upstream
  # can also revert or rewrite its default branch. Moving the pin to a commit
  # that does not contain the pinned commit DROPS the pinned work while
  # recording "ADVANCED" — the record would assert an advance that did not
  # happen. Checked BEFORE anything is checked out.
  if ! git -C "$sub_dir" merge-base --is-ancestor "$old_commit" "$new_commit" 2>/dev/null; then
    echo "  !! ${remote}/${default_branch} HEAD (${new_commit}) is not a fast-forward from the pinned commit (${old_commit}): the pin does not lag that branch, it points at something the branch does not contain. Moving it would DROP the pinned commit. REFUSING — nothing was checked out, the pin is untouched." >&2
    echo "     No Submodule Advance Record is written for this refusal: the record schema's outcome enum has no value that describes it, and reusing REJECTED_BREAKING_CHANGE or REFUSED_GOVERNANCE_DENY would assert a cause that did not occur. See the T054 review note in docs/scripts/advance-all-submodules.sh.md." >&2
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # --- R-005 step 4: check the new commit out ----------------------------
  if ! git -C "$sub_dir" checkout --detach --quiet "$new_commit" 2>/dev/null; then
    echo "  !! could not check out ${new_commit} (local changes in the way?) — advance discarded" >&2
    restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
    write_advance_record "$sub_path" "$old_commit" "$new_commit" false false "REJECTED_BREAKING_CHANGE" >/dev/null \
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
  bash -c "$VERIFY_CMD" "lava-advance-verify" "$sub_dir" "$REPO_ROOT" || VERIFY_RC=$?
  if [[ "$VERIFY_RC" -ne 0 ]]; then
    verify_note="exit status ${VERIFY_RC}"
    if [[ "$VERIFY_RC" -ge 128 ]]; then
      verify_note="${verify_note} — KILLED by signal $((VERIFY_RC - 128)); a timeout or an OOM kill is not evidence of a breaking change, nothing was learned about the advanced state"
    fi
    echo "  !! rebuild-and-test did not pass against ${new_commit} (${verify_note}) — advance discarded, prior pin ${old_commit} restored" >&2
    restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
    write_advance_record "$sub_path" "$old_commit" "$new_commit" false false "REJECTED_BREAKING_CHANGE" >/dev/null \
      || note_record_failure "$sub_path"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  # --- R-005 step 6: commit + push the submodule's own local changes -----
  #
  # `local_modifications_pushed` in the record is derived from what actually
  # happened, never from having entered this branch. Two states are tracked
  # separately, because they are separately observable on the upstream:
  #   committed  — a commit was really created (the step-4 checkout can leave
  #                `git add -A` with nothing to stage: e.g. the new upstream
  #                commit adds a .gitignore rule covering the operator's
  #                untracked file. Reporting "pushed" there described a commit
  #                that was never made and a push that never happened.)
  #   pushed_any — at least one remote really accepted that commit. Once one
  #                mirror has it the work IS published and cannot be taken
  #                back (force-push is forbidden), so reporting "false"
  #                because a LATER mirror refused states the opposite of the
  #                upstream's observable state.
  final_commit="$new_commit"
  committed=false
  pushed_any=false
  push_failed=false
  push_failure_detail=""

  if [[ "$has_local_mods" == "true" ]]; then
    echo "  submodule carried local modifications before the advance — committing and pushing them to its own upstream(s)"
    if ! git -C "$sub_dir" add -A >/dev/null 2>&1; then
      echo "  !! 'git add -A' failed in '${sub_path}' — nothing was staged, so nothing is claimed to have been carried forward" >&2
    fi
    if ! git -C "$sub_dir" diff --cached --quiet 2>/dev/null; then
      if ! git -C "$sub_dir" commit --quiet -m "chore: local modifications carried forward by advance-all-submodules" 2>/dev/null; then
        echo "  !! could not commit local modifications — advance discarded" >&2
        restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
        write_advance_record "$sub_path" "$old_commit" "$new_commit" false false "REJECTED_PUSH_CONFLICT" >/dev/null \
          || note_record_failure "$sub_path"
        FAILURES=$((FAILURES + 1))
        continue
      fi
      if ! final_commit="$(git -C "$sub_dir" rev-parse HEAD 2>/dev/null)" || [[ -z "$final_commit" ]]; then
        echo "  !! committed the local modifications but could not read the resulting commit — advance discarded" >&2
        restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
        write_advance_record "$sub_path" "$old_commit" "$new_commit" false false "REJECTED_PUSH_CONFLICT" >/dev/null \
          || note_record_failure "$sub_path"
        FAILURES=$((FAILURES + 1))
        continue
      fi
      committed=true
    else
      echo "  ...after the step-4 checkout there is nothing left to commit; no local-modification commit was created and nothing will be pushed"
    fi

    # Push to EVERY configured remote (§6.W: GitHub + GitLab). Plain
    # fast-forward pushes only — a rejection here is honoured as a refusal
    # (FR-016), never retried with an overwriting flag. Skipped entirely when
    # no commit was created: there is nothing to publish.
    if [[ "$committed" == "true" ]]; then
      while IFS= read -r r; do
        [[ -n "$r" ]] || continue
        r_branch="$(remote_default_branch "$sub_dir" "$r")"
        if [[ -z "$r_branch" ]]; then
          push_failed=true
          push_failure_detail="could not resolve remote '${r}''s own default branch — refusing to guess which branch to push onto"
          break
        fi
        if push_err="$(git -C "$sub_dir" push --quiet "$r" "HEAD:refs/heads/${r_branch}" 2>&1)"; then
          pushed_any=true
        else
          push_failed=true
          push_failure_detail="push to remote '${r}' (${r_branch}) was refused by git: ${push_err//$'\n'/ ; }"
          break
        fi
      done < <(git -C "$sub_dir" remote)
    fi

    if [[ "$push_failed" == "true" ]]; then
      # The cause is git's own message, quoted verbatim. The previous text
      # asserted "cannot fast-forward" for every failure, which is a cause the
      # tool's own output routinely contradicts (branch protection, a
      # server-side hook, an auth failure, an unreachable host).
      echo "  !! ${push_failure_detail} — refusing to retry with any history-overwriting flag (§6.T.3 / FR-016)" >&2
      if [[ "$pushed_any" == "true" ]]; then
        echo "  !! MIRROR DIVERGENCE: ${final_commit} is already published on at least one remote and could not be published on the rest. Force-pushing is forbidden, so this must be reconciled by hand (§6.C / §6.W)." >&2
      fi
      if rescue_ref="$(save_rescue_ref "$sub_dir" "$final_commit")"; then
        echo "  local work preserved at ${rescue_ref} (${final_commit}) before restoring the prior pin" >&2
      else
        echo "  !! could not create a rescue ref for ${final_commit}; it remains reachable via the submodule's reflog" >&2
      fi
      restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
      write_advance_record "$sub_path" "$old_commit" "$new_commit" "$pushed_any" false "REJECTED_PUSH_CONFLICT" >/dev/null \
        || note_record_failure "$sub_path"
      FAILURES=$((FAILURES + 1))
      continue
    fi
  fi

  # --- R-005 step 7: record the new pin in the PARENT repository ---------
  if ! stage_err="$(git -C "$REPO_ROOT" add -- "$sub_path" 2>&1)"; then
    echo "  !! could not stage the new pin in the parent repository — advance discarded. git said: ${stage_err//$'\n'/ ; }" >&2
    if [[ "$committed" == "true" ]]; then
      # Reached only when step 6 created (and possibly published) a commit
      # that HEAD is about to be moved off. Without this the commit survives
      # locally in the reflog only, even though it may already be on a remote.
      if rescue_ref="$(save_rescue_ref "$sub_dir" "$final_commit")"; then
        echo "  local work preserved at ${rescue_ref} (${final_commit}) before restoring the prior pin" >&2
      else
        echo "  !! could not create a rescue ref for ${final_commit}; it remains reachable via the submodule's reflog" >&2
      fi
    fi
    echo "     Recorded as REJECTED_BREAKING_CHANGE because that is the closest value the record schema's outcome enum offers; the real cause was parent-index staging, not the advanced state. See the T054 review note in docs/scripts/advance-all-submodules.sh.md." >&2
    restore_or_count "$sub_dir" "$old_commit" "$sub_path" || true
    write_advance_record "$sub_path" "$old_commit" "$final_commit" "$pushed_any" false "REJECTED_BREAKING_CHANGE" >/dev/null \
      || note_record_failure "$sub_path"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  echo "  ADVANCED to ${final_commit}; parent pin staged"
  write_advance_record "$sub_path" "$old_commit" "$final_commit" "$pushed_any" true "ADVANCED" >/dev/null \
    || { note_record_failure "$sub_path"; FAILURES=$((FAILURES + 1)); }
  ADVANCED_COUNT=$((ADVANCED_COUNT + 1))
done

echo "---"
echo "advance-all-submodules: ${ADVANCED_COUNT} advanced, ${NOOP_COUNT} already current, ${FAILURES} rejected/failed, ${UNRESTORED} not restored to the prior pin"
if [[ "$UNRESTORED" -gt 0 ]]; then
  echo "advance-all-submodules: THESE SUBMODULES ARE LEFT ON A REJECTED COMMIT and the parent tree is dirty for them — inspect and restore by hand:" >&2
  for u in "${UNRESTORED_PATHS[@]}"; do
    echo "  - ${u}" >&2
  done
fi
echo "Submodule Advance Records: ${RECORD_DIR}"

if [[ "$FAILURES" -gt 0 ]]; then
  exit 1
fi
exit 0
