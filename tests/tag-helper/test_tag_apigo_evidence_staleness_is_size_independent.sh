#!/usr/bin/env bash
# Asserts: scripts/tag.sh's api-go pretag-evidence STALENESS gate returns the
# same verdict regardless of how many files changed since the evidence commit.
#
# FORENSIC ANCHOR (2026-08-26). The gate was written as
#
#     if git diff --name-only "$anc..HEAD" -- | grep -qvE '^\.lava-ci-evidence/'; then
#       die "...evidence is ... stale..."
#
# under this script's `set -Eeuo pipefail`. `grep -q` exits at the FIRST
# matching path and closes the pipe; once the listing exceeds the 64 KiB pipe
# buffer, git is killed by SIGPIPE and exits 141, `pipefail` promotes 141 to
# the pipeline status, and the enclosing `if` reads FALSE. A MATCH is thereby
# delivered as a NO-MATCH. Measured against the real gate before the fix:
#
#     changed-code-files=1      diff-bytes=117      caught 10/10
#     changed-code-files=2000   diff-bytes=106064   caught  0/10
#
# and on the missed runs the gate printed the positively FALSE claim
#   "[api-go] pretag evidence found at ancestor <sha> (only .lava-ci-evidence/
#    changed since)"
# The gate was therefore weakest exactly where the evidence was most stale —
# the perverse direction. This is the LVA-135 SIGPIPE-under-pipefail shape.
#
# This suite pins the fixed behaviour: the same wrong-answer input must be
# refused at 100 bytes of listing and at >64 KiB of listing alike, and the
# large case must PROVE it actually crossed the pipe-buffer threshold rather
# than quietly testing the small regime twice.
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILURES=0
EXAMINED=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

# _fixture <n_code_files> — builds a throwaway repo in which the pretag
# evidence belongs to an ancestor and <n_code_files> NON-evidence files have
# changed since. Echoes "<workdir> <diff_bytes>".
_fixture() {
  local n="$1"
  local work; work="$(mktemp -d)"
  cp -r "$REPO_ROOT/scripts" "$work/scripts"
  mkdir -p "$work/lava-api-go/internal/version"
  printf 'package version\nconst (\n\tName = "9.9.9"\n\tCode = 999\n)\n' \
    > "$work/lava-api-go/internal/version/version.go"
  printf '# Changelog\n\n## Lava-API-Go-9.9.9-999\n- entry\n' > "$work/CHANGELOG.md"
  mkdir -p "$work/.lava-ci-evidence/distribute-changelog/container-registry"
  printf 'notes\n' > "$work/.lava-ci-evidence/distribute-changelog/container-registry/9.9.9-999.md"
  ( cd "$work" && git init -q && git config user.email t@t && git config user.name t \
      && git add -A && git commit -qm seed ) >/dev/null 2>&1
  local anc; anc="$(git -C "$work" rev-parse HEAD)"
  echo '{"verified":true}' > "$work/.lava-ci-evidence/${anc}.json"
  ( cd "$work" && git add -A && git commit -qm "pretag evidence only" ) >/dev/null 2>&1
  # Long paths so the --name-only listing crosses the 64 KiB pipe buffer
  # without needing an impractical number of files.
  local deep="src/a_deliberately_long_directory_component_to_grow_the_name_only_listing/another_deliberately_long_directory_component_here"
  mkdir -p "$work/$deep"
  local i
  for ((i = 0; i < n; i++)); do
    printf 'package p\n' > "$work/$deep/source_file_$(printf '%06d' "$i")_with_a_long_basename.go"
  done
  ( cd "$work" && git add -A && git commit -qm "n non-evidence files" ) >/dev/null 2>&1
  local bytes; bytes="$(git -C "$work" diff --name-only "${anc}..HEAD" -- | wc -c)"
  printf '%s %s' "$work" "$bytes"
}

# _run <workdir> — echoes tag.sh's combined output.
_run() { ( cd "$1" && bash scripts/tag.sh --app api-go --no-bump --no-push 2>&1 ); }

_STALE_MSG='non-evidence files have changed since'
# Wording-agnostic freshness marker: tag.sh emits this line ONLY when the
# staleness check concluded the evidence is fresh. Its presence on a run whose
# evidence IS stale is the false claim itself, whatever the sentence around it.
_FALSE_CLAIM='pretag evidence found at ancestor'

echo "==============================================================="
echo "CASE 1: stale evidence, TINY diff listing -> gate must refuse"
echo "==============================================================="
read -r W1 B1 <<< "$(_fixture 1)"
EXAMINED=$((EXAMINED + 1))
OUT1="$(_run "$W1")"
if grep -qF "$_STALE_MSG" <<< "$OUT1"; then
  pass "1 changed non-evidence file (${B1}-byte listing) -> gate refuses"
else
  fail "1 changed non-evidence file (${B1}-byte listing) -> gate did NOT refuse. Output: ${OUT1}"
fi
rm -rf -- "$W1"

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): same defect, listing OVER the 64 KiB pipe buffer"
echo "==============================================================="
read -r W2 B2 <<< "$(_fixture 1500)"
EXAMINED=$((EXAMINED + 1))
if [[ "$B2" -gt 65536 ]]; then
  pass "fixture precondition: the --name-only listing is ${B2} bytes, past the 64 KiB pipe buffer"
else
  fail "fixture precondition FAILED: listing is only ${B2} bytes, so this case never enters the regime the bug lived in and proves nothing"
fi
OUT2="$(_run "$W2")"
if grep -qF "$_STALE_MSG" <<< "$OUT2"; then
  pass "large stale diff (${B2}-byte listing) -> gate still refuses (verdict is size-independent)"
else
  fail "large stale diff (${B2}-byte listing) -> gate FAILED OPEN. The staleness verdict degrades with input size: the gate is weakest exactly when the evidence is most stale. Output: ${OUT2}"
fi
if grep -qF "$_FALSE_CLAIM" <<< "$OUT2"; then
  fail "the gate positively CLAIMED the pretag evidence was fresh while ${B2} bytes of non-evidence paths had changed since it. A silent miss would be bad; announcing freshness that does not exist is worse."
else
  pass "the gate made no freshness claim on the large-diff run"
fi
EXAMINED=$((EXAMINED + 1))
if grep -qF "created tag:" <<< "$OUT2"; then
  fail "tag.sh CREATED a release tag on a commit whose pretag evidence was ${B2} bytes of code-changes stale. This is the end-state the gate exists to prevent."
else
  pass "no release tag was created on the stale-evidence run"
fi
rm -rf -- "$W2"

echo ""
echo "==============================================================="
echo "CASE 3 (control): evidence-only changes must NOT be called stale"
echo "==============================================================="
read -r W3 _B3 <<< "$(_fixture 0)"
EXAMINED=$((EXAMINED + 1))
OUT3="$(_run "$W3")"
if grep -qF "$_STALE_MSG" <<< "$OUT3"; then
  fail "a run where ONLY .lava-ci-evidence/ changed was wrongly refused as stale — the fix must not simply refuse everything. Output: ${OUT3}"
else
  pass "evidence-only changes are accepted (the fix did not just make the gate always refuse)"
fi
rm -rf -- "$W3"

echo ""
echo "==============================================================="
if [[ "$EXAMINED" -eq 0 ]]; then
  echo "FAIL: this suite examined 0 cases and therefore proves nothing"
  exit 1
fi
echo "examined ${EXAMINED} case(s)"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
fi
echo "$FAILURES CHECK(S) FAILED"
exit 1
