#!/usr/bin/env bash
# scripts/scan-no-hardcoded-uuid.sh — standalone §6.R UUID scanner.
#
# Purpose: enforce the §6.R No-Hardcoding Mandate clause that no 36-char
# UUIDs appear in tracked source outside the exemption set. Extracted as
# a standalone script so the hermetic test suite can invoke ONLY this
# rule (without piggy-backing on the broader check-constitution.sh and
# its silent-PASS fall-through bluff). The main checker delegates here;
# tests/check-constitution/test_no_hardcoded_uuid.sh delegates here.
#
# Exit codes:
#   0 — no UUID violations
#   1 — UUID violation(s) found (paths printed to stderr)
#
# Exemptions (kept in lockstep with the §6.R clause body):
#   .env.example                                — placeholder file
#   .lava-ci-evidence/                          — ALL evidence/gate artifacts
#       (test logs, gradle logs, test reports, device-serial TSVs, forensic
#       anchors, gate verdicts — every artifact under this tree). UUIDs in
#       evidence files come from build tools / test frameworks / device serials,
#       NEVER from handwritten production code. Broad exemption prevents
#       per-file-extension tracking drift; §6.Z/§6.AK content-proof gates
#       prevent counterfeiting of evidence. Supersedes the previous narrower
#       exemptions for sixth-law-incidents/ and running-devices.tsv.
#   docs/superpowers/specs/*.md                 — design docs
#   docs/superpowers/plans/*.md                 — implementation plans
#   *_test.go, *Test.kt, *Tests.kt, *Test.java  — synthetic test fixtures
#   lava-api-go/third_party/modernc-libc/       — generated/vendored upstream
#     libc syscall tables (not Lava-authored; literals originate in the upstream
#     modernc.org/libc generator)
#   the IETF "nil" UUID <vm-instance-uuid-redacted> — the canonical
#     "no UUID" sentinel/placeholder (RFC 4122 §4.1.7); semantically an empty
#     value, never a real production identifier (cf. the .env.example exemption).

set -euo pipefail

cd "$(dirname "$0")/.."

# NUL-delimited pipeline so paths with whitespace (the matrix-evidence
# test reports under .lava-ci-evidence/Lava-Android-1.2.3-1023/matrix/.../
# carry "Android SDK built for x86_64 - 9-_app-.xml" style filenames) are
# preserved end-to-end. The previous `xargs` form split on whitespace by
# default and silently dropped any whitespace-pathed file — a UUID hidden
# in such a file would have been missed, which is the exact §6.R bluff
# vector this scanner is supposed to evict.
#
# After the exemption filter, a `read -d ''` while-loop drops non-file
# entries (submodule gitlinks appear in `git ls-files` as bare paths and
# would print "Is a directory" through grep; broken symlinks similarly
# error out). Doing this explicitly avoids relying on `2>/dev/null` to
# silence what would otherwise be real diagnostic output.

# ---------------------------------------------------------------------------
# §6.J anti-bluff corpus floor (added 2026-08-26, LVA vacuous-pass sweep B2).
#
# The `git ls-files -z | ... | while ...` enumeration is terminated by `|| true`
# and nothing asserted that any file was actually read. A broken corpus was
# therefore byte-identical to a clean scan — these scanners print nothing on
# success, so there was no signal either way:
#
#   GIT_DIR=/nonexistent/x.git  ->  uuid exit=0   ipv4 exit=0   hostport exit=0
#   control (normal env)        ->  uuid exit=0   ipv4 exit=0   hostport exit=0
#
# and a fixture with real violations of all three rules, in a directory that is
# not a git repository, passes all three; `git init` on the same tree makes all
# three fail. Realistic triggers: index.lock contention, a corrupt index, a
# `safe.directory` dubious-ownership refusal (this repo lives on an external
# mount, where that refusal is a known class), or a stray GIT_DIR.
#
# The expectation is DERIVED from the git index and from .gitmodules — never a
# hardcoded file count, which would go stale on the next commit and be this
# same defect wearing a different mask. Entries that survive the exemption
# filter but are not regular files are classified rather than dropped: a
# declared submodule path is an EXPECTED gitlink, anything else is real drift.
__scan_tmp="$(mktemp -d)"
trap 'rm -rf "$__scan_tmp"' EXIT

__gls_rc=0
git ls-files -z >"$__scan_tmp/raw" 2>"$__scan_tmp/err" || __gls_rc=$?
if [[ "$__gls_rc" -ne 0 ]]; then
  echo "6.R SCAN FAILED: could not enumerate the tracked-file corpus." >&2
  echo "  → Examined: 0 files; 'git ls-files' exited ${__gls_rc}." >&2
  echo "  → Cause distinguished: this is NOT 'no violations found'. The corpus" >&2
  echo "    enumeration itself failed, so a clean exit would assert nothing." >&2
  sed 's/^/      /' "$__scan_tmp/err" >&2
  echo "  → Do: run from inside the Lava repository (cwd is $(pwd)); if this checkout" >&2
  echo "    lives on an external mount, git may be refusing it for dubious ownership" >&2
  echo "    — check 'git status' first, and confirm GIT_DIR is not set to a stale path." >&2
  exit 2
fi

# Declared submodule paths — legitimately not regular files (gitlinks).
declare -A __gitlink_paths=()
while read -r __decl; do
  [[ -n "$__decl" ]] && __gitlink_paths["$__decl"]=1
done < <(sed -n 's/^[[:space:]]*path = //p' .gitmodules 2>/dev/null)

# Paths git ITSELF reports as deleted from the working tree. These are a
# legitimate mid-workflow state (a pending `git rm`, an unstaged deletion), they
# are already visible to the operator through `git status`, and a deleted file
# has no content that could hold a violation. They are therefore EXCLUDED from
# the expectation rather than treated as drift — and they are NAMED in the clean
# verdict, so the scanner's claim always states the corpus it actually read.
# The distinction that matters: absent-and-reported-by-git is a deletion in
# progress; absent-and-NOT-reported-by-git is invisible drift, and only the
# second is a refusal. Calling the first "working-tree drift" would be a
# diagnosis that misstates its cause and sends the reader to the wrong remedy.
declare -A __deleted_paths=()
while IFS= read -r -d '' __del; do
  [[ -n "$__del" ]] && __deleted_paths["$__del"]=1
done < <(git ls-files -z --deleted 2>/dev/null || true)

grep -zvE '^\.env\.example$|^\.lava-ci-evidence/|^docs/superpowers/(specs|plans)/|_test\.go$|(Test\.kt|Tests\.kt|Test\.java)$|\.db$|^lava-api-go/third_party/modernc-libc/' <"$__scan_tmp/raw" >"$__scan_tmp/corpus" || true

SCAN_FILES=()
__unreadable=()
__pending_deletions=()
__declared=0
while IFS= read -r -d '' __p; do
  [[ -z "$__p" ]] && continue
  __declared=$((__declared + 1))
  if [[ -f "$__p" ]]; then
    SCAN_FILES+=("$__p")
  elif [[ -n "${__gitlink_paths[$__p]:-}" ]] || [[ -d "$__p" ]]; then
    :   # expected: a submodule gitlink, not a file this scanner can read
  elif [[ -n "${__deleted_paths[$__p]:-}" ]]; then
    __pending_deletions+=("$__p")
  else
    __unreadable+=("$__p")
  fi
done <"$__scan_tmp/corpus"

if [[ ${#SCAN_FILES[@]} -eq 0 ]]; then
  echo "6.R SCAN FAILED: the scan examined ZERO files." >&2
  echo "  → Examined: 0 of ${__declared} tracked path(s) surviving the exemption filter" >&2
  echo "  → Expected: at least 1. A clean exit over an empty corpus asserts nothing," >&2
  echo "    and this scanner prints nothing on success — so the empty case is" >&2
  echo "    indistinguishable from a real pass unless it refuses here (§6.J)." >&2
  if [[ "$__declared" -eq 0 ]]; then
    echo "  → Cause distinguished: 'git ls-files' succeeded but the exemption filter" >&2
    echo "    left nothing. Either this is not the Lava tree, or the filter regex has" >&2
    echo "    drifted to exclude everything." >&2
  else
    echo "  → Cause distinguished: ${__declared} path(s) were listed but none is a readable" >&2
    echo "    regular file — working-tree drift, not an empty repository." >&2
  fi
  echo "  → Do: confirm you are at the Lava repository root and the working tree is" >&2
  echo "    checked out, then re-run." >&2
  exit 2
fi

if [[ ${#__unreadable[@]} -gt 0 ]]; then
  echo "6.R SCAN INCOMPLETE: the scan examined a PARTIAL corpus." >&2
  echo "  → Examined: ${#SCAN_FILES[@]} file(s) of ${__declared} tracked path(s) after exemptions" >&2
  echo "  → Tracked, but NOT a readable regular file, NOT a declared submodule gitlink," >&2
  echo "    and NOT reported deleted by git. That combination is invisible drift: no" >&2
  echo "    other gate will surface it, and this scanner would silently skip it:" >&2
  printf '      %s\n' "${__unreadable[@]}" >&2
  echo "  → A clean verdict over a subset asserts nothing about the absent files." >&2
  echo "  → Do: git checkout -- <path> to restore them, then re-run." >&2
  exit 2
fi

__corpus_note=""
if [[ ${#__pending_deletions[@]} -gt 0 ]]; then
  __corpus_note=" (${#__pending_deletions[@]} tracked path(s) excluded: git reports them deleted from the working tree — a pending deletion, already visible in 'git status'; a deleted file holds no content to scan)"
fi

uuid_violations=$(
  for p in "${SCAN_FILES[@]}"; do
    # A file is a violation iff it contains a UUID that is NOT the IETF
    # nil UUID. Extract every UUID, drop the nil sentinel, and flag the
    # file only if a real (non-nil) UUID remains.
    if grep -oE '\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b' "$p" 2>/dev/null \
         | grep -qvE '^0{8}-0{4}-0{4}-0{4}-0{12}$'; then
      printf '%s\n' "$p"
    fi
  done \
    || true
)

if [[ -n "$uuid_violations" ]]; then
  echo "6.R VIOLATION: hardcoded UUIDs in tracked source:" >&2
  echo "$uuid_violations" >&2
  echo "  → Move to .env (gitignored); read via config layer." >&2
  exit 1
fi

echo "6.R UUID scan clean: ${#SCAN_FILES[@]} tracked file(s) examined.${__corpus_note}" >&2
exit 0
