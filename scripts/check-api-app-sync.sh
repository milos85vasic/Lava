#!/usr/bin/env bash
#
# check-api-app-sync.sh — the CI gate guaranteeing the on-device Lava API embed
# (liblavaapi.so, packaged by :core:apiengine) is built from EXACTLY the current
# lava-api-go source codebase, with NO drift.
#
# Mechanism (single source of truth):
#   1. Recompute the live hash of the embed's source tree via
#      scripts/compute-api-source-hash.sh.
#   2. Read the committed manifest core/apiengine/src/main/resources/api-source.hash
#      — the hash the last freshly-built .so was built from (written by
#      build-cshared.sh on a successful build).
#   3. If they differ, the .so on disk / packaged in the APK is STALE relative to
#      the current lava-api-go source: exit 1, loudly, with the remediation.
#
# Why this is the load-bearing gate (§6.J / §11.4.69): a passing gate means the
# committed manifest matches the live source, which (because build-cshared.sh
# writes the manifest ONLY when it injects that same hash into the .so) means the
# embed the build produced contains the current API codebase. The on-device
# Challenge (Challenge*ApiEmbedSourceHashMatchesTest) closes the loop at runtime
# by asserting the RUNNING .so's reported hash equals the APK's build-time hash.
#
# §6.R: no hardcoded values. §6.T.2 / §11.4.67: bounded, read-only, no host
# mutation. bash -n clean.
#
# See docs/scripts/check-api-app-sync.sh.md for the full guide.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

HASH_SCRIPT="${REPO_ROOT}/scripts/compute-api-source-hash.sh"
MANIFEST="${REPO_ROOT}/core/apiengine/src/main/resources/api-source.hash"

if [[ ! -x "${HASH_SCRIPT}" ]]; then
    echo "FATAL: hash script not found/executable at ${HASH_SCRIPT}" >&2
    exit 2
fi

if [[ ! -f "${MANIFEST}" ]]; then
    echo "FAIL: API↔embed sync manifest MISSING at ${MANIFEST}" >&2
    echo "      Build the embed once to seed it:" >&2
    echo "        lava-api-go/scripts/build-cshared.sh" >&2
    echo "      (or seed it with: scripts/compute-api-source-hash.sh > ${MANIFEST})" >&2
    exit 1
fi

LIVE_HASH="$(bash "${HASH_SCRIPT}")"
COMMITTED_HASH="$(tr -d '[:space:]' < "${MANIFEST}")"

if [[ "${LIVE_HASH}" != "${COMMITTED_HASH}" ]]; then
    echo "FAIL: on-device API embed is STALE vs lava-api-go — rebuild liblavaapi.so via build-cshared.sh" >&2
    echo "" >&2
    echo "  committed (manifest): ${COMMITTED_HASH}" >&2
    echo "  live (lava-api-go):   ${LIVE_HASH}" >&2
    echo "" >&2
    echo "  The lava-api-go source compiled into the embed has changed since the" >&2
    echo "  .so + manifest were last built. The packaged on-device API would NOT" >&2
    echo "  match the current API codebase (drift). Remediation:" >&2
    echo "    lava-api-go/scripts/build-cshared.sh   # rebuilds .so + refreshes manifest" >&2
    echo "  then commit the updated core/apiengine/src/main/resources/api-source.hash" >&2
    exit 1
fi

echo "OK: on-device API embed source hash matches lava-api-go (no drift)"
echo "    hash: ${LIVE_HASH}"
