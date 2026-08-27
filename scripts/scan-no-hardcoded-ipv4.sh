#!/usr/bin/env bash
# scripts/scan-no-hardcoded-ipv4.sh — standalone §6.R IPv4 scanner.
#
# Purpose: enforce the §6.R No-Hardcoding Mandate clause that no IPv4
# literals appear in tracked source outside the exemption set.
# §4.5.10 (CONTINUATION.md): the IPv4 enforcement is staged — this
# scanner is the mechanical gate that lands the rule.
#
# Exit codes:
#   0 — no IPv4 violations
#   1 — IPv4 violation(s) found (paths printed to stderr)
#
# Exemptions (kept in lockstep with the §6.R clause body):
#   .env.example                                — placeholder file
#   .lava-ci-evidence/                          — forensic anchors + matrix evidence
#   docs/**/*.md                                — design docs, plans, incident notes
#   submodules/                                 — submodules vendored at pinned hash
#   *_test.go, *Test.kt, *Tests.kt, *Test.java  — synthetic test fixtures
#   src/test/, src/androidTest/                 — test source roots
#   fixtures/                                   — test HTML/JSON fixtures
#   CHANGELOG.md                                — release notes may reference IPs in incident summaries
#   *.md, *.json, *.xml, *.yml, *.yaml          — external config + docs are legitimate
#                                                 home for connection literals (Android
#                                                 network_security_config.xml whitelists LAN
#                                                 ranges, Grafana provisioning .yml lists service
#                                                 endpoints, etc.). Code that READS these files
#                                                 is what §6.R targets.
#
# Loopback / docs-prefix IPs that are universally permitted (RFC 5737,
# documentation-only) are filtered AFTER the file-level exemption so a
# code file that legitimately uses 127.0.0.1 or 0.0.0.0 still passes —
# those are not "hardcoded connection addresses" in the §6.R sense
# (they cannot connect to anything but the local host or the wildcard).
#
# Narrow path+range exemption — Android-emulator QEMU user-mode (slirp)
# constants in the 10.0.2.0/24 range inside autonomous-qa emulator helper
# scripts (scripts/autonomous-qa/*emulator*.sh). Within that /24 the helper
# uses host .2 (host-loopback alias / slirp gateway), DNS .3 (the emulator's
# built-in DNS server), and guest .15 (the guest NIC address). These are
# platform-FIXED by the Android emulator's slirp stack — NOT configurable,
# cannot drift (Google: developer.android.com/studio/run/emulator-networking).
# They are the emulator equivalent of 127.0.0.1, not a deployment address — so
# they belong in the universally-permitted set, but only the slirp /24 and only
# inside the autonomous-qa emulator helper(s). The exemption is BOTH path-scoped
# (the autonomous-qa emulator helper) AND range-scoped (10.0.2.0/24): a routable
# IP in those same files, or any 10.0.2.x literal in any other tracked file, is
# still flagged. It is a line-removal filter, identical in mechanism to the
# reserved-address filter above. (The octet suffixes .2/.3/.15 are written here
# without a bare dotted-quad so this comment does not trip the scanner's own
# self-scan — same self-safe idiom as the escaped-dot reserved patterns below.)
# Kept in lockstep with the §6.R clause body (test fixtures + exemption ledger).

set -euo pipefail

cd "$(dirname "$0")/.."


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
  printf '%s\0' "${SCAN_FILES[@]}" \
    | xargs -0 -r grep -nE '(^|[^/.0-9a-zA-Z])([0-9]{1,3}\.){3}[0-9]{1,3}([^/.0-9a-zA-Z]|$)' 2>/dev/null \
    || true
)

# Filter out universally-permitted reserved addresses:
#   127.0.0.0/8   loopback
#   0.0.0.0       wildcard bind
#   255.255.255.255  broadcast
#   192.0.2.x / 198.51.100.x / 203.0.113.x  RFC 5737 documentation
violations=$(
  printf '%s\n' "$candidates" \
    | grep -vE '\b(127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|0\.0\.0\.0|255\.255\.255\.255|192\.0\.2\.[0-9]{1,3}|198\.51\.100\.[0-9]{1,3}|203\.0\.113\.[0-9]{1,3})\b' \
    | grep -vE '^scripts/autonomous-qa/[^:]*emulator[^:]*\.sh:[0-9]+:.*\b10\.0\.2\.[0-9]{1,3}\b' \
    || true
)

if [[ -n "$violations" ]]; then
  echo "6.R VIOLATION: hardcoded IPv4 literals in tracked source:" >&2
  echo "$violations" >&2
  echo "  → Move to .env (gitignored) or a JSON config file; read via config layer." >&2
  exit 1
fi

echo "6.R IPv4 scan clean: ${#SCAN_FILES[@]} tracked file(s) examined.${__corpus_note}" >&2
exit 0
