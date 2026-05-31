#!/usr/bin/env bash
# test_markdown_export_sync.sh — hermetic falsifiability test for the
# CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC gate (§11.4.65 / CONST-066).
#
# §6.A real-binary contract test: exercises the REAL generator + gate against a
# synthetic temp-repo fixture so deleting/backdating a sibling provably flips the
# gate verdict (and restoring flips it back).
#
# The fixture is a self-contained directory laid out like the parent repo's
# in-scope tree (root *.md + docs/ + scripts/), with a copy of the real
# sync-markdown-exports.sh + check-markdown-export-sync.sh. Because both scripts
# resolve ROOT from their own BASH_SOURCE location, copying them into the fixture
# makes them operate on the fixture tree — no live-tree mutation occurs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SYNC_SRC="$ROOT/scripts/sync-markdown-exports.sh"
GATE_SRC="$ROOT/scripts/check-markdown-export-sync.sh"

PASS=0
FAIL=0
check() {
  local desc="$1"; local want="$2"; local got="$3"
  if [[ "$want" == "$got" ]]; then
    echo "  PASS: $desc (expected=$want got=$got)"
    PASS=$((PASS+1))
  else
    echo "  FAIL: $desc (expected=$want got=$got)"
    FAIL=$((FAIL+1))
  fi
}

echo "test_markdown_export_sync.sh — falsifiability rehearsal"
echo "======================================================="

# Preflight: tooling required by the real scripts.
if ! command -v pandoc >/dev/null 2>&1 || ! command -v weasyprint >/dev/null 2>&1; then
  echo "  SKIP: pandoc/weasyprint not present — gate cannot run hermetically"
  exit 0
fi

FIX="$(mktemp -d -t md-export-fixture.XXXXXX)"
cleanup() { rm -rf "$FIX"; }
trap cleanup EXIT

mkdir -p "$FIX/scripts" "$FIX/docs"
cp "$SYNC_SRC" "$FIX/scripts/sync-markdown-exports.sh"
cp "$GATE_SRC" "$FIX/scripts/check-markdown-export-sync.sh"
chmod +x "$FIX/scripts/"*.sh

# In-scope fixture markdown (root + docs/).
printf '# Fixture Root\n\nHello.\n'        > "$FIX/README.md"
printf '# Fixture Doc\n\nWorld.\n'         > "$FIX/docs/note.md"
# Out-of-scope: must be ignored by scope filter (no sibling required).
mkdir -p "$FIX/submodules/x"
printf '# Excluded\n\nIgnore me.\n'        > "$FIX/submodules/x/skip.md"

SYNC="$FIX/scripts/sync-markdown-exports.sh"
GATE="$FIX/scripts/check-markdown-export-sync.sh"

# ---------------------------------------------------------------------------
# Positive: backfill then gate (strict) PASSES.
# ---------------------------------------------------------------------------
"$SYNC" --regenerate-all >/dev/null 2>&1

rc=0; LAVA_MARKDOWN_EXPORT_STRICT=strict "$GATE" >/dev/null 2>&1 || rc=$?
check "strict gate PASSES when all siblings synced" "0" "$rc"

# Confirm out-of-scope file got NO siblings (scope exclusion is real).
if [[ -f "$FIX/submodules/x/skip.html" || -f "$FIX/submodules/x/skip.pdf" ]]; then
  check "out-of-scope file excluded from generation" "yes" "no"
else
  check "out-of-scope file excluded from generation" "yes" "yes"
fi

# ---------------------------------------------------------------------------
# Falsifiability #1: delete one sibling → strict gate FAILS.
# ---------------------------------------------------------------------------
rm -f "$FIX/docs/note.html"
rc=0; LAVA_MARKDOWN_EXPORT_STRICT=strict "$GATE" >/dev/null 2>&1 || rc=$?
check "strict gate FAILS on deleted sibling" "1" "$rc"

# Restore → PASSES again.
"$SYNC" --regenerate-all >/dev/null 2>&1
rc=0; LAVA_MARKDOWN_EXPORT_STRICT=strict "$GATE" >/dev/null 2>&1 || rc=$?
check "strict gate PASSES after restore" "0" "$rc"

# ---------------------------------------------------------------------------
# Falsifiability #2: backdate a sibling (mtime < .md) → strict gate FAILS.
# ---------------------------------------------------------------------------
# Make the .md newer than its siblings.
touch -t 200001010000 "$FIX/README.html" "$FIX/README.pdf"
touch "$FIX/README.md"
rc=0; LAVA_MARKDOWN_EXPORT_STRICT=strict "$GATE" >/dev/null 2>&1 || rc=$?
check "strict gate FAILS on backdated (stale) sibling" "1" "$rc"

# Restore → PASSES again.
"$SYNC" --regenerate-all >/dev/null 2>&1
rc=0; LAVA_MARKDOWN_EXPORT_STRICT=strict "$GATE" >/dev/null 2>&1 || rc=$?
check "strict gate PASSES after regeneration" "0" "$rc"

# ---------------------------------------------------------------------------
# Advisory mode never blocks even with a missing sibling.
# ---------------------------------------------------------------------------
rm -f "$FIX/docs/note.pdf"
rc=0; "$GATE" >/dev/null 2>&1 || rc=$?
check "advisory mode exits 0 despite missing sibling" "0" "$rc"

echo ""
echo "Results: PASS=$PASS FAIL=$FAIL"
[[ $FAIL -eq 0 ]] || exit 1
exit 0
