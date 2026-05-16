#!/usr/bin/env bash
# Tests for scripts/run-helixqa-challenges.sh (HelixQA Option 1 wiring).
#
# Per the §6.J anti-bluff posture: a wired-in HelixQA script MUST be
# actually invokable. These hermetic tests build synthetic fixtures
# that exercise the wrapper's success / drift-detection / missing-
# submodule paths and assert on the wrapper's observable behavior.
#
# Falsifiability rehearsal: each test_* function plants a deliberate
# condition (script absent / script returns non-zero / submodule
# directory missing) and asserts the wrapper produces the EXPECTED
# exit code + attestation JSON content. If the wrapper's logic
# regressed, the test catches it via the exit-code + JSON assertions.
#
# Inheritance: §6.J + §6.AE + HelixConstitution §11.4 anti-bluff
# hermetic test discipline.
# Classification: project-specific.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="$REPO_ROOT/scripts/run-helixqa-challenges.sh"

if [[ ! -x "$WRAPPER" ]]; then
    echo "FAIL: $WRAPPER missing or not executable"
    exit 1
fi

# Helper: build a fake repo tree at $1 with HelixQA scripts populated
# from a per-script {name, exit_code, stdout} spec via stdin.
# Usage:
#   echo 'anchor_manifest_challenge.sh 0 OK' | build_fake_repo "$dir"
#   echo 'bluff_scanner_challenge.sh 1 BOOM' | build_fake_repo "$dir"
build_fake_repo() {
    local root="$1"
    mkdir -p "$root/Submodules/HelixQA/challenges/scripts"
    mkdir -p "$root/scripts"
    # Copy the wrapper into the fake repo so it resolves REPO_ROOT correctly
    cp "$WRAPPER" "$root/scripts/run-helixqa-challenges.sh"
    chmod +x "$root/scripts/run-helixqa-challenges.sh"
    # Make a minimal .git dir so `git rev-parse HEAD` inside the wrapper
    # doesn't error out catastrophically; the wrapper tolerates "unknown".
    ( cd "$root/Submodules/HelixQA" && git init -q && git config user.email t@t && git config user.name t )
    # Read per-script spec lines: "scriptname exitcode stdoutmsg"
    while IFS=' ' read -r script_name script_exit script_msg; do
        [[ -z "$script_name" ]] && continue
        cat > "$root/Submodules/HelixQA/challenges/scripts/$script_name" <<SH
#!/usr/bin/env bash
echo "$script_msg"
exit $script_exit
SH
        chmod +x "$root/Submodules/HelixQA/challenges/scripts/$script_name"
    done
}

# Helper: build a fake repo with ALL 11 canonical scripts present
# and behaving as specified by `default_exit` (0 = all pass).
build_fake_repo_full() {
    local root="$1"
    local default_exit="${2:-0}"
    local default_msg="${3:-OK_from_test}"
    local specs=""
    for s in anchor_manifest_challenge.sh \
             bluff_scanner_challenge.sh \
             chaos_failure_injection_challenge.sh \
             ddos_health_flood_challenge.sh \
             host_no_auto_suspend_challenge.sh \
             mutation_ratchet_challenge.sh \
             no_suspend_calls_challenge.sh \
             scaling_horizontal_challenge.sh \
             stress_sustained_load_challenge.sh \
             ui_terminal_interaction_challenge.sh \
             ux_end_to_end_flow_challenge.sh; do
        specs+="$s $default_exit $default_msg"$'\n'
    done
    printf '%s' "$specs" | build_fake_repo "$root"
}

# ---------------------------------------------------------------------
# Test 1 (positive): HelixQA submodule present + every script returns 0
# → wrapper exits 0, attestation reports 11/0/0 pass/fail/skip
# ---------------------------------------------------------------------
test_passes_when_helixqa_present_and_all_green() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "all-green-fixture"
    local ev="$f/evidence"
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" --evidence-dir "$ev" >"$f/wrapper.log" 2>&1
    local rc=$?
    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_passes_when_helixqa_present_and_all_green: wrapper exit=$rc (expected 0)"
        echo "    wrapper log:"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if [[ ! -f "$ev/helixqa-attestation.json" ]]; then
        echo "FAIL test_passes_when_helixqa_present_and_all_green: attestation JSON missing at $ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q '"pass_count": 11' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_passes_when_helixqa_present_and_all_green: attestation lacks pass_count: 11"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q '"all_passed": true' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_passes_when_helixqa_present_and_all_green: attestation lacks all_passed: true"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    # Per-script log files MUST exist (proof the script actually ran, not just
    # was listed) — §6.J primary-on-user-visible-state assertion.
    if [[ ! -f "$ev/anchor_manifest_challenge.log" ]]; then
        echo "FAIL test_passes_when_helixqa_present_and_all_green: per-script log missing"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q "all-green-fixture" "$ev/anchor_manifest_challenge.log"; then
        echo "FAIL test_passes_when_helixqa_present_and_all_green: per-script log does NOT contain fixture stdout — proves the script did not actually execute"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_passes_when_helixqa_present_and_all_green"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 2 (negative): one script removed (wiring drift)
