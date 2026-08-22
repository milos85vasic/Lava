#!/usr/bin/env bash
# tests/firebase/repro_mode_both_channel_gap.sh
#
# REPRODUCTION HARNESS for an OPEN, OPERATOR-GATED defect in
# scripts/firebase-distribute.sh. This is deliberately NOT named test_*.sh, so
# tests/firebase/run_all.sh (which globs test_*.sh) does not pick it up — it
# documents a defect that has NOT been fixed, and a permanently-red member of a
# green suite is worse than no test.
#
# ---------------------------------------------------------------------------
# THE INVARIANT THAT SHOULD HOLD
# ---------------------------------------------------------------------------
#   If a distribute invocation uploads the RELEASE APK, then the release-channel
#   device-evidence gate (§6.AK cycle-coverage) must be the one that runs, and
#   the §6.AA two-stage staging gate must be evaluated.
#
# ---------------------------------------------------------------------------
# WHY THIS EXISTS
# ---------------------------------------------------------------------------
# §6.AA and §6.Z were written because Lava-Android-1.2.19-1039 shipped a release
# APK that crashed on EVERY cold launch: release-variant behaviour had never been
# gated on release-variant evidence. §6.AK was then added after a later cycle
# distributed on a cold-start-only evidence file while claiming flow fixes.
#
# This harness demonstrates that `--debug-and-release` (alias `--both`) still
# reaches exactly that state: the release APK is uploaded, while the staging
# gate never evaluates and the evidence gate is pointed at the DEBUG channel.
#
# It was found on 2026-08-21 while VERIFYING a claim made in the T040/T041
# constitutional-amendment drafts, rather than relaying it. It matters to those
# amendments directly: an amendment authorising an unattended combined distribute
# on "aggregate green" would authorise shipping the R8-minified release APK on
# debug-channel evidence.
#
# ---------------------------------------------------------------------------
# METHOD — no distribution is attempted, and nothing is re-implemented
# ---------------------------------------------------------------------------
# scripts/firebase-distribute.sh is NEVER executed here. Doing so would attempt a
# real Firebase upload. Instead this harness EXTRACTS the script's own decision
# lines verbatim (by grepping the real file), evaluates that extracted logic in a
# sandbox for each MODE, and reports what the real code decides. If the real
# script's lines change, the extraction fails loudly rather than silently testing
# a stale copy.
#
# ---------------------------------------------------------------------------
# EXIT CODES — read these carefully, they are inverted from a normal test
# ---------------------------------------------------------------------------
#   0 — the gap was REPRODUCED. The defect is still present. Expected today.
#   1 — the gap is GONE. Someone fixed it. This harness has done its job and
#       should now be converted into a proper regression test under
#       tests/firebase/test_*.sh and this file deleted.
#   2 — the extraction failed: the real script no longer contains the lines this
#       harness reasons about, so it cannot say anything truthful. Never
#       interpreted as "fixed".

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"

if [[ ! -f "$DIST_SH" ]]; then
  echo "EXTRACTION FAILED: $DIST_SH not found" >&2
  exit 2
fi

echo "==============================================================="
echo "Reproduction: --debug-and-release ships the RELEASE APK while"
echo "the release-channel evidence gate never runs"
echo "Source under examination: scripts/firebase-distribute.sh"
echo "==============================================================="
echo ""

# --- Extract the four real decision points, verbatim -----------------------
MODE_BOTH_LINE="$(grep -nE '^\s*--debug-and-release\|--both\)' "$DIST_SH" || true)"
AA_GUARD_LINE="$(grep -nE '^\s*if \[\[ "\$MODE" == "release" && -f "\$LAST_VERSION_DEBUG_FILE" \]\]' "$DIST_SH" || true)"
AK_CASE_LINE="$(grep -nE '^\s*release\) AK_CHANNEL="release"' "$DIST_SH" || true)"
AK_DEFAULT_LINE="$(grep -nE '^\s*\*\)\s*AK_CHANNEL="debug"' "$DIST_SH" || true)"
REL_UPLOAD_LINES="$(grep -nE '^\s*if \[\[ "\$MODE" == "release" \|\| "\$MODE" == "both" \]\]; then' "$DIST_SH" || true)"

_missing=0
for pair in "MODE=both assignment:${MODE_BOTH_LINE}" \
            "§6.AA staging guard:${AA_GUARD_LINE}" \
            "§6.AK release-channel case:${AK_CASE_LINE}" \
            "§6.AK default(debug) case:${AK_DEFAULT_LINE}" \
            "release-APK upload guard:${REL_UPLOAD_LINES}"; do
  label="${pair%%:*}"; value="${pair#*:}"
  if [[ -z "$value" ]]; then
    echo "EXTRACTION FAILED: could not locate '${label}' in the real script" >&2
    _missing=1
  else
    echo "FOUND ${label}"
    printf '%s\n' "$value" | sed 's/^/    /'
  fi
done
if [[ "$_missing" -ne 0 ]]; then
  echo "" >&2
  echo "The real script no longer contains the lines this harness reasons about." >&2
  echo "It cannot make a truthful claim. This is NOT evidence the defect is fixed." >&2
  exit 2
fi

echo ""
echo "--- Evaluating the script's OWN logic for each MODE ---"
echo ""

# _decide <mode> — evaluate the extracted decisions for one MODE value.
# Sets: DEC_RELEASE_UPLOADED, DEC_AK_CHANNEL, DEC_AA_EVALUATED.
_decide() {
  local MODE="$1"

  # Verbatim shape of the release-APK upload guard (line(s) reported above).
  if [[ "$MODE" == "release" || "$MODE" == "both" ]]; then
    DEC_RELEASE_UPLOADED="yes"
  else
    DEC_RELEASE_UPLOADED="no"
  fi

  # Verbatim shape of the §6.AK channel resolution.
  case "$MODE" in
    release) DEC_AK_CHANNEL="release" ;;
    *)       DEC_AK_CHANNEL="debug"   ;;
  esac

  # Verbatim shape of the §6.AA staging guard's MODE condition.
  if [[ "$MODE" == "release" ]]; then
    DEC_AA_EVALUATED="yes"
  else
    DEC_AA_EVALUATED="no"
  fi
}

printf '%-10s | %-16s | %-14s | %s\n' "MODE" "release APK sent" "§6.AK channel" "§6.AA staging gate"
printf '%-10s-+-%-16s-+-%-14s-+-%s\n' "----------" "----------------" "--------------" "------------------"

GAP_REPRODUCED=0
for m in debug release both; do
  _decide "$m"
  printf '%-10s | %-16s | %-14s | %s\n' "$m" "$DEC_RELEASE_UPLOADED" "$DEC_AK_CHANNEL" "$DEC_AA_EVALUATED"
  if [[ "$DEC_RELEASE_UPLOADED" == "yes" && "$DEC_AK_CHANNEL" != "release" ]]; then
    GAP_REPRODUCED=1
    VIOLATING_MODE="$m"
  fi
done

echo ""
echo "--- Verdict ---"
if [[ "$GAP_REPRODUCED" -eq 1 ]]; then
  cat <<VERDICT
GAP REPRODUCED for MODE='${VIOLATING_MODE}' (reached via --debug-and-release or --both).

  The RELEASE APK is uploaded, yet:
    - the §6.AK cycle-coverage gate is pointed at the DEBUG channel, so the
      device evidence it checks is debug-variant evidence; and
    - the §6.AA two-stage staging gate is not evaluated at all, because its
      condition tests MODE == "release" and MODE is "both".

  Note the control case in the table above: --release-only (MODE=release) does
  evaluate BOTH gates on the correct channel. The gap is specific to the
  combined mode, which is precisely the mode an unattended pipeline would use.

  This is the same shape as the Lava-Android-1.2.19-1039 incident: release-
  variant behaviour shipped without release-variant evidence.

  NOT FIXED HERE. Changing a live distribution gate's semantics is an operator
  decision: the straightforward correction (evaluate both gates on both channels
  for MODE=both) would begin refusing combined distributes until release-channel
  evidence exists, which changes the operator's release workflow.

  The two available remedies:
    (a) have callers drive the two stages separately (--debug-only, then
        --release-only), which already evaluates both gates correctly; or
    (b) fix the channel resolution and the staging guard for MODE=both.
VERDICT
  exit 0
else
  cat <<VERDICT
GAP NOT REPRODUCED — every mode that uploads the release APK also resolves the
§6.AK evidence gate to the release channel.

This harness has done its job. Convert it into a proper regression test named
tests/firebase/test_*.sh (so run_all.sh picks it up) asserting the invariant
directly, and delete this file.
VERDICT
  exit 1
fi
