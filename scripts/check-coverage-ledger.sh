#!/usr/bin/env bash
# scripts/check-coverage-ledger.sh — §11.4.25 ledger verifier.
#
# Per HelixConstitution §11.4.25 (Full-Automation-Coverage Mandate): the
# coverage ledger MUST exist, MUST be fresh (regenerated state matches
# committed state), AND MUST contain a row for every feature/* + core/* +
# app + lava-api-go + submodules/* unit. Stale OR incomplete ledgers are
# §11.4.25 violations.
#
# This script:
#   1. Asserts `docs/coverage-ledger.yaml` exists with the expected
#      schema_version + metadata block.
#   2. Asserts every feature/* + core/* + submodules/* + app + lava-api-go
#      path on disk has a row in the ledger.
#   3. Asserts the ledger is FRESH: regenerates it into a tmp file via
#      `scripts/generate-coverage-ledger.sh` and diffs the row content
#      (metadata + generated_at timestamp stripped to avoid spurious
#      diffs); reject if any row content differs.
#
# Modes:
#   --strict (default): exit 1 on any violation
#   --advisory:         exit 0 even on violation (incremental adoption)
#   LAVA_COVERAGE_LEDGER_STRICT=0 env: same as --advisory
#
# Inheritance: HelixConstitution §11.4.25 + §11.4.18 (script docs) + §6.J/§6.L
# (anti-bluff posture — the verifier itself MUST detect ledger staleness, not
# accept "close enough").
# Classification: project-specific (the Lava module list is project-specific;
# the §11.4.25 ledger mandate is universal per HelixConstitution).

set -uo pipefail

# Verifier script's own location — used to locate the generator script
# regardless of how LAVA_REPO_ROOT points (synthetic fixture vs real tree).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GENERATOR="$SCRIPT_DIR/generate-coverage-ledger.sh"

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$REPO_ROOT"

STRICT="${LAVA_COVERAGE_LEDGER_STRICT:-1}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)   STRICT=1; shift ;;
        --advisory) STRICT=0; shift ;;
        -h|--help)  sed -n '3,30p' "$0"; exit 0 ;;
        *)          echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

LEDGER_PATH="docs/coverage-ledger.yaml"

violations=()

# -----------------------------------------------------------------------------
# Check 1: ledger file exists + has minimal expected structure
# -----------------------------------------------------------------------------
if [[ ! -f "$LEDGER_PATH" ]]; then
    violations+=("$LEDGER_PATH: missing — regenerate via scripts/generate-coverage-ledger.sh")
else
    if ! grep -qE '^metadata:[[:space:]]*$' "$LEDGER_PATH"; then
        violations+=("$LEDGER_PATH: missing metadata: block")
    fi
    if ! grep -qE '^[[:space:]]+schema_version:[[:space:]]+1[[:space:]]*$' "$LEDGER_PATH"; then
        violations+=("$LEDGER_PATH: missing or wrong schema_version (must be 1)")
    fi
    if ! grep -qE '^rows:[[:space:]]*$' "$LEDGER_PATH"; then
        violations+=("$LEDGER_PATH: missing rows: block")
    fi
fi

# -----------------------------------------------------------------------------
# Check 2: every on-disk path has a row in the ledger
# -----------------------------------------------------------------------------
declare -a expected_paths=()

if [[ -d feature ]]; then
    while IFS= read -r p; do
        expected_paths+=("$p")
    done < <(find feature -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
fi
if [[ -d core ]]; then
    while IFS= read -r p; do
        expected_paths+=("$p")
    done < <(find core -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
fi
[[ -d app ]] && expected_paths+=("app")
[[ -d lava-api-go ]] && expected_paths+=("lava-api-go")
if [[ -d submodules ]]; then
    while IFS= read -r p; do
        expected_paths+=("$p")
    done < <(find submodules -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
fi

missing_rows=()
if [[ -f "$LEDGER_PATH" ]]; then
    for p in "${expected_paths[@]}"; do
        # Match `  - path: "<p>"` as a row header.
        if ! grep -qE "^[[:space:]]+-[[:space:]]+path:[[:space:]]+\"${p//\//\\/}\"[[:space:]]*$" "$LEDGER_PATH"; then
            missing_rows+=("$p")
        fi
    done
    if [[ ${#missing_rows[@]} -gt 0 ]]; then
        violations+=("$LEDGER_PATH: missing rows for ${#missing_rows[@]} on-disk paths")
    fi
fi

# -----------------------------------------------------------------------------
# Check 3: ledger is FRESH (regenerated state matches committed state).
# Strip metadata block + generated_at timestamp before diffing so changes
# in those alone don't fail the check.
# -----------------------------------------------------------------------------
stale_diff=""
if [[ -f "$LEDGER_PATH" && -x "$GENERATOR" ]]; then
    fresh=$(mktemp)
    "$GENERATOR" --stdout >"$fresh" 2>/dev/null || true

    # Strip metadata block (lines from `metadata:` through the blank line
    # before `rows:`) and any `generated_at:` line, leaving only the rows.
    strip() {
        awk '
            /^metadata:/   { in_meta = 1; next }
            in_meta && /^rows:/ { in_meta = 0; print; next }
            in_meta        { next }
            /generated_at:/{ next }
            { print }
        ' "$1"
    }
    if ! diff -u <(strip "$LEDGER_PATH") <(strip "$fresh") >/dev/null 2>&1; then
        stale_diff=$(diff -u <(strip "$LEDGER_PATH") <(strip "$fresh") | head -50)
        violations+=("$LEDGER_PATH: STALE — committed content differs from regenerated content; run scripts/generate-coverage-ledger.sh to refresh")
    fi
    rm -f "$fresh"
fi

# -----------------------------------------------------------------------------
# Report
# -----------------------------------------------------------------------------
echo "==> §11.4.25 coverage ledger verifier"
echo ""
echo "    Ledger: $LEDGER_PATH"
echo "    Expected rows: ${#expected_paths[@]}"
if [[ ${#missing_rows[@]} -eq 0 ]]; then
    echo "    Row coverage: ✓ every on-disk path has a row"
else
    echo "    Row coverage: ✗ ${#missing_rows[@]} on-disk paths missing a row"
fi

if [[ ${#violations[@]} -eq 0 ]]; then
    echo ""
    echo "    ✓ ledger is well-formed, complete, and fresh"
    exit 0
fi

echo ""
echo "    Violations:"
for v in "${violations[@]}"; do
    echo "      ✗ $v"
done
if [[ ${#missing_rows[@]} -gt 0 ]]; then
    echo ""
    echo "    Missing rows:"
    for r in "${missing_rows[@]}"; do
        echo "      $r"
    done
fi
if [[ -n "$stale_diff" ]]; then
    echo ""
    echo "    Staleness diff (first 50 lines):"
    echo "$stale_diff" | sed 's/^/      /'
fi

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_COVERAGE_LEDGER_STRICT=1 — failing." >&2
    exit 1
fi
exit 0
