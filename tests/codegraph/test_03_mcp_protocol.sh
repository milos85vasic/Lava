#!/usr/bin/env bash
# test_03_mcp_protocol.sh — LAYER 3: proves the codegraph MCP SERVER works —
# the exact stdio JSON-RPC surface every one of the 5 CLI agents connects to.
#
# Primary assertion (Sixth Law clause 3): a real MCP `tools/call` response
# carrying a real Lava symbol location, obtained over the real stdio transport.
#
# FALSIFIABILITY: test_06 removes the index DB and re-runs this file — the
# `tools/call` result then no longer contains MainActivity.kt and the test
# FAILS. Verified break: `mv .codegraph/codegraph.db /tmp` → "MCP tools/call
# ... returns the real MainActivity.kt location" FAILS.
TEST_NAME="03_mcp_protocol"
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

log "== LAYER 3: codegraph MCP server (stdio JSON-RPC) =="

if ! have python3; then
  skip "python3 unavailable — cannot drive JSON-RPC"; finish; exit $?
fi

# (a) tools/list returns the documented codegraph tool surface
tools="$(mcp_list_tools)"
printf '%s\n' "$tools" > "$EVIDENCE_DIR/test_03_tools_list.txt"
for t in codegraph_search codegraph_context codegraph_callers codegraph_callees \
         codegraph_impact codegraph_node codegraph_files codegraph_status; do
  assert_contains "$tools" "$t" "MCP tools/list advertises '$t'"
done

# (b) tools/call codegraph_search returns the REAL MainActivity location
res="$(mcp_tool_call codegraph_search '{"query":"MainActivity"}')"
printf '%s\n' "$res" > "$EVIDENCE_DIR/test_03_call_search.txt"
assert_contains "$res" "MainActivity.kt" \
  "MCP tools/call codegraph_search('MainActivity') returns the real MainActivity.kt location"
assert_contains "$res" "app/src/main/kotlin/digital/vasic/lava/client" \
  "MCP tools/call result carries the real Lava package path"

# (c) tools/call codegraph_node on a real symbol returns real detail
node_res="$(mcp_tool_call codegraph_node '{"symbol":"LavaApplication"}')"
printf '%s\n' "$node_res" > "$EVIDENCE_DIR/test_03_call_node.txt"
assert_contains "$node_res" "LavaApplication" \
  "MCP tools/call codegraph_node('LavaApplication') returns real symbol detail"

# (d) FALSIFIABILITY (built-in): a search for a nonexistent symbol must not
#     yield a Lava source location.
none_res="$(mcp_tool_call codegraph_search '{"query":"ZzqNonexistentSymbolXyz987"}')"
assert_not_contains "$none_res" ".kt:" \
  "MCP search of a nonexistent symbol returns NO Kotlin location (no canned data)"

finish; exit $?
