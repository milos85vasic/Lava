#!/usr/bin/env bash
# scripts/check-cycle-coverage.sh — §6.AK cycle-coverage gate (Phase 1)
#
# Enforces the §6.AK coverage-intersection contract: every CHANGELOG-claimed
# user-visible fix in the cycle-coverage-map for <version> MUST have a covering
# device Challenge that was EXECUTED + PASSED on a non-host-direct runner, on
# the SAME commit, in fresh (<=24h) §6.Z evidence. It also subsumes the §6.Z-debt
# runtime checks (evidence presence + commit-SHA match + freshness).
#
# ── The gate PARSES VERDICTS. It does not match names. ───────────────────────
# History (LVA-149, 2026-08-26): this gate previously fell through to a bare
# fixed-string match on the Challenge NAME whenever the evidence was not in the
# one JSON shape it recognised. Production §6.Z evidence is markdown, so the
# gate's verdict was a function of how the evidence was FORMATTED, not of what
# it SAID — markdown evidence that explicitly recorded a Challenge as FAILED
# passed the gate (exit 0) while the identical verdicts as JSON failed (exit 1).
# The gate that exists to stop the 2026-06-26 "C00-only gate shipped broken
# flows" incident could not itself distinguish a passing release from a failing
# one. Reproduction, fix and falsifiability rehearsal: see the Bluff-Audit stamp
# on the landing commit and docs/scripts/check-cycle-coverage.sh.md.
#
# Design invariant, non-negotiable: an evidence format this parser does not
# recognise MUST REFUSE (exit 2), never pass. A gate that cannot determine a
# verdict has not established a PASS. Every refusal names the offending file.
#
# Recognised verdict-record shapes (see EVIDENCE FORMATS below for detail):
#   R1  challenge: fqn=<FQN> verdict=<V> runner=<R>     (structured/autonomous-QA)
#   R2  "<TestName>": "PASS|FAIL|SKIP|ERROR..."         (JSON verdict map)
#   R3  "test_class": "<FQN>" ... "test_passed": true   (JSON attestation rows)
#   R4  | <TestName> | ... | **PASS** | ... |           (markdown table row)
#   R5  <TestName>: PASS                                (colon verdict line)
#   R6  <TestName>: tests="N" failures="0" errors="0"   (JUnit summary line)
#   R7  "challenge": "<FQN>" ... "status": "PASS"        (autonomous-QA rows)
#
# Interface (firebase-distribute.sh Gate 7 + pre-push Check 10 pass
# --version/--evidence-dir[/--head]/--strict; the hermetic tests add
# --map/--head/--now-epoch and the env overrides below):
#   --version=<vName-vCode>   (required)  e.g. 1.3.12-1078
#   --evidence-dir=<dir>      (required)  §6.Z evidence + cycle-coverage-map dir
#   --map=<path>              (optional)  cycle-coverage-map; auto-resolved if absent
#   --head=<sha>              (optional)  commit the evidence must match; default git HEAD
#   --now-epoch=<epoch>       (optional)  "now" for the freshness check; default date +%s
#   --strict                  (optional)  accepted; the gate is fail-closed regardless
#
# NOTE (LVA-148, 2026-08-26): --channel was REMOVED. It was parsed, asserted
# non-empty, and then never read again — debug, release and an arbitrary value
# all produced byte-identical results, because BOTH artifacts this gate reads
# (the cycle-coverage-map and the §6.Z evidence file) resolve from
# --evidence-dir + --version alone. There is no per-variant evidence layout to
# select: a cycle has ONE <version>-test-evidence.{json,md}, and the §6.AA
# Stage-2 release authorization is appended INTO that same file. A parameter
# that appears to narrow a safety gate and narrows nothing is itself a small
# bluff, so it is gone rather than silently accepted-and-ignored: an invocation
# that still passes --channel now fails loudly (exit 2, unknown argument).
#
# Env overrides (used by the hermetic wiring tests; flags take precedence):
#   LAVA_CYCLE_COVERAGE_HEAD       -> --head
#   LAVA_CYCLE_COVERAGE_NOW_EPOCH  -> --now-epoch
#
# Exit codes:
#   0 = every claim covered by an executed+PASSED non-host-direct Challenge
#   1 = a claim lacks a covering executed+PASSED Challenge, OR evidence is stale
#   2 = REFUSAL: map/evidence missing, map-version mismatch, map declares no
#       claims, evidence commit-SHA absent/unknown/mismatched, or the evidence
#       format yields no parseable verdict record at all
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VER="" EDIR="" MAP="" HEAD_FLAG="" NOW_FLAG="" STRICT=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --version=*)      VER="${1#*=}"; shift 1;;
        --version)        VER="${2:-}"; shift 2;;
        --evidence-dir=*) EDIR="${1#*=}"; shift 1;;
        --evidence-dir)   EDIR="${2:-}"; shift 2;;
        --map=*)          MAP="${1#*=}"; shift 1;;
        --map)            MAP="${2:-}"; shift 2;;
        --head=*)         HEAD_FLAG="${1#*=}"; shift 1;;
        --head)           HEAD_FLAG="${2:-}"; shift 2;;
        --now-epoch=*)    NOW_FLAG="${1#*=}"; shift 1;;
        --now-epoch)      NOW_FLAG="${2:-}"; shift 2;;
        --strict)         STRICT=1; shift 1;;
        --channel|--channel=*)
            echo "FATAL: --channel was REMOVED (LVA-148). It selected nothing: this gate resolves" >&2
            echo "       both the cycle-coverage-map and the §6.Z evidence from --evidence-dir +" >&2
            echo "       --version alone, so debug/release/anything produced identical results." >&2
            echo "       Drop --channel from the invocation; pass the right --evidence-dir instead." >&2
            exit 2;;
        *) echo "FATAL: unknown $1" >&2; exit 2;;
    esac
