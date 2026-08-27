#!/usr/bin/env bash
# Asserts: scripts/tag.sh's SP-3a Android evidence-pack gate refuses a pack
# whose required artifacts EXIST but carry no content.
#
# FORENSIC ANCHOR (2026-08-26). The gate's required-subfile block was
#
#     [[ -f "$pack_dir/ci.sh.json"   ]] || missing+=("ci.sh.json")
#     [[ -d "$pack_dir/bluff-audit"  ]] || missing+=("bluff-audit/")
#     [[ -d "$pack_dir/mirror-smoke" ]] || missing+=("mirror-smoke/")
#
# while the function's own header states the pack certifies that
# `scripts/ci.sh --full` ran green (ci.sh.json), that the bluff-audit hunt has
# run (bluff-audit/<recent>.json), and that the mirror smoke test passed
# (mirror-smoke/<recent>.json). `-f` is satisfied by a ZERO-BYTE file and `-d`
# by an EMPTY directory, so two `mkdir`s and a `touch` satisfied all three.
# Measured end-to-end through the real tag.sh before the fix:
#
#     ci.sh.json bytes = 0 / bluff-audit files = 0 / mirror-smoke files = 0
#     [tag] [android] SP-3a evidence pack OK: <pack>          EXIT=0
#
# byte-for-byte the same verdict as a pack carrying real evidence. Presence of
# a path is not evidence; only content is. (Note for future readers: `-e` and
# `-s` are BOTH true for a directory — verified — so neither is a content test
# either. `-f` plus `-s` plus a parse is.)
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

FAILURES=0
EXAMINED=0
pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; FAILURES=$((FAILURES + 1)); }

command -v jq >/dev/null 2>&1 || { echo "FAIL: jq is required by this suite"; exit 1; }

# _build <variant> — creates a fixture repo whose Android evidence pack is
# complete and valid in every respect EXCEPT the aspect named by <variant>.
# Echoes the workdir.
_build() {
  local variant="$1"
  local work; work="$(mktemp -d)"
  cp -r "$REPO_ROOT/scripts" "$work/scripts"
  mkdir -p "$work/buildSrc/src/main/kotlin/lava/conventions" "$work/app"
  printf 'package lava.conventions\nfun x() { val compileSdk = 36 }\n' \
    > "$work/buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt"
  printf 'android {\n  defaultConfig {\n    versionName = "1.2.1"\n    versionCode = 127\n  }\n}\n' \
    > "$work/app/build.gradle.kts"
  printf '# Changelog\n\n## 1.2.1 (127)\n- entry\n' > "$work/CHANGELOG.md"
  mkdir -p "$work/.lava-ci-evidence/distribute-changelog/firebase-app-distribution"
  printf 'notes\n' > "$work/.lava-ci-evidence/distribute-changelog/firebase-app-distribution/1.2.1-127.md"
  ( cd "$work" && git init -q && git config user.email t@t && git config user.name t \
      && git add -A && git commit -qm seed ) >/dev/null 2>&1
  local ci_sha; ci_sha="$(git -C "$work" rev-parse HEAD)"

  local pack="$work/.lava-ci-evidence/Lava-Android-1.2.1-127"
  mkdir -p "$pack/challenges" "$pack/bluff-audit" "$pack/mirror-smoke" "$pack/matrix/run1"
  local i; for i in 1 2 3 4 5 6 7 8; do echo '{"status":"VERIFIED"}' > "$pack/challenges/C${i}.json"; done
  printf 'status: VERIFIED\n' > "$pack/real-device-verification.md"
  cat > "$pack/matrix/run1/real-device-verification.json" <<'J'
{"all_passed": true, "gating": true, "rows": [
 {"avd":"A28","api_level":28,"form_factor":"phone","test_passed":true,"diag":{"sdk":28},"concurrent":1},
 {"avd":"A30","api_level":30,"form_factor":"phone","test_passed":true,"diag":{"sdk":30},"concurrent":1},
 {"avd":"A34","api_level":34,"form_factor":"phone","test_passed":true,"diag":{"sdk":34},"concurrent":1},
 {"avd":"A36","api_level":36,"form_factor":"phone","test_passed":true,"diag":{"sdk":36},"concurrent":1}]}
J
  # Valid defaults, then break exactly one thing per variant.
  printf '{"mode":"--full","all_gates_passed":true,"sha":"%s"}\n' "$ci_sha" > "$pack/ci.sh.json"
  echo '{"date":"2026-08-26","targets":["a"],"bluffs_found":0}' > "$pack/bluff-audit/2026-08-26.json"
  echo '{"mirrors":["github","gitlab"],"converged":true}'      > "$pack/mirror-smoke/2026-08-26.json"

  case "$variant" in
    valid) : ;;
    hollow)
      : > "$pack/ci.sh.json"
      rm -f "$pack/bluff-audit/"*.json "$pack/mirror-smoke/"*.json
      ;;
    ci_json_is_a_directory)
      rm -f "$pack/ci.sh.json"; mkdir -p "$pack/ci.sh.json"
      ;;
    bluff_audit_has_only_non_json)
      rm -f "$pack/bluff-audit/"*.json
      printf 'we ran a hunt, honest\n' > "$pack/bluff-audit/notes.txt"
      ;;
    ci_gates_not_green)
      printf '{"mode":"--full","all_gates_passed":false,"sha":"%s"}\n' "$ci_sha" > "$pack/ci.sh.json"
      ;;
    ci_no_commit_recorded)
      printf '{"mode":"--full","all_gates_passed":true}\n' > "$pack/ci.sh.json"
      ;;
    *) echo "internal: unknown variant $variant" >&2; return 1 ;;
  esac

  ( cd "$work" && git add -A && git commit -qm "evidence pack" ) >/dev/null 2>&1
  printf '%s' "$work"
}

