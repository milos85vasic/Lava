#!/usr/bin/env bash
# Asserts §6.R inheritance reference appears in every submodules/*/CLAUDE.md.
#
# Heading-anchored pattern (`## §6.R — No-Hardcoding Mandate`) — a
# passing mention of the bare phrase in a notes/history paragraph MUST
# NOT satisfy the §6.F inheritance gate. The 16 submodule paragraphs
# all use this exact heading prefix; tightening here keeps test, checker
# and clause body in lockstep.
set -euo pipefail
cd "$(dirname "$0")/../.."

# HelixDevelopment-owned submodules are exempt from Lava-specific clause
# heading inheritance — they ship the canonical-root §INHERITED FROM
# Helix Constitution pointer block instead. Mirrors the HELIX_DEV_OWNED
# list in scripts/check-constitution.sh.
HELIX_DEV_OWNED=("HelixQA")
is_helix_dev_owned() {
  local path=$1
  for owned in "${HELIX_DEV_OWNED[@]}"; do
    [[ "$path" == *"/$owned/"* ]] && return 0
    [[ "$path" == *"/$owned"* ]] && return 0
  done
  return 1
}

# A submodule CLAUDE.md satisfies §6.R inheritance if it carries EITHER the
# verbatim Lava clause heading OR the §6.AD-canonical
# `## INHERITED FROM constitution/...` pointer block. §6.AD.8 declares that
# pointer the canonical submodule-inheritance mechanism, and constitution
# §11.4.28 (Submodules-As-Equal-Codebase + Decoupling) FORBIDS injecting
# project-specific clause text into reusable submodules — so the pointer is
# the compliant form for an upstream that has slimmed to inherit-from-
# constitution. The no-hardcoding SOURCE scanners (scan-no-hardcoded-*.sh)
# stay fully strict; this only governs the doc-presence inheritance gate.
# Lockstep with scripts/check-constitution.sh doc_inherits_clause().
missing=()
for sub in submodules/*/CLAUDE.md; do
  is_helix_dev_owned "$sub" && continue
  if ! grep -qF '## §6.R — No-Hardcoding Mandate' "$sub" \
     && ! grep -qE '^## INHERITED FROM constitution/' "$sub"; then
    missing+=("$sub")
  fi
done
if [[ ${#missing[@]} -eq 0 ]]; then
  echo "PASS test_clause_6r_inheritance"
  exit 0
fi
echo "FAIL test_clause_6r_inheritance: missing in:" >&2
printf '  %s\n' "${missing[@]}" >&2
exit 1
