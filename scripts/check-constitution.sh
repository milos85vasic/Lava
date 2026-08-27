#!/usr/bin/env bash
# scripts/check-constitution.sh — verify constitutional clauses present.
#
# Per the SP-3a plan Task 5.19. Asserts that the three SP-3a clauses
# (6.D, 6.E, 6.F) are present in root CLAUDE.md and that the
# submodules/tracker_sdk/CLAUDE.md exists. Run from scripts/ci.sh in
# every mode.

set -euo pipefail

cd "$(dirname "$0")/.."

# ---------------------------------------------------------------------
# HelixDevelopment-owned submodules — exempt from Lava-specific heading
# checks (§6.R / §6.S / §6.X / §6.AD). Lava pins these submodules at
# upstream HEAD AS-IS; their CLAUDE.md / AGENTS.md are HelixDevelopment-
# authored and follow the canonical-root inheritance pattern (covered by
# their own §INHERITED FROM Helix Constitution pointer block) rather
# than Lava-specific clause headings.
# ---------------------------------------------------------------------
# HelixDevelopment-owned submodules exempt from the §6.R-block propagation gate
# (Lava does not force its governance structure onto externally-owned repos).
# Both the historical CamelCase dir name and the post-snake_case-migration
# lowercase path are listed so the exemption survives the dir rename — the
# 2026-06-11 helixqa upstream pull surfaced the case-mismatch when the new pin's
# CLAUDE.md dropped the §6.R block that the old pin happened to carry.
HELIX_DEV_OWNED=("HelixQA" "helixqa")

is_helix_dev_owned() {
  local path=$1
  for owned in "${HELIX_DEV_OWNED[@]}"; do
    [[ "$path" == *"/$owned/"* ]] && return 0
    [[ "$path" == *"/$owned"* ]] && return 0
  done
  return 1
}

# A submodule governance doc satisfies a §6.R/§6.S/§6.X inheritance gate if it
# carries EITHER the verbatim Lava clause heading OR the §6.AD-canonical
# `## INHERITED FROM constitution/...` pointer block.
#
# Why the pointer is an HONEST pass (not a relaxed bluff): §6.AD.8 declares the
# `## INHERITED FROM constitution/` pointer the CANONICAL submodule-inheritance
# mechanism, and constitution §11.4.28 (Submodules-As-Equal-Codebase +
# Decoupling) FORBIDS injecting project-specific clause text into reusable
# submodules. So an upstream that has slimmed its CLAUDE.md to the
# inherit-from-constitution stub is the COMPLIANT form — it transitively
# inherits §6.R/§6.S/§6.X via the constitution it points at. Forcing the
# verbatim Lava headings back in (the pre-2026-06-24 gate behavior) would
# itself violate §11.4.28. The no-hardcoding SOURCE scanners
# (scan-no-hardcoded-{uuid,ipv4,hostport}.sh) remain FULLY STRICT below — this
# helper only governs the DOC-presence inheritance gate, never behavior.
# Falsifiability: a doc with NEITHER form returns 1 (gate fires) — proven by
# the §6.N rehearsal in the commit that introduced this helper + the
# tests/check-constitution/test_clause_6r_inheritance.sh fixture.
doc_inherits_clause() {
  local file=$1 heading=$2
  grep -qF "$heading" "$file" && return 0
  grep -qE '^## INHERITED FROM constitution/' "$file" && return 0
  return 1
}

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
# 2. submodules/tracker_sdk/CLAUDE.md MUST exist.
# ---------------------------------------------------------------------
if [[ ! -f submodules/tracker_sdk/CLAUDE.md ]]; then
  echo "MISSING submodules/tracker_sdk/CLAUDE.md" >&2
  echo "  → Restore via 'git submodule update --init submodules/tracker_sdk'" >&2
  echo "    or per SP-3a Phase 1 Task 1.7." >&2
  exit 1
fi

