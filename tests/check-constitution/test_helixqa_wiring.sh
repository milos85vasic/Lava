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

# ---------------------------------------------------------------------
# Test 5 (Q1 §6.X — --runner=containerized honest-fail-fast when
# Submodules/Containers is absent)
# → wrapper exits 4 (missing-runtime), explicit §6.X message
# Falsifiability rehearsal: if the wrapper silently degraded
# containerized → host on missing Containers submodule (the canonical
# §6.X bluff), this test would PASS-with-wrapper-exit-0 because the
# fake repo's 11 scripts all return 0. The test asserts exit 4 + the
# specific "git submodule update --init Submodules/Containers" guidance,
# so silent degradation is mechanically detectable.
# ---------------------------------------------------------------------
test_containerized_runner_fails_fast_when_containers_absent() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    # Deliberately do NOT create Submodules/Containers
    local ev="$f/evidence"
    set +e
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" \
        --evidence-dir "$ev" \
        --runner containerized >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 4 ]]; then
        echo "FAIL test_containerized_runner_fails_fast_when_containers_absent: wrapper exit=$rc (expected 4 = missing runtime)"
        echo "    wrapper log:"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q "git submodule update --init Submodules/Containers" "$f/wrapper.log"; then
        echo "FAIL test_containerized_runner_fails_fast_when_containers_absent: wrapper log lacks the actionable remediation command"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -qE "(§6\.X|containerized runner unavailable)" "$f/wrapper.log"; then
        echo "FAIL test_containerized_runner_fails_fast_when_containers_absent: wrapper log lacks §6.X classification"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    # No attestation should be written when the runtime was unavailable
    if [[ -f "$ev/helixqa-attestation.json" ]]; then
        echo "FAIL test_containerized_runner_fails_fast_when_containers_absent: attestation written despite missing-runtime precondition"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_containerized_runner_fails_fast_when_containers_absent"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 6 (Q1 §6.X — --runner=host default produces attestation with
# helixqa_runner: "host" + the runner-caveat note)
# Falsifiability rehearsal: if the default flipped to containerized
# without updating defaults explicitly, this test would FAIL because
# the attestation would lack the host-mode caveat and would have
# different default container fields. The assertion forces the host
# default to be deliberate.
# ---------------------------------------------------------------------
test_host_runner_default_attestation_shape() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    local ev="$f/evidence"
    set +e
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" \
        --evidence-dir "$ev" >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_host_runner_default_attestation_shape: wrapper exit=$rc (expected 0)"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q '"helixqa_runner": "host"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_host_runner_default_attestation_shape: attestation lacks helixqa_runner: host (default-runner regression)"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q '"helixqa_runner_caveat"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_host_runner_default_attestation_shape: attestation lacks helixqa_runner_caveat field"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q '"require_toolchain": "go"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_host_runner_default_attestation_shape: attestation lacks default require_toolchain: go"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q '"w_exclusion_audit": "docs/helixqa-script-audit.md"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_host_runner_default_attestation_shape: attestation lacks w_exclusion_audit pointer"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_host_runner_default_attestation_shape"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 7 (Q2 §6.J — --require-toolchain=go (default) honest-fail-fast
# when 'go' is absent from PATH)
# → wrapper exits 4 (missing-toolchain), explicit message naming the
#   Go-requiring scripts the operator can skip via --require-toolchain=none
# Falsifiability rehearsal: simulate a stripped PATH that has no 'go'.
# If the wrapper silently SKIPped Go-requiring scripts with the default
# (--require-toolchain=go), this test would PASS-with-wrapper-exit-0 and
# the user would have a false-green coverage signal. The exit-4 assertion
# forces the default to be deliberate.
# ---------------------------------------------------------------------
test_require_toolchain_go_fails_fast_when_go_absent() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    local ev="$f/evidence"
    # Strip PATH to a minimal set that does NOT contain 'go'
    # (typical UNIX hosts have /bin + /usr/bin which never contain `go`).
    set +e
    PATH=/bin:/usr/bin LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" \
        --evidence-dir "$ev" >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    # Skip if 'go' IS reachable in /bin or /usr/bin (rare; Homebrew puts
    # go in /opt/homebrew/bin or /usr/local/bin). The test only validates
    # the fail-fast behavior; we cannot artificially break the host PATH
    # without root. If go is in /bin/usr-bin we report a skip with a clear
    # message so the test outcome is unambiguous.
    if command -v go >/dev/null 2>&1 && type -p go | grep -qE '^/bin/|^/usr/bin/'; then
        echo "SKIP test_require_toolchain_go_fails_fast_when_go_absent: 'go' present in /bin or /usr/bin; cannot exercise missing-toolchain path on this host"
        rm -rf "$f"
        return 0
    fi
    if [[ "$rc" -ne 4 ]]; then
        echo "FAIL test_require_toolchain_go_fails_fast_when_go_absent: wrapper exit=$rc (expected 4 = missing toolchain)"
        echo "    wrapper log:"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q "require-toolchain=none" "$f/wrapper.log"; then
        echo "FAIL test_require_toolchain_go_fails_fast_when_go_absent: wrapper log does NOT mention the --require-toolchain=none escape hatch"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_require_toolchain_go_fails_fast_when_go_absent"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 8 (Q2 §6.J — --require-toolchain=none SKIPs Go-requiring scripts
