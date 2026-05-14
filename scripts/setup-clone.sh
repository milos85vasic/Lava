#!/usr/bin/env bash
# scripts/setup-clone.sh — one-time per-clone setup.
#
# Forensic anchor: 1.2.23 cycle (2026-05-14) — discovered that
# core.hooksPath was UNSET on a developer's clone for an entire session,
# silently bypassing the .githooks/pre-push gate (Layer 1 anti-bluff
# checks + Layer 2 scripts/ci.sh --changed-only). Multiple commits
# landed on master without ever passing through the gate.
#
# This script is the operator's run-once-per-clone setup. Idempotent.
#
# Usage:
#   bash scripts/setup-clone.sh
#
# What it sets:
#   - core.hooksPath = .githooks (so .githooks/pre-push runs on push)
#
# What it verifies:
#   - .githooks/pre-push exists + is executable
#   - scripts/commit_all.sh exists + is executable
#   - scripts/check-constitution.sh exists + is executable
#   - scripts/inject-helix-inheritance-block.sh exists + is executable
#   - constitution submodule is initialized (if .gitmodules references it)
#   - Required Lava-domain submodules are initialized
#   - github + gitlab named remotes per §6.W
#
# Inheritance: HelixConstitution §11.4.18 (script documentation mandate)
# + Lava §6.AD (HelixConstitution Inheritance) + Lava Local-Only CI/CD
# rule (the hook IS the local CI gate; bypassing it bypasses the gate).
#
# Classification: project-specific (the file-paths checked are Lava-
# specific; the discipline of "wire the hook on clone" is universal).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "==> Lava per-clone setup"
echo ""

# 1. Wire .githooks/pre-push.
CURRENT_HOOKSPATH="$(git config --get core.hooksPath || echo '<unset>')"
if [[ "$CURRENT_HOOKSPATH" != ".githooks" ]]; then
    git config core.hooksPath .githooks
    echo "  ✓ set core.hooksPath = .githooks (was: $CURRENT_HOOKSPATH)"
else
    echo "  ✓ core.hooksPath already set to .githooks"
fi

# 2. Verify the hook file is present + executable.
if [[ ! -x .githooks/pre-push ]]; then
    echo "  ✗ FATAL: .githooks/pre-push missing OR not executable" >&2
    echo "    → chmod +x .githooks/pre-push" >&2
    exit 1
fi
echo "  ✓ .githooks/pre-push exists + executable"

# 3. Verify commit + check-constitution + inject scripts.
for s in scripts/commit_all.sh scripts/check-constitution.sh scripts/inject-helix-inheritance-block.sh; do
    if [[ ! -x "$s" ]]; then
        echo "  ✗ FATAL: $s missing OR not executable" >&2
        exit 1
    fi
done
echo "  ✓ commit + check-constitution + inject scripts present"

# 4. Verify submodule initialization.
uninitialized=()
while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    status_char="${line:0:1}"
    path=$(echo "$line" | awk '{print $2}')
    if [[ "$status_char" == "-" ]]; then
        uninitialized+=("$path")
    fi
done < <(git submodule status 2>/dev/null || true)

if [[ ${#uninitialized[@]} -gt 0 ]]; then
    echo "  ⚠ ${#uninitialized[@]} submodule(s) not initialized:"
    for u in "${uninitialized[@]}"; do echo "      $u"; done
    echo "    → git submodule update --init --recursive"
else
    echo "  ✓ all submodules initialized"
fi

# 5. Verify constitution submodule structure.
if [[ -d constitution ]]; then
    for required in CLAUDE.md AGENTS.md Constitution.md; do
        if [[ ! -f "constitution/$required" ]]; then
            echo "  ⚠ constitution/$required missing — submodule may need re-init" >&2
        fi
    done
    echo "  ✓ constitution submodule structure ok"
fi

# 6. Verify github + gitlab named remotes per §6.W.
remotes=$(git remote)
have_github=false
have_gitlab=false
for r in $remotes; do
    case "$r" in
        github) have_github=true ;;
        gitlab) have_gitlab=true ;;
    esac
done
if [[ "$have_github" != "true" ]]; then
    echo "  ⚠ no 'github' named remote — §6.W requires github + gitlab"
fi
if [[ "$have_gitlab" != "true" ]]; then
    echo "  ⚠ no 'gitlab' named remote — §6.W requires github + gitlab"
fi
if [[ "$have_github" == "true" && "$have_gitlab" == "true" ]]; then
    echo "  ✓ github + gitlab remotes configured"
fi

echo ""
echo "==> Setup complete. Run:"
echo "    bash scripts/check-constitution.sh   # verify all clauses"
echo "    bash scripts/ci.sh --changed-only    # local CI gate"
