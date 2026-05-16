#!/usr/bin/env bash
# scripts/generate-coverage-ledger.sh — §11.4.25 Full-Automation-Coverage ledger generator.
#
# Per HelixConstitution §11.4.25 (Full-Automation-Coverage Mandate, 2026-05-15):
# "Consuming projects MUST publish a coverage ledger (matrix of: feature ×
# platform × invariant-1..6 × status) that is regenerated as part of the
# release-gate sweep."
#
# This script walks the Lava tree + every owned-by-us submodule + the Go API
# service and emits a machine-readable YAML ledger at docs/coverage-ledger.yaml
# capturing — per module / per submodule — which tests + Challenges + invariants
# are wired and which are gaps.
#
# DESIGN RATIONALE (committed to script as the canonical reference):
#
# 1. **Hybrid row format.** One ledger contains rows for ALL coverage units:
#    - feature/* modules (per-feature Compose UI features)
#    - core/* modules (shared infrastructure)
#    - app/ (the Android client entry point)
#    - lava-api-go/ (the Go API service)
#    - submodules/* (owned-by-us reusable code that ships with the project)
#    Mixing kinds in one file lets the verifier do one walk + one assertion
#    pass rather than 5 separate files; the `kind` column distinguishes them.
#
# 2. **Six invariants per row (§11.4.25):**
#    1. `anti_bluff` — Anti-bluff posture (captured runtime evidence per §7.1)
#    2. `working_capability` — Proof of working capability E2E on target topology
#    3. `documented_promise` — Implementation matches the documented promise
#    4. `no_open_bugs` — Test suite is the canonical seam; no surfaced defects
#    5. `documented` — Full documentation kept in sync (§11.4.12 / §11.4.18)
#    6. `four_layer` — Four-layer test floor (pre-build + post-build + runtime
#       + paired mutation per §1)
#
#    Each invariant per row is one of: `pass` | `gap` | `n/a` | `waiver:<reason>`.
#
# 3. **Mechanical inference vs. manual annotation.**
#    - Invariants 1, 2, 6 CAN be inferred from on-disk state (test file counts,
#      Challenge presence, paired-mutation rehearsal records).
#    - Invariants 3, 4, 5 require human-or-operator judgement.
#      The generator emits a conservative DEFAULT (`gap` for unknown) which
#      operators / reviewers OVERRIDE via the per-row `waiver:` mechanism in
#      `docs/coverage-ledger.waivers.yaml` (hand-edited; merged in).
#
# 4. **Determinism.** Same git tree → same generated ledger byte-for-byte
#    (modulo the metadata.generated_at timestamp). Sort orders are explicit
#    (alpha by path within kind). The verifier strips the metadata block
#    before comparing committed vs regenerated ledgers.
#
# 5. **Source-of-truth ordering.** The generator scans the FILESYSTEM (not
#    the ledger) — so a feature module added without a ledger entry will
#    appear in the next generation. The verifier catches the case where a
#    feature module exists but the ledger is stale.
#
# 6. **Anti-bluff posture per §6.J:** the ledger MUST reflect actual test
#    files on disk, not aspirational coverage. The generator never assigns
#    `pass` for an invariant without positive evidence (file count > 0,
#    Challenge import detected, etc.). When in doubt, emit `gap` — never
#    `pass`.
#
# Usage:
#   bash scripts/generate-coverage-ledger.sh           # writes docs/coverage-ledger.yaml + prints gap summary
#   bash scripts/generate-coverage-ledger.sh --stdout  # emits YAML to stdout (no file write)
#   bash scripts/generate-coverage-ledger.sh --quiet   # writes file silently
#
# Output:
#   docs/coverage-ledger.yaml: deterministic YAML ledger (UTF-8)
#   stdout: gap analysis summary + count of rows by status
#
# Inheritance: HelixConstitution §11.4.25 (the mandate); §11.4.18 (script docs);
# §6.J/§6.L (anti-bluff posture for the generator itself).
# Classification: project-specific (the Lava module list is project-specific;
# the §11.4.25 ledger mandate is universal per HelixConstitution).

set -uo pipefail

REPO_ROOT="${LAVA_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$REPO_ROOT"