# when 'go' absent, but still runs the non-Go scripts)
# → wrapper exits 0 (no FAIL — only SKIPs); attestation marks Go-requiring
#   scripts as SKIP with the precondition reason in the per-script log
# Falsifiability rehearsal: if --require-toolchain=none silently invoked
# Go-requiring scripts despite go being absent, those scripts would FAIL
# inside themselves (real error) and the wrapper would exit 1 not 0.
# Asserting exit 0 + SKIP count forces the precondition gate to actually
# skip the Go-requiring scripts.
# ---------------------------------------------------------------------
test_require_toolchain_none_skips_go_scripts_when_go_absent() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    local ev="$f/evidence"
    set +e
    PATH=/bin:/usr/bin LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" \
        --evidence-dir "$ev" \
        --require-toolchain none >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    # Same skip condition as Test 7: if go is in /bin/usr-bin we cannot
    # exercise the SKIP path because TOOLCHAIN_AVAILABLE=1.
    if command -v go >/dev/null 2>&1 && type -p go | grep -qE '^/bin/|^/usr/bin/'; then
        echo "SKIP test_require_toolchain_none_skips_go_scripts_when_go_absent: 'go' present in /bin or /usr/bin; cannot exercise toolchain-absent SKIP path on this host"
        rm -rf "$f"
        return 0
    fi
    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_require_toolchain_none_skips_go_scripts_when_go_absent: wrapper exit=$rc (expected 0 = SKIPs not FAILs)"
        echo "    wrapper log:"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    # bluff_scanner_challenge.sh + mutation_ratchet_challenge.sh are Go-requiring per HELIXQA_TOOLCHAIN_MAP
    if ! grep -qE '"name": "bluff_scanner_challenge.sh", "result": "SKIP"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_require_toolchain_none_skips_go_scripts_when_go_absent: bluff_scanner not SKIPped"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -qE '"name": "mutation_ratchet_challenge.sh", "result": "SKIP"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_require_toolchain_none_skips_go_scripts_when_go_absent: mutation_ratchet not SKIPped"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    # anchor_manifest is NOT Go-requiring per HELIXQA_TOOLCHAIN_MAP — it MUST PASS
    if ! grep -qE '"name": "anchor_manifest_challenge.sh", "result": "PASS"' "$ev/helixqa-attestation.json"; then
        echo "FAIL test_require_toolchain_none_skips_go_scripts_when_go_absent: anchor_manifest (no-toolchain script) did not PASS"
        cat "$ev/helixqa-attestation.json"
        rm -rf "$f"
        exit 1
    fi
    # Per-script log MUST explain the SKIP reason — operator-visible
    if ! grep -q "requires Go toolchain" "$ev/bluff_scanner_challenge.log"; then
        echo "FAIL test_require_toolchain_none_skips_go_scripts_when_go_absent: bluff_scanner log lacks the toolchain-absent precondition message"
        cat "$ev/bluff_scanner_challenge.log"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_require_toolchain_none_skips_go_scripts_when_go_absent"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 9 (Q3 — LAVA_HELIXQA_EVIDENCE_DIR env-var override)
# → when env var is set AND --evidence-dir is NOT passed, wrapper uses
#   the env-var value
# Falsifiability rehearsal: if the wrapper ignored the env var, the
# attestation would land at the default dated dir, not at $LAVA_HELIXQA_EVIDENCE_DIR.
# Asserting that the env-var path receives the attestation + NOT the
# default dated dir forces the env-var precedence to be deliberate.
# ---------------------------------------------------------------------
test_evidence_dir_env_var_override() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    local ev="$f/my-custom-evidence"
    set +e
    LAVA_REPO_ROOT="$f" LAVA_HELIXQA_EVIDENCE_DIR="$ev" \
        bash "$f/scripts/run-helixqa-challenges.sh" >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 0 ]]; then
        echo "FAIL test_evidence_dir_env_var_override: wrapper exit=$rc"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if [[ ! -f "$ev/helixqa-attestation.json" ]]; then
        echo "FAIL test_evidence_dir_env_var_override: attestation NOT at LAVA_HELIXQA_EVIDENCE_DIR ($ev)"
        ls -la "$ev" 2>&1 || true
        rm -rf "$f"
        exit 1
    fi
    # Make sure the default dated dir was NOT used (would indicate env var was ignored)
    if [[ -d "$f/.lava-ci-evidence/helixqa-challenges" ]] && [[ -n "$(ls -A "$f/.lava-ci-evidence/helixqa-challenges" 2>/dev/null || true)" ]]; then
        echo "FAIL test_evidence_dir_env_var_override: default dated dir was populated despite LAVA_HELIXQA_EVIDENCE_DIR being set"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_evidence_dir_env_var_override"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 10 (Q4 §6.W — invalid --runner argument rejected)
# Falsifiability rehearsal: if the wrapper silently fell back to 'host'
# on an unknown --runner value, gate runs invoked with a typo
# (--runner=container) would silently degrade. Asserting exit 2 +
# explicit error forces the runner choice to be validated.
# ---------------------------------------------------------------------
test_invalid_runner_rejected() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    set +e
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" \
        --runner container >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 2 ]]; then
        echo "FAIL test_invalid_runner_rejected: wrapper exit=$rc (expected 2 = invalid argument)"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q "runner must be 'host' or 'containerized'" "$f/wrapper.log"; then
        echo "FAIL test_invalid_runner_rejected: wrapper log lacks the explicit runner-choice error message"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_invalid_runner_rejected"
    rm -rf "$f"
}

# ---------------------------------------------------------------------
# Test 11 (Q2 §6.J — invalid --require-toolchain argument rejected)
# Falsifiability rehearsal: same class as Test 10. If --require-toolchain
# silently accepted unknown values, a typo (--require-toolchain=Go vs go)
# would silently fall through to default. Exit-2 assertion forces validation.
# ---------------------------------------------------------------------
test_invalid_require_toolchain_rejected() {
    local f
    f=$(mktemp -d)
    build_fake_repo_full "$f" 0 "ok"
    set +e
    LAVA_REPO_ROOT="$f" bash "$f/scripts/run-helixqa-challenges.sh" \
        --require-toolchain rust >"$f/wrapper.log" 2>&1
    local rc=$?
    set -e
    if [[ "$rc" -ne 2 ]]; then
        echo "FAIL test_invalid_require_toolchain_rejected: wrapper exit=$rc (expected 2)"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    if ! grep -q "require-toolchain must be 'go' or 'none'" "$f/wrapper.log"; then
        echo "FAIL test_invalid_require_toolchain_rejected: wrapper log lacks the explicit toolchain-choice error message"
        sed 's/^/        /' "$f/wrapper.log"
        rm -rf "$f"
        exit 1
    fi
    echo "PASS test_invalid_require_toolchain_rejected"
    rm -rf "$f"
}

test_passes_when_helixqa_present_and_all_green
test_fails_when_script_missing
test_skip_mode_when_helixqa_absent
test_fail_classified_correctly
test_containerized_runner_fails_fast_when_containers_absent
test_host_runner_default_attestation_shape
test_require_toolchain_go_fails_fast_when_go_absent
test_require_toolchain_none_skips_go_scripts_when_go_absent
test_evidence_dir_env_var_override
test_invalid_runner_rejected
test_invalid_require_toolchain_rejected
echo "all helixqa-wiring tests passed"
