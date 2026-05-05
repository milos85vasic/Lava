#!/usr/bin/env bash
# tests/vm-signing/sign-and-hash.sh — runs INSIDE each VM in the matrix.
# Signs /tmp/sample.apk with /tmp/keystore.p12 (uploaded by the wrapper),
# then writes /tmp/signing-output.json with the SHA-256 of the signed
# bytes plus the running architecture and distro.
#
# Idempotent JRE installation handles distro family detection (apk/
# apt-get/dnf). The hash is the comparison surface — if architectures
# produce divergent bytes, the wrapper's post-processing detects it.
set -Eeuo pipefail

if ! command -v jarsigner >/dev/null 2>&1; then
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache openjdk17-jre-headless jq
  elif command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq && apt-get install -y --no-install-recommends openjdk-17-jre-headless jq
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y java-17-openjdk-headless jq
  else
    echo "ERROR: no supported package manager found (apk/apt-get/dnf)" >&2
    exit 1
  fi
fi

cd /tmp
jarsigner -keystore /tmp/keystore.p12 \
  -storepass "${KEYSTORE_PASSWORD:-changeit}" \
  -signedjar /tmp/signed.apk \
  /tmp/sample.apk \
  upload >/tmp/jarsigner.log 2>&1

HASH=$(sha256sum /tmp/signed.apk | awk '{print $1}')
ARCH=$(uname -m)
# /etc/os-release's ID field — fallback to "unknown" if absent.
DISTRO=$( . /etc/os-release 2>/dev/null && echo "${ID:-unknown}" )
[[ -z "$DISTRO" ]] && DISTRO=unknown

jq -n --arg hash "$HASH" --arg arch "$ARCH" --arg distro "$DISTRO" '{
  sha256_signed_apk: $hash,
  arch: $arch,
  distro: $distro
}' > /tmp/signing-output.json

echo "Signed; SHA-256: $HASH (arch=$ARCH distro=$DISTRO)"
