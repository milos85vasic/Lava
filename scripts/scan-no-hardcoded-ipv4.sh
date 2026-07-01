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

candidates=$(
  git ls-files -z \
    | grep -zvE '^\.env\.example$|^\.lava-ci-evidence/|^submodules/|^tests/|_test\.go$|(Test\.kt|Tests\.kt|Test\.java)$|/test/|/androidTest/|fixtures/|^CHANGELOG\.md$|\.md$|\.json$|\.xml$|\.yml$|\.yaml$|\.html$|\.pdf$|\.docx$|\.db$' \
    | while IFS= read -r -d '' p; do
        [[ -f "$p" ]] && printf '%s\0' "$p"
      done \
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

exit 0
