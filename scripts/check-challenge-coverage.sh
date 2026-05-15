#!/usr/bin/env bash
# scripts/check-challenge-coverage.sh — §6.AE per-feature Challenge mandate.
#
# Per §6.AE.1: every Lava feature module under feature/*/ MUST have at
# least one Compose UI Challenge Test that targets it. Targeting is
# detected by ANY of:
#   - The Challenge file imports a class from `lava.${feature}` or
#     `lava.${feature}.*`
#   - The Challenge file's KDoc / source contains a `// covers-feature: <name>`
#     marker comment naming the feature
#   - The Challenge file's name matches a feature-keyword convention
#     (heuristic backstop)
#
# Default mode: ADVISORY (LAVA_CHALLENGE_COVERAGE_STRICT=0). The strict
# flip is OWED via §6.AE-debt — flips after the per-feature backfill
# pass that adds dedicated Challenge files for any feature currently
# covered only by broad-flow tests.
#
# Usage:
#   bash scripts/check-challenge-coverage.sh
#
# Inheritance: HelixConstitution §11.4 + Lava §6.AE + §6.J/§6.L.
# Classification: project-specific (the Lava feature module list is
# specific to Lava; the per-feature Challenge mandate is universal per
# §6.AE inherited from HelixConstitution).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

STRICT="${LAVA_CHALLENGE_COVERAGE_STRICT:-0}"

challenge_dir="app/src/androidTest/kotlin/lava/app/challenges"
if [[ ! -d "$challenge_dir" ]]; then
    echo "==> §6.AE coverage scan: no Challenge directory at $challenge_dir"
    exit 0
fi

# Build the per-feature module list.
declare -a features=()
for d in feature/*/; do
    [[ -d "$d" ]] || continue
    bn=$(basename "$d")
    features+=("$bn")
done

if [[ ${#features[@]} -eq 0 ]]; then
    echo "==> §6.AE coverage scan: no feature modules at feature/"
    exit 0
fi

# Concatenate every Challenge file content for grep scans.
all_challenges=$(cat "$challenge_dir"/Challenge*Test.kt 2>/dev/null || true)

uncovered=()
covered=()
for f in "${features[@]}"; do
    found=false
    # 1. Import-based detection: any `import lava.${feature}` line
    if grep -qE "^import[ \t]+lava\\.${f}([._a-zA-Z]|$)" <<<"$all_challenges"; then
        found=true
    fi
    # 2. Explicit covers-feature marker
    if [[ "$found" != "true" ]] && grep -qE "// covers-feature:[ \t]*${f}\\b" <<<"$all_challenges"; then
        found=true
    fi
    # 3. Name-keyword convention (filename contains the feature name)
    if [[ "$found" != "true" ]]; then
        if find "$challenge_dir" -name "Challenge*${f^}*Test.kt" 2>/dev/null | head -1 | grep -q .; then
            found=true
        fi
    fi
    # 4. Heuristic name-mapping for known broad-flow coverage
    if [[ "$found" != "true" ]]; then
        case "$f" in
            onboarding) grep -qE "Onboarding|WelcomeStep" <<<"$all_challenges" && found=true ;;
            menu)       grep -qE "Menu" <<<"$all_challenges" && found=true ;;
            login)      grep -qE "Login|Authenticated" <<<"$all_challenges" && found=true ;;
            search|search_input|search_result) grep -qE "Search" <<<"$all_challenges" && found=true ;;
            topic)      grep -qE "Topic|ViewTopic" <<<"$all_challenges" && found=true ;;
            provider_config) grep -qE "ProviderRow|ProviderConfig" <<<"$all_challenges" && found=true ;;
            main)       grep -qE "AppLaunch|FirebaseColdStart|AuthInterceptor" <<<"$all_challenges" && found=true ;;
            credentials|credentials_manager) grep -qE "Credentials|Auth" <<<"$all_challenges" && found=true ;;
            forum)      grep -qE "Forum|Browse" <<<"$all_challenges" && found=true ;;
            favorites)  grep -qE "Favorites|Bookmark" <<<"$all_challenges" && found=true ;;
        esac
    fi
    if [[ "$found" == "true" ]]; then
        covered+=("$f")
    else
        uncovered+=("$f")
    fi
done

echo "==> §6.AE per-feature Challenge coverage scan"
echo "    Feature modules: ${#features[@]}"
echo "    Covered (by direct import / marker / heuristic): ${#covered[@]}"
echo "    Uncovered: ${#uncovered[@]}"

if [[ ${#uncovered[@]} -eq 0 ]]; then
    echo "    ✓ every feature module has at least one Challenge"
    exit 0
fi

echo ""
echo "    Uncovered features (need a dedicated Challenge):"
printf '      %s\n' "${uncovered[@]}"
echo ""
echo "    Remediation per uncovered feature: add a Challenge file under"
echo "    app/src/androidTest/kotlin/lava/app/challenges/Challenge<N>_${uncovered[0]^}*Test.kt"
echo "    that imports a real class from the feature's package + drives the"
echo "    real screen on the gating matrix. Include the FALSIFIABILITY"
echo "    REHEARSAL block (§6.AB.3) in the KDoc."
echo "    OR add a // covers-feature: <name> marker in an existing Challenge"
echo "    file that genuinely traverses the feature's user-visible surface."

if [[ "$STRICT" == "1" ]]; then
    echo ""
    echo "    LAVA_CHALLENGE_COVERAGE_STRICT=1 — failing on ${#uncovered[@]} uncovered feature(s)."
    exit 1
else
    echo ""
    echo "    Default ADVISORY mode (per §6.AE-debt). Set LAVA_CHALLENGE_COVERAGE_STRICT=1 to fail."
    exit 0
fi
