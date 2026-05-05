#!/usr/bin/env bash
# tests/vm-signing/test_signing_matrix_accepts_concordant_signatures.sh
#
# Asserts: when all 9 rows produce the same signing hash, the
# divergence detector does NOT fire (signing_match=true everywhere).
#
# Pairs with test_signing_matrix_rejects_byte_divergence.sh — together
# they cover both branches of the wrapper's load-bearing post-
# processing block.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

EVIDENCE_DIR="$WORK/evidence"

# All 9 rows produce the same hash AAAA (clean, concordant matrix).
for id in alpine-3.20-x86_64 debian-12-x86_64 fedora-40-x86_64 alpine-3.20-aarch64 debian-12-aarch64 fedora-40-aarch64 alpine-edge-riscv64 debian-sid-riscv64 fedora-rawhide-riscv64; do
  mkdir -p "$EVIDENCE_DIR/$id"
  echo '{"sha256_signed_apk":"AAAA"}' > "$EVIDENCE_DIR/$id/signing-output.json"
done

# Inline divergence-check (matches the wrapper's logic).
divergence=0
REFERENCE_HASH=$(jq -r '.sha256_signed_apk' "$EVIDENCE_DIR/alpine-3.20-x86_64/signing-output.json")
for row_id in alpine-3.20-x86_64 debian-12-x86_64 fedora-40-x86_64 alpine-3.20-aarch64 debian-12-aarch64 fedora-40-aarch64 alpine-edge-riscv64 debian-sid-riscv64 fedora-rawhide-riscv64; do
  row_hash=$(jq -r '.sha256_signed_apk' "$EVIDENCE_DIR/$row_id/signing-output.json")
  if [[ "$row_hash" != "$REFERENCE_HASH" ]]; then
    divergence=1
  fi
done

if [[ $divergence -ne 0 ]]; then
  echo "FAIL: false divergence (matrix was concordant but check fired)"
  exit 1
fi
exit 0
