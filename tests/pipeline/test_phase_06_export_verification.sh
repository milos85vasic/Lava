#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-06-docs.sh's PASS-2
# (derived-export) verification and for the honesty of the Evidence Record it
# writes.
#
# scripts/sync-markdown-exports.sh is replaced by a stub in a throwaway repo,
# so no pandoc/weasyprint run happens and nothing in the real tree is touched.
# The seam is the script's own documented `[repo-path]` positional.
#
# WHY CASE 2 EXISTS (forensic anchor, 2026-08-22):
# The phase's header commits to a precisely scoped verdict — "it FAILS if and
# only if a file THIS phase changed still appears in --check-only's problem
# list afterwards". That verification was a NO-MATCH PARSE: it ran
# --check-only, captured its stdout, and grepped for each changed file's
# .html/.pdf path. Nothing found meant "fresh".
#
# So an external sync-markdown-exports.sh that exits 0 for every flag and does
# absolutely nothing produced an EMPTY problem list, no grep matched, and the
# phase reported PASS with an Evidence Record asserting
#
#   "every changed .md was re-checked with scripts/sync-markdown-exports.sh
#    --check-only and none of their .html/.pdf siblings appear in its
#    MISSING/STALE list"
#
# while not one .html or .pdf existed anywhere on disk. Zero results therefore
# zero failures therefore PASS — the canonical shape.
#
# WHY CASE 3 EXISTS (forensic anchor, 2026-08-22):
# The --check-only invocation's exit code was never captured at all:
#
#   CHECK_OUTPUT="$( ( cd "$REPO_PATH" && bash "$SYNC_SH" --check-only ) 2>&1 )"
#
# sync-markdown-exports.sh's own documented codes are 0 (clean), 1 (problems
# found) and 2 (usage error). When the flag is renamed upstream, --check-only
# exits 2 having printed a usage line; that usage line contains no .html/.pdf
# path, so the grep matched nothing and the phase again reported "verified".
# The verification step failing to run at all was indistinguishable from it
# passing.
#
# WHY CASE 4 EXISTS: the PASS-branch assertion_summary was a fixed sentence
# that asserted the --check-only re-verification unconditionally, including on
# the paths where PASS 2 provably never ran (--skip-exports, dry run, nothing
# to regenerate). It read, in one breath, "Derived-export pass: skipped
# (--skip-exports); every changed .md was re-checked with ... --check-only".
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PHASE06="${REPO_ROOT}/scripts/pipeline/phase-06-docs.sh"

if [[ ! -f "$PHASE06" ]]; then
  echo "FAIL: script under test not found: $PHASE06"
  exit 1
fi
for tool in jq python3 git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# _new_fixture <name> <sync-stub-kind: working|noop|nocheckonly>
# The two documents carry the exact stale strings PASS 1 matches, so PASS 1
# really applies and CHANGED_FILES is really non-empty.
_new_fixture() {
  local name="$1" kind="$2"
  local f="${WORKDIR}/${name}"
  mkdir -p "${f}/scripts" "${f}/docs"
  git init -q -b master "$f"
  git -C "$f" config user.email "fixture@example.invalid"
  git -C "$f" config user.name "Fixture"

  cat > "${f}/docs/ARCHITECTURE.md" <<'MD'
# Architecture

## On-Device Lava API

**Pending (Phases C/D/E):** the Kotlin wrapper, the Compose module, the service.

The next paragraph.
MD

  cat > "${f}/CLAUDE.md" <<'MD'
# CLAUDE.md

## Project

Lava is an unofficial Android client, plus a companion **Ktor proxy server** that scrapes upstream sites and exposes a JSON API to the app. Two artifacts share one Gradle build:

- `:proxy` — Ktor/Netty headless server, packaged as a fat JAR + Docker image.
MD

  case "$kind" in
    working)
      # Really writes fresh .html + .pdf siblings, and really reports on them.
      cat > "${f}/scripts/sync-markdown-exports.sh" <<'SS'
