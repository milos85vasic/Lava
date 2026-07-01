#!/usr/bin/env bash
# scripts/check-cycle-coverage.sh — §6.AK cycle-coverage gate (Phase 1)
#
# Enforces the §6.AK coverage-intersection contract: every CHANGELOG-claimed
# user-visible fix in the cycle-coverage-map for <version> MUST have a covering
# device Challenge that was EXECUTED + PASSED on a non-host-direct runner, on
# the SAME commit, in fresh (<=24h) §6.Z evidence. It also subsumes the §6.Z-debt
# runtime checks (evidence presence + commit-SHA match + freshness).
#
# Interface (backward-compatible — firebase-distribute.sh Gate 7 + pre-push
# Check 10 pass --version/--channel/--evidence-dir[/--head]/--strict; the
# hermetic tests add --map/--head/--now-epoch and the env overrides below):
#   --version=<vName-vCode>   (required)  e.g. 1.3.12-1078
#   --channel=<chan>          (required)  e.g. debug | release
#   --evidence-dir=<dir>      (required)  §6.Z evidence + cycle-coverage-map dir
#   --map=<path>              (optional)  cycle-coverage-map; auto-resolved if absent
#   --head=<sha>              (optional)  commit the evidence must match; default git HEAD
#   --now-epoch=<epoch>       (optional)  "now" for the freshness check; default date +%s
#   --strict                  (optional)  accepted; the gate is fail-closed regardless
# Env overrides (used by the hermetic wiring tests; flags take precedence):
#   LAVA_CYCLE_COVERAGE_HEAD       -> --head
#   LAVA_CYCLE_COVERAGE_NOW_EPOCH  -> --now-epoch
#
# Exit codes:
#   0 = every claim covered by an executed+PASSED non-host-direct Challenge
#   1 = a claim lacks a covering executed+PASSED Challenge, OR evidence is stale
#   2 = map/evidence missing, map-version mismatch, or evidence commit-SHA mismatch
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VER="" CHAN="" EDIR="" MAP="" HEAD_FLAG="" NOW_FLAG="" STRICT=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version=*)      VER="${1#*=}"; shift 1;;
        --version)        VER="${2:-}"; shift 2;;
        --channel=*)      CHAN="${1#*=}"; shift 1;;
        --channel)        CHAN="${2:-}"; shift 2;;
        --evidence-dir=*) EDIR="${1#*=}"; shift 1;;
        --evidence-dir)   EDIR="${2:-}"; shift 2;;
        --map=*)          MAP="${1#*=}"; shift 1;;
        --map)            MAP="${2:-}"; shift 2;;
        --head=*)         HEAD_FLAG="${1#*=}"; shift 1;;
        --head)           HEAD_FLAG="${2:-}"; shift 2;;
        --now-epoch=*)    NOW_FLAG="${1#*=}"; shift 1;;
        --now-epoch)      NOW_FLAG="${2:-}"; shift 2;;
        --strict)         STRICT=1; shift 1;;
        *) echo "FATAL: unknown $1" >&2; exit 2;;
    esac
done
[[ -n "$VER" && -n "$CHAN" && -n "$EDIR" ]] || { echo "FATAL: --version --channel --evidence-dir required" >&2; exit 2; }

# Effective HEAD + "now": flag > env > live default. (-u-safe via :- defaults.)
HEAD="${HEAD_FLAG:-${LAVA_CYCLE_COVERAGE_HEAD:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}}"
NOW="${NOW_FLAG:-${LAVA_CYCLE_COVERAGE_NOW_EPOCH:-$(date -u +%s)}}"

# ── Resolve the cycle-coverage-map (exit 2 if absent) ────────────────────────
# Auto-resolution accepts both the hermetic-test layout ($VER-cycle-coverage-map.yaml)
# and the shipped/real layout (cycle-coverage-map-$VER.yaml).
if [[ -z "$MAP" ]]; then
    for cand in "$EDIR/$VER-cycle-coverage-map.yaml" "$EDIR/cycle-coverage-map-$VER.yaml"; do
        [[ -f "$cand" ]] && { MAP="$cand"; break; }
    done
