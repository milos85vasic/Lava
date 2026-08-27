#!/usr/bin/env bash
# test_wiring_apiapp.sh — hermetic proof of the §6.AK pre-push Check 10 EXTENSION
# to the api-app channel (the owed §6.AK-debt follow-up).
#
# CONTEXT. §6.AK gate-wiring landed in commit 28a8b79b: .githooks/pre-push
# Check 10 + scripts/firebase-distribute.sh Phase-1 Gate 7 enforce
# scripts/check-cycle-coverage.sh. Check 10 as shipped scans ONLY the CLIENT
# channel (.lava-ci-evidence/distribute-changelog/firebase-app-distribution).
# The api-app channel (firebase-app-distribution-api-app) pointer advance is
# gated only at distribute time (firebase-distribute.sh Gate 7), NOT at push
# time. This test proves the INTENDED extension that makes Check 10 ALSO scan
# the api-app channel — the patch authored in
#   docs/superpowers/specs/2026-06-26-ak-check10-apiapp-extension.md
#
# It does this WITHOUT editing .githooks/pre-push or any real gate file. It
# generates a self-contained STUB wrapper whose grafted block mirrors the
# intended api-app Check 10 extension (the existing Check 10 logic looped over
# the api-app channel dir), invokes the REAL, committed
# scripts/check-cycle-coverage.sh against synthetic api-app §6.Z evidence +
# cycle-coverage-map fixtures, with HEAD + "now" injected via the gate's
# documented LAVA_CYCLE_COVERAGE_HEAD / LAVA_CYCLE_COVERAGE_NOW_EPOCH env
# overrides (so no real git, no real device, no gradle).
#
# Assertions (api-app channel only — the client channel is already proven by
# the sibling test_wiring.sh):
#   api-app advance (last-version-debug)   + coverage PASS → exit 0 (no violation)
#   api-app advance (last-version-release) + coverage PASS → exit 0 (no violation)
#   api-app advance (last-version-debug)   + coverage FAIL → exit 1 + "§6.AK violation"
#   api-app advance (last-version-release) + coverage FAIL → exit 1 + "§6.AK violation"
#   api-app NO advance (FAIL evidence present)             → exit 0 (gate never consulted)
#
# A FAIL anywhere means the api-app extension the spec grafts is unsound and
# MUST NOT be grafted as-is.
#
# Bluff-Audit: tests/cycle-coverage/test_wiring_apiapp.sh
#   Mutation: in the wrapper's grafted api-app block, change the refusal
#             `violations+=` push into a no-op (`:`) so a failing api-app gate
#             is swallowed at push time.
#   Observed-Failure: with the swallow, the negative cases below report
#             "FAIL [apiapp_advance_debug_fail] exit=0 (expected 1)" and
#             "FAIL [apiapp_advance_release_fail] exit=0 (expected 1)" — the
#             test catches an api-app extension that would let a broken api-app
#             gate ship its pointer advance unpoliced. (Rehearsed + reverted —
#             see the captured run quoted in the integration spec.)
#   Reverted: yes
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

GATE="$ROOT/scripts/check-cycle-coverage.sh"
if [[ ! -x "$GATE" ]]; then
  echo "FATAL: real gate $GATE missing/not executable — cannot prove wiring" >&2
  exit 1
fi

VERSION="0.9.9-9909"                                   # synthetic api-app version, never collides
HEAD="89abcdef0123456789abcdef0123456789abcdef"        # synthetic 40-char HEAD
# Deterministic "now": 2026-06-26T12:00:00Z (matches the sibling gate tests).
NOW_EPOCH="$(date -u -d '2026-06-26T12:00:00Z' +%s 2>/dev/null \
  || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' '2026-06-26T12:00:00Z' +%s)"
FRESH_TS="2026-06-26T11:30:00Z"                        # 30 min old → fresh

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

pass_count=0
fail_count=0

assert_case() {  # <label> <expected-exit> <actual-exit> <output> [grep-needle]
  local label="$1" want="$2" got="$3" out="$4" needle="${5:-}"
  local ok=1
  [[ "$got" == "$want" ]] || ok=0
  if [[ -n "$needle" ]] && ! grep -qF "$needle" <<<"$out"; then ok=0; fi
  if [[ "$ok" == 1 ]]; then
    echo "  PASS  [$label] exit=$got (expected $want)${needle:+ + matched '$needle'}"
    pass_count=$(( pass_count + 1 ))
  else
    echo "  FAIL  [$label] exit=$got (expected $want)${needle:+ + needle '$needle'}" >&2
    echo "        ---- wrapper output ----" >&2
    sed 's/^/        /' <<<"$out" >&2
    fail_count=$(( fail_count + 1 ))
  fi
}

