#!/usr/bin/env bash
# check-workable-items.sh — CM-WORKABLE-ITEMS-SYNC gate
#
# §11.4.93 + §11.4.95: Lava tracks workable items via the CANONICAL
# workable-items Go binary in the constitution submodule
# (constitution/scripts/workable-items/), keyed LVA-N. The SQLite SSoT
# at docs/workable_items.db (TRACKED in git, §11.4.95) MUST stay in sync
# with the generated Markdown trackers (docs/Issues.md, docs/Fixed.md).
#
# This gate:
#   1. builds the canonical binary if absent (from inside the module dir;
#      CGO_ENABLED=1 is required — cgo sqlite driver);
#   2. runs `validate` (closed-set + §11.4.91 invariants), filtered against the
#      §6.D-pattern exemption ledger at docs/superpowers/specs/2026-08-20-
#      workable-items-evidence-exemptions.md — every exemption is a named,
#      dated, individually-investigated (atm_id, history_id) pair, never a
#      blanket waiver; a violation absent from the ledger still fails the gate;
#   3. runs `diff` (exit 1 on DB↔Markdown divergence — CM-WORKABLE-ITEMS-MD-DB-IN-SYNC);
#   4. asserts docs/workable_items.db is git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED).
#
# Replaces the retired CM-LVA-TICKETS-SYNC gate (bespoke tools/lava-tickets/
# system, migrated 2026-05-31 per docs/tickets/MIGRATION-TO-CANONICAL.md).
#
# Exit 0 = in sync + tracked; exit 1 = divergence / build failure / DB untracked.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODULE_DIR="constitution/scripts/workable-items"
# Paths are overridable for hermetic testing (e.g. to point the §11.4.95
# DB-tracked assertion at an untracked fixture DB). Defaults are the real tree.
DB="${LAVA_WORKABLE_ITEMS_DB:-docs/workable_items.db}"
ISSUES="${LAVA_WORKABLE_ITEMS_ISSUES:-docs/Issues.md}"
FIXED="${LAVA_WORKABLE_ITEMS_FIXED:-docs/Fixed.md}"

# §11.4.81 cross-platform parity: select a HOST-ARCH-correct workable-items
# binary. The module commits a macOS arm64 Mach-O (bin/workable-items) AND a
# Linux x86_64 ELF (bin/workable-items-linux) side by side; BOTH are kept (never
# deleted) so this gate is runnable on macOS arm64 AND Linux x86_64. A macOS
# Mach-O is still +x on Linux, so the old `[[ ! -x "$WI_BIN" ]]` guard never
# fired and the gate died with "Exec format error". We pick the per-OS binary
# AND prove it actually executes on THIS host (a uname-correct name is necessary,
# not sufficient), rebuilding from canonical source only when no committed binary
# runs here.
BIN_DIR="$MODULE_DIR/bin"
_os="$(uname -s)"
_arch="$(uname -m)"
case "$_os" in
  Linux)  WI_CANDIDATES=("$BIN_DIR/workable-items-linux" "$BIN_DIR/workable-items-linux-$_arch") ;;
  Darwin) WI_CANDIDATES=("$BIN_DIR/workable-items" "$BIN_DIR/workable-items-darwin-$_arch") ;;
  *)      WI_CANDIDATES=("$BIN_DIR/workable-items-$_os-$_arch") ;;
esac

# A binary "runs here" iff it is executable AND does not fail with an
# Exec-format error (`--help` exits 0 on the host arch, 126 otherwise).
_wi_runs() { [[ -x "$1" ]] && "$1" --help >/dev/null 2>&1; }

WI_BIN=""
for _cand in "${WI_CANDIDATES[@]}"; do
  if _wi_runs "$_cand"; then WI_BIN="$_cand"; break; fi
done

# No committed binary runs on this host → rebuild from canonical source into a
# host-arch-tagged path (leaves the committed macOS + Linux binaries untouched).
# go-sqlite3 is a cgo driver, so CGO_ENABLED=1 + a C toolchain are required;
# resource-bounded per §11.4.82 / §12.6 (GOMAXPROCS=2, nice -n 18).
if [[ -z "$WI_BIN" ]]; then
  _built_name="workable-items-$(printf '%s' "$_os" | tr '[:upper:]' '[:lower:]')-$_arch"
  ( cd "$MODULE_DIR" && GOMAXPROCS=2 nice -n 18 \
      env CGO_ENABLED=1 go build -o "bin/$_built_name" ./cmd/workable-items )
  if ! _wi_runs "$BIN_DIR/$_built_name"; then
    echo "CM-WORKABLE-ITEMS-SYNC: no host-runnable workable-items binary for $_os/$_arch and rebuild failed" >&2
    exit 1
  fi
  WI_BIN="$BIN_DIR/$_built_name"
