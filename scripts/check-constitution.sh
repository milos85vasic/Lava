#!/usr/bin/env bash
# scripts/check-constitution.sh — verify constitutional clauses present.
#
# Per the SP-3a plan Task 5.19. Asserts that the three SP-3a clauses
# (6.D, 6.E, 6.F) are present in root CLAUDE.md and that the
# Submodules/Tracker-SDK/CLAUDE.md exists. Run from scripts/ci.sh in
# every mode.

set -euo pipefail

cd "$(dirname "$0")/.."

# ---------------------------------------------------------------------
# 1. Root CLAUDE.md MUST contain clauses 6.D, 6.E, 6.F.
# ---------------------------------------------------------------------
required_clauses=(
  "6.D — Behavioral Coverage Contract"
  "6.E — Capability Honesty"
  "6.F — Anti-Bluff Submodule Inheritance"
)
for clause in "${required_clauses[@]}"; do
  if ! grep -qF "$clause" CLAUDE.md; then
    echo "MISSING constitutional clause: $clause" >&2
    echo "  → Add to CLAUDE.md per SP-3a Phase 5 Task 5.1." >&2
    exit 1
  fi
done

# ---------------------------------------------------------------------
# 2. Submodules/Tracker-SDK/CLAUDE.md MUST exist.
# ---------------------------------------------------------------------
if [[ ! -f Submodules/Tracker-SDK/CLAUDE.md ]]; then
  echo "MISSING Submodules/Tracker-SDK/CLAUDE.md" >&2
  echo "  → Restore via 'git submodule update --init Submodules/Tracker-SDK'" >&2
  echo "    or per SP-3a Phase 1 Task 1.7." >&2
  exit 1
fi

# ---------------------------------------------------------------------
# 3. Tracker-SDK constitution MUST reference clauses 6.A-6.F (or the
#    individual clauses) via the Sixth Law inheritance.
# ---------------------------------------------------------------------
if ! grep -qE '6\.A.{0,4}6\.F|6\.A through 6\.E|clauses 6\.A' Submodules/Tracker-SDK/CLAUDE.md; then
  echo "WARN: Submodules/Tracker-SDK/CLAUDE.md does not explicitly cite 6.A-6.F. Verify the Sixth Law inheritance is present and re-run." >&2
fi

# ---------------------------------------------------------------------
# 4. core/CLAUDE.md MUST reference clause 6.E (Capability Honesty).
# ---------------------------------------------------------------------
if ! grep -qE '6\.E|Capability Honesty' core/CLAUDE.md; then
  echo "MISSING reference to 6.E in core/CLAUDE.md" >&2
  echo "  → Add per SP-3a Phase 5 Task 5.2." >&2
  exit 1
fi

# ---------------------------------------------------------------------
# 5. feature/CLAUDE.md MUST reference Challenge Test requirement.
# ---------------------------------------------------------------------
if ! grep -qE 'Challenge Test|SDK-consuming ViewModel' feature/CLAUDE.md; then
  echo "MISSING Challenge Test clause in feature/CLAUDE.md" >&2
  echo "  → Add per SP-3a Phase 5 Task 5.3." >&2
  exit 1
fi

echo "Constitution check passed: 6.D + 6.E + 6.F present in CLAUDE.md;"
echo "Submodules/Tracker-SDK/CLAUDE.md present; core/ + feature/ scoped"
echo "clauses present."