done
[[ -n "$VER" && -n "$EDIR" ]] || { echo "FATAL: --version --evidence-dir required" >&2; exit 2; }

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

# ── Commit-SHA binding (§6.Z-debt subsumed; exit 2 unless it genuinely binds) ─
# LVA-149 (F2): this binding used to be INERT on the production markdown format.
# It recognised only three shapes, none of which markdown evidence uses, so it
# fell through to ESHA="unknown" and passed — the five most recent shipped
# cycles all passed this check against an ARBITRARY wrong HEAD. It now reads
# every shape the evidence actually uses, and "unknown"/absent is a REFUSAL,
# not a free pass: a gate that cannot bind evidence to a commit has not bound it.
declare -a ESHAS=()
collect_sha() {  # $1 = grep -oE pattern, $2 = sed strip pattern
    local raw
    while IFS= read -r raw; do
        raw="$(sed -E "s/$2//" <<<"$raw" | tr -d '"`*' | xargs 2>/dev/null || true)"
        [[ "$raw" =~ ^[0-9a-fA-F]{7,40}$ ]] && ESHAS+=("$(tr 'A-F' 'a-f' <<<"$raw")")
    done < <(grep -oiE "$1" "$EVI" 2>/dev/null || true)
}
collect_sha 'cycle-coverage:.*commit=[0-9a-fA-F]{7,40}'                '.*commit='
collect_sha '"commit_sha"[[:space:]]*:[[:space:]]*"[^"]*"'                 '.*:[[:space:]]*'
collect_sha '"tested_code_sha"[[:space:]]*:[[:space:]]*"[^"]*"'            '.*:[[:space:]]*'
collect_sha '"artifact_code_sha"[[:space:]]*:[[:space:]]*"[^"]*"'          '.*:[[:space:]]*'
collect_sha '^commit_sha:[[:space:]]*[^[:space:]]+'                        '^commit_sha:[[:space:]]*'
collect_sha '\*{0,2}commit sha:?\*{0,2}[[:space:]]*`?[0-9a-fA-F]{7,40}'    '.*[Ss][Hh][Aa]:?\**[[:space:]]*`?'
collect_sha '\*{0,2}tested code sha:?\*{0,2}[[:space:]]*`?[0-9a-fA-F]{7,40}' '.*[Ss][Hh][Aa]:?\**[[:space:]]*`?'

if [[ "${#ESHAS[@]}" -eq 0 ]]; then
    echo "FATAL §6.AK.4: §6.Z evidence '$EVI' declares NO usable commit SHA." >&2
    echo "       (An absent SHA — or the literal 'unknown' — is a REFUSAL, not a free pass: the" >&2
    echo "       gate cannot bind this evidence to the commit being shipped, so it cannot" >&2
    echo "       establish that the evidence covers THIS artifact. LVA-149.)" >&2
    echo "       Add one of: '**Commit SHA:** <sha>', '**Tested code SHA:** <sha>'," >&2
    echo "       '\"commit_sha\": \"<sha>\"', '\"tested_code_sha\": \"<sha>\"'," >&2
    echo "       '\"artifact_code_sha\": \"<sha>\"', or 'cycle-coverage: ... commit=<sha>'." >&2
    exit 2
fi
sha_bound=0
for s in "${ESHAS[@]}"; do
    if [[ "$HEAD" == "$s"* || "$s" == "$HEAD"* ]]; then sha_bound=1; break; fi
done
if [[ "$sha_bound" -ne 1 ]]; then
    echo "FATAL §6.AK.4: §6.Z evidence commit-SHA does not match the commit under test." >&2
    echo "       evidence '$EVI' declares: ${ESHAS[*]}" >&2
    echo "       current HEAD:             $HEAD" >&2
    exit 2
fi

# ── Freshness (<=24h; exit 1 on stale).
#
# §6.J anti-bluff floor (added 2026-08-26, LVA vacuous-pass sweep F11). This
# block used to be "only enforced when a timestamp is found": an ABSENT
# timestamp skipped the check entirely, and an UNPARSEABLE one skipped it again
# at the inner `[[ -n "$ETS_EPOCH" ]]`. Evidence with no recognisable timestamp
# was therefore NEVER stale, however old it actually was.
#
# The decisive control — identical evidence, identical map, identical HEAD, only
# the timestamp line differing, all three run with --now-epoch=99999999999:
#
#   timestamp=2021-01-01T00:00:00Z  -> FATAL §6.AK: evidence is stale     EXIT=1
#   (no timestamp line at all)      -> §6.AK PASS: 1 claim(s) verified    EXIT=0
#   timestamp=not-a-real-date       -> §6.AK PASS: 1 claim(s) verified    EXIT=0
#
# So the gate's staleness verdict was a function of whether a timestamp could be
# PARSED, not of how old the evidence was — and the two ways to defeat it were
# writing nothing and writing garbage, which are exactly the two states a
# hand-assembled evidence file falls into by accident.
#
# §6.Z clause 2 is unambiguous: "The evidence file's timestamp MUST be within 24
# hours of the distribute attempt; if older, refuse." An unreadable timestamp
# cannot establish that, so it is refused rather than waived. The extractor
# patterns below ARE the accepted-format manifest; the refusal names all four so
# the operator knows what shape to write rather than having to read this script.
ETS=""
ETS_PATTERNS_TRIED=(
  'cycle-coverage: timestamp=<ISO8601>'
  '"timestamp": "<ISO8601>"           (JSON evidence)'
  '"authored_utc": "<ISO8601>"        (JSON evidence)'
  '**Evidence authored:** <ISO8601>   (markdown evidence)'
)
extract_ts() {  # $1 = grep -oE pattern, $2 = sed strip pattern
    [[ -n "$ETS" ]] && return 0
    local raw
    raw="$(grep -oiE "$1" "$EVI" 2>/dev/null | head -1 || true)"
    [[ -n "$raw" ]] || return 0
    ETS="$(sed -E "s/$2//" <<<"$raw" | tr -d '"`*' | xargs 2>/dev/null || true)"
}
# The two JSON strip patterns are ANCHORED at the KEY (`^"<key>"<ws>:<ws>"`)
# rather than written as `.*:[[:space:]]*` (fixed 2026-08-26).
#
# WHY THIS MATTERS — an ISO-8601 instant CONTAINS colons, and `.*:` is greedy,
# so the old strip consumed the timestamp's own `HH:MM:` and kept whatever
# followed the LAST colon. Measured on the four forms this repo actually
# writes, with the old strip:
#
#   "timestamp": "2026-08-26T11:30:00Z"        -> '00Z' -> today 00:00:00Z
#   "timestamp": "2026-08-12T18:20:00+02:00"   -> '00'  -> today 00:00:00Z
#   "authored_utc": "2026-08-12T16:20:00Z"     -> '00Z' -> today 00:00:00Z
#   "timestamp": "2026-08-26T15:22:38Z"        -> '38Z' -> UNPARSEABLE
#
# The first three are the dangerous ones and they are FAIL-OPEN in the exact
# direction this floor exists to close: a genuinely 14-day-old JSON evidence
# file — .lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app/
# 0.2.12-25-test-evidence.json, which really does carry
# "timestamp": "2026-08-12T18:20:00+02:00" — was being read as TODAY AT
# MIDNIGHT, i.e. always inside the 24h window, on every day it was ever
# checked. The staleness verdict was a function of the clock, not of the
# evidence. The fourth is the mirror-image failure: a correctly-formatted,
# genuinely fresh timestamp is refused whenever its seconds field is not also
# a valid hour. So both documented JSON forms were broken, and neither the
# tests nor a real run could show it — a hardcoded `…T11:30:00Z` fixture
# mangles to '00Z', which parses as today-midnight and therefore always looks
# fresh. That accident is why this went unnoticed.
#
# The anchored strip removes exactly `"<key>"<ws>:<ws>"` from the FRONT and
# leaves the value intact, so all four forms above now extract in full and
# `date` reads the instant that is actually written in the file.
extract_ts 'cycle-coverage:.*timestamp=[^[:space:]]+'   '.*timestamp='
extract_ts '"timestamp"[[:space:]]*:[[:space:]]*"[^"]*"'    '^"[^"]*"[[:space:]]*:[[:space:]]*"'
extract_ts '"authored_utc"[[:space:]]*:[[:space:]]*"[^"]*"' '^"[^"]*"[[:space:]]*:[[:space:]]*"'
extract_ts '\*{0,2}evidence authored:?\*{0,2}[[:space:]]*[0-9][0-9T:+.Z-]+' '.*authored:?\**[[:space:]]*'
if [[ -z "$ETS" ]]; then
    echo "FATAL §6.AK/§6.Z: no timestamp found in the §6.Z evidence for $VER." >&2
    echo "  → Examined: $EVI" >&2
    echo "  → Expected: one of the four accepted timestamp forms —" >&2
    printf '        %s\n' "${ETS_PATTERNS_TRIED[@]}" >&2
    echo "  → Cause distinguished: this is NOT 'the evidence is fresh'. No timestamp" >&2
    echo "    was present at all, so its age is UNKNOWN — and an unknown age cannot" >&2
    echo "    satisfy §6.Z clause 2's <=24h requirement." >&2
    echo "  → Before this floor, evidence with no timestamp was never stale however old" >&2
    echo "    it was; the same file WITH a parseable 5-year-old timestamp was refused." >&2
    echo "  → Do: add a timestamp line in one of the forms above, recording when the" >&2
    echo "    device gate actually ran, and re-run." >&2
    exit 1
fi

ETS_EPOCH="$(date -u -d "$ETS" +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$ETS" +%s 2>/dev/null || echo "")"
if [[ -z "$ETS_EPOCH" ]]; then
    echo "FATAL §6.AK/§6.Z: the §6.Z evidence timestamp for $VER could not be parsed." >&2
    echo "  → Examined: $EVI" >&2
    echo "  → Extracted: '$ETS'" >&2
    echo "  → Expected: an ISO-8601 instant such as 2026-08-26T09:15:00Z" >&2
    echo "  → Cause distinguished: a timestamp IS present but 'date' cannot read it," >&2
    echo "    so the evidence's age is UNKNOWN. That is a malformed evidence file, not" >&2
    echo "    a fresh one — treating it as fresh is the bluff this floor closes." >&2
    echo "  → Do: rewrite that timestamp in ISO-8601 form and re-run." >&2
    exit 1
