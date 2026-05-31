#!/usr/bin/env bash
# check-markdown-export-sync.sh — CM-UNIVERSAL-MARKDOWN-EXPORT-SYNC gate
#
# §11.4.65 / CONST-066: thin gate wrapper around
# `scripts/sync-markdown-exports.sh --check-only`. Every in-scope .md MUST have
# synchronized .html + .pdf siblings; missing or stale siblings fail the gate.
#
# Advisory by default; strict when LAVA_MARKDOWN_EXPORT_STRICT=strict|1|true.
# (Convention mirrors check-coverage-ledger.sh.)
#
# §11.4.18 companion doc: docs/scripts/check-markdown-export-sync.sh.md
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

STRICT_MODE="advisory"
case "${LAVA_MARKDOWN_EXPORT_STRICT:-}" in
  strict|1|true) STRICT_MODE="strict" ;;
esac

SYNC="scripts/sync-markdown-exports.sh"

if [[ ! -x "$SYNC" ]]; then
  echo "  [markdown-export] FAIL: generator missing/not executable: $SYNC"
  exit 1
fi

rc=0
"$SYNC" --check-only || rc=$?

if [[ $rc -eq 0 ]]; then
  echo "  [markdown-export] OK: all in-scope markdown siblings present and current"
  exit 0
fi

echo "  [markdown-export] FAIL: missing or stale .html/.pdf siblings (rc=$rc)"
echo "  [markdown-export] Fix with: $SYNC --regenerate-all"
if [[ "$STRICT_MODE" == "strict" ]]; then
  exit 1
fi
echo "  [markdown-export] (advisory mode — not blocking)"
exit 0
