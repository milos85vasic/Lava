#!/usr/bin/env bash
# Tests for scripts/check-canonical-root-and-upstreams.sh — §11.4.35 + §11.4.36.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCANNER="$REPO_ROOT/scripts/check-canonical-root-and-upstreams.sh"

# Test 1: synthetic compliant fixture passes
test_compliant_fixture_passes() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p constitution Submodules/foo
    cat > CLAUDE.md <<'EOF'
# CLAUDE.md

## INHERITED FROM constitution/CLAUDE.md

All rules apply.
EOF
    cat > AGENTS.md <<'EOF'
# AGENTS.md

## INHERITED FROM constitution/AGENTS.md

All rules apply.
EOF
    cat > constitution/CLAUDE.md <<'EOF'
# Canonical root CLAUDE.md
EOF
    cat > constitution/AGENTS.md <<'EOF'
# Canonical root AGENTS.md
EOF
    cat > constitution/Constitution.md <<'EOF'
# Canonical Constitution.md
EOF
    touch Submodules/foo/install_upstreams.sh
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_CANONICAL_ROOT_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -qE "all clauses pass"; then
        echo "PASS test_compliant_fixture_passes"
    else
        echo "FAIL test_compliant_fixture_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 2: missing root CLAUDE.md inheritance pointer → reject
test_missing_root_pointer_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p constitution Submodules/foo
    cat > CLAUDE.md <<'EOF'
# CLAUDE.md without inheritance block
This file forgets the pointer entirely.
EOF
    cat > AGENTS.md <<'EOF'
# AGENTS.md

## INHERITED FROM constitution/AGENTS.md
EOF
    cat > constitution/CLAUDE.md <<'EOF'
canonical
EOF
    cat > constitution/AGENTS.md <<'EOF'
canonical
EOF
    cat > constitution/Constitution.md <<'EOF'
canonical
EOF
    touch Submodules/foo/install_upstreams.sh
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_CANONICAL_ROOT_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "CLAUDE.md.*missing.*inheritance pointer"; then
        echo "PASS test_missing_root_pointer_rejected"
    else
        echo "FAIL test_missing_root_pointer_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 3: canonical root carrying its own INHERITED FROM → reject
test_canonical_self_inheritance_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p constitution
    cat > CLAUDE.md <<'EOF'
# CLAUDE.md
## INHERITED FROM constitution/CLAUDE.md
EOF
    cat > AGENTS.md <<'EOF'
# AGENTS.md
## INHERITED FROM constitution/AGENTS.md
EOF
    cat > constitution/CLAUDE.md <<'EOF'
# Canonical root
## INHERITED FROM somewhere/else/CLAUDE.md

This canonical root incorrectly inherits from elsewhere.
EOF
    cat > constitution/AGENTS.md <<'EOF'
canonical
EOF
    cat > constitution/Constitution.md <<'EOF'
canonical
EOF
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_CANONICAL_ROOT_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "canonical-root MUST NOT carry"; then
        echo "PASS test_canonical_self_inheritance_rejected"
    else
        echo "FAIL test_canonical_self_inheritance_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 4: missing install_upstreams in submodule → reject
test_missing_install_upstreams_rejected() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p constitution Submodules/withscript Submodules/withoutscript
    cat > CLAUDE.md <<'EOF'
# CLAUDE.md
## INHERITED FROM constitution/CLAUDE.md
EOF
    cat > AGENTS.md <<'EOF'
# AGENTS.md
## INHERITED FROM constitution/AGENTS.md
EOF
    cat > constitution/CLAUDE.md <<'EOF'
canonical
EOF
    cat > constitution/AGENTS.md <<'EOF'
canonical
EOF
    cat > constitution/Constitution.md <<'EOF'
canonical
EOF
    touch Submodules/withscript/install_upstreams.sh
    # Submodules/withoutscript has NO install_upstreams
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_CANONICAL_ROOT_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 1 ]] && echo "$out" | grep -qE "withoutscript"; then
        echo "PASS test_missing_install_upstreams_rejected"
    else
        echo "FAIL test_missing_install_upstreams_rejected: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

# Test 5: --advisory mode returns 0 even on violation
test_advisory_mode_returns_zero() {
    local f
    f=$(mktemp -d)
    cd "$f"
    # Empty fixture — every check fails → would normally exit 1 in strict
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

# Test 6: canonical root with INHERITED FROM inside fenced code block → PASS
# (the constitution submodule documents the pointer pattern for consumers
# inside ```markdown ... ``` blocks; this is documentation, not inheritance)
test_canonical_fenced_code_block_pointer_passes() {
    local f
    f=$(mktemp -d)
    cd "$f"
    mkdir -p constitution Submodules/foo
    cat > CLAUDE.md <<'EOF'
# CLAUDE.md
## INHERITED FROM constitution/CLAUDE.md
EOF
    cat > AGENTS.md <<'EOF'
# AGENTS.md
## INHERITED FROM constitution/AGENTS.md
EOF
    cat > constitution/CLAUDE.md <<'EOF'
# Canonical CLAUDE.md

## How inheritance works

A consuming project's root CLAUDE.md MUST start with:

```markdown
## INHERITED FROM constitution/CLAUDE.md

All rules apply.
```

The above is documentation, not inheritance.
EOF
    cat > constitution/AGENTS.md <<'EOF'
canonical
EOF
    cat > constitution/Constitution.md <<'EOF'
canonical
EOF
    touch Submodules/foo/install_upstreams.sh
    local out
    out=$(LAVA_REPO_ROOT="$f" LAVA_CANONICAL_ROOT_STRICT=1 bash "$SCANNER" 2>&1)
    local rc=$?
    if [[ "$rc" -eq 0 ]] && echo "$out" | grep -qE "all clauses pass"; then
        echo "PASS test_canonical_fenced_code_block_pointer_passes"
    else
        echo "FAIL test_canonical_fenced_code_block_pointer_passes: rc=$rc out=$out"
        rm -rf "$f"; exit 1
    fi
    rm -rf "$f"
}

test_compliant_fixture_passes
test_missing_root_pointer_rejected
test_canonical_self_inheritance_rejected
test_missing_install_upstreams_rejected
test_advisory_mode_returns_zero
test_canonical_fenced_code_block_pointer_passes
echo "all canonical-root + install_upstreams tests passed"
