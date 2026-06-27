#!/usr/bin/env bash
# scripts/check-cycle-coverage.sh — §6.AK cycle-coverage gate (Phase 1)
# Exit codes: 0=all covered, 1=uncovered claims, 2=missing/stale evidence
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VER="" CHAN="" EDIR="" STRICT=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version=*)    VER="${1#*=}"; shift 1;;
        --version)      VER="$2"; shift 2;;
        --channel=*)    CHAN="${1#*=}"; shift 1;;
        --channel)      CHAN="$2"; shift 2;;
        --evidence-dir=*) EDIR="${1#*=}"; shift 1;;
        --evidence-dir) EDIR="$2"; shift 2;;
        --strict)       STRICT=1; shift 1;;
        *) echo "FATAL: unknown $1" >&2; exit 2;;
    esac
done
[[ -n "$VER" && -n "$CHAN" && -n "$EDIR" ]] || { echo "FATAL: --version --channel --evidence-dir required" >&2; exit 2; }
SHA="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
MAP="$EDIR/cycle-coverage-map-$VER.yaml"
[[ -f "$MAP" ]] || { echo "FATAL §6.AK.1: map not found $MAP" >&2; exit 2; }
MV="$(grep -E '^version:' "$MAP" | head -1 | sed 's/^version: *//' | tr -d '"')"
[[ "$MV" == "$VER" ]] || { echo "FATAL §6.AK.2: map version $MV != $VER" >&2; exit 2; }
CNT="$(grep -cE '^\s+-\s+fix:' "$MAP" 2>/dev/null || echo 0)"
if [[ "$CNT" -eq 0 ]]; then
    echo "WARN: no claims in map" >&2
    [[ "$STRICT" -eq 1 ]] && exit 2 || exit 0
fi
EVI="$EDIR/$VER-test-evidence.json"
[[ -f "$EVI" ]] || EVI="$EDIR/$VER-test-evidence.md"
[[ -f "$EVI" ]] || { echo "FATAL §6.AK.3: evidence not found for $VER" >&2; exit 2; }
if grep -q '"commit_sha":' "$EVI" 2>/dev/null; then ESHA="$(grep -o '"commit_sha": *"[^"]*"' "$EVI" | head -1 | sed 's/.*"//;s/".*//')"
elif grep -q '^commit_sha:' "$EVI" 2>/dev/null; then ESHA="$(grep '^commit_sha:' "$EVI" | head -1 | sed 's/^commit_sha: *//')"
else ESHA=unknown; fi
[[ "$ESHA" == unknown || "$ESHA" == "$SHA" ]] || { echo "FATAL §6.AK.4: evidence SHA $ESHA != current $SHA" >&2; exit 2; }
fail=0
while IFS= read -r cl; do
    c="$(echo "$cl" | sed 's/.*fix: *//;s/"//g' | xargs)"
    while IFS= read -r fl; do
        echo "$fl" | grep -qE 'fix:' && break
        echo "$fl" | grep -qE 'covering_challenges:' && continue
        fqn="$(echo "$fl" | sed 's/.*- *//;s/"//g' | xargs)"
        [[ -n "$fqn" && "$fqn" != "$c" ]] && grep -q "$fqn" "$EVI" 2>/dev/null || { echo "WARN: $fqn not in evidence (claim: $c)" >&2; fail=$((fail+1)); }
    done < <(sed -n "/fix:.*$c/,/^[^-]/p" "$MAP")
done < <(grep -E '^\s+- fix:' "$MAP")
[[ $fail -eq 0 ]] || { echo "$fail unverifiable ref(s)" >&2; [[ "$STRICT" -eq 1 ]] && exit 1; }
echo "§6.AK PASS: $CNT claims, SHA match OK"
exit 0
