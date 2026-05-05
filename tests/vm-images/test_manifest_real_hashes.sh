#!/usr/bin/env bash
# tests/vm-images/test_manifest_real_hashes.sh — anti-bluff guard for
# tools/lava-containers/vm-images.json. Asserts that all entries except
# the documented upstream-unavailable alpine-edge-riscv64 placeholder
# have:
#   - non-zero SHA-256 (NOT the 64-char all-zero placeholder)
#   - positive size > 100 KB (qcow2 cloud images are at minimum ~30MB,
#     so any entry below 100KB is suspect)
#
# This test catches the "operator forgot to populate real hashes"
# bluff vector: without this gate, scripts/run-vm-{signing,distro}-
# matrix.sh + scripts/run-emulator-tests.sh --image-manifest could
# pull placeholder zeros into pkg/cache.Store.Get, where the
# downstream SHA-mismatch rejection would still catch it (defense in
# depth) — but THIS test catches it earlier at manifest-validation
# time, with a clearer error message.
#
# Falsifiability rehearsal: mutate any non-placeholder entry's
# sha256 field to "0".repeat(64); test fails with explicit
# "ENTRY <id>: sha256 is the all-zero placeholder" message.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MANIFEST="$REPO_ROOT/tools/lava-containers/vm-images.json"

if [[ ! -f "$MANIFEST" ]]; then
    echo "FAIL: manifest not found at $MANIFEST"
    exit 1
fi

if ! jq empty "$MANIFEST" 2>/dev/null; then
    echo "FAIL: manifest is not valid JSON"
    jq empty "$MANIFEST"
    exit 1
fi

ZERO_SHA='0000000000000000000000000000000000000000000000000000000000000000'

# Permitted upstream-unavailable placeholder list. Each entry MUST also
# carry a "_status" field documenting why it's a placeholder. Adding
# entries here is a deliberate operator decision that goes through
# manual review — automation MUST NOT silently extend this list.
declare -A PERMITTED_PLACEHOLDERS=(
    [alpine-edge-riscv64]=1
)

failures=0

while IFS= read -r entry_json; do
    id=$(jq -r '.id' <<<"$entry_json")
    sha=$(jq -r '.sha256' <<<"$entry_json")
    size=$(jq -r '.size' <<<"$entry_json")
    status=$(jq -r '._status // empty' <<<"$entry_json")

    if [[ "${PERMITTED_PLACEHOLDERS[$id]:-0}" == "1" ]]; then
        if [[ -z "$status" ]]; then
            echo "FAIL: ENTRY $id is on the permitted-placeholder list but lacks a _status field documenting why"
            failures=$((failures + 1))
            continue
        fi
        # Permitted placeholder: skip the real-hash check. Still
        # require size > 0 (manifest schema rejects size <= 0).
        if [[ "$size" -le 0 ]]; then
            echo "FAIL: ENTRY $id has size $size; manifest schema requires size > 0"
            failures=$((failures + 1))
        fi
        continue
    fi

    # Non-placeholder entries: must have real values.
    if [[ "$sha" == "$ZERO_SHA" ]]; then
        echo "FAIL: ENTRY $id: sha256 is the all-zero placeholder; populate via 'curl -fsSL <url> | sha256sum' before invoking matrix wrappers"
        failures=$((failures + 1))
        continue
    fi
    if [[ ${#sha} -ne 64 ]]; then
        echo "FAIL: ENTRY $id: sha256 length is ${#sha}, expected 64 hex chars"
        failures=$((failures + 1))
        continue
    fi
    if ! [[ "$sha" =~ ^[0-9a-f]+$ ]]; then
        echo "FAIL: ENTRY $id: sha256 contains non-hex characters"
        failures=$((failures + 1))
        continue
    fi
    if [[ "$size" -lt 102400 ]]; then
        echo "FAIL: ENTRY $id: size $size is below 100KB sanity floor; qcow2/zip images are larger"
        failures=$((failures + 1))
        continue
    fi
done < <(jq -c '.images[]' "$MANIFEST")

if [[ $failures -ne 0 ]]; then
    echo "[vm-images] $failures entry/entries failed validation"
    exit 1
fi

count=$(jq '.images | length' "$MANIFEST")
echo "[vm-images] OK: $count entries validated; permitted placeholders: ${!PERMITTED_PLACEHOLDERS[*]}"
exit 0
