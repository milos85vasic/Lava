#!/usr/bin/env bash
# test_01_index_reality.sh — LAYER 1: proves the codegraph index EXISTS, is
# NON-EMPTY, and reflects the REAL Lava codebase (not an empty stub).
#
# Primary assertion (Sixth Law clause 3): operator-visible index statistics —
# a real file/node count from `codegraph status`, against the real repo.
#
# FALSIFIABILITY: test_06_falsifiability.sh removes .codegraph/codegraph.db and
# re-runs this file; every (c)/(d) assertion below then FAILS. Verified break:
# `mv .codegraph/codegraph.db /tmp` → "codegraph status reports N files" FAILS
# because `codegraph status` then reports an empty/absent index.
TEST_NAME="01_index_reality"
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

log "== LAYER 1: codegraph index reality =="

# (a) the SQLite knowledge-graph DB exists and is non-trivial
assert_file_nonempty "$REPO_ROOT/.codegraph/codegraph.db" \
  "codegraph.db (SQLite knowledge graph) exists and is non-empty"

# (b) config.json exists and excludes submodules + §6.H secret paths
CFG="$REPO_ROOT/.codegraph/config.json"
assert_file_nonempty "$CFG" "config.json exists"
cfg="$(cat "$CFG" 2>/dev/null || true)"
assert_contains "$cfg" 'submodules/**'        "config.json excludes submodules/ (spec: Lava domain code only)"
assert_contains "$cfg" '"**/.env"'            "config.json excludes .env (§6.H credential inviolability)"
assert_contains "$cfg" 'keystores/**'         "config.json excludes keystores/ (§6.H)"
assert_contains "$cfg" 'google-services.json' "config.json excludes google-services.json (§6.H)"

# (c) `codegraph status` reports a real, populated graph
status_out="$(codegraph status 2>&1 || true)"
printf '%s\n' "$status_out" > "$EVIDENCE_DIR/test_01_status.txt"
files_n="$(printf '%s' "$status_out" | grep -iE 'Files:'  | grep -oE '[0-9][0-9,]*' | head -1 | tr -d ',')"
nodes_n="$(printf '%s' "$status_out" | grep -iE 'Nodes:'  | grep -oE '[0-9][0-9,]*' | head -1 | tr -d ',')"
edges_n="$(printf '%s' "$status_out" | grep -iE 'Edges:'  | grep -oE '[0-9][0-9,]*' | head -1 | tr -d ',')"
# A real index of Lava is >>100 files. A stub/empty index trips these.
assert_ge "$files_n" 100  "codegraph status reports a real file count"
assert_ge "$nodes_n" 1000 "codegraph status reports a real node count"
assert_ge "$edges_n" 1000 "codegraph status reports a real edge count"

# (d) the index actually contains Kotlin AND Go (Lava is Kotlin + lava-api-go)
assert_contains "$status_out" "kotlin" "index contains Kotlin (the :app / feature / core modules)"
assert_contains "$status_out" "go"     "index contains Go (the lava-api-go service)"

# (e) §6.H / spec compliance — NO submodule file leaked into the index
leak="$(codegraph files 2>/dev/null | grep -c 'submodules/' || true)"
case "$leak" in (*[!0-9]*|"") leak=0 ;; esac
if [ "$leak" -eq 0 ]; then
  pass "no submodule files leaked into the index (exclude config honored)"
else
  fail "$leak submodule file(s) leaked into the index — exclude glob is broken"
fi

finish; exit $?
