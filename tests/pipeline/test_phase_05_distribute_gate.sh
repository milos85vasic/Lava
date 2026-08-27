#!/usr/bin/env bash
# Hermetic test suite for scripts/pipeline/phase-05-distribute.sh — the
# §6.AA clause 8 REFUSAL GATE (tasks.md T043, gate-only slice).
#
# WHAT IS UNDER TEST: the gate's decision logic, in isolation. Nothing here
# builds, boots an emulator, or distributes anything. scripts/firebase-
# distribute.sh is NEVER invoked by this suite or by the script under test —
# invoking it would attempt a real Firebase upload. Condition (E) is verified
# by STATIC inspection of that script, which is what this suite exercises.
#
# THE SHAPE OF THE THING BEING GUARDED (forensic anchor):
# §6.AA clause 8 grants the pipeline permission to distribute debug->release
# with no operator pause, IF AND ONLY IF eight conjunctive conditions (A)-(H)
# hold. The clause closes with its own standing note: treating a green run
# report as sufficient WITHOUT condition (C) having genuinely executed is
# "the §6.J bluff class this project has recorded three times ... and is a
# violation of this clause, not a use of it".
#
# RE-LETTERED 2026-08-26 (§6.AA amendment). The former condition (D) --
# cycle-coverage on BOTH channels -- was WITHDRAWN, not renumbered, and
# former (E)-(I) became (D)-(H). There is no condition (I). Cases that
# exercised (D) are DELETED rather than renamed, which is why the case
# numbers below skip.
#
# So the load-bearing property of this suite is NOT "a good report passes".
# It is that EACH condition, mutated ALONE, flips the verdict to REFUSED and
# NAMES ITSELF. A gate that refuses everything is useless (CASE 1 catches
# that); a gate that passes anything is the defect the whole feature exists
# to prevent (CASES 2-17 catch that).
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="${REPO_ROOT}/scripts/pipeline/phase-05-distribute.sh"

if [[ ! -f "$GATE" ]]; then
  echo "FAIL: script under test not found: $GATE"
  exit 1
fi
for tool in jq git python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
trap 'rm -rf -- "$WORKDIR"' EXIT

RUN_ID="2026-08-25T12-00-00Z"
VER_APP="1.3.20-1090"
VER_API="0.2.20-30"

# ---------------------------------------------------------------------------
# Fixture construction. _qualifying builds a repo + run whose report and
# evidence satisfy ALL EIGHT conditions; each CASE then breaks exactly one
# thing, so a refusal is attributable to that one thing and nothing else.
# ---------------------------------------------------------------------------

# _stub_firebase_distribute <path>  — gate-bearing, no bypass flag.
_stub_firebase_distribute() {
  cat > "$1" <<'STUB'
#!/usr/bin/env bash
# Stub standing in for scripts/firebase-distribute.sh for STATIC inspection
# only. This suite never executes it.
echo "FATAL: this stub must never be executed by the gate" >&2
exit 99
# Gate 1: monotonic version code
# Gate 2: CHANGELOG.md entry
# Gate 3: per-version snapshot file exists
# Gate 4: pepper rotation
# Gate 5: LAVA_AUTH_CURRENT_CLIENT_NAME consistency
# Gate 7 (§6.AK): cycle-coverage
STUB
  chmod +x "$1"
}

# The real, independent anti-bluff validator. A fixture that writes Evidence
# Records without ever putting them through this is not modelling a pipeline
# run -- see the header note on _evidence below.
# shellcheck source=scripts/pipeline/lib/anti-bluff-validate.sh
source "${REPO_ROOT}/scripts/pipeline/lib/anti-bluff-validate.sh"

# Anti-vacuity ledger. A fixture suite that reports success having put ZERO
# records through the validator has proven nothing about a gate whose central
# question is "was this record examined?".
#
# A FILE, not shell variables. Fixture builders in this suite are invoked as
# `DIR="$(_qualifying name)"` -- a COMMAND SUBSTITUTION, i.e. a subshell -- so
# every `COUNTER=$((COUNTER + 1))` inside them is discarded when the subshell
# exits. Measured: an in-memory counter reported 5 records written / 1
# validated for a run that really wrote 100+, and still printed a green
# ">0, so not vacuous" line -- an anti-vacuity check that was itself nearly
# vacuous. Appending one line per record to a file crosses the subshell
# boundary, so the count printed is the real one.
EVIDENCE_LEDGER="${WORKDIR}/evidence-ledger.tsv"
: > "$EVIDENCE_LEDGER"
_ledger() { printf '%s\t%s\n' "$1" "$2" >> "$EVIDENCE_LEDGER"; }
# _ledger_count <validated|rejected|unvalidated|any>
_ledger_count() {
  if [[ "$1" == "any" ]]; then
    wc -l < "$EVIDENCE_LEDGER" | tr -d ' '
  else
    awk -F'\t' -v s="$1" '$1 == s' "$EVIDENCE_LEDGER" | wc -l | tr -d ' '
  fi
}

