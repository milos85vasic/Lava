#!/usr/bin/env bash
# tests/check-lva-tickets/test_check_lva_tickets.sh
#
# Hermetic falsifiability test for scripts/check-lva-tickets.sh
# (CM-LVA-TICKETS-SYNC, §11.4.93 / §11.4.95 / §11.4.106).
#
# Pattern (mirrors tests/check-constitution/test_coverage_ledger.sh): build a
# throwaway LVA ticket DB + generated trackers in a tmp dir, point the gate at
# them via LAVA_LVA_TICKETS_DB / LAVA_LVA_TICKETS_OUT, and exercise the gate in
# both PASS and FAIL configurations. The gate MUST pass on a clean DB+docs and
# MUST fail on each deliberate mutation:
#   - a corrupted generated tracker (.md no longer byte-identical, §11.4.106)
#   - a dropped schema-integrity trigger (§11.4.33/34)
#   - the DB gitignored (§11.4.95)
#   - the DB missing entirely (§11.4.93)
# A gate that passes on a mutation is a §6.J bluff.
#
# §6.A real-binary contract: the test uses the ACTUAL lava-tickets binary (built
# here if absent) and a real sqlite3 — it does not stub verify or the DB.
#
# Run: bash tests/check-lva-tickets/test_check_lva_tickets.sh
# Exit 0 = all sub-tests passed; non-zero = a sub-test failed (bluff detected).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
GATE="$REPO_ROOT/scripts/check-lva-tickets.sh"
TOOL_DIR="$REPO_ROOT/tools/lava-tickets"
BIN="$TOOL_DIR/bin/lava-tickets"
SCHEMA="$REPO_ROOT/docs/tickets/schema.sql"

