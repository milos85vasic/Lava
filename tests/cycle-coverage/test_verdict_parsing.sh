#!/usr/bin/env bash
# test_verdict_parsing.sh — hermetic regression test for the §6.AK gate's
# EVIDENCE-VERDICT PARSER (LVA-149).
#
# THE DEFECT THIS SUITE EXISTS TO KEEP CLOSED
# -------------------------------------------
# scripts/check-cycle-coverage.sh used to recognise exactly one JSON verdict
# shape ("Name": "PASS"). Production §6.Z evidence is MARKDOWN, which matched
# neither of its two strict checks, so it fell through to a bare fixed-string
# match on the Challenge NAME and returned "covered and PASSED". Markdown
# evidence that explicitly recorded a Challenge as FAILED therefore PASSED the
# gate. The gate's verdict was a function of how the evidence was FORMATTED,
# not of what it SAID:
#
#   markdown (the production format):  §6.AK PASS: 1 claim(s) covered   EXIT=0
#   the identical verdicts as JSON:    FATAL §6.AK: 1 of 1 claim(s)...  EXIT=1
#
# A gate built to stop the 2026-06-26 "C00-only gate shipped broken flows"
# incident (§6.AK) that cannot itself tell a passing release from a failing one
# is the §6.J bluff class at its most consequential. Two paired cases below
# (md_vs_json_FAIL_agree / md_vs_json_PASS_agree) assert the two formats reach
# the SAME verdict; they are the load-bearing regression.
#
# Sibling defect F2: the commit-SHA binding was INERT on markdown (it recognised
# only three shapes, none of them markdown's), so it fell through to
# ESHA="unknown" and passed. The five most recent shipped cycles all passed the
# gate against an ARBITRARY wrong HEAD. Cases sha_* assert the binding binds and
# that an absent / "unknown" SHA now REFUSES rather than passing.
#
# Design invariant asserted here: an evidence format the parser does not
# recognise REFUSES (exit 2) and NAMES THE FILE. "Cannot determine a verdict"
# is never a PASS.
#
# NO device, NO gradle, NO git mutation, NO network — every input is a temp file
# and HEAD/now are injected via --head/--now-epoch.
#
# Exit 0 = every case produced its expected exit code AND at least one case ran.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

GATE="$ROOT/scripts/check-cycle-coverage.sh"
VERSION="9.9.9-9999"
HEAD="0123456789abcdef0123456789abcdef01234567"
WRONG="ffffffffffffffffffffffffffffffffffffffff"
NOW_EPOCH="$(date -u -d '2026-08-26T12:00:00Z' +%s 2>/dev/null || date -u -j -f '%Y-%m-%dT%H:%M:%SZ' '2026-08-26T12:00:00Z' +%s)"
FRESH_TS="2026-08-26T11:30:00Z"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass_count=0
fail_count=0
cases_run=0

# run_case <label> <expected-exit> <evidence-dir> <map> [head] [stderr-needle]
run_case() {
  local label="$1" want="$2" edir="$3" map="$4" head="${5:-$HEAD}" needle="${6:-}"
  local out got ok=1
  cases_run=$(( cases_run + 1 ))
  set +e
  out="$(bash "$GATE" --version="$VERSION" --evidence-dir="$edir" --map="$map" \
         --head="$head" --now-epoch="$NOW_EPOCH" 2>&1)"
  got=$?
  set -e
  [[ "$got" == "$want" ]] || ok=0
  if [[ -n "$needle" ]] && ! grep -qF -- "$needle" <<<"$out"; then ok=0; fi
  if [[ "$ok" == 1 ]]; then
    echo "  PASS  [$label] exit=$got (expected $want)${needle:+ + needle present}"
    pass_count=$(( pass_count + 1 ))
  else
    echo "  FAIL  [$label] exit=$got (expected $want)${needle:+ + needle '$needle'}" >&2
    echo "        ---- gate output ----" >&2
    sed 's/^/        /' <<<"$out" >&2
    fail_count=$(( fail_count + 1 ))
  fi
}

# ── fixture builders ───────────────────────────────────────────────────────
# mk_md <dirname> <sha-line> <body...>  (body read from stdin)
mk_md() {
  local d="$WORK/$1"; shift
  local shaline="$1"; shift
  mkdir -p "$d"
  {
    echo "# §6.Z Pre-Distribute Test-Execution Evidence — Synthetic $VERSION"
    echo
    [[ -n "$shaline" ]] && echo "$shaline"
    echo "**Evidence authored:** $FRESH_TS"
    echo
    cat
  } > "$d/${VERSION}-test-evidence.md"
  echo "$d"
}
mk_json() {
  local d="$WORK/$1"; shift
  mkdir -p "$d"; cat > "$d/${VERSION}-test-evidence.json"
  echo "$d"
}

MAP="$WORK/map.yaml"
cat > "$MAP" <<EOF
version: "${VERSION}"
claims:
  - fix: "Search now returns real results"
    covering_challenges:
      - "Challenge58SearchReturnsResultsTest"
EOF

MAP_GO="$WORK/map-go.yaml"
cat > "$MAP_GO" <<EOF
version: "${VERSION}"
claims:
  - fix: "RuTracker Cloudflare-challenge classification"
    covering_challenges:
      - "TestLogin_CloudflareChallenge_ErrCloudflareChallenge"
EOF

MAP_JVM="$WORK/map-jvm.yaml"
cat > "$MAP_JVM" <<EOF
version: "${VERSION}"
claims:
  - fix: "Search chip labels resolve after the cold-start registry catches up"
    covering_challenges:
      - "SearchResultViewModelStreamingTest"
EOF

MAP_EMPTY="$WORK/map-empty.yaml"
cat > "$MAP_EMPTY" <<EOF
version: "${VERSION}"
claims:
EOF

SHA_MD="**Commit SHA:** ${HEAD}"

echo "test: §6.AK evidence-verdict PARSER + commit-SHA binding (LVA-149)"

# ═══ 1. THE REGRESSION: markdown verdicts must be READ, not assumed ═════════
# Production shape (1.3.17-1084 / 1.3.17-1085): | Name | **PASS** | ... |
D_MD_FAIL="$(mk_md md-fail "$SHA_MD" <<'EOF'
## Device gate

| Test | Result | Boot | Test duration | Timestamp |
|---|---|---|---|---|
| Challenge58SearchReturnsResultsTest | **FAIL** | 94.6s | 421.4s | 2026-08-26T11:31:03+02:00 |
EOF
)"
run_case "md_table_FAIL_refuses"  1 "$D_MD_FAIL" "$MAP" "$HEAD" "recorded as 'FAIL'"

D_MD_PASS="$(mk_md md-pass "$SHA_MD" <<'EOF'
## Device gate

| Test | Result | Boot | Test duration | Timestamp |
|---|---|---|---|---|
| Challenge58SearchReturnsResultsTest | **PASS** | 94.6s | 421.4s | 2026-08-26T11:31:03+02:00 |
EOF
)"
run_case "md_table_PASS_accepted" 0 "$D_MD_PASS" "$MAP"

# Production shape (1.3.15-1082): verdict in the LAST column, timings before it.
D_MD_LAST="$(mk_md md-lastcol "$SHA_MD" <<'EOF'
| Test | boot_seconds | test_seconds | Result |
|---|---:|---:|---|
| Challenge58SearchReturnsResultsTest | 36.90 | 73.40 | **PASS** (retry — see note) |
EOF
)"
run_case "md_table_verdict_last_column" 0 "$D_MD_LAST" "$MAP"

# Production shape (0.2.13-26): a "not run" cell is NOT a pass.
D_MD_NOTRUN="$(mk_md md-notrun "$SHA_MD" <<'EOF'
| Test | Result | boot_s | test_s | Evidence dir |
|---|---|---:|---:|---|
| Challenge58SearchReturnsResultsTest | not run — pre-existing, documented gap | — | — | see below |
EOF
)"
run_case "md_table_not_run_refuses" 1 "$D_MD_NOTRUN" "$MAP" "$HEAD" "recorded as 'SKIP'"

# ═══ 2. THE EXACT OLD FALLTHROUGH: a NAME with no verdict is not a verdict ══
D_MD_PROSE="$(mk_md md-prose "$SHA_MD" <<'EOF'
## Scope

Challenge58SearchReturnsResultsTest was written and compiled this cycle. It is
mentioned here by name, in prose, with no executed result recorded anywhere —
which is precisely the shape the pre-LVA-149 gate scored as "covered and PASSED".

| Test | Result |
|---|---|
| Challenge00CrashSurvivalTest | **PASS** |
EOF
)"
run_case "md_name_in_prose_only_refuses" 1 "$D_MD_PROSE" "$MAP" "$HEAD" "has NO verdict record"

# ═══ 3. Other production markdown conventions ══════════════════════════════
# Colon-verdict lines (Go tests, 1.3.16-1083 / 0.2.13-26).
D_GO_PASS="$(mk_md md-go-pass "$SHA_MD" <<'EOF'
```
TestLogin_CloudflareChallenge_ErrCloudflareChallenge: PASS
```
EOF
)"
run_case "md_colon_verdict_PASS" 0 "$D_GO_PASS" "$MAP_GO"

D_GO_FAIL="$(mk_md md-go-fail "$SHA_MD" <<'EOF'
```
TestLogin_CloudflareChallenge_ErrCloudflareChallenge: FAIL
```
EOF
)"
run_case "md_colon_verdict_FAIL_refuses" 1 "$D_GO_FAIL" "$MAP_GO" "$HEAD" "recorded as 'FAIL'"

# JUnit summary lines (1.3.16-1083 / 1.3.17-1084).
D_JVM_PASS="$(mk_md md-jvm-pass "$SHA_MD" <<'EOF'
```
SearchResultViewModelStreamingTest: tests="7" failures="0" errors="0" (2026-08-26T11:45:12)
```
EOF
)"
run_case "md_junit_summary_PASS" 0 "$D_JVM_PASS" "$MAP_JVM"

D_JVM_FAIL="$(mk_md md-jvm-fail "$SHA_MD" <<'EOF'
```
SearchResultViewModelStreamingTest: tests="7" failures="2" errors="0" (2026-08-26T11:45:12)
```
EOF
)"
run_case "md_junit_summary_failures_refuses" 1 "$D_JVM_FAIL" "$MAP_JVM" "$HEAD" "recorded as 'FAIL'"

# ═══ 4. JSON in BOTH directions — proving the working path is not broken ════
D_JS_PASS="$(mk_json json-pass <<EOF
{ "commit_sha": "$HEAD", "timestamp": "$FRESH_TS",
  "covering_challenges": { "Challenge58SearchReturnsResultsTest": "PASS" } }
EOF
)"
run_case "json_verdict_map_PASS" 0 "$D_JS_PASS" "$MAP"

D_JS_FAIL="$(mk_json json-fail <<EOF
{ "commit_sha": "$HEAD", "timestamp": "$FRESH_TS",
  "covering_challenges": { "Challenge58SearchReturnsResultsTest": "FAIL (documented limitation)" } }
EOF
)"
run_case "json_verdict_map_FAIL_refuses" 1 "$D_JS_FAIL" "$MAP" "$HEAD" "recorded as 'FAIL'"

# JSON attestation rows (1.3.15-1082 / 0.2.12-25): "test_class" + "test_passed".
D_JS_ROW_OK="$(mk_json json-row-pass <<EOF
{ "commit_sha": "$HEAD", "timestamp": "$FRESH_TS", "device_gate": { "rows": [
  {"test_class": "lava.app.challenges.Challenge58SearchReturnsResultsTest", "test_passed": true, "concurrent": 1}
] } }
EOF
)"
run_case "json_rows_test_passed_true" 0 "$D_JS_ROW_OK" "$MAP"

D_JS_ROW_BAD="$(mk_json json-row-fail <<EOF
{ "commit_sha": "$HEAD", "timestamp": "$FRESH_TS", "device_gate": { "rows": [
  {"test_class": "lava.app.challenges.Challenge58SearchReturnsResultsTest", "test_passed": false, "test_error": "AssertionError"}
] } }
EOF
)"
run_case "json_rows_test_passed_false_refuses" 1 "$D_JS_ROW_BAD" "$MAP" "$HEAD" "recorded as 'FAIL'"

# ═══ 5. FORMAT-EQUIVALENCE — the load-bearing pair ═════════════════════════
# Identical verdicts expressed in the two formats MUST produce the same exit
# code. Before LVA-149 these two lines disagreed: markdown 0, JSON 1.
mdfail_rc=0; bash "$GATE" --version="$VERSION" --evidence-dir="$D_MD_FAIL" --map="$MAP" \
  --head="$HEAD" --now-epoch="$NOW_EPOCH" >/dev/null 2>&1 || mdfail_rc=$?