fi

# 1. Validate DB invariants (closed sets + §11.4.91 floor), filtered against
# the §6.D-pattern exemption ledger (docs/superpowers/specs/2026-08-20-workable-items-evidence-exemptions.md
# — same precedent as the SP-3a coverage-exemption ledger). Every exemption is a
# named, dated, investigated (atm_id, history_id) pair; a violation NOT listed
# in the ledger still fails the gate. This is never a blanket waiver — see the
# ledger's own header for what does and does not qualify for an entry.
LEDGER="${LAVA_WORKABLE_ITEMS_EXEMPTIONS:-docs/superpowers/specs/2026-08-20-workable-items-evidence-exemptions.md}"

VALIDATE_OUT="$("$WI_BIN" validate --db "$DB" 2>&1)" || true
if printf '%s\n' "$VALIDATE_OUT" | grep -q '^validate: 0 violation'; then
  : # clean — nothing to filter
elif printf '%s\n' "$VALIDATE_OUT" | grep -q '^validate: [0-9]\+ violation'; then
  # Extract exempted (atm_id, history_id) pairs from the ledger's fenced block.
  EXEMPT_SET=""
  if [[ -f "$LEDGER" ]]; then
    EXEMPT_SET="$(sed -n '/^```exemptions$/,/^```$/p' "$LEDGER" | sed '1d;$d')"
  fi
  UNEXEMPTED=""
  while IFS= read -r line; do
    [[ "$line" == "  - "* ]] || continue
    atm_id="$(printf '%s\n' "$line" | sed -E 's/^  - ([A-Za-z0-9_-]+):.*/\1/')"
    hist_id="$(printf '%s\n' "$line" | grep -oE 'history id=[0-9]+' | grep -oE '[0-9]+')"
    pair="${atm_id}|${hist_id}"
    if ! printf '%s\n' "$EXEMPT_SET" | grep -qxF "$pair"; then
      UNEXEMPTED="${UNEXEMPTED}${line}"$'\n'
    fi
  done <<< "$VALIDATE_OUT"
  if [[ -n "$UNEXEMPTED" ]]; then
    echo "CM-WORKABLE-ITEMS-SYNC: unexempted validate violation(s) — not in $LEDGER:" >&2
    printf '%s' "$UNEXEMPTED" >&2
    exit 1
  fi
  exempted_count="$(printf '%s\n' "$EXEMPT_SET" | grep -c '.' || true)"
  echo "CM-WORKABLE-ITEMS-SYNC: validate found violations, all $exempted_count-ledger-exempted (see $LEDGER) — no fabricated evidence, per-row investigated"
else
  # Unexpected validate output shape (not "0 violation" or "N violation") —
  # a real failure (build error, DB corruption) must not be silently swallowed.
  echo "CM-WORKABLE-ITEMS-SYNC: validate produced unexpected output:" >&2
  printf '%s\n' "$VALIDATE_OUT" >&2
  exit 1
fi

# 2. DB ↔ Markdown sync (CM-WORKABLE-ITEMS-MD-DB-IN-SYNC). `diff` exits 1 on divergence.
if ! "$WI_BIN" diff --db "$DB" --issues "$ISSUES" --fixed "$FIXED"; then
  echo "CM-WORKABLE-ITEMS-SYNC: $ISSUES/$FIXED are stale — regenerate via:" >&2
  echo "  $WI_BIN sync db-to-md --db $DB --out-issues $ISSUES --out-fixed $FIXED" >&2
  exit 1
fi

# 3. §11.4.95 — the DB MUST be tracked in git (never gitignored).
if ! git ls-files --error-unmatch "$DB" >/dev/null 2>&1; then
  echo "CM-WORKABLE-ITEMS-SYNC: $DB is NOT git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED) — run: git add $DB" >&2
  exit 1
fi

echo "CM-WORKABLE-ITEMS-SYNC: OK — $DB validated, DB ↔ Markdown in sync, DB tracked"
