#!/usr/bin/env bash
# test_wiring.sh — hermetic proof of the §6.AK GATE-WIRING contract (§6.AK-debt)
#
# This test proves the INTENDED wiring of scripts/check-cycle-coverage.sh into
#   (1) scripts/firebase-distribute.sh  — a new Phase-1 Gate that REFUSES the
#       distribute when the cycle-coverage gate fails, and
#   (2) .githooks/pre-push              — a new Check that REJECTS a push which
#       advances a distribute last-version pointer while the cycle-coverage
#       gate fails.
#
# It does this WITHOUT editing either real gate file. Instead it generates two
# self-contained STUB wrappers in a temp dir whose grafted blocks are
# byte-for-byte the snippets the integration spec
# (docs/superpowers/specs/2026-06-26-ak-gate-wiring-integration.md) tells the
# main stream to paste. Each wrapper sources/invokes the REAL, committed
# scripts/check-cycle-coverage.sh against synthetic §6.Z evidence + cycle-
# coverage-map fixtures, with HEAD + "now" injected via the gate's documented
# LAVA_CYCLE_COVERAGE_HEAD / LAVA_CYCLE_COVERAGE_NOW_EPOCH env overrides (so no
# real git, no real device, no gradle).
#
# Assertions:
#   firebase-wrapper, coverage PASS  → exit 0  (distribute proceeds)
#   firebase-wrapper, coverage FAIL  → exit 1  + "FATAL §6.AK" on stderr (refused)
#   pre-push-wrapper, advance + PASS → exit 0  (no violation)
#   pre-push-wrapper, advance + FAIL → exit 1  + "§6.AK violation" (push rejected)
#   pre-push-wrapper, NO advance     → exit 0  (gate not even consulted)
#
# A FAIL anywhere means the wiring LOGIC the integration patch grafts is unsound
# and MUST NOT be grafted as-is.
#
# Bluff-Audit: tests/cycle-coverage/test_wiring.sh
#   Mutation: in each wrapper's grafted block, change the refusal `exit 1`
#             (firebase) / the `violations+=` push (pre-push) into a no-op so a
#             failing gate is swallowed.
#   Observed-Failure: with the swallow, the negative cases below report
#             "FAIL [firebase_coverage_fail] exit=0 (expected 1)" and
#             "FAIL [prepush_advance_fail] exit=0 (expected 1)" — the test
#             catches a wiring that would let a broken gate ship.
#   Reverted: yes
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

GATE="$ROOT/scripts/check-cycle-coverage.sh"
if [[ ! -x "$GATE" ]]; then
  echo "FATAL: real gate $GATE missing/not executable — cannot prove wiring" >&2
  exit 1
fi

VERSION="9.9.9-9999"                                   # synthetic, never collides
HEAD="0123456789abcdef0123456789abcdef01234567"        # synthetic 40-char HEAD
# Deterministic "now": 2026-06-26T12:00:00Z (matches the sibling gate test).
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

# ── synthetic §6.Z evidence + cycle-coverage-map fixtures ───────────────────
write_evidence() {  # $1=dir $2=commit $3=ts ; challenge lines on stdin
  local dir="$1" commit="$2" ts="$3"
  mkdir -p "$dir"
  {
    echo "# §6.Z device-gate evidence (synthetic wiring fixture)"
    echo "cycle-coverage: version=${VERSION} commit=${commit} channel=debug timestamp=${ts}"
    cat
  } > "$dir/${VERSION}-test-evidence.md"
}

MAP="$WORK/${VERSION}-map.yaml"
cat > "$MAP" <<EOF
version: "${VERSION}"
claims:
  - bullet: "Search now returns real results"
    covering_challenge: "Challenge58SearchReturnsResults"
  - bullet: "Search filters follow onboarded providers"
    covering_challenge: "Challenge59SearchUsesOnboardedProviders"
EOF

# Evidence dir where BOTH covering Challenges PASS on a container runner.
EDIR_PASS="$WORK/edir-pass"
write_evidence "$EDIR_PASS" "$HEAD" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=PASS runner=genymotion-vm
EOF

# Evidence dir where the 2nd covering Challenge is SKIP (compiled-not-executed)
# → the incident shape: a claimed fix with no executed+passed device Challenge.
EDIR_FAIL="$WORK/edir-fail"
write_evidence "$EDIR_FAIL" "$HEAD" "$FRESH_TS" <<EOF
challenge: fqn=lava.app.challenges.Challenge58SearchReturnsResultsTest verdict=PASS runner=containers-submodule
challenge: fqn=lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest verdict=SKIP runner=containers-submodule
EOF

# ════════════════════════════════════════════════════════════════════════════
# WRAPPER 1 — firebase-distribute.sh "Phase 1 Gate 7 (§6.AK)" block.
# The block between the BEGIN/END markers is byte-identical to the integration
# spec's firebase-distribute.sh patch. The preamble only sets the variables the
# real script already has in scope at the insertion point (SCRIPT_DIR, MODE,
# APP_VERSION, APP_VERSION_CODE, CHANGELOG_DIR) + forwards an --evidence-dir
# override so this stub can point at the synthetic fixture.
# ════════════════════════════════════════════════════════════════════════════
make_firebase_wrapper() {  # $1=outfile $2=evidence-dir
  cat > "$1" <<WRAP
#!/usr/bin/env bash
set -euo pipefail
# --- stub preamble: mirrors firebase-distribute.sh scope at the insertion point
SCRIPT_DIR="$ROOT/scripts"
MODE="debug"
APP_VERSION="9.9.9"
APP_VERSION_CODE="9999"
CHANGELOG_DIR="$2"
# --- >>> BEGIN §6.AK Phase-1 Gate 7 block (copy into firebase-distribute.sh) >>>
# LVA-149: --channel was REMOVED from check-cycle-coverage.sh (it selected
# nothing). The MODE validation is retained per LVA-120 — a silent catch-all
# default here is what once pointed a release distribute at debug evidence.
case "\$MODE" in
    debug|release) : ;;
    *) echo "FATAL: unknown MODE '\$MODE'" >&2; exit 1 ;;
esac
echo "    Phase 1 Gate 7 (§6.AK): cycle-coverage — CHANGELOG claims × executed device Challenges"
ak_rc=0
"\$SCRIPT_DIR/check-cycle-coverage.sh" \\
    --version="\$APP_VERSION-\$APP_VERSION_CODE" \\
    --evidence-dir="\$CHANGELOG_DIR" \\
    --strict || ak_rc=\$?
case "\$ak_rc" in
    0) echo "    §6.AK gate PASS — all CHANGELOG claims covered by executed device Challenges" ;;
    1) echo "FATAL §6.AK: CHANGELOG claim(s) lack a covering executed+PASSED device Challenge for \$APP_VERSION-\$APP_VERSION_CODE." >&2
       echo "       Run the missing device Challenge(s) on the gate, OR strike the unverified claim(s) from CHANGELOG.md (§6.AK clause 6)." >&2
       exit 1 ;;
    2) echo "FATAL §6.AK: §6.Z evidence or cycle-coverage-map missing/stale/wrong-SHA for \$APP_VERSION-\$APP_VERSION_CODE." >&2
       exit 1 ;;
    *) echo "FATAL §6.AK: internal error in cycle-coverage scanner (rc=\$ak_rc)." >&2
       exit 1 ;;
esac
# --- <<< END §6.AK Phase-1 Gate 7 block <<<
echo "distribute would proceed"
exit 0
WRAP
  chmod +x "$1"
}

# ════════════════════════════════════════════════════════════════════════════
# WRAPPER 2 — .githooks/pre-push "Check 10 (§6.AK)" block.
# Advance-detection (new_val > old_val) is INHERITED verbatim from the already-
# committed Check 7 pattern; this stub injects its boolean result (ADVANCE) +
# the derived AK_* vars and exercises ONLY the NEW wiring the patch adds:
# advance ⇒ invoke the real gate ⇒ non-zero ⇒ record a violation ⇒ reject.
# The block between the markers is byte-identical to the integration spec's
# pre-push patch (minus the Check-7-shared advance-detection, noted inline).
# ════════════════════════════════════════════════════════════════════════════
make_prepush_wrapper() {  # $1=outfile $2=evidence-dir $3=ADVANCE(true|false)
  cat > "$1" <<WRAP
#!/usr/bin/env bash
set -euo pipefail
ROOT="$ROOT"
violations=()
sha="$HEAD"
# Inputs the real Check 10 derives from the advancing commit (Check 7 pattern):
ADVANCE="$3"          # "true" when last-version-<chan> new_val > old_val
AK_CHANNEL="debug"    # from the advancing pointer (last-version-debug→debug)
AK_VERSION="$VERSION" # vname-newval, from snapshot/build.gradle at the commit
AK_EVIDENCE_DIR="$2"
# --- >>> BEGIN §6.AK pre-push Check 10 block (copy into .githooks/pre-push) >>>
if [[ "\$ADVANCE" == "true" ]]; then
  ak_rc=0
  "\$ROOT/scripts/check-cycle-coverage.sh" \\
      --version="\$AK_VERSION" \\
      --evidence-dir="\$AK_EVIDENCE_DIR" \\
      --head="\$sha" \\
      --strict >/dev/null 2>&1 || ak_rc=\$?
  if [[ "\$ak_rc" -ne 0 ]]; then
    violations+=("\$sha: §6.AK violation — advances last-version-\$AK_CHANNEL but the cycle-coverage gate exits \$ak_rc (a CHANGELOG claim lacks a covering executed+PASSED device Challenge / evidence missing). Run the missing Challenge(s) or strike the claim before pushing the pointer advance.")
  fi
fi
# --- <<< END §6.AK pre-push Check 10 block <<<
if [[ \${#violations[@]} -gt 0 ]]; then
  printf '%s\n' "\${violations[@]}" >&2
  exit 1
fi
echo "pre-push Check 10 OK — no §6.AK violation"
exit 0
WRAP
  chmod +x "$1"
}

echo "test: §6.AK GATE-WIRING contract (firebase-distribute Phase-1 Gate + pre-push Check 10)"

export LAVA_CYCLE_COVERAGE_HEAD="$HEAD"
export LAVA_CYCLE_COVERAGE_NOW_EPOCH="$NOW_EPOCH"

# Make the real gate find the map by auto-resolution next to the evidence dir.
cp "$MAP" "$EDIR_PASS/${VERSION}-cycle-coverage-map.yaml"
cp "$MAP" "$EDIR_FAIL/${VERSION}-cycle-coverage-map.yaml"

# ── firebase wrapper: coverage PASS → distribute proceeds (exit 0) ──────────
FW_PASS="$WORK/fw-pass.sh"; make_firebase_wrapper "$FW_PASS" "$EDIR_PASS"
set +e; out="$(bash "$FW_PASS" 2>&1)"; got=$?; set -e
assert_case "firebase_coverage_pass" 0 "$got" "$out" "§6.AK gate PASS"

# ── firebase wrapper: coverage FAIL → distribute REFUSED (exit 1) ───────────
FW_FAIL="$WORK/fw-fail.sh"; make_firebase_wrapper "$FW_FAIL" "$EDIR_FAIL"
set +e; out="$(bash "$FW_FAIL" 2>&1)"; got=$?; set -e
assert_case "firebase_coverage_fail" 1 "$got" "$out" "FATAL §6.AK"

# ── pre-push wrapper: advance + coverage PASS → no violation (exit 0) ────────
PP_PASS="$WORK/pp-pass.sh"; make_prepush_wrapper "$PP_PASS" "$EDIR_PASS" "true"
set +e; out="$(bash "$PP_PASS" 2>&1)"; got=$?; set -e
assert_case "prepush_advance_pass" 0 "$got" "$out" "Check 10 OK"

# ── pre-push wrapper: advance + coverage FAIL → push REJECTED (exit 1) ───────
PP_FAIL="$WORK/pp-fail.sh"; make_prepush_wrapper "$PP_FAIL" "$EDIR_FAIL" "true"
set +e; out="$(bash "$PP_FAIL" 2>&1)"; got=$?; set -e
assert_case "prepush_advance_fail" 1 "$got" "$out" "§6.AK violation"

# ── pre-push wrapper: NO advance → gate never consulted (exit 0) ─────────────
# Point at the FAIL evidence to prove the gate is NOT run when ADVANCE=false:
# a failing gate must NOT reject a push that does not advance a pointer.
PP_NOADV="$WORK/pp-noadv.sh"; make_prepush_wrapper "$PP_NOADV" "$EDIR_FAIL" "false"
set +e; out="$(bash "$PP_NOADV" 2>&1)"; got=$?; set -e
assert_case "prepush_no_advance" 0 "$got" "$out" "Check 10 OK"

# ── verdict ──────────────────────────────────────────────────────────────────
echo "-----------------------------------------------------------"
echo "cases passed: $pass_count   cases failed: $fail_count"
if (( fail_count > 0 )); then
  echo "RESULT: FAIL — the wiring logic is unsound; do NOT graft as-is" >&2
  exit 1
fi
echo "RESULT: PASS — firebase Phase-1 Gate + pre-push Check 10 wiring proven against the real gate"
exit 0
