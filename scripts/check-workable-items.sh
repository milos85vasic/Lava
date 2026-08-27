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
# NOTE (2026-08-26): these three classification greps read a HERE-STRING, not a
# `printf … | grep -q` pipeline. Under this script's `set -euo pipefail` that pipeline
# is a SIGPIPE race, and it was firing: `grep -q` exits the instant it matches —
# on the FIRST line here — while `printf` still has the remaining ~21 KB of
# validate output to write, so `printf` dies of SIGPIPE and the PIPELINE status
# becomes 141 even though the pattern MATCHED. Both classification arms then
# evaluate false and the run falls through to the `else`, reporting
# "validate produced unexpected output" for output that is exactly the expected
# `validate: N violation(s):` shape.
#
# MEASURED, 12 consecutive trials on the real 21171-byte validate output:
#   with    pipefail: 141 141 141 141 141 141 141 141 141 141 141 141
#   without pipefail:   0   0   0   0   0   0   0   0   0   0   0   0
#
# It is a race, not a constant, so it presented as flakiness: the same gate
# exited 0 ("all 67-ledger-exempted") and 1 ("unexpected output") minutes apart
# on an unchanged tree, and it intermittently reddened
# tests/check-constitution/test_corpus_floors.sh (case f17) and, through
# CM-WORKABLE-ITEMS-SYNC, the whole verify-all sweep. A here-string has no
# pipeline and therefore no SIGPIPE, so the classification now depends only on
# whether the pattern matches. Same defect class as
# tests/pre-push/pipefail_sigpipe_test.sh guards elsewhere in this repo.
if grep -q '^validate: 0 violation' <<<"$VALIDATE_OUT"; then
  : # clean — nothing to filter
elif grep -q '^validate: [0-9]\+ violation' <<<"$VALIDATE_OUT"; then
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
    # Here-string, same reason as above: `grep -qxF` exits on its first match
    # and would SIGPIPE the writer under pipefail, turning a FOUND exemption
    # into a 141 and therefore into a spurious "unexempted violation".
    if ! grep -qxF "$pair" <<<"$EXEMPT_SET"; then
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

# 2b. §6.J corpus-scope floor (added 2026-08-26, LVA vacuous-pass sweep F17).
#
# Step 2 above passes --issues and --fixed to `diff`, and those are the ONLY two
# markdown renderings it inspects. The tracker artifact is four files, not two:
# `export` (constitution/scripts/workable-items/cmd/workable-items/export.go:85-133)
# also writes Issues_Summary.md and Fixed_Summary.md beside them. Those two were
# outside the gate's corpus entirely, so they could carry arbitrary text and the
# gate still reported "DB ↔ Markdown in sync":
#
#   REPRO (real DB, real Issues.md/Fixed.md, both _Summary files replaced with
#          "TOTALS ARE FABRICATED: 9999 open items, none of which exist."):
#     diff: DB and Markdown are in sync                                  EXIT=0
#   CONTROL (same tree, one byte appended to Issues.md instead):
#     ~ LVA-152 body differs ... diff: 1 difference(s)                   EXIT=1
#
# A gate that certifies "in sync" having read half the artifact asserts nothing
# about the other half. The binary's `diff` subcommand takes no _Summary flags
# (`workable-items diff --help` offers only -db/-issues/-fixed), so the check is
# done here by REGENERATING the summaries from the same DB into a temp dir with
# `export --no-formats` and comparing byte-for-byte. Same source of truth, same
# renderer, no reimplementation of the format on the Lava side.
SUMMARY_DIR="$(dirname "$ISSUES")"
ISSUES_SUMMARY="${LAVA_WORKABLE_ITEMS_ISSUES_SUMMARY:-$SUMMARY_DIR/Issues_Summary.md}"
FIXED_SUMMARY="${LAVA_WORKABLE_ITEMS_FIXED_SUMMARY:-$(dirname "$FIXED")/Fixed_Summary.md}"

