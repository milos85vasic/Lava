#!/usr/bin/env bash
# scripts/check-gitignore-coverage.sh — §11.4.30 enforcement gate.
#
# Per HelixConstitution §11.4.30 (.gitignore + No-Versioned-Build-
# Artifacts Mandate, 2026-05-15): every project module / owned-by-us
# submodule / service / application MUST ship a proper .gitignore
# covering the 6 forbidden-from-version-control classes:
#   1. Build artefacts: /bin/ /build/ /dist/ /out/ target/ *.exe etc
#   2. Cache files: __pycache__/ .gradle/ node_modules/ .next/ etc
#   3. Temp files: *.tmp *.swp *~ .DS_Store etc
#   4. Sensitive-data files: .env / .env.* / *.pem / *.key etc
#   5. Generated reports/logs: *.log coverage.out htmlcov/
#   6. OS/IDE personal state: .idea/ .vscode/ .history/
#
# Plus: NO tracked files may match the forbidden patterns (a tracked
# *.log despite the ignore-line is a violation of equal severity).
#
# Modes:
#   --strict (default): exit 1 on any violation
#   --advisory: exit 0 even on violation (incremental adoption)
#
# Inheritance: HelixConstitution §11.4.30 + §11.4.18 (script docs).
# Classification: project-specific (the module list is Lava-specific;
# the per-module .gitignore mandate is universal per HelixConstitution).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

STRICT="${LAVA_GITIGNORE_STRICT:-1}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --strict)   STRICT=1; shift ;;
        --advisory) STRICT=0; shift ;;
        *)          echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

# Module roots that MUST have .gitignore. List is per-Lava but the
# discipline is universal. Only ACTUAL modules (with build.gradle.kts
# OR being a submodule root with .git) are required to have .gitignore.
# Pure parent dirs (e.g., core/network/ which only holds child modules)
# are skipped — their child modules carry the .gitignore.
declare -a MODULE_ROOTS=()
[[ -d app ]] && MODULE_ROOTS+=(app)
[[ -d buildSrc ]] && MODULE_ROOTS+=(buildSrc)
[[ -d lava-api-go ]] && MODULE_ROOTS+=(lava-api-go)
for d in core/*/ feature/*/; do
    [[ -d "$d" ]] || continue
    # Only include if it's an actual Gradle module (has build.gradle.kts)
    if [[ -f "${d}build.gradle.kts" ]] || [[ -f "${d}build.gradle" ]]; then
        MODULE_ROOTS+=("${d%/}")
    fi
done
# Sub-leaf modules under core/network, core/work, core/auth, core/tracker etc.
for d in core/*/*/; do
    [[ -d "$d" ]] || continue
    if [[ -f "${d}build.gradle.kts" ]] || [[ -f "${d}build.gradle" ]]; then
        MODULE_ROOTS+=("${d%/}")
    fi
done
# Submodule roots — each owned-by-us submodule MUST have .gitignore.
for d in submodules/*/; do
    [[ -d "$d" ]] && MODULE_ROOTS+=("${d%/}")
done

missing_gitignore=()
for m in "${MODULE_ROOTS[@]}"; do
    [[ -f "$m/.gitignore" ]] || missing_gitignore+=("$m")
done

# Tracked files matching forbidden patterns (anti-bluff: ignore-line
# alone insufficient — file must actually be untracked).
#
# Allowlist: explicit per-file exceptions (with per-line justification).
# Adding to this list requires inline rationale. Each entry MUST be a
# legitimate non-sensitive file whose name pattern matches the
# forbidden-pattern regex.
ALLOWLIST=(
    # deployment/thinker/thinker.local.env — DEPLOYMENT-RECIPE doc, NOT a secret container.
    # File header explicitly states "no credentials hardcoded; all values come
    # from the operator's local .env which is gitignored". The .env extension
    # is misleading (would be cleaner as .config); rename owed to Phase 6
    # (lowercase snake_case + clearer semantics).
    "deployment/thinker/thinker.local.env"
)

is_allowlisted() {
    local f=$1
    for allowed in "${ALLOWLIST[@]}"; do
        [[ "$f" == "$allowed" ]] && return 0
    done
    return 1
}

forbidden_tracked=()
while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    is_allowlisted "$f" && continue
    forbidden_tracked+=("$f")
done < <(git ls-files 2>/dev/null | grep -E '\.env$|\.env\.[a-z]+$|\.pem$|\.key$|\.crt$|id_rsa|id_ed25519|node_modules/|__pycache__/|\.gradle/|/build/|/dist/|/out/|\.DS_Store$|Thumbs\.db$|\.swp$' | grep -v '\.env\.example$' || true)

# Report
echo "==> §11.4.30 .gitignore coverage scan"
echo "    Module roots scanned: ${#MODULE_ROOTS[@]}"
echo "    Missing .gitignore: ${#missing_gitignore[@]}"
echo "    Tracked forbidden-pattern files: ${#forbidden_tracked[@]}"

if [[ ${#missing_gitignore[@]} -eq 0 && ${#forbidden_tracked[@]} -eq 0 ]]; then
    echo "    ✓ all modules have .gitignore + no tracked forbidden-pattern files"
    exit 0
fi

if [[ ${#missing_gitignore[@]} -gt 0 ]]; then
    echo ""
    echo "    Missing .gitignore (per §11.4.30):"
    printf '      %s\n' "${missing_gitignore[@]}"
fi
if [[ ${#forbidden_tracked[@]} -gt 0 ]]; then
    echo ""
    echo "    Tracked forbidden-pattern files (per §11.4.30 anti-bluff):"
    printf '      %s\n' "${forbidden_tracked[@]}"
fi

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_GITIGNORE_STRICT=1 — failing." >&2
    exit 1
fi
exit 0
