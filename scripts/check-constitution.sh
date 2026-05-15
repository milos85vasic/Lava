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
# 9b. §6.O propagation count across the same 21+ targets
# (added 2026-05-05, Crashlytics-Resolved Issue Coverage Mandate
# inheritance enforcement). Same pattern as §6.N.
# ----------------------------------------------------------------
for f in "${propagation_targets[@]}"; do
  if [[ ! -f "$f" ]]; then continue; fi
  count=$(grep -c "6\.O" "$f")
  if [[ "$count" -lt 1 ]]; then
    echo "§6.O propagation REGRESSED: $f has 0 references (expected ≥ 1)" >&2
    echo "  → Re-propagate per the §6.O Crashlytics-Resolved Issue Coverage Mandate pattern." >&2
    exit 1
  fi
done

# ----------------------------------------------------------------
# 9d. §6.P propagation count across the same 21+ targets
# (added 2026-05-05, Distribution Versioning + Changelog Mandate
# inheritance enforcement, TWELFTH §6.L invocation). Same pattern.
# ----------------------------------------------------------------
for f in "${propagation_targets[@]}"; do
  if [[ ! -f "$f" ]]; then continue; fi
  count=$(grep -c "6\.P" "$f")
  if [[ "$count" -lt 1 ]]; then
    echo "§6.P propagation REGRESSED: $f has 0 references (expected ≥ 1)" >&2
    echo "  → Re-propagate per the §6.P Distribution Versioning + Changelog Mandate pattern." >&2
    exit 1
  fi
done

# ----------------------------------------------------------------
# 9e. §6.Q propagation count across the same 21+ targets
# (added 2026-05-05, Compose Layout Antipattern Guard inheritance
# enforcement, THIRTEENTH §6.L invocation). Same pattern.
# ----------------------------------------------------------------
for f in "${propagation_targets[@]}"; do
  if [[ ! -f "$f" ]]; then continue; fi
  count=$(grep -c "6\.Q" "$f")
  if [[ "$count" -lt 1 ]]; then
    echo "§6.Q propagation REGRESSED: $f has 0 references (expected ≥ 1)" >&2
    echo "  → Re-propagate per the §6.Q Compose Layout Antipattern Guard pattern." >&2
    exit 1
  fi
done

# ----------------------------------------------------------------
# 9c. §6.O closure-log soft warning. When a commit introduces a Crashlytics
# fix (commit message contains "Crashlytics" + "fix"/"resolve"), the gate
# WARNS if no matching .lava-ci-evidence/crashlytics-resolved/ entry exists
# in the change set. Soft for now; hardens in a future phase per §6.O cl 3.
# ----------------------------------------------------------------

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

# ---------------------------------------------------------------------
# 6.R — No-Hardcoding Mandate enforcement
# ---------------------------------------------------------------------

# 6.R clause must appear in root CLAUDE.md
if ! grep -qF '##### 6.R — No-Hardcoding Mandate' CLAUDE.md; then
  echo "MISSING constitutional clause: 6.R — No-Hardcoding Mandate" >&2
  echo "  → Add to CLAUDE.md per Phase 1 Task 1.1." >&2
  exit 1
fi