# ---------------------------------------------------------------------
# 3. Tracker-SDK constitution MUST reference clauses 6.A-6.F (or the
#    individual clauses) via the Sixth Law inheritance.
# ---------------------------------------------------------------------
if ! grep -qE '6\.A.{0,4}6\.F|6\.A through 6\.E|clauses 6\.A' submodules/tracker_sdk/CLAUDE.md; then
  echo "WARN: submodules/tracker_sdk/CLAUDE.md does not explicitly cite 6.A-6.F. Verify the Sixth Law inheritance is present and re-run." >&2
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
#
# Also excluded (added 2026-08-25, LVA-134; scope corrected 2026-08-26): the
# GENERATED workable-items tracker renderings
# {Issues,Fixed}{,_Summary}.{md,html,pdf,docx} — at the repository ROOT and
# under docs/. The `(docs/)?` in the anchor is NOT a loosening: this repo
# tracks the SAME generated renderings in BOTH locations, and the two copies
# are byte-identical (verified with cmp on all four .md and all four .html
# members). Naming only docs/ left the root copies scanned, so the moment the
# trackers were regenerated the LVA-134 ticket body reproduced the violation
# in Fixed.md, Fixed.html, Fixed_Summary.md and Fixed_Summary.html at the
# root — measured: `git show HEAD:Fixed.md | grep -c` = 0, working tree = 1,
# i.e. the hit was NEW output from the generator, not inherited content.
#
# WHY. Every exemption already in the list above is the same category: a
# surface whose PURPOSE is to DESCRIBE the forbidden pattern rather than to
# use a credential — .env.example (template), CHANGELOG.md (historical
# incidents), .lava-ci-evidence/ (forensic leak records), docs/INCIDENT_*,
# the governance docs, and this scanner's own source. The issue tracker was
# the one such prose surface missing from the list, and it is the surface
# MOST likely to describe a credential-pattern defect: LVA-134 — the ticket
# filed about this very false positive — quotes the pattern verbatim in its
# body, so regenerating the trackers from docs/workable_items.db reproduced
# the violation in 4 more tracked files. Filing the bug re-created the bug.
#
# WHY ALL FOUR NAMES, not just the two that were hit. The trackers are
# generated: an OPEN item renders into Issues*, and closing it MOVES the same
# body into Fixed*. Exempting only Issues* would re-break this gate at the
# moment LVA-134 is marked closed. The scope is derived from that mechanic,
# not from which files happened to be red today.
#
# COVERAGE COST, stated plainly (§6.J — an exemption is surface no longer
# scanned): 32 tracked files out of 5510 (0.58%) — 16 under docs/ and the 16
# byte-identical copies at the root. All 32 are generated from
# docs/workable_items.db; none is handwritten source. The .pdf/.docx/.db
# members were already effectively unscanned (grep -I skips binaries), so the
# REAL loss is 16 files: {Issues,Fixed}{,_Summary}.{md,html} × 2 dirs. The
# root half of that is NOT additional information withheld from the scan —
# it is the same bytes as the docs/ half, already exempt. Residual
# risk: a real credential pasted into a ticket body would no longer be caught
# in its markdown/HTML rendering. That risk is accepted here because the
# rendering is a copy — the authoritative store, docs/workable_items.db, is
# binary and was never scanned by this block in the first place.
#
# NOT exempted: tests/**. The hermetic fixture in
# tests/check-constitution/test_credential_scan_corpus.sh that used to trip
# this scan now ASSEMBLES its leak string at runtime (self-safe idiom, same
# one scripts/scan-no-hardcoded-ipv4.sh uses on itself), so all 102 tracked
# files under tests/ remain fully inside this corpus. That is deliberate: the
# two sibling §6.R scanners (ipv4, hostport) blanket-exempt '^tests/', and
# copying them here would have cost 102 files of coverage to fix 1 file's
# false positive. Runtime assembly costs nothing and is self-verifying.
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
  grep -vE '^\.env\.example$|^CHANGELOG\.md$|^\.lava-ci-evidence/|^scripts/check-constitution\.sh$|^docs/INCIDENT_|^CLAUDE\.md$|^AGENTS\.md$|^lava-api-go/AGENTS\.md$|^lava-api-go/CLAUDE\.md$|^lava-api-go/CONSTITUTION\.md$|^(docs/)?(Issues|Fixed)(_Summary)?\.(md|html|pdf|docx)$' || true
)