#!/usr/bin/env bash
_emit() { base="${1%.md}"; printf '<html>%s</html>\n' "$1" > "${base}.html"; printf '%%PDF-1.4 %s\n' "$1" > "${base}.pdf"; }
case "$1" in
  --regenerate)     _emit "$2"; echo "[markdown-export] regenerated $2"; exit 0 ;;
  --regenerate-all) for m in $(find . -name '*.md' -not -path './.git/*'); do _emit "$m"; done; exit 0 ;;
  --check-only)
      problems=0
      for m in $(find . -name '*.md' -not -path './.git/*'); do
        b="${m%.md}"
        [[ -f "$b.html" ]] || { echo "  MISSING html: $b.html"; problems=$((problems+1)); continue; }
        [[ -f "$b.pdf"  ]] || { echo "  MISSING pdf:  $b.pdf";  problems=$((problems+1)); continue; }
        [[ "$m" -nt "$b.html" ]] && { echo "  STALE html:   $b.html (older than $m)"; problems=$((problems+1)); }
        [[ "$m" -nt "$b.pdf"  ]] && { echo "  STALE pdf:    $b.pdf (older than $m)";  problems=$((problems+1)); }
      done
      echo "[markdown-export] checked in-scope .md file(s); $problems problem(s)."
      (( problems > 0 )) && exit 1
      exit 0 ;;
esac
echo "Usage: $0 [--check-only|--regenerate-all|--regenerate <file>]" >&2
exit 2
SS
      ;;
    noop)
      # Exits 0 for absolutely everything and does nothing at all.
      cat > "${f}/scripts/sync-markdown-exports.sh" <<'SS'
#!/usr/bin/env bash
echo "[markdown-export] stub: did nothing at all for: $*"
exit 0
SS
      ;;
    nocheckonly)
      # --regenerate really works; --check-only was renamed upstream, so it
      # exits 2 with a usage line that names no .html/.pdf path.
      cat > "${f}/scripts/sync-markdown-exports.sh" <<'SS'
#!/usr/bin/env bash
case "$1" in
  --regenerate)
      base="${2%.md}"; printf '<html>x</html>\n' > "${base}.html"; printf '%%PDF-1.4\n' > "${base}.pdf"
      echo "[markdown-export] regenerated $2"; exit 0 ;;
esac
echo "Usage: $0 [--verify|--regenerate-all|--regenerate <file>]" >&2
exit 2
SS
      ;;
  esac
  chmod +x "${f}/scripts/sync-markdown-exports.sh"

  git -C "$f" add -A >/dev/null 2>&1
  git -C "$f" commit -qm "fixture init" >/dev/null 2>&1
  printf '%s' "$f"
}