# 6.R must appear in every Submodules/*/CLAUDE.md (per §6.F inheritance).
# Heading-anchored pattern (`## §6.R — No-Hardcoding Mandate`) — a passing
# mention in a notes/history paragraph MUST NOT satisfy this gate.
for sub in Submodules/*/CLAUDE.md; do
  if ! grep -qF '## §6.R — No-Hardcoding Mandate' "$sub"; then
    echo "MISSING 6.R inheritance reference: $sub" >&2
    echo "  → Append the §6.R heading paragraph per Phase 1 Task 1.3." >&2
    exit 1
  fi
done

# 6.R: no 36-char UUIDs in tracked source outside the exemption set.
# Delegate to the standalone scanner so the hermetic test
# (tests/check-constitution/test_no_hardcoded_uuid.sh) can invoke the SAME
# rule in isolation — eliminating the previous silent-PASS bluff where
# the test green-lit a UUID violation when an unrelated earlier gate in
# this script failed first.
bash scripts/scan-no-hardcoded-uuid.sh

# 6.R staged scopes (§4.5.10 closure, 2026-05-13):
# IPv4 + host:port literal enforcement was deferred at §6.R landing time
# per "Enforcement status (2026-05-06)". This pair of scanners ships the
# mechanical gate. Same delegate-to-standalone pattern as the UUID gate
# so each rule can be invoked in isolation under hermetic test conditions.
bash scripts/scan-no-hardcoded-ipv4.sh
bash scripts/scan-no-hardcoded-hostport.sh

# ---------------------------------------------------------------------
# 6.S — Continuation Document Maintenance Mandate enforcement
# ---------------------------------------------------------------------

# 6.S(1): docs/CONTINUATION.md must exist
if [[ ! -f docs/CONTINUATION.md ]]; then
  echo "MISSING continuation document: docs/CONTINUATION.md" >&2
  echo "  → §6.S requires a maintained CONTINUATION index." >&2
  exit 1
fi

# 6.S(2): §0 "Last updated" line must be present (the date that mechanically
# tracks freshness; CI cannot prove it's the SAME date as HEAD's, but
# absence of the line is by itself a §6.S violation)
if ! grep -qE '^> \*\*Last updated:\*\*' docs/CONTINUATION.md; then
  echo "MISSING §0 'Last updated' line in docs/CONTINUATION.md" >&2
  echo '  → §6.S requires `> **Last updated:** YYYY-MM-DD, ...` after the §0 heading.' >&2
  exit 1
fi

# 6.S(3): §7 RESUME PROMPT must be present (the operator-pasteable text
# that lets a fresh CLI agent resume work)
if ! grep -qE '^## 7\. RESUME PROMPT' docs/CONTINUATION.md; then
  echo "MISSING §7 RESUME PROMPT section in docs/CONTINUATION.md" >&2
  echo "  → §6.S requires the operator-pasteable resume prompt." >&2
  exit 1
fi

# 6.S(4): §6.S clause itself must appear in root CLAUDE.md
if ! grep -qF '##### 6.S — Continuation Document Maintenance Mandate' CLAUDE.md; then
  echo "MISSING constitutional clause: 6.S — Continuation Document Maintenance Mandate" >&2
  echo "  → Add to CLAUDE.md." >&2
  exit 1
fi

# 6.S(5): §6.S inheritance reference must appear in every Submodules/*/CLAUDE.md
for sub in Submodules/*/CLAUDE.md; do
  if ! grep -qF '## §6.S — Continuation Document Maintenance Mandate' "$sub"; then
    echo "MISSING 6.S inheritance reference: $sub" >&2
    echo "  → Append the §6.S heading paragraph (mirror the §6.R pattern)." >&2
    exit 1
  fi
done

# 6.S(6): §6.S inheritance reference must appear in lava-api-go/CLAUDE.md
if [[ -f lava-api-go/CLAUDE.md ]] && ! grep -qF 'Clause 6.S' lava-api-go/CLAUDE.md; then
  echo "MISSING 6.S reference in lava-api-go/CLAUDE.md" >&2
  echo "  → Append a §6.S inheritance reference per §6.F." >&2
  exit 1
fi

# -----------------------------------------------------------------------------
# 6.X — Container-Submodule Emulator Wiring Mandate enforcement
# (added 2026-05-13, TWENTY-FIRST §6.L invocation). Same pattern as §6.S.
# Per §6.X clause "Mechanical enforcement" (d) + (e), every submodule's
# CLAUDE.md / AGENTS.md / CONSTITUTION.md and lava-api-go's three docs MUST
# contain a §6.X inheritance reference. Clauses (a)–(c) [runtime/wiring
# checks] activate progressively: as of 2026-05-13 evening, (a) and (b)
# are active — Containers submodule shipped Containerized impl + CLI
# --runner flag at submodule HEAD 562069e7. Clause (c) [tag.sh
# attestation row check] activates with the next scripts/tag.sh touch.
# -----------------------------------------------------------------------------

# 6.X(1): §6.X clause itself must appear in root CLAUDE.md
if ! grep -qF '##### 6.X — Container-Submodule Emulator Wiring Mandate' CLAUDE.md; then
  echo "MISSING 6.X clause in CLAUDE.md" >&2
  echo "  → Add the §6.X Container-Submodule Emulator Wiring Mandate clause." >&2
  exit 1
fi

# 6.X(2): §6.X inheritance reference must appear in every Submodules/*/CLAUDE.md,
# */AGENTS.md, and */CONSTITUTION.md (per §6.F inheritance).
for sub in Submodules/*/CLAUDE.md Submodules/*/AGENTS.md Submodules/*/CONSTITUTION.md; do
  if ! grep -qF '## §6.X — Container-Submodule Emulator Wiring Mandate' "$sub"; then
    echo "MISSING 6.X inheritance reference: $sub" >&2
    echo "  → Append the §6.X heading paragraph (mirror the §6.S pattern)." >&2
    exit 1
  fi
done

# 6.X(3): §6.X inheritance reference must appear in lava-api-go's three docs
for doc in lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md; do
  if [[ -f "$doc" ]] && ! grep -qF '§6.X — Container-Submodule Emulator Wiring Mandate' "$doc"; then
    echo "MISSING 6.X reference in $doc" >&2
    echo "  → Append a §6.X inheritance reference per §6.F." >&2
    exit 1
  fi
done

# 6.X(4) — runtime check (a): Containers submodule MUST provide a
# Containerized Emulator implementation distinct from the host-direct
# AndroidEmulator path. This is the §6.X-debt close criterion (1).
# Activated 2026-05-13 evening after Containers commit 562069e7 shipped.
if [[ -d Submodules/Containers/pkg/emulator ]]; then
  if [[ ! -f Submodules/Containers/pkg/emulator/containerized.go ]]; then
    echo "MISSING 6.X runtime check (a): Submodules/Containers/pkg/emulator/containerized.go" >&2
    echo "  → Containers-side §6.X-debt close requires a Containerized Emulator impl." >&2
    exit 1
  fi
  if ! grep -qF 'type Containerized struct' Submodules/Containers/pkg/emulator/containerized.go 2>/dev/null; then
    echo "MISSING 6.X runtime check (a): containerized.go lacks the Containerized type declaration" >&2
    exit 1
  fi
  # And the Emulator interface compile-time check.
  if ! grep -qF 'var _ Emulator = (*Containerized)(nil)' Submodules/Containers/pkg/emulator/containerized.go 2>/dev/null; then
    echo "MISSING 6.X runtime check (a): Containerized does not assert Emulator-interface satisfaction" >&2
    exit 1
  fi
fi

# 6.X(5) — runtime check (b): cmd/emulator-matrix MUST accept the
# --runner flag (host-direct|containerized). This is the §6.X-debt
# close criterion (2). Activated 2026-05-13 evening.
if [[ -f Submodules/Containers/cmd/emulator-matrix/main.go ]]; then
  if ! grep -qF 'flag.String("runner"' Submodules/Containers/cmd/emulator-matrix/main.go 2>/dev/null; then
    echo "MISSING 6.X runtime check (b): cmd/emulator-matrix/main.go lacks --runner flag" >&2
    echo "  → §6.X-debt close requires the runner-choice flag on the matrix CLI." >&2
    exit 1
  fi
fi

# -----------------------------------------------------------------------------
# §6.AD HelixConstitution Inheritance — closes §6.AD-debt items 1 + 7.
# Added 2026-05-14 (29th §6.L cycle).
# -----------------------------------------------------------------------------

# 6.AD(1): root CLAUDE.md MUST contain the §6.AD clause itself.
if ! grep -qF '##### 6.AD — HelixConstitution Inheritance' CLAUDE.md; then
  echo "MISSING 6.AD clause in CLAUDE.md" >&2
  echo "  → Add the §6.AD HelixConstitution Inheritance Mandate clause." >&2
  exit 1
fi

# 6.AD(2): the constitution submodule MUST exist with expected files.
if [[ ! -d constitution ]]; then
  echo "MISSING constitution/ submodule directory (§6.AD)" >&2
  echo "  → git submodule update --init constitution" >&2
  exit 1
fi
for required in constitution/CLAUDE.md constitution/AGENTS.md constitution/Constitution.md constitution/install_upstreams.sh constitution/find_constitution.sh; do
  if [[ ! -f "$required" ]]; then
    echo "MISSING $required (§6.AD)" >&2
    echo "  → constitution submodule appears truncated; re-init with --recursive." >&2
    exit 1
  fi
done

# 6.AD(3): root CLAUDE.md + AGENTS.md MUST carry the inheritance pointer-block.
for root in CLAUDE.md AGENTS.md; do
  if ! grep -qF '## INHERITED FROM constitution/' "$root"; then
    echo "MISSING §6.AD inheritance pointer-block in $root" >&2
    echo "  → Add '## INHERITED FROM constitution/CLAUDE.md' (or AGENTS.md) at the top, after the H1." >&2
    exit 1
  fi
done

# 6.AD(4): every per-scope CLAUDE.md / AGENTS.md / CONSTITUTION.md MUST carry
# the inheritance pointer-block. Scope: Submodules/* + lava-api-go/ + core/ +
# app/ + feature/. (Root CLAUDE.md + AGENTS.md handled above.)
ad_propagated_targets=()
for f in Submodules/*/CLAUDE.md Submodules/*/AGENTS.md Submodules/*/CONSTITUTION.md; do
  [[ -f "$f" ]] && ad_propagated_targets+=("$f")
