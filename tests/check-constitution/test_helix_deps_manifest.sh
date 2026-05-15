#!/usr/bin/env bash
# Tests for scripts/check-helix-deps-manifest.sh — §11.4.31 enforcement.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-helix-deps-manifest.sh"

write_well_formed_manifest() {
    cat > "$1" <<'EOF'
schema_version: 1

deps:
  - name: SomeDep
    ssh_url: git@github.com:vasic-digital/SomeDep.git
    ref: main
    why: "Test fixture"
    layout: grouped

transitive_handling:
  recursive: true
  conflict_resolution: operator-required

language_specific_subtree: false
EOF
}

# Test 1: synthetic compliant fixture passes
test_compliant_fixture_passes() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p Submodules/foo
    write_well_formed_manifest helix-deps.yaml
    write_well_formed_manifest Submodules/foo/helix-deps.yaml
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_HELIX_DEPS_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -qE "all manifests present"; then
        echo "PASS test_compliant_fixture_passes"
    else
        echo "FAIL test_compliant_fixture_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 2: missing parent helix-deps.yaml → reject
test_missing_parent_manifest_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p Submodules/foo
    write_well_formed_manifest Submodules/foo/helix-deps.yaml
    # NO parent helix-deps.yaml
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_HELIX_DEPS_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "repo root: missing helix-deps"; then
        echo "PASS test_missing_parent_manifest_rejected"
    else
        echo "FAIL test_missing_parent_manifest_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 3: missing per-submodule helix-deps.yaml → reject
test_missing_submodule_manifest_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p Submodules/withscript Submodules/withoutscript
    write_well_formed_manifest helix-deps.yaml
    write_well_formed_manifest Submodules/withscript/helix-deps.yaml
    # Submodules/withoutscript has NO helix-deps.yaml
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_HELIX_DEPS_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "withoutscript"; then
        echo "PASS test_missing_submodule_manifest_rejected"
    else
        echo "FAIL test_missing_submodule_manifest_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 4: parent manifest with wrong schema_version → reject
test_wrong_schema_version_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p Submodules/foo
    cat > helix-deps.yaml <<'EOF'
schema_version: 999

deps:
  - name: SomeDep
    ssh_url: git@github.com:vasic-digital/SomeDep.git
    ref: main
    why: "wrong schema_version"
    layout: grouped

transitive_handling:
  recursive: true
  conflict_resolution: operator-required
EOF
    write_well_formed_manifest Submodules/foo/helix-deps.yaml
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_HELIX_DEPS_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "schema_version"; then
        echo "PASS test_wrong_schema_version_rejected"
    else
        echo "FAIL test_wrong_schema_version_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 5: --advisory mode returns 0 even on violation
test_advisory_mode_returns_zero() {
    local f
    f=$(mktemp -d)
    cd "$f"
    # Empty fixture — every check fails → would normally exit 1
    local rc
    LAVA_REPO_ROOT="$f" bash "$SCANNER" --advisory > /dev/null 2>&1
    rc=$?
    if [[ "$rc" -eq 0 ]]; then
        echo "PASS test_advisory_mode_returns_zero"
    else
        echo "FAIL test_advisory_mode_returns_zero: expected 0 in advisory, got $rc"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 6: parent .json manifest variant accepted
test_json_variant_accepted() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p Submodules/foo
    # JSON-ish content satisfying the structural grep checks (the grep
    # patterns use yaml-style; this test verifies file-presence check
    # for variant extensions doesn't crash, even though structural
    # parse would fail. Confirms the file-presence priority order
    # logic.)
    cat > helix-deps.yaml <<'EOF'
schema_version: 1

deps:
  - name: SomeDep
    ssh_url: git@github.com:vasic-digital/SomeDep.git
    ref: main
    why: "yaml fallback"
    layout: grouped

transitive_handling:
  recursive: true
  conflict_resolution: operator-required
EOF
    cat > Submodules/foo/helix-deps.json <<'EOF'
{"schema_version": 1, "deps": []}
EOF
    local out
    out=$(LAVA_REPO_ROOT="$f" bash "$SCANNER" --advisory 2>&1)
    # Scanner only prints per-name detail on the violation path; on
    # success it summarises with "present: N" + "all manifests present".
    # The discrimination this test proves: helix-deps.json is recognized
    # as a valid alternative extension to helix-deps.yaml — rename it
    # to anything else (e.g. helix-deps.txt) and the present count drops
    # to 0 + missing rises to 1.
    if echo "$out" | grep -qE "present: 1" && echo "$out" | grep -qE "missing: 0"; then
        echo "PASS test_json_variant_accepted"
    else
        echo "FAIL test_json_variant_accepted: expected present:1 + missing:0, got: $out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

test_compliant_fixture_passes
test_missing_parent_manifest_rejected
test_missing_submodule_manifest_rejected
test_wrong_schema_version_rejected
test_advisory_mode_returns_zero
test_json_variant_accepted
echo "all helix-deps-manifest tests passed"
