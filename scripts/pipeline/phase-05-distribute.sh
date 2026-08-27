#!/usr/bin/env bash
# scripts/pipeline/phase-05-distribute.sh — tasks.md T043, GATE-ONLY SLICE.
#
# This script is the §6.AA clause 8 REFUSAL GATE. Today it is ONLY that.
# It decides whether a pipeline run has earned the clause 8 "Pipeline
# Distribution Path" permission — and then, deliberately, does not
# distribute anything. See "WHAT THIS SCRIPT DOES NOT DO" below.
#
# ---------------------------------------------------------------------------
# WHY THE GUARD SHIPS BEFORE THE DISTRIBUTION IT GUARDS
# ---------------------------------------------------------------------------
# §6.AA clause 8 lets this pipeline go debug -> release with no operator
# pause, IF AND ONLY IF eight conjunctive conditions (A)-(H) hold. It closes
# with its own standing note:
#
#   "Any future reading of this clause that treats a green run report as
#    sufficient WITHOUT condition (C) having genuinely executed -- against
#    the release-variant artifact specifically, never inferred from the
#    debug variant -- is the §6.J bluff class this project has recorded
#    three times (§6.Z, §6.AB, §6.AK), and is a violation of this clause,
#    not a use of it."
#
# RE-LETTERED 2026-08-26 (§6.AA amendment, constitution 3.0.0). The former
# condition (D) -- cycle-coverage on BOTH channels -- was WITHDRAWN, not
# renumbered, and former (E)-(I) became (D)-(H). THERE IS NO CONDITION (I).
# Every letter in this file, including in the historical audit narratives
# below, has been mapped forward so a reader lands on the evaluator that
# actually exists: (E)->(D), (F)->(E), (G)->(F), (H)->(G), (I)->(H).
# (A), (B) and (C) are unchanged. Anything written before 2026-08-26 that
# cites clause 8 by letter must be read against that map.
#
# A permission that broad is only safe if the thing enforcing it exists,
# is tested, and refuses by default FIRST. So the guard lands on its own,
# with its own hermetic suite (tests/pipeline/test_phase_05_distribute_gate.sh),
# and the distribute call is deferred to a later task.
#
# ---------------------------------------------------------------------------
# WHAT THIS SCRIPT DOES NOT DO (deliberate, not unfinished-by-accident)
# ---------------------------------------------------------------------------
#   - It NEVER invokes scripts/firebase-distribute.sh. Condition (E) is
#     verified by STATIC INSPECTION of that script. Executing it would
#     attempt a real upload to Firebase App Distribution.
#   - It writes NO Distribution Record and mutates report.json in NO way.
#     It is a read-only reader of the run, plus one verdict artifact of its
#     own at <run>/phase-05/gate-verdict.json.
#   - It is NOT wired into scripts/pipeline-build-test-distribute.sh.
#
# ---------------------------------------------------------------------------
# NO ESCAPE HATCH (§6.Z clause 6)
# ---------------------------------------------------------------------------
# Every option below selects WHERE TO LOOK. None of them can change a
# verdict. There is deliberately no --force, --bypass, --skip, --override or
# equivalent, and none may be added: §6.Z clause 6 says no bypass flag exists
# and none may be added, and a gate with an escape hatch is not a gate. An
# unrecognized argument is a hard error, never silently ignored.
#
# A condition this script cannot evaluate REFUSES. It never passes on the
# grounds that it could not tell.
#
# ---------------------------------------------------------------------------
# INDEPENDENT AUDIT, 2026-08-25 — WHAT WAS WRONG AND IS NOW FIXED
# ---------------------------------------------------------------------------
# The first revision of this file was verified only by its own author. An
# independent audit built hermetic fixtures for each condition and found EIGHT
# ways to reach "GATE QUALIFIED" that should have refused, plus one way to
# refuse a run that should have qualified. Each is now a regression case in
# tests/pipeline/test_phase_05_distribute_gate_vacuity.sh with a recorded RED.
# They are listed here because the shapes recur, not as a confession:
#
#   1. (D) counted ANY record in the run. A `go build ./cmd/lava-api-go`
#      record and a `:api-app:testDebugUnitTest` record — neither of which
#      starts a service — satisfied "live verification of both surfaces".
#      FIXED: only records under a live_verify phase's own evidence_dir count.
#   2. (C) bound records to variants by token alone, so TWO records, each an
#      explicit `--artifact <debug>` run whose summary added "covers
#      <release>", satisfied all four variants. That is the §6.Z forensic
#      anchor exactly. FIXED: a record naming more than one variant counts
#      for none.
#   3. (C) discovered device records by DIRECTORY PATH and never read
#      `.category`, so four records declaring `category: kotlin-unit`
#      satisfied it. FIXED: the field is read.
#   4. (H) grepped for a re-authorization KEY, so `"reauthorized": false`
#      CLEARED the suspension while an incident with no such key refused —
#      writing down that the operator had NOT re-authorized removed the
#      refusal. FIXED: the VALUE is read; a denial always wins.
#   5. (H) used `-f` on the marker, so an ACTIVE suspension inside a mode-000
#      directory, and an ACTIVE marker that was a directory, both read as "no
#      suspension". FIXED: existence via -e/-L, and unreadable REFUSES.
#   6. The schema validator's crash was indistinguishable from a clean pass —
#      the caller only asked "did any line start with ERR?". `--schema`
#      pointed at any non-object JSON therefore turned (A) from FAIL into
#      PASS, making an option an escape hatch. FIXED: a terminal DONE
#      sentinel plus the validator's exit status are both required.
#   7. Evidence Records were never checked for shape. A record with no
#      `anti_bluff_status` (so the FR-004 validator never saw it), one whose
#      `result` was `ERROR`, and a file holding the literal text "this is not
#      json" were all silently skipped and then COUNTED in the gate's own
#      "N record(s) all validated" claim. FIXED: shape is checked first.
#   8. A failed write of the verdict artifact only WARNED, so the gate could
#      return QUALIFIED while printing "verdict artifact: <path>" for a file
#      that did not exist. FIXED: an authorization that cannot be recorded is
#      not granted.
#   9. (wrongly REFUSED) FR-009 demanded exactly one entry per phase name,
#      but the two live-verify scripts each append their own `live_verify`
#      entry — so every real qualifying run refused, reporting a phase that
#      ran TWICE as "absent from the run report". FIXED: every entry under a
#      required name must PASS; the count is not constrained.
#
# ---------------------------------------------------------------------------
# SECOND INDEPENDENT AUDIT, 2026-08-25 — OF THE FIX CODE ABOVE
# ---------------------------------------------------------------------------
# The nine repairs listed above were written and verified by the same agent
# that wrote the code they repaired. A second audit reviewed those 416 added
# lines as unreviewed code, because they were, and found SEVEN more defects —
# THREE of which reach "GATE QUALIFIED". Regression cases with recorded REDs:
# tests/pipeline/test_phase_05_distribute_fix_audit.sh.
#
#   1. (C) The repair that makes a multi-variant record count for NO variant
#      never fired for the app-*/api-app-* pair. _names_variant answered "does
#      this name app-debug?" with NO whenever `api-app-debug` appeared at all,
#      including when the text named BOTH, so _variants_named returned 1 and
#      the record was credited to api-app-debug. FOUR records all running a
#      client-app artifact satisfied "all 4 Android variants", with ZERO
#      :api-app device runs in the run. FIXED: strip the longer sibling, then
#      look for the short name in what remains.
#   2. (A) `--schema` is still an escape hatch. Refusing a NON-OBJECT schema
#      left `{}` — an object that defines no constraints just as completely.
#      A report carrying a property the real schema forbids went REFUSED ->
#      QUALIFIED, with (A) asserting "report schema-valid". FIXED: a schema
#      that lacks properties/required/additionalProperties:false is refused.
#   3. The verdict artifact was forgeable from the artifact it audits. Rows
#      were printf'd as TAB-separated lines and split back apart, so a report
#      value containing a newline and a tab injected rows: 11 rows for 9
#      conditions (as clause 8 then stood), and a duplicate whose last value
#      read PASS. FIXED: each row
#      is built by jq from typed arguments.
#   4. (H) Reading the re-authorization VALUE fixes nothing for an incident the
#      scan never reaches. "§6.AA clause 8(H) suspension is ACTIVE" carrying
#      "operator_reauthorization": "none" — an explicit written DENIAL — was
#      invisible and the run QUALIFIED. FIXED: "clause 8" AND a suspension word
#      anywhere in the incident, in any order.
#   5. (wrongly REFUSED) (D) over-corrected: crediting a both-naming record to
#      NEITHER surface refuses honest runs, because the :api-app is a CLIENT of
#      lava-api-go and its live-verification summary names the service it
#      reached. FIXED: `test_id` — the field that NAMES what ran rather than
#      describing it — decides the surface when it names exactly one; the strict
#      both-namer exclusion still governs the prose fallback, so a probe claiming
#      it "checked lava-api-go and api-app together" is still evidence for
#      neither. A record is still credited to at most ONE surface.
#   6. (wrongly REFUSED) (F) still carried the exact defect FR-009 was repaired
#      for: `select(.name=="precondition") | .result` unaggregated concatenates
#      two entries into "PASS\nPASS". The repair was applied at one site and
#      not swept. FIXED: aggregate, as FR-009 now does.
#   7. `--help` remained shape-coupled. The awk range ended at the first
#      horizontal rule, so a separator rule added INSIDE the Usage block
#      silently dropped the exit codes, and shortening the rules printed 1316
#      lines. FIXED: print from the marker to the end of the comment run.
#
# ---------------------------------------------------------------------------
# HONEST DEFECTS THIS GATE SURFACES RATHER THAN PAPERS OVER
# ---------------------------------------------------------------------------
# 0. CLAUSE 8(H) IS ENFORCED ON TWO OF ITS THREE LIMBS.
#    8(H) lifts a suspension on (i) a §6.Z-class incident record, (ii) a
#    covering device Challenge reproducing the failure RED-then-GREEN per
#    §6.AK clause 2, and (iii) a written operator re-authorization. This gate
#    checks (i) and (iii). Limb (ii) has no machine-readable form in the
#    incident convention, and inventing one here would assert a check that is
#    not happening. A green (H) is NOT proof the covering Challenge exists.
#
# 1. NOTHING VERIFIES PER-VARIANT CYCLE COVERAGE. NOT THIS GATE EITHER.
#    Until 2026-08-26 a condition (D) required the §6.AK cycle-coverage check
#    to pass for the `debug` channel AND the `release` channel. That condition
#    was WITHDRAWN, for two independently sufficient reasons, and this gate no
#    longer evaluates it:
#      (a) Its stated premise was that scripts/firebase-distribute.sh resolved
#          the §6.AK channel to `debug` for any MODE other than exactly
#          `release`. That `*) AK_CHANNEL="debug"` catch-all is gone: MODE has
#          two reachable values and an explicit two-arm `case` hard-errors on
#          a third (LVA-120 retired the combined mode).
#      (b) It never achieved what it claimed. scripts/check-cycle-coverage.sh
#          parses --channel, asserts it non-empty, and then never reads it
#          again; both artifacts it inspects resolve from $EDIR + $VER alone.
#          --channel=debug, --channel=release and --channel=banana produce
#          byte-identical output. Repairing the condition to "invoke
#          --channel=release" would have mandated a call that provably does
#          nothing.
#    STATED PLAINLY, because withdrawing the condition does NOT create the
#    check it was reaching for: no release-variant coverage artifacts exist —
#    the evidence tree under .lava-ci-evidence/distribute-changelog/ partitions
#    by APP, not by build variant. Per-variant cycle coverage is UNVERIFIED,
#    is tracked as §6.AA-pipeline-debt-D, and the release-variant protection
#    inside clause 8 rests ENTIRELY on condition (C).
#
# 2. THE (A)/RESIDUAL-GAP DEADLOCK IS RESOLVED, AND `residual-gap` NOW FAILS.
#    Until 2026-08-26 condition (H) mandated a top-level `residual-gap` field
#    on the run report while the schema is "additionalProperties": false and
#    defines no such property — so (A) and (H) could not both hold and no run
#    could qualify (LVA-147). This gate previously tolerated `residual-gap` as
#    a single clause-8 extension to keep the clause operable.
#    That tolerance is REMOVED. The condition (now (G)) was reworded to record
#    the gap through build_artifacts[].build_output_path, a property the schema
#    already defines, so no extension is needed. A report carrying a top-level
#    `residual-gap` property is now REFUSED by (A) like any other unknown
#    property, which is what the schema has always said.
#
# ---------------------------------------------------------------------------
# Usage:
#   scripts/pipeline/phase-05-distribute.sh <run_id> [repo-path] [options]
#
# Options (all select WHERE TO LOOK; none can change a verdict):
#   --report <path>                       override report.json location
#   --firebase-distribute-script <path>   override the script (E) INSPECTS
#   --schema <path>                       override the run-report schema
#   --suspension-dir <path>               override the clause-8 suspension dir
#
# Exit codes:
#   0 - a distribution completed. RESERVED, and unreachable today: no
#       distribute step is implemented. Nothing returns 0 yet.
#   2 - GATE REFUSED (one or more of FR-009 / (A)-(H) failed), or a usage
#       or configuration error. Refusal is the default.
#   3 - GATE QUALIFIED, and the distribute step is not implemented.
# No other exit codes are defined by this script.
# ---------------------------------------------------------------------------

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

RUN_ID=""
REPO_PATH_OVERRIDE=""
REPORT_OVERRIDE=""
FIREBASE_SCRIPT=""
SCHEMA_OVERRIDE=""
SUSPENSION_DIR_OVERRIDE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)                      REPORT_OVERRIDE="${2:-}"; shift 2 ;;
    --report=*)                    REPORT_OVERRIDE="${1#*=}"; shift 1 ;;
    --firebase-distribute-script)  FIREBASE_SCRIPT="${2:-}"; shift 2 ;;
    --firebase-distribute-script=*) FIREBASE_SCRIPT="${1#*=}"; shift 1 ;;
    --schema)                      SCHEMA_OVERRIDE="${2:-}"; shift 2 ;;
    --schema=*)                    SCHEMA_OVERRIDE="${1#*=}"; shift 1 ;;
    --suspension-dir)              SUSPENSION_DIR_OVERRIDE="${2:-}"; shift 2 ;;
    --suspension-dir=*)            SUSPENSION_DIR_OVERRIDE="${1#*=}"; shift 1 ;;
    -h|--help)
      # Matched STRUCTURALLY, from the '# Usage:' marker to the END OF THE
      # COMMENT RUN it lives in. A hardcoded '1,120p' window printed NOTHING
      # once the header grew past 120 lines; an awk range that stopped at the
      # first horizontal rule was the same defect one edit away — adding a
      # separator rule INSIDE the Usage block silently dropped the exit-code
      # section, and shortening the rules dumped the whole 1300-line file.
      # "print from the marker while the line is still a comment" depends on
      # no rule length, no rule count and no internal formatting at all.
      awk '/^# Usage:/{u=1} u && !/^#/{exit} u{print}' "${BASH_SOURCE[0]}"
      exit 2 ;;
    --*)
      # Deliberately fatal. An unknown flag is never silently ignored: that
      # is how a '--force' someone expects to work becomes a no-op the
      # author believed was a bypass, or vice versa.
      echo "phase-05-distribute: FATAL — unknown argument '$1'." >&2
      echo "  This gate has no force/bypass/override option and none may be added (§6.Z clause 6)." >&2
      exit 2 ;;
    *)
      if [[ -z "$RUN_ID" ]]; then
        RUN_ID="$1"
      elif [[ -z "$REPO_PATH_OVERRIDE" ]]; then
        REPO_PATH_OVERRIDE="$1"
      else
        echo "phase-05-distribute: FATAL — unexpected positional argument '$1'" >&2
        exit 2
      fi
      shift 1 ;;
  esac
done

if [[ -z "$RUN_ID" ]]; then
  echo "phase-05-distribute: FATAL — <run_id> is required" >&2
  echo "usage: scripts/pipeline/phase-05-distribute.sh <run_id> [repo-path] [options]" >&2
  exit 2
fi

REPO_PATH="${REPO_PATH_OVERRIDE:-$DEFAULT_REPO_ROOT}"
RUN_DIR="${REPO_PATH}/.lava-ci-evidence/pipeline-runs/${RUN_ID}"
REPORT_PATH="${REPORT_OVERRIDE:-${RUN_DIR}/report.json}"
FIREBASE_SCRIPT="${FIREBASE_SCRIPT:-${REPO_PATH}/scripts/firebase-distribute.sh}"
# The contract schema ships with the pipeline CODE, so it resolves against
# this script's own repository — never against the run's repo-path, which
# may be a bare fixture with no specs/ tree.
SCHEMA_PATH="${SCHEMA_OVERRIDE:-${DEFAULT_REPO_ROOT}/specs/002-build-test-distribute-pipeline/contracts/pipeline-run-report.schema.json}"
SUSPENSION_DIR="${SUSPENSION_DIR_OVERRIDE:-${REPO_PATH}/.lava-ci-evidence/clause-8-suspension}"
PHASE_DIR="${RUN_DIR}/phase-05"
VERDICT_PATH="${PHASE_DIR}/gate-verdict.json"

for tool in jq git python3; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "phase-05-distribute: FATAL — required tool '$tool' not found on PATH" >&2
    exit 2
  fi
done

# The four Android artifact variants clause 8(C) enumerates by name.
ANDROID_VARIANTS=(app-debug app-release api-app-debug api-app-release)

# _names_variant <haystack> <variant> — does this record's text name THIS
# artifact variant? `api-app-debug` contains `app-debug` as a substring, so
# the longer name must never be matched by the shorter one.
#
# INDEPENDENT AUDIT 2026-08-25: the disambiguation used to be
# `[[ "$hay" == *"api-${variant}"* ]] && return 1`, which answers "does this
# text name app-debug?" with NO the moment `api-app-debug` appears ANYWHERE in
# it — including when the text names BOTH. _variants_named therefore counted a
# record saying `--artifact app-debug ... also covers api-app-debug` as naming
# exactly ONE variant, the >1 guard never fired, and that record was credited
# to api-app-debug. Four records that all ran a client-app artifact then
# satisfied "all 4 Android variants have a PASSing real-device-challenge
# record" with ZERO :api-app device runs in the run — precisely the "one
# device run is not evidence for two APKs" laundering this guard exists to
# stop, and precisely the §6.Z release-variant forensic anchor.
# Stripping the longer sibling FIRST and then looking for the short name in
# what remains answers the question that was actually being asked.
_names_variant() {
  local hay="$1" variant="$2" rest
  case "$variant" in
    app-debug|app-release)
      rest="${hay//api-${variant}/}"
      [[ "$rest" == *"${variant}"* ]] ;;
    *)
      [[ "$hay" == *"${variant}"* ]] ;;
  esac
}

# _variants_named <haystack> — how many of the four variants this text names.
_variants_named() {
  local hay="$1" v n=0
  for v in "${ANDROID_VARIANTS[@]}"; do
    _names_variant "$hay" "$v" && n=$((n + 1))
  done
  printf '%s' "$n"
}
# US1/US2 phases FR-009 requires to have passed before distribution.
US1_US2_PHASES=(build test install_boot live_verify)

CONDITION_IDS=(A B C D E F G H)
declare -A COND_VERDICT=()
declare -A COND_WHY=()

# _set_cond <id> <verdict> <why>
_set_cond() {
  COND_VERDICT["$1"]="$2"
  COND_WHY["$1"]="$3"
}
# _add_why <id> <line> — accumulate a reason without losing earlier ones.
_add_why() {
  local id="$1" line="$2"
  if [[ -n "${COND_WHY[$id]:-}" ]]; then
    COND_WHY["$id"]="${COND_WHY[$id]}; ${line}"
  else
    COND_WHY["$id"]="$line"
  fi
}

# _incident_reauthorized <incident.json>
#   exit 0 — the incident records an AFFIRMATIVE operator re-authorization
#   exit 1 — it does not (absent, or explicitly denied)
#   exit 2 — it could not be parsed, so nothing was learned (caller refuses)
#
# The previous check grepped for the KEY (`"?reauthoriz(ed|ation)"?[:=]`) and
# treated a hit as clearance. Writing down `"reauthorized": false` therefore
# CLEARED the suspension: recording that the operator had NOT re-authorized
# flipped the gate from REFUSED to QUALIFIED, while an incident with no such
# key at all correctly refused. Clause 8(H): "re-authorization is an explicit
# act, never an inference". A denial must never read as an act.
_incident_reauthorized() {
  python3 - "$1" <<'PY'
import json, re, sys

# Keys that can GRANT. A free-text note (`reauthorization_note`) never grants:
# "operator has NOT re-authorized clause 8" is a non-empty string.
GRANT_KEY = re.compile(r"^re[-_ ]?authoriz(ed|ation)(_?(by|at|on|date))?$", re.I)
# Any key mentioning re-authorization can DENY, and a denial always wins.
MENTIONS = re.compile(r"re[-_ ]?authoriz", re.I)
DENIALS = {"", "false", "no", "none", "null", "n/a", "na", "pending", "owed",
           "0", "not re-authorized", "not reauthorized", "unauthorized",
           "not yet", "outstanding", "denied"}

granted = False
denied = False


def value_is_denial(v):
    if v is False or v is None:
        return True
    if isinstance(v, str):
        return v.strip().lower() in DENIALS
    return False


def value_is_grant(v):
    if v is True:
        return True
    return isinstance(v, str) and v.strip() != "" and v.strip().lower() not in DENIALS


def walk(node):
    global granted, denied
    if isinstance(node, dict):
        for k, v in node.items():
            key = str(k)
            if MENTIONS.search(key) and value_is_denial(v):
                denied = True
            elif GRANT_KEY.match(key) and value_is_grant(v):
                granted = True
            walk(v)
    elif isinstance(node, list):
        for item in node:
            walk(item)


try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        doc = json.load(fh)
except Exception:
    raise SystemExit(2)
walk(doc)
raise SystemExit(0 if (granted and not denied) else 1)
PY
}

FR009_VERDICT="FAIL"
FR009_WHY=""
VERDICT_RECORDED="no"

echo "==============================================================="
echo "phase-05-distribute — §6.AA clause 8 refusal gate (gate-only)"
echo "==============================================================="
echo "run_id            : ${RUN_ID}"
echo "repo              : ${REPO_PATH}"
echo "report            : ${REPORT_PATH}"
echo ""

# ---------------------------------------------------------------------------
# Preflight: the report must exist and parse before ANY condition can be
# evaluated against it. With no qualifying report the gate refuses — that is
# the default state, not an error path.
# ---------------------------------------------------------------------------
REPORT_USABLE="no"
if [[ ! -f "$REPORT_PATH" ]]; then
  _set_cond A "FAIL" "no Pipeline Run Report at ${REPORT_PATH} — clause 8(A) requires one to exist"
  FR009_WHY="no run report to read evidence from at ${REPORT_PATH}"
elif ! python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$REPORT_PATH" >/dev/null 2>&1; then
  _set_cond A "FAIL" "report at ${REPORT_PATH} is not parseable JSON"
  FR009_WHY="run report is not parseable JSON"
else
  REPORT_USABLE="yes"
fi

HEAD_SHA="$(git -C "$REPO_PATH" rev-parse HEAD 2>/dev/null)"
GIT_RC=$?
if [[ "$GIT_RC" -ne 0 || -z "$HEAD_SHA" ]]; then
  HEAD_SHA=""
  _add_why A "could not resolve git HEAD in ${REPO_PATH}, so the clause 8(A) commit_sha identity check cannot be performed"
fi

# ---------------------------------------------------------------------------
# Evidence Record discovery. Records live at <run>/<phase>/<category>/<id>.json
# (see scripts/pipeline/lib/evidence.sh); raw captured output lives under
# <category>/raw/ and is NOT a record.
# ---------------------------------------------------------------------------
ALL_RECORDS=()
DEVICE_RECORDS=()
if [[ -d "$RUN_DIR" ]]; then
  mapfile -t ALL_RECORDS < <(find "$RUN_DIR" -type f -name '*.json' \
      ! -name 'report.json' ! -name 'gate-verdict.json' ! -path '*/raw/*' 2>/dev/null | sort)
  mapfile -t DEVICE_RECORDS < <(find "$RUN_DIR" -type f -name '*.json' \
      -path '*/real-device-challenge/*' ! -path '*/raw/*' 2>/dev/null | sort)
fi

# _rec <file> <jq-filter> — read one field from a record, empty on any failure.
# SAFE ONLY because _record_shape_problem below has already established that
# every file in ALL_RECORDS parses and carries every required string field.
# Without that gate an unreadable, unparseable or field-less file read as a
# row of empty strings, which compared equal to "not FAIL" and "not SKIPPED"
# and was then counted in the "N record(s) all validated" claim.
_rec() { jq -r "$2 // empty" "$1" 2>/dev/null; }

# _record_shape_problem <file> — echo a human-readable reason this file is not
# a well-formed Evidence Record, or echo nothing when it is one. The contract
# is contracts/evidence-record.schema.json: 7 required string fields, a
# closed-set `category`, a closed-set `result`, and an `anti_bluff_status`
# matching ^(validated|REJECTED: .+)$ (written only by the independent
# validator, per FR-004 — so a MISSING one means no validator ever saw it).
_record_shape_problem() {
  python3 - "$1" <<'PY' 2>/dev/null || echo "could not be inspected at all (the record shape checker did not run)"
import json, re, sys
CATEGORIES = {"kotlin-unit", "go-unit-integration", "real-binary-contract",
              "real-device-challenge", "hermetic-script", "stress-chaos",
              "release-canary", "constitutional-gate-sweep"}
RESULTS = {"PASS", "FAIL", "SKIPPED"}
REQUIRED = ["test_id", "category", "command", "result",
            "assertion_summary", "raw_output_ref", "anti_bluff_status"]
try:
    with open(sys.argv[1], encoding="utf-8") as fh:
        rec = json.load(fh)
except Exception as exc:  # unreadable or not JSON
    print(f"is not readable, well-formed JSON ({type(exc).__name__})")
    raise SystemExit(0)
if not isinstance(rec, dict):
    print(f"is a JSON {type(rec).__name__}, not an Evidence Record object")
    raise SystemExit(0)
problems = []
for key in REQUIRED:
    if key not in rec:
        problems.append(f"has no '{key}'")
    elif not isinstance(rec[key], str) or not rec[key]:
        problems.append(f"has a non-string or empty '{key}' ({rec[key]!r})")
if isinstance(rec.get("result"), str) and rec["result"] not in RESULTS:
    problems.append(f"has result '{rec['result']}', outside the schema enum {sorted(RESULTS)}")
if isinstance(rec.get("category"), str) and rec["category"] not in CATEGORIES:
    problems.append(f"has category '{rec['category']}', outside the schema enum")
abs_ = rec.get("anti_bluff_status")
if isinstance(abs_, str) and not re.fullmatch(r"validated|REJECTED: .+", abs_):
    problems.append(f"has anti_bluff_status '{abs_}', which does not match ^(validated|REJECTED: .+)$")
print("; ".join(problems))
PY
}

# Shape-scan every discovered record ONCE. Both FR-009 and condition (B)
# consult this: a file that cannot be read as a record must not be reported by
# (B) as one of "N record(s) all validated" merely because FR-009 already
# refused the run on its account.
declare -A RECORD_SHAPE_PROBLEM=()
RECORD_SHAPE_PROBLEMS=()
for rec in "${ALL_RECORDS[@]}"; do
  _shape="$(_record_shape_problem "$rec")"
  if [[ -n "$_shape" ]]; then
    RECORD_SHAPE_PROBLEM["$rec"]="$_shape"
    RECORD_SHAPE_PROBLEMS+=("evidence file '${rec#"${REPO_PATH}/"}' is not a well-formed Evidence Record: it ${_shape}")
  fi
done

# The live_verify phases' own evidence directories, and a predicate over them.
# Used by (B) to locate the records clause 8(B) calls "any category named in
# condition (C) or (D)", and by (D) for provenance. A phase name may repeat,
# so this is a LIST: deriving one directory by string-trimming the jq output
# silently kept only the last of them.
LIVE_VERIFY_DIRS=()
if [[ "$REPORT_USABLE" == "yes" ]]; then
  mapfile -t LIVE_VERIFY_DIRS < <(jq -r '.phases[]? | select(.name=="live_verify") | .evidence_dir // empty' "$REPORT_PATH" 2>/dev/null)
fi
_under_live_verify() {
  local rec="$1" d
  for d in "${LIVE_VERIFY_DIRS[@]}"; do
    [[ -z "$d" ]] && continue
    case "$d" in
      /*) [[ "$rec" == "${d}/"* ]] && return 0 ;;
      *)  [[ "$rec" == "${REPO_PATH}/${d}/"* ]] && return 0 ;;
    esac
  done
  return 1
}

# =========================================================================
# FR-009 BASELINE — refuse unless every US1/US2 phase PASSed and no evidence
# failed or was rejected by anti-bluff validation. FR-009 also requires
# reporting EXACTLY which evidence blocked, so every offending record is
# named individually rather than counted.
# =========================================================================
echo "---------------------------------------------------------------"
echo "FR-009 baseline — build-time and live-verification evidence"
echo "---------------------------------------------------------------"
if [[ "$REPORT_USABLE" == "yes" ]]; then
  fr009_problems=()
  for phase in "${US1_US2_PHASES[@]}"; do
    presence="$(jq -r --arg n "$phase" '[.phases[]? | select(.name==$n)] | length' "$REPORT_PATH" 2>/dev/null)"
    if [[ ! "$presence" =~ ^[0-9]+$ ]] || [[ "$presence" -eq 0 ]]; then
      fr009_problems+=("phase '${phase}' is absent from the run report — an absent phase proves nothing")
      continue
    fi
    # A phase name may legitimately appear MORE THAN ONCE. phase-04-live-
    # verify-api.sh and phase-04-live-verify-api-app.sh each append their own
    # "live_verify" entry, and the latter's header states both "legitimately
    # coexist in phases[]"; they are the two halves of FR-008. Demanding
    # exactly one entry refused every real qualifying run, and reported a
    # phase that ran TWICE as "absent from the run report" — the opposite of
    # what happened. Every entry under the name must PASS; the count must not.
    non_pass_entries="$(jq -r --arg n "$phase" \
        '[.phases[] | select(.name==$n) | select(.result != "PASS") | .result] | join(", ")' \
        "$REPORT_PATH" 2>/dev/null)"
    if [[ -n "$non_pass_entries" ]]; then
      fr009_problems+=("phase '${phase}' has ${presence} entry/entries, of which one or more is not PASS: ${non_pass_entries}")
    fi
  done

  rejected_count="$(jq -r '.evidence_summary.rejected_by_anti_bluff' "$REPORT_PATH" 2>/dev/null)"
  if [[ ! "$rejected_count" =~ ^[0-9]+$ ]]; then
    fr009_problems+=("evidence_summary.rejected_by_anti_bluff is '${rejected_count}', not a number — the anti-bluff count cannot be read, so it cannot be trusted to be zero")
  elif [[ "$rejected_count" -ne 0 ]]; then
    fr009_problems+=("evidence_summary.rejected_by_anti_bluff is ${rejected_count}, must be 0")
  fi

  # Name every individual record that blocks, per FR-009's "report exactly
  # which evidence blocked it".
  #
  # SHAPE FIRST. A file that does not parse, or that is missing a required
  # field, cannot be read as PASSing OR as FAILing — it is UNEVALUABLE, and
  # this gate refuses what it cannot evaluate. Previously such a file was
  # skipped in silence (`[[ -z result && -z id ]] && continue`) and then
  # counted in "N record(s) all validated": a record with no
  # anti_bluff_status at all, one whose result was "ERROR", and a file
  # holding the literal text "this is not json" each qualified a run.
  for shape_problem in "${RECORD_SHAPE_PROBLEMS[@]}"; do
    fr009_problems+=("$shape_problem")
  done
  for rec in "${ALL_RECORDS[@]}"; do
    [[ -n "${RECORD_SHAPE_PROBLEM[$rec]:-}" ]] && continue
    r_result="$(_rec "$rec" '.result')"
    r_status="$(_rec "$rec" '.anti_bluff_status')"
    r_id="$(_rec "$rec" '.test_id')"
    if [[ "$r_result" == "FAIL" ]]; then
      fr009_problems+=("evidence '${r_id}' (${rec#"${REPO_PATH}/"}) has result FAIL")
    fi
    if [[ -n "$r_status" && "$r_status" != "validated" ]]; then
      fr009_problems+=("evidence '${r_id}' (${rec#"${REPO_PATH}/"}) has anti_bluff_status '${r_status}'")
    fi
  done

  if [[ "${#ALL_RECORDS[@]}" -eq 0 ]]; then
    fr009_problems+=("the run produced ZERO Evidence Records — there is no evidence to authorize a distribution with")
  fi

  if [[ "${#fr009_problems[@]}" -eq 0 ]]; then
    FR009_VERDICT="PASS"
    FR009_WHY="all ${#US1_US2_PHASES[@]} US1/US2 phases PASS; ${#ALL_RECORDS[@]} Evidence Record(s) scanned, 0 FAIL, 0 anti-bluff rejections"
  else
    FR009_VERDICT="FAIL"
    FR009_WHY="$(printf '%s; ' "${fr009_problems[@]}")"
    FR009_WHY="${FR009_WHY%; }"
  fi
fi
echo "FR-009: ${FR009_VERDICT} — ${FR009_WHY}"
echo ""

# =========================================================================
# CONDITION (A) — Run Report identity.
# =========================================================================
if [[ "$REPORT_USABLE" == "yes" ]]; then
  a_problems=()

  # Structural validation against the real schema document. There is no
  # `jsonschema` module on this host, so the schema's actual constraints are
  # applied explicitly rather than assumed satisfied.
  schema_out="$(python3 - "$REPORT_PATH" "$SCHEMA_PATH" <<'PY' 2>&1
import json, re, sys

report_path, schema_path = sys.argv[1], sys.argv[2]
try:
    rep = json.load(open(report_path))
    sch = json.load(open(schema_path))
except Exception as exc:  # noqa: BLE001
    print(f"ERR could not load report or schema: {exc}")
    print("DONE")
    sys.exit(0)

# A schema that is not a JSON OBJECT defines no constraints, so validating
# against it establishes nothing. Saying so is the only honest outcome:
# `--schema` pointing at a JSON array used to crash this validator on
# `sch.get(...)`, which printed no ERR line and therefore read as "valid".
if not isinstance(sch, dict):
    print(f"ERR schema document at {schema_path} is a JSON "
          f"{type(sch).__name__}, not an object, so it defines no constraints "
          f"and nothing about the report was established")
    print("DONE")
    sys.exit(0)

# INDEPENDENT AUDIT 2026-08-25. Rejecting a NON-OBJECT schema closed one door
# and left the next one open: an EMPTY object is a perfectly valid JSON object
# that also defines no constraints. `--schema` pointed at a file containing
# exactly `{}` turned a report carrying a property the real schema forbids from
# REFUSED into QUALIFIED, with (A) then asserting "report schema-valid" — the
# same escape hatch, one character of JSON away. Every option on this gate
# selects WHERE TO LOOK and none may change a verdict, so a schema that cannot
# constrain is refused rather than deferred to. These three keys are exactly
# what the checks below consult; a document lacking any of them cannot
# establish that the report is a Pipeline Run Report.
_missing = []
if not isinstance(sch.get("properties"), dict) or not sch.get("properties"):
    _missing.append("a non-empty 'properties' object")
if not isinstance(sch.get("required"), list) or not sch.get("required"):
    _missing.append("a non-empty 'required' array")
if sch.get("additionalProperties") is not False:
    _missing.append("'additionalProperties': false")
if _missing:
    print(f"ERR schema document at {schema_path} is a JSON object but defines "
          f"no usable constraints: it lacks {', and '.join(_missing)}. "
          f"Validating against it would establish nothing about the report, so "
          f"it cannot stand in for the run-report contract")
    print("DONE")
    sys.exit(0)
if not isinstance(rep, dict):
    print(f"ERR report at {report_path} is a JSON {type(rep).__name__}, "
          f"not a Pipeline Run Report object")
    print("DONE")
    sys.exit(0)

# NO TOLERATED EXTENSIONS. Until 2026-08-26 `residual-gap` was allowed
# through here, because the then-condition 8(H) mandated a field the schema
# forbids and (A) and (H) could not otherwise both hold (LVA-147). The
# condition was reworded -- it now records the gap through
# build_artifacts[].build_output_path, which the schema already defines -- so
# the extension is no longer needed and is no longer accepted. A report
# carrying `residual-gap` is refused here like any other unknown property.
errs = []
props = sch.get("properties", {})

try:
    for key in sch.get("required", []):
        if key not in rep:
            errs.append(f"required property '{key}' is missing")

    if sch.get("additionalProperties") is False:
        for key in rep:
            if key in props:
                continue
            errs.append(f"property '{key}' is not permitted (additionalProperties: false)")

    def check(value, spec, where):
        t = spec.get("type")
        if t == "string" and not isinstance(value, str):
            errs.append(f"{where} must be a string"); return
        if t == "integer" and not isinstance(value, int):
            errs.append(f"{where} must be an integer"); return
        if t == "number" and not isinstance(value, (int, float)):
            errs.append(f"{where} must be a number"); return
        if t == "array" and not isinstance(value, list):
            errs.append(f"{where} must be an array"); return
        if t == "object" and not isinstance(value, dict):
            errs.append(f"{where} must be an object"); return
        if "enum" in spec and value not in spec["enum"]:
            errs.append(f"{where} is '{value}', not one of {spec['enum']}")
        if "pattern" in spec and isinstance(value, str) and not re.search(spec["pattern"], value):
            errs.append(f"{where} '{value}' does not match {spec['pattern']}")
        if "minimum" in spec and isinstance(value, (int, float)) and value < spec["minimum"]:
            errs.append(f"{where} is below minimum {spec['minimum']}")
        if t == "array" and isinstance(value, list) and isinstance(spec.get("items"), dict):
            isp = spec["items"]
            if "properties" in isp:
                for i, item in enumerate(value):
                    if not isinstance(item, dict):
                        errs.append(f"{where}[{i}] must be an object"); continue
                    for req in isp.get("required", []):
                        if req not in item:
                            errs.append(f"{where}[{i}] is missing required '{req}'")
                    if isp.get("additionalProperties") is False:
                        for k in item:
                            if k not in isp.get("properties", {}):
                                errs.append(f"{where}[{i}] has unpermitted property '{k}'")
                    for k, v in item.items():
                        if k in isp.get("properties", {}):
                            check(v, isp["properties"][k], f"{where}[{i}].{k}")
        if t == "object" and isinstance(value, dict) and "properties" in spec:
            for req in spec.get("required", []):
                if req not in value:
                    errs.append(f"{where} is missing required '{req}'")
            for k, v in value.items():
                if k in spec["properties"]:
                    check(v, spec["properties"][k], f"{where}.{k}")

    for key, value in rep.items():
        if key in props:
            check(value, props[key], key)

    # The schema's allOf rule: outcome PASS implies zero anti-bluff rejections.
    if rep.get("outcome") == "PASS":
        rb = rep.get("evidence_summary", {}).get("rejected_by_anti_bluff")
        if rb != 0:
            errs.append(f"outcome is PASS but evidence_summary.rejected_by_anti_bluff is {rb}, schema requires 0")

except Exception as exc:  # noqa: BLE001
    # A validator that dies mid-way has learned nothing, which is NOT the same
    # as having found nothing. Recorded as an error so the caller refuses.
    errs.append(f"structural validation aborted: {type(exc).__name__}: {exc}")

for e in errs:
    print(f"ERR {e}")
if not errs:
    print("OK schema-valid")
# Terminal sentinel. Its ABSENCE is how the caller tells "the validator
# finished and found nothing wrong" from "the validator never finished".
print("DONE")
PY
)"
  schema_rc=$?
  # POSITIVE OUTPUT CONTRACT. Previously the caller only asked "did any line
  # start with ERR?", so a validator that CRASHED — printing a traceback and
  # no ERR line — was indistinguishable from one that found nothing wrong.
  # That made `--schema <any-non-object-json>` an escape hatch: a report
  # carrying a property the real schema forbids went from REFUSED to
  # QUALIFIED, with the gate asserting "report schema-valid". Every option on
  # this gate selects WHERE TO LOOK; none may change a verdict, and this is
  # what makes that true of --schema.
  if [[ "$schema_rc" -ne 0 ]] || ! grep -qx 'DONE' <<< "$schema_out"; then
    a_problems+=("structural validation of the run report did not complete (validator exit ${schema_rc}), so the report has NOT been validated against ${SCHEMA_PATH}; output was: ${schema_out//$'\n'/ | }")
  fi
  if grep -q '^ERR ' <<< "$schema_out"; then
    while IFS= read -r eline; do
      [[ "$eline" == ERR\ * ]] && a_problems+=("schema: ${eline#ERR }")
    done <<< "$schema_out"
  fi

  # commit_sha must equal HEAD at THIS moment. Clause 8(A): a mismatch "is
  # not stale evidence to be tolerated — it is a REFUSAL condition."
  report_sha="$(jq -r '.commit_sha // empty' "$REPORT_PATH" 2>/dev/null)"
  if [[ -z "$HEAD_SHA" ]]; then
    a_problems+=("git HEAD could not be resolved, so commit_sha identity is unverifiable")
  elif [[ "$report_sha" != "$HEAD_SHA" ]]; then
    a_problems+=("commit_sha '${report_sha}' != current HEAD '${HEAD_SHA}'")
  fi

  # Every Distribution Record's version_code must equal the version_code of
  # the artifact it names.
  dr_count="$(jq -r '.distributions | length' "$REPORT_PATH" 2>/dev/null)"
  if [[ "$dr_count" =~ ^[0-9]+$ ]] && [[ "$dr_count" -gt 0 ]]; then
    while IFS=$'\t' read -r d_art d_code; do
      [[ -z "$d_art" ]] && continue
      b_code="$(jq -r --arg a "$d_art" '.build_artifacts[] | select(.artifact_id==$a) | .version_code' "$REPORT_PATH" 2>/dev/null)"
      if [[ -z "$b_code" ]]; then
        a_problems+=("Distribution Record for '${d_art}' has no matching build_artifacts entry to cross-check its version_code against")
      elif [[ "$b_code" != "$d_code" ]]; then
        a_problems+=("Distribution Record '${d_art}' version_code ${d_code} != built artifact version_code ${b_code}")
      fi
    done < <(jq -r '.distributions[]? | [.artifact_id, (.version_code|tostring)] | @tsv' "$REPORT_PATH" 2>/dev/null)
  fi

  if [[ "${#a_problems[@]}" -eq 0 ]]; then
    _set_cond A "PASS" "report schema-valid; commit_sha matches HEAD ${HEAD_SHA}; ${dr_count:-0} Distribution Record(s) cross-checked"
  else
    _set_cond A "FAIL" "$(printf '%s; ' "${a_problems[@]}")"
    COND_WHY[A]="${COND_WHY[A]%; }"
  fi
fi

# =========================================================================
# CONDITION (B) — Unqualified pass.
# =========================================================================
if [[ "$REPORT_USABLE" == "yes" ]]; then
  b_problems=()
  outcome="$(jq -r '.outcome // empty' "$REPORT_PATH" 2>/dev/null)"
  [[ "$outcome" != "PASS" ]] && b_problems+=("report outcome is '${outcome}', not PASS")

  non_pass="$(jq -r '[.phases[]? | select(.result != "PASS") | "\(.name)=\(.result)"] | join(", ")' "$REPORT_PATH" 2>/dev/null)"
  [[ -n "$non_pass" ]] && b_problems+=("phase(s) not PASS: ${non_pass}")

  n_phases="$(jq -r '.phases | length' "$REPORT_PATH" 2>/dev/null)"
  if [[ "$n_phases" =~ ^[0-9]+$ ]] && [[ "$n_phases" -eq 0 ]]; then
    b_problems+=("phases[] is empty — a run with no phases has demonstrated nothing")
  fi

  # (B) asserts "every record validated". Over an empty set that sentence is
  # true and worthless: it reported PASS having examined nothing.
  if [[ "${#ALL_RECORDS[@]}" -eq 0 ]]; then
    b_problems+=("the run produced ZERO Evidence Records — 'every record is validated' over an empty set is not an unqualified pass, it is an unexamined one")
  fi

  for shape_problem in "${RECORD_SHAPE_PROBLEMS[@]}"; do
    b_problems+=("$shape_problem")
  done
  for rec in "${ALL_RECORDS[@]}"; do
    [[ -n "${RECORD_SHAPE_PROBLEM[$rec]:-}" ]] && continue
    r_result="$(_rec "$rec" '.result')"
    r_status="$(_rec "$rec" '.anti_bluff_status')"
    r_id="$(_rec "$rec" '.test_id')"
    if [[ "$r_result" == "FAIL" ]]; then
      b_problems+=("Evidence Record '${r_id}' has result FAIL")
    fi
    if [[ -n "$r_status" && "$r_status" != "validated" ]]; then
      b_problems+=("Evidence Record '${r_id}' has anti_bluff_status '${r_status}', not validated")
    fi
  done

  # Clause 8(B): "A SKIPPED Evidence Record in any category named in
  # condition (C) or (D) disqualifies the run." (C) names
  # real-device-challenge; (D) names the live-verification surfaces, whose
  # records live in the live_verify phase's evidence dir.
  for rec in "${ALL_RECORDS[@]}"; do
    [[ -n "${RECORD_SHAPE_PROBLEM[$rec]:-}" ]] && continue
    r_result="$(_rec "$rec" '.result')"
    [[ "$r_result" != "SKIPPED" ]] && continue
    r_id="$(_rec "$rec" '.test_id')"
    r_cat="$(_rec "$rec" '.category')"
    if [[ "$r_cat" == "real-device-challenge" ]]; then
      b_problems+=("Evidence Record '${r_id}' is SKIPPED in category real-device-challenge, which clause 8(C) names — a SKIPPED record there disqualifies the run")
    elif _under_live_verify "$rec"; then
      b_problems+=("Evidence Record '${r_id}' is SKIPPED in the live-verification evidence clause 8(D) names — a SKIPPED record there disqualifies the run")
    fi
  done

  if [[ "${#b_problems[@]}" -eq 0 ]]; then
    _set_cond B "PASS" "outcome PASS, all ${n_phases} phase(s) PASS, ${#ALL_RECORDS[@]} record(s) all validated with no FAIL and no disqualifying SKIP"
  else
    _set_cond B "FAIL" "$(printf '%s; ' "${b_problems[@]}")"
    COND_WHY[B]="${COND_WHY[B]%; }"
  fi
fi

# =========================================================================
# CONDITION (C) — Device evidence for BOTH variants of BOTH Android apps.
#
# Clause 8(C) is explicit that release-variant evidence is mandatory and NOT
# inferable from debug-variant evidence, because the R8-minified release
# artifact is a different artifact and the §6.Z forensic anchor was a
# release-variant-only failure. So all four named variants are required.
#
# HONEST LIMITATION, stated rather than hidden: evidence-record.schema.json
# has NO artifact field, so a record cannot structurally declare which
# variant it exercised. This gate therefore binds a record to a variant by
# requiring the variant's artifact_id token to appear in the record's
# test_id, command or assertion_summary, AND requires the report to carry a
# matching build_artifacts entry built from the same commit. That binding is
# weaker than a typed field would be. It errs toward REFUSAL — a record that
# does not say which artifact it ran against does not count for any variant.
# Adding an `artifact_id` to the Evidence Record schema would close this.
# =========================================================================
if [[ "$REPORT_USABLE" == "yes" ]]; then
  c_problems=()
  report_sha_c="$(jq -r '.commit_sha // empty' "$REPORT_PATH" 2>/dev/null)"

  for variant in "${ANDROID_VARIANTS[@]}"; do
    ba_present="$(jq -r --arg a "$variant" '[.build_artifacts[]? | select(.artifact_id==$a)] | length' "$REPORT_PATH" 2>/dev/null)"
    if [[ "$ba_present" != "1" ]]; then
      c_problems+=("variant '${variant}': no build_artifacts entry, so there is no artifact for device evidence to be 'against'")
      continue
    fi
    ba_commit="$(jq -r --arg a "$variant" '.build_artifacts[] | select(.artifact_id==$a) | .built_from_commit' "$REPORT_PATH" 2>/dev/null)"
    if [[ "$ba_commit" != "$report_sha_c" ]]; then
      c_problems+=("variant '${variant}': built_from_commit '${ba_commit}' != report commit_sha '${report_sha_c}', so the evidence is not against the artifact being shipped")
    fi

    found_ok="no"
    reject_note=""
    for rec in "${DEVICE_RECORDS[@]}"; do
      # A malformed record has already blocked FR-009 and (B) by name; it must
      # not additionally be able to SATISFY a variant here.
      [[ -n "${RECORD_SHAPE_PROBLEM[$rec]:-}" ]] && continue
      r_id="$(_rec "$rec" '.test_id')"
      r_cmd="$(_rec "$rec" '.command')"
      r_sum="$(_rec "$rec" '.assertion_summary')"
      r_result="$(_rec "$rec" '.result')"
      r_status="$(_rec "$rec" '.anti_bluff_status')"
      r_cat="$(_rec "$rec" '.category')"
      haystack="${r_id} ${r_cmd} ${r_sum}"

      # Bind this record to THIS variant.
      _names_variant "$haystack" "$variant" || continue

      # Clause 8(C) names `category: real-device-challenge`. Records were
      # discovered by DIRECTORY PATH and the field was never read, so four
      # records declaring `category: kotlin-unit` satisfied "all 4 Android
      # variants have a PASSing real-device-challenge record". Verify the
      # fact, not the location that usually implies it.
      if [[ "$r_cat" != "real-device-challenge" ]]; then
        reject_note="a record naming it declares category '${r_cat}', not the 'real-device-challenge' clause 8(C) requires"
        continue
      fi

      # ONE RECORD, ONE ARTIFACT. A record whose text names more than one of
      # the four variants does not say which artifact it ran against, and a
      # single emulator run cannot be evidence for two different APKs. Clause
      # 8(C): "Release-variant device evidence is mandatory and is NOT
      # inferable from debug-variant evidence" — the §6.Z forensic anchor was
      # precisely a release-only failure hidden behind debug evidence. Two
      # records, each an explicit `--artifact <debug>` run whose summary added
      # "covers <release>", used to satisfy all four variants. This is the
      # same principle condition (D) already applies to its two live surfaces,
      # and the same one this file's own header states: a record that does not
      # say which artifact it ran against counts for no variant.
      if [[ "$(_variants_named "$haystack")" -gt 1 ]]; then
        reject_note="a record naming it also names other artifact variants, so it does not say which single artifact it ran against; one device run is not evidence for two APKs"
        continue
      fi

      if [[ "$r_result" != "PASS" ]]; then
        reject_note="a record naming it has result '${r_result}'"
        continue
      fi
      if [[ "$r_status" != "validated" ]]; then
        reject_note="a record naming it has anti_bluff_status '${r_status}'"
        continue
      fi
      # §6.AH / §6.AG: container-or-VM only. Host-direct and live physical
      # devices are forbidden as gate evidence. The negative test is applied
      # FIRST so a record carrying both markers cannot launder itself.
      if [[ "$haystack" == *"host-direct"* || "$haystack" == *"live-device"* || "$haystack" == *"physical-device"* ]]; then
        reject_note="its only PASSing record ran on a host-direct or live physical device, which §6.AH forbids as gate evidence"
        continue
      fi
      if [[ "$haystack" != *"container"* && "$haystack" != *"podman"* && "$haystack" != *"docker"* \
            && "$haystack" != *"qemu"* && "$haystack" != *"-vm"* ]]; then
        reject_note="its only PASSing record does not identify a container-or-VM runner, so §6.AH conformance cannot be confirmed"
        continue
      fi
      found_ok="yes"
      break
    done

    if [[ "$found_ok" != "yes" ]]; then
      if [[ -n "$reject_note" ]]; then
        c_problems+=("variant '${variant}': ${reject_note}")
      else
        c_problems+=("variant '${variant}': no PASSing real-device-challenge Evidence Record identifies it")
      fi
    fi
  done

  if [[ "${#c_problems[@]}" -eq 0 ]]; then
    _set_cond C "PASS" "all 4 Android variants (${ANDROID_VARIANTS[*]}) have a PASSing container-or-VM real-device-challenge record against the shipped commit"
  else
    _set_cond C "FAIL" "$(printf '%s; ' "${c_problems[@]}")"
    COND_WHY[C]="${COND_WHY[C]%; }"
  fi
fi

# =========================================================================
# CONDITION (D) — Live verification of EVERY distributed live surface.
# Clause 8(D): a run covering only one of the two does NOT qualify,
# "irrespective of that half's result".
# =========================================================================
if [[ "$REPORT_USABLE" == "yes" ]]; then
  e_problems=()
  # A phase name may repeat: the lava-api-go and :api-app live-verify scripts
  # each append their own "live_verify" entry. Require at least one, and
  # require every one of them to have PASSed.
  lv_count="$(jq -r '[.phases[]? | select(.name=="live_verify")] | length' "$REPORT_PATH" 2>/dev/null)"
  lv_non_pass="$(jq -r '[.phases[]? | select(.name=="live_verify") | select(.result != "PASS") | .result] | join(", ")' "$REPORT_PATH" 2>/dev/null)"
  if [[ ! "$lv_count" =~ ^[0-9]+$ ]] || [[ "$lv_count" -eq 0 ]]; then
    e_problems+=("the run report has no live_verify phase at all")
  elif [[ -n "$lv_non_pass" ]]; then
    e_problems+=("a live_verify phase entry is not PASS: ${lv_non_pass}")
  fi

  # PROVENANCE. Clause 8(D) requires the surfaces to have been exercised
  # "against actually-running services over their real interfaces". Scanning
  # EVERY record in the run and merely excluding category
  # real-device-challenge let build-time evidence satisfy it: a
  # `go build ./cmd/lava-api-go` record and a `:api-app:testDebugUnitTest`
  # record — neither of which starts anything — qualified a run in which no
  # live verification happened at all, and the gate then printed
  # "real-interface evidence for both". Only records written UNDER a
  # live_verify phase's own evidence_dir are live-verification evidence.
  # Two live surfaces: the lava-api-go service and the on-device :api-app.
  # A record naming BOTH is credited to NEITHER — "a single probe is not two
  # surfaces exercised, whatever its summary says" was already this gate's
  # stated rule, but it was enforced only when the combined record happened to
  # be the FIRST match for both surfaces. With a go-only record sorting ahead
  # of it, the combined record was quietly credited to api-app and the guard
  # never fired. Excluding both-namers outright makes the rule order-
  # independent, and matches how condition (C) now treats a record that names
  # more than one artifact variant.
  #
  # INDEPENDENT AUDIT 2026-08-25 — this rule was over-corrected. Dropping every
  # both-naming record OUTRIGHT refuses honest runs: the :api-app IS a client of
  # lava-api-go, so an api-app live-verification summary naturally names the
  # service it reached ("the api-app ... reached the running lava-api-go service
  # over the forwarded port and rendered 12 providers"). With a separate,
  # unambiguous lava-api-go record ALSO present, the gate still reported "live
  # surface 'api-app' has no PASSing live-verification Evidence Record of its
  # own" — a wrongly-REFUSED run, and a false statement about the evidence.
  #
  # The property worth keeping is narrower than "discard ambiguity": ONE record
  # must never be TWO surfaces. So records are partitioned three ways, and an
  # ambiguous record may carry AT MOST ONE surface — only when the OTHER surface
  # is carried by a record that names it and nothing else. A lone both-namer
  # still refuses; two both-namers and nothing else still refuse; and the result
  # does not depend on sort order, which is what made the original guard miss.
  e_go_cands=(); e_app_cands=()
  for rec in "${ALL_RECORDS[@]}"; do
    [[ -n "${RECORD_SHAPE_PROBLEM[$rec]:-}" ]] && continue
    r_result="$(_rec "$rec" '.result')"
    [[ "$r_result" != "PASS" ]] && continue
    r_cat="$(_rec "$rec" '.category')"
    # A device-challenge record is build-time evidence, not live
    # verification, so it must not be able to satisfy (D).
    [[ "$r_cat" == "real-device-challenge" ]] && continue
    _under_live_verify "$rec" || continue
    r_id="$(_rec "$rec" '.test_id')"
    r_cmd="$(_rec "$rec" '.command')"
    r_sum="$(_rec "$rec" '.assertion_summary')"
    # SUBJECT FIRST, PROSE SECOND. `test_id` is the record's identifier — the
    # one field that names what was exercised rather than describing it — so it
    # decides the surface whenever it names exactly one. Only when the test_id
    # is silent (or names both) does the free prose of command+summary get a
    # vote, and there the strict rule still applies: naming both attests to
    # neither. This is what separates the two shapes that "names both surfaces"
    # cannot tell apart — `live.api-app.providers` whose summary happens to
    # mention the service it reached (its subject is the api-app) from
    # `live.combined.health` claiming it "checked lava-api-go and api-app
    # together in one probe" (its subject is nothing in particular). Either way
    # a record is credited to AT MOST ONE surface, so one record is never two.
    e_hits_go="no"; e_hits_app="no"
    [[ "$r_id" == *"lava-api-go"* ]] && e_hits_go="yes"
    [[ "$r_id" == *"api-app"* ]] && e_hits_app="yes"
    if [[ "$e_hits_go" == "$e_hits_app" ]]; then
      # test_id names both, or neither: fall back to the whole record's text.
      hay="${r_id} ${r_cmd} ${r_sum}"
      e_hits_go="no"; e_hits_app="no"
      [[ "$hay" == *"lava-api-go"* ]] && e_hits_go="yes"
      [[ "$hay" == *"api-app"* ]] && e_hits_app="yes"
      # Still ambiguous: a single probe is not two surfaces exercised, whatever
      # its summary says, and a record naming neither is evidence for neither.
      [[ "$e_hits_go" == "$e_hits_app" ]] && continue
    fi
    [[ "$e_hits_go" == "yes" ]] && e_go_cands+=("$rec")
    [[ "$e_hits_app" == "yes" ]] && e_app_cands+=("$rec")
  done

  for surface in lava-api-go api-app; do
    if [[ "$surface" == "api-app" ]]; then n_cands="${#e_app_cands[@]}"; else n_cands="${#e_go_cands[@]}"; fi
    if [[ "$n_cands" -eq 0 ]]; then
      e_problems+=("live surface '${surface}' has no PASSing live-verification Evidence Record of its own under a live_verify evidence directory — clause 8(E) requires BOTH the lava-api-go service and the on-device api-app to have been exercised over their real interfaces, and a record whose test_id does not name one surface and whose text names both attests to neither")
    fi
  done

  # Belt and braces: the two surfaces must be carried by DISTINCT records. The
  # attribution above already credits each record to at most one surface, so the
  # lists are disjoint; this keeps that from being lost by a later refactor.
  if [[ "${#e_go_cands[@]}" -gt 0 && "${#e_app_cands[@]}" -gt 0 ]]; then
    e_distinct="no"
    for e_g in "${e_go_cands[@]}"; do
      for e_a in "${e_app_cands[@]}"; do
        [[ "$e_g" != "$e_a" ]] && { e_distinct="yes"; break 2; }
      done
    done
    if [[ "$e_distinct" != "yes" ]]; then
      e_problems+=("the SAME Evidence Record was the only match for both live surfaces — one record is not two surfaces exercised")
    fi
  fi

  if [[ "${#e_problems[@]}" -eq 0 ]]; then
    _set_cond D "PASS" "live_verify PASS with real-interface evidence for both the lava-api-go service and the on-device api-app"
  else
    _set_cond D "FAIL" "$(printf '%s; ' "${e_problems[@]}")"
    COND_WHY[D]="${COND_WHY[D]%; }"
  fi
fi

# =========================================================================
# CONDITION (E) — Unmodified distribute path.
#
# Verified by STATIC INSPECTION ONLY. This script never executes
# scripts/firebase-distribute.sh: doing so would attempt a real upload.
# =========================================================================
f_problems=()
if [[ ! -f "$FIREBASE_SCRIPT" ]]; then
  f_problems+=("the distribute script is missing at ${FIREBASE_SCRIPT} — there is no unmodified path to distribute through")
else
  fb_body="$(cat "$FIREBASE_SCRIPT" 2>/dev/null)"

  # §6.Z clause 6: no bypass flag exists and none may be added.
  bypass_hits="$(grep -nEi -- '--(bypass|skip-tests|skip-gate|no-gate|no-verify-gates|force-distribute)' "$FIREBASE_SCRIPT" 2>/dev/null)"
  if [[ -n "$bypass_hits" ]]; then
    f_problems+=("the distribute script contains bypass-shaped option(s), which §6.Z clause 6 forbids: $(tr '\n' ' ' <<< "$bypass_hits")")
  fi

  # Its own Phase-1 gates must still be present and active.
  for gate_marker in "Gate 1" "Gate 2" "Gate 3" "Gate 7"; do
    if ! grep -qF "$gate_marker" <<< "$fb_body"; then
      f_problems+=("the distribute script no longer mentions '${gate_marker}' — one of its own Phase-1 gates has gone missing")
    fi
  done

  # It must be the committed script, not a locally edited one.
  if git -C "$REPO_PATH" ls-files --error-unmatch "$FIREBASE_SCRIPT" >/dev/null 2>&1; then
    git -C "$REPO_PATH" diff --quiet HEAD -- "$FIREBASE_SCRIPT" >/dev/null 2>&1
    fb_diff_rc=$?
    if [[ "$fb_diff_rc" -ne 0 ]]; then
      f_problems+=("the distribute script has uncommitted local modifications — clause 8(E) requires it to be invoked unmodified")
    fi
  else
    f_problems+=("the distribute script is not tracked by git at ${REPO_PATH}, so 'unmodified' cannot be established")
  fi
fi
if [[ "${#f_problems[@]}" -eq 0 ]]; then
  _set_cond E "PASS" "distribute script present, git-clean, carries its Phase-1 gate markers, and defines no bypass option (inspected only, never executed)"
else
  _set_cond E "FAIL" "$(printf '%s; ' "${f_problems[@]}")"
  COND_WHY[E]="${COND_WHY[E]%; }"
fi

# =========================================================================
# CONDITION (F) — Scope: this pipeline, on master, FR-000 precondition met.
# =========================================================================
g_problems=()
g_branch="$(git -C "$REPO_PATH" branch --show-current 2>/dev/null)"
if [[ "$g_branch" != "master" ]]; then
  g_problems+=("branch is '${g_branch}', not master — clause 8(F) scopes this permission to master only")
fi
g_dirty="$(git -C "$REPO_PATH" status --porcelain 2>/dev/null)"
if [[ -n "$g_dirty" ]]; then
  g_problems+=("working tree is not clean, so the FR-000 precondition guard is not satisfied")
fi
if [[ "$REPORT_USABLE" == "yes" ]]; then
  # INDEPENDENT AUDIT 2026-08-25. This carried the SAME defect FR-009 was
  # repaired for, and the repair was not swept here: reading `.result`
  # unaggregated returns ONE LINE PER MATCHING ENTRY, so two entries concatenate
  # into "PASS\nPASS", which compares unequal to "PASS" and refused a run whose
  # precondition had passed — while the printed reason was truncated at the
  # embedded newline and read "result is 'PASS". Aggregate, exactly as FR-009
  # now does: require at least one entry, and require every one of them to PASS.
  g_pre_count="$(jq -r '[.phases[]? | select(.name=="precondition")] | length' "$REPORT_PATH" 2>/dev/null)"
  g_pre_non_pass="$(jq -r '[.phases[]? | select(.name=="precondition") | select(.result != "PASS") | .result] | join(", ")' "$REPORT_PATH" 2>/dev/null)"
  if [[ ! "$g_pre_count" =~ ^[0-9]+$ ]] || [[ "$g_pre_count" -eq 0 ]]; then
    g_problems+=("the run report records no precondition phase, so FR-000 is unproven for this run")
  elif [[ -n "$g_pre_non_pass" ]]; then
    g_problems+=("precondition phase has ${g_pre_count} entry/entries, of which one or more is not PASS: ${g_pre_non_pass}")
  fi
fi
if [[ "${#g_problems[@]}" -eq 0 ]]; then
  _set_cond F "PASS" "on master with a clean tree and a PASSing FR-000 precondition phase"
else
  _set_cond F "FAIL" "$(printf '%s; ' "${g_problems[@]}")"
  COND_WHY[F]="${COND_WHY[F]%; }"
fi

# =========================================================================
# CONDITION (G) — Disclosed residual gap, recorded through an EXISTING
# run-report property.
#
# REWORDED 2026-08-26 (LVA-147). This condition used to demand a top-level
# `residual-gap` field that pipeline-run-report.schema.json forbids
# (additionalProperties: false), so it could not be satisfied without
# breaking (A). The gap it discloses is unchanged: the pipeline's evidence
# exercises LOCALLY-BUILT artifacts and does NOT cover the Firebase
# upload -> download -> install path on a physical device
# (§6.AA-pipeline-debt, OWED). What changed is where that is recorded:
# build_artifacts[].build_output_path, a property the schema already defines
# and already lists as required, which names the local build output every
# Evidence Record in this run actually exercised.
#
# STATED WITHOUT INFLATION: this check confirms the report NAMES the local
# artifact. It is a disclosure check, not a verification of the release
# build. The substantive release-variant protection is condition (C), and
# nothing here adds to it.
# =========================================================================
if [[ "$REPORT_USABLE" == "yes" ]]; then
  g2_problems=()
  for variant in "${ANDROID_VARIANTS[@]}"; do
    g2_path="$(jq -r --arg a "$variant" '.build_artifacts[]? | select(.artifact_id==$a) | .build_output_path' "$REPORT_PATH" 2>/dev/null)"
    if [[ -z "$g2_path" || "$g2_path" == "null" ]]; then
      g2_problems+=("variant '${variant}' has no build_artifacts[] entry with a build_output_path, so the run report does not name the locally-built file its evidence exercised — clause 8(G) requires that recording, which is how the §6.AA-pipeline-debt Firebase-install gap stays visible in the artifact")
    elif [[ "$(printf '%s\n' "$g2_path" | wc -l)" -ne 1 ]]; then
      g2_problems+=("variant '${variant}' resolves to MORE THAN ONE build_artifacts[] entry, so build_output_path is ambiguous and names no single file")
    fi
  done
  if [[ "$(jq -r 'has("residual-gap")' "$REPORT_PATH" 2>/dev/null)" == "true" ]]; then
    g2_problems+=("the run report carries a top-level 'residual-gap' property — that field was RETIRED on 2026-08-26; it is not in the schema, additionalProperties is false, and condition (A) refuses it")
  fi
  if [[ "${#g2_problems[@]}" -eq 0 ]]; then
    _set_cond G "PASS" "all ${#ANDROID_VARIANTS[@]} variants name a build_output_path in build_artifacts[], so the report records on its face that this run's evidence covers LOCALLY-BUILT artifacts and not a Firebase-delivered install (§6.AA-pipeline-debt remains OWED)"
  else
    _set_cond G "FAIL" "$(printf '%s; ' "${g2_problems[@]}")"
    COND_WHY[G]="${COND_WHY[G]%; }"
  fi
fi

# =========================================================================
# CONDITION (H) — Automatic suspension trigger.
#
# Clause 8(H): on a Crashlytics FATAL on first user-visible interaction or an
# operator-reported cold-start failure, this clause is SUSPENDED
# automatically until an incident record, a RED-then-GREEN covering
# Challenge, AND a written operator re-authorization all exist.
# "Suspension is the default state on failure; re-authorization is an
# explicit act, never an inference from time passing." So a suspension marker
# is only cleared by an explicit, recorded re-authorization — never by age.
# =========================================================================
#
# HONEST SCOPE. Clause 8(H) lifts a suspension on THREE things: (i) a
# §6.Z-class incident record, (ii) a RED-then-GREEN covering Challenge per
# §6.AK clause 2, and (iii) a written operator re-authorization. This gate
# mechanically checks (i) and (iii) only. (ii) has no machine-readable form
# in the incident convention, and inventing one here would assert a check
# that is not happening. It stays operator- and reviewer-verified.
i_problems=()
SUSPENSION_MARKER="${SUSPENSION_DIR}/ACTIVE"

# UNEVALUABLE REFUSES. `-f` on a path inside a mode-000 directory is false, so
# a live suspension sitting inside an unreadable directory read as "no
# suspension marker" and QUALIFIED the run. So did an ACTIVE marker that was
# a DIRECTORY, since `-f` is false for one. Existence is now tested with
# `-e`/`-L` — any inode at that name is a marker — and anything that exists
# but cannot be read refuses rather than reading as "not suspended".
if [[ -e "$SUSPENSION_DIR" || -L "$SUSPENSION_DIR" ]]; then
  if [[ ! -d "$SUSPENSION_DIR" ]]; then
    i_problems+=("the clause-8 suspension path at ${SUSPENSION_DIR} exists but is not a directory, so whether a suspension marker is present cannot be established; an unevaluable suspension state refuses")
  elif [[ ! -r "$SUSPENSION_DIR" || ! -x "$SUSPENSION_DIR" ]]; then
    i_problems+=("the clause-8 suspension directory at ${SUSPENSION_DIR} exists but cannot be read, so whether a suspension is ACTIVE cannot be established; an unevaluable suspension state refuses, it does not read as 'not suspended'")
  fi
fi
if [[ -e "$SUSPENSION_MARKER" || -L "$SUSPENSION_MARKER" ]]; then
  if [[ -f "$SUSPENSION_MARKER" && -r "$SUSPENSION_MARKER" ]]; then
    i_reason="$(head -c 400 "$SUSPENSION_MARKER" 2>/dev/null | tr '\n' ' ')"
    i_problems+=("an ACTIVE clause-8 suspension marker exists at ${SUSPENSION_MARKER} — clause 8 is suspended and every distribution reverts to the clause 1-3 human path until the operator re-authorizes in writing. Recorded reason: ${i_reason}")
  else
    i_problems+=("a clause-8 suspension marker exists at ${SUSPENSION_MARKER} but is not a readable regular file, so its recorded reason cannot be read; a marker that exists is a suspension whether or not this gate can read why")
  fi
fi
INCIDENT_DIR="${REPO_PATH}/.lava-ci-evidence/sixth-law-incidents"
if [[ -e "$INCIDENT_DIR" ]] && { [[ ! -d "$INCIDENT_DIR" ]] || [[ ! -r "$INCIDENT_DIR" ]] || [[ ! -x "$INCIDENT_DIR" ]]; }; then
  i_problems+=("the incident directory at ${INCIDENT_DIR} exists but cannot be read, so an un-reauthorized clause-8 suspension incident cannot be ruled out; an unevaluable suspension state refuses")
elif [[ -d "$INCIDENT_DIR" ]]; then
  while IFS= read -r inc; do
    [[ -z "$inc" ]] && continue
    if [[ ! -r "$inc" ]]; then
      i_problems+=("incident ${inc#"${REPO_PATH}/"} cannot be read, so whether it records an un-reauthorized clause-8 suspension cannot be established")
      continue
    fi
    inc_body="$(cat "$inc" 2>/dev/null)"
    # INDEPENDENT AUDIT 2026-08-25. Reading the re-authorization VALUE fixes
    # nothing for an incident the scan never REACHES. The pattern used to demand
    # the words adjacent — 'clause-8-suspension' or 'clause 8 suspension' — so an
    # incident reading "§6.AA clause 8(H) suspension is ACTIVE" carrying
    # "operator_reauthorization": "none", an explicit WRITTEN DENIAL, was
    # invisible and the gate QUALIFIED. Suspension is clause 8(H)'s DEFAULT state
    # on failure, so the scan must reach any incident that says both "clause 8"
    # and "suspend/suspension" in any order or phrasing. Requiring BOTH keeps
    # unrelated incidents out: the §6.M host-stability records say "suspend"
    # without naming clause 8, and a record that cites clause 8 without a
    # suspension word is not a suspension. Anything this DOES reach is then
    # decided by the re-authorization value below, where a denial always wins.
    if grep -qiE 'clause[-_ ]?8' <<< "$inc_body" && grep -qiE 'suspend|suspension' <<< "$inc_body"; then
      # THE VALUE, NOT THE KEY. The previous check grepped for a
      # `reauthoriz(ed|ation):` KEY and treated any hit as clearance, so an
      # incident recording `"reauthorized": false` — writing down that the
      # operator had NOT re-authorized — CLEARED the suspension, while an
      # incident with no such key at all correctly refused. Adding evidence of
      # non-authorization must never remove a refusal.
      _incident_reauthorized "$inc"
      inc_reauth_rc=$?
      case "$inc_reauth_rc" in
        0) : ;;  # an affirmative, explicitly recorded re-authorization
        1) i_problems+=("incident ${inc#"${REPO_PATH}/"} records a clause-8 suspension with no affirmative operator re-authorization — suspension persists until re-authorization is an explicit, recorded act, and a recorded value of false/no/pending is a DENIAL, never a clearance") ;;
        *) i_problems+=("incident ${inc#"${REPO_PATH}/"} records a clause-8 suspension but could not be parsed to establish whether the operator re-authorized; an unevaluable re-authorization refuses") ;;
      esac
    fi
  done < <(find "$INCIDENT_DIR" -maxdepth 1 -type f -name '*.json' 2>/dev/null | sort)
fi
if [[ "${#i_problems[@]}" -eq 0 ]]; then
  _set_cond H "PASS" "no active clause-8 suspension marker and no un-reauthorized clause-8 suspension incident"
else
  _set_cond H "FAIL" "$(printf '%s; ' "${i_problems[@]}")"
  COND_WHY[H]="${COND_WHY[H]%; }"
fi

# =========================================================================
# VERDICT — conjunctive. Any single failure returns the run to the clause
# 1-3 human path in full; clause 8 states there is no partial qualification.
# =========================================================================
echo ""
echo "---------------------------------------------------------------"
echo "§6.AA clause 8 conditions (conjunctive — all eight must hold)"
echo "---------------------------------------------------------------"

# Re-lettered 2026-08-26: the former (D) "Cycle-coverage on BOTH channels"
# was WITHDRAWN, not renumbered, and former (E)-(I) became (D)-(H). There is
# no condition (I).
declare -A COND_TITLE=(
  [A]="Run Report identity"
  [B]="Unqualified pass"
  [C]="Device evidence for BOTH variants"
  [D]="Live verification of every live surface"
  [E]="Unmodified distribute path"
  [F]="Scope (this pipeline, master, FR-000)"
  [G]="Disclosed residual gap"
  [H]="Automatic suspension trigger"
)

REFUSALS=()
for id in "${CONDITION_IDS[@]}"; do
  verdict="${COND_VERDICT[$id]:-}"
  why="${COND_WHY[$id]:-}"
  if [[ -z "$verdict" ]]; then
    # A condition that never got the chance to run has NOT passed. It
    # refuses, and says why it could not be evaluated.
    verdict="FAIL"
    [[ -z "$why" ]] && why="not evaluated (no usable run report), and a condition that cannot be evaluated refuses"
  fi
  printf 'CONDITION (%s) %-42s : %s\n' "$id" "${COND_TITLE[$id]}" "$verdict"
  printf '    %s\n' "$why"
  case "$verdict" in
    FAIL) REFUSALS+=("(${id}) ${COND_TITLE[$id]}: ${why}") ;;
  esac
done

if [[ "$FR009_VERDICT" != "PASS" ]]; then
  REFUSALS+=("FR-009 baseline: ${FR009_WHY}")
fi

echo ""
echo "NOTE — WHAT THIS GATE DOES NOT CHECK, STATED EVERY RUN:"
echo "    Per-variant §6.AK cycle coverage is NOT verified by anything, here or"
echo "    elsewhere: check-cycle-coverage.sh parses --channel, asserts it"
echo "    non-empty and never reads it again, and no release-variant coverage"
echo "    artifacts exist (the evidence tree partitions by APP, not by build"
echo "    variant). The clause-8 condition that used to gesture at this was"
echo "    WITHDRAWN on 2026-08-26 rather than repaired, because repairing it"
echo "    would have mandated a call that provably does nothing. Tracked as"
echo "    §6.AA-pipeline-debt-D, OWED. Release-variant protection inside"
echo "    clause 8 rests ENTIRELY on condition (C)."
echo ""

# Record the verdict so the run is auditable from the artifact and not only
# from a terminal someone has since closed.
mkdir -p -- "$PHASE_DIR" 2>/dev/null
if [[ "${#REFUSALS[@]}" -eq 0 ]]; then
  gate_state="QUALIFIED"
else
  gate_state="REFUSED"
fi
jq -n \
  --arg run_id "$RUN_ID" \
  --arg evaluated_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg head_sha "${HEAD_SHA:-unknown}" \
  --arg gate "$gate_state" \
  --arg fr009 "$FR009_VERDICT" \
  --arg fr009_why "$FR009_WHY" \
  --argjson conditions "$(
      # INDEPENDENT AUDIT 2026-08-25. These rows used to be printf'd as
      # TAB-separated LINES and split back apart. Condition details quote report
      # and Evidence Record content verbatim, so a report field value carrying a
      # newline and a tab INJECTED rows: the recorded artifact held 11 rows for
      # the 9 conditions clause 8 then had, a row for a condition that produced
      # no such verdict, and a
      # duplicate whose last value read PASS — a clause 8 audit record forgeable
      # from the very artifact it is auditing. jq builds each row from typed
      # arguments instead, so no value can be mistaken for a delimiter. If any
      # row fails to build, the outer jq gets invalid --argjson and the write
      # fails, which ADDS a refusal below — never removes one.
      for id in "${CONDITION_IDS[@]}"; do
        jq -n --arg id "$id" \
              --arg verdict "${COND_VERDICT[$id]:-FAIL}" \
              --arg detail "${COND_WHY[$id]:-not evaluated}" \
              '{id: $id, verdict: $verdict, detail: $detail}'
      done | jq -s '.'
    )" \
  '{
     run_id: $run_id, evaluated_at: $evaluated_at, head_sha: $head_sha,
     gate: $gate, clause: "6.AA-8",
     fr009: {verdict: $fr009, detail: $fr009_why},
     conditions: $conditions,
     per_variant_cycle_coverage: "NOT VERIFIED — §6.AA-pipeline-debt-D, OWED",
     distribute_step: "NOT IMPLEMENTED — this script is the gate only"
   }' > "$VERDICT_PATH" 2>/dev/null
VERDICT_WRITE_RC=$?
# An authorization the gate cannot RECORD is not an authorization. Previously
# this only warned: with the run directory read-only the gate printed
# "verdict artifact: <path>" for a file that did not exist and still returned
# QUALIFIED, leaving clause 8's audit trail silently absent on the one run
# that most needs it. A failed write can only ADD a refusal, never remove one,
# so a run that was already REFUSED keeps refusing for its own reasons.
if [[ "$VERDICT_WRITE_RC" -ne 0 ]] || [[ ! -s "$VERDICT_PATH" ]]; then
  echo "phase-05-distribute: could not write the verdict artifact to ${VERDICT_PATH}" >&2
  REFUSALS+=("verdict artifact: the gate could not record its verdict at ${VERDICT_PATH}, so this run would carry no clause 8 audit record. An authorization that cannot be recorded is not granted")
  VERDICT_RECORDED="no"
else
  VERDICT_RECORDED="yes"
fi

if [[ "${#REFUSALS[@]}" -gt 0 ]]; then
  echo "==============================================================="
  echo "GATE REFUSED — ${#REFUSALS[@]} condition(s) failed"
  echo "==============================================================="
  echo "§6.AA clause 8 is conjunctive: failure of any single condition"
  echo "returns this distribution to the clause 1-3 human path in full."
  echo "There is no partial qualification, and no flag can override this."
  echo ""
  for r in "${REFUSALS[@]}"; do
    echo "  REFUSED: ${r}"
  done
  echo ""
  if [[ "$VERDICT_RECORDED" == "yes" ]]; then
    echo "verdict artifact: ${VERDICT_PATH#"${REPO_PATH}/"}"
  else
    echo "verdict artifact: NOT WRITTEN (${VERDICT_PATH#"${REPO_PATH}/"} could not be created)"
  fi
  exit 2
fi

echo "==============================================================="
echo "GATE QUALIFIED — all eight clause 8 conditions hold"
echo "==============================================================="
echo "verdict artifact: ${VERDICT_PATH#"${REPO_PATH}/"}"
echo ""

# ===========================================================================
# TODO(T043, follow-up task) — THE DISTRIBUTE STEP ITSELF.
# ===========================================================================
# Deliberately not implemented in this slice. When it lands it must:
#   1. invoke scripts/firebase-distribute.sh UNMODIFIED, TWICE — once
#      --debug-only, then once --release-only — with all of its own Phase-1
#      gates active (clause 8(E)); never `firebase appdistribution:
#      distribute` directly, which is a §6.Z violation whether or not a
#      pipeline is running, and never a combined invocation, which no longer
#      exists (LVA-120, retired 2026-08-26);
#   2. write one Distribution Record per uploaded artifact, whose
#      version_code equals the versionCode compiled into that artifact;
#   3. append its result to the run report via append_phase_result;
#   4. define what happens when the debug stage succeeds and the release
#      stage refuses — a partially-distributed cycle is now reachable in a way
#      the single combined invocation never made it, and that semantics is
#      UNDECIDED (recorded as draft item D-2).
# Until then this exits non-zero: a gate that returned success while
# distributing nothing would be its own small bluff.
# ===========================================================================
echo "distribute step not implemented — gate-only. Nothing was distributed."
exit 3