# _run <fixture> <run_id> [extra args...] — sets P6_RC, P6_OUT, P6_RECORD.
_run() {
  local f="$1" run_id="$2"; shift 2
  ( cd "$f" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" \
      && init_run_report "$run_id" "$(printf '0%.0s' {1..40})" >/dev/null )
  local out="${WORKDIR}/p6.log"
  set +e
  ( cd "$f" && bash "$PHASE06" "$run_id" "$f" "$@" ) >"$out" 2>&1
  P6_RC=$?
  set -e
  P6_OUT="$(cat "$out")"
  P6_RECORD="${f}/.lava-ci-evidence/pipeline-runs/${run_id}/phase-06/hermetic-script/docs-refresh-stale-fixes-and-exports.json"
}

echo "==============================================================="
echo "CASE 1: a sync script that really regenerates -> PASS"
echo "(guards against a 'fix' that just makes every run fail)"
echo "==============================================================="

F1="$(_new_fixture working working)"
_run "$F1" "2026-08-22T20-00-00Z"

if grep -q 'APPLIED' <<< "$P6_OUT"; then
  pass "fixture sanity: PASS 1 really applied a stale-doc fix, so PASS 2 had files to regenerate"
else
  fail "fixture sanity: PASS 1 applied nothing, so this suite proves nothing; output: ${P6_OUT}"
fi
if [[ -f "${F1}/docs/ARCHITECTURE.html" && -f "${F1}/docs/ARCHITECTURE.pdf" ]]; then
  pass "fixture sanity: the working stub really wrote the .html + .pdf siblings"
else
  fail "fixture sanity: the working stub wrote no siblings; CASE 1 proves nothing"
fi
if [[ "$P6_RC" -eq 0 ]]; then
  pass "working sync script -> phase exits 0"
else
  fail "working sync script -> phase exits ${P6_RC}; output: ${P6_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (LOAD-BEARING): sync script exits 0 and does NOTHING"
echo "==============================================================="
echo "No .html or .pdf is created. The phase's own verification is a grep over"
echo "--check-only's problem list, and an empty problem list matches nothing."
echo ""

F2="$(_new_fixture noop noop)"
_run "$F2" "2026-08-22T21-00-00Z"

if [[ ! -f "${F2}/docs/ARCHITECTURE.html" && ! -f "${F2}/docs/ARCHITECTURE.pdf" ]]; then
  pass "fixture sanity: not one derived export exists on disk after the run"
else
  fail "fixture sanity: the no-op stub somehow produced siblings; this case proves nothing"
fi
if grep -q 'APPLIED' <<< "$P6_OUT"; then
  pass "fixture sanity: PASS 1 applied a fix, so PASS 2 genuinely had work to verify"
else
  fail "fixture sanity: PASS 1 applied nothing; this case proves nothing"
fi

if [[ "$P6_RC" -ne 0 ]]; then
  pass "no-op sync script -> phase exits non-zero (${P6_RC})"
else
  fail "no-op sync script -> phase exits 0. It changed two .md files, regenerated no exports at all, and reported PASS."
fi
if [[ -f "$P6_RECORD" ]]; then
  r2="$(jq -r '.result' "$P6_RECORD")"
  s2="$(jq -r '.assertion_summary' "$P6_RECORD")"
  if [[ "$r2" == "FAIL" ]]; then
    pass "no-op sync script -> Evidence Record result FAIL"
  else
    fail "no-op sync script -> Evidence Record result '${r2}', expected FAIL"
  fi
  if grep -qiE 'ARCHITECTURE\.(html|pdf)' <<< "$s2"; then
    pass "assertion_summary names a specific sibling that is not on disk"
  else
    fail "assertion_summary does not name the missing sibling(s): ${s2}"
  fi
else
  fail "no Evidence Record was written at ${P6_RECORD}"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (LOAD-BEARING): --check-only was renamed upstream (exits 2)"
echo "==============================================================="
echo "--regenerate still works, so the exports really are fresh; but the"
echo "verification step could not run. 'Could not verify' must not read the"
echo "same as 'verified'."
echo ""

F3="$(_new_fixture nocheckonly nocheckonly)"
_run "$F3" "2026-08-22T22-00-00Z"

if [[ -f "${F3}/docs/ARCHITECTURE.html" ]]; then
  pass "fixture sanity: --regenerate really worked, so only the VERIFICATION is broken"
else
  fail "fixture sanity: --regenerate did not work either; this case does not isolate the defect"
fi
if grep -qE 'Usage:' <<< "$P6_OUT"; then
  pass "fixture sanity: --check-only really failed with a usage error"
else
  fail "fixture sanity: --check-only did not fail as intended; output: ${P6_OUT}"
fi
if [[ "$P6_RC" -ne 0 ]]; then
  pass "--check-only exiting 2 -> phase exits non-zero (${P6_RC})"
else
  fail "--check-only exiting 2 -> phase exits 0. The verification never ran, and its exit code was never captured, so 'could not check' was reported as 'checked and clean'."
fi
if [[ -f "$P6_RECORD" ]] && jq -r '.assertion_summary' "$P6_RECORD" | grep -qiE 'exit(ed)? *(code )?2|could not|did not run|usage'; then
  pass "assertion_summary reports that the verification itself failed to run"
else
  fail "assertion_summary does not report the failed verification: $(jq -r '.assertion_summary' "${P6_RECORD:-/dev/null}" 2>/dev/null)"
fi

echo ""
echo "==============================================================="
echo "CASE 4: --skip-exports must not claim a re-check that never ran"
echo "==============================================================="

F4="$(_new_fixture skipexports working)"
_run "$F4" "2026-08-22T23-00-00Z" --skip-exports

if [[ "$P6_RC" -eq 0 ]]; then
  pass "--skip-exports -> phase exits 0 (PASS 1 alone is a legitimate outcome)"
else
  fail "--skip-exports -> phase exits ${P6_RC}; skipping exports is an honest documented mode, not a failure"
fi
if [[ -f "$P6_RECORD" ]]; then
  s4="$(jq -r '.assertion_summary' "$P6_RECORD")"
  if grep -q 'skipped' <<< "$s4" && grep -q 're-checked' <<< "$s4"; then
    fail "assertion_summary says the export pass was skipped AND that every changed .md was re-checked with --check-only, in the same sentence: ${s4}"
  else
    pass "assertion_summary does not claim a --check-only re-check on the skipped path"
  fi
  if grep -qiE 'skip' <<< "$s4"; then
    pass "assertion_summary states plainly that the export pass was skipped"
  else
    fail "assertion_summary does not record that exports were skipped: ${s4}"
  fi
else
  fail "no Evidence Record was written at ${P6_RECORD}"
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