# → wrapper exits 3 (missing dependency), explicit drift error message
# ---------------------------------------------------------------------
test_fails_when_script_missing() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    rm "$f/Submodules/HelixQA/challenges/scripts/bluff_scanner_challenge.sh"
    local ev="$f/evidence"
    set +e
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" --evidence-dir "$ev" >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 3 ]]; then
        echo "FAIL test_fails_when_script_missing: wrapper exit=$rc (expected 3 = missing dependency)"
        echo "    wrapper log:"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q "bluff_scanner_challenge.sh" "$f/wrapper.log"; then
        echo "FAIL test_fails_when_script_missing: wrapper log does NOT name the missing script — error message is non-actionable"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -qE "(wiring drift|absent in)" "$f/wrapper.log"; then
        echo "FAIL test_fails_when_script_missing: wrapper log does NOT explain drift class"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_fails_when_script_missing"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 3 (negative): HelixQA submodule absent entirely
# → wrapper exits 3 with explicit "missing submodule" message + the
#   exact `git submodule update --init` remediation command
# ---------------------------------------------------------------------
test_skip_mode_when_helixqa_absent() {
    local f
    f=$(mktemp -d)
    mkdir -p "$f/scripts"
    cp "$WRAPPER" "$f/scripts/run-helixqa-challenges.sh"
    chmod +x "$f/scripts/run-helixqa-challenges.sh"
    # Deliberately do NOT create Submodules/HelixQA/
    local ev="$f/evidence"
    set +e
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" --evidence-dir "$ev" >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 3 ]]; then
        echo "FAIL test_skip_mode_when_helixqa_absent: wrapper exit=$rc (expected 3 = missing dependency)"
        echo "    wrapper log:"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q "git submodule update --init Submodules/HelixQA" "$f/wrapper.log"; then
        echo "FAIL test_skip_mode_when_helixqa_absent: wrapper log lacks the actionable remediation command"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    # No attestation should be written when the submodule was absent
    if [[ -f "$ev/helixqa-attestation.json" ]]; then
        echo "FAIL test_skip_mode_when_helixqa_absent: attestation written despite missing-submodule precondition"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_skip_mode_when_helixqa_absent"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 4 (anti-bluff falsifiability): one script returns FAIL (exit 1)
# → wrapper exits 1; attestation records the failure (not a SKIP)
# This test exists to prove the wrapper does NOT mis-classify a real
# failure as a SKIP — the exit-code-→-classification path is correct.
# ---------------------------------------------------------------------
test_fail_classified_correctly() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    # Override one script to exit 1 (real defect)
    cat > "$f/Submodules/HelixQA/challenges/scripts/chaos_failure_injection_challenge.sh" <<'SH'
#!/usr/bin/env bash
echo "deliberate-fail-fixture"
exit 1
SH
    chmod +x "$f/Submodules/HelixQA/challenges/scripts/chaos_failure_injection_challenge.sh"
    local ev="$f/evidence"
    set +e
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" --evidence-dir "$ev" >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 1 ]]; then
        echo "FAIL test_fail_classified_correctly: wrapper exit=$rc (expected 1)"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q '"fail_count": 1' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_fail_classified_correctly: attestation lacks fail_count: 1"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -qE '"name": "chaos_failure_injection_challenge.sh", "result": "FAIL"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_fail_classified_correctly: attestation does not mark chaos script as FAIL"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_fail_classified_correctly"
    rm -rf "$f"
}

test_passes_when_helixqa_present_and_all_green
test_fails_when_script_missing
test_skip_mode_when_helixqa_absent
test_fail_classified_correctly
echo "all helixqa-wiring tests passed"
