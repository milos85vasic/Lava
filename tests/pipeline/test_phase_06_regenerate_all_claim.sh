#!/usr/bin/env bash
# Hermetic test for the honesty of phase-06's derived-export claim on the
# --regenerate-all path.
#
# scripts/pipeline/phase-06-docs.sh verifies its PASS-2 work with two loops,
# BOTH of which iterate over CHANGED_FILES (the .md files PASS 1 actually
# rewrote):
#   (a) a first-hand on-disk check that each changed .md has an .html and a
#       .pdf sibling no older than itself, and
#   (b) a scoped grep of `sync-markdown-exports.sh --check-only`'s output for
#       those same siblings.
#
# WHY THIS TEST EXISTS (forensic anchor, 2026-08-25): --regenerate-all is the
# one flag that sets EXPORTS_STATUS=ran while CHANGED_FILES can be EMPTY (PASS
# 1 found both docs already correct, so it changed nothing, but the operator
# asked for the whole-repo sweep anyway). Both loops then iterate ZERO times,
# nothing can fail, and the Evidence Record asserted verbatim:
#
#   "Derived-export pass: ran; every changed .md was verified first-hand on
#    disk (an .html and a .pdf sibling present, neither older than its .md)
#    and re-checked with scripts/sync-markdown-exports.sh --check-only
#    (exit 0), with none of their .html/.pdf siblings in its MISSING/STALE
#    list."
#
# -- while the sweep had been handed to a stub that exits 0 and writes
# nothing, and not one .html or .pdf existed anywhere on disk. That is the
# SAME "check passing having examined zero items" shape the first-hand check
# was added to close, left open on this path: a vacuous truth (there were no
# changed files, so every one of them trivially verified) printed as a
# verification. An Evidence Record may only assert what this run really
# examined, and must say when that was nothing.
#
# The whole-repo sweep's own output is separately unverifiable by this phase
# without duplicating sync-markdown-exports.sh's in-scope path rules, which
# the phase explicitly refuses to do — so it must DISCLOSE that rather than
# let the sentence above imply coverage it does not have.
#
# scripts/sync-markdown-exports.sh is replaced by a stub in a throwaway repo;
# no pandoc/weasyprint runs and nothing in the real tree is touched. The seam
# is the script's own documented `[repo-path]` positional.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PHASE06="${REPO_ROOT}/scripts/pipeline/phase-06-docs.sh"

[[ -f "$PHASE06" ]] || { echo "FAIL: script under test not found: $PHASE06"; exit 1; }
for tool in jq python3 git; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

# _mkfix <dir> <stale|clean>
#   stale — docs carry the exact strings PASS 1 rewrites, so it changes files
#   clean — docs carry none of them, so PASS 1 is a NOOP and nothing changes
_mkfix() {
  local fix="$1" flavour="$2"
  mkdir -p "${fix}/scripts" "${fix}/docs"
  git init -q -b master "$fix"
  git -C "$fix" config user.email "fixture@example.invalid"
  git -C "$fix" config user.name "Fixture"

  if [[ "$flavour" == "stale" ]]; then
    cat > "${fix}/docs/ARCHITECTURE.md" <<'A'
# Architecture

## On-Device Lava API

**Pending (Phases C/D/E):** not yet built.

Tail.
A
    cat > "${fix}/CLAUDE.md" <<'C'
# CLAUDE.md

## Project

Lava is a client, plus a companion **Ktor proxy server** that scrapes upstream sites and exposes a JSON API to the app. Two artifacts share one Gradle build:

- `:proxy` — Ktor/Netty headless server, packaged as a fat JAR + Docker image.
C
  else
    printf '# Architecture\n\nAll phases landed; nothing stale here.\n' > "${fix}/docs/ARCHITECTURE.md"
    printf '# CLAUDE.md\n\n## Project\n\nNothing stale here either.\n'   > "${fix}/CLAUDE.md"
  fi
  git -C "$fix" add -A >/dev/null 2>&1
  git -C "$fix" commit -qm "fixture init" >/dev/null 2>&1
}

# _stub_silent — exits 0 for every flag and writes nothing at all.
_stub_silent() { printf '#!/usr/bin/env bash\nexit 0\n' > "$1"; chmod +x "$1"; }

# _stub_honest — really writes siblings and really checks them.
_stub_honest() {
  cat > "$1" <<'S'
#!/usr/bin/env bash
set -uo pipefail
case "${1:---check-only}" in
  --regenerate) printf 'html\n' > "${2%.md}.html"; printf 'pdf\n' > "${2%.md}.pdf" ;;
  --regenerate-all)
    while IFS= read -r m; do printf 'html\n' > "${m%.md}.html"; printf 'pdf\n' > "${m%.md}.pdf"; done \
      < <(find . -name '*.md' -not -path './.lava-ci-evidence/*') ;;
  --check-only)
    p=0; c=0
    while IFS= read -r m; do
      c=$((c+1)); b="${m%.md}"
      [[ -f "$b.html" ]] || { echo "  MISSING html: $b.html"; p=$((p+1)); continue; }
      [[ -f "$b.pdf"  ]] || { echo "  MISSING pdf:  $b.pdf";  p=$((p+1)); continue; }
    done < <(find . -name '*.md' -not -path './.lava-ci-evidence/*')
    echo "[markdown-export] checked $c in-scope .md file(s); $p problem(s)."
    (( p > 0 )) && exit 1 || exit 0 ;;
  *) echo "Usage: $0 [--check-only|--regenerate-all|--regenerate <file>]" >&2; exit 2 ;;
esac
S
  chmod +x "$1"
}

