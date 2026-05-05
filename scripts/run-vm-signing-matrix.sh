#!/usr/bin/env bash
# scripts/run-vm-signing-matrix.sh — cross-arch signing matrix wrapper.
# Drives cmd/vm-matrix against (alpine,debian,fedora) × (x86_64,aarch64,riscv64)
# = 9 configs. Each VM signs the same input APK with the same keystore.
# Post-processing computes per-row signing_match by comparing the
# SHA-256 of /tmp/signed.apk to the alpine-3.20-x86_64 KVM reference.
#
# Bluff vector this catches: JCA-provider divergence — the same JRE
# producing different signing bytes across architectures. If signing
# bytes diverge, an APK signed on architecture A might validate but
# not match what architecture B produces, breaking reproducible-build
# guarantees and silently exposing keystore-binding inconsistencies.
#
# The matrix RUN is the constitutional gate; this wrapper exists so
# Lava-side CI invocation is uniform (a single bash entry-point) and
# the post-processing block (the divergence detector) is unit-testable
# via tests/vm-signing/test_*.sh fixtures.
#
# Pre-requisites the operator MUST satisfy before running:
#   - tests/vm-signing/sample.apk exists (operator supplies; intentionally
#     NOT shipped — anti-bluff: don't pretend a stub APK is signable)
#   - proxy/build/libs/app.jar exists (./gradlew :proxy:buildFatJar)
#   - keystores/upload.keystore.p12 exists (Lava signing keystore)
#   - tools/lava-containers/vm-images.json has REAL SHA-256 + size for
#     each of the 9 qcow2 entries (placeholder zeros reject in pkg/cache)
#
# Exit codes:
#   0 — all 9 rows produced byte-equivalent signed APKs
#   1 — at least one row diverged (JCA bluff caught) OR a row's
#       signing-output.json is missing
#   2 — configuration error (missing inputs, missing tooling)
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

# --tag <T> places the run's evidence directory under
# .lava-ci-evidence/<T>/vm-signing/<UTC>/ so scripts/tag.sh's
# require_matrix_attestation_clause_6_I (which find-walks all
# subdirectories of the per-tag pack-dir) discovers it. Without --tag
# the run lives outside any per-tag pack and is purely diagnostic /
# developer-iteration.
TAG_OVERRIDE=""
EVIDENCE_DIR_OVERRIDE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) TAG_OVERRIDE="${2:-}"; shift 2 ;;
    --evidence-dir) EVIDENCE_DIR_OVERRIDE="${2:-}"; shift 2 ;;
    -h|--help)
      cat <<USAGE
Usage: $0 [--tag <tag>] [--evidence-dir <path>]

Drive the VM signing matrix (3 distros × 3 architectures = 9 configs)
and post-process the per-row signing-output.json files to detect
JCA-provider divergence.

OPTIONS
  --tag <tag>           Place the run's evidence directory under
                        .lava-ci-evidence/<tag>/vm-signing/<UTC>/ so
                        scripts/tag.sh discovers it (Sixth Law
                        clause 6.I gate).
  --evidence-dir <path> Explicit evidence-dir override; wins over
                        --tag if both are provided.
  -h, --help            Show this help and exit.

Evidence-path resolution priority:
  1. --evidence-dir <path>     (wins)
  2. --tag <tag>               (.lava-ci-evidence/<tag>/vm-signing/<UTC>/)
  3. default                   (.lava-ci-evidence/vm-signing/<UTC>/)
USAGE
      exit 0
      ;;
    *) echo "ERROR: unknown option: $1 (try --help)" >&2; exit 2 ;;
  esac
done

if [[ -n "$EVIDENCE_DIR_OVERRIDE" ]]; then
  EVIDENCE_DIR="$EVIDENCE_DIR_OVERRIDE"
elif [[ -n "$TAG_OVERRIDE" ]]; then
  EVIDENCE_DIR=".lava-ci-evidence/${TAG_OVERRIDE}/vm-signing/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
else
  EVIDENCE_DIR=".lava-ci-evidence/vm-signing/$(date -u +%Y-%m-%dT%H-%M-%SZ)"
fi
mkdir -p "$EVIDENCE_DIR"

# Build the cmd/vm-matrix binary from the pinned Containers submodule.
BIN_DIR="$PROJECT_DIR/build/vm-matrix"
mkdir -p "$BIN_DIR"
( cd "$PROJECT_DIR/Submodules/Containers" && go build -o "$BIN_DIR/vm-matrix" ./cmd/vm-matrix/ )

