#!/usr/bin/env bash
# scripts/check-script-docs-sync.sh — CM-SCRIPT-DOCS-SYNC gate per
# HelixConstitution §11.4.18 + Lava §6.AD-debt closure.
#
# Verifies bidirectional drift between:
#   - scripts/*.sh
#   - docs/scripts/*.sh.md
#
# Every script MUST have a matching doc file and vice versa. Orphan
# scripts (no doc) indicate undocumented capability. Orphan docs (no
# script) indicate stale references to removed scripts.
#
# Usage:
#   bash scripts/check-script-docs-sync.sh
#   LAVA_REPO_ROOT=/path/to/repo bash scripts/check-script-docs-sync.sh
#
# Exit codes:
#   0 — drift-free
#   1 — at least one orphan (paths printed to stderr)
#
# Classification: project-specific (the convention is universal per
# HelixConstitution §11.4.18; the path layout is Lava-specific).

set -euo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

# ---------------------------------------------------------------------------
# §6.J anti-bluff corpus floor (added 2026-08-26, LVA vacuous-pass sweep F12).
#
# Two empty-corpus routes used to report success here:
#   both directories absent   -> "skipping — scripts/ or docs/scripts/ missing"  exit 0
#   both directories empty    -> "gate clean: 0 scripts <-> 0 docs (1:1)."       exit 0
#
# The second is the sharper bluff: 0 == 0 satisfies the 1:1 invariant perfectly,
# so the gate prints a positive verdict having compared nothing. "Nothing was
# learned" reported as "nothing failed" is the shape §6.J forbids — the same
# shape the clause-6.H credential floor (check-constitution.sh:188) already
# guards against.
#
# The expectation is DERIVED from the git index, not hardcoded: a literal
# count goes stale the moment a script is added or removed, and a stale floor
# is this same defect wearing a different mask. awk, not `grep -c`, because
# `grep -c` exits 1 on a zero count and under `set -e` in a pipeline that is
# its own hazard.
# The `|| true` inside the braces is deliberate: `git ls-files` exits 128
# outside a repository, and under `set -euo pipefail` that would abort this
# script with NO message at all — fail-closed, but with a diagnosis so empty it
# sends the reader nowhere. Degrading to a declared count of 0 lets the
# not-a-checkout branch below say what actually happened.
declared_scripts="$(
  { git ls-files -- scripts 2>/dev/null || true; } |
  awk -F/ 'NF==2 && /\.sh$/{n++} END{print n+0}'
)"
declared_docs="$(
  { git ls-files -- docs/scripts 2>/dev/null || true; } |
  awk -F/ 'NF==3 && /\.sh\.md$/{n++} END{print n+0}'
)"

if [[ ! -d "scripts" ]] || [[ ! -d "docs/scripts" ]]; then
  echo "CM-SCRIPT-DOCS-SYNC / HelixConstitution §11.4.18 VIOLATION: a corpus directory is ABSENT." >&2
  echo "  → Examined: 0 scripts, 0 docs (in $REPO_ROOT)" >&2
  echo "      scripts/      $([[ -d scripts ]] && echo present || echo MISSING)" >&2
  echo "      docs/scripts/ $([[ -d docs/scripts ]] && echo present || echo MISSING)" >&2
  echo "  → Expected: ${declared_scripts} script(s) and ${declared_docs} doc(s), derived from 'git ls-files'." >&2
  if [[ "$declared_scripts" -gt 0 || "$declared_docs" -gt 0 ]]; then
    echo "  → Cause distinguished: the git index DECLARES these paths, so a missing directory" >&2
    echo "    is working-tree drift (a deletion or a partial checkout), not an absent convention." >&2
    echo "  → Do: 'git checkout -- scripts docs/scripts' and re-run." >&2
  else
    echo "  → Cause distinguished: the git index declares neither, so this is not a Lava" >&2
    echo "    checkout or LAVA_REPO_ROOT points somewhere else." >&2
    echo "  → Do: set LAVA_REPO_ROOT to the Lava repository root and re-run." >&2
  fi
  echo "  → Skipping and exiting 0 here would report a 1:1 sync that was never checked." >&2
  exit 1
fi

# Build the canonical "expected" lists.
# Note: `find -printf` is GNU-only; this script supports BSD find (macOS)
# via `basename` on each path so it works on both gate-hosts.
mapfile -t scripts_present < <(find scripts -maxdepth 1 -name '*.sh' -type f 2>/dev/null | xargs -n1 basename | sort)
mapfile -t docs_present < <(find docs/scripts -maxdepth 1 -name '*.sh.md' -type f 2>/dev/null | xargs -n1 basename | sed 's|\.md$||' | sort)

