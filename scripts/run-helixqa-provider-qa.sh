#!/usr/bin/env bash
# =============================================================================
# run-helixqa-provider-qa.sh
#
# Purpose:   Thin Lava glue that runs HelixQA against the Lava Android app for a
#            given provider (or all), feeding the matching Lava test bank, using
#            the `claude` CLI as the LLM/Vision model bridge (BridgedCLIProvider).
# Usage:     scripts/run-helixqa-provider-qa.sh [--provider <id>|--all]
#                                               [--serial <adb-serial>]
#                                               [--autonomous] [--matrix]
#            --provider <id>   one of: rutracker rutor iptorrents kinozal nnmclub archiveorg gutenberg
#            --all             run every provider bank sequentially
#            --serial <s>      adb device serial (default: env LAVA_REAL_DEVICE_SERIALS
#                              or 127.0.0.1:6555 — the Genymotion VM)
#            --autonomous      use `helixqa autonomous` (doc-driven session) instead
#                              of `helixqa run --banks` (explicit per-provider bank)
#            --matrix          resolve banks as lava-<prov>-matrix-journey.yaml instead
#                              of the default lava-<prov>-journey.yaml
# Inputs:    submodules/helixqa (the HelixQA Go source), lava-api-go/qa/banks/*.yaml,
#            the `claude` CLI on PATH (model bridge — no API key needed),
#            a reachable adb device with the Lava app installed.
# Outputs:   Per-run evidence under .lava-ci-evidence/helixqa/<provider>/<run-id>/.
# Side-fx:   Builds the helixqa binary to a temp path; connects adb to --serial.
# Exit:      0 = QA run completed; 2 = no device reachable (honest BLOCKED, §6.AH/§11.4.3);
#            3 = helixqa build failed; 1 = QA run reported failures.
# Deps:      go (1.24+), adb, the `claude` CLI, the helixqa submodule deps (added 2026-06-08).
# X-ref:     docs/qa/helixqa-wiring-plan.md, docs/qa/helixqa-dependency-submodules.md,
#            scripts/run-genymotion-challenges.sh (the Genymotion device path).
# Constitution: §6.AE (per-provider Challenge coverage), §6.AH (device in VM/container),
#            §11.4.3 (topology SKIP not fake PASS), §11.4.27 (HelixQA full coverage),
#            §11.4.87/§11.4.98 (autonomous, no manual intervention).
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HELIXQA_SRC="$REPO_ROOT/submodules/helixqa"
BANKS_DIR="$REPO_ROOT/lava-api-go/qa/banks"
PKG="${LAVA_QA_PACKAGE:-digital.vasic.lava.client.dev}"
SERIAL="${LAVA_REAL_DEVICE_SERIALS:-127.0.0.1:6555}"
PROVIDERS=(rutracker rutor iptorrents kinozal nnmclub archiveorg gutenberg)
MODE="run"
SELECTED=""
BANK_KIND="journey"

while [ $# -gt 0 ]; do
  case "$1" in
    --provider) SELECTED="$2"; shift 2 ;;
    --all)      SELECTED="__all__"; shift ;;
    --serial)   SERIAL="$2"; shift 2 ;;
    --autonomous) MODE="autonomous"; shift ;;
    --matrix)   BANK_KIND="matrix-journey"; shift ;;
    *) echo "unknown arg: $1" >&2; exit 64 ;;
  esac
done
[ -n "$SELECTED" ] || { echo "ERROR: pass --provider <id> or --all" >&2; exit 64; }

# --- Honest device gate (§6.AH / §11.4.3): no device => BLOCKED, never fake PASS ---
if ! command -v adb >/dev/null 2>&1; then
  echo "BLOCKED: adb not on PATH — cannot reach a device. Install platform-tools." >&2
  exit 2
fi
adb connect "$SERIAL" >/dev/null 2>&1 || true
if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "BLOCKED: no adb device in 'device' state (looked for $SERIAL)." >&2
  echo "         Boot the Genymotion VM (or attach a device) then re-run." >&2
  echo "         This is an honest BLOCKED per §6.AH/§11.4.3 — NOT a pass." >&2
  exit 2
fi

# --- Build the helixqa binary ---
HELIXQA_BIN="$(mktemp -t helixqa.XXXXXX)"
echo "Building helixqa from $HELIXQA_SRC ..."
if ! ( cd "$HELIXQA_SRC" && GOMAXPROCS=2 go build -o "$HELIXQA_BIN" ./cmd/helixqa ); then
  echo "BLOCKED: helixqa build failed (check submodule deps per docs/qa/helixqa-dependency-submodules.md)." >&2
  exit 3
fi
echo "helixqa: $("$HELIXQA_BIN" version 2>/dev/null || echo '?')"

command -v claude >/dev/null 2>&1 \
  && echo "model bridge: claude CLI at $(command -v claude) (no API key needed)" \
  || echo "WARNING: 'claude' not on PATH — autonomous session will lack the LLM/Vision bridge."

run_one() {
  prov="$1"
  bank="$BANKS_DIR/lava-${prov}-${BANK_KIND}.yaml"
  [ -f "$bank" ] || { echo "SKIP: no bank for $prov ($bank)"; return 0; }
  rid="$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || echo run)-${prov}"
  out="$REPO_ROOT/.lava-ci-evidence/helixqa/${prov}/${rid}"
  mkdir -p "$out"
  echo "=== HelixQA [$MODE] provider=$prov serial=$SERIAL pkg=$PKG ==="
  if [ "$MODE" = "autonomous" ]; then
    "$HELIXQA_BIN" autonomous --project "$REPO_ROOT" --platforms android \
      --env "$REPO_ROOT/.env" --output "$out" || return $?
  else
    "$HELIXQA_BIN" run --banks "$bank" --platform android \
      --device "$SERIAL" --package "$PKG" --output "$out" || return $?
  fi
}

rc=0
if [ "$SELECTED" = "__all__" ]; then
  for p in "${PROVIDERS[@]}"; do run_one "$p" || rc=1; done
else
  run_one "$SELECTED" || rc=1
fi
exit "$rc"