# §6.J anti-bluff corpus assertion (added 2026-08-22, §6.N.2 gate-shaping
# bluff hunt). Without this, a `git ls-files` that yields nothing — broken
# index, non-repo cwd, a stubbed/failed git — makes the loop below iterate
# zero times, report `credential_violations=0`, and print "no clause-6.H
# credential patterns in tracked files". That is "nothing was learned"
# reported as "nothing failed": the exact shape §6.J forbids. The `|| true`
# on the pipeline above (needed because grep -v exits 1 on an all-filtered
# corpus) is what makes the failure silent. A healthy tree has thousands of
# tracked files, so this guard can never fire on a real run — it fires only
# when the gate is about to make an unbacked claim.
if [[ ${#tracked_files[@]} -eq 0 ]]; then
  echo "clause 6.H credential scan examined ZERO tracked files." >&2
  echo "  → The scan corpus is empty, so a PASS here would assert nothing." >&2
  echo "  → Check that 'git ls-files' works in $(pwd) (repo present, index" >&2
  echo "    readable, git on PATH) and re-run." >&2
  exit 1
fi

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
# Per clause 6.K clause 5: once submodules/containers/pkg/emulator/
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
containers_emulator_dir="submodules/containers/pkg/emulator"
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
  echo "    Submodule may not be initialised; run \`git submodule update --init submodules/containers\`."
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
# 8b. §6.J per-scope doc CORPUS floor (added 2026-08-26, LVA vacuous-pass
# sweep F5) — covers AGENTS.md, CONSTITUTION.md and the fixed root/scope
# doc list, which the CLAUDE.md-only floor further down does not.
#
# Blocks 9 / 9b / 9d / 9e, 6.S(5)-(6), 6.X(2)-(3) and 6.AD(4) all build their
# corpus from files that EXIST — `for f in submodules/*/AGENTS.md …;
# [[ -f "$f" ]] || continue`, and `[[ -f "$doc" ]] && …` for the fixed list.
# Weakening a doc therefore fails, while DELETING it passes. Deletion is the
# strictly worse state and it was the one that passed:
#
#   MUTATION A: EDIT submodules/auth/AGENTS.md to drop the pointer-block
#     MISSING §6.AD inheritance pointer-block in 1 per-scope doc(s)      EXIT=1
#   MUTATION B: DELETE submodules/auth/AGENTS.md entirely
#     REACHED-CLEAN (6.AD(4) block passed)                              EXIT=0
#   MUTATION C: DELETE lava-api-go/CONSTITUTION.md + core/CLAUDE.md
#     REACHED-CLEAN (6.AD(4) block passed)                              EXIT=0
#
# The expectation is DERIVED from .gitmodules (submodule docs) and from the
# fixed scope list this script already enumerates (root/lava-api-go/core/app/
# feature) — never hardcoded, so adding or removing a submodule cannot silently
# lower the bar. Same source of truth, and the same
# uninitialised-vs-real-drift distinction, as the CLAUDE.md floor below.
#
# awk, not `grep -c`: `grep -c` exits 1 on a zero count, which under `set -e`
# in a pipeline is its own hazard.
declared_submodule_paths=()
while read -r _decl; do
  case "$_decl" in submodules/*) ;; *) continue ;; esac
  is_helix_dev_owned "$_decl" && continue
  declared_submodule_paths+=("$_decl")
done < <(sed -n 's/^[[:space:]]*path = //p' .gitmodules 2>/dev/null)

corpus_missing=()
corpus_uninitialised=()
for _decl in "${declared_submodule_paths[@]}"; do
  for _doc in CLAUDE.md AGENTS.md CONSTITUTION.md; do
    [[ -f "${_decl}/${_doc}" ]] && continue
    if [[ ! -d "$_decl" ]] || [[ -z "$(ls -A "$_decl" 2>/dev/null)" ]]; then
      corpus_uninitialised+=("${_decl}/${_doc}")
    else
      corpus_missing+=("${_decl}/${_doc}")
    fi
  done
done

# The fixed scope list. These are not optional members whose absence means
# "nothing to check" — every downstream block that reads them guards with
# `[[ -f ]] && …`, so an absent one is silently exempted from every §6.S / §6.X
# / §6.AD assertion made about it.
for _doc in CLAUDE.md AGENTS.md \
            lava-api-go/CLAUDE.md lava-api-go/AGENTS.md lava-api-go/CONSTITUTION.md \
            core/CLAUDE.md app/CLAUDE.md feature/CLAUDE.md; do
  [[ -f "$_doc" ]] || corpus_missing+=("$_doc")
done

if [[ ${#corpus_missing[@]} -gt 0 || ${#corpus_uninitialised[@]} -gt 0 ]]; then
  _expected_docs=$(( ${#declared_submodule_paths[@]} * 3 + 8 ))
  _absent=$(( ${#corpus_missing[@]} + ${#corpus_uninitialised[@]} ))
  echo "§6.AD/§6.S/§6.X per-scope doc corpus is INCOMPLETE." >&2
  echo "  → Examined: $(( _expected_docs - _absent )) of ${_expected_docs} governance doc(s)" >&2
  echo "  → Expected: ${_expected_docs} = ${#declared_submodule_paths[@]} own-org submodule(s) x 3 (CLAUDE/AGENTS/CONSTITUTION," >&2
  echo "    derived from .gitmodules, HelixDevelopment-owned excluded) + the 8 fixed" >&2
  echo "    root / lava-api-go / core / app / feature docs." >&2
  if [[ ${#corpus_missing[@]} -gt 0 ]]; then
    echo "  → REAL DRIFT — the tree is populated but these docs are gone. Every §6.S," >&2
    echo "    §6.X and §6.AD assertion about them is silently skipped, so DELETING a doc" >&2
    echo "    passes where WEAKENING it fails:" >&2
    for f in "${corpus_missing[@]}"; do echo "      $f" >&2; done
    echo "    Do: restore them (git checkout -- <path>) or, for a genuinely new scope," >&2
    echo "    run scripts/inject-helix-inheritance-block.sh to create the doc." >&2
  fi
  if [[ ${#corpus_uninitialised[@]} -gt 0 ]]; then
    echo "  → NOT INITIALISED — the submodule directory is absent or empty, so this is a" >&2
    echo "    checkout artifact rather than propagation drift:" >&2
    for f in "${corpus_uninitialised[@]}"; do echo "      $f" >&2; done
    echo "    Do: git submodule update --init --recursive" >&2
  fi
  echo "  → A PASS over the surviving docs asserts nothing about the absent ones (§6.J)." >&2
  exit 1
fi
# END-OF-BLOCK 8b per-scope doc CORPUS floor (regression-harness sentinel)

# ----------------------------------------------------------------
# 9. §6.N propagation count across 21 target files (Group A propagation)
# ----------------------------------------------------------------
declare -a propagation_targets=(
  "CLAUDE.md" "AGENTS.md"
  "lava-api-go/CLAUDE.md" "lava-api-go/AGENTS.md" "lava-api-go/CONSTITUTION.md"
)
# Post-snake_case-migration (§11.4.29) the submodule dirs are lowercase; a glob
# is migration-proof. The prior CamelCase literals (the pre-migration Auth /
# Tracker-SDK / … dir names) stopped resolving after the rename, so the
# `[[ ! -f ]]` guard below silently skipped
# EVERY submodule — turning blocks 9/9b/9d/9e into no-ops for submodules and
# hiding real propagation drift. Fixed 2026-07-02 (falsifiability: the hermetic
# test_missing_6n_from_submodule_fails now exercises the submodule path).
submodule_propagation_targets=0
for sub in submodules/*/CLAUDE.md; do
  [[ -f "$sub" ]] || continue
  propagation_targets+=("$sub")
  submodule_propagation_targets=$((submodule_propagation_targets + 1))
done
# Floor on the submodule contribution. Without `nullglob` (unset here) an
# unmatched glob yields the LITERAL string `submodules/*/CLAUDE.md`; `[[ -f ]]`
# is false and nothing is appended. Because a failing NON-FINAL operand of an
# `&&` list is exempt from `set -e`, the script does not abort — it continues
# and blocks 9/9b/9d/9e verify only the 5 root docs, then report PASS. The same
# repository state therefore yields OPPOSITE verdicts depending only on whether
# submodules happen to be initialised: real drift is caught when they are, and
# silently ignored when they are not. That is "nothing was learned" reported as
# "nothing failed" — the shape §6.J forbids, and the same shape the clause-6.H
# corpus floor above already guards against.
# Not hypothetical: the comment above records the identical no-op occurring
# after the §11.4.29 rename (CamelCase literals stopped resolving and "silently
# skipped EVERY submodule ... hiding real propagation drift", fixed 2026-07-02).
# The glob fixed the rename; this floor closes the uninitialised-clone route
# into the same no-op. A real checkout has 17+ own-org submodules, so this can
# only fire when the gate is about to make an unbacked claim.
# LVA-136 choice [B] — PARTIAL initialisation, not just total.
#
# The zero-floor below catches a corpus of 0. It does NOT catch a corpus of 15
# when 22 are declared: with 7 submodules uninitialised the propagation blocks
# examine 20 targets instead of 27 and still PASS, so the gate's verdict stays
# a function of checkout state rather than of the tree's compliance — which is
# the whole defect LVA-136 records, merely at a smaller scale. A floor that only
# fires at exactly zero is a floor with one stair.
#
# The expectation is DERIVED from .gitmodules rather than hardcoded, so adding
# or removing a submodule cannot silently lower the bar. awk, not
# `grep -c | ...`: `grep -c` exits 1 on a zero count, and under `set -e` in a
# pipeline that is its own hazard (see LVA-135 for what pipes do to gate
# conditions in this repo).
declared_submodule_docs="$(awk '/^[[:space:]]*path = submodules\//{n++} END{print n+0}' .gitmodules 2>/dev/null || echo 0)"

if [[ "$declared_submodule_docs" -gt 0 && "$submodule_propagation_targets" -lt "$declared_submodule_docs" ]]; then
  echo "propagation gate examined ${submodule_propagation_targets} submodule CLAUDE.md files, but .gitmodules declares ${declared_submodule_docs} under submodules/." >&2
  echo "  → The gate would PASS on a partial corpus, asserting nothing about the missing ones." >&2
  echo "  → Missing, with the reason distinguished:" >&2
  while read -r _decl; do
    case "$_decl" in submodules/*) ;; *) continue ;; esac
    [[ -f "${_decl}/CLAUDE.md" ]] && continue
    if [[ ! -d "$_decl" ]] || [[ -z "$(ls -A "$_decl" 2>/dev/null)" ]]; then
      echo "      ${_decl} — directory absent or empty: the submodule is NOT INITIALISED." >&2
    else
      echo "      ${_decl} — directory populated but carries NO CLAUDE.md: this is real propagation drift, not a checkout artifact (§6.AD requires the inheritance pointer-block in every submodule CLAUDE.md)." >&2
    fi
  done < <(sed -n 's/^[[:space:]]*path = //p' .gitmodules 2>/dev/null)
  echo "  → If the cause is initialisation, run: git submodule update --init --recursive" >&2
  exit 1
fi

if [[ "$submodule_propagation_targets" -eq 0 ]]; then
  echo "propagation gate examined ZERO submodule CLAUDE.md files." >&2
  echo "  → submodules/*/CLAUDE.md matched nothing, so the §6.N/§6.O/§6.P/§6.Q" >&2
  echo "    propagation blocks would check only the ${#propagation_targets[@]} root docs." >&2
  echo "  → A PASS here would assert nothing about submodule propagation." >&2
  echo "  → Initialise the submodules and re-run:" >&2
  echo "      git submodule update --init --recursive" >&2
  echo "    (scripts/setup-clone.sh reports the same remedy at its step 4.)" >&2
  exit 1
fi
for f in "${propagation_targets[@]}"; do
  if [[ ! -f "$f" ]]; then continue; fi
  if ! doc_inherits_clause "$f" "6.N"; then
    echo "§6.N propagation REGRESSED: $f has neither a literal 6.N reference nor the §6.AD '## INHERITED FROM constitution/' pointer-block" >&2
    echo "  → Re-propagate per Group A's pattern (see commit 130b655) OR add the inheritance pointer-block." >&2
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
  if ! doc_inherits_clause "$f" "6.O"; then
    echo "§6.O propagation REGRESSED: $f has neither a literal 6.O reference nor the §6.AD '## INHERITED FROM constitution/' pointer-block" >&2
    echo "  → Re-propagate per the §6.O Crashlytics-Resolved Issue Coverage Mandate pattern OR add the inheritance pointer-block." >&2
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
  if ! doc_inherits_clause "$f" "6.P"; then
    echo "§6.P propagation REGRESSED: $f has neither a literal 6.P reference nor the §6.AD '## INHERITED FROM constitution/' pointer-block" >&2
    echo "  → Re-propagate per the §6.P Distribution Versioning + Changelog Mandate pattern OR add the inheritance pointer-block." >&2
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
  if ! doc_inherits_clause "$f" "6.Q"; then
    echo "§6.Q propagation REGRESSED: $f has neither a literal 6.Q reference nor the §6.AD '## INHERITED FROM constitution/' pointer-block" >&2
    echo "  → Re-propagate per the §6.Q Compose Layout Antipattern Guard pattern OR add the inheritance pointer-block." >&2
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

# 6.R must appear in every submodules/*/CLAUDE.md (per §6.F inheritance).
# Heading-anchored pattern (`## §6.R — No-Hardcoding Mandate`) — a passing
# mention in a notes/history paragraph MUST NOT satisfy this gate.
# HelixDevelopment-owned submodules are exempt (see HELIX_DEV_OWNED).
for sub in submodules/*/CLAUDE.md; do
  is_helix_dev_owned "$sub" && continue
  if ! doc_inherits_clause "$sub" '## §6.R — No-Hardcoding Mandate'; then
    echo "MISSING 6.R inheritance reference: $sub" >&2
    echo "  → Append the §6.R heading paragraph, OR the §6.AD canonical" >&2
    echo "    '## INHERITED FROM constitution/CLAUDE.md' pointer block." >&2
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

# LVA-054: no JDK21 SequencedCollection-shaped List methods in production Kotlin.
# removeLast()/removeFirst()/getFirst()/getLast() desugar to java.util.List.*
# (SequencedCollection) which is ABSENT on Android < API 35 -> NoSuchMethodError
# at runtime while JVM unit tests pass (the bluff). Same delegate-to-standalone
# pattern so tests/check-constitution/test_no_removelast_seqcoll.sh can invoke
# the SAME rule in isolation.
bash scripts/scan-no-removelast-seqcoll.sh

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

# 6.S(5): §6.S inheritance reference must appear in every submodules/*/CLAUDE.md
# HelixDevelopment-owned submodules are exempt (see HELIX_DEV_OWNED).
for sub in submodules/*/CLAUDE.md; do
  is_helix_dev_owned "$sub" && continue
  if ! doc_inherits_clause "$sub" '## §6.S — Continuation Document Maintenance Mandate'; then
    echo "MISSING 6.S inheritance reference: $sub" >&2
    echo "  → Append the §6.S heading paragraph, OR the §6.AD canonical" >&2
    echo "    '## INHERITED FROM constitution/CLAUDE.md' pointer block." >&2
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

# 6.X(2): §6.X inheritance reference must appear in every submodules/*/CLAUDE.md,
# */AGENTS.md, and */CONSTITUTION.md (per §6.F inheritance).
# HelixDevelopment-owned submodules are exempt (see HELIX_DEV_OWNED).
for sub in submodules/*/CLAUDE.md submodules/*/AGENTS.md submodules/*/CONSTITUTION.md; do
  is_helix_dev_owned "$sub" && continue
  if ! doc_inherits_clause "$sub" '## §6.X — Container-Submodule Emulator Wiring Mandate'; then
    echo "MISSING 6.X inheritance reference: $sub" >&2
    echo "  → Append the §6.X heading paragraph, OR the §6.AD canonical" >&2
    echo "    '## INHERITED FROM constitution/...' pointer block." >&2
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
if [[ -d submodules/containers/pkg/emulator ]]; then
  if [[ ! -f submodules/containers/pkg/emulator/containerized.go ]]; then
    echo "MISSING 6.X runtime check (a): submodules/containers/pkg/emulator/containerized.go" >&2
    echo "  → Containers-side §6.X-debt close requires a Containerized Emulator impl." >&2
    exit 1
  fi
  if ! grep -qF 'type Containerized struct' submodules/containers/pkg/emulator/containerized.go 2>/dev/null; then
    echo "MISSING 6.X runtime check (a): containerized.go lacks the Containerized type declaration" >&2
    exit 1
  fi
  # And the Emulator interface compile-time check.
  if ! grep -qF 'var _ Emulator = (*Containerized)(nil)' submodules/containers/pkg/emulator/containerized.go 2>/dev/null; then
    echo "MISSING 6.X runtime check (a): Containerized does not assert Emulator-interface satisfaction" >&2
    exit 1
  fi
fi

# 6.X(5) — runtime check (b): cmd/emulator-matrix MUST accept the
# --runner flag (host-direct|containerized). This is the §6.X-debt
# close criterion (2). Activated 2026-05-13 evening.
if [[ -f submodules/containers/cmd/emulator-matrix/main.go ]]; then
  if ! grep -qF 'flag.String("runner"' submodules/containers/cmd/emulator-matrix/main.go 2>/dev/null; then
    echo "MISSING 6.X runtime check (b): cmd/emulator-matrix/main.go lacks --runner flag" >&2
    echo "  → §6.X-debt close requires the runner-choice flag on the matrix CLI." >&2
    exit 1
  fi
fi

# 6.X(6) — enforcement check (c) made mechanical: any NEW emulator/Challenge
# attestation evidence file that records emulator execution MUST declare
# `runner: containers-submodule`. Previously paper-only ("rejected by tag.sh");
# now also checked here at pre-push time, going-forward (pre-existing committed
# evidence grandfathered). Delegated to the standalone scanner so the hermetic
# test (tests/check-constitution/test_emulator_runner_tag.sh) can invoke the
# SAME rule in isolation with injected fixtures.
bash scripts/check-emulator-runner-tag.sh

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
# the inheritance pointer-block. Scope: submodules/* + lava-api-go/ + core/ +
# app/ + feature/. (Root CLAUDE.md + AGENTS.md handled above.)
ad_propagated_targets=()
for f in submodules/*/CLAUDE.md submodules/*/AGENTS.md submodules/*/CONSTITUTION.md; do
  [[ -f "$f" ]] || continue
  is_helix_dev_owned "$f" && continue
  ad_propagated_targets+=("$f")
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
# CM-COVENANT-114-*-PROPAGATION — §11.4.128–§11.4.141 literal-anchor gate.
# Closes §6.AI-debt item 4. Added 2026-06-09.
#
# §6.AI ("HelixConstitution §11.4.128–§11.4.141 Adoption") + §6.AJ
# ("Universal Action-Prefix Recognition") claim the new universal clause
# anchors are wired into root CLAUDE.md. The constitution submodule's
# CM-COVENANT-114-*-PROPAGATION scans look for these literal tokens to
# confirm a consuming project has adopted (not silently dropped) each
# universal clause. This gate enforces that the literal "11.4.N" tokens
# remain present in CLAUDE.md for every UNIVERSAL clause §6.AI adopts.
#
# Scope note: §11.4.135–§11.4.139 are the ATMosphere-TV-specific
# audio/SurfaceFlinger batch that §6.AI EXPLICITLY demotes as project-
# specific to ATMosphere and NOT binding on Lava — so they are
# deliberately NOT required here. Only the 9 universal anchors §6.AI
# enumerates as adopted/equivalence-mapped are gated.
# -----------------------------------------------------------------------------
# 6.AI(1): root CLAUDE.md MUST contain the §6.AI clause itself.
if ! grep -qF '##### 6.AI — HelixConstitution §11.4.128' CLAUDE.md; then
  echo "MISSING 6.AI clause in CLAUDE.md (CM-COVENANT-114)" >&2
  echo "  → Add the §6.AI HelixConstitution §11.4.128–§11.4.141 Adoption clause." >&2
  exit 1
fi

# 6.AI(2): root CLAUDE.md MUST contain the §6.AJ action-prefix clause.
if ! grep -qF '##### 6.AJ — Universal Action-Prefix Recognition' CLAUDE.md; then
  echo "MISSING 6.AJ clause in CLAUDE.md (CM-COVENANT-114, §11.4.140 LAYER 1)" >&2
  echo "  → Add the §6.AJ Universal Action-Prefix Recognition clause." >&2
  exit 1
fi

# 6.AI(3): every UNIVERSAL §11.4.N propagation anchor MUST appear as a
# literal token in CLAUDE.md. A removed anchor = a silently-dropped
# universal clause = a CM-COVENANT-114-N-PROPAGATION failure.
covenant_114_anchors=(
  "11.4.128"
  "11.4.129"
  "11.4.130"
  "11.4.131"
  "11.4.132"
  "11.4.133"
  "11.4.134"
  "11.4.140"
  "11.4.141"
)
covenant_114_missing=()
for anchor in "${covenant_114_anchors[@]}"; do
  if ! grep -qF "$anchor" CLAUDE.md; then
    covenant_114_missing+=("$anchor")
  fi
done
if [[ ${#covenant_114_missing[@]} -gt 0 ]]; then
  echo "CM-COVENANT-114 VIOLATION: ${#covenant_114_missing[@]} universal clause anchor(s) missing from CLAUDE.md:" >&2
  for a in "${covenant_114_missing[@]}"; do
    echo "    §$a — propagation anchor absent (CM-COVENANT-114-${a##*.}-PROPAGATION)" >&2
  done
  echo "  → §6.AI/§6.AJ claim these universal clauses are adopted; the literal" >&2
  echo "    '11.4.N' token MUST remain in the §6.AI enumeration so the" >&2
  echo "    constitution's CM-COVENANT-114-* scans can confirm adoption." >&2
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
#
# §6.J (added 2026-08-26, LVA vacuous-pass sweep F19): `git remote`'s EXIT
# STATUS is now checked. Before this, a failing `git remote` yielded the empty
# string, the empty string matched no forbidden host, and §6.W "passed" — or,
# under `set -e`, aborted at exit 128 with no message at all. Fail-closed with
# an empty diagnosis is still a bad diagnosis: it sends the reader nowhere.
parent_remote_rc=0
parent_remotes="$(git remote 2>/dev/null)" || parent_remote_rc=$?
if [[ "$parent_remote_rc" -ne 0 ]]; then
  echo "§6.W CHECK FAILED: could not enumerate the parent repository's remotes." >&2
  echo "  → Examined: 0 remote(s); 'git remote' exited ${parent_remote_rc}." >&2
  echo "  → Cause distinguished: this is NOT 'no forbidden remotes found'. The" >&2
  echo "    enumeration itself failed, so the §6.W verdict is unbacked." >&2
  echo "  → Do: run from inside the Lava repository (cwd is $(pwd)); if this checkout" >&2
  echo "    lives on an external mount, git may be refusing it for dubious ownership —" >&2
  echo "    check 'git status' first." >&2
  exit 1
fi
for h in "${forbidden_remote_hosts[@]}"; do
  if echo "$parent_remotes" | grep -qx "$h"; then
    echo "§6.W VIOLATION: parent repo has '$h' remote (forbidden — only github + gitlab permitted)" >&2
    echo "  → git remote remove $h" >&2
    exit 1
  fi
done
# vasic-digital submodules (every submodules/* — none is HelixDevelopment-owned)
#
# §6.J corpus floor (LVA vacuous-pass sweep F19): `[[ -d "$sub/.git" ]] ||
# continue` skipped any submodule without a .git marker IN SILENCE, so a
# forbidden remote inside an uninitialised submodule was never inspected and
# §6.W reported clean:
#
#   REPRO: submodules/auth carries a 'gitverse' remote
#     with .git present -> §6.W VIOLATION: submodules/auth/ has 'gitverse'  EXIT=1
#     with .git absent   -> REACHED-CLEAN (§6.W block passed)               EXIT=0
#
# The expectation is DERIVED from .gitmodules, which is the repository's own
# declaration of how many submodules exist — never a hardcoded number, which
# would go stale the moment a submodule is added or removed.
w_declared=0
while read -r _decl; do
  case "$_decl" in submodules/*) ;; *) continue ;; esac
  w_declared=$((w_declared + 1))
done < <(sed -n 's/^[[:space:]]*path = //p' .gitmodules 2>/dev/null)

w_examined=0
w_unexaminable=()
for sub in submodules/*/; do
  [[ -d "$sub" ]] || continue
  if [[ ! -d "$sub/.git" && ! -f "$sub/.git" ]]; then
    w_unexaminable+=("${sub%/}")
    continue
  fi
  sub_remote_rc=0
  sub_remotes="$(git -C "$sub" remote 2>/dev/null)" || sub_remote_rc=$?
  if [[ "$sub_remote_rc" -ne 0 ]]; then
    w_unexaminable+=("${sub%/} (git remote exited ${sub_remote_rc})")
    continue
  fi
  w_examined=$((w_examined + 1))
  for h in "${forbidden_remote_hosts[@]}"; do
    if echo "$sub_remotes" | grep -qx "$h"; then
      echo "§6.W VIOLATION: $sub has '$h' remote (only github + gitlab permitted for Lava-owned submodules)" >&2
      echo "  → git -C $sub remote remove $h" >&2
      exit 1
    fi
  done
done

if [[ "$w_declared" -gt 0 && "$w_examined" -lt "$w_declared" ]]; then
  echo "§6.W CHECK INCOMPLETE: the remote-host boundary was verified on a PARTIAL corpus." >&2
  echo "  → Examined: ${w_examined} submodule(s)" >&2
  echo "  → Expected: ${w_declared} (derived from .gitmodules)" >&2
  echo "  → Not examinable — each was skipped in silence before this floor existed," >&2
  echo "    so a forbidden remote inside any of them would go unreported:" >&2
  for f in "${w_unexaminable[@]}"; do echo "      $f" >&2; done
  echo "  → Cause distinguished: a submodule with no .git marker is NOT INITIALISED —" >&2
  echo "    a checkout artifact, not a §6.W violation. It still cannot be cleared." >&2
  echo "  → Do: git submodule update --init --recursive, then re-run." >&2
  exit 1
fi
# END-OF-BLOCK §6.W remote-host boundary (regression-harness sentinel)

# -----------------------------------------------------------------------------
# §6.AD-debt item 4 + HelixConstitution §11.4.6 — no-guessing-vocabulary
# grep gate. Delegated to scripts/check-no-guessing-vocabulary.sh (extracted
# 2026-05-17, 1.2.30-1050 cycle) so the gate is independently testable via
# tests/check-constitution/test_no_guessing_vocabulary.sh.
# -----------------------------------------------------------------------------
if ! bash "$(dirname "${BASH_SOURCE[0]}")/check-no-guessing-vocabulary.sh"; then
  exit 1
fi

echo "Constitution check passed: 6.D + 6.E + 6.F present in CLAUDE.md;"
echo "submodules/tracker_sdk/CLAUDE.md present; core/ + feature/ scoped"
echo "clauses present; no clause-6.H credential patterns in tracked files;"
echo "clause-6.K Containers extension present; §6.X Container-Submodule"
echo "Emulator Wiring inherited in all submodule + lava-api-go docs;"
echo "§6.X runtime checks (a) Containerized impl + (b) --runner flag active;"
echo "§6.AD HelixConstitution clause + constitution submodule + 54 per-scope"
echo "inheritance pointer-blocks present; §6.AI/§6.AJ clauses + 9 universal"
echo "CM-COVENANT-114-* §11.4.128–141 propagation anchors present;"
echo "§6.W remote-host boundary clean; §11.4.6 no-guessing vocabulary gate clean."
