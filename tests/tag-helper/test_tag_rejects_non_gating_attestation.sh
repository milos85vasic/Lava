#!/usr/bin/env bash
# Asserts: tag.sh refuses an evidence pack whose run-level gating is false.
# (Group B clause 6.I Gate 2)
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cp -r "$REPO_ROOT/scripts" "$WORK/scripts"
mkdir -p "$WORK/buildSrc/src/main/kotlin/lava/conventions"
cat > "$WORK/buildSrc/src/main/kotlin/lava/conventions/AndroidCommon.kt" <<'KOTLIN'
package lava.conventions
fun whatever() {
  val compileSdk = 36
}
KOTLIN
mkdir -p "$WORK/app"
cat > "$WORK/app/build.gradle.kts" <<'GRADLE'
android {
  defaultConfig {
    versionName = "1.2.1"
    versionCode = 127
  }
}
GRADLE
mkdir -p "$WORK/.lava-ci-evidence"
( cd "$WORK" && git init -q && git config user.email t@t && git config user.name t && git add -A && git commit -qm seed )

PACK="$WORK/.lava-ci-evidence/Lava-Android-1.2.1-127"
mkdir -p "$PACK/challenges" "$PACK/bluff-audit" "$PACK/mirror-smoke" "$PACK/matrix/run1"
echo '{}' > "$PACK/ci.sh.json"
for i in 1 2 3 4 5 6 7 8; do echo '{"status":"VERIFIED"}' > "$PACK/challenges/C${i}.json"; done
echo '{}' > "$PACK/bluff-audit/x.json"
echo '{}' > "$PACK/mirror-smoke/x.json"
cat > "$PACK/real-device-verification.md" <<'EOF'
status: VERIFIED
EOF

cat > "$PACK/matrix/run1/real-device-verification.json" <<'EOF'
{
  "started_at": "2026-05-06T00:00:00Z",
  "finished_at": "2026-05-06T00:01:00Z",
  "all_passed": true,
  "gating": false,
  "rows": [
    {"avd":"CZ_API28_Phone","api_level":28,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":28,"device":"Pixel"},"failure_summaries":[],"concurrent":1},
    {"avd":"CZ_API30_Phone","api_level":30,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":30,"device":"Pixel"},"failure_summaries":[],"concurrent":1},
    {"avd":"CZ_API34_Phone","api_level":34,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":34,"device":"Pixel"},"failure_summaries":[],"concurrent":1},
    {"avd":"CZ_API36_Phone","api_level":36,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":36,"device":"Pixel"},"failure_summaries":[],"concurrent":1}
  ]
}
EOF

# Commit the evidence pack so tag.sh's "dirty tree" check passes and we
# reach the Group B gate.
( cd "$WORK" && git add -A && git commit -qm "seed evidence pack" )

out=$(cd "$WORK" && bash scripts/tag.sh --app android --no-bump --no-push 2>&1) && rc=0 || rc=$?
if [[ $rc -eq 0 ]]; then
  echo "FAIL: tag.sh exited 0 despite gating: false"
  echo "$out"; exit 1
fi
if ! grep -q "Group B Gate 2" <<<"$out"; then
  echo "FAIL: tag.sh exited non-zero but for the wrong reason"
  echo "$out"; exit 1
fi
exit 0
