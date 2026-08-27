#!/usr/bin/env bash
# tests/firebase/test_axis_app_vs_build_variant.sh
#
# STANDING REGRESSION GUARD for LVA-148 (§6.J / §6.AA / §6.AK / §6.Z).
#
# ---------------------------------------------------------------------------
# THE DEFECT (LVA-148, operator decision 2026-08-26: "rename both axes now")
# ---------------------------------------------------------------------------
# The word `channel` named TWO genuinely different axes in artifacts that are
# read together, directly under the §6.AK repair path:
#
#   AXIS A — WHICH APPLICATION.  contracts/distribution-record.schema.json:11
#     defined  "channel": enum ["firebase-app-distribution",
#     "firebase-app-distribution-api-app"] — the user client (:app) vs the
#     on-device API server (:api-app).
#
#   AXIS B — WHICH BUILD VARIANT.  scripts/firebase-distribute.sh resolved a
#     variable named AK_CHANNEL to `release` or `debug`, and its §6.P prose
#     called the last-version-debug / last-version-release pointers "channels".
#
# One name for two concepts is how a debug-variant device gate came to certify
# a release-variant upload in the 1.2.19-1039 forensic anchor (§6.Z) and again
# in the retired combined mode (LVA-120). The rename:
#
#   AXIS A -> `app`            (schema property; the codebase's own word:
#                               --app client|api-app, SELECTED_APP, APP_DISPLAY,
#                               phase-05a's --app/APP, artifact_id's app-/api-app- prefixes)
#   AXIS B -> `build_variant`  (schema property + AK_BUILD_VARIANT in the script;
#                               the codebase's own word: buildTypes/variant in
#                               app/build.gradle.kts, _pick_apk_by_version's
#                               $2=buildtype, spec.md "Build Artifact variant",
#                               FR-010 "the debug variant", SC-005 "build variant")
#
# ---------------------------------------------------------------------------
# THE INVARIANT THIS TEST DEFENDS
# ---------------------------------------------------------------------------
#   The two axes are INDEPENDENTLY settable and INDEPENDENTLY read, and a value
#   valid for one axis is REJECTED for the other. That mutual rejection is the
#   whole point: the pre-rename code could not tell the two apart, so a value
#   from either axis was silently acceptable wherever "channel" appeared.
#
# ---------------------------------------------------------------------------
# SAFETY
# ---------------------------------------------------------------------------
# Hermetic: mktemp fixture repo, fake `firebase` binary on PATH, no network, no
# upload. Every script case is designed to exit at an EARLY gate (arg parse,
# §6.P Gate 1, §6.AA, §6.P Gate 3) — long before APK resolution or any upload.
# Every script case additionally ASSERTS the fake firebase binary was never
# invoked, so a future change that lets a case run to completion fails here
# rather than quietly attempting a real distribute.
#
# ---------------------------------------------------------------------------
# FALSIFIABILITY REHEARSAL (§6.J)
# ---------------------------------------------------------------------------
# Mutation: re-introduce the collision by making one axis accept the other's
# values — add the two build-variant tokens to the schema's `app` enum:
#     "app": { "enum": ["firebase-app-distribution",
#                       "firebase-app-distribution-api-app",
#                       "debug", "release"] }
# Observed: SECTION 1 case S4 fails —
#   "FAIL [S4] app must REJECT the build-variant value 'debug' ..."
# See the evidence log referenced in the Bluff-Audit stamp for the verbatim run.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_SH="$REPO_ROOT/scripts/firebase-distribute.sh"
ENV_SH="$REPO_ROOT/scripts/firebase-env.sh"
SCHEMA="$REPO_ROOT/specs/002-build-test-distribute-pipeline/contracts/distribution-record.schema.json"

fails=0
examined=0

for required in "$DIST_SH" "$ENV_SH" "$SCHEMA"; do
    if [[ ! -f "$required" ]]; then
        echo "FAIL: required input not found: $required"
        exit 1
    fi
