#!/usr/bin/env bash
# Asserts: tag.sh's clause 6.I gate (require_matrix_attestation_clause_6_I)
# AND the Group B gates (require_matrix_attestation_group_b_gates) WALK
# the entire pack-dir tree and DISCOVER a VM-matrix attestation placed
# under <pack>/vm-signing/<UTC>/real-device-verification.json — i.e. the
# gate sees the VM file as well as the Android-matrix file, applies its
# checks to BOTH, and accepts when both are clean.
#
# The Phase 1 follow-up of the pkg/vm + image-cache cycle introduced
# scripts/run-vm-signing-matrix.sh --tag <T>. When --tag is supplied
# the wrapper writes its attestation under
# .lava-ci-evidence/<T>/vm-signing/<UTC>/. The tag-time gate MUST see
# that file. This test asserts the find pattern is broad enough; a
# regression that scoped the find to a specific subdir would fail this
# test.
#
# Falsifiability rehearsal (Bluff-Audit, Seventh Law clause 1):
#   Mutation: in scripts/tag.sh, change the find pattern from
#             '-name real-device-verification.json' to
#             '-name android-matrix.json' (a non-existent name).
#   Observed: With the mutation, find returns zero files and the gate
#             dies "no matrix attestation (real-device-verification.json)
#             under <pack>" — i.e. the VM file is no longer discovered.
#   Reverted: yes (the file is unchanged in the committed tree).
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Seed a minimal Lava-shaped tree under WORK so tag.sh's REPO_ROOT
# detection points at WORK, not the real repo. (Same pattern as the
# existing tag-helper fixtures.)
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
mkdir -p "$PACK/challenges" "$PACK/bluff-audit" "$PACK/mirror-smoke" "$PACK/matrix/run1" "$PACK/vm-signing/run1"
echo '{}' > "$PACK/ci.sh.json"
for i in 1 2 3 4 5 6 7 8; do echo '{"status":"VERIFIED"}' > "$PACK/challenges/C${i}.json"; done
echo '{}' > "$PACK/bluff-audit/x.json"
echo '{}' > "$PACK/mirror-smoke/x.json"
cat > "$PACK/real-device-verification.md" <<'EOF'
status: VERIFIED
EOF

# Android matrix attestation — covers API 28/30/34/36 with phone form
# factor. This is what unblocks clause 6.I clause 2 / clause 3 coverage.
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

# VM signing matrix attestation — what scripts/run-vm-signing-matrix.sh
# --tag Lava-Android-1.2.1-127 would produce. Rows cover (alpine,
# debian, fedora) × (x86_64, aarch64, riscv64) = 9 configs. No
# api_level (these aren't Android emulators); diag.target carries the
# config tag instead. Group B Gate 3 carve-out: rows without api_level
# are skipped by the gate's jq selector, so these rows do NOT trip the
# diag.sdk-vs-api_level mismatch check.
cat > "$PACK/vm-signing/run1/real-device-verification.json" <<'EOF'
{
  "started_at": "2026-05-06T01:00:00Z",
  "finished_at": "2026-05-06T01:30:00Z",
  "all_passed": true,
  "gating": true,
  "signing_reference_hash": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "signing_reference_target": "alpine-3.20-x86_64",
  "rows": [
    {"target":"alpine-3.20-x86_64","passed":true,"diag":{"target":"alpine-3.20-x86_64"},"failure_summaries":[],"concurrent":1},
    {"target":"debian-12-x86_64","passed":true,"diag":{"target":"debian-12-x86_64"},"failure_summaries":[],"concurrent":1},
    {"target":"fedora-40-x86_64","passed":true,"diag":{"target":"fedora-40-x86_64"},"failure_summaries":[],"concurrent":1},
    {"target":"alpine-3.20-aarch64","passed":true,"diag":{"target":"alpine-3.20-aarch64"},"failure_summaries":[],"concurrent":1},
    {"target":"debian-12-aarch64","passed":true,"diag":{"target":"debian-12-aarch64"},"failure_summaries":[],"concurrent":1},
    {"target":"fedora-40-aarch64","passed":true,"diag":{"target":"fedora-40-aarch64"},"failure_summaries":[],"concurrent":1},
    {"target":"alpine-edge-riscv64","passed":true,"diag":{"target":"alpine-edge-riscv64"},"failure_summaries":[],"concurrent":1},
    {"target":"debian-sid-riscv64","passed":true,"diag":{"target":"debian-sid-riscv64"},"failure_summaries":[],"concurrent":1},
    {"target":"fedora-rawhide-riscv64","passed":true,"diag":{"target":"fedora-rawhide-riscv64"},"failure_summaries":[],"concurrent":1}
  ]
}
EOF

( cd "$WORK" && git add -A && git commit -qm "seed evidence pack with android+vm-signing attestations" )

# Run tag.sh — we only care about reaching, and passing, the
# clause-6.I + Group B gates. tag.sh may exit non-zero later for some
# unrelated reason (no real upstream remote etc.), but neither of the
# matrix gates may misfire on the clean VM attestation.
out=$(cd "$WORK" && bash scripts/tag.sh --app android --no-bump --no-push 2>&1) && rc=0 || rc=$?

# Failure mode 1: gate refused with "no matrix attestation under …".
# That would mean the find didn't walk into vm-signing/ — exactly the
# bluff this test prevents.
if grep -qE "no matrix attestation \(real-device-verification\.json\)" <<<"$out"; then
  echo "FAIL: tag.sh's matrix gate did not discover any attestation despite vm-signing/ being present"
  echo "$out"
  exit 1
fi

# Failure mode 2: a Group B gate misfired on the VM rows (e.g.
# spurious Gate 1 for concurrent != 1, Gate 2 for gating != true,
# Gate 3 for diag.sdk != api_level). Both attestations are clean by
# construction; any Group B gate firing here is a regression.
if grep -qE "Group B Gate [123]" <<<"$out"; then
  echo "FAIL: a Group B gate misfired on a clean android+vm-signing attestation pair"
  echo "$out"
  exit 1
fi

# Positive assertion: the gate's success-line lists the matrix file
# count. With Android run1 + vm-signing run1 we expect 2 files.
if ! grep -qE "clause 6\.I matrix gate OK: 2 matrix file\(s\)" <<<"$out"; then
  echo "FAIL: matrix gate did not report exactly 2 matrix file(s) — find may not have walked vm-signing/"
  echo "$out"
  exit 1
fi

# Positive assertion: Group B gates ran across both files.
if ! grep -qE "Group B clause 6\.I gates OK across 2 attestation file" <<<"$out"; then
  echo "FAIL: Group B gates did not see 2 attestation files — find may not have walked vm-signing/"
  echo "$out"
  exit 1
fi

exit 0
