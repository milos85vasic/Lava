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
#   .lava-ci-evidence/sixth-law-incidents/      — forensic anchors
#   docs/superpowers/specs/*.md                 — design docs
#   docs/superpowers/plans/*.md                 — implementation plans
#   *_test.go, *Test.kt, *Tests.kt, *Test.java  — synthetic test fixtures
#   the IETF "nil" UUID 00000000-0000-0000-0000-000000000000 — the canonical
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
uuid_violations=$(
  git ls-files -z \
    | grep -zvE '^\.env\.example$|^\.lava-ci-evidence/sixth-law-incidents/|^docs/superpowers/(specs|plans)/|_test\.go$|(Test\.kt|Tests\.kt|Test\.java)$|\.db$' \
    | while IFS= read -r -d '' p; do
        [[ -f "$p" ]] || continue
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

exit 0