# _evidence <dir> <run_id> <phase> <category> <test_id> <result> <command> <summary>
#
# Writes ONE Evidence Record through the production writer and then puts it
# through the real, independent anti-bluff validator -- which is exactly what
# every production wrapper does (see the write_evidence_record /
# validate_evidence_record pair in scripts/pipeline/phase-02-test-challenge.sh).
#
# WHY THE VALIDATE CALL IS NOT OPTIONAL (forensic anchor, 2026-08-26).
# Until this change these fixtures called write_evidence_record and NEVER
# called validate_evidence_record. That was survivable only because the writer
# stamped every record it produced with the literal "validated" -- the same
# value the validator writes on ACCEPT -- so "the independent validator
# examined this record and accepted it" and "no validator has ever looked at
# this record" were BYTE-IDENTICAL on disk, and every consumer reads that field
# as a verdict. The writer now emits an honest not-yet-validated placeholder
# that fails closed, so a fixture that skips validation models a BROKEN run.
#
# That matters more here than in an ordinary fixture. This suite's canonical
# QUALIFYING fixture is the artifact that certifies §6.AA clause 8 -- the rule
# permitting an unattended debug->release distribution. Clause 8(B) requires
# "zero Evidence Records carry an anti_bluff_status other than validated", and
# with the old fail-open default that condition was satisfiable by records
# NOBODY EVER VALIDATED, including all four mandatory real-device-challenge
# records. That is the §6.AK forensic anchor (the 2026-06-26 C00-only gate)
# reproduced one level ABOVE where it was found: not in a release's evidence,
# but in the fixture certifying the gate that authorises release.
#
# For category real-device-challenge the raw capture additionally carries a
# FALSIFIABILITY REHEARSAL block, because that is what the real wrapper writes
# (it lifts the block verbatim out of the Challenge class's KDoc into the raw
# capture) and what anti-bluff-validate.sh's Rule 4 reads. Omitting it here
# would make honest device fixtures REJECT for a reason the real pipeline does
# not have.
_evidence() {
  local dir="$1" run="$2" phase="$3" cat="$4" tid="$5" res="$6" cmd="$7" sum="$8"
  local pdir="${dir}/.lava-ci-evidence/pipeline-runs/${run}/${phase}"
  local rawdir="${pdir}/${cat}/raw"
  mkdir -p -- "$rawdir"
  local raw="${rawdir}/${tid}.log"
  printf 'real captured output for %s\n%s\n' "$tid" "$sum" > "$raw"
  if [[ "$cat" == "real-device-challenge" ]]; then
    _append_falsifiability_marker "$raw" "$tid"
  fi
  local record
  if ! record="$( cd "$dir" && source "${REPO_ROOT}/scripts/pipeline/lib/evidence.sh" && \
      write_evidence_record "$pdir" "$tid" "$cat" "$cmd" "$res" "$sum" "$raw" )"; then
    fail "fixture error: write_evidence_record failed for ${tid}"
    return 0
  fi
  if validate_evidence_record "$record" >/dev/null 2>&1; then
    _ledger validated "$record"
  else
    _ledger rejected "$record"
  fi
}

# _evidence_unvalidated <same args as _evidence>
# Writes a record through the production writer and DELIBERATELY does not
# validate it -- the state a run is in when a wrapper writes evidence and no
# validator ever looks at it. Used only by the negative case that proves such
# a record cannot qualify the gate.
_evidence_unvalidated() {
  local dir="$1" run="$2" phase="$3" cat="$4" tid="$5" res="$6" cmd="$7" sum="$8"
  local pdir="${dir}/.lava-ci-evidence/pipeline-runs/${run}/${phase}"
  local rawdir="${pdir}/${cat}/raw"
  mkdir -p -- "$rawdir"
  local raw="${rawdir}/${tid}.log"
  printf 'real captured output for %s\n%s\n' "$tid" "$sum" > "$raw"
  if [[ "$cat" == "real-device-challenge" ]]; then
    _append_falsifiability_marker "$raw" "$tid"
  fi
  ( cd "$dir" && source "${REPO_ROOT}/scripts/pipeline/lib/evidence.sh" && \
      write_evidence_record "$pdir" "$tid" "$cat" "$cmd" "$res" "$sum" "$raw" >/dev/null )
  _ledger unvalidated "${pdir}/${cat}/${tid}.json"
}

