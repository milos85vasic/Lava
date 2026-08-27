#!/usr/bin/env bash
# Hermetic test suite for the RUN ISOLATION property of
# scripts/pipeline/phase-02-test-go.sh: a `go` test-category run must not
# modify any TRACKED file in the working tree.
#
# No real Go toolchain, no real lava-api-go: a stub `go` on PATH replays a
# captured real `go test -json` stream AND faithfully reproduces the
# side-effect that the real Go suite has — writing stress/chaos evidence via
# the resolver both real test files implement.
#
# WHY THIS SUITE EXISTS (forensic anchor, 2026-08-23):
# The first genuine end-to-end pipeline run proved the `go` category rewrites
# six TRACKED files under .lava-ci-evidence/stress-chaos/jackett/*.json on
# every run. They are not gitignored (`git check-ignore` exits 1) and are
# tracked (`git ls-files --error-unmatch` succeeds). So a SUCCESSFUL run
# dirties the working tree, and the NEXT invocation is refused by FR-000 with
# exit 2, "working tree is not clean". Proof: run 2026-08-23T10-17-26Z
# succeeded; run 2026-08-23T10-31-21Z was refused, exit 2.
#
# That falsifies quickstart.md Scenario 5's claim that "everything the first
# run produced is gitignored", and it blocks FR-018 (every run restarts from
# scratch) and SC-007. A pipeline that can only ever run once is not a
# pipeline.
#
# The writers are the Go test files themselves, not a wrapper:
#   lava-api-go/internal/jackett/stress_chaos_test.go:53  evidenceDir()
#   lava-api-go/internal/handlers/v1/jackett_stress_chaos_test.go:42
#                                                    handlerEvidenceDir()
# Both walk UP from the test's working directory looking for a
# `.lava-ci-evidence` directory, then write into its `stress-chaos/jackett`
# subdir. The payload carries `captured_at` plus measured latency
# percentiles, so the content differs on every run by construction.
#
# WHY THE STUB IS NOT CIRCULAR:
# `fake_go` below is a line-for-line shell transcription of that real Go
# resolver — walk up for `.lava-ci-evidence`, else fall back. The thing under
# test is whether the WRAPPER isolates that resolver's output into the run's
# own gitignored evidence directory. If the wrapper does nothing, the stub
# resolves to the tracked path exactly as the real Go tests do, and CASE 1
# fails. The stub is never told the answer.
#
# CASE 2 is the positive case: it fails a blanket "write nothing ever"
# change, which would satisfy CASE 1 vacuously while destroying the evidence
# the §11.4.85 stress/chaos tests exist to produce.
#
# Exit 0 if every case passes; non-zero otherwise.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="${REPO_ROOT}/scripts/pipeline/phase-02-test-go.sh"
PRECONDITION="${REPO_ROOT}/scripts/pipeline/phase-00-precondition.sh"

if [[ ! -f "$WRAPPER" ]]; then
  echo "FAIL: script under test not found: $WRAPPER"
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

# --- the stub `go` -----------------------------------------------------------
# Replays a real captured `go test -json` stream on stdout AND reproduces the
# real suite's evidence side-effect through the same resolver algorithm the
# two real Go test files implement.
BIN="${WORKDIR}/bin"; mkdir -p "$BIN"
cat > "${BIN}/go" <<'STUB'
#!/usr/bin/env bash
# Stub `go`. Faithful transcription of the resolver in
# lava-api-go/internal/jackett/stress_chaos_test.go:53 (evidenceDir) and
# lava-api-go/internal/handlers/v1/jackett_stress_chaos_test.go:42
# (handlerEvidenceDir):
#
#   dir := os.Getwd()
#   for {
#       candidate := filepath.Join(dir, ".lava-ci-evidence")
#       if isDir(candidate) { return mkdirAll(candidate/stress-chaos/jackett) }
#       parent := filepath.Dir(dir); if parent == dir { fallback }; dir = parent
#   }
#
# The ONLY addition is the env seam the wrapper is expected to provide. When
# it is unset, behaviour is byte-for-byte the historical behaviour.
resolve_evidence_dir() {
  if [[ -n "${LAVA_STRESS_CHAOS_EVIDENCE_DIR:-}" ]]; then
    mkdir -p -- "$LAVA_STRESS_CHAOS_EVIDENCE_DIR"
    printf '%s' "$LAVA_STRESS_CHAOS_EVIDENCE_DIR"
    return
  fi
  local dir; dir="$(pwd)"
  while :; do
    if [[ -d "${dir}/.lava-ci-evidence" ]]; then
      local out="${dir}/.lava-ci-evidence/stress-chaos/jackett"
      mkdir -p -- "$out"
      printf '%s' "$out"
      return
    fi
    local parent; parent="$(dirname "$dir")"
    if [[ "$parent" == "$dir" ]]; then
      mkdir -p -- "stress-chaos-evidence/jackett"
      printf '%s' "stress-chaos-evidence/jackett"
      return
    fi
    dir="$parent"
  done
}

evidence_dir="$(resolve_evidence_dir)"
# The real payload carries captured_at + measured latency percentiles, so it
# differs every run. Reproduce that: nanosecond-resolution, always different.
printf '{\n  "captured_at": "%s",\n  "latency_ns": { "p50": %s }\n}\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(date +%N)" \
  > "${evidence_dir}/stress-latency-jackett.json"

cat "$GO_FIXTURE_STREAM"
exit "${GO_FIXTURE_RC:-0}"
STUB
chmod +x "${BIN}/go"

# A real captured all-green `go test -race -json` stream.
cat > "${WORKDIR}/stream-allpass.jsonl" <<'EOF'
{"Time":"2026-08-21T22:10:16.319698688+02:00","Action":"start","Package":"example.invalid/fixture/svc"}
{"Time":"2026-08-21T22:10:16.35138947+02:00","Action":"run","Package":"example.invalid/fixture/svc","Test":"TestPing"}
{"Time":"2026-08-21T22:10:16.351545719+02:00","Action":"output","Package":"example.invalid/fixture/svc","Test":"TestPing","Output":"=== RUN   TestPing\n"}
{"Time":"2026-08-21T22:10:16.352528853+02:00","Action":"output","Package":"example.invalid/fixture/svc","Test":"TestPing","Output":"--- PASS: TestPing (0.00s)\n"}
{"Time":"2026-08-21T22:10:16.352861585+02:00","Action":"pass","Package":"example.invalid/fixture/svc","Test":"TestPing","Elapsed":0}
{"Time":"2026-08-21T22:10:16.353088574+02:00","Action":"output","Package":"example.invalid/fixture/svc","Output":"PASS\n"}
{"Time":"2026-08-21T22:10:16.360629805+02:00","Action":"output","Package":"example.invalid/fixture/svc","Output":"ok  \texample.invalid/fixture/svc\t0.040s\n"}
{"Time":"2026-08-21T22:10:16.360811279+02:00","Action":"pass","Package":"example.invalid/fixture/svc","Elapsed":0.041}
EOF

# _new_repo <name> -> prints repo path
# A real git repo mirroring the real one's relevant shape: the jackett
# evidence files are TRACKED and NOT gitignored, exactly as `git ls-files`
# and `git check-ignore` report them in this project.
_new_repo() {
  local name="$1"
  local dir="${WORKDIR}/${name}"
  git init -q -b master "$dir"
  git -C "$dir" config user.email "fixture@example.invalid"
  git -C "$dir" config user.name "Fixture"
  printf '.lava-ci-evidence/pipeline-runs/\n' > "${dir}/.gitignore"
  mkdir -p "${dir}/lava-api-go" "${dir}/.lava-ci-evidence/stress-chaos/jackett"
  printf 'test:\n\tGOMAXPROCS=2 go test -race -count=1 ./...\n' > "${dir}/lava-api-go/Makefile"
  printf '{\n  "captured_at": "2026-08-21T16:22:30Z",\n  "latency_ns": { "p50": 3326927 }\n}\n' \
    > "${dir}/.lava-ci-evidence/stress-chaos/jackett/stress-latency-jackett.json"
  git -C "$dir" add -A
  git -C "$dir" commit -qm init
  printf '%s' "$dir"
}

