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

if [[ ! -d "scripts" ]] || [[ ! -d "docs/scripts" ]]; then
  echo "CM-SCRIPT-DOCS-SYNC: skipping — scripts/ or docs/scripts/ missing in $REPO_ROOT" >&2
  exit 0
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

echo "CM-SCRIPT-DOCS-SYNC gate clean: ${#scripts_present[@]} scripts ↔ ${#docs_present[@]} docs (1:1)."
