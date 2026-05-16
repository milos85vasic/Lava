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
# Loopback hosts (localhost / 127.x.x.x / 0.0.0.0) are filtered AFTER
# the file-level exemption — those are not "hardcoded connection
# addresses" in the §6.R sense.

set -euo pipefail

cd "$(dirname "$0")/.."

candidates=$(
  git ls-files -z \
    | grep -zvE '^\.env\.example$|^\.lava-ci-evidence/|^submodules/|_test\.go$|(Test\.kt|Tests\.kt|Test\.java)$|/test/|/androidTest/|fixtures/|^CHANGELOG\.md$|\.md$|\.json$|\.xml$|\.yml$|\.yaml$' \
    | while IFS= read -r -d '' p; do
        [[ -f "$p" ]] && printf '%s\0' "$p"
      done \
    | xargs -0 -r grep -nE '(http|https|ws|wss)://[a-zA-Z0-9.-]+:[0-9]{2,5}\b' 2>/dev/null \
    || true
)

# Filter loopback hosts (universally permitted).
violations=$(
  printf '%s\n' "$candidates" \
    | grep -vE '(http|https|ws|wss)://(localhost|127\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|0\.0\.0\.0)' \
    || true
)

if [[ -n "$violations" ]]; then
  echo "6.R VIOLATION: hardcoded host:port literals in tracked source:" >&2
  echo "$violations" >&2
  echo "  → Move to .env (gitignored) or a JSON config file; read via config layer." >&2
  exit 1
fi

exit 0
