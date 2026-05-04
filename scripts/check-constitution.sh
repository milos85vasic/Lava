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

# ---------------------------------------------------------------------
# 6. Credential pattern scan (constitutional clause 6.H clause 5).
#
# Scan tracked files for credential strings that should never appear
# in source. Patterns target the KDoc-claims-placeholder bluff variant
# (see .lava-ci-evidence/sixth-law-incidents/2026-05-04-bridge-credentials.json)
# plus the obvious literal-credential cases. Excludes: .env.example
# (intentional template), CHANGELOG.md (may document historical incidents),
# .lava-ci-evidence/ (forensic records of past leaks), this script
# itself, and CLAUDE.md/AGENTS.md (which document the patterns themselves).
# ---------------------------------------------------------------------
forbidden_credential_patterns=(
  # The C2 bluff shape: a "private object *Bridge" containing string constants
  # is the canonical placeholder-claiming-to-be-placeholder anti-pattern.
  'private[[:space:]]+object[[:space:]]+[A-Za-z_]*Bridge[[:space:]]*\{'
  # Literal credential string assignments in source
  '(RUTRACKER|KINOZAL|NNMCLUB|IPTORRENTS)_(USERNAME|PASSWORD)[[:space:]]*[:=][[:space:]]*"[^"$][^"]*"'
)

mapfile -t tracked_files < <(
  git ls-files 2>/dev/null |
  grep -vE '^\.env\.example$|^CHANGELOG\.md$|^\.lava-ci-evidence/|^scripts/check-constitution\.sh$|^docs/INCIDENT_|^CLAUDE\.md$|^AGENTS\.md$|^lava-api-go/AGENTS\.md$|^lava-api-go/CLAUDE\.md$|^lava-api-go/CONSTITUTION\.md$' || true
)

credential_violations=0
for pat in "${forbidden_credential_patterns[@]}"; do
  for f in "${tracked_files[@]}"; do
    [[ -f "$f" ]] || continue
    if grep -nIE "$pat" "$f" 2>/dev/null; then
      echo "CREDENTIAL PATTERN /$pat/ found in $f — clause 6.H violation." >&2
      credential_violations=$((credential_violations + 1))
    fi
  done
done

if [[ $credential_violations -gt 0 ]]; then
  echo "" >&2
  echo "Constitutional clause 6.H violation: $credential_violations credential" >&2
  echo "pattern hit(s) in tracked files. Move credentials to gitignored .env" >&2
  echo "and read them at runtime via build-time injection (e.g. buildConfigField)." >&2
  echo "" >&2
  echo "If a hit is a false positive (e.g. a regex example in a comment or" >&2
  echo "this script's own source), refactor the file or extend the exclusion" >&2
  echo "list in scripts/check-constitution.sh — but never weaken the regex." >&2
  exit 1
fi

echo "Constitution check passed: 6.D + 6.E + 6.F present in CLAUDE.md;"
echo "Submodules/Tracker-SDK/CLAUDE.md present; core/ + feature/ scoped"
echo "clauses present; no clause-6.H credential patterns in tracked files."
