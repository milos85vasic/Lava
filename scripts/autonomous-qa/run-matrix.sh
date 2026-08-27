#!/usr/bin/env bash
# scripts/autonomous-qa/run-matrix.sh
# ---------------------------------------------------------------------------
# Orchestrate ONE backend at a time (operator: never both APIs at once):
#   backend up -> boot ONE containerized KVM emulator (kept alive) -> for each
#   provider-subset × query: FRESH install + record + run Challenge70 -> tear
#   emulator down -> backend down -> write curated summary.md.
#
# Usage:
#   run-matrix.sh --backend goapi|apiapp [--queries 1080p,mp3] [--subsets all]
#   # Keystone (Phase 0): --backend goapi --subsets rutracker --queries 1080p
#   # --subsets accepts "all" OR ';'-separated csv groups, e.g. "rutracker;kinozal,nnmclub"
#   # Only API-backed ids (QA_PROVIDERS in lib-subsets.sh) are honored; rutor
#   # (bundled-only) + iptorrents (Jackett-only) are dropped with a warning —
#   # neither API backend vends them via GET /v1/providers.
#
# Plan: docs/superpowers/plans/2026-06-29-autonomous-qa-backend-provider-matrix.md
# ---------------------------------------------------------------------------
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$QA_DIR/../.." && pwd)"
source "$QA_DIR/lib-subsets.sh"
source "$QA_DIR/lib-emulator.sh"
source "$QA_DIR/lib-backend.sh"
source "$QA_DIR/lib-summary.sh"

BACKEND="goapi"; QUERIES="1080p,mp3"; SUBSETS="all"; EXTERNAL_BACKEND=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --backend) BACKEND="$2"; shift 2;;
    --queries) QUERIES="$2"; shift 2;;
    --subsets) SUBSETS="$2"; shift 2;;
    --external-backend) EXTERNAL_BACKEND=true; shift ;;
    *) echo "usage: $0 --backend goapi|apiapp [--queries 1080p,mp3] [--subsets all|<csv1>;<csv2>] [--external-backend]" >&2; exit 2;;
  esac
done

DATE="$(date +%Y-%m-%d)"
RUN_ROOT="$REPO_ROOT/.lava-ci-evidence/autonomous-qa/$DATE/$BACKEND"
mkdir -p "$RUN_ROOT"
SUMMARY="$RUN_ROOT/summary.md"