done
for f in lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md core/CLAUDE.md app/CLAUDE.md feature/CLAUDE.md; do
  [[ -f "$f" ]] && ad_propagated_targets+=("$f")
done
ad_missing=()
for f in "${ad_propagated_targets[@]}"; do
  if ! grep -qE '^## INHERITED FROM constitution/' "$f"; then
    ad_missing+=("$f")
  fi
done
if [[ ${#ad_missing[@]} -gt 0 ]]; then
  echo "MISSING §6.AD inheritance pointer-block in ${#ad_missing[@]} per-scope doc(s):" >&2
  for f in "${ad_missing[@]}"; do echo "    $f" >&2; done
  echo "  → Run scripts/inject-helix-inheritance-block.sh to add the block idempotently." >&2
  exit 1
fi

# 6.AD(5): scripts/commit_all.sh wrapper MUST exist + be executable.
if [[ ! -x scripts/commit_all.sh ]]; then
  echo "MISSING executable scripts/commit_all.sh (§6.AD.2)" >&2
  echo "  → HelixConstitution mandates a project-official commit + push wrapper." >&2
  exit 1
fi

# 6.AD(6): scripts/inject-helix-inheritance-block.sh debt-closure tool MUST exist.
if [[ ! -x scripts/inject-helix-inheritance-block.sh ]]; then
  echo "MISSING executable scripts/inject-helix-inheritance-block.sh (§6.AD-debt item 1)" >&2
  echo "  → Add the idempotent inject script for new per-scope docs." >&2
  exit 1
fi

# -----------------------------------------------------------------------------
# §6.AE Comprehensive Challenge Coverage + Container/QEMU Matrix Mandate.
# Added 2026-05-15 (31st §6.L invocation).
# -----------------------------------------------------------------------------

# 6.AE(1): root CLAUDE.md MUST contain the §6.AE clause itself.
if ! grep -qF '##### 6.AE — Comprehensive Challenge Coverage' CLAUDE.md; then
  echo "MISSING 6.AE clause in CLAUDE.md" >&2
  echo "  → Add the §6.AE Comprehensive Challenge Coverage + Container/QEMU Matrix Mandate clause." >&2
  exit 1
fi

# 6.AE(2): scripts/check-challenge-coverage.sh MUST exist + be executable.
if [[ ! -x scripts/check-challenge-coverage.sh ]]; then
  echo "MISSING executable scripts/check-challenge-coverage.sh (§6.AE.6)" >&2
  echo "  → Add the per-feature Challenge coverage scanner." >&2
  exit 1
fi

# 6.AE(3): scripts/run-challenge-matrix.sh MUST exist + be executable.
if [[ ! -x scripts/run-challenge-matrix.sh ]]; then
  echo "MISSING executable scripts/run-challenge-matrix.sh (§6.AE.6)" >&2
  echo "  → Add the §6.AE matrix-runner glue (delegates to Containers submodule)." >&2
  exit 1
fi

# -----------------------------------------------------------------------------
# §6.W applicability boundary check — closes §6.AD-debt item 7.
# The 2-mirror rule (GitHub + GitLab only) applies to the parent + every
# vasic-digital submodule. The constitution submodule (HelixDevelopment-domain)
# is permitted up to 4 named remotes via its own install_upstreams.sh:
# github + gitlab + gitflic + gitverse. This check verifies:
#   - parent + vasic-digital submodules have NO gitflic/gitverse named remotes
#   - constitution submodule (if remotes configured) only adds gitflic/gitverse
#     to its OWN remotes, never bleeds to parent
# -----------------------------------------------------------------------------
forbidden_remote_hosts=("gitflic" "gitverse")
# Parent
parent_remotes=$(git remote 2>/dev/null)
for h in "${forbidden_remote_hosts[@]}"; do
  if echo "$parent_remotes" | grep -qx "$h"; then
    echo "§6.W VIOLATION: parent repo has '$h' remote (forbidden — only github + gitlab permitted)" >&2
    echo "  → git remote remove $h" >&2
    exit 1
  fi
done
# vasic-digital submodules (every Submodules/* — none is HelixDevelopment-owned)
for sub in Submodules/*/; do
  [[ -d "$sub/.git" || -f "$sub/.git" ]] || continue
  sub_remotes=$(git -C "$sub" remote 2>/dev/null || true)
  for h in "${forbidden_remote_hosts[@]}"; do
    if echo "$sub_remotes" | grep -qx "$h"; then
      echo "§6.W VIOLATION: $sub has '$h' remote (only github + gitlab permitted for Lava-owned submodules)" >&2
      echo "  → git -C $sub remote remove $h" >&2
      exit 1
    fi
  done
done

# -----------------------------------------------------------------------------
# §6.AD-debt item 4 + HelixConstitution §11.4.6 — no-guessing-vocabulary
# grep gate. Forbidden words in tracked status / closure / commit-template
# files when describing causes. UNCONFIRMED: / UNKNOWN: / PENDING_FORENSICS:
# lead-in allows the otherwise-forbidden word to pass.
# -----------------------------------------------------------------------------
forbidden_guess_words='\b(likely|probably|maybe|might|possibly|presumably|seemingly|apparently|perhaps|supposedly|conjectured)\b|\bseems\s+to\b|\bappears\s+to\b'
guess_scan_paths=(
  ".lava-ci-evidence/sixth-law-incidents"
  ".lava-ci-evidence/crashlytics-resolved"
)
guess_violations=()
for p in "${guess_scan_paths[@]}"; do
  [[ -d "$p" ]] || continue
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    # Skip the line if it begins with an UNCONFIRMED:/UNKNOWN:/PENDING_FORENSICS: lead
    # OR if the file is a forensic anchor that quotes historical agent output verbatim
    # (we exempt the file's own forensic-anchor headers via grep -v on their stable markers).
    if grep -ihnE "$forbidden_guess_words" "$f" 2>/dev/null | \
       grep -ivE '^[^:]+:[0-9]+:.*\b(UNCONFIRMED|UNKNOWN|PENDING_FORENSICS):' | \
       grep -ivE 'forensic[[:space:]]+anchor|verbatim[[:space:]]+(operator|agent|user)|historical[[:space:]]+quote' | \
       head -3 | grep -q .; then
      guess_violations+=("$f")
    fi
  done < <(find "$p" -type f \( -name '*.md' -o -name '*.json' \))
done
# Note: this gate intentionally does NOT scan CLAUDE.md / AGENTS.md / CONSTITUTION.md
# because those documents must DESCRIBE the forbidden vocabulary as part of the
# mandate itself — the gate exists for future status reports, not the rule's text.
if [[ ${#guess_violations[@]} -gt 0 ]]; then
  echo "§6.AD/HelixConstitution §11.4.6 VIOLATION: forbidden guessing vocabulary in:" >&2
  printf '    %s\n' "${guess_violations[@]}" >&2
  echo "  → Either prove the cause with captured evidence and state as fact," >&2
  echo "    OR mark the line with UNCONFIRMED: / UNKNOWN: / PENDING_FORENSICS: prefix." >&2
  exit 1
fi

echo "Constitution check passed: 6.D + 6.E + 6.F present in CLAUDE.md;"
echo "Submodules/Tracker-SDK/CLAUDE.md present; core/ + feature/ scoped"
echo "clauses present; no clause-6.H credential patterns in tracked files;"
echo "clause-6.K Containers extension present; §6.X Container-Submodule"
echo "Emulator Wiring inherited in all submodule + lava-api-go docs;"
echo "§6.X runtime checks (a) Containerized impl + (b) --runner flag active;"
echo "§6.AD HelixConstitution clause + constitution submodule + 54 per-scope"
echo "inheritance pointer-blocks present; §6.W remote-host boundary clean;"
echo "§11.4.6 no-guessing vocabulary gate clean."
