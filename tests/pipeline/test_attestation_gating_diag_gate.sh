#!/usr/bin/env bash
# Hermetic regression suite for LVA-162 — a per-AVD attestation row that
# declares `gating: true` while carrying an EMPTY `diag` must be REFUSED
# Lava-side by scripts/pipeline/phase-02-test-challenge.sh.
#
# FORENSIC ANCHOR (measured, run_id 2026-08-26T14-09-17Z): the gating row read
#   "gating": true, "diag": {}, "failure_summaries": []
# §6.I.4 (Group B) requires every row to carry diag.target, diag.sdk,
# diag.device and diag.adb_devices_state — the per-AVD forensic snapshot taken
# immediately before instrumentation.
#
# WHY THIS MATTERS — scripts/tag.sh Group-B Gate 3 (the AVD-SHADOW BLUFF gate)
# is written as:
#   jq '.rows[] | select(.diag.sdk != null and .api_level != null
#                        and .diag.sdk != .api_level)'
# With diag == {}, `.diag.sdk` is null, the `!= null` guard filters the row OUT,
# and the gate reports "no mismatches". tag.sh's own comment documents the
# carve-out ("Gate 3: rows lacking `diag` or `api_level` are skipped") as
# backward-compatibility for pre-Group-B attestations — but a CURRENT emitter
# writing diag:{} slides through the identical hole. The gate is inert on
# exactly the rows it exists to police, on a row that declares itself gating.
#
# WHY THE FIX IS LAVA-SIDE: the emitter is
# submodules/containers/pkg/emulator/matrix.go:611 writeAttestation(), inside a
# frozen submodule pin this repo does not edit. An inert gate that REFUSES is
# strictly better than one that passes, so the refusal lives in the Lava-side
# wrapper.
#
# CASE C proves the inertness claim above is real rather than asserted, by
# running tag.sh's own Gate-3 expression against both shapes.
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-challenge.sh"
TAG_SH="${REPO_ROOT}/scripts/tag.sh"

