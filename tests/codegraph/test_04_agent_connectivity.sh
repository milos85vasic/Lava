#!/usr/bin/env bash
# test_04_agent_connectivity.sh — LAYER 4: proves each of the 5 CLI agents
# registers the codegraph MCP server, and — where the agent reports it —
# actually COMPLETES the MCP handshake ("Connected" = real stdio handshake +
# tool discovery, not a config-file-exists bluff).
#
# Claude Code / OpenCode / Qwen Code expose a live connection status → asserted
# as CONNECTED. Kimi CLI / Crush expose registration only via their config →
# asserted as REGISTERED here; their live runtime use is proven by
# test_05_agent_e2e.sh.
TEST_NAME="04_agent_connectivity"
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

log "== LAYER 4: per-agent MCP connectivity (5 agents) =="

# --- Claude Code (primary agent) — project .mcp.json ---------------------
if have claude; then
  c="$(claude mcp get codegraph 2>&1 || true)"
  printf '%s\n' "$c" > "$EVIDENCE_DIR/test_04_claude.txt"
  assert_contains "$c" "Connected" "Claude Code: codegraph MCP server CONNECTED (real handshake)"
else
  skip "Claude Code (claude) not installed"
fi

# --- OpenCode — opencode.json -------------------------------------------
if have opencode; then
  o="$(opencode mcp list 2>&1 || true)"
  printf '%s\n' "$o" > "$EVIDENCE_DIR/test_04_opencode.txt"
  o_lc="$(printf '%s' "$o" | tr 'A-Z' 'a-z')"
  assert_contains "$o_lc" "codegraph"  "OpenCode: codegraph MCP server registered"
  assert_contains "$o_lc" "connected"  "OpenCode: codegraph MCP server CONNECTED (real handshake)"
else
  skip "OpenCode (opencode) not installed"
fi

# --- Qwen Code — .qwen/settings.json ------------------------------------
if have qwen; then
  q="$(qwen mcp list 2>&1 || true)"
  printf '%s\n' "$q" > "$EVIDENCE_DIR/test_04_qwen.txt"
  q_lc="$(printf '%s' "$q" | tr 'A-Z' 'a-z')"
  assert_contains "$q_lc" "codegraph"  "Qwen Code: codegraph MCP server registered"
  assert_contains "$q_lc" "connected"  "Qwen Code: codegraph MCP server CONNECTED (real handshake)"
else
  skip "Qwen Code (qwen) not installed"
fi

# --- Kimi CLI — ~/.kimi/mcp.json ----------------------------------------
if have kimi; then
  k="$(kimi mcp list 2>&1 || true)"
  printf '%s\n' "$k" > "$EVIDENCE_DIR/test_04_kimi.txt"
  assert_contains "$k" "codegraph" "Kimi CLI: codegraph MCP server registered (~/.kimi/mcp.json)"
else
  skip "Kimi CLI (kimi) not installed"
fi

# --- Crush — .crush.json ------------------------------------------------
if have crush; then
  if [ -f "$REPO_ROOT/.crush.json" ] && grep -q '"codegraph"' "$REPO_ROOT/.crush.json"; then
    pass "Crush: codegraph MCP server registered (.crush.json)"
  else
    fail "Crush: .crush.json missing the codegraph MCP entry"
  fi
else
  skip "Crush (crush) not installed"
fi

finish; exit $?