# _append_falsifiability_marker <raw-file> <test_id>
# Mirrors what scripts/pipeline/phase-02-test-challenge.sh appends to a
# real-device-challenge raw capture: the Challenge class's own KDoc
# falsifiability-rehearsal block, verbatim. anti-bluff-validate.sh Rule 4
# reads assertion_summary + this file looking for the marker.
_append_falsifiability_marker() {
  cat >> "$1" <<MARKEREOF
--- real KDoc falsifiability-rehearsal marker, verbatim from the source file ---
FALSIFIABILITY REHEARSAL: LavaApplication.onCreate() was made to throw before
the Hilt graph is built; ${2} then failed with
"expected launcher activity to reach RESUMED, observed process death at
onCreate" instead of passing. Mutation reverted; the test passes again.
MARKEREOF
}

# _qualifying <name> -> echoes the fixture repo dir
_qualifying() {
  local name="$1"
  local dir="${WORKDIR}/${name}"
  mkdir -p "${dir}/scripts"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Fixture"
  printf '.lava-ci-evidence/\n' > "${dir}/.gitignore"
  _stub_firebase_distribute "${dir}/scripts/firebase-distribute.sh"
  printf 'x\n' > "${dir}/f"
  git -C "$dir" add -A
  git -C "$dir" commit -qm init
  local sha; sha="$(git -C "$dir" rev-parse HEAD)"

  # §6.Z / §6.AK evidence dirs the cycle-coverage check is pointed at.
  mkdir -p "${dir}/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
  mkdir -p "${dir}/.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app"

  # ---- Run Report: every phase PASS, zero anti-bluff rejections ----
  local rdir="${dir}/.lava-ci-evidence/pipeline-runs/${RUN_ID}"
  mkdir -p "$rdir"
  python3 - "$rdir/report.json" "$sha" "$VER_APP" "$VER_API" <<'PY'
import json, sys
out, sha, vapp, vapi = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
an, ac = vapp.rsplit("-", 1)
bn, bc = vapi.rsplit("-", 1)
phases = ["precondition","build","test","install_boot","live_verify","changelog_entry"]
rep = {
  "run_id": "2026-08-25T12-00-00Z",
  "commit_sha": sha,
  "started_at": "2026-08-25T12:00:00Z",
  "completed_at": "2026-08-25T13:00:00Z",
  "outcome": "PASS",
  "phases": [
    {"name": p, "result": "PASS", "duration_seconds": 1,
     "evidence_dir": f".lava-ci-evidence/pipeline-runs/2026-08-25T12-00-00Z/phase-0{i}"}
    for i, p in enumerate(phases)
  ],
  "build_artifacts": [
    {"artifact_id": a, "version_name": (an if a.startswith("app-") else bn),
     "version_code": int(ac if a.startswith("app-") else bc),
     "build_output_path": f"build/{a}.apk", "built_from_commit": sha}
    for a in ["app-debug","app-release","api-app-debug","api-app-release"]
  ],
  "evidence_summary": {"total": 6, "passed": 6, "failed": 0, "skipped": 0,
                       "rejected_by_anti_bluff": 0},
  "distributions": [],
  "submodule_advances": [],
}
with open(out, "w") as fh:
    json.dump(rep, fh, indent=2)
PY

  # ---- (C) real-device-challenge evidence, one PASS per variant ----
  local v
  for v in app-debug app-release api-app-debug api-app-release; do
    _evidence "$dir" "$RUN_ID" "phase-02" "real-device-challenge" \
      "lava.app.challenges.Challenge00CrashSurvival.${v}" "PASS" \
      "scripts/run-challenge-matrix.sh --no-build --artifact ${v} --runner=containerized --container-runtime podman" \
      "cold-start survived on a containerized emulator for artifact ${v}; launcher activity reached RESUMED"
  done

  # ---- (D) live-verification evidence for BOTH live surfaces ----
  _evidence "$dir" "$RUN_ID" "phase-04" "hermetic-script" \
    "live.lava-api-go.health" "PASS" \
    "curl --fail against the running lava-api-go service health route" \
    "lava-api-go returned HTTP 200 with a JSON body reporting status=ok over its real TLS listener"
  _evidence "$dir" "$RUN_ID" "phase-04" "hermetic-script" \
    "live.api-app.health" "PASS" \
    "curl --fail against the on-device api-app forwarded health route" \
    "api-app on-device surface answered its real health probe with HTTP 200 over the forwarded port"

  printf '%s' "$dir"
}

