#!/usr/bin/env bash
# Static guard against the VACUOUS-PASS defect family in the pipeline's own
# scripts.
#
# During feature 002's implementation, thirty-two real defects were found and
# fixed. They were not thirty-two independent mistakes; they were a handful of
# SHAPES, each recurring wherever a new wrapper was written. The overwhelming
# majority were the same two:
#
#   (A) an exit code captured into a variable and then NEVER COMPARED.
#       Seen in: phase-02-test-go.sh (the real `go test` rc),
#       phase-02-test-kotlin.sh (GRADLE_EXIT_CODE), and
#       phase-02-test-stress-chaos.sh (RUN_RC). In every case the wrapper
#       reported PASS while the underlying tool had genuinely failed.
#
#   (B) a `jq` `//` fallback applied to a field whose meaningful value can be
#       FALSY. jq's `//` fires not only on null/missing but also on `false`,
#       so `jq -r '.all_passed // "unknown"'` turned a real `false` into
#       "unknown" — erasing the did-not-pass signal on exactly the runs where
#       it mattered. Seen in phase-02-test-constitutional-gate-sweep.sh, and
#       again in a different disguise in phase-02-test-stress-chaos.sh, where
#       `.ran` compared as a string to "true" also matched a MISSING key.
#
# A third shape appeared once and is worth guarding because it is silent and
# deeply counter-intuitive:
#
#   (C) `[[ -e "$ref" ]]` / `[[ -s "$ref" ]]` used where a REGULAR FILE is
#       required. Both tests are TRUE for a directory — a directory inode has
#       non-zero size — so anti-bluff-validate.sh's "the captured output
#       exists and is non-empty" rules both passed when raw_output_ref
#       resolved to the record's own directory.
#
# Three further shapes were added after a second sweep over the same defect
# set. Each had also reached production here at least once:
#
#   (D) a hardcoded SUCCESS LITERAL presented as a value read from somewhere.
#       phase-04-live-verify-api-app.sh's PASS summary claimed "the INDEPENDENT
#       Containers attestation row agrees (test_passed=true, ...)" with `true`
#       written into the format string — while no attestation row existed at
#       all. The prose credited a second source with a value the author had
#       supplied. Fixed to `test_passed=%s` fed from the row itself, which is
#       also what makes the shape detectable: literal versus interpolated.
#
#   (E) `${#VAR}` used where a BYTE count is claimed. `${#VAR}` counts
#       CHARACTERS. phase-05a-changelog-entry.sh reported
#       `${#SNAPSHOT_CONTENT}` as "N bytes of release notes" and was wrong by
#       seven bytes even on the happy path, from the non-ASCII in the notes
#       alone; on the already-present path it described text the script had
#       deliberately never written. Fixed to `wc -c` on the file that exists.
#
#   (F) `grep -q` on the RIGHT-HAND SIDE OF A PIPE in a file running under
#       pipefail. `grep -q` exits on first match; the left-hand side, still
#       writing, takes SIGPIPE and dies 141, and pipefail makes 141 the
#       pipeline's status — so a SUCCESSFUL MATCH is reported as a failed one,
#       decided by the SIZE of the data rather than by the pattern. This one
#       bit CHECK A of this very file during its own construction (see the note
#       there), and it is live in lib/anti-bluff-validate.sh today.
#
# These were each fixed individually. This suite exists so the SHAPE cannot
# come back the next time someone adds a wrapper, which is the difference
# between fixing thirty-two bugs and closing a defect class.
#
# SCOPE AND HONESTY ABOUT WHAT A STATIC SCAN CAN DO:
# this is a heuristic lint, not a proof. It can produce false positives, and it
# certainly cannot catch every vacuous pass. It is a cheap net for six shapes
# that have each already reached production in this codebase, not a substitute
# for the behavioural suites alongside it. When it flags something that is
# genuinely fine, add an explicit `# vacuous-pass-ok: <reason>` comment on the
# SAME line — an opt-out that has to be written down and justified, never a
# silent exemption.
#
# WHAT A GREEN RUN OF THIS FILE DOES NOT MEAN.
# A green run means six specific syntactic shapes are absent. It is not
# evidence that the pipeline's passes are earned. Known-uncovered, non-
# exhaustively:
#
#   * Recording an artifact that is not on disk. The phase-01 defect — an
#     Evidence Record naming a built APK that was never produced — has no
#     syntactic signature at all. Only a behavioural test catches it.
#
#   * A record asserting a CAUSE that the tool's own exit code contradicts:
#     a matrix script exiting 0 classified SKIPPED claiming "BLOCKED (real
#     precondition gap)", or 127/137 laundered into a SKIPPED asserting a
#     config cause. Deliberately NOT implemented. Deciding it needs the
#     enclosing branch, and the legitimate justifications are syntactically
#     identical to the illegitimate one: phase-02-test-release-canary.sh
#     justifies its SKIPPED from `[[ $exit_code -eq 2 ]]`, while
#     phase-02-test-stress-chaos.sh justifies its SKIPPED from the harness's
#     own `ran`/`status` fields and never looks at an exit code — correctly.
#     A scanner that demanded an exit-code comparison would flag the second;
#     one that accepted any enclosing conditional would flag neither and
#     nothing else either. Real control-flow analysis, not a line scanner.
#
#   * A success report emitted after a loop that iterated ZERO times. Also
#     deliberately NOT implemented. 17 of the 42 array loops in scope have no
#     `${#ARR[@]}` test anywhere in their file, and nearly all of them are
#     correct — `FAILURE_REASONS`, `failed_test_ids` and `rejected_records`
#     are SUPPOSED to be empty on a good run, and `PHASES` is a static literal
#     list. A check with that hit profile gets annotated into uselessness,
#     which is worse than not having it.
#
#   * `|| true` / `|| :` on a command whose failure is meaningful. Deliberately
#     NOT implemented even narrowed to git/cp/mv/mkdir: the only two sites in
#     scope are the best-effort provenance copies in
#     phase-02-test-stress-chaos.sh, where the original stays authoritative and
#     a failed copy costs a convenience duplicate, not a verdict. Both are
#     false positives, so the narrowed check would ship with zero true
#     positives — pure decoration. Whether a suppressed failure MATTERS is a
#     question about what depends on the result, which is not on the line.
#
#   * Everything semantic. Whether an assertion is on user-visible state,
#     whether a fake matches the real implementation, whether the thing
#     measured is the thing claimed. CHECK D catches one narrow, mechanical
#     tell of a fabricated corroboration claim; it does not read prose.
#
# CHECK F is the one check here that is precise rather than heuristic — the
# pipe IS the defect, with no safe subset to carve out — and it is the only one
# currently reporting hits. Those hits are real. See its note.
#
# Exit 0 if no unexplained instance is found; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCAN_DIRS=("${REPO_ROOT}/scripts/pipeline")
ORCHESTRATOR="${REPO_ROOT}/scripts/pipeline-build-test-distribute.sh"

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

mapfile -t SCRIPTS < <(
  { find "${SCAN_DIRS[@]}" -type f -name '*.sh' 2>/dev/null; printf '%s\n' "$ORCHESTRATOR"; } | sort -u
)

if [[ "${#SCRIPTS[@]}" -lt 5 ]]; then
  echo "FAIL: expected to find the pipeline's scripts to scan, found ${#SCRIPTS[@]}"
  exit 1
fi
echo "Scanning ${#SCRIPTS[@]} pipeline script(s)."
echo ""

# _rel <abs-path> — repo-relative path, for readable output.
_rel() { printf '%s' "${1#"${REPO_ROOT}/"}"; }

# ---------------------------------------------------------------------------
# CHECK A — an exit code captured into a variable and never compared.
# ---------------------------------------------------------------------------
# "Compared" means the variable appears somewhere in a test/case/arithmetic
# context, NOT merely interpolated into an echo/printf for reporting. A
# wrapper that prints "tool exited $RC" and then reports PASS anyway is the
# exact defect: the value was observed, displayed, and ignored.
echo "=== CHECK A: exit codes captured but never compared ==="
a_hits=0
for f in "${SCRIPTS[@]}"; do
  # Precomputed ONCE per file: the file with its `echo`/`printf` lines removed.
  # Held in a variable rather than piped, deliberately — see the note below.
  f_haystack="$(grep -vE '^[[:space:]]*(echo|printf)[[:space:]]' "$f" || true)"

  while IFS= read -r line; do
    lineno="${line%%:*}"
    text="${line#*:}"
    [[ "$text" == *"vacuous-pass-ok:"* ]] && continue

    var="$(printf '%s' "$text" | sed -nE 's/^[[:space:]]*(local[[:space:]]+)?([A-Za-z_][A-Za-z0-9_]*)=(\$\?|"?\$\{PIPESTATUS\[0\]\}"?).*/\2/p')"
    [[ -z "$var" ]] && continue

    # Does this variable ever appear in a comparison/test context anywhere in
    # the file? Deliberately generous about WHERE the check lives — the goal is
    # to catch "never examined at all", not to police placement.
    #
    # FALSE-NEGATIVE FIXED 2026-08-22. This previously searched the whole file
    # including its `echo`/`printf` lines, and accepted a bare `[` as evidence
    # of a test context. A reporting line like
    #     echo "wrapper [${module_label}] exited ${rc}"
    # therefore satisfied it: the `[` of the label matched, so the variable
    # counted as "compared" when it had only ever been PRINTED. That is exactly
    # the defect this check exists to find — an exit code observed, displayed,
    # and then ignored — so the guard was blind to its own target. Caught in
    # scripts/pipeline/phase-02-test-challenge.sh, where `local rc=$?` was
    # captured, echoed three times, and never compared, while this scanner
    # reported the file clean.
    #
    # Fix: strip `echo`/`printf` lines from the haystack before looking for a
    # test context. A variable appearing ONLY inside output statements is by
    # definition not being examined.
    #
    # SECOND BUG, introduced BY that fix and caught immediately (2026-08-22):
    # the first version of this piped `grep -v … | grep -q …`. `grep -q` exits
    # the moment it matches, closing the pipe; the upstream `grep -v` still has
    # output to write, takes SIGPIPE, and exits 141. Under `set -o pipefail`
    # (which this suite sets) the pipeline's status is that 141 — so a
    # SUCCESSFUL match was reported as failure, and two genuinely-compared
    # variables (`MATRIX_RC` in phase-04-live-verify-api-app.sh:506 and `rc` in
    # phase-06-docs.sh:198, both plainly inside `if [[ … -eq … ]]`) were flagged
    # as never-compared. Measured directly: both branches returned 141.
    #
    # It is worth noting what that near-miss was: a check for "an exit code was
    # captured and never examined" was itself defeated by an exit code it did
    # not examine closely enough. Hence no pipelines here — the haystack is a
    # variable, so there is no upstream process left to signal.
    if grep -qE "(\[\[|\[|case|if|while|until|\(\()[^\n]*\\\$\{?${var}\}?" <<< "$f_haystack" \
       || grep -qE "\\\$\{?${var}\}?[^\n]*(-eq|-ne|-gt|-lt|-ge|-le|==|!=)" <<< "$f_haystack" \
       || grep -qE "^[[:space:]]*(exit|return)[[:space:]]+\"?\\\$\{?${var}\}?" "$f"; then
      continue
    fi
    echo "  HIT $(_rel "$f"):${lineno}  \$${var} captured, never compared"
    a_hits=$((a_hits + 1))
  done < <(grep -nE '^[[:space:]]*(local[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*=(\$\?|"?\$\{PIPESTATUS\[0\]\}"?)' "$f" || true)
