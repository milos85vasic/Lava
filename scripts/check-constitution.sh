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

# ---------------------------------------------------------------------
# 7. Clause 6.K presence check (Containers extension shipped).
#
# Per clause 6.K clause 5: once Submodules/Containers/pkg/emulator/
# ships, the constitution checker MUST verify (a) the package exists in
# the pinned submodule, (b) Lava-side scripts/run-emulator-tests.sh
# references the package's CLI, (c) at least one passing test inside
# the package. Failure of any is a clause-6.K violation.
#
# This check is conditional: if the submodule is not initialised (fresh
# clone before `git submodule update --init`), it warns rather than
# fails, since the matrix capability isn't required for every push.
# Pre-tag invocation (scripts/tag.sh) MUST upgrade the warn to a hard
# fail.
# ---------------------------------------------------------------------
containers_emulator_dir="Submodules/Containers/pkg/emulator"
if [[ -d "$containers_emulator_dir" ]]; then
  if [[ ! -f "$containers_emulator_dir/types.go" ]] ||
     [[ ! -f "$containers_emulator_dir/android.go" ]] ||
     [[ ! -f "$containers_emulator_dir/matrix.go" ]]; then
    echo "MISSING clause 6.K files in $containers_emulator_dir" >&2
    echo "  → expected types.go + android.go + matrix.go." >&2
    exit 1
  fi
  if ! grep -q 'cmd/emulator-matrix' scripts/run-emulator-tests.sh; then
    echo "MISSING reference to cmd/emulator-matrix in scripts/run-emulator-tests.sh" >&2
    echo "  → clause 6.K mandates Lava-side glue invokes the Containers CLI." >&2
    exit 1
  fi
  emulator_test_count=$(find "$containers_emulator_dir" -name '*_test.go' | wc -l)
  if [[ "$emulator_test_count" -eq 0 ]]; then
    echo "MISSING tests in $containers_emulator_dir (clause 6.K clause 5)" >&2
    exit 1
  fi
  echo "  ✓ clause 6.K: $containers_emulator_dir present + $emulator_test_count test file(s)"
else
  echo "  ⚠ clause 6.K: $containers_emulator_dir not present in this checkout."
  echo "    Submodule may not be initialised; run \`git submodule update --init Submodules/Containers\`."
  echo "    scripts/tag.sh MUST upgrade this warn to a hard fail at tag time."
fi

# ----------------------------------------------------------------
# 8. §6.N + §6.N-debt presence in root CLAUDE.md
# (added 2026-05-05, Group A-prime — closes §6.N-debt's transitional
# "MAY warn but MUST NOT yet hard-fail" clause).
# ----------------------------------------------------------------
required_6n=(
  "##### 6.N — Bluff-Hunt Cadence"
  "##### 6.N-debt"
)
for clause in "${required_6n[@]}"; do
  if ! grep -qF "$clause" CLAUDE.md; then
    echo "MISSING constitutional clause heading: $clause" >&2
    echo "  → Group A landed §6.N + §6.N-debt; do not delete them." >&2
    exit 1
  fi
done

# ----------------------------------------------------------------
# 9. §6.N propagation count across 21 target files (Group A propagation)
# ----------------------------------------------------------------
declare -a propagation_targets=(
  "CLAUDE.md" "AGENTS.md"
  "lava-api-go/CLAUDE.md" "lava-api-go/AGENTS.md" "lava-api-go/CONSTITUTION.md"
)
for sm in Auth Cache Challenges Concurrency Config Containers Database \
          Discovery HTTP3 Mdns Middleware Observability RateLimiter \
          Recovery Security Tracker-SDK; do
  propagation_targets+=("Submodules/$sm/CLAUDE.md")
done
for f in "${propagation_targets[@]}"; do
  if [[ ! -f "$f" ]]; then continue; fi
  count=$(grep -c "6\.N" "$f")
  if [[ "$count" -lt 1 ]]; then
    echo "§6.N propagation REGRESSED: $f has 0 references (expected ≥ 1)" >&2
    echo "  → Re-propagate per Group A's pattern (see commit 130b655)." >&2
    exit 1
  fi
done

# ----------------------------------------------------------------
# 10. .githooks/pre-push has Check 4 + Check 5 markers
# ----------------------------------------------------------------
if ! grep -qE "# ===== Check 4: §6.N.1.2" .githooks/pre-push; then
  echo "MISSING pre-push Check 4 (§6.N.1.2 enforcement marker)" >&2
  echo "  → Group A-prime added this; do not remove the marker comment." >&2
  exit 1
fi
if ! grep -qE "# ===== Check 5: §6.N.1.3" .githooks/pre-push; then
  echo "MISSING pre-push Check 5 (§6.N.1.3 enforcement marker)" >&2
  echo "  → Group A-prime added this; do not remove the marker comment." >&2
  exit 1
fi

echo "Constitution check passed: 6.D + 6.E + 6.F present in CLAUDE.md;"
echo "Submodules/Tracker-SDK/CLAUDE.md present; core/ + feature/ scoped"
echo "clauses present; no clause-6.H credential patterns in tracked files;"
echo "clause-6.K Containers extension present."
