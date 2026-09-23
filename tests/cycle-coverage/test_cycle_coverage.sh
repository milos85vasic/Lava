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

# ── ancestor-code-identity fallback (design doc 2026-09-22, LVA self-
#    reference gap: a commit cannot embed its own SHA, so evidence
#    authored in commit A and pushed via a later pointer-advance commit B
#    needs a DIFFERENT binding path than literal HEAD match). Needs a REAL
#    resolvable git repo — synthetic 40-hex SHAs (used everywhere else in
#    this file) can't satisfy merge-base/diff. Build a tiny throwaway repo
#    and point the gate's git plumbing at it via LAVA_CYCLE_COVERAGE_REPO_ROOT.
# ─────────────────────────────────────────────────────────────────────────
FIXTURE_REPO="$WORK/fixture-repo"
mkdir -p "$FIXTURE_REPO"
git -C "$FIXTURE_REPO" init -q -b main
git -C "$FIXTURE_REPO" config user.email "test@example.com"
git -C "$FIXTURE_REPO" config user.name "test"

# Commit A: "code" commit — a production-shaped file, plus the evidence
# authored against ITSELF (the load-bearing firebase-distribute.sh Gate 7
# case: this binds via literal match, unchanged behavior).
mkdir -p "$FIXTURE_REPO/app" "$FIXTURE_REPO/.lava-ci-evidence" "$FIXTURE_REPO/docs"
echo "versionCode = 1" > "$FIXTURE_REPO/app/build.gradle.kts"
git -C "$FIXTURE_REPO" add app/build.gradle.kts
git -C "$FIXTURE_REPO" commit -q -m "commit A: code"
COMMIT_A="$(git -C "$FIXTURE_REPO" rev-parse HEAD)"


# LAST_FIXTURE_OUT is set as a side effect, deliberately NOT returned via
# stdout: an earlier version of this function returned via `printf '%s'
# "$out"` so callers could inspect the output with `x="$(run_case_fixture
# ...)"` — but command substitution forks a subshell, so the SAME call's own
# pass_count/fail_count increments below were silently discarded (the
# subshell's copy of the counters vanished when it exited), meaning the
# exit-code assertion for that case was never actually counted. Using a
# global instead of a return value lets callers both get the real counter
# increment AND inspect the output afterward.
LAST_FIXTURE_OUT=""
run_case_fixture() {  # <label> <expected-exit> <evidence-dir> <map> <head>
  local label="$1" want="$2" edir="$3" map="$4" head="$5"
  local out got
  set +e
  out="$(LAVA_CYCLE_COVERAGE_REPO_ROOT="$FIXTURE_REPO" \
        bash "$GATE" --version="$VERSION" \
        --evidence-dir="$edir" --map="$map" \
        --head="$head" --now-epoch="$NOW_EPOCH" 2>&1)"
  got=$?
  set -e
  LAST_FIXTURE_OUT="$out"
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

EDIR_ANCESTOR="$WORK/ancestor"
write_evidence "$EDIR_ANCESTOR" "$COMMIT_A" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=PASS runner=containers-submodule
EOF

# test_ancestor_governance_only_diff_passes: commit B (child of A) touches
# ONLY .lava-ci-evidence/ + docs/ — the pointer-advance shape. Expect exit 0,
# bound via the NEW ancestor-code-identity path (not literal).
cp -r "$EDIR_ANCESTOR" "$FIXTURE_REPO/.lava-ci-evidence/ancestor-evidence"
echo "# doc" > "$FIXTURE_REPO/docs/CHANGENOTE.md"
git -C "$FIXTURE_REPO" add .lava-ci-evidence docs
git -C "$FIXTURE_REPO" commit -q -m "commit B: governance-only (pointer-advance shape)"
COMMIT_B="$(git -C "$FIXTURE_REPO" rev-parse HEAD)"
run_case_fixture "ancestor_governance_only_diff_passes" 0 "$EDIR_ANCESTOR" "$MAP_FULL" "$COMMIT_B"
if [[ "$LAST_FIXTURE_OUT" == *"bound via ancestor-code-identity:"* ]]; then
  echo "  PASS  [ancestor_governance_only_diff_passes: bound_via tag correct]"
  pass_count=$(( pass_count + 1 ))
else
  echo "  FAIL  [ancestor_governance_only_diff_passes: expected 'bound via ancestor-code-identity:' in output]" >&2
  fail_count=$(( fail_count + 1 ))
fi

# test_ancestor_with_production_diff_refuses: commit C (child of B) ALSO
# touches app/build.gradle.kts — a non-allowlisted production path. The
# fallback must refuse even though A is still a real ancestor of C.
echo "versionCode = 2" > "$FIXTURE_REPO/app/build.gradle.kts"
git -C "$FIXTURE_REPO" add app/build.gradle.kts
git -C "$FIXTURE_REPO" commit -q -m "commit C: production diff too"
COMMIT_C="$(git -C "$FIXTURE_REPO" rev-parse HEAD)"
run_case_fixture "ancestor_with_production_diff_refuses" 2 "$EDIR_ANCESTOR" "$MAP_FULL" "$COMMIT_C"

# test_ancestor_non_ancestor_sha_still_refuses: a real, resolvable SHA that
# is NOT an ancestor of --head (a sibling branch tip) — proves the fallback
# doesn't accept any resolvable SHA, only true ancestors.
git -C "$FIXTURE_REPO" checkout -q -b sibling "$COMMIT_A"
echo "unrelated" > "$FIXTURE_REPO/SIBLING.txt"
git -C "$FIXTURE_REPO" add SIBLING.txt
git -C "$FIXTURE_REPO" commit -q -m "sibling commit, not an ancestor of B"
SIBLING_SHA="$(git -C "$FIXTURE_REPO" rev-parse HEAD)"
git -C "$FIXTURE_REPO" checkout -q main

EDIR_SIBLING="$WORK/sibling"
write_evidence "$EDIR_SIBLING" "$SIBLING_SHA" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=PASS runner=containers-submodule
EOF
run_case_fixture "ancestor_non_ancestor_sha_still_refuses" 2 "$EDIR_SIBLING" "$MAP_FULL" "$COMMIT_B"

# Regression tightening: the ORIGINAL positive_all_covered case (synthetic
# SHA, literal match) must still report bound_via=literal — proving the new
# ancestor fallback did not silently become the ONLY path that fires.
OUT_POS="$(LAVA_CYCLE_COVERAGE_REPO_ROOT="$FIXTURE_REPO" bash "$GATE" --version="$VERSION" \
    --evidence-dir="$EDIR_POS" --map="$MAP_FULL" --head="$HEAD" --now-epoch="$NOW_EPOCH" 2>&1 || true)"
if [[ "$OUT_POS" == *"bound via literal:"* ]]; then
  echo "  PASS  [positive_all_covered: bound_via still literal for a true literal match]"
  pass_count=$(( pass_count + 1 ))
else
  echo "  FAIL  [positive_all_covered: expected 'bound via literal:' in output]" >&2
  fail_count=$(( fail_count + 1 ))
fi

# ── verdict ────────────────────────────────────────────────────────────────
echo "-----------------------------------------------------------"
echo "cases passed: $pass_count   cases failed: $fail_count"
if (( fail_count > 0 )); then
  echo "RESULT: FAIL — the gate did not behave per spec §5.2" >&2
  exit 1
fi
echo "RESULT: PASS — all positive + negative cases produced the expected exit codes"
exit 0
