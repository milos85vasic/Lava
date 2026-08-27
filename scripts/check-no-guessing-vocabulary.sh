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

# ---------------------------------------------------------------------------
# §6.J anti-bluff corpus floor (added 2026-08-26, LVA vacuous-pass sweep F14).
#
# `[[ -d "$p" ]] || continue` skipped a missing scan path in silence, so a
# configuration in which NO path resolves produced:
#
#   LAVA_NO_GUESSING_SCAN_PATHS=<nonexistent>  ->  "§11.4.6 ... gate clean."  exit 0
#
# and so did a path that resolves but holds no *.md / *.json. Both are "nothing
# was learned" reported as "nothing failed" — the shape §6.J forbids, and the
# same shape the clause-6.H credential floor (check-constitution.sh:188) guards
# against. The counters below make the corpus size an explicit, reported fact,
# and the two floors after the loop refuse rather than certify an empty scan.
guess_violations=()
scan_paths_resolved=0
scan_files_examined=0
scan_paths_missing=()
for p in "${guess_scan_paths[@]}"; do
  if [[ ! -d "$p" ]]; then
    scan_paths_missing+=("$p")
    continue
  fi
  scan_paths_resolved=$((scan_paths_resolved + 1))
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    scan_files_examined=$((scan_files_examined + 1))
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

# §6.J corpus floors — both run BEFORE any clean verdict can be printed.
if [[ "$scan_paths_resolved" -eq 0 ]]; then
  echo "§6.AD/HelixConstitution §11.4.6 VIOLATION: NO scan path resolved to a directory." >&2
  echo "  → Examined: 0 director(ies), 0 file(s)" >&2
  echo "  → Expected: all ${#guess_scan_paths[@]} configured scan path(s) to exist:" >&2
  printf '      %s  (MISSING)\n' "${scan_paths_missing[@]}" >&2
  if [[ -n "${LAVA_NO_GUESSING_SCAN_PATHS:-}" ]]; then
    echo "  → Cause distinguished: the paths came from LAVA_NO_GUESSING_SCAN_PATHS," >&2
    echo "    so this is a CONFIGURATION error, not a missing corpus." >&2
    echo "  → Do: correct LAVA_NO_GUESSING_SCAN_PATHS (colon-separated, relative to" >&2
    echo "    ${REPO_ROOT}), or unset it to use the built-in defaults." >&2
  else
    echo "  → Cause distinguished: these are the BUILT-IN default paths, so either this" >&2
    echo "    is not a Lava checkout or LAVA_REPO_ROOT points somewhere else" >&2
    echo "    (currently: ${REPO_ROOT})." >&2
    echo "  → Do: run from the Lava repository root and re-run." >&2
  fi
  echo "  → A 'gate clean' verdict over zero directories asserts nothing (§6.J)." >&2
  exit 1
fi

if [[ "$scan_files_examined" -eq 0 ]]; then
  echo "§6.AD/HelixConstitution §11.4.6 VIOLATION: the scan read ZERO files." >&2
  echo "  → Examined: ${scan_paths_resolved} director(ies), 0 file(s) matching *.md / *.json" >&2
  echo "  → Expected: at least 1 file. The directories below resolved but hold nothing" >&2
  echo "    this gate can read:" >&2
  for p in "${guess_scan_paths[@]}"; do
    [[ -d "$p" ]] && echo "      ${p}  (present, 0 matching files)" >&2
  done
  if [[ ${#scan_paths_missing[@]} -gt 0 ]]; then
    echo "    and these did not resolve at all:" >&2
    printf '      %s  (MISSING)\n' "${scan_paths_missing[@]}" >&2
  fi
  echo "  → Do: confirm the forensic-anchor corpus is present" >&2
  echo "    (.lava-ci-evidence/sixth-law-incidents/, .lava-ci-evidence/crashlytics-resolved/)" >&2
  echo "    and re-run; a clean verdict from an empty corpus asserts nothing (§6.J)." >&2
  exit 1
fi

# A resolved-but-partial path set is also reported: the gate's verdict must not
# be a function of which directories happen to exist on this checkout.
if [[ ${#scan_paths_missing[@]} -gt 0 ]]; then
  echo "§6.AD/HelixConstitution §11.4.6 VIOLATION: the scan examined a PARTIAL corpus." >&2
  echo "  → Examined: ${scan_paths_resolved} of ${#guess_scan_paths[@]} configured scan path(s), ${scan_files_examined} file(s)" >&2
  echo "  → Missing (skipped in silence before this floor existed):" >&2
  printf '      %s\n' "${scan_paths_missing[@]}" >&2
  echo "  → A verdict over a subset asserts nothing about the absent paths; the result" >&2
  echo "    would be a function of checkout state rather than of §11.4.6 compliance." >&2
  echo "  → Do: restore the missing path(s), or narrow LAVA_NO_GUESSING_SCAN_PATHS to" >&2
  echo "    exactly the set you intend to audit so the claim matches the corpus." >&2
  exit 1
fi

if [[ ${#guess_violations[@]} -gt 0 ]]; then
  echo "§6.AD/HelixConstitution §11.4.6 VIOLATION: forbidden guessing vocabulary in:" >&2
  printf '    %s\n' "${guess_violations[@]}" >&2
  echo "  → Either prove the cause with captured evidence and state as fact," >&2
  echo "    OR mark the line with UNCONFIRMED: / UNKNOWN: / PENDING_FORENSICS: prefix." >&2
  exit 1
fi

echo "§11.4.6 no-guessing vocabulary gate clean: ${scan_files_examined} file(s) scanned across ${scan_paths_resolved} director(ies)."
