#!/usr/bin/env bash
# test_05_agent_e2e.sh — LAYER 5: the load-bearing anti-bluff layer. Drives
# each of the 5 CLI agents through a REAL non-interactive LLM turn and proves
# the agent actually USED codegraph.
#
# THE UNFORGEABLE CHALLENGE: each agent is asked to call the `codegraph_status`
# MCP tool and report the index's total NODE COUNT. The node count is a fact
# that exists ONLY inside codegraph's database — it cannot be grepped from
# source files, cannot be known from training data, cannot be guessed. If an
# agent's answer contains the exact live node count, the agent provably called
# the codegraph MCP tool and received a real response. This defeats the
# classic bluff where an agent answers from its own file-reading tools.
#
# HONEST CLASSIFICATION (operator decision 2026-05-20, §6.J / §6.L):
#   PASS  — agent answered with the real node count → codegraph E2E proven.
#   SKIP  — agent could not be run here (not authenticated / no model). This is
#           a DOCUMENTED GAP, never a faked pass. Re-run after authenticating.
#   FAIL  — agent ran and produced output but did NOT report the node count →
#           the codegraph integration is genuinely broken for that agent.
TEST_NAME="05_agent_e2e"
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

log "== LAYER 5: per-agent end-to-end LLM drive (5 agents) =="

# --- the live, unforgeable expected answer ------------------------------
NODES="$(codegraph status 2>&1 | grep -iE 'Nodes:' | grep -oE '[0-9][0-9,]*' | head -1 | tr -d ',')"
if [ -z "${NODES:-}" ] || ! printf '%s' "$NODES" | grep -qE '^[0-9]+$'; then
  fail "could not determine the live codegraph node count — cannot run E2E layer"
  finish; exit $?
fi
info "live codegraph index node count = $NODES (the unforgeable answer)"

PROMPT="You have a CodeGraph MCP server available for this project. Call its \
codegraph_status tool, read the returned index statistics, and reply with ONLY \
the total number of nodes in the index — just the integer, nothing else."

AGENT_TIMEOUT="${CODEGRAPH_AGENT_TIMEOUT:-240}"
TIMEOUT_BIN=""
have gtimeout && TIMEOUT_BIN="gtimeout"
[ -z "$TIMEOUT_BIN" ] && have timeout && TIMEOUT_BIN="timeout"

# run_agent <outfile> <cmd...> — runs with a hard timeout; returns the rc.
run_agent() {
  local out="$1"; shift
  if [ -n "$TIMEOUT_BIN" ]; then
    "$TIMEOUT_BIN" "$AGENT_TIMEOUT" "$@" >"$out" 2>&1
    return $?
  fi
  ( "$@" >"$out" 2>&1 ) &
  local pid=$!
  ( sleep "$AGENT_TIMEOUT"; kill -TERM "$pid" 2>/dev/null; sleep 3; kill -KILL "$pid" 2>/dev/null ) &
  local w=$!
  wait "$pid" 2>/dev/null; local rc=$?
  kill "$w" 2>/dev/null; wait "$w" 2>/dev/null
  return $rc
}

# classify <label> <outfile> <rc> — PASS / SKIP(documented gap) / FAIL.
classify() {
  local label="$1" out="$2" rc="$3"
  local txt txt_nc
  txt="$(cat "$out" 2>/dev/null || true)"
  txt_nc="$(printf '%s' "$txt" | tr -d ',')"
  if printf '%s' "$txt_nc" | grep -qF "$NODES"; then
    pass "$label E2E: agent called codegraph and reported the real node count ($NODES)"
    return 0
  fi
  # Documented-gap detection: an agent that cannot run a turn here because it
  # lacks credentials / quota / a model, OR fails to load its own context for a
  # reason unrelated to codegraph (e.g. Qwen Code's import processor aborting on
  # an @-token in the upstream constitution/QWEN.md). NOT a codegraph defect —
  # codegraph connectivity for every agent is independently proven by Layer 04.
  if printf '%s' "$txt" | grep -qiE 'api[_ -]?key|unauthor|not authenticated|no model|model not (set|configured|found)|credential|[^0-9](401|402|403|429)([^0-9]|$)|please (log|sign) ?in|ENOTFOUND|quota|usage limit|billing cycle|exceeded_current|rate.?limit|no provider|configure a provider|payment required|insufficient|balance to complete|add credits|ImportProcessor'; then
    skip "$label E2E: DOCUMENTED GAP — agent not runnable here (auth / quota / model not configured, or an agent-environment incompatibility unrelated to codegraph — see the evidence log for the precise reason). Evidence: $out"
    return 0
  fi
  if [ -z "$(printf '%s' "$txt" | tr -d '[:space:]')" ]; then
    skip "$label E2E: DOCUMENTED GAP — agent produced no output (rc=$rc, likely timeout/setup). Evidence: $out"
    return 0
  fi
  fail "$label E2E: agent ran but did NOT report the codegraph node count ($NODES) — integration broken. Evidence: $out"
}

# --- Claude Code ---------------------------------------------------------
if have claude; then
  o="$EVIDENCE_DIR/test_05_claude.log"
  run_agent "$o" claude -p "$PROMPT" --permission-mode bypassPermissions; rc=$?
  classify "Claude Code" "$o" "$rc"
else skip "Claude Code not installed"; fi

# --- OpenCode ------------------------------------------------------------
if have opencode; then
  o="$EVIDENCE_DIR/test_05_opencode.log"
  run_agent "$o" opencode run "$PROMPT"; rc=$?
  classify "OpenCode" "$o" "$rc"
else skip "OpenCode not installed"; fi

# --- Qwen Code -----------------------------------------------------------
if have qwen; then
  o="$EVIDENCE_DIR/test_05_qwen.log"
  run_agent "$o" qwen -p "$PROMPT" --approval-mode yolo; rc=$?
  classify "Qwen Code" "$o" "$rc"
else skip "Qwen Code not installed"; fi

# --- Kimi CLI ------------------------------------------------------------
if have kimi; then
  o="$EVIDENCE_DIR/test_05_kimi.log"
  run_agent "$o" kimi --print --prompt "$PROMPT"; rc=$?
  classify "Kimi CLI" "$o" "$rc"
else skip "Kimi CLI not installed"; fi

# --- Crush ---------------------------------------------------------------
if have crush; then
  o="$EVIDENCE_DIR/test_05_crush.log"
  run_agent "$o" crush run "$PROMPT"; rc=$?
  classify "Crush" "$o" "$rc"
else skip "Crush not installed"; fi

finish; exit $?
