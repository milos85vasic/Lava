#!/usr/bin/env bash
# test_cycle_coverage.sh — hermetic falsifiability test for CM-CYCLE-COVERAGE (§6.AK / §6.AK-debt)
#
# Builds synthetic fixtures (fake CHANGELOG-claim coverage-map + fake §6.Z
# evidence dir) and drives scripts/check-cycle-coverage.sh through the spec
# §5.2 case matrix. NO real device, NO gradle, NO git mutation — every input
# is a temp file and HEAD/now are injected via --head/--now-epoch.
#
# Spec §5.2 case matrix (docs/superpowers/specs/2026-06-26-ak-cycle-coverage-spec.md):
#   positive_all_covered                  → exit 0
#   negative_claim_missing_challenge      → exit 1
#   negative_challenge_compiled_not_executed (SKIP) → exit 1
#   negative_stale_evidence (>24h)        → exit 1
#   negative_wrong_sha                    → exit 2
# Plus two extra falsifiability cases the gate's branches require:
#   negative_host_direct_runner (§6.AH)   → exit 1
#   negative_missing_evidence_file        → exit 2
#
# Exit 0 = every case produced its expected exit code (positive AND negatives).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

GATE="$ROOT/scripts/check-cycle-coverage.sh"
VERSION="9.9.9-9999"             # synthetic version, never collides with the real tree
HEAD="0123456789abcdef0123456789abcdef01234567"   # synthetic 40-char HEAD
WRONG="ffffffffffffffffffffffffffffffffffffffff"
# Fixed "now" so freshness math is deterministic: 2026-06-26T12:00:00Z.
NOW_EPOCH="$(date -u -d '2026-06-26T12:00:00Z' +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' '2026-06-26T12:00:00Z' +%s)"
FRESH_TS="2026-06-26T11:30:00Z"  # 30 min old → fresh
STALE_TS="2026-06-24T10:00:00Z"  # ~50h old  → stale

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

pass_count=0
fail_count=0

# run_case <label> <expected-exit> <evidence-dir> <map-path> [head-override]
run_case() {
  local label="$1" want="$2" edir="$3" map="$4" head="${5:-$HEAD}"
  local out got
  set +e
  out="$(bash "$GATE" --version="$VERSION" \
        --evidence-dir="$edir" --map="$map" \
        --head="$head" --now-epoch="$NOW_EPOCH" 2>&1)"
  got=$?
  set -e
  if [[ "$got" == "$want" ]]; then
    echo "  PASS  [$label] exit=$got (expected $want)"
    pass_count=$(( pass_count + 1 ))
  else
    echo "  FAIL  [$label] exit=$got (expected $want)" >&2
    echo "        ---- gate output ----" >&2
    sed 's/^/        /' <<<"$out" >&2
    fail_count=$(( fail_count + 1 ))
  fi
}

# ── fixture builders ───────────────────────────────────────────────────────
write_evidence() {
  # $1=dir $2=commit $3=timestamp ; reads challenge lines from stdin
  local dir="$1" commit="$2" ts="$3"
  mkdir -p "$dir"
  {
    echo "# §6.Z device-gate evidence (synthetic fixture)"
    echo "cycle-coverage: version=${VERSION} commit=${commit} channel=debug timestamp=${ts}"
    cat
  } > "$dir/${VERSION}-test-evidence.md"
}

# Map with two claims, both naming a covering Challenge.
MAP_FULL="$WORK/map-full.yaml"
cat > "$MAP_FULL" <<EOF
version: "${VERSION}"
claims:
  - bullet: "Search now returns real results"
    covering_challenge: "Challenge58SearchReturnsResults"
  - bullet: "Search filters follow onboarded providers"
    covering_challenge: "Challenge59SearchUsesOnboardedProviders"
EOF

# Map where one claim has NO covering Challenge (the incident shape).
MAP_MISSING="$WORK/map-missing.yaml"
cat > "$MAP_MISSING" <<EOF
version: "${VERSION}"
claims:
  - bullet: "Search now returns real results"
    covering_challenge: "Challenge58SearchReturnsResults"
  - bullet: "Provider chips show friendly names"
    covering_challenge: ""
EOF

echo "test: CM-CYCLE-COVERAGE falsifiability (§6.AK / spec §5.2)"

# ── positive_all_covered → 0 ───────────────────────────────────────────────
EDIR_POS="$WORK/pos"
write_evidence "$EDIR_POS" "$HEAD" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=PASS runner=genymotion-vm
challenge: fqn=lava.app.challenges.Challenge00CrashSurvivalTest verdict=PASS runner=containers-submodule
EOF
run_case "positive_all_covered" 0 "$EDIR_POS" "$MAP_FULL"

# ── negative_claim_missing_challenge → 1 ───────────────────────────────────
# (Challenge58 covered; the 2nd claim has empty covering_challenge.)
run_case "negative_claim_missing_challenge" 1 "$EDIR_POS" "$MAP_MISSING"

# ── negative_challenge_compiled_not_executed (SKIP) → 1 ─────────────────────
EDIR_SKIP="$WORK/skip"
write_evidence "$EDIR_SKIP" "$HEAD" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=SKIP runner=containers-submodule
EOF
run_case "negative_challenge_compiled_not_executed" 1 "$EDIR_SKIP" "$MAP_FULL"

# ── negative_stale_evidence (>24h) → 1 ─────────────────────────────────────
EDIR_STALE="$WORK/stale"
write_evidence "$EDIR_STALE" "$HEAD" "$STALE_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=PASS runner=containers-submodule
EOF
run_case "negative_stale_evidence" 1 "$EDIR_STALE" "$MAP_FULL"

# ── negative_wrong_sha → 2 ─────────────────────────────────────────────────
# Evidence built for $HEAD, but the working-tree HEAD we pass is $WRONG.
run_case "negative_wrong_sha" 2 "$EDIR_POS" "$MAP_FULL" "$WRONG"

# ── negative_host_direct_runner (§6.AH) → 1 ────────────────────────────────
EDIR_HD="$WORK/hostdirect"
write_evidence "$EDIR_HD" "$HEAD" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=host-direct
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=PASS runner=host-direct
EOF
run_case "negative_host_direct_runner" 1 "$EDIR_HD" "$MAP_FULL"

# ── negative_missing_evidence_file → 2 ─────────────────────────────────────
EDIR_EMPTY="$WORK/empty"; mkdir -p "$EDIR_EMPTY"
run_case "negative_missing_evidence_file" 2 "$EDIR_EMPTY" "$MAP_FULL"

# ── verdict ────────────────────────────────────────────────────────────────
echo "-----------------------------------------------------------"
echo "cases passed: $pass_count   cases failed: $fail_count"
if (( fail_count > 0 )); then
  echo "RESULT: FAIL — the gate did not behave per spec §5.2" >&2
  exit 1
fi
echo "RESULT: PASS — all positive + negative cases produced the expected exit codes"
exit 0