# ── synthetic api-app §6.Z evidence + cycle-coverage-map fixtures ────────────
# The evidence channel=api-app is informational; the gate keys on version +
# commit + timestamp + the challenge: rows. We model an api-app cycle that
# claims two user-visible fixes, each backed by a covering Challenge.
write_evidence() {  # $1=dir $2=commit $3=ts ; challenge lines on stdin
  local dir="$1" commit="$2" ts="$3"
  mkdir -p "$dir"
  {
    echo "# §6.Z api-app device-gate evidence (synthetic wiring fixture)"
    echo "cycle-coverage: version=${VERSION} commit=${commit} channel=debug timestamp=${ts}"
    cat
  } > "$dir/${VERSION}-test-evidence.md"
}

MAP="$WORK/${VERSION}-map.yaml"
cat > "$MAP" <<EOF
version: "${VERSION}"
claims:
  - bullet: "api-app: partial-failure search returns No results, not full Error"
    covering_challenge: "Challenge83ApiAppPartialFailureSearch"
  - bullet: "api-app: provider sync toggle survives restart"
    covering_challenge: "Challenge48ProviderSyncToggleSurvives"
EOF

# api-app evidence dir where BOTH covering Challenges PASS on a container/VM runner.
EDIR_PASS="$WORK/apiapp-edir-pass"
write_evidence "$EDIR_PASS" "$HEAD" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge83ApiAppPartialFailureSearchTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge48ProviderSyncToggleSurvivesTest verdict=PASS runner=genymotion-vm
EOF

# api-app evidence dir where the 2nd covering Challenge is SKIP (compiled-not-
# executed) → the 1076 incident shape: a claimed api-app fix with no
# executed+passed device Challenge.
EDIR_FAIL="$WORK/apiapp-edir-fail"
write_evidence "$EDIR_FAIL" "$HEAD" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge83ApiAppPartialFailureSearchTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge48ProviderSyncToggleSurvivesTest verdict=SKIP runner=containers-submodule
EOF