done
if [[ "$a_hits" -eq 0 ]]; then
  pass "no exit code is captured and then left uncompared"
else
  fail "${a_hits} exit code(s) captured but never compared — each is a place a real tool failure can be observed and then ignored"
fi

echo ""
echo "=== CHECK B: jq '//' fallback on a field whose real value can be falsy ==="
# jq's `//` fires on false and null alike. For a boolean-valued field that is
# the whole signal, so a `//` default silently converts "it failed" into "we
# do not know", which downstream code then treats as non-blocking.
BOOLISH='all_passed|passed|ran|ok|success|succeeded|healthy|valid|enabled|gating|test_passed|complete|completed'
b_hits=0
for f in "${SCRIPTS[@]}"; do
  while IFS= read -r line; do
    lineno="${line%%:*}"
    text="${line#*:}"
    [[ "$text" == *"vacuous-pass-ok:"* ]] && continue
    # Skip comment lines. The gate-sweep wrapper carries an explanatory comment
    # quoting the very bug it fixed (`jq -r '.all_passed // "unknown"'`), and a
    # scanner that flags the documentation of a fixed bug as the bug is itself
    # a false-positive generator. Observed on this suite's first real run.
    [[ "$(printf '%s' "$text" | sed -E 's/^[[:space:]]*//')" == \#* ]] && continue
    echo "  HIT $(_rel "$f"):${lineno}  jq '//' default applied to a boolean-valued field"
    echo "      ${text#"${text%%[![:space:]]*}"}"
    b_hits=$((b_hits + 1))
  done < <(grep -nE "jq[^#]*\.(${BOOLISH})[[:space:]]*//" "$f" || true)
done
if [[ "$b_hits" -eq 0 ]]; then
  pass "no jq '//' fallback is applied to a boolean-valued field"
else
  fail "${b_hits} jq '//' fallback(s) on boolean-valued field(s) — '//' fires on false as well as null, so a real 'it failed' becomes 'unknown'"
fi

echo ""
echo "=== CHECK C: -e / -s used on a reference that must be a regular file ==="
# Both -e and -s are TRUE for a directory. Where the value is supposed to be a
# captured-output FILE, -f is the only test that means what the code intends.
c_hits=0
for f in "${SCRIPTS[@]}"; do
  while IFS= read -r line; do
    lineno="${line%%:*}"
    text="${line#*:}"
    [[ "$text" == *"vacuous-pass-ok:"* ]] && continue
    echo "  HIT $(_rel "$f"):${lineno}  -e/-s on a *_ref/*_path/*_file value (true for a DIRECTORY too)"
    echo "      ${text#"${text%%[![:space:]]*}"}"
    c_hits=$((c_hits + 1))
  done < <(grep -nEi '\[\[?[[:space:]]+-(e|s)[[:space:]]+"?\$\{?[A-Za-z_][A-Za-z0-9_]*(_ref|_path|_file|_REF|_PATH|_FILE)\}?"?' "$f" || true)
done
if [[ "$c_hits" -eq 0 ]]; then
  pass "no -e/-s test stands in for -f on a file reference"
else
  fail "${c_hits} -e/-s test(s) on a file reference — both are true for a directory, which is how an empty raw_output_ref once satisfied the anti-bluff validator"
fi

echo ""
echo "=== CHECK D: a hardcoded success literal presented as a corroborated reading ==="
# The bluff: a summary that embeds `test_passed=true` as a LITERAL inside its
# format string while the surrounding prose claims an INDEPENDENT source
# agreed. The prose asserts corroboration; the literal supplies the value the
# corroborating source was supposed to have supplied. A reader — and the
# anti-bluff validator — sees a specific field value attributed to a second
# source, when nothing was ever read from one.
#
# Only SUCCESS literals are flagged. `test_passed=false` in a FAIL summary is
# not a vacuous pass — you cannot bluff a failure into safety — and the tree
# has a legitimate instance of exactly that at phase-04-live-verify-api-app.sh
# (the row genuinely reported FAIL on that branch).
#
# The discriminator is LITERAL vs INTERPOLATED. The fixed form of the real
# defect reads `test_passed=%s` fed from `row.get("test_passed")`, so a `%s`,
# `$VAR` or `\(...)` after the `=` is what tells "read from the row" apart
# from "asserted by the author". Corroboration wording is required as well,
# because a branch-established literal with no corroboration claim is normal
# and correct — `result="PASS"` inside `case $raw_result in PASS)` is the tree's
# own example, and flagging it would be noise.
D_CORROB='agrees?|independent|corroborat|cross-check|cross_check|both sources|second source|attestation row'
D_LIT='[A-Za-z_][A-Za-z0-9_]*=(true|TRUE|PASS|passed)([^A-Za-z0-9_%$\\]|$)'
d_hits=0
for f in "${SCRIPTS[@]}"; do
  d_cands="$(grep -nE "$D_LIT" "$f" || true)"
  [[ -z "$d_cands" ]] && continue
  mapfile -t D_L < "$f"
  d_n="${#D_L[@]}"
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    lineno="${line%%:*}"
    text="${line#*:}"
    [[ "$text" == *"vacuous-pass-ok:"* ]] && continue
    case "${text#"${text%%[![:space:]]*}"}" in \#*) continue ;; esac
    # A summary string is routinely split across many source lines (the real
    # instance was a multi-line Python format string embedded in this bash
    # script), so the corroboration claim and the literal are usually NOT on
    # the same line. Window the surrounding statement rather than the line.
    d_i=$((lineno - 1))
    d_lo=$((d_i - 5)); ((d_lo < 0)) && d_lo=0
    d_hi=$((d_i + 5)); ((d_hi >= d_n)) && d_hi=$((d_n - 1))
    d_win=""
    for ((d_j = d_lo; d_j <= d_hi; d_j++)); do d_win+="${D_L[$d_j]}"$'\n'; done
    grep -qiE "$D_CORROB" <<< "$d_win" || continue
    echo "  HIT $(_rel "$f"):${lineno}  success literal asserted alongside a corroboration claim"
    echo "      ${text#"${text%%[![:space:]]*}"}"
    d_hits=$((d_hits + 1))
  done <<< "$d_cands"
done
if [[ "$d_hits" -eq 0 ]]; then
  pass "no hardcoded success literal is presented as a corroborated reading"
else
  fail "${d_hits} hardcoded success literal(s) presented as corroborated — the prose credits an independent source with a value the format string supplied itself"
fi

echo ""
echo "=== CHECK E: \${#VAR} used where a BYTE count is claimed ==="
# `${#VAR}` counts CHARACTERS. Any multi-byte UTF-8 in the value makes it
# disagree with the byte length of what is written to disk. phase-05a's
# per-version snapshot reported `${#SNAPSHOT_CONTENT}` as "N bytes of release
# notes" and was wrong by 7 bytes even on the happy path, purely from the
# non-ASCII characters in the notes — and on the already-present path it
# described text the script had deliberately not written at all. The fix reads
# `wc -c` from the file that actually exists.
#
# Flagged when a `${#...}` appears on a line that talks about bytes, or is
# assigned into a `*BYTES*`-named variable. The comment block in phase-05a that
# documents this very fix mentions both `${#SNAPSHOT_CONTENT}` and "bytes";
# comment lines are skipped for the same reason CHECK B skips them — a scanner
# that flags the write-up of a fixed bug as the bug is a false-positive engine.
e_hits=0
for f in "${SCRIPTS[@]}"; do
  e_lines="$(grep -nE '\$\{#[A-Za-z_][A-Za-z0-9_]*\}' "$f" || true)"
  [[ -z "$e_lines" ]] && continue
  e_cands="$(grep -iE 'byte|[A-Za-z_]*bytes?=' <<< "$e_lines" || true)"
  [[ -z "$e_cands" ]] && continue
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    lineno="${line%%:*}"
    text="${line#*:}"
    [[ "$text" == *"vacuous-pass-ok:"* ]] && continue
    case "${text#"${text%%[![:space:]]*}"}" in \#*) continue ;; esac
    echo "  HIT $(_rel "$f"):${lineno}  \${#VAR} counts CHARACTERS, but this line claims BYTES"
    echo "      ${text#"${text%%[![:space:]]*}"}"
    e_hits=$((e_hits + 1))
  done <<< "$e_cands"
done
if [[ "$e_hits" -eq 0 ]]; then
  pass "no character count is reported as a byte count"
else
  fail "${e_hits} character count(s) reported as bytes — measure the artifact that exists (wc -c), never the string in memory"
fi

echo ""
echo "=== CHECK F: 'grep -q' on the right-hand side of a pipe under pipefail ==="
# `grep -q` exits the instant it matches. If the left-hand side is still
# writing, it takes SIGPIPE and dies 141; under `set -o pipefail` the pipeline's
# status IS that 141, so a SUCCESSFUL MATCH is reported as a failed one. The
# outcome therefore depends on the SIZE of the data, not on whether the pattern
# is present.
#
# This is not theoretical and it is not rare. It bit CHECK A of this very file
# during its own construction (see the note there), and it is live in the tree
# today: anti-bluff-validate.sh Rule 4 builds `combined` from `cat` of a
# record's raw_output_ref and then tests it for the FALSIFIABILITY REHEARSAL
# marker through exactly this shape. Measured on this host, the flip happens
# between 100 KB and 130 KB of left-hand output; the real-device logcats that
# feed raw_output_ref in this repo run 1.4 MB to 5 MB. Running that rule's own
# code byte-for-byte against a 51-byte file and a 2 MB file with the SAME marker
# in the SAME position returns ACCEPTED and REJECTED respectively.
#
# There is no safe subset to carve out, which is what makes this check precise:
# the pipe IS the defect. The fix is always to drop it — `grep -q <<< "$var"`,
# or a bash `[[ $var == *pat* ]]` / `case`, neither of which has an upstream
# process left to signal.
#
# Applies to a file that sets pipefail itself AND to a sourced library, which
# runs under whatever options its caller set. All three files in lib/ set no
# options of their own and are sourced by phase scripts that all set
# `-uo pipefail`, so the option is inherited and the defect is reachable there.
f_hits=0
for f in "${SCRIPTS[@]}"; do
  f_own_pipefail=0
  grep -qE '^[[:space:]]*set[[:space:]]+-[a-zA-Z]*o[a-zA-Z]*[[:space:]]+pipefail|^[[:space:]]*set[[:space:]]+-o[[:space:]]+pipefail' "$f" && f_own_pipefail=1
  if [[ "$f_own_pipefail" -eq 0 ]]; then
    # Sourced by any script in scope? Then it inherits that caller's options.
    f_base="$(basename "$f")"
    f_sourced=0
    for g in "${SCRIPTS[@]}"; do
      [[ "$g" == "$f" ]] && continue
      grep -qE "^[[:space:]]*(source|\.)[[:space:]]+.*${f_base}" "$g" && { f_sourced=1; break; }
    done
    [[ "$f_sourced" -eq 1 ]] || continue
  fi
  # `[^|]` before the bar is load-bearing: without it the pattern also matches
  # the second bar of `cmd || grep -q pat file`, which is a logical OR and not a
  # pipe at all — there is no upstream writer to signal, so it is perfectly
  # safe. Found while self-checking THIS file, whose own CHECK A uses
  # `|| grep -qE ... <<< "$f_haystack"` on two consecutive lines and was duly
  # flagged by the first draft of this check. The optional `&` additionally
  # picks up `cmd |& grep -q`, a real pipe the first draft missed.
  f_cands="$(grep -nE '[^|]\|&?[[:space:]]*grep([[:space:]]+-[a-zA-Z-]+)*[[:space:]]+-[a-zA-Z]*q' "$f" || true)"
  [[ -z "$f_cands" ]] && continue
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    lineno="${line%%:*}"
    text="${line#*:}"
    [[ "$text" == *"vacuous-pass-ok:"* ]] && continue
    case "${text#"${text%%[![:space:]]*}"}" in \#*) continue ;; esac
    echo "  HIT $(_rel "$f"):${lineno}  'grep -q' after a pipe — a match on >100KB of input reports rc=141, not 0"
    echo "      ${text#"${text%%[![:space:]]*}"}"
    f_hits=$((f_hits + 1))
  done <<< "$f_cands"
done
if [[ "$f_hits" -eq 0 ]]; then
  pass "no 'grep -q' sits on the right-hand side of a pipe under pipefail"
else
  fail "${f_hits} 'grep -q' on the right of a pipe under pipefail — each turns a real match into rc=141 once the left-hand side exceeds the pipe buffer, making the verdict depend on input SIZE rather than on the pattern"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi
