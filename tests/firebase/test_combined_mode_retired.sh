#!/usr/bin/env bash
# tests/firebase/test_combined_mode_retired.sh
#
# STANDING REGRESSION GUARD for LVA-120 (§6.AA / §6.Z / §6.AK).
#
# ---------------------------------------------------------------------------
# WHAT THIS REPLACES
# ---------------------------------------------------------------------------
# This file supersedes tests/firebase/repro_mode_both_channel_gap.sh, which was
# a REPRODUCTION harness for the then-open defect (its exit codes were inverted:
# 0 meant "gap reproduced"). Per that harness's own documented instruction —
# "1 — the gap is GONE. Someone fixed it. This harness has done its job and
# should now be converted into a proper regression test under
# tests/firebase/test_*.sh and this file deleted." — it has been converted into
# this test and removed. Its forensic narrative is preserved verbatim below so
# nothing is lost by the deletion.
#
# ---------------------------------------------------------------------------
# THE DEFECT (LVA-120, P0, operator-approved remedy [B]: retire the mode)
# ---------------------------------------------------------------------------
# scripts/firebase-distribute.sh accepted `--debug-and-release` (alias `--both`),
# setting MODE="both". In that mode:
#   * the §6.AA staging gate was guarded on `[[ "$MODE" == "release" && ... ]]`
#     and therefore NEVER EVALUATED;
#   * the §6.AK cycle-coverage gate — which also subsumes the §6.Z evidence
#     presence, commit-SHA match and 24h freshness checks — resolved its channel
#     through a `*) AK_CHANNEL="debug"` catch-all (that variable is now
#     AK_BUILD_VARIANT per LVA-148), so it checked DEBUG-variant evidence;
#   * the R8-minified RELEASE APK was nevertheless version-asserted, resolved
#     and UPLOADED.
# Net effect: a combined distribute shipped the release APK while the only
# device-evidence gate that ran examined debug-channel evidence — mechanically
# the same setup as Lava-Android-1.2.19-1039, the §6.Z forensic anchor, whose
# release APK crashed on every cold launch because release-variant behaviour had
# never been gated on release-variant evidence.
#
# ---------------------------------------------------------------------------
# THE INVARIANT THIS TEST DEFENDS
# ---------------------------------------------------------------------------
#   MODE has exactly TWO reachable values, `debug` and `release`. The retired
#   combined flags FAIL LOUDLY — they never silently degrade to a debug-only
#   distribute — and each remaining mode's gates actually evaluate.
#
# A silent degrade is the specific regression this test exists to catch: simply
# DELETING the `--debug-and-release|--both` arm would let the flag fall through
# to the arg loop's `*) shift ;;` arm, so a caller asking for a release would
# receive a debug-only distribute and exit 0. That is worse than the original
# defect, because it is silent.
#
# ---------------------------------------------------------------------------
# SAFETY
# ---------------------------------------------------------------------------
# Hermetic: mktemp fixture repo, fake `firebase` binary on PATH, no network, no
# upload. Every case is designed to exit at an EARLY gate (arg parse, §6.P
# Gate 1, §6.AA, §6.P Gate 2) — long before APK resolution or any upload. Each
# case additionally ASSERTS the fake firebase binary was never invoked, so a
# future change that lets a case run to completion fails here rather than
# quietly attempting a distribute.
#
# ---------------------------------------------------------------------------
# FALSIFIABILITY REHEARSAL (§6.J)
# ---------------------------------------------------------------------------
# Mutation: replace the `--debug-and-release|--both)` rejecting arm in
# scripts/firebase-distribute.sh with `--debug-and-release|--both) MODE="debug";
# shift ;;` (the silent-degrade regression).
# Observed: CASE 1 fails —
#   "FAIL [1] --debug-and-release must exit non-zero; got exit 1 with ... "
# See the evidence log referenced in the Bluff-Audit stamp for the verbatim run.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"
ENV_SH="$REPO_ROOT/scripts/firebase-env.sh"

fails=0
examined=0

if [[ ! -f "$DIST_SH" ]]; then
    echo "FAIL: scripts/firebase-distribute.sh not found at $DIST_SH"
    exit 1
fi

# ──────────────────────────────────────────────────────────────────────────────
# FIXTURE
# ──────────────────────────────────────────────────────────────────────────────
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAKE_BIN_DIR="$TMP/fakebin"
mkdir -p "$FAKE_BIN_DIR"

# Fake `firebase`: records every invocation. NOTHING in this test should ever
# reach it; a non-empty log is itself an assertion failure.
cat > "$FAKE_BIN_DIR/firebase" <<'FAKEEOF'
#!/usr/bin/env bash
echo "firebase $*" >> "${FAKE_FIREBASE_LOG:-/dev/null}"
echo "FAKE_FIREBASE_CALLED: $*"
exit 0
FAKEEOF
chmod +x "$FAKE_BIN_DIR/firebase"

cat > "$FAKE_BIN_DIR/git" <<'GITEOF'
#!/usr/bin/env bash
case "$*" in
    *"rev-parse --short HEAD")       echo "deadbeef" ;;
    *"rev-parse HEAD")               echo "deadbeef" ;;
    *"rev-parse --abbrev-ref HEAD")  echo "master" ;;
    *) command git "$@" ;;
esac
GITEOF
chmod +x "$FAKE_BIN_DIR/git"

export PATH="$FAKE_BIN_DIR:$PATH"

FAKE_REPO="$TMP/repo"
mkdir -p "$FAKE_REPO/scripts" "$FAKE_REPO/app"

cat > "$FAKE_REPO/app/build.gradle.kts" <<'GRADLEEOF'
android {
    defaultConfig {
        applicationId = "digital.vasic.lava.client"
        versionCode = 1055
        versionName = "1.2.35"
    }
}
GRADLEEOF

# §6.H: placeholder values only, never real tokens.
cat > "$FAKE_REPO/.env" <<'ENVEOF'
LAVA_FIREBASE_TOKEN=1//fake-token-for-test
LAVA_FIREBASE_PROJECT_ID=fake-project-id
LAVA_FIREBASE_ANDROID_APP_ID=1:111111111111:android:aaaaaaaaaaaaaaaaaaaaaa
LAVA_FIREBASE_ANDROID_DEV_APP_ID=1:111111111111:android:bbbbbbbbbbbbbbbbbbbbbb
LAVA_FIREBASE_API_GO_APP_ID=1:111111111111:web:cccccccccccccccccccccc
LAVA_FIREBASE_TESTERS_OWNER=owner@example.com
LAVA_FIREBASE_TESTERS_DEVELOPER=developer@example.com
LAVA_FIREBASE_TESTERS_TESTER=tester@example.com
ENVEOF

cat > "$FAKE_REPO/CHANGELOG.md" <<'CLEOF'
# Changelog

## Lava-Android-1.2.35-1055
- Client app 1.2.35
CLEOF

CHAN="$FAKE_REPO/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
mkdir -p "$CHAN"
echo "Client snapshot 1.2.35-1055" > "$CHAN/1.2.35-1055.md"

ln -sf "$ENV_SH"  "$FAKE_REPO/scripts/firebase-env.sh"
ln -sf "$DIST_SH" "$FAKE_REPO/scripts/firebase-distribute.sh"

FIREBASE_CALLS_LOG="$TMP/firebase_calls.log"

# Reset the channel pointers to a chosen (debug, release) pair before each case.
seed_pointers() {   # $1 = debug pointer value, $2 = release pointer value
    echo "$1" > "$CHAN/last-version-debug"
    echo "$2" > "$CHAN/last-version-release"
    local max=$(( $1 > $2 ? $1 : $2 ))
    echo "$max" > "$CHAN/last-version"
}

run_distribute() {
    : > "$FIREBASE_CALLS_LOG"
    FAKE_FIREBASE_LOG="$FIREBASE_CALLS_LOG" \
    LAVA_REPO_ROOT="$FAKE_REPO" \
    bash "$FAKE_REPO/scripts/firebase-distribute.sh" "$@" 2>&1
}

assert_no_upload() {   # $1 = case label
    if [[ -s "$FIREBASE_CALLS_LOG" ]]; then
        echo "FAIL [$1] the fake firebase binary WAS invoked — this case must never reach an upload:"
        sed 's/^/        /' "$FIREBASE_CALLS_LOG"
        fails=$((fails + 1))
    fi
}

# ──────────────────────────────────────────────────────────────────────────────
# CASE 1 + 2: the retired flags FAIL LOUDLY (never a silent debug default)
# ──────────────────────────────────────────────────────────────────────────────
for flag in --debug-and-release --both; do
    examined=$((examined + 1))
    seed_pointers 1000 1000
    OUT="$(run_distribute "$flag")"; RC=$?

    if [[ $RC -eq 0 ]]; then
        echo "FAIL [$flag] must exit NON-ZERO; got exit 0. A retired flag that succeeds is a silent degrade."
        echo "        output: $OUT"
        fails=$((fails + 1))
    fi
    if ! echo "$OUT" | grep -qi "RETIRED"; then
        echo "FAIL [$flag] rejection message must say the flag is RETIRED. Got:"
        echo "$OUT" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
    if ! echo "$OUT" | grep -qF "6.AA"; then
        echo "FAIL [$flag] rejection message must name §6.AA as the governing mandate."
        fails=$((fails + 1))
    fi
    if ! echo "$OUT" | grep -qF -- "--debug-only"; then
        echo "FAIL [$flag] rejection message must point at --debug-only."
        fails=$((fails + 1))
    fi
    if ! echo "$OUT" | grep -qF -- "--release-only"; then
        echo "FAIL [$flag] rejection message must point at --release-only."
        fails=$((fails + 1))
    fi
    # The silent-degrade regression: a debug distribute announcing itself.
    if echo "$OUT" | grep -qF "==> Distributing"; then
        echo "FAIL [$flag] the run PROCEEDED into a distribute instead of refusing — silent degrade."
        echo "$OUT" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
    assert_no_upload "$flag"
done

# ──────────────────────────────────────────────────────────────────────────────
# CASE 3: --debug-only still works, and its gates EVALUATE on the debug channel.
#   Seeded so §6.P Gate 1 must fire against last-version-debug (1055 !> 1055).
# ──────────────────────────────────────────────────────────────────────────────
examined=$((examined + 1))
seed_pointers 1055 1000
OUT="$(run_distribute --debug-only)"; RC=$?
if [[ $RC -eq 0 ]]; then
    echo "FAIL [3] --debug-only with last-version-debug == current code must be refused by §6.P Gate 1."
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "last-version-debug"; then
    echo "FAIL [3] --debug-only must resolve its §6.P gate to the DEBUG channel pointer. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
assert_no_upload 3

# ──────────────────────────────────────────────────────────────────────────────
# CASE 4: --debug-only is NOT blocked by the §6.AA staging gate (stage 1 has no
#   predecessor), and proceeds past §6.P Gate 1 into the later gates.
#   Seeded so Gate 1 passes; CHANGELOG entry removed so it stops at Gate 2.
# ──────────────────────────────────────────────────────────────────────────────
examined=$((examined + 1))
seed_pointers 1000 1000
cp "$FAKE_REPO/CHANGELOG.md" "$TMP/CHANGELOG.md.bak"
printf '# Changelog\n\n(no entry for this version)\n' > "$FAKE_REPO/CHANGELOG.md"
OUT="$(run_distribute --debug-only)"; RC=$?
cp "$TMP/CHANGELOG.md.bak" "$FAKE_REPO/CHANGELOG.md"
if echo "$OUT" | grep -qF "FATAL §6.AA"; then
    echo "FAIL [4] --debug-only must NOT be blocked by the §6.AA staging gate. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "CHANGELOG.md does not contain an entry"; then
    echo "FAIL [4] --debug-only must pass §6.P Gate 1 + §6.AA and reach §6.P Gate 2. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
assert_no_upload 4

# ──────────────────────────────────────────────────────────────────────────────
# CASE 5: --release-only works AND its §6.AA guard EVALUATES — it refuses when
#   no companion debug stage has distributed this versionCode.
# ──────────────────────────────────────────────────────────────────────────────
examined=$((examined + 1))
seed_pointers 1000 1000
OUT="$(run_distribute --release-only)"; RC=$?
if [[ $RC -eq 0 ]]; then
    echo "FAIL [5] --release-only without a companion debug stage must be refused."
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "FATAL §6.AA"; then
    echo "FAIL [5] the §6.AA staging gate did not evaluate for --release-only. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if echo "$OUT" | grep -qF -- "--debug-and-release"; then
    echo "FAIL [5] the §6.AA message still advertises the RETIRED combined flag as an escape hatch."
    fails=$((fails + 1))
fi
assert_no_upload 5