_missing_summaries=()
[[ -f "$ISSUES_SUMMARY" ]] || _missing_summaries+=("$ISSUES_SUMMARY")
[[ -f "$FIXED_SUMMARY" ]]  || _missing_summaries+=("$FIXED_SUMMARY")
if [[ ${#_missing_summaries[@]} -gt 0 ]]; then
  echo "CM-WORKABLE-ITEMS-SYNC: summary tracker(s) ABSENT — the gate would certify a partial artifact." >&2
  echo "  → Examined: Issues.md + Fixed.md (2 of the 4 files 'export' produces)" >&2
  echo "  → Missing:" >&2
  printf '      %s\n' "${_missing_summaries[@]}" >&2
  echo "  → Cause distinguished: these are OUTPUT files of the same renderer, so an" >&2
  echo "    absent one is a regeneration that never ran (or was reverted), not an" >&2
  echo "    optional artifact." >&2
  echo "  → Do: $WI_BIN export --no-formats --db $DB --out-issues $ISSUES --out-fixed $FIXED" >&2
  exit 1
fi

_wi_tmp="$(mktemp -d)"
trap 'rm -rf "$_wi_tmp"' EXIT
_wi_abs() { case "$1" in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "$1" ;; esac; }
if ! "$WI_BIN" export --no-formats \
        --db "$(_wi_abs "$DB")" \
        --out-issues "$_wi_tmp/Issues.md" \
        --out-fixed  "$_wi_tmp/Fixed.md" >/dev/null 2>"$_wi_tmp/export.err"; then
  echo "CM-WORKABLE-ITEMS-SYNC: could not regenerate the summary trackers for comparison." >&2
  echo "  → Examined: 0 of 2 summary files — the comparison itself failed to run." >&2
  echo "  → Cause distinguished: this is a TOOLING failure, not a sync verdict. Skipping" >&2
  echo "    it would let the summary half of the artifact go unchecked in silence." >&2
  sed 's/^/      /' "$_wi_tmp/export.err" >&2
  echo "  → Do: verify $WI_BIN runs on this host and that $DB is readable, then re-run." >&2
  exit 1
fi

_summary_drift=()
cmp -s "$_wi_tmp/Issues_Summary.md" "$ISSUES_SUMMARY" || _summary_drift+=("$ISSUES_SUMMARY")
cmp -s "$_wi_tmp/Fixed_Summary.md"  "$FIXED_SUMMARY"  || _summary_drift+=("$FIXED_SUMMARY")
if [[ ${#_summary_drift[@]} -gt 0 ]]; then
  echo "CM-WORKABLE-ITEMS-SYNC: summary tracker(s) are STALE against the DB:" >&2
  printf '      %s\n' "${_summary_drift[@]}" >&2
  echo "  → Examined: 4 of 4 rendered files (Issues.md, Fixed.md via 'diff'; both" >&2
  echo "    _Summary files by regenerating from the same DB and comparing bytes)." >&2
  echo "  → First differing line(s):" >&2
  for _d in "${_summary_drift[@]}"; do
    _b="$(basename "$_d")"
    echo "      --- $_d" >&2
    diff -u "$_d" "$_wi_tmp/$_b" 2>/dev/null | sed -n '4,12p' | sed 's/^/        /' >&2 || true
  done
  echo "  → Do: $WI_BIN export --no-formats --db $DB --out-issues $ISSUES --out-fixed $FIXED" >&2
  exit 1
fi

# 3. §11.4.95 — the DB MUST be tracked in git (never gitignored).
if ! git ls-files --error-unmatch "$DB" >/dev/null 2>&1; then
  echo "CM-WORKABLE-ITEMS-SYNC: $DB is NOT git-tracked (§11.4.95 CM-WORKABLE-ITEMS-DB-TRACKED) — run: git add $DB" >&2
  exit 1
fi

echo "CM-WORKABLE-ITEMS-SYNC: OK — $DB validated, all 4 rendered trackers (Issues, Fixed, Issues_Summary, Fixed_Summary) in sync with the DB, DB tracked"
