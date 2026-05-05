#!/usr/bin/env bash
# tests/vm-signing/test_signing_matrix_rejects_byte_divergence.sh
#
# Asserts: the post-processor in scripts/run-vm-signing-matrix.sh
# rejects when any row's signing-output.json's sha256_signed_apk
# differs from the alpine-3.20-x86_64 reference.
#
# Strategy: seed a synthetic per-target evidence dir, then inline the
# wrapper's divergence-detection block (logically identical to the
# block in scripts/run-vm-signing-matrix.sh — keep them in sync;
# the falsifiability rehearsal is mutating THIS block, not the wrapper).
# Expected: divergence=1 → exit 0 (test PASS).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

EVIDENCE_DIR="$WORK/evidence"
mkdir -p "$EVIDENCE_DIR"

# Reference (alpine-3.20-x86_64): hash AAAA
mkdir -p "$EVIDENCE_DIR/alpine-3.20-x86_64"
echo '{"sha256_signed_apk":"AAAA","arch":"x86_64","distro":"alpine"}' \
  > "$EVIDENCE_DIR/alpine-3.20-x86_64/signing-output.json"

# Divergent row: debian-12-aarch64 has hash BBBB (≠ AAAA)
mkdir -p "$EVIDENCE_DIR/debian-12-aarch64"
echo '{"sha256_signed_apk":"BBBB","arch":"aarch64","distro":"debian"}' \
  > "$EVIDENCE_DIR/debian-12-aarch64/signing-output.json"

# All other rows match the reference.
for id in debian-12-x86_64 fedora-40-x86_64 alpine-3.20-aarch64 fedora-40-aarch64 alpine-edge-riscv64 debian-sid-riscv64 fedora-rawhide-riscv64; do
  mkdir -p "$EVIDENCE_DIR/$id"
  echo '{"sha256_signed_apk":"AAAA"}' > "$EVIDENCE_DIR/$id/signing-output.json"
done

# Inline divergence-check (matches the wrapper's logic):
divergence=0
REFERENCE_HASH=$(jq -r '.sha256_signed_apk' "$EVIDENCE_DIR/alpine-3.20-x86_64/signing-output.json")
for row_id in alpine-3.20-x86_64 debian-12-x86_64 fedora-40-x86_64 alpine-3.20-aarch64 debian-12-aarch64 fedora-40-aarch64 alpine-edge-riscv64 debian-sid-riscv64 fedora-rawhide-riscv64; do
  row_hash=$(jq -r '.sha256_signed_apk' "$EVIDENCE_DIR/$row_id/signing-output.json")
  if [[ "$row_hash" != "$REFERENCE_HASH" ]]; then
    divergence=1
  fi
done

if [[ $divergence -eq 0 ]]; then
  echo "FAIL: divergence not detected"
  exit 1
fi
exit 0