MODE="write"
QUIET=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --stdout) MODE="stdout"; QUIET=1; shift ;;
        --quiet)  QUIET=1; shift ;;
        -h|--help) sed -n '3,70p' "$0"; exit 0 ;;
        *) echo "ERROR: unknown arg $1" >&2; exit 2 ;;
    esac
done

LEDGER_PATH="docs/coverage-ledger.yaml"
WAIVERS_PATH="docs/coverage-ledger.waivers.yaml"

# -----------------------------------------------------------------------------
# Pre-scan: cache the concatenated Challenge file body once for cheap greps.
# -----------------------------------------------------------------------------

CHALLENGE_DIR="app/src/androidTest/kotlin/lava/app/challenges"
ALL_CHALLENGES_BODY=""
if [[ -d "$CHALLENGE_DIR" ]]; then
    ALL_CHALLENGES_BODY=$(cat "$CHALLENGE_DIR"/Challenge*Test.kt 2>/dev/null || true)
fi

# Build a map from feature-name → space-separated Challenge labels.
# Strategies (matches scripts/check-challenge-coverage.sh):
#   1. import lava.<feature>
#   2. // covers-feature: <feature>
#   3. file-name keyword match
#   4. heuristic broad-flow mapping
challenges_for_feature() {
    local feature="$1"
    [[ -d "$CHALLENGE_DIR" ]] || return 0
    local matches=""
    local f bn label
    for f in "$CHALLENGE_DIR"/Challenge*Test.kt; do
        [[ -f "$f" ]] || continue
        bn=$(basename "$f" .kt)
        label=$(echo "$bn" | grep -oE '^Challenge[0-9]+' | sed -E 's/^Challenge0*([0-9]+)$/C\1/' || true)
        [[ -z "$label" ]] && continue
        # Strategy 1 + 2: import or covers-feature marker.
        if grep -qE "(^import[[:space:]]+lava\\.${feature}([._a-zA-Z]|$)|// covers-feature:[[:space:]]+${feature}\\b)" "$f" 2>/dev/null; then
            matches="$matches $label"
            continue
        fi
        # Strategy 3: filename contains capitalized feature name.
        local feat_cap
        feat_cap="$(printf '%s' "${feature^}")"
        if [[ "$bn" == *"$feat_cap"* ]]; then
            matches="$matches $label"
            continue
        fi
    done
    # Strategy 4: heuristic broad-flow mapping (match against full body).
    case "$feature" in
        onboarding)
            grep -qE "Onboarding|WelcomeStep" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        menu)
            grep -qE "Menu" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        login)
            grep -qE "Login|Authenticated" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        search|search_input|search_result)
            grep -qE "Search" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        topic)
            grep -qE "Topic|ViewTopic" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        provider_config)
            grep -qE "ProviderRow|ProviderConfig" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        main)
            grep -qE "AppLaunch|FirebaseColdStart|AuthInterceptor" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        credentials|credentials_manager)
            grep -qE "Credentials|Auth" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        forum)
            grep -qE "Forum|Browse" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
        favorites)
            grep -qE "Favorites|Bookmark" <<<"$ALL_CHALLENGES_BODY" \
                && matches="$matches HEURISTIC" ;;
    esac
    # De-dupe + alpha sort (keeping HEURISTIC sentinel at the end).
    echo "$matches" | tr ' ' '\n' | sort -uV | grep -v '^$' | tr '\n' ' ' | sed 's/[[:space:]]*$//'
}

# -----------------------------------------------------------------------------
# Helpers
# -----------------------------------------------------------------------------

count_tests() {
    local base="$1"
    local pattern="$2"
    [[ -d "$base" ]] || { echo 0; return; }
    find "$base" -path '*/build' -prune -o -path '*/.git' -prune -o -name "$pattern" -print 2>/dev/null \
        | grep -c . || true
}

sample_tests() {
    local base="$1"
    local pattern="$2"
    local n="${3:-3}"
    [[ -d "$base" ]] || return 0
    find "$base" -path '*/build' -prune -o -path '*/.git' -prune -o -name "$pattern" -print 2>/dev/null \
        | xargs -n1 basename 2>/dev/null \
        | sort -u \
        | head -n "$n"
}

yaml_str() {
    local s="$1"
    s="${s//\\/\\\\}"
    s="${s//\"/\\\"}"
    echo "\"$s\""
}

