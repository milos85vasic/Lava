#!/usr/bin/env bash
# Hermetic VACUITY suite for scripts/pipeline/phase-05-distribute.sh — the
# §6.AA clause 8 refusal gate.
#
# WHY A SECOND SUITE. tests/pipeline/test_phase_05_distribute_gate.sh proves
# that each condition, mutated in the OBVIOUS direction, refuses: delete the
# release-variant record, set a phase to FAIL, add a bypass flag. That is
# necessary and it is not sufficient. A gate that wrongly REFUSES is an
# annoyance; a gate that wrongly PASSES authorises an unattended release.
#
# This suite attacks the other direction only: for each condition, what
# happens when the thing it examines is ABSENT, EMPTY, UNREADABLE, MALFORMED,
# or merely SHAPED LIKE the thing it should be? The gate's own header states
# its contract — "A condition this script cannot evaluate REFUSES. It never
# passes on the grounds that it could not tell." Every case below was first
# observed VIOLATING that contract (exit 3, GATE QUALIFIED) against the
# pre-fix script; each is therefore a regression test with a recorded RED.
#
# Every case is paired with a positive control (CASE 1, CASE 2, CASE 16) so a
# "refuse everything" change cannot pass this file.
#
# Nothing here builds, boots an emulator, or distributes. scripts/firebase-
# distribute.sh is never executed, by this suite or by the script under test.
#
# Exit 0 if every case passes; non-zero otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="${REPO_ROOT}/scripts/pipeline/phase-05-distribute.sh"

[[ -f "$GATE" ]] || { echo "FAIL: script under test not found: $GATE"; exit 1; }
for tool in jq git python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FAIL: '$tool' required"; exit 1; }
done

FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
# chmod back anything a case locked down, or rm -rf cannot clean up after it.
cleanup() { chmod -R u+rwX -- "$WORKDIR" 2>/dev/null; rm -rf -- "$WORKDIR"; }
trap cleanup EXIT

RUN_ID="2026-08-25T12-00-00Z"
VER_APP="1.3.20-1090"
VER_API="0.2.20-30"

# --------------------------------------------------------------------------
# Fixture construction (same shape as the sibling suite: a repo + a run whose
# report and evidence satisfy all eight conditions, which each case then
# breaks in exactly one way).
# --------------------------------------------------------------------------
_stub_firebase_distribute() {
  cat > "$1" <<'STUB'
#!/usr/bin/env bash
echo "FATAL: this stub must never be executed by the gate" >&2
exit 99
# Gate 1: monotonic version code
# Gate 2: CHANGELOG.md entry
# Gate 3: per-version snapshot file exists
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

# _qualifying <name> [--split-live-verify] -> echoes the fixture repo dir
_qualifying() {
  local name="$1" split="${2:-}"
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

  mkdir -p "${dir}/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
  mkdir -p "${dir}/.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app"

  local rdir="${dir}/.lava-ci-evidence/pipeline-runs/${RUN_ID}"
  mkdir -p "$rdir"
  python3 - "$rdir/report.json" "$sha" "$VER_APP" "$VER_API" "$RUN_ID" "$split" <<'PY'
import json, sys
out, sha, vapp, vapi, run_id, split = sys.argv[1:7]
an, ac = vapp.rsplit("-", 1)
bn, bc = vapi.rsplit("-", 1)
base = f".lava-ci-evidence/pipeline-runs/{run_id}"
phases = []
for i, p in enumerate(["precondition", "build", "test", "install_boot"]):
    phases.append({"name": p, "result": "PASS", "duration_seconds": 1,
                   "evidence_dir": f"{base}/phase-0{i}"})
if split == "--split-live-verify":
    # EXACTLY what a real run produces: phase-04-live-verify-api.sh and
    # phase-04-live-verify-api-app.sh each append their own "live_verify".
    phases.append({"name": "live_verify", "result": "PASS", "duration_seconds": 1,
                   "evidence_dir": f"{base}/phase-04"})
    phases.append({"name": "live_verify", "result": "PASS", "duration_seconds": 1,
                   "evidence_dir": f"{base}/phase-04-api-app"})
else:
    phases.append({"name": "live_verify", "result": "PASS", "duration_seconds": 1,
                   "evidence_dir": f"{base}/phase-04"})
phases.append({"name": "changelog_entry", "result": "PASS", "duration_seconds": 1,
               "evidence_dir": f"{base}/phase-05a"})
rep = {
  "run_id": run_id, "commit_sha": sha,
  "started_at": "2026-08-25T12:00:00Z", "completed_at": "2026-08-25T13:00:00Z",
  "outcome": "PASS", "phases": phases,
  "build_artifacts": [
    {"artifact_id": a, "version_name": (an if a.startswith("app-") else bn),
     "version_code": int(ac if a.startswith("app-") else bc),
     "build_output_path": f"build/{a}.apk", "built_from_commit": sha}
    for a in ["app-debug", "app-release", "api-app-debug", "api-app-release"]
  ],
  "evidence_summary": {"total": 6, "passed": 6, "failed": 0, "skipped": 0,
                       "rejected_by_anti_bluff": 0},
  "distributions": [], "submodule_advances": [],
}
with open(out, "w") as fh:
    json.dump(rep, fh, indent=2)
PY

  local v
  for v in app-debug app-release api-app-debug api-app-release; do
    _evidence "$dir" "$RUN_ID" "phase-02" "real-device-challenge" \
      "lava.app.challenges.Challenge00CrashSurvival.${v}" "PASS" \
      "scripts/run-challenge-matrix.sh --no-build --artifact ${v} --runner=containerized --container-runtime podman" \
      "cold-start survived on a containerized emulator for artifact ${v}; launcher activity reached RESUMED"
  done

  local app_phase="phase-04"
  [[ "$split" == "--split-live-verify" ]] && app_phase="phase-04-api-app"
  _evidence "$dir" "$RUN_ID" "phase-04" "hermetic-script" \
    "live.lava-api-go.health" "PASS" \
    "curl --fail against the running lava-api-go service health route" \
    "lava-api-go returned HTTP 200 with a JSON body reporting status=ok over its real TLS listener"
  _evidence "$dir" "$RUN_ID" "$app_phase" "hermetic-script" \
    "live.api-app.health" "PASS" \
    "curl --fail against the on-device api-app forwarded health route" \
    "api-app on-device surface answered its real health probe with HTTP 200 over the forwarded port"

  printf '%s' "$dir"
}

# _run <dir> [extra args...] -> sets G_RC and G_OUT
_run() {
  local dir="$1"; shift
  local out="${WORKDIR}/gate.log"
  ( cd "$dir" && bash "$GATE" "$RUN_ID" "$dir" \
      --firebase-distribute-script "${dir}/scripts/firebase-distribute.sh" \
      "$@" ) >"$out" 2>&1
  G_RC=$?
  G_OUT="$(cat "$out")"
}

# _refused_naming <label> <case-name>
_refused_naming() {
  local label="$1" name="$2"
  if [[ "$G_RC" -eq 2 ]] && grep -qF "REFUSED: ${label}" <<< "$G_OUT"; then
    pass "${name}: REFUSED (exit 2) naming ${label}"
  else
    fail "${name}: expected REFUSED (exit 2) naming ${label}; got exit ${G_RC}. Output:
${G_OUT}"
  fi
}

# _qualified <case-name>
_qualified() {
  if [[ "$G_RC" -eq 3 ]]; then
    pass "$1: QUALIFIED (exit 3)"
  else
    fail "$1: expected QUALIFIED (exit 3); got exit ${G_RC}. Output:
${G_OUT}"
  fi
}

echo "==========================================================="
echo "CASE 1 (POSITIVE CONTROL): a fully-qualifying run still passes"
echo "==========================================================="
echo "Without this, every fix below could be 'refuse unconditionally'."
D1="$(_qualifying ok)"; _run "$D1"; _qualified "qualifying run"

echo ""
echo "==========================================================="
echo "CASE 2 (POSITIVE CONTROL): the TWO live_verify entries a real run writes"
echo "==========================================================="
echo "phase-04-live-verify-api.sh and phase-04-live-verify-api-app.sh each"
echo "append a 'live_verify' entry; the api-app script's own header states"
echo "both 'legitimately coexist in phases[]'. RED: the gate refused, and"
echo "reported the phase as \"absent from the run report (found 2 entries)\"."
D2="$(_qualifying splitlv --split-live-verify)"; _run "$D2"
_qualified "two live_verify phase entries"

echo ""
echo "==========================================================="
echo "CASE 3: (D) satisfied by BUILD-TIME records -> REFUSED"
echo "==========================================================="
echo "Clause 8(D) requires the live surfaces to have been exercised 'against"
echo "actually-running services over their real interfaces'. A 'go build' and"
echo "a JVM unit test start no service. RED: exit 3, and the gate asserted"
echo "'real-interface evidence for both'."
D3="$(_qualifying ebuild)"
rm -f "${D3}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-04/hermetic-script/"*.json
_evidence "$D3" "$RUN_ID" "phase-01" "go-unit-integration" \
  "unit.lava-api-go.compile" "PASS" "go build ./cmd/lava-api-go" \
  "lava-api-go compiled to a static binary; no network or service was started"
_evidence "$D3" "$RUN_ID" "phase-01" "kotlin-unit" \
  "unit.api-app.viewmodel" "PASS" "./gradlew :api-app:testDebugUnitTest" \
  "api-app ViewModel unit test on the JVM; nothing was launched on a device"
_run "$D3"; _refused_naming "(D)" "build-time records standing in for live verification"

echo ""
echo "==========================================================="
echo "CASE 4: (D) one 'both surfaces' record + one go-only record -> REFUSED"
echo "==========================================================="
echo "The sibling suite's CASE 11b deletes both live records first, so its"
echo "guard is only exercised when the combined record is the ONLY match."
echo "RED: with a go-only record sorting first, the combined record was"
echo "credited to api-app and the double-count guard never fired."
D4="$(_qualifying eorder)"
rm -f "${D4}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-04/hermetic-script/"*.json
_evidence "$D4" "$RUN_ID" "phase-04" "hermetic-script" "a.live.lava-api-go.health" "PASS" \
  "curl --fail against the running lava-api-go health route" \
  "lava-api-go returned HTTP 200 over its real TLS listener"
_evidence "$D4" "$RUN_ID" "phase-04" "hermetic-script" "z.live.combined" "PASS" \
  "curl --fail against a health route" \
  "checked lava-api-go and api-app together in one single probe"
_run "$D4"; _refused_naming "(D)" "one combined record credited to a second surface"

echo ""
echo "==========================================================="
echo "CASE 5 (LOAD-BEARING): (C) two DEBUG records covering four variants"
echo "==========================================================="
echo "Clause 8(C): 'Release-variant device evidence is mandatory and is NOT"
echo "inferable from debug-variant evidence.' One emulator run cannot be"
echo "evidence for two different APKs. RED: two records, each an explicit"
echo "--artifact <debug> run, satisfied all four variants; exit 3."
D5="$(_qualifying ctwo)"
rm -f "${D5}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge/"*.json
_evidence "$D5" "$RUN_ID" "phase-02" "real-device-challenge" \
  "lava.app.challenges.Challenge00CrashSurvival.client" "PASS" \
  "scripts/run-challenge-matrix.sh --runner=containerized --container-runtime podman --artifact app-debug" \
  "cold-start survived on a containerized emulator; covers app-debug and app-release"
_evidence "$D5" "$RUN_ID" "phase-02" "real-device-challenge" \
  "lava.app.challenges.Challenge00CrashSurvival.apiapp" "PASS" \
  "scripts/run-challenge-matrix.sh --runner=containerized --container-runtime podman --artifact api-app-debug" \
  "cold-start survived on a containerized emulator; covers api-app-debug and api-app-release"
_run "$D5"; _refused_naming "(C)" "one device record credited to two artifact variants"

echo ""
echo "==========================================================="
echo "CASE 6: (C) records whose category is NOT real-device-challenge"
echo "==========================================================="
echo "Clause 8(C) names 'category: real-device-challenge'. RED: the gate"
echo "discovered device records by DIRECTORY PATH and never read the field,"
echo "so records declaring category kotlin-unit satisfied it; exit 3."
D6="$(_qualifying ccat)"
for f in "${D6}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/real-device-challenge/"*.json; do
  jq '.category = "kotlin-unit"' "$f" > "${f}.t" && mv "${f}.t" "$f"
done
_run "$D6"; _refused_naming "(C)" "device records declaring a non-device category"

echo ""
echo "==========================================================="
echo "CASE 7 (LOAD-BEARING): (H) an incident recording reauthorized=false"
echo "==========================================================="
echo "Clause 8(H): 're-authorization is an explicit act, never an inference'."
echo "RED: the check matched the KEY, not the VALUE, so writing down that the"
echo "operator has NOT re-authorized cleared the suspension; exit 3."
D7="$(_qualifying ireauth)"
mkdir -p "${D7}/.lava-ci-evidence/sixth-law-incidents"
cat > "${D7}/.lava-ci-evidence/sixth-law-incidents/2026-08-24-cold-start-fatal.json" <<'J'
{
  "incident": "clause-8-suspension",
  "trigger": "Crashlytics FATAL on first user-visible interaction, 1.3.19-1089",
  "covering_challenge_red_then_green": false,
  "reauthorized": false,
  "reauthorization_note": "operator has NOT re-authorized clause 8"
}
J
_run "$D7"; _refused_naming "(H)" "incident recording reauthorized=false"

echo ""
echo "==========================================================="
echo "CASE 8: (H) an ACTIVE marker inside an UNREADABLE directory"
echo "==========================================================="
echo "RED: [[ -f dir/ACTIVE ]] is false when dir is mode 000, so an active"
echo "suspension read as 'no suspension marker'; exit 3."
D8="$(_qualifying iunread)"
SD8="${D8}/.lava-ci-evidence/clause-8-suspension"
mkdir -p "$SD8"
printf 'suspended after a Crashlytics FATAL on 1.3.19-1089 cold start\n' > "${SD8}/ACTIVE"
chmod 000 "$SD8"
_run "$D8"
chmod 755 "$SD8"
_refused_naming "(H)" "unreadable suspension directory"

echo ""
echo "==========================================================="
echo "CASE 9: (H) the ACTIVE marker is a directory, not a file"
echo "==========================================================="
echo "RED: -f is false for a directory, so the marker read as absent; exit 3."
D9="$(_qualifying iactivedir)"
mkdir -p "${D9}/.lava-ci-evidence/clause-8-suspension/ACTIVE"
printf 'suspended\n' > "${D9}/.lava-ci-evidence/clause-8-suspension/ACTIVE/reason.txt"
_run "$D9"; _refused_naming "(H)" "ACTIVE marker present but not a regular file"

echo ""
echo "==========================================================="
echo "CASE 10 (LOAD-BEARING): --schema must not be an escape hatch"
echo "==========================================================="
echo "The gate's header claims every option 'selects WHERE TO LOOK' and none"
echo "'can change a verdict'. RED: a report carrying a property the schema"
echo "forbids was REFUSED by default and QUALIFIED (exit 3) when --schema"
echo "pointed at a JSON array — the validator crashed, printed no ERR line,"
echo "and the gate reported 'report schema-valid'."
D10="$(_qualifying aschema)"
R10="${D10}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json"
jq '. + {"gate_override":"approved"}' "$R10" > "${R10}.t" && mv "${R10}.t" "$R10"
_run "$D10"
_refused_naming "(A)" "forbidden extra property, default schema"
printf '[]\n' > "${WORKDIR}/notaschema.json"   # OUTSIDE the fixture repo: keeps (F) clean
_run "$D10" --schema "${WORKDIR}/notaschema.json"
_refused_naming "(A)" "--schema pointed at a non-object JSON document"

echo ""
echo "==========================================================="
echo "CASE 11: an Evidence Record with no anti_bluff_status -> REFUSED"
echo "==========================================================="
echo "Clause 8(B): 'zero Evidence Records carry an anti_bluff_status other"
echo "than validated'. A MISSING field is other than validated — it means the"
echo "independent validator never saw the record. RED: exit 3, and the gate"
echo "reported 'N record(s) all validated'."
D11="$(_qualifying unvalidated)"
_evidence "$D11" "$RUN_ID" "phase-02" "kotlin-unit" "unit.search.parser" "PASS" \
  "./gradlew :core:search:testDebugUnitTest" "parser returned 12 rows with the expected titles"
F11="${D11}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/kotlin-unit/unit.search.parser.json"
jq 'del(.anti_bluff_status)' "$F11" > "${F11}.t" && mv "${F11}.t" "$F11"
_run "$D11"; _refused_naming "FR-009" "record with no anti_bluff_status"

echo ""
echo "==========================================================="
echo "CASE 12: an Evidence Record whose result is outside the enum"
echo "==========================================================="
echo "evidence-record.schema.json enumerates PASS|FAIL|SKIPPED. RED: the gate"
echo "compared only against the literal 'FAIL', so 'ERROR' counted as fine."
D12="$(_qualifying badresult)"
_evidence "$D12" "$RUN_ID" "phase-02" "kotlin-unit" "unit.search.parser" "PASS" \
  "./gradlew :core:search:testDebugUnitTest" "parser returned 12 rows with the expected titles"
F12="${D12}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/kotlin-unit/unit.search.parser.json"
jq '.result = "ERROR"' "$F12" > "${F12}.t" && mv "${F12}.t" "$F12"
_run "$D12"; _refused_naming "FR-009" "record with an out-of-enum result"

echo ""
echo "==========================================================="
echo "CASE 13: an Evidence Record that is not JSON at all"
echo "==========================================================="
echo "RED: jq's error was swallowed, every field read as empty, the record"
echo "was silently skipped -- and then COUNTED in 'N record(s) all validated'."
D13="$(_qualifying notjson)"
_evidence "$D13" "$RUN_ID" "phase-02" "kotlin-unit" "unit.search.parser" "PASS" \
  "./gradlew :core:search:testDebugUnitTest" "parser returned 12 rows with the expected titles"
printf 'this is not json\n' \
  > "${D13}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-02/kotlin-unit/unit.search.parser.json"
_run "$D13"; _refused_naming "FR-009" "record that does not parse as JSON"
# (B) must refuse on it too, and must NOT still be claiming "all validated":
# FR-009 refusing the run does not license (B) to assert a falsehood about a
# file it could not read.
if grep -qF "REFUSED: (B)" <<< "$G_OUT" && ! grep -qF "record(s) all validated" <<< "$G_OUT"; then
  pass "unreadable record: (B) refuses on it too and drops the 'all validated' claim"
else
  fail "unreadable record: (B) did not refuse, or still claimed 'all validated'. Output:
${G_OUT}"
fi

echo ""
echo "==========================================================="
echo "CASE 14: an authorization the gate cannot RECORD is not an authorization"
echo "==========================================================="
echo "RED: with the run directory read-only the gate warned, printed"
echo "'verdict artifact: <path>' for a file that did not exist, and still"
echo "returned QUALIFIED (exit 3) — clause 8's audit trail silently absent."
D14="$(_qualifying vwrite)"
RD14="${D14}/.lava-ci-evidence/pipeline-runs/${RUN_ID}"
chmod a-w "$RD14"
_run "$D14"
chmod u+w "$RD14"
if [[ "$G_RC" -ne 3 ]]; then
  pass "unwritable verdict artifact: did NOT return QUALIFIED (exit ${G_RC})"
else
  fail "unwritable verdict artifact: returned QUALIFIED (exit 3) with no verdict recorded. Output:
${G_OUT}"
fi

echo ""
echo "==========================================================="
echo "CASE 15: (B) must not report PASS having examined zero records"
echo "==========================================================="
echo "RED: with every record deleted, (B) reported PASS — 'all validated' over"
echo "an empty set. FR-009 refused the run, so this was a false statement in"
echo "the artifact rather than a wrong exit code; it is still a check"
echo "reporting success having examined nothing."
D15="$(_qualifying norecords)"
find "${D15}/.lava-ci-evidence/pipeline-runs/${RUN_ID}" -name '*.json' ! -name report.json -delete
_run "$D15"
if [[ "$G_RC" -eq 2 ]] && grep -qF "REFUSED: (B)" <<< "$G_OUT"; then
  pass "zero Evidence Records: (B) itself refuses rather than reporting PASS"
else
  fail "zero Evidence Records: (B) did not refuse. Output:
${G_OUT}"
fi

echo ""
echo "==========================================================="
echo "CASE 16 (POSITIVE CONTROL): a genuine re-authorization DOES clear (H)"
echo "==========================================================="
echo "The CASE 7 fix must reject a false/absent value, not every incident."
D16="$(_qualifying ireauthok)"
mkdir -p "${D16}/.lava-ci-evidence/sixth-law-incidents"
cat > "${D16}/.lava-ci-evidence/sixth-law-incidents/2026-08-24-cold-start-fatal.json" <<'J'
{
  "incident": "clause-8-suspension",
  "trigger": "Crashlytics FATAL on first user-visible interaction, 1.3.19-1089",
  "covering_challenge_red_then_green": true,
  "reauthorized": "operator, in writing, 2026-08-24: clause 8 re-authorized"
}
J
_run "$D16"; _qualified "incident with a written operator re-authorization"

echo ""
echo "==========================================================="
echo "CASE 17: --help must actually print the usage block"
echo "==========================================================="
echo "The handler used to slice a hardcoded '1,120p' window of this file's"
echo "own header; growing the header past 120 lines made --help print"
echo "NOTHING while still exiting 2, so the failure was invisible."
H_OUT="$(bash "$GATE" --help 2>&1)"; H_RC=$?
if [[ "$H_RC" -eq 2 ]] && grep -qF '# Usage:' <<< "$H_OUT" && grep -qF -- '--suspension-dir' <<< "$H_OUT"; then
  pass "--help prints the usage block and exits 2"
else
  fail "--help produced exit ${H_RC} and $(wc -l <<< "$H_OUT") line(s) without the usage block. Output:
${H_OUT}"
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
echo "==========================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi
