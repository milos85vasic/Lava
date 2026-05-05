#!/usr/bin/env bash
# Asserts: tag.sh's clause 6.I gate (require_matrix_attestation_clause_6_I)
# AND the Group B gates (require_matrix_attestation_group_b_gates) WALK
# the entire pack-dir tree and DISCOVER a VM-distro-matrix attestation
# placed under <pack>/vm-distro/<UTC>/real-device-verification.json.
#
# Companion to test_tag_includes_vm_signing_attestation.sh — same
# assertion, different VM-matrix subdir name. The Phase 1 follow-up of
# the pkg/vm + image-cache cycle introduced
# scripts/run-vm-distro-matrix.sh --tag <T>; with --tag the wrapper
# writes its attestation under .lava-ci-evidence/<T>/vm-distro/<UTC>/.
# The tag-time gate MUST see that file.
#
# Falsifiability rehearsal (Bluff-Audit, Seventh Law clause 1):
#   See test_tag_includes_vm_signing_attestation.sh — the same find-
#   pattern mutation makes both VM-matrix files invisible to the gate.
#   One rehearsal covers both companion tests by construction.
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
mkdir -p "$PACK/challenges" "$PACK/bluff-audit" "$PACK/mirror-smoke" "$PACK/matrix/run1" "$PACK/vm-distro/run1"
echo '{}' > "$PACK/ci.sh.json"
for i in 1 2 3 4 5 6 7 8; do echo '{"status":"VERIFIED"}' > "$PACK/challenges/C${i}.json"; done
echo '{}' > "$PACK/bluff-audit/x.json"
echo '{}' > "$PACK/mirror-smoke/x.json"
cat > "$PACK/real-device-verification.md" <<'EOF'
status: VERIFIED
EOF

# Android matrix attestation — covers API 28/30/34/36 with phone form
# factor.
cat > "$PACK/matrix/run1/real-device-verification.json" <<'EOF'
{
  "started_at": "2026-05-06T00:00:00Z",
  "finished_at": "2026-05-06T00:01:00Z",
  "all_passed": true,
  "gating": true,
  "rows": [
    {"avd":"CZ_API28_Phone","api_level":28,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":28},"failure_summaries":[],"concurrent":1},
    {"avd":"CZ_API30_Phone","api_level":30,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":30},"failure_summaries":[],"concurrent":1},
    {"avd":"CZ_API34_Phone","api_level":34,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":34},"failure_summaries":[],"concurrent":1},
    {"avd":"CZ_API36_Phone","api_level":36,"form_factor":"phone","test_class":"lava.app.X","test_passed":true,"diag":{"sdk":36},"failure_summaries":[],"concurrent":1}
  ]
}
EOF

# VM distro matrix attestation — what scripts/run-vm-distro-matrix.sh
# --tag Lava-Android-1.2.1-127 would produce. 3 distros × x86_64.
# probe-output booleans (proxy_health, proxy_search, goapi_health,
# goapi_metrics) live in per-row probe-output.json files; the
# attestation file itself only carries pass/fail and run-level metadata.
cat > "$PACK/vm-distro/run1/real-device-verification.json" <<'EOF'
{
  "started_at": "2026-05-06T02:00:00Z",
  "finished_at": "2026-05-06T02:10:00Z",
  "all_passed": true,
  "gating": true,
  "rows": [
    {"target":"alpine-3.20-x86_64","passed":true,"diag":{"target":"alpine-3.20-x86_64"},"failure_summaries":[],"concurrent":1},
    {"target":"debian-12-x86_64","passed":true,"diag":{"target":"debian-12-x86_64"},"failure_summaries":[],"concurrent":1},
    {"target":"fedora-40-x86_64","passed":true,"diag":{"target":"fedora-40-x86_64"},"failure_summaries":[],"concurrent":1}
  ]
}
EOF

( cd "$WORK" && git add -A && git commit -qm "seed evidence pack with android+vm-distro attestations" )

out=$(cd "$WORK" && bash scripts/tag.sh --app android --no-bump --no-push 2>&1) && rc=0 || rc=$?

if grep -qE "no matrix attestation \(real-device-verification\.json\)" <<<"$out"; then
  echo "FAIL: tag.sh's matrix gate did not discover any attestation despite vm-distro/ being present"
  echo "$out"
  exit 1
fi

if grep -qE "Group B Gate [123]" <<<"$out"; then
  echo "FAIL: a Group B gate misfired on a clean android+vm-distro attestation pair"
  echo "$out"
  exit 1
fi

if ! grep -qE "clause 6\.I matrix gate OK: 2 matrix file\(s\)" <<<"$out"; then
  echo "FAIL: matrix gate did not report exactly 2 matrix file(s) — find may not have walked vm-distro/"
  echo "$out"
  exit 1
fi

if ! grep -qE "Group B clause 6\.I gates OK across 2 attestation file" <<<"$out"; then
  echo "FAIL: Group B gates did not see 2 attestation files — find may not have walked vm-distro/"
  echo "$out"
  exit 1
fi

exit 0