jsfail_rc=0; bash "$GATE" --version="$VERSION" --evidence-dir="$D_JS_FAIL" --map="$MAP" \
  --head="$HEAD" --now-epoch="$NOW_EPOCH" >/dev/null 2>&1 || jsfail_rc=$?
cases_run=$(( cases_run + 1 ))
if [[ "$mdfail_rc" == "$jsfail_rc" && "$mdfail_rc" == 1 ]]; then
  echo "  PASS  [md_vs_json_FAIL_agree] markdown=$mdfail_rc json=$jsfail_rc (both refuse)"
  pass_count=$(( pass_count + 1 ))
else
  echo "  FAIL  [md_vs_json_FAIL_agree] markdown=$mdfail_rc json=$jsfail_rc — the gate's verdict" >&2
  echo "        depends on the evidence FORMAT, not on what the evidence SAYS (LVA-149)." >&2
  fail_count=$(( fail_count + 1 ))
fi

mdpass_rc=0; bash "$GATE" --version="$VERSION" --evidence-dir="$D_MD_PASS" --map="$MAP" \
  --head="$HEAD" --now-epoch="$NOW_EPOCH" >/dev/null 2>&1 || mdpass_rc=$?
jspass_rc=0; bash "$GATE" --version="$VERSION" --evidence-dir="$D_JS_PASS" --map="$MAP" \
  --head="$HEAD" --now-epoch="$NOW_EPOCH" >/dev/null 2>&1 || jspass_rc=$?
cases_run=$(( cases_run + 1 ))
if [[ "$mdpass_rc" == "$jspass_rc" && "$mdpass_rc" == 0 ]]; then
  echo "  PASS  [md_vs_json_PASS_agree] markdown=$mdpass_rc json=$jspass_rc (both accept)"
  pass_count=$(( pass_count + 1 ))
else
  echo "  FAIL  [md_vs_json_PASS_agree] markdown=$mdpass_rc json=$jspass_rc" >&2
  fail_count=$(( fail_count + 1 ))
fi

# ═══ 6. UNRECOGNISED FORMAT MUST REFUSE, AND MUST NAME THE FILE ════════════
D_GARBAGE="$(mk_md md-garbage "$SHA_MD" <<'EOF'
Everything went fine this cycle. All the tests were run and they were all
good. Challenge58SearchReturnsResultsTest is covered. Ship it.
EOF
)"
run_case "unrecognised_format_refuses" 2 "$D_GARBAGE" "$MAP" "$HEAD" "9.9.9-9999-test-evidence.md"

# Same, but the file DOES contain one parseable verdict (for an unrelated test),
# so the format is recognised and the failure is per-claim rather than a refusal.
D_GARBAGE_WITH_A_VERDICT="$(mk_md md-otherverdict "$SHA_MD" <<'EOF'
| Test | Result |
|---|---|
| Challenge00CrashSurvivalTest | **PASS** |
EOF
)"
run_case "unrecognised_format_names_shapes" 2 "$D_GARBAGE" "$MAP" "$HEAD" "verdict=PASS runner=containers-submodule"

# ═══ 7. F2 — the commit-SHA binding must actually BIND ═════════════════════
run_case "sha_mismatch_refuses" 2 "$D_MD_PASS" "$MAP" "$WRONG" "does not match the commit under test"

D_MD_NOSHA="$(mk_md md-nosha "" <<'EOF'
| Test | Result |
|---|---|
| Challenge58SearchReturnsResultsTest | **PASS** |
EOF
)"
run_case "sha_absent_refuses"  2 "$D_MD_NOSHA" "$MAP" "$HEAD" "declares NO usable commit SHA"

D_JS_UNKNOWN="$(mk_json json-sha-unknown <<EOF
{ "commit_sha": "unknown", "timestamp": "$FRESH_TS",
  "covering_challenges": { "Challenge58SearchReturnsResultsTest": "PASS" } }
EOF
)"
run_case "sha_literal_unknown_refuses" 2 "$D_JS_UNKNOWN" "$MAP" "$HEAD" "declares NO usable commit SHA"

# A SHA declared only in markdown prose still binds (F2's real-world shape).
D_MD_SHAONLY="$(mk_md md-shaonly "**Commit SHA:** ${WRONG}" <<'EOF'
| Test | Result |
|---|---|
| Challenge58SearchReturnsResultsTest | **PASS** |
EOF
)"
run_case "md_prose_sha_is_read_and_enforced" 2 "$D_MD_SHAONLY" "$MAP" "$HEAD" "declares: $WRONG"

# ═══ 8. A map with zero claims is a vacuous pass — it must refuse ══════════
run_case "zero_claim_map_refuses" 2 "$D_MD_PASS" "$MAP_EMPTY" "$HEAD" "declares ZERO claims"

# ═══ 9. LVA-148 — the inert --channel parameter is gone and fails loudly ═══
cases_run=$(( cases_run + 1 ))
set +e
chan_out="$(bash "$GATE" --version="$VERSION" --channel=debug --evidence-dir="$D_MD_PASS" \
            --map="$MAP" --head="$HEAD" --now-epoch="$NOW_EPOCH" 2>&1)"
chan_rc=$?
set -e
if [[ "$chan_rc" == 2 ]] && grep -qF -- "--channel was REMOVED" <<<"$chan_out"; then
  echo "  PASS  [channel_flag_rejected_loudly] exit=2 + explains the removal"
  pass_count=$(( pass_count + 1 ))
else
  echo "  FAIL  [channel_flag_rejected_loudly] exit=$chan_rc — an inert parameter that is" >&2
  echo "        silently accepted-and-ignored is a bluff; it must fail loudly (LVA-148)." >&2
  sed 's/^/        /' <<<"$chan_out" >&2
  fail_count=$(( fail_count + 1 ))
fi

# ═══ 10. autonomous-QA artifacts (aggregate-evidence.sh) ═══════════════════
# Two shapes only that generator emits, both previously unreachable:
#   evidence: {"challenge": "<FQN>", "status": "PASS"}   (keyed pair, R7)
#   map:      covering_challenges: ["<FQN>"]             (inline-flow list)
# The map's inline-flow list was NEVER parsed (pre-LVA-149 too), so every
# autonomous-QA cycle reported "claim has NO covering Challenge" regardless of
# what it had actually executed. Both directions asserted here.
MAP_AQA="$WORK/map-aqa.yaml"
cat > "$MAP_AQA" <<EOF
# §6.AK cycle-coverage-map for autonomous-QA cycle ${VERSION}
version: "${VERSION}"
fixes:
  - fix: "archiveorg — search returns results, opens details, obtains download"
    covering_challenges: ["lava.app.challenges.Challenge58SearchReturnsResultsTest"]
EOF

D_AQA_PASS="$(mk_json aqa-pass <<EOF
{
  "version": "$VERSION", "channel": "debug",
  "commit_sha": "$HEAD", "timestamp": "$FRESH_TS",
  "test_results": [
    { "challenge": "lava.app.challenges.Challenge58SearchReturnsResultsTest",
      "status": "PASS", "device": "CZ_API34_Phone", "duration_seconds": 512 }
  ]
}
EOF
)"
run_case "aqa_keyed_status_PASS" 0 "$D_AQA_PASS" "$MAP_AQA"

D_AQA_FAIL="$(mk_json aqa-fail <<EOF
{
  "version": "$VERSION", "channel": "debug",
  "commit_sha": "$HEAD", "timestamp": "$FRESH_TS",
  "test_results": [
    { "challenge": "lava.app.challenges.Challenge58SearchReturnsResultsTest",
      "status": "FAIL", "device": "CZ_API34_Phone", "duration_seconds": 512 }
  ]
}
EOF
)"
run_case "aqa_keyed_status_FAIL_refuses" 1 "$D_AQA_FAIL" "$MAP_AQA" "$HEAD" "recorded as 'FAIL'"

# An unclassifiable status must NOT become a pass.
D_AQA_WEIRD="$(mk_json aqa-weird <<EOF
{
  "version": "$VERSION", "commit_sha": "$HEAD", "timestamp": "$FRESH_TS",
  "test_results": [
    { "challenge": "lava.app.challenges.Challenge58SearchReturnsResultsTest",
      "status": "inconclusive", "device": "CZ_API34_Phone" }
  ]
}
EOF
)"
run_case "aqa_unclassifiable_status_refuses" 1 "$D_AQA_WEIRD" "$MAP_AQA" "$HEAD" "recorded as 'FAIL'"