fi
[[ -n "$MAP" && -f "$MAP" ]] || { echo "FATAL §6.AK.1: cycle-coverage-map not found for $VER under $EDIR" >&2; exit 2; }

MV="$(grep -E '^version:' "$MAP" | head -1 | sed 's/^version:[[:space:]]*//' | tr -d '"' | xargs 2>/dev/null || true)"
[[ "$MV" == "$VER" ]] || { echo "FATAL §6.AK.2: map version '$MV' != '$VER'" >&2; exit 2; }

# ── Resolve the §6.Z evidence file (exit 2 if absent) ────────────────────────
EVI=""
for cand in "$EDIR/$VER-test-evidence.json" "$EDIR/$VER-test-evidence.md"; do
    [[ -f "$cand" ]] && { EVI="$cand"; break; }
done
[[ -n "$EVI" ]] || { echo "FATAL §6.AK.3: §6.Z evidence not found for $VER ($EDIR/$VER-test-evidence.{json,md})" >&2; exit 2; }

# ── Commit-SHA match (§6.Z-debt subsumed; exit 2 on mismatch) ────────────────
# Extract the evidence's commit from any of the supported shapes; "unknown"
# (un-extractable) passes, preserving the shipped lenient behaviour for older
# free-text markdown evidence.
ESHA="unknown"
if grep -qE 'cycle-coverage:.*commit=' "$EVI" 2>/dev/null; then
    ESHA="$(grep -E 'cycle-coverage:.*commit=' "$EVI" | head -1 | sed -E 's/.*commit=([^[:space:]]+).*/\1/')"
elif grep -q '"commit_sha"' "$EVI" 2>/dev/null; then
    ESHA="$(grep -o '"commit_sha"[[:space:]]*:[[:space:]]*"[^"]*"' "$EVI" | head -1 | sed -E 's/.*"([^"]*)"[[:space:]]*$/\1/')"
elif grep -qE '^commit_sha:' "$EVI" 2>/dev/null; then
    ESHA="$(grep -E '^commit_sha:' "$EVI" | head -1 | sed 's/^commit_sha:[[:space:]]*//' | tr -d '"' | xargs 2>/dev/null || true)"
fi
[[ "$ESHA" == "unknown" || "$ESHA" == "$HEAD" ]] || { echo "FATAL §6.AK.4: evidence commit-SHA '$ESHA' != current '$HEAD'" >&2; exit 2; }

# ── Freshness (<=24h; exit 1 on stale). Only enforced when a timestamp is found.
ETS=""
if grep -qE 'cycle-coverage:.*timestamp=' "$EVI" 2>/dev/null; then
    ETS="$(grep -E 'cycle-coverage:.*timestamp=' "$EVI" | head -1 | sed -E 's/.*timestamp=([^[:space:]]+).*/\1/')"
elif grep -q '"timestamp"' "$EVI" 2>/dev/null; then
    ETS="$(grep -o '"timestamp"[[:space:]]*:[[:space:]]*"[^"]*"' "$EVI" | head -1 | sed -E 's/.*"([^"]*)"[[:space:]]*$/\1/')"
fi
if [[ -n "$ETS" ]]; then
    ETS_EPOCH="$(date -u -d "$ETS" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$ETS" +%s 2>/dev/null || echo "")"
    if [[ -n "$ETS_EPOCH" ]]; then
        AGE=$(( NOW - ETS_EPOCH ))
        if (( AGE > 86400 )); then
            echo "FATAL §6.AK: §6.Z evidence is stale (age ${AGE}s > 24h) for $VER" >&2
            exit 1
        fi
    fi
fi

# ── Evidence parsing mode ────────────────────────────────────────────────────
# Structured mode = machine "challenge: fqn=... verdict=... runner=..." lines
# (the hermetic-test / autonomous-QA format). Otherwise free-text/JSON/markdown
# (real §6.Z evidence) → fall back to a JSON-aware + presence match.
STRUCTURED=0
grep -qE 'fqn=.*verdict=' "$EVI" 2>/dev/null && STRUCTURED=1