# Inputs uploaded to each VM:
#   - tests/vm-signing/sample.apk      — input APK (operator-supplied)
#   - proxy/build/libs/app.jar         — Lava-built JAR for cross-check
#   - keystores/upload.keystore.p12    — Lava's signing keystore
#   - tests/vm-signing/sign-and-hash.sh — script run inside VM
APK="tests/vm-signing/sample.apk"
JAR="proxy/build/libs/app.jar"
KEYSTORE="keystores/upload.keystore.p12"
SCRIPT="tests/vm-signing/sign-and-hash.sh"

if [[ ! -f "$APK" || ! -f "$JAR" || ! -f "$KEYSTORE" ]]; then
  echo "ERROR: signing matrix requires $APK, $JAR, and $KEYSTORE to exist." >&2
  echo "  - Run ./gradlew :proxy:buildFatJar (produces $JAR)" >&2
  echo "  - Place a real APK at $APK (anti-bluff: not shipped as a stub)" >&2
  echo "  - Ensure $KEYSTORE is in place (Lava signing keystore)" >&2
  exit 2
fi

# Run the matrix.
"$BIN_DIR/vm-matrix" \
  --image-manifest tools/lava-containers/vm-images.json \
  --targets alpine-3.20-x86_64,debian-12-x86_64,fedora-40-x86_64,alpine-3.20-aarch64,debian-12-aarch64,fedora-40-aarch64,alpine-edge-riscv64,debian-sid-riscv64,fedora-rawhide-riscv64 \
  --uploads "$APK:/tmp/sample.apk,$JAR:/tmp/app.jar,$KEYSTORE:/tmp/keystore.p12,$SCRIPT:/tmp/sign-and-hash.sh" \
  --script /tmp/sign-and-hash.sh \
  --captures "/tmp/signed.apk:signed.apk,/tmp/signing-output.json:signing-output.json" \
  --evidence-dir "$EVIDENCE_DIR" \
  --concurrent 1 --cold-boot

# Post-processing: parse each per-target signing-output.json and compute
# signing_match relative to alpine-3.20-x86_64 (the KVM reference row,
# i.e. the architecture that runs natively on the host). The reference
# choice is deterministic and recorded so divergence bug-reports cite
# the same baseline.
ATTEST="$EVIDENCE_DIR/real-device-verification.json"
REFERENCE_HASH=$(jq -r '.sha256_signed_apk' "$EVIDENCE_DIR/alpine-3.20-x86_64/signing-output.json")

if [[ -z "$REFERENCE_HASH" || "$REFERENCE_HASH" == "null" ]]; then
  echo "ERROR: no reference hash from alpine-3.20-x86_64 signing-output.json" >&2
  exit 1
fi

# Bash-side hash comparison (the load-bearing divergence detector).
# Tested by tests/vm-signing/test_signing_matrix_{rejects_byte_divergence,
# accepts_concordant_signatures}.sh which inline this same block.
divergence=0
for row_id in alpine-3.20-x86_64 debian-12-x86_64 fedora-40-x86_64 alpine-3.20-aarch64 debian-12-aarch64 fedora-40-aarch64 alpine-edge-riscv64 debian-sid-riscv64 fedora-rawhide-riscv64; do
  row_hash_file="$EVIDENCE_DIR/$row_id/signing-output.json"
  if [[ ! -f "$row_hash_file" ]]; then
    echo "ERROR: missing $row_hash_file" >&2
    divergence=1
    continue
  fi
  row_hash=$(jq -r '.sha256_signed_apk' "$row_hash_file")
  if [[ "$row_hash" != "$REFERENCE_HASH" ]]; then
    echo "DIVERGENCE: $row_id hash $row_hash != reference $REFERENCE_HASH"
    divergence=1
  fi
done

# Augment attestation with the reference hash + per-row divergence bits
# so downstream tooling has structured access to the comparison.
if [[ -f "$ATTEST" ]]; then
  jq --arg ref "$REFERENCE_HASH" '. + {signing_reference_hash:$ref, signing_reference_target:"alpine-3.20-x86_64"}' \
    "$ATTEST" > "$ATTEST.tmp" && mv "$ATTEST.tmp" "$ATTEST"
fi

if [[ $divergence -ne 0 ]]; then
  echo "SIGNING MATRIX FAILED — JCA divergence detected; refusing." >&2
  exit 1
fi
echo "SIGNING MATRIX PASSED — all 9 configs produced byte-equivalent signed APKs."
exit 0