# _run_go <repo> <run_id> -> sets RC, OUT, PHASE
_run_go() {
  local repo="$1" run_id="$2"
  PHASE="${repo}/.lava-ci-evidence/pipeline-runs/${run_id}/phase-02"
  local out="${WORKDIR}/$(basename "$repo")-${run_id}.log"
  set +e
  PATH="${BIN}:${PATH}" GO_FIXTURE_STREAM="${WORKDIR}/stream-allpass.jsonl" GO_FIXTURE_RC=0 \
    bash "$WRAPPER" "$repo" "$PHASE" >"$out" 2>&1
  RC=$?
  set -e
  OUT="$(cat "$out")"
}

echo "==============================================================="
echo "CASE 1 (LOAD-BEARING): a go-category run must not modify any"
echo "TRACKED file — otherwise FR-000 refuses the NEXT run (exit 2)"
echo "==============================================================="

REPO_A="$(_new_repo isolation)"
BEFORE_A="$(git -C "$REPO_A" status --porcelain)"
if [[ -n "$BEFORE_A" ]]; then
  fail "fixture precondition: tree was already dirty before the run: ${BEFORE_A}"
fi
_run_go "$REPO_A" "2026-08-23T10-17-26Z"

if [[ "$RC" -eq 0 ]]; then
  pass "wrapper exited 0 on an all-green stream"
else
  fail "wrapper exited ${RC}, expected 0; output: ${OUT}"
fi

# Only TRACKED-file modifications matter here: an untracked path inside the
# run's own gitignored evidence dir is invisible to `git status` anyway.
TRACKED_MODS_A="$(git -C "$REPO_A" status --porcelain | grep -Ev '^\?\?' || true)"
if [[ -z "$TRACKED_MODS_A" ]]; then
  pass "after a go-category run, git status shows no tracked-file modification"
else
  fail "go-category run modified TRACKED file(s) — the next run's FR-000 precondition will refuse with exit 2:
${TRACKED_MODS_A}"
fi

echo ""
echo "==============================================================="
echo "CASE 2 (POSITIVE / anti-vacuous): the stress-chaos evidence is"
echo "still produced — inside the run's own gitignored evidence dir"
echo "==============================================================="
echo "A blanket 'write nothing ever' change would satisfy CASE 1 while"
echo "destroying the evidence the §11.4.85 tests exist to produce."
echo ""

RUN_DIR_A="${REPO_A}/.lava-ci-evidence/pipeline-runs/2026-08-23T10-17-26Z"
FOUND_A="$(find "$RUN_DIR_A" -name 'stress-latency-jackett.json' 2>/dev/null | head -1)"
if [[ -n "$FOUND_A" ]]; then
  pass "stress-chaos evidence written inside the run dir: ${FOUND_A#"$REPO_A"/}"
else
  fail "no stress-chaos evidence found anywhere under the run dir ${RUN_DIR_A#"$REPO_A"/} — the run produced nothing"
fi

RECORDS_A="$(find "$PHASE" -name '*.json' -not -path '*/raw/*' 2>/dev/null | wc -l | tr -d ' ')"
if [[ "$RECORDS_A" -ge 1 ]]; then
  pass "wrapper still wrote ${RECORDS_A} Evidence Record(s)"
else
  fail "wrapper wrote 0 Evidence Records — the run proved nothing"
fi

echo ""
echo "==============================================================="
echo "CASE 3 (END-TO-END): FR-000 still passes after a run, so the"
echo "pipeline can actually run a SECOND time (FR-018 / SC-007)"
echo "==============================================================="

set +e
PRE_OUT="$(bash "$PRECONDITION" "$REPO_A" 2>&1)"
PRE_RC=$?
set -e
if [[ "$PRE_RC" -eq 0 ]]; then
  pass "FR-000 precondition satisfied after a completed run -> a second run can start"
else
  fail "FR-000 refused the second run (exit ${PRE_RC}): ${PRE_OUT}"
fi

echo ""
echo "==============================================================="
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
else
  echo "$FAILURES CHECK(S) FAILED"
  exit 1
fi
