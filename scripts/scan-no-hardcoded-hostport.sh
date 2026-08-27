#!/usr/bin/env bash
# scripts/scan-no-hardcoded-hostport.sh — standalone §6.R host:port scanner.
#
# Purpose: enforce the §6.R No-Hardcoding Mandate clause that no
# `host:port` literals in URLs appear in tracked source outside the
# exemption set. §4.5.10 (CONTINUATION.md): staged enforcement — this
# is the mechanical gate that lands the rule.
#
# Exit codes:
#   0 — no host:port violations
#   1 — host:port violation(s) found (paths printed to stderr)
#
# Pattern: `<scheme>://<host>:<numeric-port>` where scheme is one of
# http/https/ws/wss and the port is 2-5 digits. Matching only URL-shaped
# literals avoids false positives on `Map<String, Int>` declarations or
# `key:value` JSON snippets in comments.
#
# Exemptions (lockstep with §6.R clause body):
#   .env.example, .lava-ci-evidence/, submodules/, tests, fixtures/,
#   CHANGELOG.md, *.md, *.json, *.xml, *.yml, *.yaml — external config
#   and docs are legitimate homes for these literals.
#
# Comment-stripping: a host:port literal that appears ONLY inside a code
# comment / KDoc / example string is documentation, not a hardcoded
# connection address. Before the host:port regex is applied, each
# candidate line has its line-comment content removed:
#   - Kotlin/Java/Go/C `//…` to EOL — BUT NOT a `scheme://` (the `//` of
#     `http://` is matched only when NOT preceded by `:`, so a REAL
#     hardcoded literal in code that has a trailing `// note` keeps the
#     literal and drops only the note). A naive strip-from-first-`//`
#     would truncate `http://host:port` itself and silently let real
#     hardcoded literals pass — that would be the exact §6.R bluff this
#     scanner exists to evict, so it is deliberately avoided.
#   - shell/yaml/python `#…` to EOL, but only when the `#` is at line
#     start or preceded by whitespace (` # comment`). This preserves
#     shell parameter-expansion (`${VAR#pattern}`) and URL fragments
#     (`http://h/p#frag`), which are real code, not comments.
#   - block-comment `/* … */` spans and KDoc continuation lines
#     (leading-whitespace `* …`).
# This mirrors the established sibling-scanner intent (UUID/IPv4) of
# matching only real code, while NOT weakening detection of REAL
# hardcoded host:port literals on non-comment lines.
#
# Loopback hosts (localhost / 127.x.x.x / 0.0.0.0) plus the
# container-runtime host-loopback aliases (host.containers.internal /
# host.docker.internal — the in-container equivalents of localhost used
# by rootless podman / docker) are filtered AFTER the file-level
# exemption — those are not "hardcoded connection addresses" in the §6.R
# sense (they resolve only to the local host).

set -euo pipefail

cd "$(dirname "$0")/.."

# strip_comments — read lines on stdin, emit each with its line-comment
# content removed (see the comment-stripping note above). Uses `@` as the
# sed delimiter so the literal `#`-comment rule does not collide with the
# sed command separator.
strip_comments() {
  sed -E \
    -e 's@/\*.*\*/@@g' \
    -e 's@([^:])//.*@\1@' \
    -e 's@^([[:space:]]*)//.*@\1@' \
    -e 's@^([[:space:]]*)\*.*@\1@' \
    -e 's@(^|[[:space:]])#.*@\1@'
}


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

grep -zvE '^\.env\.example$|^\.lava-ci-evidence/|^submodules/|^lava-api-go/third_party/modernc-libc/|^tests/|_test\.go$|(Test\.kt|Tests\.kt|Test\.java)$|/test/|/androidTest/|fixtures/|^CHANGELOG\.md$|\.md$|\.json$|\.xml$|\.yml$|\.yaml$|\.html$|\.pdf$|\.docx$|\.db$' <"$__scan_tmp/raw" >"$__scan_tmp/corpus" || true

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

candidates=$(
  for p in "${SCAN_FILES[@]}"; do
        # grep with line numbers, then strip comment content from the line
        # body and re-match. Output shape stays `path:lineno:content` so the
        # downstream loopback filter + violation report are unchanged.
        grep -nE '(http|https|ws|wss)://[a-zA-Z0-9.-]+:[0-9]{2,5}\b' "$p" 2>/dev/null \
          | while IFS= read -r hit; do
              lineno="${hit%%:*}"
              body="${hit#*:}"
              stripped="$(printf '%s' "$body" | strip_comments)"
              if printf '%s' "$stripped" \
                   | grep -qE '(http|https|ws|wss)://[a-zA-Z0-9.-]+:[0-9]{2,5}\b'; then
                printf '%s:%s:%s\n' "$p" "$lineno" "$stripped"
              fi
            done
  done \
    || true
)

# Filter loopback + container-runtime host-loopback aliases (all
# universally permitted; they resolve only to the local host).
violations=$(
  printf '%s\n' "$candidates" \
    | grep -vE '(http|https|ws|wss)://(localhost|127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|0\.0\.0\.0|host\.containers\.internal|host\.docker\.internal)' \
    || true
)

if [[ -n "$violations" ]]; then
  echo "6.R VIOLATION: hardcoded host:port literals in tracked source:" >&2
  echo "$violations" >&2
  echo "  → Move to .env (gitignored) or a JSON config file; read via config layer." >&2
  exit 1
fi

echo "6.R host:port scan clean: ${#SCAN_FILES[@]} tracked file(s) examined.${__corpus_note}" >&2
exit 0