# The inline-flow map must actually yield a NAME (not an empty claim). Proven by
# pointing the same map at evidence that covers nothing: the failure must be
# "not executed+PASSED", NOT "claim has NO covering Challenge".
run_case "aqa_inline_flow_map_yields_a_name" 1 "$D_GARBAGE_WITH_A_VERDICT" "$MAP_AQA" "$HEAD" "has NO verdict record"

# ═══ 9. TIMESTAMP EXTRACTION — the strip must not eat the timestamp ════════
#
# Added 2026-08-26. The two JSON timestamp forms were stripped with
# `s/.*:[[:space:]]*//`, and `.*:` is GREEDY, so it consumed the instant's own
# `HH:MM:` and kept whatever followed the LAST colon. Measured:
#
#   "timestamp": "2026-08-12T18:20:00+02:00"  -> '00'  -> parsed as TODAY 00:00
#   "timestamp": "2026-08-26T11:30:00Z"       -> '00Z' -> parsed as TODAY 00:00
#   "timestamp": "2026-08-26T15:22:38Z"       -> '38Z' -> UNPARSEABLE
#
# The first two are FAIL-OPEN in the direction the freshness floor exists to
# close: a genuinely 14-day-old JSON evidence file (the real
# 0.2.12-25-test-evidence.json carries exactly the first form) read as today at
# midnight, i.e. always inside the 24h window. The third is the mirror failure —
# a correct, fresh timestamp refused because its seconds field is not a valid
# hour.
#
# NOTE ON WHY THIS SUITE DID NOT CATCH IT. FRESH_TS is "…T11:30:00Z", whose
# mangled remnant '00Z' happens to parse as today-midnight, which is always
# within 24h of anything. The fixture's own choice of a :00 seconds field is
# what hid the defect. The three cases below use timestamps that cannot be
# rescued by that accident.
TS_STALE_OFFSET="2026-08-12T18:20:00+02:00"   # the real 0.2.12-25 form, 14d old
TS_FRESH_ODD_SECS="2026-08-26T11:30:38Z"      # fresh; seconds is not a valid hour

D_TS_STALE="$(mk_json ts-stale-offset <<EOF
{ "commit_sha": "$HEAD", "timestamp": "$TS_STALE_OFFSET",
  "covering_challenges": { "Challenge58SearchReturnsResultsTest": "PASS" } }
EOF
)"
run_case "json_timestamp_offset_14d_old_is_stale" 1 "$D_TS_STALE" "$MAP" "$HEAD" "evidence is stale"

D_TS_STALE_AUTH="$(mk_json ts-stale-authored <<EOF
{ "commit_sha": "$HEAD", "authored_utc": "2026-08-12T16:20:00Z",
  "covering_challenges": { "Challenge58SearchReturnsResultsTest": "PASS" } }
EOF
)"
run_case "json_authored_utc_14d_old_is_stale" 1 "$D_TS_STALE_AUTH" "$MAP" "$HEAD" "evidence is stale"

D_TS_ODD="$(mk_json ts-fresh-odd-secs <<EOF
{ "commit_sha": "$HEAD", "timestamp": "$TS_FRESH_ODD_SECS",
  "covering_challenges": { "Challenge58SearchReturnsResultsTest": "PASS" } }
EOF
)"
run_case "json_timestamp_fresh_with_odd_seconds_passes" 0 "$D_TS_ODD" "$MAP"

# ── verdict ────────────────────────────────────────────────────────────────
echo "-----------------------------------------------------------"
echo "cases examined: $cases_run   passed: $pass_count   failed: $fail_count"
if (( cases_run == 0 )); then
  echo "RESULT: FAIL — ZERO cases examined. A suite that asserts nothing is a vacuous" >&2
  echo "        pass, which is the exact bluff class this suite exists to prevent." >&2
  exit 1
fi
if (( fail_count > 0 )); then
  echo "RESULT: FAIL — the §6.AK gate's verdict is not a faithful function of the evidence" >&2
  exit 1
fi
echo "RESULT: PASS — $cases_run cases: verdicts are PARSED (not name-matched), unrecognised"
echo "        formats REFUSE and name the file, and the commit-SHA binding binds."
exit 0