PASS=0
FAIL=0
ok()  { echo "  ok: $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

echo "=== test_check_lva_tickets.sh ==="
echo "gate:   $GATE"
echo "binary: $BIN"

# ---------------------------------------------------------------------------
# Prerequisites — build the real binary if needed; discover sqlite3.
# ---------------------------------------------------------------------------
if [[ ! -x "$BIN" ]]; then
    if command -v go >/dev/null 2>&1; then
        echo "  (building lava-tickets binary ...)"
        ( cd "$TOOL_DIR" && GOMAXPROCS=2 go build -o bin/lava-tickets . ) || {
            echo "  FATAL: go build failed — cannot run hermetic test"; exit 2; }
    else
        echo "  FATAL: lava-tickets binary missing and go not found"; exit 2
    fi
fi

find_sqlite3() {
    if command -v sqlite3 >/dev/null 2>&1; then command -v sqlite3; return 0; fi
    local cand
    for cand in \
        /opt/homebrew/bin/sqlite3 /usr/local/bin/sqlite3 /usr/bin/sqlite3 \
        "${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/sqlite3" \
        "${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}/platform-tools/sqlite3"; do
        [[ -x "$cand" ]] && { echo "$cand"; return 0; }
    done
    return 1
}
SQLITE3="$(find_sqlite3 || true)"
if [[ -z "$SQLITE3" ]]; then
    echo "  FATAL: sqlite3 not found — trigger-drop sub-test cannot run"; exit 2
fi
echo "sqlite3: $SQLITE3"

# ---------------------------------------------------------------------------
# Helper: build a clean throwaway DB + generated trackers in $1.
# ---------------------------------------------------------------------------
make_fixture() {
    local root="$1"
    mkdir -p "$root/out"
    cp "$SCHEMA" "$root/schema.sql"
    "$BIN" init --db "$root/t.db" --schema "$root/schema.sql" >/dev/null 2>&1
    "$BIN" add  --db "$root/t.db" --title "hermetic fixture item" --type Task >/dev/null 2>&1
    "$BIN" gen  --db "$root/t.db" --out "$root/out" >/dev/null 2>&1
}

run_gate() {  # $1=db $2=out  -> echoes exit code
    local rc=0
    LAVA_LVA_TICKETS_DB="$1" LAVA_LVA_TICKETS_OUT="$2" \
        "$GATE" --strict >/dev/null 2>&1 || rc=$?
    echo "$rc"
}

# ---------------------------------------------------------------------------
# Sub-test 1: clean DB + docs PASSES (positive path).
# ---------------------------------------------------------------------------
t1="$(mktemp -d)"
make_fixture "$t1"
rc=$(run_gate "$t1/t.db" "$t1/out")
if [[ "$rc" -eq 0 ]]; then ok "clean DB+docs passes (strict)"
else bad "clean DB+docs should pass (rc=$rc)"; LAVA_LVA_TICKETS_DB="$t1/t.db" LAVA_LVA_TICKETS_OUT="$t1/out" "$GATE" --strict; fi
rm -rf "$t1"

# ---------------------------------------------------------------------------
# Sub-test 2: corrupted tracker FAILS (§11.4.106 byte-identity).
# ---------------------------------------------------------------------------
t2="$(mktemp -d)"
make_fixture "$t2"
printf '\n<!-- tamper -->\n' >> "$t2/out/Issues.md"
rc=$(run_gate "$t2/t.db" "$t2/out")
if [[ "$rc" -ne 0 ]]; then ok "corrupted tracker fails (§11.4.106)"
else bad "corrupted tracker should fail"; fi
# restore by regenerating → PASS again (proves the FAIL was the corruption, not noise)
"$BIN" gen --db "$t2/t.db" --out "$t2/out" >/dev/null 2>&1
rc=$(run_gate "$t2/t.db" "$t2/out")
if [[ "$rc" -eq 0 ]]; then ok "restored tracker passes again"
else bad "restored tracker should pass (rc=$rc)"; fi
rm -rf "$t2"

# ---------------------------------------------------------------------------
# Sub-test 3: dropped §11.4.34 trigger FAILS (schema-integrity guard).
# ---------------------------------------------------------------------------
t3="$(mktemp -d)"
make_fixture "$t3"
"$SQLITE3" "$t3/t.db" "DROP TRIGGER trg_reopen_attribution;" >/dev/null 2>&1
rc=$(run_gate "$t3/t.db" "$t3/out")
if [[ "$rc" -ne 0 ]]; then ok "dropped §11.4.34 trigger fails"
else bad "dropped trigger should fail"; fi
rm -rf "$t3"

# ---------------------------------------------------------------------------
# Sub-test 3b: dropped §11.4.33 closure trigger FAILS.
# ---------------------------------------------------------------------------
t3b="$(mktemp -d)"
make_fixture "$t3b"
"$SQLITE3" "$t3b/t.db" "DROP TRIGGER trg_closure_status_typeaware;" >/dev/null 2>&1
rc=$(run_gate "$t3b/t.db" "$t3b/out")
if [[ "$rc" -ne 0 ]]; then ok "dropped §11.4.33 trigger fails"
else bad "dropped §11.4.33 trigger should fail"; fi
rm -rf "$t3b"

# ---------------------------------------------------------------------------
# Sub-test 4: gitignored DB FAILS (§11.4.95) — fixture is a real tiny git repo.
# ---------------------------------------------------------------------------
if command -v git >/dev/null 2>&1; then
    t4="$(mktemp -d)"
    make_fixture "$t4"
    (
        cd "$t4"
        git init -q
        git config user.email t@example.com
        git config user.name t
        # gitignore the DB → §11.4.95 violation
        echo "t.db" > .gitignore
    )
    rc=$(run_gate "$t4/t.db" "$t4/out")
    if [[ "$rc" -ne 0 ]]; then ok "gitignored DB fails (§11.4.95)"
    else bad "gitignored DB should fail"; fi
    # remove the gitignore → PASS again
    rm -f "$t4/.gitignore"
    rc=$(run_gate "$t4/t.db" "$t4/out")
    if [[ "$rc" -eq 0 ]]; then ok "un-gitignored DB passes again"
    else bad "un-gitignored DB should pass (rc=$rc)"; fi
    rm -rf "$t4"
else
    echo "  skip: git not found — §11.4.95 gitignore sub-test skipped"
fi

# ---------------------------------------------------------------------------
# Sub-test 5: missing DB FAILS (§11.4.93).
# ---------------------------------------------------------------------------
t5="$(mktemp -d)"
mkdir -p "$t5/out"
rc=$(run_gate "$t5/does-not-exist.db" "$t5/out")
if [[ "$rc" -ne 0 ]]; then ok "missing DB fails (§11.4.93)"
else bad "missing DB should fail"; fi
rm -rf "$t5"

# ---------------------------------------------------------------------------
# Sub-test 6: advisory mode exits 0 even on violation.
# ---------------------------------------------------------------------------
t6="$(mktemp -d)"
make_fixture "$t6"
"$SQLITE3" "$t6/t.db" "DROP TRIGGER trg_reopen_attribution;" >/dev/null 2>&1
rc=0
LAVA_LVA_TICKETS_DB="$t6/t.db" LAVA_LVA_TICKETS_OUT="$t6/out" \
    "$GATE" --advisory >/dev/null 2>&1 || rc=$?
if [[ "$rc" -eq 0 ]]; then ok "advisory mode exits 0 on violation"
else bad "advisory should exit 0 (rc=$rc)"; fi
# env-var form of advisory
rc=0
LAVA_LVA_TICKETS_STRICT=0 LAVA_LVA_TICKETS_DB="$t6/t.db" LAVA_LVA_TICKETS_OUT="$t6/out" \
    "$GATE" >/dev/null 2>&1 || rc=$?
if [[ "$rc" -eq 0 ]]; then ok "LAVA_LVA_TICKETS_STRICT=0 exits 0 on violation"
else bad "env advisory should exit 0 (rc=$rc)"; fi
rm -rf "$t6"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "=== results: $PASS passed, $FAIL failed ==="
[[ $FAIL -eq 0 ]] || exit 1
exit 0