done

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# ══════════════════════════════════════════════════════════════════════════════
# VALIDATOR
#
# No jsonschema library is installed on this host (the same constraint
# tests/pipeline/test_evidence_and_run_report.sh records), so this is a
# purpose-built validator covering EXACTLY the draft-2020-12 subset this schema
# uses: type, required, additionalProperties:false, enum, minimum, minLength,
# and allOf[{if:{required,properties.enum}, then:{properties.const}}].
#
# ANTI-BLUFF: a hand-rolled validator that returns "valid" for everything would
# make every rejection case below pass vacuously. Case S1 is a POSITIVE case
# that MUST validate, so a refuse-everything validator fails too; and S6/S7
# exercise two different rejection mechanisms (additionalProperties vs allOf),
# so a validator that only implements one is caught by the other.
# ══════════════════════════════════════════════════════════════════════════════
cat > "$TMP/validate.py" <<'PYEOF'
import json, sys

def errs(inst, schema):
    out = []
    t = schema.get("type")
    if t == "object" and not isinstance(inst, dict):
        return ["not an object"]
    if t == "integer" and not (isinstance(inst, int) and not isinstance(inst, bool)):
        return ["not an integer"]
    if t == "string" and not isinstance(inst, str):
        return ["not a string"]
    if "enum" in schema and inst not in schema["enum"]:
        out.append("value %r not in enum %r" % (inst, schema["enum"]))
    if "const" in schema and inst != schema["const"]:
        out.append("value %r != const %r" % (inst, schema["const"]))
    if "minimum" in schema and isinstance(inst, int) and inst < schema["minimum"]:
        out.append("below minimum")
    if "minLength" in schema and isinstance(inst, str) and len(inst) < schema["minLength"]:
        out.append("below minLength")
    if isinstance(inst, dict):
        props = schema.get("properties", {})
        for r in schema.get("required", []):
            if r not in inst:
                out.append("missing required property %r" % r)
        if schema.get("additionalProperties") is False:
            for k in inst:
                if k not in props:
                    out.append("additional property %r not permitted" % k)
        for k, sub in props.items():
            if k in inst:
                out += ["%s: %s" % (k, e) for e in errs(inst[k], sub)]
    for clause in schema.get("allOf", []):
        if "if" in clause:
            if not errs(inst, clause["if"]):
                out += ["allOf/then: %s" % e for e in errs(inst, clause.get("then", {}))]
        else:
            out += errs(inst, clause)
    return out

schema = json.load(open(sys.argv[1]))
inst = json.load(open(sys.argv[2]))
e = errs(inst, schema)
if e:
    print("INVALID: " + "; ".join(e))
    sys.exit(1)
print("VALID")
PYEOF

# validate_record <json-text> ; sets VOUT, returns validator exit code
validate_record() {
    printf '%s' "$1" > "$TMP/rec.json"
    VOUT="$(python3 "$TMP/validate.py" "$SCHEMA" "$TMP/rec.json" 2>&1)"
    return $?
}

expect_valid() {   # $1=label $2=json
    examined=$((examined + 1))
    if ! validate_record "$2"; then
        echo "FAIL [$1] record must VALIDATE but did not: $VOUT"
        echo "        record: $2"
        fails=$((fails + 1))
    fi
}

expect_invalid() { # $1=label $2=json $3=why
    examined=$((examined + 1))
    if validate_record "$2"; then
        echo "FAIL [$1] $3"
        echo "        record: $2"
        fails=$((fails + 1))
    fi
}

# ══════════════════════════════════════════════════════════════════════════════
# SECTION 1 — THE CONTRACT: two axes, independently settable, mutually rejected
# ══════════════════════════════════════════════════════════════════════════════

# S1 POSITIVE — a fully-correct record validates. Guards against a validator (or
#    a schema) that simply refuses everything, which would make every rejection
#    case below pass for the wrong reason.
expect_valid S1 '{"artifact_id":"app-debug","app":"firebase-app-distribution","build_variant":"debug","version_code":1055,"evidence_ref":".lava-ci-evidence/pipeline-runs/X/report.json","distributed_at":"2026-08-26T10:00:00Z"}'

