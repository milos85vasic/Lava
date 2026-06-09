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

candidates=$(
  git ls-files -z \
    | grep -zvE '^\.env\.example$|^\.lava-ci-evidence/|^submodules/|^tests/|_test\.go$|(Test\.kt|Tests\.kt|Test\.java)$|/test/|/androidTest/|fixtures/|^CHANGELOG\.md$|\.md$|\.json$|\.xml$|\.yml$|\.yaml$|\.html$|\.pdf$|\.docx$|\.db$' \
    | while IFS= read -r -d '' p; do
        [[ -f "$p" ]] || continue
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

exit 0