# _run <fix> <run_id> [args...] -> sets RC and REC_SUMMARY
_run() {
  local fix="$1" rid="$2"; shift 2
  ( cd "$fix" && source "${REPO_ROOT}/scripts/pipeline/lib/run-report.sh" \
      && init_run_report "$rid" "$(git -C "$fix" rev-parse HEAD)" >/dev/null )
  set +e
  RUN_OUT="$( cd "$fix" && bash "$PHASE06" "$rid" "$fix" "$@" 2>&1 )"
  RC=$?
  set -e
  local rec="${fix}/.lava-ci-evidence/pipeline-runs/${rid}/phase-06/hermetic-script/docs-refresh-stale-fixes-and-exports.json"
  REC_SUMMARY=""
  [[ -f "$rec" ]] && REC_SUMMARY="$(jq -r '.assertion_summary' "$rec")"
}

echo "==============================================================="
echo "CASE 0: honest exporter, real changed files -> PASS, and the"
echo "        claim names how many files it actually verified"
echo "==============================================================="
FIX0="${WORKDIR}/case0"; _mkfix "$FIX0" stale; _stub_honest "${FIX0}/scripts/sync-markdown-exports.sh"
_run "$FIX0" "2026-08-25T60-00-00Z"
if [[ "$RC" -eq 0 ]]; then
  pass "case0: exits 0 (over-correction guard — the honest path must keep passing)"
else
  fail "case0: expected exit 0, got ${RC}; output: ${RUN_OUT}"
fi
if [[ -n "$REC_SUMMARY" ]] && printf '%s' "$REC_SUMMARY" | grep -qF -- "2 .md file(s)"; then
  pass "case0: claim states the number of files verified first-hand"
else
  fail "case0: claim does not state how many files were verified; got: ${REC_SUMMARY}"
fi
for f in "docs/ARCHITECTURE.md" "CLAUDE.md"; do
  for e in html pdf; do
    if [[ -f "${FIX0}/${f%.md}.${e}" ]]; then :; else
      fail "case0 fixture sanity: ${f%.md}.${e} was never written; the case proves nothing"
    fi
  done
done

echo
echo "==============================================================="
echo "CASE 1 (LOAD-BEARING): --regenerate-all + do-nothing exporter +"
echo "        already-correct docs => both verification loops examine"
echo "        ZERO files. The record must not claim a verification."
echo "==============================================================="
FIX1="${WORKDIR}/case1"; _mkfix "$FIX1" clean; _stub_silent "${FIX1}/scripts/sync-markdown-exports.sh"
_run "$FIX1" "2026-08-25T61-00-00Z" --regenerate-all

SIBS="$(find "$FIX1" \( -name '*.html' -o -name '*.pdf' \) -not -path '*/.lava-ci-evidence/*' | wc -l | tr -d '[:space:]')"
if [[ "$SIBS" -eq 0 ]]; then
  pass "case1 fixture sanity: the do-nothing stub really produced 0 derived exports"
else
  fail "case1 fixture sanity: expected 0 derived exports on disk, found ${SIBS}"
fi
if [[ -z "$REC_SUMMARY" ]]; then
  fail "case1: no Evidence Record was written; output: ${RUN_OUT}"
else
  if printf '%s' "$REC_SUMMARY" | grep -qF -- "every changed .md was verified first-hand"; then
    fail "case1: THE VACUOUS CLAIM IS STILL THERE — the record asserts 'every changed .md was verified first-hand' although the verification loops iterated zero times and no export exists on disk. Claim: ${REC_SUMMARY}"
  else
    pass "case1: the record does not assert a first-hand verification of files it never examined"
  fi
  if printf '%s' "$REC_SUMMARY" | grep -qF -- "ZERO"; then
    pass "case1: the record states plainly that zero files were examined"
  else
    fail "case1: the record does not disclose that zero files were examined; claim: ${REC_SUMMARY}"
  fi
fi

echo
echo "==============================================================="
echo "CASE 2: the record must disclose that the whole-repo"
echo "        --regenerate-all sweep's own output is NOT verified"
echo "        by this phase"
echo "==============================================================="
if [[ -z "$REC_SUMMARY" ]]; then
  fail "case2: no Evidence Record to inspect"
elif printf '%s' "$REC_SUMMARY" | grep -qF -- "does NOT verify"; then
  pass "case2: the record discloses the unverified --regenerate-all sweep"
else
  fail "case2: --regenerate-all ran but the record makes no statement about whether its whole-repo output was verified; claim: ${REC_SUMMARY}"
fi

echo
echo "==============================================================="
echo "CASE 3: the default (no --regenerate-all) already-correct run"
echo "        must keep its honest 'nothing-to-regenerate' wording"
echo "==============================================================="
FIX3="${WORKDIR}/case3"; _mkfix "$FIX3" clean; _stub_honest "${FIX3}/scripts/sync-markdown-exports.sh"
_run "$FIX3" "2026-08-25T62-00-00Z"
if [[ "$RC" -eq 0 ]]; then
  pass "case3: exits 0"
else
  fail "case3: expected exit 0, got ${RC}; output: ${RUN_OUT}"
fi
if printf '%s' "$REC_SUMMARY" | grep -qF -- "no --check-only re-verification was performed"; then
  pass "case3: unchanged honest wording for the nothing-to-regenerate path"
else
  fail "case3: expected the nothing-to-regenerate disclosure; claim: ${REC_SUMMARY}"
fi

echo
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CASES PASSED"
  exit 0
fi
echo "${FAILURES} CASE(S) FAILED"
exit 1