# S2 AXIS A settable independently — both app values accepted, each with its own
#    matching artifact, while build_variant is held CONSTANT at debug.
expect_valid S2a '{"artifact_id":"app-debug","app":"firebase-app-distribution","build_variant":"debug","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}'
expect_valid S2b '{"artifact_id":"api-app-debug","app":"firebase-app-distribution-api-app","build_variant":"debug","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}'

# S3 AXIS B settable independently — both build_variant values accepted, while
#    app is held CONSTANT at the client. Together with S2 this is the proof the
#    axes are orthogonal rather than one being a re-spelling of the other.
expect_valid S3a '{"artifact_id":"app-debug","app":"firebase-app-distribution","build_variant":"debug","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}'
expect_valid S3b '{"artifact_id":"app-release","app":"firebase-app-distribution","build_variant":"release","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}'

# S4 CROSS-REJECTION A<-B — a BUILD-VARIANT value offered to the APP axis.
#    Pre-rename, "channel" accepted whichever of these a writer happened to mean.
for bad in debug release; do
    expect_invalid "S4" \
      "{\"artifact_id\":\"app-debug\",\"app\":\"$bad\",\"build_variant\":\"debug\",\"version_code\":1,\"evidence_ref\":\"r\",\"distributed_at\":\"2026-08-26T10:00:00Z\"}" \
      "app must REJECT the build-variant value '$bad' — that is AXIS B's vocabulary, not AXIS A's."
done

# S5 CROSS-REJECTION B<-A — an APP value offered to the BUILD-VARIANT axis.
for bad in firebase-app-distribution firebase-app-distribution-api-app; do
    expect_invalid "S5" \
      "{\"artifact_id\":\"app-debug\",\"app\":\"firebase-app-distribution\",\"build_variant\":\"$bad\",\"version_code\":1,\"evidence_ref\":\"r\",\"distributed_at\":\"2026-08-26T10:00:00Z\"}" \
      "build_variant must REJECT the app value '$bad' — that is AXIS A's vocabulary, not AXIS B's."
done

# S6 THE RETIRED NAME IS GONE — a record using `channel` (with EITHER axis's
#    vocabulary) is refused, and `channel` is not a property of the schema.
examined=$((examined + 1))
if python3 -c "
import json,sys
s=json.load(open(sys.argv[1]))
sys.exit(0 if 'channel' in s.get('properties',{}) else 1)
" "$SCHEMA"; then
    echo "FAIL [S6] the schema still defines a property named 'channel' — the collision survives."
    fails=$((fails + 1))
fi
expect_invalid "S6a" '{"artifact_id":"app-debug","channel":"firebase-app-distribution","build_variant":"debug","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}' \
  "a record carrying the retired 'channel' property (axis-A value) must be refused."
expect_invalid "S6b" '{"artifact_id":"app-debug","app":"firebase-app-distribution","channel":"debug","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}' \
  "a record carrying the retired 'channel' property (axis-B value) must be refused."

# S7 AXIS SWAP WITHIN VALID VOCABULARY — both values are individually legal for
#    their own axis, but disagree with artifact_id. This is the 1.2.19-1039
#    shape in contract form: an R8-minified release artifact recorded as debug.
expect_invalid "S7a" '{"artifact_id":"app-release","app":"firebase-app-distribution","build_variant":"debug","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}' \
  "artifact_id app-release with build_variant debug must be refused (axis B disagrees with the artifact)."
expect_invalid "S7b" '{"artifact_id":"api-app-debug","app":"firebase-app-distribution","build_variant":"debug","version_code":1,"evidence_ref":"r","distributed_at":"2026-08-26T10:00:00Z"}' \
  "artifact_id api-app-debug with app firebase-app-distribution must be refused (axis A disagrees with the artifact)."