infer_invariants() {
    local unit="$1"
    local integ="$2"
    local chal="$3"
    local has_docs="$4"

    local i1="gap"
    [[ "$chal" -gt 0 ]] && i1="pass"

    local i2="gap"
    [[ "$chal" -gt 0 ]] && i2="pass"

    local i3="gap"
    local i4="gap"

    local i5="gap"
    [[ "$has_docs" == "1" ]] && i5="pass"

    local i6="gap"
    if [[ "$unit" -gt 0 && "$chal" -gt 0 ]]; then
        i6="pass"
    fi

    echo "$i1 $i2 $i3 $i4 $i5 $i6"
}

# Read waiver value for a (path, invariant) tuple.
# Returns the override string if present in WAIVERS_PATH, else empty.
get_waiver() {
    local path="$1"
    local invariant="$2"
    [[ -f "$WAIVERS_PATH" ]] || { echo ""; return; }
    awk -v target="$path" -v inv="$invariant" '
        /^[[:space:]]*-[[:space:]]+path:[[:space:]]+/ {
            current = $0
            sub(/^[[:space:]]*-[[:space:]]+path:[[:space:]]+/, "", current)
            sub(/^"/, "", current); sub(/"$/, "", current)
            in_target = (current == target) ? 1 : 0
            in_invariants = 0
            next
        }
        /^[[:space:]]+invariants:[[:space:]]*$/ {
            in_invariants = in_target ? 1 : 0
            next
        }
        in_target && in_invariants && $1 == inv":" {
            line = $0
            sub(/^[[:space:]]+[a-z_]+:[[:space:]]+/, "", line)
            sub(/^"/, "", line); sub(/"$/, "", line)
            print line
            exit
        }
    ' "$WAIVERS_PATH"
}

apply_waiver() {
    local path="$1"
    local invariant="$2"
    local default_value="$3"
    local override
    override=$(get_waiver "$path" "$invariant")
    if [[ -n "$override" ]]; then
        echo "$override"
    else
        echo "$default_value"
    fi
}

# Compute coverage status (covered / partial / gap) from the 6 invariant values.
compute_status() {
    local pass_count=0 gap_count=0
    local v
    for v in "$@"; do
        case "$v" in
            pass|pass:*) pass_count=$((pass_count + 1)) ;;
            gap)         gap_count=$((gap_count + 1)) ;;
            # waiver:* and n/a treated as non-pass + non-gap
        esac
    done
    if [[ "$gap_count" -eq 0 ]]; then
        echo "covered"
    elif [[ "$pass_count" -ge 3 ]]; then
        echo "partial"
    else
        echo "gap"
    fi
}

# Emit one YAML row.
# Args: path kind base unit_pattern integ_pattern feature_name (optional)
emit_row() {
    local path="$1"
    local kind="$2"
    local base="$3"
    local unit_pattern="$4"
    local integ_pattern="$5"
    local feature_name="${6:-}"

    local unit_count integ_count chal_count chal_list
    unit_count=$(count_tests "$base" "$unit_pattern")
    integ_count=$(count_tests "$base" "$integ_pattern")

    if [[ -n "$feature_name" ]]; then
        chal_list=$(challenges_for_feature "$feature_name")
    else
        chal_list=""
    fi
    chal_count=$(echo "$chal_list" | tr ' ' '\n' | grep -c . || true)
    [[ -z "$chal_count" ]] && chal_count=0

    local has_docs=0
    if [[ -f "$base/README.md" ]] || [[ -f "$base/CLAUDE.md" ]] || [[ -f "$base/AGENTS.md" ]] || [[ -f "$base/CONSTITUTION.md" ]]; then
        has_docs=1
    fi

    local invariants
    invariants=$(infer_invariants "$unit_count" "$integ_count" "$chal_count" "$has_docs")
    local i1 i2 i3 i4 i5 i6
    read -r i1 i2 i3 i4 i5 i6 <<<"$invariants"
    i1=$(apply_waiver "$path" "anti_bluff" "$i1")
    i2=$(apply_waiver "$path" "working_capability" "$i2")
    i3=$(apply_waiver "$path" "documented_promise" "$i3")
    i4=$(apply_waiver "$path" "no_open_bugs" "$i4")
    i5=$(apply_waiver "$path" "documented" "$i5")
    i6=$(apply_waiver "$path" "four_layer" "$i6")

    local status
    status=$(compute_status "$i1" "$i2" "$i3" "$i4" "$i5" "$i6")

    local samples=()
    while IFS= read -r line; do
        [[ -n "$line" ]] && samples+=("$line")
    done < <(sample_tests "$base" "$unit_pattern" 3)

    echo "  - path: $(yaml_str "$path")"
    echo "    kind: $(yaml_str "$kind")"
    echo "    status: $(yaml_str "$status")"
    echo "    unit_test_count: $unit_count"
    echo "    integration_test_count: $integ_count"
    echo "    challenge_count: $chal_count"
    if [[ -n "$chal_list" && "$chal_count" -gt 0 ]]; then
        echo "    challenges:"
        local c
        for c in $chal_list; do
            echo "      - $(yaml_str "$c")"
        done
    else
        echo "    challenges: []"
    fi
    if [[ ${#samples[@]} -gt 0 ]]; then
        echo "    sample_tests:"
        local s
        for s in "${samples[@]}"; do
            echo "      - $(yaml_str "$s")"
        done
    else
        echo "    sample_tests: []"
    fi
    echo "    invariants:"
    echo "      anti_bluff: $(yaml_str "$i1")"
    echo "      working_capability: $(yaml_str "$i2")"
    echo "      documented_promise: $(yaml_str "$i3")"
    echo "      no_open_bugs: $(yaml_str "$i4")"
    echo "      documented: $(yaml_str "$i5")"
    echo "      four_layer: $(yaml_str "$i6")"

    # Per-row diagnostic to stderr (consumed by summary).
    echo "ROW_STATUS=$status path=$path kind=$kind" >&2
}

# -----------------------------------------------------------------------------
# Build the ledger
# -----------------------------------------------------------------------------

build_ledger() {
    local commit_sha
    commit_sha=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
    local generated_at
    generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)

    cat <<EOF
# AUTO-GENERATED by scripts/generate-coverage-ledger.sh — DO NOT HAND-EDIT.
#
# Per HelixConstitution §11.4.25 (Full-Automation-Coverage Mandate): every
# feature × platform × invariant-1..6 cell appears in this ledger. To
# override an inferred invariant, add an entry in
# docs/coverage-ledger.waivers.yaml (hand-edited, version-controlled,
# requires rationale per row).
#
# Verifier: scripts/check-coverage-ledger.sh
# Companion (waivers): docs/coverage-ledger.waivers.yaml
# Companion (script docs): docs/scripts/generate-coverage-ledger.sh.md
#
# Invariants (per §11.4.25):
#   anti_bluff          — captured runtime evidence per §7.1 + §11.4
#   working_capability  — proof of working capability E2E on target topology
#   documented_promise  — implementation matches documented promise
#   no_open_bugs        — test suite is canonical seam; no surfaced defects
#   documented          — full doc-sync per §11.4.12 / §11.4.18
#   four_layer          — four-layer test floor per §1
#
# Invariant values: pass | gap | n/a | "waiver: <rationale>" | "pass: <rationale>"
metadata:
  schema_version: 1
  generated_at: $(yaml_str "$generated_at")
  generator: $(yaml_str "scripts/generate-coverage-ledger.sh")
  commit_sha: $(yaml_str "$commit_sha")
  mandate: $(yaml_str "HelixConstitution §11.4.25 Full-Automation-Coverage Mandate")

rows:
EOF

    # feature/* rows
    local d feat
    for d in $(find feature -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort); do
        feat=$(basename "$d")
        emit_row "$d" "feature" "$d" "*Test.kt" "*IntegrationTest.kt" "$feat"
    done

    # core/* rows
    for d in $(find core -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort); do
        emit_row "$d" "core" "$d" "*Test.kt" "*IntegrationTest.kt" ""
    done

    # app/ row
    if [[ -d app ]]; then
        emit_row "app" "app" "app" "*Test.kt" "*IntegrationTest.kt" ""
    fi

    # lava-api-go row (Go service)
    if [[ -d lava-api-go ]]; then
        emit_row "lava-api-go" "api" "lava-api-go" "*_test.go" "*integration_test.go" ""
    fi

    # submodules/* rows
    local sm unit_count has_docs invariants status
    local i1 i2 i3 i4 i5 i6
    for d in $(find submodules -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort); do
        sm=$(basename "$d")
        unit_count=0
        local p c
        for p in "*_test.go" "*Test.kt" "test_*.sh"; do
            c=$(count_tests "$d" "$p")
            unit_count=$((unit_count + c))
        done
        has_docs=0
        local f
        for f in README.md CLAUDE.md AGENTS.md CONSTITUTION.md; do
            [[ -f "$d/$f" ]] && has_docs=1 && break
        done
        invariants=$(infer_invariants "$unit_count" "0" "0" "$has_docs")
        read -r i1 i2 i3 i4 i5 i6 <<<"$invariants"
        i1=$(apply_waiver "$d" "anti_bluff" "$i1")
        i2=$(apply_waiver "$d" "working_capability" "$i2")
        i3=$(apply_waiver "$d" "documented_promise" "$i3")
        i4=$(apply_waiver "$d" "no_open_bugs" "$i4")
        i5=$(apply_waiver "$d" "documented" "$i5")
        i6=$(apply_waiver "$d" "four_layer" "$i6")
        status=$(compute_status "$i1" "$i2" "$i3" "$i4" "$i5" "$i6")

        echo "  - path: $(yaml_str "$d")"
        echo "    kind: $(yaml_str "submodule")"
        echo "    status: $(yaml_str "$status")"
        echo "    unit_test_count: $unit_count"
        echo "    integration_test_count: 0"
        echo "    challenge_count: 0"
        echo "    challenges: []"
        echo "    sample_tests: []"
        echo "    invariants:"
        echo "      anti_bluff: $(yaml_str "$i1")"
        echo "      working_capability: $(yaml_str "$i2")"
        echo "      documented_promise: $(yaml_str "$i3")"
        echo "      no_open_bugs: $(yaml_str "$i4")"
        echo "      documented: $(yaml_str "$i5")"
        echo "      four_layer: $(yaml_str "$i6")"

        echo "ROW_STATUS=$status path=$d kind=submodule" >&2
    done
}

# -----------------------------------------------------------------------------
# Emit + summary
# -----------------------------------------------------------------------------

tmp_yaml=$(mktemp)
tmp_summary=$(mktemp)
trap 'rm -f "$tmp_yaml" "$tmp_summary"' EXIT

build_ledger >"$tmp_yaml" 2>"$tmp_summary"

if [[ "$MODE" == "stdout" ]]; then
    cat "$tmp_yaml"
else
    mkdir -p "$(dirname "$LEDGER_PATH")"
    mv "$tmp_yaml" "$LEDGER_PATH"
fi

if [[ "$QUIET" == "0" ]]; then
    echo "==> §11.4.25 coverage ledger generation"
    echo ""
    [[ "$MODE" == "write" ]] && echo "    written: $LEDGER_PATH"
    total=$(wc -l <"$tmp_summary" | tr -d ' ')
    covered=$(grep -c 'ROW_STATUS=covered' "$tmp_summary" 2>/dev/null || true)
    partial=$(grep -c 'ROW_STATUS=partial' "$tmp_summary" 2>/dev/null || true)
    gap=$(grep -c 'ROW_STATUS=gap' "$tmp_summary" 2>/dev/null || true)
    [[ -z "$covered" ]] && covered=0
    [[ -z "$partial" ]] && partial=0
    [[ -z "$gap" ]] && gap=0
    echo "    rows: $total"
    echo "    covered: $covered"
    echo "    partial: $partial"
    echo "    gap: $gap"
    echo ""
    echo "    Per-kind breakdown:"
    for kind in feature core app api submodule; do
        k_total=$(grep -c "kind=$kind" "$tmp_summary" 2>/dev/null || true)
        [[ -z "$k_total" ]] && k_total=0
        [[ "$k_total" -gt 0 ]] && echo "      $kind: $k_total"
    done
    if [[ "$gap" -gt 0 ]]; then
        echo ""
        echo "    Gap rows (status=gap — need attention OR explicit waiver):"
        grep 'ROW_STATUS=gap' "$tmp_summary" | sed 's/^/      /'
    fi
fi
exit 0
