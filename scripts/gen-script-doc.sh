#!/usr/bin/env bash
# scripts/gen-script-doc.sh — one-time generator for docs/scripts/X.sh.md stubs.
#
# Closes §6.AD-debt follow-up (task #61): backfill 19 missing companion
# docs so CM-SCRIPT-DOCS-SYNC pre-push gate (Check 9) starts gating those
# scripts too.
#
# Usage:
#   bash scripts/gen-script-doc.sh                          # all missing
#   bash scripts/gen-script-doc.sh scripts/foo.sh           # only this one
#
# Idempotent: skips files that already have a doc.
#
# Inheritance: HelixConstitution §11.4.18 (script documentation mandate).
# Classification: project-specific (the generator output shape is Lava-side).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

mkdir -p docs/scripts

declare -a TARGETS=()
if [[ $# -eq 0 ]]; then
    while IFS= read -r s; do
        bn=$(basename "$s")
        if [[ ! -f "docs/scripts/${bn}.md" ]]; then
            TARGETS+=("$s")
        fi
    done < <(ls scripts/*.sh)
else
    TARGETS=("$@")
fi

extract_purpose() {
    local f=$1
    # Read lines 2-N, strip leading "# ", until first blank-comment line.
    awk '
        NR == 1 { next }                      # skip shebang
        /^# / { sub(/^# /, ""); print; next } # comment line: emit
        /^#$/ { print ""; next }              # blank comment: emit blank
        /^[^#]/ { exit }                      # first non-comment line: stop
    ' "$f"
}

ADDED=0
SKIPPED=0
for s in "${TARGETS[@]}"; do
    bn=$(basename "$s")
    doc="docs/scripts/${bn}.md"
    if [[ -f "$doc" ]]; then
        echo "  ✓ $doc already present"
        SKIPPED=$((SKIPPED + 1))
        continue
    fi
    purpose=$(extract_purpose "$s")
    cat > "$doc" <<DOC
# \`scripts/${bn}\` — User Guide

**Last verified:** 2026-05-15 (1.2.23 closure-cycle, §6.AD-debt task #61 backfill)
**Inheritance:** HelixConstitution §11.4.18 (script documentation mandate)

## Overview

Stub doc generated from the script's in-source header comment. See \`scripts/${bn}\` for canonical behavior. This stub exists so the \`CM-SCRIPT-DOCS-SYNC\` pre-push gate (\`.githooks/pre-push\` Check 9) starts gating modifications to this script.

The script's own header documentation (verbatim):

\`\`\`
${purpose}
\`\`\`

## Usage

See the script's in-source comment block (above) for canonical usage examples.

## Maintenance

When this script is modified, update this document in the same commit (CM-SCRIPT-DOCS-SYNC requires it). Per §11.4.18, the documentation MUST stay in sync with the codebase — no doc may be out of sync with its script.

## Cross-references

- \`scripts/${bn}\` — the script itself
- \`docs/helix-constitution-gates.md\` — gate inventory
- HelixConstitution \`Constitution.md\` §11.4.18 (the mandate)
- Lava \`CLAUDE.md\` §6.AD (HelixConstitution Inheritance)
DOC
    echo "  + wrote $doc"
    ADDED=$((ADDED + 1))
done

echo ""
echo "Summary: added=${ADDED} skipped=${SKIPPED}"