_run() { ( cd "$1" && bash scripts/tag.sh --app android --no-bump --no-push 2>&1 ); }

# _expect_refused <variant> <human description>
_expect_refused() {
  local variant="$1" desc="$2" w out
  EXAMINED=$((EXAMINED + 1))
  w="$(_build "$variant")"
  out="$(_run "$w")"
  if grep -q 'SP-3a evidence pack OK' <<< "$out"; then
    fail "${desc}: the evidence-pack gate PASSED. Presence of a path is not evidence — this pack certifies nothing the header claims it certifies. Output: ${out}"
  else
    pass "${desc}: refused"
  fi
  if grep -q 'created tag:' <<< "$out"; then
    fail "${desc}: a release tag was CREATED against a contentless evidence pack"
  fi
  rm -rf -- "$w"
}

echo "==============================================================="
echo "CASE 1 (LOAD-BEARING): two mkdirs and a zero-byte file"
echo "==============================================================="
_expect_refused hollow \
  "zero-byte ci.sh.json + empty bluff-audit/ + empty mirror-smoke/"

echo ""
echo "==============================================================="
echo "CASE 2: a DIRECTORY standing in for ci.sh.json"
echo "==============================================================="
_expect_refused ci_json_is_a_directory "ci.sh.json is a directory"

echo ""
echo "==============================================================="
echo "CASE 3: bluff-audit/ holds a file, but not the evidence contracted"
echo "==============================================================="
_expect_refused bluff_audit_has_only_non_json \
  "bluff-audit/ contains only a non-JSON note"

echo ""
echo "==============================================================="
echo "CASE 4: ci.sh.json parses, but records a RED run"
echo "==============================================================="
_expect_refused ci_gates_not_green "ci.sh.json says all_gates_passed=false"

echo ""
echo "==============================================================="
echo "CASE 5: ci.sh.json parses and is green, but names no commit"
echo "==============================================================="
_expect_refused ci_no_commit_recorded "ci.sh.json records no .sha"

echo ""
echo "==============================================================="
echo "CASE 6 (control): a pack with real content must still PASS"
echo "==============================================================="
EXAMINED=$((EXAMINED + 1))
W_OK="$(_build valid)"
OUT_OK="$(_run "$W_OK")"
if grep -q 'SP-3a evidence pack OK' <<< "$OUT_OK"; then
  pass "a pack whose artifacts carry real content is accepted (the fix did not just refuse everything)"
else
  fail "a fully-populated, honest evidence pack was REFUSED — the fix over-reached. Output: ${OUT_OK}"
fi
rm -rf -- "$W_OK"

echo ""
echo "==============================================================="
if [[ "$EXAMINED" -eq 0 ]]; then
  echo "FAIL: this suite examined 0 packs and therefore proves nothing"
  exit 1
fi
echo "examined ${EXAMINED} evidence pack(s)"
if [[ "$FAILURES" -eq 0 ]]; then
  echo "ALL CHECKS PASSED"
  exit 0
fi
echo "$FAILURES CHECK(S) FAILED"
exit 1