orphan_scripts=()
orphan_docs=()

# Scripts without docs
for s in "${scripts_present[@]}"; do
  found=false
  for d in "${docs_present[@]}"; do
    if [[ "$s" == "$d" ]]; then
      found=true
      break
    fi
  done
  if [[ "$found" == "false" ]]; then
    orphan_scripts+=("scripts/$s")
  fi
done

# Docs without scripts
for d in "${docs_present[@]}"; do
  found=false
  for s in "${scripts_present[@]}"; do
    if [[ "$d" == "$s" ]]; then
      found=true
      break
    fi
  done
  if [[ "$found" == "false" ]]; then
    orphan_docs+=("docs/scripts/${d}.md")
  fi
done

violations=$((${#orphan_scripts[@]} + ${#orphan_docs[@]}))

if [[ $violations -gt 0 ]]; then
  echo "CM-SCRIPT-DOCS-SYNC / HelixConstitution §11.4.18 VIOLATION:" >&2
  if [[ ${#orphan_scripts[@]} -gt 0 ]]; then
    echo "  Scripts WITHOUT matching docs/scripts/<name>.md:" >&2
    printf '    %s\n' "${orphan_scripts[@]}" >&2
    echo "  → Add the missing user guide before pushing." >&2
  fi
  if [[ ${#orphan_docs[@]} -gt 0 ]]; then
    echo "  Docs WITHOUT matching scripts/<name>:" >&2
    printf '    %s\n' "${orphan_docs[@]}" >&2
    echo "  → Either restore the deleted script or remove the stale doc." >&2
  fi
  exit 1
fi

# §6.J anti-bluff corpus floor, part 2 (LVA vacuous-pass sweep F12). 0 == 0
# satisfies the 1:1 invariant vacuously; this runs before the clean verdict.
if [[ ${#scripts_present[@]} -eq 0 && ${#docs_present[@]} -eq 0 ]]; then
  echo "CM-SCRIPT-DOCS-SYNC / HelixConstitution §11.4.18 VIOLATION: the gate compared ZERO scripts against ZERO docs." >&2
  echo "  → Examined: 0 scripts under scripts/, 0 docs under docs/scripts/" >&2
  echo "  → Expected: ${declared_scripts} script(s) and ${declared_docs} doc(s), derived from 'git ls-files'." >&2
  if [[ "$declared_scripts" -gt 0 || "$declared_docs" -gt 0 ]]; then
    echo "  → Cause distinguished: the git index declares them, so the working tree has drifted." >&2
    echo "  → Do: 'git checkout -- scripts docs/scripts' and re-run." >&2
  else
    echo "  → Cause distinguished: the git index declares none either — wrong root, or not a" >&2
    echo "    Lava checkout." >&2
    echo "  → Do: run from the Lava repository root and re-run." >&2
  fi
  echo "  → '0 scripts ↔ 0 docs (1:1)' is vacuously true and asserts nothing (§6.J)." >&2
  exit 1
fi

# Partial-corpus floor. A floor that only fires at exactly zero is a floor with
# one stair: 2 of 60 scripts present passes as cleanly as 60 of 60.
if [[ "$declared_scripts" -gt 0 && ${#scripts_present[@]} -lt "$declared_scripts" ]] ||
   [[ "$declared_docs" -gt 0 && ${#docs_present[@]} -lt "$declared_docs" ]]; then
  echo "CM-SCRIPT-DOCS-SYNC / HelixConstitution §11.4.18 VIOLATION: the gate examined a PARTIAL corpus." >&2
  echo "  → Examined: ${#scripts_present[@]} script(s) and ${#docs_present[@]} doc(s)" >&2
  echo "  → Expected: ${declared_scripts} script(s) and ${declared_docs} doc(s), derived from 'git ls-files'." >&2
  echo "  → Missing from the working tree:" >&2
  { git ls-files -- scripts docs/scripts 2>/dev/null || true; } |
    awk -F/ '(NF==2 && /\.sh$/) || (NF==3 && /\.sh\.md$/){print}' |
    while read -r _decl; do
      [[ -f "$_decl" ]] && continue
      echo "      ${_decl}" >&2
    done
  echo "  → A 1:1 verdict over a subset asserts nothing about the absent members; the" >&2
  echo "    result would be a function of checkout state rather than of §11.4.18 compliance." >&2
  echo "  → Do: 'git checkout -- scripts docs/scripts' and re-run." >&2
  exit 1
fi

echo "CM-SCRIPT-DOCS-SYNC gate clean: ${#scripts_present[@]} scripts ↔ ${#docs_present[@]} docs (1:1) — corpus floor ${declared_scripts}/${declared_docs} (git-index-derived) satisfied."