# Build the subset list ("csv|slug|hash" lines).
declare -a SUBSET_LINES
if [[ "$SUBSETS" == "all" ]]; then
  # §6.J subset floor (added 2026-08-26, LVA vacuous-pass sweep B12).
  #
  # A process substitution's exit status is invisible to `set -euo pipefail`, so
  # a failing qa_emit_subsets produced an EMPTY list and the matrix ran nothing:
  #
  #   lib-subsets.sh: line 43: sha1sum: command not found
  #     mapfile rc=0  SUBSET_LINES count=0   (expected 31)
  #     matrix would report: Totals: PASS=0 SKIP=0 FAIL=0  and exit 0
  #
  # The manual --subsets path already has a floor (the `exit 2` below); the
  # DEFAULT `--subsets all` path — the one every unattended run takes — had
  # none. Capturing the status explicitly is what makes the failure visible.
  _emit_rc=0
  _emit_out="$(qa_emit_subsets)" || _emit_rc=$?
  if [[ "$_emit_rc" -ne 0 ]]; then
    echo "[matrix] ERROR qa_emit_subsets failed (exit ${_emit_rc}) — refusing to run a matrix over an empty subset list." >&2
    echo "  → Examined: 0 subset(s)" >&2
    echo "  → Cause distinguished: this is NOT 'nothing to test'. The subset generator" >&2
    echo "    itself failed; lib-subsets.sh depends on sha1sum being on PATH." >&2
    echo "  → Do: confirm 'sha1sum' resolves (coreutils), then re-run." >&2
    exit 2
  fi
  # Filter into a FRESH, explicitly-assigned array. `unset 'SUBSET_LINES[-1]'`
  # on a one-element array leaves the name unbound under `set -u` in bash 5.2,
  # which would abort here with no message — fail-closed, but with an empty
  # diagnosis, which is the failure mode this whole sweep is about.
  mapfile -t _emit_lines <<< "$_emit_out"
  SUBSET_LINES=()
  for _l in "${_emit_lines[@]}"; do
    [[ -n "$_l" ]] && SUBSET_LINES+=("$_l")
  done
  if [[ ${#SUBSET_LINES[@]} -eq 0 ]]; then
    echo "[matrix] ERROR qa_emit_subsets exited 0 but emitted ZERO subsets." >&2
    echo "  → Examined: 0 subset(s)" >&2
    echo "  → Expected: one line per API-backed provider subset (a healthy tree emits 31)." >&2
    echo "  → Cause distinguished: the generator succeeded and produced nothing, so this" >&2
    echo "    is generator drift rather than a missing dependency." >&2
    echo "  → Do: run 'qa_emit_subsets' from scripts/autonomous-qa/lib-subsets.sh directly" >&2
    echo "    and inspect its output; a matrix over zero subsets reports PASS=0 FAIL=0" >&2
    echo "    and exits 0, which asserts nothing (§6.J)." >&2
    exit 2
  fi
  # END-OF-BLOCK §6.J subset floor (regression-harness sentinel)
else
  # Validate manual subsets against the API-backed set (QA_PROVIDERS, sourced from
  # lib-subsets.sh). Ids not vended by GET /v1/providers — rutor (native bundled-
  # only) and iptorrents (Jackett-only) — are unreachable via either API backend,
  # so drop them here with a clear message instead of letting Challenge70
  # AssumptionViolatedException-SKIP opaquely inside the emulator.
  IFS=';' read -ra groups <<< "$SUBSETS"
  for g in "${groups[@]}"; do
    IFS=',' read -ra ids <<< "$g"
    keep=()
    for id in "${ids[@]}"; do
      id="$(printf '%s' "$id" | tr -d '[:space:]')"
      [[ -z "$id" ]] && continue
      if printf '%s\n' "${QA_PROVIDERS[@]}" | grep -qx "$id"; then
        keep+=("$id")
      else
        echo "[matrix] WARN dropping '$id' from subset '$g' — not API-backed (absent from GET /v1/providers); test it via the bundled/direct backend, not $BACKEND." >&2
      fi
    done
    if [[ ${#keep[@]} -eq 0 ]]; then
      echo "[matrix] SKIP subset '$g' — no API-backed providers remain after filtering." >&2
      continue
    fi
    csv="$(IFS=,; printf '%s' "${keep[*]}")"
    slug="$(printf '%s' "$csv" | tr ',' '-')"
    hash="$(printf '%s' "$csv" | sha1sum | cut -c1-8)"
    SUBSET_LINES+=("$csv|$slug|$hash")
  done
  if [[ ${#SUBSET_LINES[@]} -eq 0 ]]; then
    echo "[matrix] ERROR no API-backed subsets to run after filtering '$SUBSETS'." >&2
    exit 2
  fi
fi
IFS=',' read -ra QUERY_LIST <<< "$QUERIES"

SERIAL=""
cleanup() {
  emu_teardown || true
  if [[ "$EXTERNAL_BACKEND" != "true" ]]; then
    "backend_down_${BACKEND}" "${SERIAL:-}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# 1) Backend up (mutual exclusion enforced inside lib-backend).
if [[ "$EXTERNAL_BACKEND" == "true" ]]; then
  echo "[matrix] external-backend mode: assuming $BACKEND already up + healthy (e.g. thinker distribute-api-remote.sh)" >&2
elif [[ "$BACKEND" == "goapi" ]]; then
  backend_up_goapi
elif [[ "$BACKEND" == "apiapp" ]]; then
  : # api-app installs on-device AFTER the emulator is up (step 3)
else
  echo "[matrix] ERROR unknown backend '$BACKEND'" >&2; exit 2
fi

# 2) Boot ONE containerized emulator, kept alive for the whole matrix.
emu_cleanup_orphans
CONTAINER="$(emu_boot default)"
ADB_PORT="$(grep '^ADB_PORT=' "$QA_DIR/.emu-state" | cut -d= -f2)"
emu_authorize_adb "$CONTAINER"
SERIAL="$(emu_connect "$ADB_PORT")"
emu_wait_boot "$SERIAL" 360
# Ensure the guest has an active default network (no-op on healthy guests; on
# hosts where the emulator boots with eth0 unregistered — podman 4.x on thinker —
# this applies the eth0 + ndc default-network fix). See lib-emulator emu_fix_network.
emu_fix_network "$SERIAL"

# 3) Backend targeting.
API_URL=""
if [[ "$BACKEND" == "goapi" ]]; then
  API_URL="$(backend_target_goapi "$SERIAL")"
elif [[ "$BACKEND" == "apiapp" ]]; then
  # backend_up_apiapp installs+starts the embed on-device, verifies /health over
  # TLS, and echoes the on-device-loopback URL the client must use (stdout only;
  # diagnostics go to stderr). Capture it so the iteration passes it explicitly.
  API_URL="$(backend_up_apiapp "$SERIAL")"
fi

# 4) Matrix loop.
{
  echo "# Autonomous QA run — backend=$BACKEND — $DATE"
  echo ""
  echo "Emulator: containerized KVM ($CONTAINER), serial $SERIAL, AVD default (API 34, x86_64)."
  echo "Backend target: ${API_URL:-on-device loopback}"
  echo ""
  echo "| providers | query | verdict | tests | fail | err | skip |"
  echo "|---|---|---|---|---|---|---|"
} > "$SUMMARY"

pass=0; fail=0; skip=0
for line in "${SUBSET_LINES[@]}"; do
  csv="${line%%|*}"; rest="${line#*|}"; slug="${rest%%|*}"
  for q in "${QUERY_LIST[@]}"; do
    evid="$RUN_ROOT/${slug}-${q}"
    mkdir -p "$evid"
    # §6.J anti-bluff: clear any stale verdict so a prior-iteration PASS can
    # never be misread when run-iteration dies before writing its own verdict
    # (sixth-law-incidents 2026-07-03 missing-APK/stale-verdict PASS bluff).
    rm -f "$evid/verdict.json"
    set +e
    "$QA_DIR/run-iteration.sh" --backend "$BACKEND" --providers "$csv" --query "$q" \
      --serial "$SERIAL" --api-url "$API_URL" --evidence-dir "$evid"
    iter_rc=$?
    set -e
    if [[ ! -f "$evid/verdict.json" ]]; then
      # run-iteration died before writing any verdict — a setup error is a FAIL,
      # never a pass (NO test executed).
      v="FAIL"; t=0; f=0; e=1; s=0
    else
      v="$(qa_parse_field "$evid/verdict.json" verdict || echo FAIL)"
      t="$(qa_parse_field "$evid/verdict.json" tests || echo 0)"
      f="$(qa_parse_field "$evid/verdict.json" failures || echo 0)"
      e="$(qa_parse_field "$evid/verdict.json" errors || echo 0)"
      s="$(qa_parse_field "$evid/verdict.json" skipped || echo 0)"
      # §6.J defense-in-depth: a non-zero iteration exit can NEVER be counted PASS.
      [[ "$iter_rc" -ne 0 && "$(qa_classify "$v")" == "PASS" ]] && v="FAIL"
    fi
    echo "| $csv | $q | $v | $t | $f | $e | $s |" >> "$SUMMARY"
    case "$(qa_classify "$v")" in PASS) pass=$((pass+1));; SKIP) skip=$((skip+1));; *) fail=$((fail+1));; esac
  done
done

{
  echo ""
  echo "**Totals: PASS=$pass SKIP=$skip FAIL=$fail**"
} >> "$SUMMARY"
echo "[matrix] done backend=$BACKEND PASS=$pass SKIP=$skip FAIL=$fail" >&2
echo "[matrix] summary: $SUMMARY" >&2