fi

AGE=$(( NOW - ETS_EPOCH ))
if (( AGE > 86400 )); then
    echo "FATAL §6.AK: §6.Z evidence is stale (age ${AGE}s > 24h) for $VER" >&2
    exit 1
fi

# ── EVIDENCE FORMATS — extract every verdict record the file actually states ──
# Emits one TAB-separated record per recognised statement:  <name>\t<verdict>\t<runner>
# ZERO records ⇒ the format is unrecognised ⇒ REFUSE (exit 2), naming the file.
RECORDS="$(mktemp)"
cleanup() { rm -f "$RECORDS"; }
trap cleanup EXIT

awk '
function isname(t) {
    return (t ~ /^[A-Za-z_][A-Za-z0-9_.]*$/ && (t ~ /Test$/ || t ~ /^Test[A-Z_]/ || t ~ /^Challenge[0-9]/))
}
function verdict_of(cell,   c, cu) {
    c = cell; gsub(/[*`]/, "", c); gsub(/^[ \t]+|[ \t]+$/, "", c)
    cu = toupper(c)
    if (cu ~ /^PASS([^A-Z0-9_]|$)/ || cu ~ /^PASSED([^A-Z0-9_]|$)/ || cu ~ /^OK([^A-Z0-9_]|$)/) return "PASS"
    if (cu ~ /^FAIL([^A-Z0-9_]|$)/ || cu ~ /^FAILED([^A-Z0-9_]|$)/ || cu ~ /^ERROR([^A-Z0-9_]|$)/) return "FAIL"
    if (cu ~ /^SKIP([^A-Z0-9_]|$)/ || cu ~ /^SKIPPED([^A-Z0-9_]|$)/ || cu ~ /^NOT RUN/) return "SKIP"
    return ""
}
function emit(n, v, r) { if (n != "" && v != "") printf "%s\t%s\t%s\n", n, v, (r == "" ? "-" : r) }
{
    line = $0

    # ── R1: structured "challenge: fqn=<FQN> verdict=<V> runner=<R>" ────────
    if (line ~ /fqn=/ && line ~ /verdict=/) {
        n = ""; v = ""; r = ""
        if (match(line, /fqn=[^ \t]+/))     n = substr(line, RSTART + 4, RLENGTH - 4)
        if (match(line, /verdict=[^ \t]+/)) v = toupper(substr(line, RSTART + 8, RLENGTH - 8))
        if (match(line, /runner=[^ \t]+/))  r = substr(line, RSTART + 7, RLENGTH - 7)
        if (v ~ /^SKIPPED/) v = "SKIP"
        if (v !~ /^(PASS|FAIL|SKIP|ERROR)/) v = "FAIL"   # unknown verdict word never passes
        emit(n, v, r); next
    }

    # ── R3: JSON attestation rows — "test_class" ... "test_passed": true ────
    if (line ~ /"test_class"[ \t]*:/) {
        if (match(line, /"test_class"[ \t]*:[ \t]*"[^"]*"/)) {
            s = substr(line, RSTART, RLENGTH)
            sub(/^"test_class"[ \t]*:[ \t]*"/, "", s); sub(/"$/, "", s)
            pending = s
        }
    }
    if (line ~ /"test_passed"[ \t]*:/ && pending != "") {
        emit(pending, (line ~ /"test_passed"[ \t]*:[ \t]*true/) ? "PASS" : "FAIL", "")
        pending = ""; next
    }

    # ── R7: autonomous-QA rows — "challenge": "<FQN>" … "status": "PASS" ───
    # Written by scripts/autonomous-qa/aggregate-evidence.sh. Keyed like R3:
    # the name and the verdict live under two different keys, so neither R2
    # (name-as-key) nor R3 (test_class/test_passed) reaches it.
    if (line ~ /"challenge"[ \t]*:/) {
        if (match(line, /"challenge"[ \t]*:[ \t]*"[^"]*"/)) {
            s2 = substr(line, RSTART, RLENGTH)
            sub(/^"challenge"[ \t]*:[ \t]*"/, "", s2); sub(/"$/, "", s2)
            pending2 = s2
        }
    }
    if (line ~ /"status"[ \t]*:/ && pending2 != "") {
        if (match(line, /"status"[ \t]*:[ \t]*"[^"]*"/)) {
            v2 = substr(line, RSTART, RLENGTH)
            sub(/^"status"[ \t]*:[ \t]*"/, "", v2); sub(/"$/, "", v2)
            vv = verdict_of(v2)
            # A status the parser cannot classify is NOT a pass.
            emit(pending2, (vv == "" ? "FAIL" : vv), "")
            pending2 = ""; next
        }
    }

    # Close out any half-captured keyed pair at the end of its JSON object. This
    # MUST come after R3 and R7, not before: a single-line row carries the name,
    # the verdict and the closing brace together, and resetting first would
    # discard the name before its verdict is read.
    if (line ~ /\}/) { pending = ""; pending2 = "" }

    # ── R6: JUnit summary — <Name>: tests="N" failures="F" errors="E" ───────
    if (line ~ /tests="[0-9]+"/ && line ~ /failures="[0-9]+"/ && line ~ /errors="[0-9]+"/) {
        if (match(line, /^[ \t]*[A-Za-z_][A-Za-z0-9_.]*[ \t]*:/)) {
            nm = substr(line, RSTART, RLENGTH); gsub(/[ \t:]/, "", nm)
            if (isname(nm)) {
                t = 0; f = 0; e = 0
                if (match(line, /tests="[0-9]+"/))     t = substr(line, RSTART + 7,  RLENGTH - 8) + 0
                if (match(line, /failures="[0-9]+"/))  f = substr(line, RSTART + 10, RLENGTH - 11) + 0
                if (match(line, /errors="[0-9]+"/))    e = substr(line, RSTART + 8,  RLENGTH - 9) + 0
                emit(nm, (t > 0 && f == 0 && e == 0) ? "PASS" : "FAIL", "")
                next
            }
        }
    }

    # ── R5: colon verdict line — <Name>: PASS ──────────────────────────────
    if (match(line, /^[ \t]*[A-Za-z_][A-Za-z0-9_.]*[ \t]*:/)) {
        nm = substr(line, RSTART, RLENGTH); gsub(/[ \t:]/, "", nm)
        if (isname(nm)) {
            vv = verdict_of(substr(line, RSTART + RLENGTH))
            if (vv != "") { emit(nm, vv, ""); next }
        }
    }

    # ── R2: JSON verdict map — "<TestName>": "PASS|FAIL|SKIP|ERROR..." ─────
    hit = 0; tmp = line
    while (match(tmp, /"[A-Za-z_][A-Za-z0-9_.]*"[ \t]*:[ \t]*"[^"]*"/)) {
        seg = substr(tmp, RSTART, RLENGTH); tmp = substr(tmp, RSTART + RLENGTH)
        k = seg; sub(/^"/, "", k); sub(/".*$/, "", k)
        v = seg; sub(/^[^:]*:[ \t]*"/, "", v); sub(/"$/, "", v)
        if (isname(k)) { vv = verdict_of(v); if (vv != "") { emit(k, vv, ""); hit = 1 } }
    }
    if (hit) next

    # ── R4: markdown table row — | <Name> | ... | **PASS** | ... | ─────────
    if (line ~ /\|/) {
        n = split(line, cells, /\|/)
        if (n >= 3) {
            best = ""; nm = ""
            for (i = 1; i <= n; i++) {
                vv = verdict_of(cells[i])
                if (vv == "FAIL") best = "FAIL"
                else if (vv == "SKIP" && best != "FAIL") best = "SKIP"
                else if (vv == "PASS" && best == "") best = "PASS"
            }
            for (i = 1; i <= n && nm == ""; i++) {
                if (verdict_of(cells[i]) != "") continue
                c = cells[i]; gsub(/[^A-Za-z0-9_.]/, " ", c)
                m = split(c, toks, / +/)
                for (j = 1; j <= m; j++) if (isname(toks[j])) { nm = toks[j]; break }
            }
            if (nm != "" && best != "") { emit(nm, best, ""); next }
        }
    }
}
' "$EVI" > "$RECORDS"

REC_COUNT="$(wc -l < "$RECORDS" | tr -d ' ')"
if [[ "$REC_COUNT" -eq 0 ]]; then
    echo "FATAL §6.AK.5: cannot determine ANY test verdict from §6.Z evidence file:" >&2
    echo "         $EVI" >&2
    echo "       Its format is not recognised, so the gate CANNOT establish that any Challenge" >&2
    echo "       executed and passed. An unrecognised format REFUSES — it never passes (LVA-149:" >&2
    echo "       the gate previously fell through to matching the Challenge NAME anywhere in the" >&2
    echo "       file, so evidence explicitly recording a FAILURE passed the gate)." >&2
    echo "       Record each covering Challenge's verdict in one of these shapes:" >&2
    echo "         challenge: fqn=<FQN> verdict=PASS runner=containers-submodule" >&2
    echo "         \"<TestName>\": \"PASS\"" >&2
    echo "         \"test_class\": \"<FQN>\", ... \"test_passed\": true" >&2
    echo "         | <TestName> | **PASS** | ... |        (markdown table row)" >&2
    echo "         <TestName>: PASS" >&2
    echo "         <TestName>: tests=\"7\" failures=\"0\" errors=\"0\"" >&2
    echo "         \"challenge\": \"<FQN>\", ... \"status\": \"PASS\"" >&2
    exit 2
fi

# Document-level runner declaration (§6.AH / §6.AG): host-direct never gates.
# Deliberately reads only DECLARED runner fields, never prose — 1.3.16-1083's
# evidence discusses "host-direct" inside a sentence whose declared runner is
# containers-submodule, and a prose match there would be a false positive.
DOC_HOST_DIRECT=0
if grep -qiE '("runner"[[:space:]]*:[[:space:]]*"host-direct"|^[[:space:]]*\*{0,2}runner:?\*{0,2}[[:space:]]*`?host-direct)' "$EVI" 2>/dev/null; then
    DOC_HOST_DIRECT=1
fi

# challenge_verdict <covering-name> → echoes "<VERDICT>|<runner>" for the best
# matching record, or "ABSENT|-" when the evidence states no verdict for it.
# Matching is on the full name OR the leaf (last dot-segment), in both
# directions, so a map may name an FQN while the evidence names the leaf.
challenge_verdict() {
    local name="$1" leaf="${1##*.}"
    awk -F'\t' -v leaf="$leaf" '
        # norm(): last dot-segment, with a single trailing "Test" stripped.
        # This is IDENTIFIER equality after normalising the two conventions in
        # use (a map may name "Challenge58Foo" while the evidence names the test
        # class "lava.app.challenges.Challenge58FooTest"). It is deliberately NOT
        # a substring match: substring matching is how the pre-LVA-149 gate let a
        # bare NAME anywhere in the file stand in for a verdict.
        function norm(x) { sub(/^.*\./, "", x); sub(/Test$/, "", x); return x }
        BEGIN { want = norm(leaf) }
        {
            if (norm($1) == want) {
                if ($2 == "PASS" && $3 !~ /host-direct/) { print "PASS|" $3; found = 1; exit }
                if (best == "") { best = $2 "|" $3 }
                else if ($2 == "FAIL") { best = $2 "|" $3 }
            }
        }
        END { if (!found) print (best == "" ? "ABSENT|-" : best) }' "$RECORDS"
}

# ── Parse claims (both map shapes) → "<idx>\t<space-separated covering names>" ─
fail=0            # distinct claims that are not fully covered
refs=0            # individual covering-Challenge refs that are missing/non-PASS
refs_total=0      # every covering-Challenge ref examined
claims=0
while IFS=$'\t' read -r idx names; do
    claims=$(( claims + 1 ))
    if [[ -z "${names// /}" ]]; then
        echo "WARN §6.AK: claim #$idx has NO covering Challenge (unverified)" >&2
        fail=$(( fail + 1 )); refs=$(( refs + 1 )); refs_total=$(( refs_total + 1 ))
        continue
    fi
    claim_bad=0
    for n in $names; do
        refs_total=$(( refs_total + 1 ))
        res="$(challenge_verdict "$n")"
        v="${res%%|*}"; r="${res#*|}"
        # A PASS is only a covering PASS when it is BOTH a pass AND not on a
        # host-direct runner (§6.AH / §6.AG: host-direct never gates).
        if [[ "$v" == "PASS" && "$r" != *host-direct* && "$DOC_HOST_DIRECT" -ne 1 ]]; then
            continue
        fi
        if [[ "$v" == "PASS" ]]; then
            echo "WARN §6.AK: covering Challenge '$n' (claim #$idx) is recorded PASS in $EVI but on a host-direct runner (runner=$r) — host-direct is forbidden as a gate runner per §6.AH/§6.AG, so this is not a covering result" >&2
        elif [[ "$v" == "ABSENT" ]]; then
            echo "WARN §6.AK: covering Challenge '$n' (claim #$idx) has NO verdict record in $EVI — the evidence states no executed result for it (a bare mention in prose is not a verdict)" >&2
        else
            echo "WARN §6.AK: covering Challenge '$n' (claim #$idx) is recorded as '$v' (runner=$r) in $EVI — not an executed+PASSED non-host-direct result" >&2
        fi
        refs=$(( refs + 1 )); claim_bad=1
    done
    [[ "$claim_bad" -eq 1 ]] && fail=$(( fail + 1 ))
done < <(awk '
    { line = $0; t = line; sub(/^[ \t]+/, "", t) }
    t ~ /^#/                                   { next }                            # comment
    line ~ /^[ \t]*-[ \t]+(fix|bullet):/       { claim++; names[claim]=""; next }  # claim start
    claim == 0                                 { next }                            # preamble (version:/claims:)
    line ~ /covering_challenges?:[ \t]*\[/ {                                        # inline-flow list
        # covering_challenges: ["a.b.C", "a.b.D"] — the shape
        # scripts/autonomous-qa/aggregate-evidence.sh generates. Unparsed before
        # LVA-149, which meant EVERY autonomous-QA cycle reported "claim has NO
        # covering Challenge" no matter what it had actually executed.
        v = line; sub(/.*covering_challenges?:[ \t]*\[/, "", v); sub(/\].*$/, "", v)
        nf = split(v, arr, /,/)
        for (k = 1; k <= nf; k++) {
            t2 = arr[k]; gsub(/"/, "", t2); gsub(/^[ \t]+|[ \t]+$/, "", t2)
            if (t2 != "") names[claim] = names[claim] (names[claim]=="" ? "" : " ") t2
        }
        next
    }
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

# A map that declares no claims cannot establish coverage — it is the vacuous
# pass this gate exists to prevent, so it REFUSES rather than exiting 0.
if [[ "$claims" -eq 0 ]]; then
    echo "FATAL §6.AK.6: cycle-coverage-map '$MAP' declares ZERO claims." >&2
    echo "       A map with nothing to verify cannot establish coverage; exiting 0 here would be a" >&2
    echo "       vacuous pass. List this cycle's CHANGELOG user-visible bullets as claims, each with" >&2
    echo "       its covering Challenge — or, for a cycle with genuinely no user-visible change, say" >&2
    echo "       so explicitly as a claim with its re-verification Challenge (§6.AK clause 6)." >&2
    exit 2
fi
if [[ "$fail" -gt 0 ]]; then
    echo "FATAL §6.AK: $fail of $claims claim(s) lack a covering executed+PASSED device Challenge for $VER ($refs of $refs_total covering ref(s) uncovered; $REC_COUNT verdict record(s) parsed from $EVI)" >&2
    exit 1
fi
echo "§6.AK PASS: $claims claim(s) / $refs_total covering ref(s) verified against $REC_COUNT parsed verdict record(s) in $EVI (SHA $HEAD)"
exit 0
