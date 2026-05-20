#!/usr/bin/env bash
# test_06_falsifiability.sh — LAYER 6: proves THIS SUITE IS NOT A BLUFF.
#
# Sixth Law clause 2 / §6.J clause 2 require every test to be provably
# falsifiable: it MUST fail when the thing it verifies is deliberately broken.
# This file performs that rehearsal mechanically and repeatably:
#
#   1. moves .codegraph/codegraph.db aside (deliberate break);
#   2. re-runs layers 01, 02, 03 and asserts every one of them now FAILS;
#   3. restores the database;
#   4. re-runs layers 01, 02, 03 and asserts every one of them PASSES again.
#
# If a layer test PASSED with the codegraph database removed, that layer is a
# bluff test and this file FAILS loudly. A trap restores the database even if
# the script is interrupted, so a crash cannot leave the index broken.
TEST_NAME="06_falsifiability"
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

log "== LAYER 6: falsifiability rehearsal (the suite proves itself) =="

DB="$REPO_ROOT/.codegraph/codegraph.db"
BAK="$REPO_ROOT/.codegraph/codegraph.db.falsify-bak"
LAYERS="test_01_index_reality test_02_query_correctness test_03_mcp_protocol"

restore_db() {
  # remove any empty DB codegraph may have recreated, then restore the real one
  [ -f "$BAK" ] && { rm -f "$DB" "$DB-shm" "$DB-wal" 2>/dev/null; mv -f "$BAK" "$DB"; }
}
trap 'restore_db' EXIT INT TERM

if [ ! -f "$DB" ]; then
  fail "codegraph.db not present at start — cannot run the falsifiability rehearsal"
  finish; exit $?
fi

# --- step 1: deliberate break -------------------------------------------
mv -f "$DB" "$BAK"
info "deliberate break applied: .codegraph/codegraph.db moved aside"

# --- step 2: every layer MUST now fail ----------------------------------
broke_ok=1
for t in $LAYERS; do
  if bash "$LIB_DIR/$t.sh" >/dev/null 2>&1; then
    fail "$t PASSED with the codegraph DB removed — IT IS A BLUFF TEST"
    broke_ok=0
  else
    pass "$t correctly FAILS when codegraph is broken (provably falsifiable)"
  fi
done

# --- step 3: restore -----------------------------------------------------
restore_db
trap - EXIT INT TERM
info "codegraph.db restored"

# --- step 4: every layer MUST pass again --------------------------------
for t in $LAYERS; do
  if bash "$LIB_DIR/$t.sh" >/dev/null 2>&1; then
    pass "$t PASSES again after codegraph is restored (break was the cause)"
  else
    fail "$t still fails after restore — restore failed or codegraph is broken"
  fi
done

[ "$broke_ok" -eq 1 ] || fail "one or more layers were bluff tests — see above"

finish; exit $?