# ════════════════════════════════════════════════════════════════════════════
# WRAPPER — .githooks/pre-push "Check 10 (§6.AK) api-app channel" block.
# Advance-detection (new_val > old_val) is INHERITED verbatim from the already-
# committed Check 7/Check 10 pattern; this stub injects its boolean result
# (ADVANCE), the api-app channel dir + derived AK_* vars, and exercises ONLY the
# NEW api-app wiring the spec patch adds: advance on the api-app channel ⇒
# invoke the real gate against the api-app evidence dir ⇒ non-zero ⇒ record a
# violation ⇒ reject. The block between the markers mirrors the integration
# spec's api-app Check 10 extension (the existing Check 10 looped over the
# api-app channel dir; minus the Check-7-shared advance-detection, noted inline).
# ════════════════════════════════════════════════════════════════════════════
make_prepush_apiapp_wrapper() {  # $1=outfile $2=api-app-evidence-dir $3=ADVANCE(true|false) $4=ptr(last-version-debug|last-version-release)
  cat > "$1" <<WRAP
#!/usr/bin/env bash
set -euo pipefail
ROOT="$ROOT"
violations=()
sha="$HEAD"
# Inputs the real Check 10 derives from the advancing commit (Check 7 pattern):
ADVANCE="$3"               # "true" when last-version-<chan> new_val > old_val on api-app
AK_PTR="$4"                # which pointer advanced (last-version-debug|last-version-release)
AK_VERSION="$VERSION"      # vname-newval, from snapshot/build.gradle at the commit
# api-app channel dir — the EXTENSION: Check 10 now also scans this dir.
AK_EVIDENCE_DIR="$2"
# --- >>> BEGIN §6.AK pre-push Check 10 api-app extension block >>>
if [[ "\$ADVANCE" == "true" ]]; then
  # LVA-149: the ak_channel derivation that fed --channel is gone with the flag.
  ak_rc=0
  "\$ROOT/scripts/check-cycle-coverage.sh" \\
      --version="\$AK_VERSION" \\
      --evidence-dir="\$AK_EVIDENCE_DIR" \\
      --head="\$sha" \\
      --strict >/dev/null 2>&1 || ak_rc=\$?
  if [[ "\$ak_rc" -ne 0 ]]; then
    violations+=("\$sha: §6.AK violation — advances \$AK_PTR on the api-app channel but the cycle-coverage gate exits \$ak_rc (a CHANGELOG claim lacks a covering executed+PASSED device Challenge / evidence missing). Run the missing Challenge(s) or strike the claim before pushing the pointer advance.")
  fi
fi
# --- <<< END §6.AK pre-push Check 10 api-app extension block <<<
if [[ \${#violations[@]} -gt 0 ]]; then
  printf '%s\n' "\${violations[@]}" >&2
  exit 1
fi
echo "pre-push Check 10 (api-app) OK — no §6.AK violation"
exit 0
WRAP
  chmod +x "$1"
}

echo "test: §6.AK CHECK-10 api-app channel EXTENSION (pre-push push-time gate)"

export LAVA_CYCLE_COVERAGE_HEAD="$HEAD"
export LAVA_CYCLE_COVERAGE_NOW_EPOCH="$NOW_EPOCH"

# Make the real gate find the map by auto-resolution next to the evidence dir.
cp "$MAP" "$EDIR_PASS/${VERSION}-cycle-coverage-map.yaml"
cp "$MAP" "$EDIR_FAIL/${VERSION}-cycle-coverage-map.yaml"

# ── api-app advance (debug) + coverage PASS → no violation (exit 0) ──────────
PP="$WORK/pp-apiapp-debug-pass.sh"; make_prepush_apiapp_wrapper "$PP" "$EDIR_PASS" "true" "last-version-debug"
set +e; out="$(bash "$PP" 2>&1)"; got=$?; set -e
assert_case "apiapp_advance_debug_pass" 0 "$got" "$out" "Check 10 (api-app) OK"

# ── api-app advance (release) + coverage PASS → no violation (exit 0) ────────
PP="$WORK/pp-apiapp-release-pass.sh"; make_prepush_apiapp_wrapper "$PP" "$EDIR_PASS" "true" "last-version-release"
set +e; out="$(bash "$PP" 2>&1)"; got=$?; set -e
assert_case "apiapp_advance_release_pass" 0 "$got" "$out" "Check 10 (api-app) OK"

# ── api-app advance (debug) + coverage FAIL → push REJECTED (exit 1) ─────────
PP="$WORK/pp-apiapp-debug-fail.sh"; make_prepush_apiapp_wrapper "$PP" "$EDIR_FAIL" "true" "last-version-debug"
set +e; out="$(bash "$PP" 2>&1)"; got=$?; set -e
assert_case "apiapp_advance_debug_fail" 1 "$got" "$out" "§6.AK violation"

# ── api-app advance (release) + coverage FAIL → push REJECTED (exit 1) ───────
PP="$WORK/pp-apiapp-release-fail.sh"; make_prepush_apiapp_wrapper "$PP" "$EDIR_FAIL" "true" "last-version-release"
set +e; out="$(bash "$PP" 2>&1)"; got=$?; set -e
assert_case "apiapp_advance_release_fail" 1 "$got" "$out" "§6.AK violation"

# ── api-app NO advance → gate never consulted (exit 0) ───────────────────────
# Point at the FAIL evidence to prove the gate is NOT run when ADVANCE=false:
# a failing api-app gate must NOT reject a push that does not advance an api-app
# pointer.
PP="$WORK/pp-apiapp-noadv.sh"; make_prepush_apiapp_wrapper "$PP" "$EDIR_FAIL" "false" "last-version-debug"
set +e; out="$(bash "$PP" 2>&1)"; got=$?; set -e
assert_case "apiapp_no_advance" 0 "$got" "$out" "Check 10 (api-app) OK"

# ── verdict ──────────────────────────────────────────────────────────────────
echo "-----------------------------------------------------------"
echo "cases passed: $pass_count   cases failed: $fail_count"
if (( fail_count > 0 )); then
  echo "RESULT: FAIL — the api-app Check 10 extension logic is unsound; do NOT graft as-is" >&2
  exit 1
fi
echo "RESULT: PASS — pre-push Check 10 api-app channel extension proven against the real gate"
exit 0