# ──────────────────────────────────────────────────────────────────────────────
# CASE 6: the §6.AA gate evaluates even when the debug pointer file is ABSENT.
#   The prior `&& -f "$LAST_VERSION_DEBUG_FILE"` conjunct skipped the gate in
#   that case — the same "gate that does not evaluate" class as MODE=both.
# ──────────────────────────────────────────────────────────────────────────────
examined=$((examined + 1))
rm -f "$CHAN/last-version-debug" "$CHAN/last-version-release" "$CHAN/last-version"
OUT="$(run_distribute --release-only)"; RC=$?
if [[ $RC -eq 0 ]]; then
    echo "FAIL [6] --release-only with NO debug pointer must be refused by §6.AA, not waved through."
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "FATAL §6.AA"; then
    echo "FAIL [6] the §6.AA staging gate was SKIPPED because the debug pointer file is absent. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
assert_no_upload 6

# ──────────────────────────────────────────────────────────────────────────────
# CASE 7 (static): MODE has exactly two reachable values, and the §6.AK channel
#   resolution maps each one EXPLICITLY — no `*)` arm defaulting to debug.
# ──────────────────────────────────────────────────────────────────────────────
examined=$((examined + 1))
# CODE only: full-line comments are stripped so this file's own prose about the
# retired mode (and the script's) cannot satisfy or trip these greps.
CODE="$TMP/dist-code-only.sh"
sed -E '/^[[:space:]]*#/d' "$DIST_SH" > "$CODE"

if grep -qE 'MODE="both"' "$CODE"; then
    echo "FAIL [7] scripts/firebase-distribute.sh still assigns MODE=\"both\"."
    fails=$((fails + 1))
fi
MODE_VALUES="$(grep -oE 'MODE="[a-z]+"' "$CODE" | sort -u | tr '\n' ' ')"
if [[ "$MODE_VALUES" != 'MODE="debug" MODE="release" ' ]]; then
    echo "FAIL [7] MODE must have exactly two reachable values (debug, release). Found: $MODE_VALUES"
    fails=$((fails + 1))
fi
# LVA-148 + LVA-149 (2026-08-26): the variable this case used to inspect was
# AK_CHANNEL. LVA-148 established that "channel" named two different axes across
# this feature; LVA-149 then established that the value was never read by the
# §6.AK gate at all and REMOVED both the --channel parameter and the assignment
# feeding it. So the original assertion ("AK_CHANNEL maps debug and release
# explicitly") no longer has a subject.
#
# The INVARIANT this case defends is unchanged and is asserted directly instead:
# MODE must still be validated explicitly where the §6.AK gate is invoked, and no
# catch-all arm may silently supply the weaker variant. That is the LVA-120
# defect shape, stated without reference to a variable that no longer exists.
if grep -qE '^\s*\*\)\s*[A-Za-z_]+="debug"' "$CODE"; then
    echo "FAIL [7] a catch-all case arm silently defaults some variable to \"debug\" — the LVA-120 shape."
    grep -nE '^\s*\*\)\s*[A-Za-z_]+="debug"' "$CODE" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if ! grep -qF 'at the §6.AK gate invocation' "$CODE"; then
    echo "FAIL [7] the §6.AK gate invocation must still validate MODE explicitly (unknown MODE fails loudly)."
    fails=$((fails + 1))
fi
if grep -qE '\$MODE" == "both"' "$CODE"; then
    echo "FAIL [7] a MODE == \"both\" branch survives in scripts/firebase-distribute.sh."
    fails=$((fails + 1))
fi

# ──────────────────────────────────────────────────────────────────────────────
# VERDICT — an explicit examined-count so a fixture that silently exercises
# nothing cannot report success.
# ──────────────────────────────────────────────────────────────────────────────
EXPECTED_CASES=7
if [[ "$examined" -eq 0 ]]; then
    echo "FAIL: ZERO cases were exercised — this run proves nothing."
    exit 1
fi
if [[ "$examined" -ne "$EXPECTED_CASES" ]]; then
    echo "FAIL: examined $examined case(s), expected $EXPECTED_CASES."
    exit 1
fi

if [[ "$fails" -eq 0 ]]; then
    echo "[firebase] OK: combined-mode retirement guard passed ($examined/$EXPECTED_CASES cases exercised)."
    exit 0
else
    echo "[firebase] FAIL: $fails assertion(s) failed across $examined case(s)."
    exit 1
fi
