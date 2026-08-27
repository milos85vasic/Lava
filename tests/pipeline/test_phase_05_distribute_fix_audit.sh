#!/usr/bin/env bash
# tests/pipeline/test_phase_05_distribute_fix_audit.sh
#
# INDEPENDENT AUDIT OF THE FIX CODE ITSELF, 2026-08-25.
#
# scripts/pipeline/phase-05-distribute.sh grew 1048 -> 1464 lines while its
# own author audited it and repaired nine defects. Those 416 lines of repair
# were written and verified by the same agent that wrote them. This suite is
# the second pair of eyes on THAT code, and it found seven more defects --
# three of which reach GATE QUALIFIED (exit 3) on a run that must refuse.
#
# Every case below was OBSERVED failing against the pre-fix revision; the
# recorded RED is quoted in the case header. Cases 1P/2P/5P/6P/7P are the
# POSITIVE controls: without them, "refuse unconditionally" would pass this
# suite, which is the way an anti-bluff gate suite becomes a bluff itself.
#
# Run:  bash tests/pipeline/test_phase_05_distribute_fix_audit.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="${REPO_ROOT}/scripts/pipeline/phase-05-distribute.sh"
RUN_ID="2026-08-25T12-00-00Z"
VER_APP="1.3.20-1090"
VER_API="0.2.20-30"
FAILURES=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

WORKDIR="$(mktemp -d)"
cleanup() { chmod -R u+rwX -- "$WORKDIR" 2>/dev/null; rm -rf -- "$WORKDIR"; }
trap cleanup EXIT

for tool in jq git python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "SKIP: '$tool' not on PATH"; exit 0; }
done
[[ -f "$GATE" ]] || { echo "FAIL: gate not found at ${GATE}"; exit 1; }

# --------------------------------------------------------------------------
# Fixture builders (same shapes as the vacuity suite, so a case that passes
# here is passing against a run the gate would really see).
# --------------------------------------------------------------------------
_stub_cycle_coverage() {
  cat > "$1" <<STUB
#!/usr/bin/env bash
CHAN=""
while [[ \$# -gt 0 ]]; do
  case "\$1" in --channel=*) CHAN="\${1#*=}";; esac
  shift
done
[[ -n "\$CHAN" ]] || { echo "FATAL: --channel required" >&2; exit 2; }
echo "stub cycle-coverage: coverage map and evidence checked"
exit 0
STUB
  chmod +x "$1"
}
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
# _skeleton <dir> [--split-live-verify] — repo + report, no Evidence Records
_skeleton() {
  local dir="$1" split="${2:-}"
  mkdir -p "${dir}/scripts"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Fixture"
  printf '.lava-ci-evidence/\n' > "${dir}/.gitignore"
  _stub_cycle_coverage "${dir}/scripts/check-cycle-coverage.sh"
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
an, ac = vapp.rsplit("-", 1); bn, bc = vapi.rsplit("-", 1)
base = f".lava-ci-evidence/pipeline-runs/{run_id}"
phases = [{"name": p, "result": "PASS", "duration_seconds": 1,
           "evidence_dir": f"{base}/phase-0{i}"}
          for i, p in enumerate(["precondition", "build", "test", "install_boot"])]
phases.append({"name": "live_verify", "result": "PASS", "duration_seconds": 1,
               "evidence_dir": f"{base}/phase-04"})
if split == "--split-live-verify":
    phases.append({"name": "live_verify", "result": "PASS", "duration_seconds": 1,
                   "evidence_dir": f"{base}/phase-04-api-app"})
phases.append({"name": "changelog_entry", "result": "PASS", "duration_seconds": 1,
               "evidence_dir": f"{base}/phase-05a"})
json.dump({
  "run_id": run_id, "commit_sha": sha,
  "started_at": "2026-08-25T12:00:00Z", "completed_at": "2026-08-25T13:00:00Z",
  "outcome": "PASS", "phases": phases,
  "build_artifacts": [
    {"artifact_id": a, "version_name": (an if a.startswith("app-") else bn),
     "version_code": int(ac if a.startswith("app-") else bc),
     "build_output_path": f"build/{a}.apk", "built_from_commit": sha}
    for a in ["app-debug", "app-release", "api-app-debug", "api-app-release"]],
  "evidence_summary": {"total": 6, "passed": 6, "failed": 0, "skipped": 0,
                       "rejected_by_anti_bluff": 0},
  "distributions": [], "submodule_advances": [],
  # RETIRED 2026-08-26 (LVA-147): the top-level "residual-gap" property this
  # fixture used to emit is NOT in pipeline-run-report.schema.json, whose
  # additionalProperties is false. Condition (A) refuses the report for
  # carrying it and condition (G) refuses it by name, so every positive
  # control in this suite refused for a fixture artifact rather than for
  # anything the case was about. The disclosure it carried now lives in
  # build_artifacts[].build_output_path above, which condition (G) reads.
}, open(out, "w"), indent=2)
PY
}
_honest_device() {
  local dir="$1" v
  for v in app-debug app-release api-app-debug api-app-release; do
    _evidence "$dir" "$RUN_ID" "phase-02" "real-device-challenge" \
      "lava.app.challenges.Challenge00CrashSurvival.${v}" "PASS" \
      "scripts/run-challenge-matrix.sh --no-build --artifact ${v} --runner=containerized --container-runtime podman" \
      "cold-start survived on a containerized emulator for artifact ${v}; launcher activity reached RESUMED"
  done
}
_honest_live() {
  local dir="$1" split="${2:-}" app_phase="phase-04"
  [[ "$split" == "--split-live-verify" ]] && app_phase="phase-04-api-app"
  _evidence "$dir" "$RUN_ID" "phase-04" "hermetic-script" \
    "live.lava-api-go.health" "PASS" \
    "curl --fail against the running lava-api-go service health route" \
    "lava-api-go returned HTTP 200 with a JSON body reporting status=ok over its real TLS listener"
  _evidence "$dir" "$RUN_ID" "$app_phase" "hermetic-script" \
    "live.api-app.health" "PASS" \
    "curl --fail against the on-device api-app forwarded health route" \
    "api-app on-device surface answered its real health probe with HTTP 200 over the forwarded port"
}
_qualifying() { _skeleton "$1" "${2:-}"; _honest_device "$1"; _honest_live "$1" "${2:-}"; }

G_RC=0; G_OUT=""
_run() {
  local dir="$1"; shift
  local out="${WORKDIR}/gate.log"
  # RE-LETTERED 2026-08-26: `--cycle-coverage-script` was WITHDRAWN together
  # with the former condition (D). The gate now refuses it as an unknown
  # argument, and refuses BEFORE evaluating anything -- so every case in this
  # suite exited 2 at argument parsing, and each one's `expected REFUSED
  # naming (X)` read as an ordinary condition failure. A stale flag that makes
  # a whole suite fail for a reason none of its cases is about is exactly the
  # confound this suite's own _recommit note warns against.
  ( cd "$dir" && bash "$GATE" "$RUN_ID" "$dir" \
      --firebase-distribute-script "${dir}/scripts/firebase-distribute.sh" \
      "$@" ) >"$out" 2>&1
  G_RC=$?
  G_OUT="$(cat "$out")"
}
_refused_naming() {
  local label="$1" name="$2"
  if [[ "$G_RC" -eq 2 ]] && grep -qF "REFUSED: ${label}" <<< "$G_OUT"; then
    pass "${name}: REFUSED (exit 2) naming ${label}"
  else
    fail "${name}: expected REFUSED (exit 2) naming ${label}; got exit ${G_RC}. Output:
${G_OUT}"
  fi
}
_qualified() {
  if [[ "$G_RC" -eq 3 ]]; then pass "$1: QUALIFIED (exit 3)"
  else fail "$1: expected QUALIFIED (exit 3); got exit ${G_RC}. Output:
${G_OUT}"; fi
}

echo "==========================================================="
echo "CASE 1P (POSITIVE CONTROL): an honest run still qualifies"
echo "==========================================================="
D="${WORKDIR}/pos"; _qualifying "$D" --split-live-verify
_run "$D"; _qualified "1P honest run"

echo
echo "==========================================================="
echo "CASE 1 (was: GATE QUALIFIED, exit 3) — condition (C):"
echo "an --artifact app-debug device run laundered as api-app-debug."
echo "_names_variant suppresses 'app-debug' whenever 'api-app-debug' is also"
echo "present, so _variants_named counted such a record as naming ONE variant"
echo "and the >1 guard never fired. Four records that all ran a client-app"
echo "artifact reported 'all 4 Android variants ... have a PASSing record',"
echo "and ZERO :api-app device runs existed anywhere in the run."
echo "==========================================================="
D="${WORKDIR}/c-launder"; _skeleton "$D" --split-live-verify; _honest_live "$D" --split-live-verify
for v in app-debug app-release; do
  _evidence "$D" "$RUN_ID" "phase-02" "real-device-challenge" "ch.honest.${v}" "PASS" \
    "scripts/run-challenge-matrix.sh --no-build --artifact ${v} --runner=containerized --container-runtime podman" \
    "cold-start survived on a containerized emulator for artifact ${v}"
  _evidence "$D" "$RUN_ID" "phase-02" "real-device-challenge" "ch.launder.${v}" "PASS" \
    "scripts/run-challenge-matrix.sh --no-build --artifact ${v} --runner=containerized --container-runtime podman" \
    "cold-start survived on a containerized emulator; per the release notes this also covers api-${v}"
done
_run "$D"; _refused_naming "(C)" "1 debug-artifact record laundered as the api-app variant"

echo
echo "==========================================================="
echo "CASE 2 (was: GATE QUALIFIED, exit 3) — --schema is STILL an"
echo "escape hatch. The repair rejected a schema that is not a JSON OBJECT,"
echo "but an EMPTY object defines no constraints just as completely. A report"
echo "carrying a property the real schema forbids went REFUSED -> QUALIFIED"
echo "with --schema pointed at a file containing exactly '{}', and (A) then"
echo "asserted 'report schema-valid'."
echo "==========================================================="
D="${WORKDIR}/schema"; _qualifying "$D" --split-live-verify
python3 - "${D}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json" <<'PY'
import json, sys
p = sys.argv[1]; r = json.load(open(p)); r["totally-unknown-prop"] = "x"
json.dump(r, open(p, "w"), indent=2)
PY
_run "$D"; _refused_naming "(A)" "2a real schema rejects the forbidden property"
for empty_schema in '{}' '{"required":[]}' '{"properties":{}}' '{"title":"anything"}'; do
  printf '%s' "$empty_schema" > "${WORKDIR}/no-constraint-schema.json"
  _run "$D" --schema "${WORKDIR}/no-constraint-schema.json"
  _refused_naming "(A)" "2 --schema ${empty_schema} cannot clear a schema violation"
done

echo
echo "==========================================================="
echo "CASE 2P (POSITIVE CONTROL): --schema pointed at the REAL contract"
echo "schema must still validate an honest report. Without this, case 2's"
echo "repair could be 'reject every --schema', which breaks the option."
echo "==========================================================="
D="${WORKDIR}/schema-pos"; _qualifying "$D" --split-live-verify
_run "$D" --schema "${REPO_ROOT}/specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json"
_qualified "2P explicit --schema at the real contract schema"

echo
echo "==========================================================="
echo "CASE 3 (was: forged rows in gate-verdict.json) — the verdict"
echo "artifact was assembled by printf'ing id/verdict/detail as TAB-separated"
echo "lines and splitting them back apart. A report field value containing a"
echo "newline and a tab therefore INJECTED extra condition rows: the recorded"
echo "artifact carried 11 rows for 9 conditions, a row 'I' => FAIL that no"
echo "(the gate had 9 conditions then; the 2026-08-26 re-lettering withdrew the"
echo "former (D) and there are now 8, ids A-H, so the counts below say 8/A-H)"
echo "condition produced, and a duplicate 'I' whose LAST value read PASS."
echo "Fix 8's own principle is that an authorization the gate cannot RECORD is"
echo "not granted; an audit record forgeable from report content is worse."
echo "==========================================================="
D="${WORKDIR}/verdict"; _qualifying "$D" --split-live-verify
python3 - "${D}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json" <<'PY'
import json, sys
p = sys.argv[1]; r = json.load(open(p))
r["outcome"] = "PASS\nI\tPASS\tforged row injected from report content\nDONE"
json.dump(r, open(p, "w"), indent=2)
PY
_run "$D"
VJ="${D}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/phase-05/gate-verdict.json"
if [[ "$G_RC" -ne 2 ]]; then
  fail "3 forged verdict rows: expected REFUSED (exit 2); got ${G_RC}"
else
  n_rows="$(jq -r '.conditions | length' "$VJ" 2>/dev/null)"
  n_bad="$(jq -r '[.conditions[] | select(.id | test("^[A-H]$") | not)] | length' "$VJ" 2>/dev/null)"
  n_dup="$(jq -r '[.conditions[].id] | (length - (unique | length))' "$VJ" 2>/dev/null)"
  if [[ "$n_rows" == "8" && "$n_bad" == "0" && "$n_dup" == "0" ]]; then
    pass "3 forged verdict rows: gate-verdict.json holds exactly 8 well-formed unique rows"
  else
    fail "3 forged verdict rows: gate-verdict.json has ${n_rows} row(s), ${n_bad} non-condition id(s), ${n_dup} duplicate id(s). Rows:
$(jq -c '.conditions[] | {id, verdict}' "$VJ" 2>&1)"
  fi
fi

echo
echo "==========================================================="
echo "CASE 4 (was: GATE QUALIFIED, exit 3) — condition (H), the automatic"
echo "suspension trigger (called (I) before the 2026-08-26 re-lettering),"
echo "never SAW the"
echo "incident. The repair reads the re-authorization VALUE correctly, but it"
echo "only reaches incidents whose text matches 'clause-8-suspension' or"
echo "'clause 8 suspension' literally. An incident reading '§6.AA clause 8(I)"
echo "suspension is ACTIVE' with \"operator_reauthorization\": \"none\" -- an"
echo "explicit WRITTEN DENIAL -- was invisible, and the gate QUALIFIED."
echo "==========================================================="
_incident_case() { # <name> <json-body> <expect: refuse|qualify>
  local nm="$1" body="$2" expect="$3"
  local d="${WORKDIR}/inc-${nm}"
  _qualifying "$d" --split-live-verify
  mkdir -p "${d}/.lava-ci-evidence/sixth-law-incidents"
  printf '%s\n' "$body" > "${d}/.lava-ci-evidence/sixth-law-incidents/2026-08-01-x.json"
  _run "$d"
  if [[ "$expect" == "refuse" ]]; then _refused_naming "(H)" "4 ${nm}"
  else _qualified "4 ${nm}"; fi
}
_incident_case "clause8-is-SUSPENDED-denied" \
  '{"summary":"Per §6.AA clause 8(I) suspension is ACTIVE for this cycle","operator_reauthorization":"none"}' refuse
_incident_case "suspends-clause-8-no-reauth" \
  '{"summary":"This incident automatically suspends clause 8 until the operator re-authorizes in writing"}' refuse
_incident_case "canonical-hyphenated-no-reauth" \
  '{"summary":"clause-8-suspension raised after a FATAL cold-start crash"}' refuse

echo
echo "==========================================================="
echo "CASE 4P (POSITIVE CONTROLS): broadening (H)'s reach must not start"
echo "refusing runs for incidents that are NOT clause-8 suspensions, and a"
echo "genuine written re-authorization must still clear one."
echo "==========================================================="
_incident_case "host-suspend-incident-unrelated" \
  '{"summary":"Class III perceived host instability: no logind transition, no suspend, no signout, uptime continuous"}' qualify
_incident_case "clause-8-mentioned-but-not-suspended" \
  '{"summary":"§6.AA clause 8 conditions (A)-(I) were all evaluated; this incident is about a flaky fixture"}' qualify
_incident_case "clause8-suspension-REAUTHORIZED" \
  '{"summary":"clause 8 suspension raised after a FATAL cold-start crash","reauthorized":true}' qualify
# The real incident corpus must not start refusing the gate either.
D="${WORKDIR}/inc-real-corpus"; _qualifying "$D" --split-live-verify
if [[ -d "${REPO_ROOT}/.lava-ci-evidence/sixth-law-incidents" ]]; then
  mkdir -p "${D}/.lava-ci-evidence/sixth-law-incidents"
  cp "${REPO_ROOT}/.lava-ci-evidence/sixth-law-incidents/"*.json \
     "${D}/.lava-ci-evidence/sixth-law-incidents/" 2>/dev/null
fi
_run "$D"; _qualified "4P the real sixth-law-incidents corpus does not refuse the gate"

echo
echo "==========================================================="
echo "CASE 5 (was: GATE REFUSED, exit 2, wrongly) — condition (D)"
echo "over-corrected. Crediting a record that names BOTH live surfaces to"
echo "NEITHER refuses an honest run: the :api-app IS a client of lava-api-go,"
echo "so an api-app live-verification summary naturally names the service it"
echo "reached. With a separate, unambiguous lava-api-go record also present,"
echo "the gate reported \"live surface 'api-app' has no PASSing"
echo "live-verification Evidence Record of its own\"."
echo "==========================================================="
D="${WORKDIR}/e-honest"; _skeleton "$D" --split-live-verify; _honest_device "$D"
_evidence "$D" "$RUN_ID" "phase-04" "hermetic-script" "live.lava-api-go.health" "PASS" \
  "curl --fail against the running lava-api-go service health route" \
  "lava-api-go returned HTTP 200 with a JSON body reporting status=ok over its real TLS listener"
_evidence "$D" "$RUN_ID" "phase-04-api-app" "hermetic-script" "live.api-app.providers" "PASS" \
  "adb shell am start + forwarded probe of the api-app providers screen" \
  "the api-app on the containerized emulator reached the running lava-api-go service over the forwarded port and rendered 12 providers"
_run "$D"; _qualified "5 an api-app live record naming the service it reached"

echo
echo "==========================================================="
echo "CASE 5P (POSITIVE CONTROLS): the anti-bluff property (D) exists for"
echo "must survive. ONE record is never TWO surfaces exercised, and"
echo "build-time evidence outside a live_verify evidence_dir never counts."
echo "==========================================================="
D="${WORKDIR}/e-single"; _skeleton "$D" --split-live-verify; _honest_device "$D"
_evidence "$D" "$RUN_ID" "phase-04" "hermetic-script" "live.combined" "PASS" \
  "one probe" \
  "this single probe covers both the lava-api-go service and the on-device api-app"
_run "$D"; _refused_naming "(D)" "5P a lone both-naming record is not two surfaces"

D="${WORKDIR}/e-ambig-only"; _skeleton "$D" --split-live-verify; _honest_device "$D"
_evidence "$D" "$RUN_ID" "phase-04" "hermetic-script" "live.combined.a" "PASS" \
  "probe a" "probe a exercised the lava-api-go service and the api-app together"
_evidence "$D" "$RUN_ID" "phase-04-api-app" "hermetic-script" "live.combined.b" "PASS" \
  "probe b" "probe b exercised the lava-api-go service and the api-app together"
_run "$D"; _refused_naming "(D)" "5P two both-naming records, neither unambiguous"

D="${WORKDIR}/e-buildtime"; _skeleton "$D" --split-live-verify; _honest_device "$D"
_evidence "$D" "$RUN_ID" "phase-01" "go-unit-integration" "build.go" "PASS" \
  "go build ./cmd/lava-api-go" "lava-api-go compiled"
_evidence "$D" "$RUN_ID" "phase-02" "kotlin-unit" "unit.apiapp" "PASS" \
  ":api-app:testDebugUnitTest" "api-app unit tests passed"
_run "$D"; _refused_naming "(D)" "5P build-time records outside a live_verify dir"

echo
echo "==========================================================="
echo "CASE 6 (was: GATE REFUSED, exit 2, wrongly) — condition (F)"
echo "carries the SAME defect FR-009 was repaired for and was not swept."
echo "It reads 'select(.name==\"precondition\") | .result' unaggregated, so two"
echo "entries concatenate into a multi-line 'PASS\\nPASS' which compares"
echo "unequal to PASS. The FR-009 repair generalised the count; (F) did not."
echo "==========================================================="
D="${WORKDIR}/g-dup"; _qualifying "$D" --split-live-verify
python3 - "${D}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json" <<'PY'
import json, sys
p = sys.argv[1]; r = json.load(open(p)); ph = r["phases"]
e = [x for x in ph if x["name"] == "precondition"][0]
ph.insert(ph.index(e) + 1, dict(e))
json.dump(r, open(p, "w"), indent=2)
PY
_run "$D"; _qualified "6 precondition recorded twice, both PASS"

echo
echo "==========================================================="
echo "CASE 6P (POSITIVE CONTROLS): (F) must still refuse a precondition"
echo "that is absent, or that has ANY non-PASS entry among several."
echo "==========================================================="
D="${WORKDIR}/g-none"; _qualifying "$D" --split-live-verify
python3 - "${D}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json" <<'PY'
import json, sys
p = sys.argv[1]; r = json.load(open(p))
r["phases"] = [x for x in r["phases"] if x["name"] != "precondition"]
json.dump(r, open(p, "w"), indent=2)
PY
_run "$D"; _refused_naming "(F)" "6P precondition absent"

D="${WORKDIR}/g-onefail"; _qualifying "$D" --split-live-verify
python3 - "${D}/.lava-ci-evidence/pipeline-runs/${RUN_ID}/report.json" <<'PY'
import json, sys
p = sys.argv[1]; r = json.load(open(p)); ph = r["phases"]
e = [x for x in ph if x["name"] == "precondition"][0]
f = dict(e); f["result"] = "FAIL"; ph.insert(ph.index(e), f)
json.dump(r, open(p, "w"), indent=2)
PY
_run "$D"; _refused_naming "(F)" "6P one of two precondition entries is FAIL"

echo
echo "==========================================================="
echo "CASE 7 (was: silently truncated / whole-file dump) — --help."
echo "The hardcoded '1,120p' window was replaced by an awk range that still"
echo "ends at the first horizontal rule. Adding a separator rule INSIDE the"
echo "Usage block silently dropped the exit-code section -- the same class of"
echo "defect, one edit away. Shortening the rules dumped 1316 lines."
echo "==========================================================="
_help_of() { # <script-path> -> sets H_OUT / H_RC
  H_OUT="$(bash "$1" --help 2>&1)"; H_RC=$?
}
_help_ok() { # <label> <script-path>
  _help_of "$2"
  local n; n="$(wc -l <<< "$H_OUT")"
  if [[ "$H_RC" -eq 2 ]] \
     && grep -qF -- '--schema <path>' <<< "$H_OUT" \
     && grep -qF -- 'Exit codes:' <<< "$H_OUT" \
     && grep -qF -- '3 - GATE QUALIFIED' <<< "$H_OUT" \
     && [[ "$n" -lt 60 ]]; then
    pass "7 --help ${1}: complete usage block (${n} lines, exit ${H_RC})"
  else
    fail "7 --help ${1}: incomplete or runaway (${n} lines, exit ${H_RC}). Output:
${H_OUT}"
  fi
}
_help_ok "as shipped" "$GATE"
sed 's|^# Exit codes:|# ---------------------------------------------------------------------------\n# Exit codes:|' \
    "$GATE" > "${WORKDIR}/help-extra-rule.sh"
_help_ok "with a separator rule inside the block" "${WORKDIR}/help-extra-rule.sh"
awk 'BEGIN{u=0} /^# Usage:/{u=1} u && /^# -{10,}$/ && !/^# Usage:/{print "# -----"; next} {print}' \
    "$GATE" > "${WORKDIR}/help-short-rule.sh"
_help_ok "with shortened rules" "${WORKDIR}/help-short-rule.sh"

echo ""
echo "==========================================================="
echo "EXAMINED-COUNT (anti-vacuity)"
echo "==========================================================="
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

echo
echo "==========================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CASES PASS — the fix-code audit findings are closed."
  exit 0
fi
echo "${FAILURES} CASE(S) FAILED"
exit 1