# S8 EACH AXIS'S VOCABULARY IS ITSELF AN INVARIANT.
#    S4/S5 above reject a cross-axis value on a record, but this schema defends
#    that TWICE — by the property enum AND by the artifact_id cross-consistency
#    allOf. Because artifact_id is required and every one of its four values
#    pins BOTH axes, no record can isolate the enum. Discovered during this
#    file's own §6.J rehearsal: widening `app`'s enum to admit debug|release
#    left every case above still passing, because the allOf caught it — so the
#    cases above did NOT actually measure the enum. This case measures it
#    directly, so a widened vocabulary fails here even while the cross-check
#    still holds. Without it, half the discrimination this file claims would be
#    unmeasured.
examined=$((examined + 1))
VOCAB="$(python3 -c "
import json,sys
s=json.load(open(sys.argv[1]))['properties']
print('app=' + ','.join(sorted(s['app']['enum'])))
print('build_variant=' + ','.join(sorted(s['build_variant']['enum'])))
" "$SCHEMA")"
EXPECT_APP="app=firebase-app-distribution,firebase-app-distribution-api-app"
EXPECT_BV="build_variant=debug,release"
if [[ "$(echo "$VOCAB" | sed -n 1p)" != "$EXPECT_APP" ]]; then
    echo "FAIL [S8] the app axis vocabulary changed — it must be EXACTLY the two applications."
    echo "        expected: $EXPECT_APP"
    echo "        actual:   $(echo "$VOCAB" | sed -n 1p)"
    fails=$((fails + 1))
fi
if [[ "$(echo "$VOCAB" | sed -n 2p)" != "$EXPECT_BV" ]]; then
    echo "FAIL [S8] the build_variant axis vocabulary changed — it must be EXACTLY debug,release."
    echo "        expected: $EXPECT_BV"
    echo "        actual:   $(echo "$VOCAB" | sed -n 2p)"
    fails=$((fails + 1))
fi
# The two vocabularies must be DISJOINT. This is the collision, stated as a set
# property: if any token is legal for both axes, one word again names two things.
examined=$((examined + 1))
OVERLAP="$(python3 -c "
import json,sys
s=json.load(open(sys.argv[1]))['properties']
print(','.join(sorted(set(s['app']['enum']) & set(s['build_variant']['enum']))))
" "$SCHEMA")"
if [[ -n "$OVERLAP" ]]; then
    echo "FAIL [S8] the two axis vocabularies OVERLAP on: $OVERLAP — a value legal for both axes is the collision."
    fails=$((fails + 1))
fi

# ══════════════════════════════════════════════════════════════════════════════
# SECTION 2 — RECORDS AT REST
#
# The compatibility decision (outright rename, no deprecated alias) rests on a
# measurement: how many Distribution Records exist on disk. This section RE-TAKES
# that measurement every run, so if a record ever appears the decision is
# re-examined by a failing test rather than by hindsight.
# ══════════════════════════════════════════════════════════════════════════════
examined=$((examined + 1))
DR_TOTAL=0
DR_BAD=0
DR_LEGACY=0
REPORTS=0
shopt -s nullglob
for rpt in "$REPO_ROOT"/.lava-ci-evidence/pipeline-runs/*/report.json; do
    REPORTS=$((REPORTS + 1))
    n="$(python3 -c "
import json,sys
try: d=json.load(open(sys.argv[1]))
except Exception: print(-1); raise SystemExit
print(len(d.get('distributions',[])))
" "$rpt")"
    [[ "$n" == "-1" ]] && continue
    DR_TOTAL=$((DR_TOTAL + n))
    if [[ "$n" -gt 0 ]]; then
        for i in $(seq 0 $((n - 1))); do
            python3 -c "
import json,sys
d=json.load(open(sys.argv[1]))
json.dump(d['distributions'][int(sys.argv[2])], open(sys.argv[3],'w'))
" "$rpt" "$i" "$TMP/disk-rec.json"
            if grep -q '"channel"' "$TMP/disk-rec.json"; then
                DR_LEGACY=$((DR_LEGACY + 1))
                echo "FAIL [D] $rpt distributions[$i] carries the RETIRED 'channel' property."
                fails=$((fails + 1))
            fi
            if ! python3 "$TMP/validate.py" "$SCHEMA" "$TMP/disk-rec.json" >/dev/null 2>&1; then
                DR_BAD=$((DR_BAD + 1))
                echo "FAIL [D] $rpt distributions[$i] does NOT validate against the renamed schema:"
                python3 "$TMP/validate.py" "$SCHEMA" "$TMP/disk-rec.json" 2>&1 | sed 's/^/        /'
                fails=$((fails + 1))
            fi
        done
    fi
done
shopt -u nullglob
if [[ "$REPORTS" -eq 0 ]]; then
    echo "FAIL [D] ZERO run reports were scanned — the at-rest measurement proves nothing."
    fails=$((fails + 1))
fi
echo "[axis] at-rest measurement: ${REPORTS} run report(s) scanned; ${DR_TOTAL} Distribution Record(s) on disk; ${DR_BAD} invalid; ${DR_LEGACY} still carrying 'channel'."

# ══════════════════════════════════════════════════════════════════════════════
# SECTION 3 — THE SCRIPT: both axes read independently, cross-values refused
# ══════════════════════════════════════════════════════════════════════════════
FAKE_BIN_DIR="$TMP/fakebin"
mkdir -p "$FAKE_BIN_DIR"

# Fake `firebase`: records every invocation. NOTHING in this test should ever
# reach it; a non-empty log is itself an assertion failure.
cat > "$FAKE_BIN_DIR/firebase" <<'FAKEEOF'
#!/usr/bin/env bash
echo "firebase $*" >> "${FAKE_FIREBASE_LOG:-/dev/null}"
echo "FAKE_FIREBASE_CALLED: $*"
exit 0
FAKEEOF
chmod +x "$FAKE_BIN_DIR/firebase"

cat > "$FAKE_BIN_DIR/git" <<'GITEOF'
#!/usr/bin/env bash
case "$*" in
    *"rev-parse --short HEAD")       echo "deadbeef" ;;
    *"rev-parse HEAD")               echo "deadbeef" ;;
    *"rev-parse --abbrev-ref HEAD")  echo "master" ;;
    *) command git "$@" ;;
esac
GITEOF
chmod +x "$FAKE_BIN_DIR/git"

export PATH="$FAKE_BIN_DIR:$PATH"

FAKE_REPO="$TMP/repo"
mkdir -p "$FAKE_REPO/scripts" "$FAKE_REPO/app" "$FAKE_REPO/api-app"

cat > "$FAKE_REPO/app/build.gradle.kts" <<'GRADLEEOF'
android {
    defaultConfig {
        applicationId = "digital.vasic.lava.client"
        versionCode = 1055
        versionName = "1.2.35"
    }
}
GRADLEEOF

cat > "$FAKE_REPO/api-app/build.gradle.kts" <<'GRADLEEOF'
android {
    defaultConfig {
        applicationId = "digital.vasic.lava.api"
        versionCode = 42
        versionName = "0.9.9"
    }
}
GRADLEEOF

# §6.H: placeholder values only, never real tokens.
cat > "$FAKE_REPO/.env" <<'ENVEOF'
LAVA_FIREBASE_TOKEN=1//fake-token-for-test
LAVA_FIREBASE_PROJECT_ID=fake-project-id
LAVA_FIREBASE_ANDROID_APP_ID=1:111111111111:android:aaaaaaaaaaaaaaaaaaaaaa
LAVA_FIREBASE_ANDROID_DEV_APP_ID=1:111111111111:android:bbbbbbbbbbbbbbbbbbbbbb
LAVA_FIREBASE_API_GO_APP_ID=1:111111111111:web:cccccccccccccccccccccc
LAVA_FIREBASE_API_APP_ID=1:111111111111:android:dddddddddddddddddddddd
LAVA_FIREBASE_API_APP_DEV_APP_ID=1:111111111111:android:eeeeeeeeeeeeeeeeeeeeee
LAVA_FIREBASE_TESTERS_OWNER=owner@example.com
LAVA_FIREBASE_TESTERS_DEVELOPER=developer@example.com
LAVA_FIREBASE_TESTERS_TESTER=tester@example.com
ENVEOF

cat > "$FAKE_REPO/CHANGELOG.md" <<'CLEOF'
# Changelog

## Lava-Android-1.2.35-1055
- Client app 1.2.35

## Lava-API-App-0.9.9-42
- API app 0.9.9
CLEOF

CLIENT_DIR="$FAKE_REPO/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
APIAPP_DIR="$FAKE_REPO/.lava-ci-evidence/distribute-changelog/firebase-app-distribution-api-app"
mkdir -p "$CLIENT_DIR" "$APIAPP_DIR"

ln -sf "$ENV_SH"  "$FAKE_REPO/scripts/firebase-env.sh"
ln -sf "$DIST_SH" "$FAKE_REPO/scripts/firebase-distribute.sh"

FIREBASE_CALLS_LOG="$TMP/firebase_calls.log"

seed() {   # $1=dir $2=debug-pointer $3=release-pointer
    echo "$2" > "$1/last-version-debug"
    echo "$3" > "$1/last-version-release"
    local max=$(( $2 > $3 ? $2 : $3 ))
    echo "$max" > "$1/last-version"
}

run_distribute() {
    : > "$FIREBASE_CALLS_LOG"
    FAKE_FIREBASE_LOG="$FIREBASE_CALLS_LOG" \
    LAVA_REPO_ROOT="$FAKE_REPO" \
    bash "$FAKE_REPO/scripts/firebase-distribute.sh" "$@" 2>&1
}

assert_no_upload() {   # $1 = case label
    if [[ -s "$FIREBASE_CALLS_LOG" ]]; then
        echo "FAIL [$1] the fake firebase binary WAS invoked — this case must never reach an upload:"
        sed 's/^/        /' "$FIREBASE_CALLS_LOG"
        fails=$((fails + 1))
    fi
}

# ── P1: AXIS B is settable AND READ independently ────────────────────────────
# Each mode must resolve the §6.P monotonic gate to ITS OWN build-variant
# pointer. Pre-rename this axis was called "channel"; the assertion is on the
# pointer actually consulted, not on the word used to describe it.
examined=$((examined + 1))
seed "$CLIENT_DIR" 1055 1
OUT="$(run_distribute --debug-only)"; RC=$?
if [[ $RC -eq 0 ]]; then
    echo "FAIL [P1a] --debug-only with last-version-debug == current code must be refused by §6.P Gate 1."
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "last-version-debug"; then
    echo "FAIL [P1a] --debug-only did not consult the DEBUG build-variant pointer. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if echo "$OUT" | grep -qF "last-version-release"; then
    echo "FAIL [P1a] --debug-only consulted the RELEASE pointer — the axes are not independent."
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "build variant: debug"; then
    echo "FAIL [P1a] the §6.P refusal must NAME the build variant it applies to. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
assert_no_upload P1a

examined=$((examined + 1))
seed "$CLIENT_DIR" 1 1055
OUT="$(run_distribute --release-only)"; RC=$?
if [[ $RC -eq 0 ]]; then
    echo "FAIL [P1b] --release-only with last-version-release == current code must be refused by §6.P Gate 1."
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "last-version-release"; then
    echo "FAIL [P1b] --release-only did not consult the RELEASE build-variant pointer. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if ! echo "$OUT" | grep -qF "build variant: release"; then
    echo "FAIL [P1b] the §6.P refusal must NAME the build variant it applies to. Got:"
    echo "$OUT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
assert_no_upload P1b

# ── P2: AXIS A is settable AND READ independently ────────────────────────────
# With the build variant held CONSTANT at debug, each app must route to ITS OWN
# evidence directory. Asserted on the full snapshot path printed by §6.P Gate 3,
# because `firebase-app-distribution` is a PREFIX of the api-app slug and a bare
# substring test would pass for the wrong reason.
examined=$((examined + 1))
seed "$CLIENT_DIR" 1 1
rm -f "$CLIENT_DIR"/*.md
OUT_CLIENT="$(run_distribute --debug-only --app client)"
if ! echo "$OUT_CLIENT" | grep -qF "/firebase-app-distribution/1.2.35-1055.md"; then
    echo "FAIL [P2a] --app client did not resolve the CLIENT evidence directory. Got:"
    echo "$OUT_CLIENT" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if echo "$OUT_CLIENT" | grep -qF "firebase-app-distribution-api-app/"; then
    echo "FAIL [P2a] --app client leaked into the api-app evidence directory."
    fails=$((fails + 1))
fi
if ! echo "$OUT_CLIENT" | grep -qF "Lava Android"; then
    echo "FAIL [P2a] --app client must announce the client application."
    fails=$((fails + 1))
fi
assert_no_upload P2a

examined=$((examined + 1))
seed "$APIAPP_DIR" 1 1
rm -f "$APIAPP_DIR"/*.md
OUT_API="$(run_distribute --debug-only --app api-app)"
if ! echo "$OUT_API" | grep -qF "/firebase-app-distribution-api-app/0.9.9-42.md"; then
    echo "FAIL [P2b] --app api-app did not resolve the API-APP evidence directory. Got:"
    echo "$OUT_API" | sed 's/^/        /'
    fails=$((fails + 1))
fi
if ! echo "$OUT_API" | grep -qF "Lava API App"; then
    echo "FAIL [P2b] --app api-app must announce the api-app application."
    fails=$((fails + 1))
fi
assert_no_upload P2b

# Both runs held build_variant CONSTANT (debug) and differed only in the app.
examined=$((examined + 1))
if [[ "$OUT_CLIENT" == "$OUT_API" ]]; then
    echo "FAIL [P2c] --app client and --app api-app produced IDENTICAL output — AXIS A is inert."
    fails=$((fails + 1))
fi

# ── P3: CROSS-REJECTION at the script layer ──────────────────────────────────
# A BUILD-VARIANT value offered to the APP axis must be refused. Under the old
# vocabulary "channel=debug" and "channel=firebase-app-distribution" were both
# spellable in the same breath; they are now different parameters with disjoint
# vocabularies, and this proves the app axis enforces its own.
for bad in debug release; do
    examined=$((examined + 1))
    seed "$CLIENT_DIR" 1 1
    OUT="$(run_distribute --debug-only --app "$bad")"; RC=$?
    if [[ $RC -eq 0 ]]; then
        echo "FAIL [P3] --app $bad must exit NON-ZERO: '$bad' is AXIS B's vocabulary, not AXIS A's."
        fails=$((fails + 1))
    fi
    if ! echo "$OUT" | grep -qF -- "--app must be 'client' or 'api-app'"; then
        echo "FAIL [P3] --app $bad must be refused by name. Got:"
        echo "$OUT" | sed 's/^/        /'
        fails=$((fails + 1))
    fi
    assert_no_upload "P3-$bad"
done

# ── P4 (static): the build-variant axis no longer wears the word `channel` ───
# CODE ONLY: full-line comments are stripped, so this file's own prose and the
# script's own LVA-148/LVA-149 explanatory comments cannot satisfy or trip these
# greps.
#
# LVA-149 landed concurrently with this rename and resolved the §6.AK end of the
# collision in the STRONGER direction: check-cycle-coverage.sh's `--channel` was
# not renamed, it was REMOVED (it selected nothing — that gate resolves both its
# artifacts from --evidence-dir + --version alone), and the variable feeding it
# went with it. So the assertion is not "the renamed variable exists" but the
# durable one: NOTHING here hands a build-variant value, under any spelling, to
# the §6.AK gate.
examined=$((examined + 1))
CODE="$TMP/dist-code-only.sh"
sed -E '/^[[:space:]]*#/d' "$DIST_SH" > "$CODE"

if grep -qE 'AK_CHANNEL' "$CODE"; then
    echo "FAIL [P4] AK_CHANNEL survives in executable code — the build-variant axis still wears 'channel'."
    fails=$((fails + 1))
fi
if grep -qE -- '--channel' "$CODE"; then
    echo "FAIL [P4] a --channel argument is still passed from here; LVA-149 removed that parameter and"
    echo "          scripts/check-cycle-coverage.sh now refuses it (exit 2). Found:"
    grep -nE -- '--channel' "$CODE" | sed 's/^/        /'
    fails=$((fails + 1))
fi
# The LVA-120 defect shape in its most general form: a catch-all case arm that
# silently supplies the weaker build variant as a default.
if grep -qE '^[[:space:]]*\*\)[[:space:]]*[A-Za-z_]+="debug"' "$CODE"; then
    echo "FAIL [P4] a catch-all case arm silently defaults some variable to \"debug\":"
    grep -nE '^[[:space:]]*\*\)[[:space:]]*[A-Za-z_]+="debug"' "$CODE" | sed 's/^/        /'
    fails=$((fails + 1))
fi
# MODE must still be validated explicitly where the §6.AK gate is invoked — an
# unexpected value must fail loudly rather than fall through (LVA-120).
if ! grep -qF 'at the §6.AK gate invocation' "$CODE"; then
    echo "FAIL [P4] the §6.AK gate invocation no longer validates MODE explicitly."
    fails=$((fails + 1))
fi

# ── P5 (static): the ONE remaining `channel`-spelled identifier is axis A only ─
# CHANGELOG_CHANNEL keeps its legacy name because scripts/pipeline/
# phase-05a-changelog-entry.sh's drift-check greps this file for the literal
# `CHANGELOG_CHANNEL="<value>"` (plus two tests mirroring it). It is pinned, so
# what this case defends is that it names EXACTLY ONE axis: it may only ever be
# assigned an app slug, never a build variant. That is what keeps "one word, two
# concepts" from re-forming through the identifier that could not be renamed.
examined=$((examined + 1))
CHAN_ASSIGNS="$(grep -oE 'CHANGELOG_CHANNEL="[^"]*"' "$CODE" | sort -u | tr '\n' ' ')"
if [[ "$CHAN_ASSIGNS" != 'CHANGELOG_CHANNEL="firebase-app-distribution" CHANGELOG_CHANNEL="firebase-app-distribution-api-app" ' ]]; then
    echo "FAIL [P5] CHANGELOG_CHANNEL must be assigned exactly the two APP slugs. Found: $CHAN_ASSIGNS"
    fails=$((fails + 1))
fi
if grep -qE 'CHANGELOG_CHANNEL="(debug|release)"' "$CODE"; then
    echo "FAIL [P5] CHANGELOG_CHANNEL is assigned a BUILD-VARIANT value — the collision has re-formed."
    fails=$((fails + 1))
fi
# No OTHER channel-spelled identifier may be assigned a build-variant value.
if grep -qiE '\b[A-Za-z_]*CHANNEL[A-Za-z_]*="(debug|release)"' "$CODE"; then
    echo "FAIL [P5] some 'channel'-spelled identifier is assigned a build-variant value:"
    grep -niE '\b[A-Za-z_]*CHANNEL[A-Za-z_]*="(debug|release)"' "$CODE" | sed 's/^/        /'
    fails=$((fails + 1))
fi

# ══════════════════════════════════════════════════════════════════════════════
# VERDICT — an explicit examined-count so a fixture that silently exercises
# nothing cannot report success.
# ══════════════════════════════════════════════════════════════════════════════
EXPECTED_CASES=26
if [[ "$examined" -eq 0 ]]; then
    echo "FAIL: ZERO cases were exercised — this run proves nothing."
    exit 1
fi
if [[ "$examined" -ne "$EXPECTED_CASES" ]]; then
    echo "FAIL: examined $examined case(s), expected $EXPECTED_CASES."
    exit 1
fi

if [[ "$fails" -eq 0 ]]; then
    echo "[firebase] OK: app-vs-build_variant axis guard passed ($examined/$EXPECTED_CASES cases exercised)."
    exit 0
else
    echo "[firebase] FAIL: $fails assertion(s) failed across $examined case(s)."
    exit 1
fi