# _recommit <dir>
# Commits whatever a CASE just changed in the fixture's tracked files and
# re-syncs the report's commit_sha to the new HEAD.
#
# WHY THIS EXISTS: a case that edits a stub AFTER _qualifying's initial
# commit leaves the tree dirty, which fails condition (F) as a side effect.
# The case would still "pass" — the gate refuses either way — but for a
# reason the case never intended to test, and a confound that makes a test
# pass is how a test stops measuring what its name claims. Committing keeps
# the tree clean so exactly ONE condition is under test; re-syncing the sha
# keeps condition (A) satisfied, since committing moves HEAD.
_recommit() {
  local dir="$1"
  git -C "$dir" add -A
  git -C "$dir" commit -qm "fixture adjustment" --allow-empty
  local sha; sha="$(git -C "$dir" rev-parse HEAD)"
  local rp="${dir}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
  if [[ -f "$rp" ]]; then
    jq --arg s "$sha" \
      '.commit_sha = $s | .build_artifacts = [.build_artifacts[] | .built_from_commit = $s]' \
      "$rp" > "${rp}.t" && mv "${rp}.t" "$rp"
  fi
}

# _run <dir> [extra args...] -> sets G_RC and G_OUT
_run() {
  local dir="$1"; shift
  local out="${WORKDIR}/gate.log"
  set +e
  ( cd "$dir" && bash "$GATE" "$RUN_ID" "$dir" \
      --firebase-distribute-script "${dir}/scripts/firebase-distribute.sh" \
      "$@" ) >"$out" 2>&1
  G_RC=$?
  set -e
  G_OUT="$(cat "$out")"
}

# _refused_naming <condition-label> <case-name>
_refused_naming() {
  local label="$1" name="$2"
  if [[ "$G_RC" -eq 2 ]] && grep -qF "$label" <<< "$G_OUT" && grep -qF "REFUSED" <<< "$G_OUT"; then
    pass "${name}: REFUSED (exit 2) naming ${label}"
  else
    fail "${name}: expected REFUSED (exit 2) naming ${label}; got exit ${G_RC}. Output:
${G_OUT}"
  fi
}

echo "==============================================================="
echo "CASE 1 (LOAD-BEARING, positive): a fully-qualifying run"
echo "==============================================================="
echo "Without this case a blanket 'refuse everything' implementation"
echo "would pass every other case in this file."
echo ""

DIR1="$(_qualifying qualifying)"
_run "$DIR1"

if [[ "$G_RC" -eq 3 ]]; then
  pass "qualifying run -> exit 3 (gate qualified, distribute deliberately unimplemented)"
else
  fail "qualifying run -> exit ${G_RC}, expected 3. Output:
${G_OUT}"
fi
if grep -qF "distribute step not implemented" <<< "$G_OUT"; then
  pass "qualifying run: says plainly that the distribute step is not implemented"
else
  fail "qualifying run: no 'distribute step not implemented' marker. Output:
${G_OUT}"
fi
# There must be no condition (I): the letter was retired with the condition,
# and a gate still emitting one would mean the re-lettering was cosmetic.
if grep -qF "CONDITION (I)" <<< "$G_OUT"; then
  fail "qualifying run: the gate still reports a CONDITION (I); the 2026-08-26 re-lettering removed that letter"
else
  pass "qualifying run: no condition (I) is reported"
fi
MISSING_COND=0
for L in "(A)" "(B)" "(C)" "(D)" "(E)" "(F)" "(G)" "(H)"; do
  if ! grep -qF "CONDITION ${L}" <<< "$G_OUT"; then
    fail "qualifying run: condition ${L} was never individually reported"
    MISSING_COND=1
  fi
done
if [[ "$MISSING_COND" -eq 0 ]]; then
  pass "qualifying run: all eight conditions individually reported"
fi

echo ""
echo "==============================================================="
echo "CASE 2: no run report at all -> REFUSED naming (A)"
echo "==============================================================="
DIR2="$(_qualifying noreport)"
rm -f "${DIR2}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
_run "$DIR2"
_refused_naming "(A)" "missing report"

echo ""
echo "==============================================================="
echo "CASE 3: commit_sha mismatch -> REFUSED naming (A)"
echo "==============================================================="
echo "Clause 8(A): a report whose commit_sha does not match 'is not stale"
echo "evidence to be tolerated — it is a REFUSAL condition.'"
echo ""
DIR3="$(_qualifying shamismatch)"
R3="${DIR3}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
jq '.commit_sha = "0123456789abcdef0123456789abcdef01234567"' "$R3" > "${R3}.t" && mv "${R3}.t" "$R3"
_run "$DIR3"
_refused_naming "(A)" "commit_sha mismatch"

