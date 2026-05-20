#!/usr/bin/env bash
# test_02_query_correctness.sh — LAYER 2: proves `codegraph query` resolves
# REAL Lava symbols to their REAL source locations.
#
# Primary assertion (Sixth Law clause 3): a real symbol's real file path — a
# fact codegraph can only produce by genuinely indexing the Lava source.
#
# FALSIFIABILITY is built IN, not just rehearsed:
#   * a query for a symbol that does NOT exist MUST return no Kotlin location;
#   * a query for a symbol that DOES exist MUST return its real path.
#   If codegraph returned canned/garbage data, the negative assertion fails.
#   test_06 additionally removes the DB and re-runs this file: all positive
#   assertions then FAIL. Verified break: `mv .codegraph/codegraph.db /tmp`
#   → "query 'MainActivity' returns the real file path" FAILS (empty result).
TEST_NAME="02_query_correctness"
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

log "== LAYER 2: codegraph query correctness =="

# (a) a known Compose entry point — the MainActivity class
q_main="$(codegraph query MainActivity -l 10 2>&1 || true)"
printf '%s\n' "$q_main" > "$EVIDENCE_DIR/test_02_query_MainActivity.txt"
assert_contains "$q_main" \
  "app/src/main/kotlin/digital/vasic/lava/client/MainActivity.kt" \
  "query 'MainActivity' resolves to the real MainActivity.kt path"
assert_contains "$q_main" "MainActivity.kt:" \
  "query 'MainActivity' returns a concrete line location"

# (b) the Application class
q_app="$(codegraph query LavaApplication -l 10 2>&1 || true)"
printf '%s\n' "$q_app" > "$EVIDENCE_DIR/test_02_query_LavaApplication.txt"
assert_contains "$q_app" \
  "app/src/main/kotlin/digital/vasic/lava/client/LavaApplication.kt" \
  "query 'LavaApplication' resolves to the real LavaApplication.kt path"

# (c) JSON output mode is parseable and carries a real path
q_json="$(codegraph query MainActivity -j -l 5 2>&1 || true)"
if printf '%s' "$q_json" | python3 -c 'import sys,json; json.load(sys.stdin)' >/dev/null 2>&1; then
  pass "query -j emits valid JSON"
  json_has_path="$(printf '%s' "$q_json" | python3 -c '
import sys, json
d = json.load(sys.stdin)
blob = json.dumps(d)
print("yes" if "MainActivity.kt" in blob else "no")
' 2>/dev/null || echo no)"
  if [ "$json_has_path" = "yes" ]; then
    pass "query -j JSON payload carries the real MainActivity.kt path"
  else
    fail "query -j JSON payload is missing the MainActivity.kt path"
  fi
else
  fail "query -j did not emit valid JSON"
fi

# (d) FALSIFIABILITY (built-in): a nonexistent symbol must NOT resolve to code
q_none="$(codegraph query ZzqNonexistentSymbolXyz987 -l 10 2>&1 || true)"
assert_not_contains "$q_none" ".kt:" \
  "query of a nonexistent symbol returns NO Kotlin location (no canned data)"
assert_not_contains "$q_none" ".go:" \
  "query of a nonexistent symbol returns NO Go location (no canned data)"

finish; exit $?