# challenge_passed <covering-name> → 0 if executed+PASSED on a non-host-direct
# runner (structured) / present-and-not-failed (free-text), else non-zero.
challenge_passed() {
    local name="$1" leaf="${1##*.}"
    if [[ "$STRUCTURED" -eq 1 ]]; then
        awk -v n="$name" -v leaf="$leaf" '
            /fqn=/ && (index($0,n)>0 || index($0,leaf)>0) && /verdict=PASS/ && $0 !~ /runner=host-direct/ { found=1 }
            END { exit(found?0:1) }' "$EVI"
        return $?
    fi
    # Free-text / JSON / markdown (real §6.Z evidence):
    grep -qE "\"$leaf\"[[:space:]]*:[[:space:]]*\"PASS\"" "$EVI" 2>/dev/null && return 0
    grep -qE "\"$leaf\"[[:space:]]*:[[:space:]]*\"(FAIL|SKIP|ERROR)\"" "$EVI" 2>/dev/null && return 1
    grep -qF "$leaf" "$EVI" 2>/dev/null && return 0
    return 1
}

# ── Parse claims (both map shapes) → "<idx>\t<space-separated covering names>" ─
fail=0            # distinct claims that are not fully covered
refs=0            # individual covering-Challenge refs that are missing/non-PASS
claims=0
while IFS=$'\t' read -r idx names; do
    claims=$(( claims + 1 ))
    if [[ -z "${names// /}" ]]; then
        echo "WARN §6.AK: claim #$idx has NO covering Challenge (unverified)" >&2
        fail=$(( fail + 1 )); refs=$(( refs + 1 ))
        continue
    fi
    claim_bad=0
    for n in $names; do
        if ! challenge_passed "$n"; then
            echo "WARN §6.AK: covering Challenge '$n' (claim #$idx) is not executed+PASSED in $EVI" >&2
            refs=$(( refs + 1 )); claim_bad=1
        fi
    done
    [[ "$claim_bad" -eq 1 ]] && fail=$(( fail + 1 ))
done < <(awk '
    { line = $0; t = line; sub(/^[ \t]+/, "", t) }
    t ~ /^#/                                   { next }                            # comment
    line ~ /^[ \t]*-[ \t]+(fix|bullet):/       { claim++; names[claim]=""; next }  # claim start
    claim == 0                                 { next }                            # preamble (version:/claims:)
    line ~ /covering_challenge:[ \t]/ && line !~ /covering_challenges:/ {           # scalar form
        v = line; sub(/.*covering_challenge:[ \t]*/, "", v); gsub(/"/, "", v); gsub(/^[ \t]+|[ \t]+$/, "", v)
        if (v != "") names[claim] = names[claim] (names[claim]=="" ? "" : " ") v
        next
    }
    line ~ /^[ \t]*-[ \t]+"/ {                                                      # list item: - "FQN"
        v = line; sub(/^[ \t]*-[ \t]+/, "", v); gsub(/"/, "", v); gsub(/^[ \t]+|[ \t]+$/, "", v)
        if (v != "") names[claim] = names[claim] (names[claim]=="" ? "" : " ") v
        next
    }
    END { for (i = 1; i <= claim; i++) printf "%d\t%s\n", i, names[i] }
' "$MAP")

if [[ "$claims" -eq 0 ]]; then
    echo "WARN §6.AK: no claims in $MAP — nothing to verify" >&2
    exit 0
fi
if [[ "$fail" -gt 0 ]]; then
    echo "FATAL §6.AK: $fail of $claims claim(s) lack a covering executed+PASSED device Challenge for $VER ($refs uncovered ref(s))" >&2
    exit 1
fi
echo "§6.AK PASS: $claims claim(s) covered by executed+PASSED device Challenges (SHA $HEAD)"
exit 0
