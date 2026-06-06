#!/usr/bin/env bash
#
# compute-api-source-hash.sh — the SINGLE SOURCE OF TRUTH for "what lava-api-go
# source codebase does the on-device API embed (liblavaapi.so) contain?".
#
# It emits a deterministic 64-hex sha256 over the EXACT set of Go source files
# that transitively compile into the c-shared `.so` produced by
# lava-api-go/scripts/build-cshared.sh — namely:
#
#   - every non-test `.go` file under lava-api-go/cmd/lavaapi-cshared/
#   - every non-test `.go` file under lava-api-go/internal/
#   - lava-api-go/go.mod
#   - lava-api-go/go.sum
#
# `*_test.go` files are EXCLUDED: they do not compile into the `.so`, so a test
# edit must NOT invalidate the embed's source identity (otherwise the gate would
# false-positive on test-only changes and erode trust). The module's `cmd/`
# entrypoints OTHER than lavaapi-cshared (lava-api-go, healthprobe, lava-migrate)
# are EXCLUDED because they are not linked into the embed.
#
# Determinism contract (the property the §6.J falsifiability rehearsal proves):
#   1. The file LIST is sorted with `LC_ALL=C sort` (byte order, locale-stable).
#   2. For each file we hash the RELATIVE PATH (module-rooted) AND the file
#      CONTENT, so a rename, a move, or a content edit each change the digest.
#   3. The final digest is sha256(stream-of-per-file-digests), so reordering is
#      impossible and the value is reproducible across runs / hosts / shells.
#
# This script is the ONE function both the build (build-cshared.sh injects the
# hash into the .so + writes the committed manifest) and the gate
# (check-api-app-sync.sh recomputes + compares) call. There is exactly one
# definition of "the embed's source hash"; drift is mechanically impossible to
# hide.
#
# Output: a bare 64-hex sha256 on stdout (no trailing label). Exit 0 on success.
#
# §6.R: no hardcoded secrets/addresses/ports here — only repo-relative source
# paths. §11.4.67 / §6.T.2: bounded, read-only, no host mutation.
#
# See docs/scripts/compute-api-source-hash.sh.md for the full guide.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
API_DIR="${REPO_ROOT}/lava-api-go"

if [[ ! -d "${API_DIR}" ]]; then
    echo "FATAL: lava-api-go not found at ${API_DIR}" >&2
    exit 2
fi

# Resolve a sha256 helper that emits a bare hash (first field). macOS ships
# `shasum -a 256`; Linux ships `sha256sum`. Both print "<hash>  <file|->".
sha256_of_stdin() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | awk '{print $1}'
    else
        shasum -a 256 | awk '{print $1}'
    fi
}

# Build the file list, module-relative, deterministically sorted (byte order).
# Sources: cmd/lavaapi-cshared + internal, non-test .go only. We list paths
# relative to API_DIR so the digest is independent of the absolute checkout
# location (a clone in a different dir produces the SAME hash for the SAME code).
cd "${API_DIR}"

file_list="$(
    {
        find cmd/lavaapi-cshared internal -type f -name '*.go' ! -name '*_test.go' 2>/dev/null
        # go.mod / go.sum pin the dependency graph that links into the .so; a
        # dependency bump changes the embed even if no first-party .go changed.
        [[ -f go.mod ]] && echo "go.mod"
        [[ -f go.sum ]] && echo "go.sum"
    } | LC_ALL=C sort
)"

if [[ -z "${file_list}" ]]; then
    echo "FATAL: no embed source files found under ${API_DIR} (cmd/lavaapi-cshared + internal)" >&2
    exit 2
fi

# Compute a per-file digest of "relative-path\0content" for every file, in the
# sorted order, then hash the concatenation of those per-file digests. Using the
# NUL separator between path and content makes path/content boundaries
# unambiguous. The outer hash over the ordered per-file hashes makes reordering
# detectable and the whole value stable.
{
    while IFS= read -r f; do
        [[ -n "${f}" ]] || continue
        # Per-file digest = sha256( "<relpath>\0" + <file bytes> ).
        per_file="$(
            {
                printf '%s\0' "${f}"
                cat -- "${f}"
            } | sha256_of_stdin
        )"
        printf '%s  %s\n' "${per_file}" "${f}"
    done <<< "${file_list}"
} | sha256_of_stdin
