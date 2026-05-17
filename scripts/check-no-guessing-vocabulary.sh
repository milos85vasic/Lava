#!/usr/bin/env bash
# scripts/check-no-guessing-vocabulary.sh — §6.AD.6 / HelixConstitution §11.4.6
# extracted standalone gate.
#
# Forbidden vocabulary in tracked status / closure / commit-template files
# when describing causes: likely, probably, maybe, might, possibly,
# presumably, seems to, appears to, guess (handled separately as the noun),
# seemingly, apparently, perhaps, supposedly, conjectured.
#
# Whitelist: any line whose match is preceded by an UNCONFIRMED: / UNKNOWN: /
# PENDING_FORENSICS: lead-in passes, AND lines in files that quote historical
# agent/operator output verbatim (forensic-anchor exemption).
#
# Usage:
#   bash scripts/check-no-guessing-vocabulary.sh
#       — scans the default paths (.lava-ci-evidence/sixth-law-incidents/ +
#         .lava-ci-evidence/crashlytics-resolved/)
#   LAVA_NO_GUESSING_SCAN_PATHS="path1:path2" bash scripts/check-no-guessing-vocabulary.sh
#       — overrides the default scan paths (used by the hermetic test)
#   LAVA_REPO_ROOT=/path/to/repo bash scripts/check-no-guessing-vocabulary.sh
#       — overrides repo-root resolution (used by the hermetic test)
#
# Exit codes:
#   0 — gate clean
#   1 — at least one violation found (paths printed to stderr)
#
# §6.J anti-bluff falsifiability rehearsal protocol:
#   1. Add a tracked file under .lava-ci-evidence/sixth-law-incidents/ with
#      a line like: "The root cause likely involves a race condition"
#   2. Run this script. Confirm exit code 1 + the file is listed.
#   3. Add `UNCONFIRMED:` prefix to the line. Re-run. Confirm exit 0.
#   4. Remove the test file.
#
# Classification: project-specific (the gate's content list is universal
# per HelixConstitution §11.4.6 but the scan-path defaults are Lava-specific).

set -euo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

forbidden_guess_words='\b(likely|probably|maybe|might|possibly|presumably|seemingly|apparently|perhaps|supposedly|conjectured)\b|\bseems\s+to\b|\bappears\s+to\b'

if [[ -n "${LAVA_NO_GUESSING_SCAN_PATHS:-}" ]]; then
  IFS=':' read -ra guess_scan_paths <<< "$LAVA_NO_GUESSING_SCAN_PATHS"
else
  guess_scan_paths=(
    ".lava-ci-evidence/sixth-law-incidents"
    ".lava-ci-evidence/crashlytics-resolved"
  )
fi

guess_violations=()
for p in "${guess_scan_paths[@]}"; do
  [[ -d "$p" ]] || continue
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    # Skip the line if it begins with an UNCONFIRMED:/UNKNOWN:/PENDING_FORENSICS:
    # lead OR if the file is a forensic anchor that quotes historical agent
    # output verbatim (exempt via grep -v on stable markers).
    if grep -ihnE "$forbidden_guess_words" "$f" 2>/dev/null | \
       grep -ivE '\b(UNCONFIRMED|UNKNOWN|PENDING_FORENSICS):' | \
       grep -ivE 'forensic[[:space:]]+anchor|verbatim[[:space:]]+(operator|agent|user)|historical[[:space:]]+quote' | \
       head -3 | grep -q .; then
      guess_violations+=("$f")
    fi
  done < <(find "$p" -type f \( -name '*.md' -o -name '*.json' \))
done

if [[ ${#guess_violations[@]} -gt 0 ]]; then
  echo "§6.AD/HelixConstitution §11.4.6 VIOLATION: forbidden guessing vocabulary in:" >&2
  printf '    %s\n' "${guess_violations[@]}" >&2
  echo "  → Either prove the cause with captured evidence and state as fact," >&2
  echo "    OR mark the line with UNCONFIRMED: / UNKNOWN: / PENDING_FORENSICS: prefix." >&2
  exit 1
fi

echo "§11.4.6 no-guessing vocabulary gate clean."