[[ -f "$WRAPPER" ]] || { echo "FAIL: script under test not found: $WRAPPER"; exit 1; }
for tool in jq python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
EXAMINED=0
pass() { echo "PASS: $1"; EXAMINED=$((EXAMINED + 1)); }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); EXAMINED=$((EXAMINED + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

MARKER_KDOC='/** FALSIFIABILITY REHEARSAL: inverted the assertion; the test failed with "expected true". */'

_new_fixture() {
  local name="$1" f="${WORKDIR}/$1"
  mkdir -p "${f}/scripts" \
           "${f}/app/src/androidTest/kotlin/lava/app/challenges" \
           "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges"
  printf 'package lava.app.challenges\n%s\nclass Challenge01FooTest\n' "$MARKER_KDOC" \
    > "${f}/app/src/androidTest/kotlin/lava/app/challenges/Challenge01FooTest.kt"
  printf 'package lava.api.app.challenges\n%s\nclass Challenge02BarTest\n' "$MARKER_KDOC" \
    > "${f}/api-app/src/androidTest/kotlin/lava/api/app/challenges/Challenge02BarTest.kt"
  printf '%s' "$f"
}

_stub_matrix() {
  local f="$1" attest_json="$2" s
  for s in run-challenge-matrix.sh run-api-app-challenge-matrix.sh; do
    cat > "${f}/scripts/${s}" <<STUB
#!/usr/bin/env bash
ev=""
while [[ \$# -gt 0 ]]; do case "\$1" in --evidence-dir) ev="\$2"; shift 2;; *) shift;; esac; done
if [[ -n "\$ev" ]]; then
  mkdir -p "\$ev"
  cat > "\$ev/real-device-verification.json" <<'ATT'
${attest_json}
ATT
fi
exit 0
STUB
    chmod +x "${f}/scripts/${s}"
  done
}

_run() {
  local f="$1" pd="$2"
  local out="${WORKDIR}/out.log"
  env LAVA_PIPELINE_CHALLENGE_CONTAINER_RUNTIME=false \
    bash "$WRAPPER" "$f" "$pd" >"$out" 2>&1
  W_RC=$?
  W_OUT="$(cat "$out")"
}

VIOLATION_RE='GROUP-B VIOLATION'

echo "==============================================================="
echo "CASE A (LOAD-BEARING): gating row with an EMPTY diag is REFUSED"
echo "==============================================================="

ATT_EMPTY='{"gating":true,"rows":[{"avd":"CZ_API34_Phone","api_level":34,"test_passed":true,"concurrent":1,"test_seconds":11.0,"diag":{},"failure_summaries":[]}]}'
FA="$(_new_fixture empty-diag)"
_stub_matrix "$FA" "$ATT_EMPTY"
_run "$FA" "${WORKDIR}/pd-a"

if grep -q "$VIOLATION_RE" <<< "$W_OUT"; then
  pass "gating:true + diag:{} -> refused, and the refusal names §6.I.4 Group B"
else
  fail "gating:true + diag:{} was ACCEPTED. tag.sh Group-B Gate 3 cannot police this row (its .diag.sdk != null guard filters it out), so the AVD-shadow bluff gate is inert on a row that declares itself gating. Output: ${W_OUT}"
fi
if grep -qiE 'diag\.sdk|adb_devices_state|missing diag field' <<< "$W_OUT"; then
  pass "the refusal names the specific missing diag field(s)"
else
  fail "the refusal does not name which diag fields are missing; a future reader cannot act on it. Output: ${W_OUT}"
fi
if [[ "$W_RC" -ne 0 ]]; then
  pass "gating:true + diag:{} -> wrapper exits non-zero (${W_RC})"
else
  fail "gating:true + diag:{} -> wrapper exited 0; an unpoliceable gating row was passed downstream"
fi

echo ""
echo "==============================================================="
echo "CASE B (DISCRIMINATION): a COMPLETE diag is NOT refused"
echo "(guards against a 'fix' that just refuses every attestation)"
echo "==============================================================="

ATT_FULL='{"gating":true,"rows":[{"avd":"CZ_API34_Phone","api_level":34,"test_passed":true,"concurrent":1,"test_seconds":11.0,"diag":{"target":"google_apis","sdk":34,"device":"CZ_API34_Phone","adb_devices_state":"device"},"failure_summaries":[]}]}'
FB="$(_new_fixture full-diag)"
_stub_matrix "$FB" "$ATT_FULL"
_run "$FB" "${WORKDIR}/pd-b"

if ! grep -q "$VIOLATION_RE" <<< "$W_OUT"; then
  pass "a row carrying all four §6.I.4 diag fields is NOT refused"
else
  fail "a fully-populated, conformant diag was refused. The gate discriminates on nothing and would block every run. Output: ${W_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE C: prove tag.sh Group-B Gate 3 really IS inert on diag:{}"
echo "(this is the reason the Lava-side refusal has to exist at all)"
echo "==============================================================="

GATE3='.rows[] | select(.diag.sdk != null and .api_level != null and .diag.sdk != .api_level) | "\(.avd): claimed api_level=\(.api_level) but diag.sdk=\(.diag.sdk)"'

if [[ -f "$TAG_SH" ]]; then
  if grep -q 'select(.diag.sdk != null and .api_level != null and .diag.sdk != .api_level)' "$TAG_SH"; then
    pass "the Gate-3 expression exercised below is the one really in scripts/tag.sh"
  else
    fail "scripts/tag.sh no longer contains the Gate-3 expression this case models; the case would prove nothing about the real gate"
  fi
else
  fail "scripts/tag.sh not found; cannot confirm the modelled Gate-3 expression is real"
fi

# An AVD-shadow row: claims api_level 28 but diag is empty.
printf '%s' '{"gating":true,"rows":[{"avd":"CZ_API28_Phone","api_level":28,"diag":{},"failure_summaries":[]}]}' > "${WORKDIR}/shadow-empty.json"
# The same shadow, with diag populated and genuinely mismatched.
printf '%s' '{"gating":true,"rows":[{"avd":"CZ_API28_Phone","api_level":28,"diag":{"target":"t","sdk":34,"device":"d","adb_devices_state":"device"},"failure_summaries":[]}]}' > "${WORKDIR}/shadow-full.json"

OUT_EMPTY="$(jq -r "$GATE3" "${WORKDIR}/shadow-empty.json" 2>/dev/null)"
OUT_FULL="$(jq -r "$GATE3" "${WORKDIR}/shadow-full.json" 2>/dev/null)"

if [[ -z "$OUT_EMPTY" ]]; then
  pass "confirmed: Gate 3 finds NOTHING when diag is empty — it is inert exactly where LVA-162 said"
else
  fail "Gate 3 caught the empty-diag row on its own; the premise of this fix would be wrong. Got: ${OUT_EMPTY}"
fi
if [[ -n "$OUT_FULL" ]]; then
  pass "confirmed: Gate 3 DOES catch the shadow when diag is populated (${OUT_FULL}) — so populating diag is what restores the gate"
else
  fail "Gate 3 failed to catch a populated api_level/diag.sdk mismatch; the gate is broken beyond the empty-diag case"
fi

echo ""
echo "==============================================================="
echo "EXAMINED: ${EXAMINED} assertion(s)"
if [[ "$EXAMINED" -eq 0 ]]; then
  echo "FAILED — zero assertions were examined. A suite that checked nothing proves nothing."
  exit 1
fi
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED (${EXAMINED} examined)"
  exit 0
fi
echo "${FAILURES} CHECK(S) FAILED (${EXAMINED} examined)"
exit 1