echo ""
echo "==============================================================="
echo "CASE 4: FR-009 baseline — an anti-bluff rejection -> REFUSED"
echo "==============================================================="
DIR4="$(_qualifying antibluff)"
R4="${DIR4}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
jq '.evidence_summary.rejected_by_anti_bluff = 1' "$R4" > "${R4}.t" && mv "${R4}.t" "$R4"
_run "$DIR4"
if [[ "$G_RC" -eq 2 ]] && grep -qF "FR-009" <<< "$G_OUT"; then
  pass "anti-bluff rejection -> REFUSED naming FR-009"
else
  fail "anti-bluff rejection -> exit ${G_RC} without naming FR-009. Output:
${G_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 5: a phase result of FAIL -> REFUSED naming (B)"
echo "==============================================================="
DIR5="$(_qualifying phasefail)"
R5="${DIR5}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
jq '(.phases[] | select(.name=="test") | .result) = "FAIL"' "$R5" > "${R5}.t" && mv "${R5}.t" "$R5"
_run "$DIR5"
_refused_naming "(B)" "phase FAIL"

echo ""
echo "==============================================================="
echo "CASE 6 (LOAD-BEARING): release-variant device evidence removed"
echo "==============================================================="
echo "Clause 8(C): 'Release-variant device evidence is mandatory and is NOT"
echo "inferable from debug-variant evidence.' The §6.Z forensic anchor was a"
echo "release-variant-only failure, so debug evidence standing in for release"
echo "is the exact bluff the clause exists to stop."
echo ""
DIR6="$(_qualifying norelease)"
rm -f "${DIR6}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge/"*app-release*.json
_run "$DIR6"
_refused_naming "(C)" "app-release device evidence missing"
if grep -qF "app-release" <<< "$G_OUT"; then
  pass "app-release missing: the gate names the specific variant that lacks evidence"
else
  fail "app-release missing: the gate refused without naming which variant. Output:
${G_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 7: device evidence from a host-direct runner -> REFUSED (C)"
echo "==============================================================="
echo "§6.AH/§6.AG: host-direct is forbidden; only container-or-VM counts."
echo ""
DIR7="$(_qualifying hostdirect)"
C7="${DIR7}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge"
for f in "$C7"/*app-release*.json; do
  jq '.command = "scripts/run-challenge-matrix.sh --artifact app-release --runner=host-direct"' \
     "$f" > "${f}.t" && mv "${f}.t" "$f"
done
_run "$DIR7"
_refused_naming "(C)" "host-direct runner"

echo ""
echo "==============================================================="
echo "CASE 8: a SKIPPED real-device-challenge record -> REFUSED (B)"
echo "==============================================================="
echo "Clause 8(B): 'A SKIPPED Evidence Record in any category named in"
echo "condition (C) or (D) disqualifies the run.'"
echo ""
DIR8="$(_qualifying skipped)"
C8="${DIR8}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge"
for f in "$C8"/*app-release*.json; do
  jq '.result = "SKIPPED"' "$f" > "${f}.t" && mv "${f}.t" "$f"
done
_run "$DIR8"
if [[ "$G_RC" -eq 2 ]] && grep -qF "SKIPPED" <<< "$G_OUT"; then
  pass "SKIPPED device record -> REFUSED, naming the skip"
else
  fail "SKIPPED device record -> exit ${G_RC} without naming the skip. Output:
${G_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 11: live-verification covers only one surface -> REFUSED (D)"
echo "==============================================================="
echo "Clause 8(D): 'A run whose live-verification covers only one of the two"
echo "does NOT qualify, irrespective of that half's result.'"
echo ""
DIR11="$(_qualifying onesurface)"
rm -f "${DIR11}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-04/hermetic-script/"*api-app*.json
_run "$DIR11"
_refused_naming "(D)" "api-app live surface unverified"

echo ""
echo "==============================================================="
echo "CASE 11b: ONE record naming both surfaces -> REFUSED (D)"
echo "==============================================================="
echo "A single probe whose summary name-drops both surfaces is not two"
echo "surfaces exercised. Without this, one record could satisfy all of (D)."
echo ""
DIR11B="$(_qualifying bothinone)"
LV11B="${DIR11B}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-04/hermetic-script"
rm -f "${LV11B}/"*api-app*.json "${LV11B}/"*lava-api-go*.json
_evidence "$DIR11B" "$RUN_ID" "phase-04" "hermetic-script" \
  "live.combined.health" "PASS" \
  "curl --fail against a health route" \
  "checked lava-api-go and api-app together in one probe"
_run "$DIR11B"
_refused_naming "(D)" "one record standing in for both live surfaces"

echo ""
echo "==============================================================="
echo "CASE 12: a bypass flag in the distribute script -> REFUSED (E)"
echo "==============================================================="
echo "§6.Z clause 6: no bypass flag exists and none may be added."
echo ""
DIR12="$(_qualifying bypass)"
printf '\n# --bypass-tests: skip every Phase 1 gate\n' >> "${DIR12}/scripts/firebase-distribute.sh"
_recommit "$DIR12"
_run "$DIR12"
_refused_naming "(E)" "bypass flag present in distribute script"

echo ""
echo "==============================================================="
echo "CASE 13: not on master -> REFUSED naming (F)"
echo "==============================================================="
DIR13="$(_qualifying wrongbranch)"
git -C "$DIR13" checkout -q -b feature/side
_run "$DIR13"
_refused_naming "(F)" "not on master"

echo ""
echo "==============================================================="
echo "CASE 14: a variant with no build_output_path -> REFUSED naming (G)"
echo "==============================================================="
echo "Condition (G) records the §6.AA-pipeline-debt Firebase-install gap"
echo "through build_artifacts[].build_output_path -- the property that names"
echo "the LOCALLY-BUILT file this run's evidence actually exercised. A report"
echo "that does not name it has not made the gap visible in the artifact."
echo ""
DIR14="$(_qualifying nogap)"
R14="${DIR14}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
jq '.build_artifacts = [.build_artifacts[] | select(.artifact_id != "app-release")]' \
  "$R14" > "${R14}.t" && mv "${R14}.t" "$R14"
_run "$DIR14"
_refused_naming "(G)" "app-release names no build_output_path"

echo ""
echo "==============================================================="
echo "CASE 14b: a RETIRED residual-gap field -> REFUSED, and by (A)"
echo "==============================================================="
echo "Until 2026-08-26 the gate TOLERATED this one unknown property because"
echo "the then-condition (H) mandated it while the schema forbids it"
echo "(LVA-147). The condition was reworded, so the tolerance is gone: the"
echo "schema says additionalProperties:false and (A) must now enforce it."
echo ""
DIR14B="$(_qualifying retiredgap)"
R14B="${DIR14B}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
jq '. + {"residual-gap": "firebase-install-path-unverified"}' \
  "$R14B" > "${R14B}.t" && mv "${R14B}.t" "$R14B"
_run "$DIR14B"
_refused_naming "(A)" "retired residual-gap property present"

echo ""
echo "==============================================================="
echo "CASE 15: an active clause-8 suspension -> REFUSED naming (H)"
echo "==============================================================="
echo "Clause 8(H): 'Suspension is the default state on failure;"
echo "re-authorization is an explicit act, never an inference from time.'"
echo ""
DIR15="$(_qualifying suspended)"
mkdir -p "${DIR15}/.lava-ci-evidence/clause-8-suspension"
printf 'suspended after a Crashlytics FATAL on 1.3.19-1089 cold start\n' \
  > "${DIR15}/.lava-ci-evidence/clause-8-suspension/ACTIVE"
_run "$DIR15"
_refused_naming "(H)" "active clause-8 suspension"

echo ""
echo "==============================================================="
echo "CASE 16: no argument can force the gate past a failed condition"
echo "==============================================================="
echo "§6.Z clause 6 is absolute. A gate with an escape hatch is not a gate."
echo ""
BYPASSY="$(grep -cE -- '^[[:space:]]*--(force|bypass|skip-gate|no-gate|skip-conditions|override|yes|assume-pass)\)' "$GATE" || true)"
if [[ "$BYPASSY" -eq 0 ]]; then
  pass "the gate defines no force/bypass/override argument"
else
  fail "the gate appears to define ${BYPASSY} bypass-shaped argument(s) — §6.Z clause 6 forbids any"
fi
# And prove it behaviourally: a failing condition stays failed no matter what
# plausible override is thrown at it.
DIR16="$(_qualifying forceproof)"
R16="${DIR16}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
jq '(.phases[] | select(.name=="test") | .result) = "FAIL"' "$R16" > "${R16}.t" && mv "${R16}.t" "$R16"
FORCE_OK=1
for flag in --force --bypass --no-gate --override --skip-conditions --yes; do
  _run "$DIR16" "$flag" || true
  if [[ "$G_RC" -eq 3 || "$G_RC" -eq 0 ]]; then
    fail "'${flag}' forced a failing run to qualify (exit ${G_RC}) — that is a §6.Z clause 6 violation"
    FORCE_OK=0
  fi
done
if [[ "$FORCE_OK" -eq 1 ]]; then
  pass "no plausible override argument turns a failing condition into a pass"
fi

echo ""
echo "==============================================================="
echo "CASE 17: the gate never executes the real distribute script"
echo "==============================================================="
if grep -qE '^[[:space:]]*[^#]*(bash|sh|exec|source)[[:space:]]+[^|]*firebase-distribute' "$GATE"; then
  fail "the gate contains what looks like an INVOCATION of firebase-distribute.sh — it must only inspect it"
else
  pass "the gate only inspects firebase-distribute.sh, never invokes it"
fi

echo ""
echo "==============================================================="
echo "CASE 18 (NEGATIVE, load-bearing): Evidence Records that were"
echo "WRITTEN but never VALIDATED must not qualify the gate"
echo "==============================================================="
echo "FORENSIC ANCHOR (2026-08-26). write_evidence_record used to stamp"
echo "every record it wrote with the literal \"validated\" -- the exact value"
echo "the independent validator writes on ACCEPT -- so a record nobody ever"
echo "examined was BYTE-IDENTICAL on disk to one examined and accepted."
echo "Clause 8(B) requires 'zero Evidence Records carry an anti_bluff_status"
echo "other than validated', and every consumer reads that field as a"
echo "verdict, so (B) was satisfiable by records NOBODY EVER VALIDATED --"
echo "including all four mandatory real-device-challenge records. There was"
echo "no test for this: this whole suite's fixtures wrote records and never"
echo "validated them, so the suite could not have noticed."
echo ""
echo "This case builds a run that is qualifying in EVERY other respect and"
echo "differs from CASE 1 in exactly one way: its records were written and"
echo "no validator ever looked at them."
echo ""
DIR18="$(_qualifying unvalidated)"
# Replace the four device records with byte-equivalent ones that skipped
# validation. Nothing else about the run changes.
rm -f "${DIR18}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge/"*.json
for v in app-debug app-release api-app-debug api-app-release; do
  _evidence_unvalidated "$DIR18" "$RUN_ID" "phase-02" "real-device-challenge" \
    "lava.app.challenges.Challenge00CrashSurvival.${v}" "PASS" \
    "scripts/run-challenge-matrix.sh --no-build --artifact ${v} --runner=containerized --container-runtime podman" \
    "cold-start survived on a containerized emulator for artifact ${v}; launcher activity reached RESUMED"
done

# Fixture precondition: prove the records really are unvalidated on disk.
# A negative case whose fixture silently failed to build the bad state would
# "pass" by refusing for some unrelated reason -- the confound this suite's
# _recommit note already warns about.
C18="${DIR18}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge"
N18_UNVAL=0
for f in "$C18"/*.json; do
  st="$(jq -r '.anti_bluff_status' "$f")"
  [[ "$st" == "validated" ]] || N18_UNVAL=$((N18_UNVAL + 1))
done
if [[ "$N18_UNVAL" -eq 4 ]]; then
  pass "fixture precondition: all 4 device records are on disk in a not-yet-validated state"
else
  fail "fixture setup error: ${N18_UNVAL}/4 device records are unvalidated, so this case would prove nothing"
fi

_run "$DIR18"
if [[ "$G_RC" -eq 2 ]]; then
  pass "a run whose records were never validated does NOT qualify (exit 2)"
else
  fail "a run whose Evidence Records were never put through the anti-bluff validator qualified with exit ${G_RC}. That is the fail-open this case exists to catch: 'examined and accepted' and 'never looked at' must not be the same state. Output:
${G_OUT}"
fi
if grep -qF "REFUSED: (B)" <<< "$G_OUT" && grep -qF "REFUSED: (C)" <<< "$G_OUT"; then
  pass "both (B) and (C) refuse it, and by name"
else
  fail "expected BOTH (B) and (C) to refuse an unvalidated-record run. Output:
${G_OUT}"
fi
if grep -qF "anti-bluff validation has not run on this record" <<< "$G_OUT"; then
  pass "the refusal states the real reason: no validator ever ran on the record"
else
  fail "the gate refused, but never said WHY — a reader cannot tell an unvalidated record from a rejected one. Output:
${G_OUT}"
fi

echo ""
echo "==============================================================="
echo "CASE 19 (DISCRIMINATION PROBE, reports a REAL GAP in the gate):"
echo "clause 8 still qualifies on C00-only device evidence"
echo "==============================================================="
echo "This is not a fixture defect. It is a measured property of the gate,"
echo "re-measured on every run of this suite so it cannot quietly change."
echo ""
echo "§6.Z clause 4 makes the cold-start check the MINIMUM per variant and"
echo "says in terms that it does not by itself satisfy §6.AK. §6.AA clause"
echo "8(C) repeats that: 'Cold-start survival (§6.Z clause 4) is the minimum"
echo "per variant, and does not by itself satisfy §6.AK.' §6.AK exists"
echo "BECAUSE of the 2026-06-26 incident in which a gate that executed only"
echo "Challenge00CrashSurvival green-lit two distributions whose CHANGELOG"
echo "claimed search / provider-selection / onboarding / display fixes."
echo ""
echo "Condition (C) as implemented checks, per variant: a build_artifacts"
echo "entry built from the shipped commit; a record naming exactly that one"
echo "variant; category real-device-challenge; result PASS; anti_bluff_status"
echo "validated; a container-or-VM runner token; no host-direct/live-device"
echo "token. It never reads WHICH Challenge ran. So four C00 records qualify."
echo ""
GAPS=0
gap() { echo "KNOWN-GAP: $1"; GAPS=$((GAPS + 1)); }

DIR19="$(_qualifying c00only)"
C19="${DIR19}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge"
C19_IDS="$(jq -r '.test_id' "$C19"/*.json | sort | tr '\n' ' ')"
C19_NON_C00="$(jq -r '.test_id' "$C19"/*.json | grep -cv 'Challenge00CrashSurvival' || true)"
echo "device record test_ids in the canonical qualifying fixture:"
echo "  ${C19_IDS}"
echo "records naming a Challenge OTHER than Challenge00CrashSurvival: ${C19_NON_C00}"
_run "$DIR19"

if [[ "$C19_NON_C00" -ne 0 ]]; then
  fail "probe precondition: the qualifying fixture is no longer C00-only, so this probe is not measuring what it claims"
elif [[ "$G_RC" -eq 3 ]]; then
  gap "clause 8 QUALIFIES (exit 3) on evidence in which all four mandatory
    real-device-challenge records are Challenge00CrashSurvival. Condition (C)
    reports PASS:
$(grep -A1 'CONDITION (C)' <<< "$G_OUT" | sed 's/^/      /')
    §6.Z clause 4 and §6.AA clause 8(C) both say cold-start alone is the
    MINIMUM and is not sufficient; §6.AK is the recorded incident of exactly
    this. The gate does not read which Challenge produced a record, so it
    cannot tell C00-only evidence from cycle-covering evidence.

    THE CONDITION THAT SHOULD CATCH IT: condition (C) must additionally
    require, per variant, at least one PASSing real-device-challenge record
    whose test_id is NOT the cold-start canary -- i.e. C00 satisfies the
    §6.Z clause 4 minimum and a second, non-C00 Challenge covering the
    cycle's CHANGELOG-claimed user-visible fixes satisfies §6.AK clause 1.
    Equivalently: intersect the executed-PASS Challenge set with the
    cycle's claim-set, per §6.AK clause 1, and refuse on any uncovered
    claim. Neither is implemented anywhere -- scripts/check-cycle-coverage.sh
    parses --channel and never reads it, and the clause-8 condition that
    gestured at it was WITHDRAWN on 2026-08-26 rather than repaired.
    Tracked upstream as §6.AA-pipeline-debt-D, OWED.

    NOT FIXED HERE: scripts/pipeline/phase-05-distribute.sh is out of this
    task's scope. This probe records the gap and fails the moment the
    fixture stops being C00-only, so the finding cannot go stale silently."
else
  pass "clause 8 no longer qualifies on C00-only device evidence (exit ${G_RC}) — the gap this probe was written to record has been closed; DELETE this probe and replace it with a real assertion"
fi

echo ""
echo "==============================================================="
echo "EXAMINED-COUNT (anti-vacuity)"
echo "==============================================================="
echo "A suite that reports success having put ZERO Evidence Records through"
echo "the real, independent anti-bluff validator has proven nothing about a"
echo "gate whose central question is whether a record was ever examined."
echo "State the count, and refuse to pass at zero."
echo ""
N_ANY="$(_ledger_count any)"
N_VAL="$(_ledger_count validated)"
N_REJ="$(_ledger_count rejected)"
N_UNV="$(_ledger_count unvalidated)"
echo "Evidence Records written through the production writer : ${N_ANY}"
echo "  ... put through the real validator and ACCEPTED      : ${N_VAL}"
echo "  ... put through the real validator and REJECTED      : ${N_REJ}"
echo "  ... deliberately left unvalidated (negative fixtures) : ${N_UNV}"
if [[ "$N_ANY" -gt 0 && "$N_VAL" -gt 0 ]]; then
  pass "this suite put ${N_VAL} of ${N_ANY} record(s) through the real anti-bluff validator (>0, so its fixtures are not vacuous)"
else
  fail "this suite put ${N_VAL} record(s) through the anti-bluff validator. A fixture that never validates anything is not modelling a pipeline run, and cannot certify §6.AA clause 8."
fi

echo ""
echo "==============================================================="
if [[ "$GAPS" -gt 0 ]]; then
  echo "${GAPS} KNOWN-GAP finding(s) recorded above (measured, not asserted)."
  echo "These are properties of the gate, not failures of this suite."
  echo ""
fi
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi
