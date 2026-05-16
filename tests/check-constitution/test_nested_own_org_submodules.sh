#!/usr/bin/env bash
# Tests for scripts/check-no-nested-own-org-submodules.sh — §11.4.28 enforcement.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-no-nested-own-org-submodules.sh"

# Test 1: synthetic fixture with no nested own-org chains → pass
test_clean_fixture_passes() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p submodules/foo
    cat > submodules/foo/.gitmodules <<'EOF'
[submodule "third-party"]
    path = third-party
    url = git@github.com:nlohmann/json.git
EOF
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_NESTED_OWN_ORG_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -qE "no nested own-org submodules"; then
        echo "PASS test_clean_fixture_passes"
    else
        echo "FAIL test_clean_fixture_passes: expected exit 0 + clean message, got rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 2: synthetic fixture with vasic-digital nested chain → reject
test_vasic_digital_chain_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p submodules/foo
    cat > submodules/foo/.gitmodules <<'EOF'
[submodule "BarLib"]
    path = BarLib
    url = git@github.com:vasic-digital/BarLib.git
EOF
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_NESTED_OWN_ORG_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "foo/BarLib.*vasic-digital"; then
        echo "PASS test_vasic_digital_chain_rejected"
    else
        echo "FAIL test_vasic_digital_chain_rejected: expected exit 1 + foo/BarLib violation, got rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 3: HelixDevelopment chain via gitlab → reject
test_helix_dev_gitlab_chain_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p submodules/qux
    cat > submodules/qux/.gitmodules <<'EOF'
[submodule "HelixCore"]
    path = HelixCore
    url = git@gitlab.com:HelixDevelopment/HelixCore.git
EOF
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_NESTED_OWN_ORG_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "qux/HelixCore.*HelixDevelopment"; then
        echo "PASS test_helix_dev_gitlab_chain_rejected"
    else
        echo "FAIL test_helix_dev_gitlab_chain_rejected: expected exit 1 + qux/HelixCore violation, got rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 4: --advisory mode returns 0 even on violation
test_advisory_mode_returns_zero() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p submodules/baz
    cat > submodules/baz/.gitmodules <<'EOF'
[submodule "RedThing"]
    path = RedThing
    url = git@github.com:red-elf/RedThing.git
EOF
    local rc
    LAVA_REPO_ROOT="$f" bash "$SCANNER" --advisory > /dev/null 2>&1
    rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_advisory_mode_returns_zero"
    else
        echo "FAIL test_advisory_mode_returns_zero: expected exit 0 in advisory mode, got $rc"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

test_clean_fixture_passes
test_vasic_digital_chain_rejected
test_helix_dev_gitlab_chain_rejected
test_advisory_mode_returns_zero
echo "all nested-own-org-submodule tests passed"
