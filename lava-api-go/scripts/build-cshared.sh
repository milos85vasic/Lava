#!/usr/bin/env bash
#
# build-cshared.sh — cross-compile the lava-api-go embed into Android-loadable
# native libraries via `go build -buildmode=c-shared`.
#
# For each Android ABI, this sets CGO_ENABLED=1 + the matching NDK clang as CC,
# then builds ./cmd/lavaapi-cshared into build/jniLibs/<abi>/liblavaapi.so plus
# the cgo-generated liblavaapi.h header. After each build it prints the .so's
# sha256 + size and verifies the exported LavaApi* symbols are present via the
# NDK's llvm-nm. Unlike gomobile bind, c-shared uses normal Go module
# resolution, so it HONORS lava-api-go's relative `replace ../submodules/*`
# directives.
#
# Usage:
#   scripts/build-cshared.sh [ABI ...]
#     ABI ∈ { arm64-v8a, x86_64, armeabi-v7a }; default = all three.
#
# Env overrides:
#   ANDROID_NDK_HOME   NDK root. If unset, it is resolved OS-aware in priority
#                      order: (1) ANDROID_SDK_ROOT/ANDROID_HOME + ndk/<newest>;
#                      (2) the OS-conventional SDK location (macOS:
#                      ~/Library/Android/sdk, Linux: ~/Android/Sdk, Windows:
#                      %LOCALAPPDATA%/Android/Sdk) + ndk/<newest>. The newest
#                      ndk/* version dir is chosen via `sort -V`.
#   ANDROID_SDK_ROOT / ANDROID_HOME   Android SDK root (used to derive the NDK
#                      when ANDROID_NDK_HOME is unset).
#   ANDROID_API        Android API level for the clang wrapper (default: 28)
#
# Anti-Bluff: this script reports REAL build output. A failed build or a missing
# exported symbol is reported as a failure with verbatim output; no .so is
# faked. The .so binaries are NOT committed (build/jniLibs is gitignored).
#
# See docs/scripts/build-cshared.sh.md for the full guide.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUT_ROOT="${MODULE_DIR}/build/jniLibs"
PKG="./cmd/lavaapi-cshared"

# §6.R no-hardcoding: the embed's default Lava-Auth header name MUST NOT be a
# source literal. It is injected into internal/mobile.defaultAuthFieldName at
# build time via `-ldflags -X`, sourced from LAVA_AUTH_FIELD_NAME in the
# gitignored repo-root .env (or an already-exported env var). The value never
# appears in any tracked file. The Android host app also passes authFieldName in
# the Start config, so this default is only a fallback; injecting it keeps the
# embed honest when the host config omits it.
REPO_ROOT="$(cd "${MODULE_DIR}/.." && pwd)"
if [[ -z "${LAVA_AUTH_FIELD_NAME:-}" && -f "${REPO_ROOT}/.env" ]]; then
    LAVA_AUTH_FIELD_NAME="$(grep -E '^LAVA_AUTH_FIELD_NAME=' "${REPO_ROOT}/.env" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'")"
fi
MOBILE_PKG="digital.vasic.lava.apigo/internal/mobile"
# Assemble the full -ldflags as a single quoted unit in an array so a
# LAVA_AUTH_FIELD_NAME containing whitespace does not split into bogus link
# tokens (mirrors the robust array pattern in build-aar.sh). The -extldflags
# soname directive is always present; the -X injection is appended only when the
# env var is set. Go concatenates multiple space-separated entries within one
# -ldflags value, and the -X 'sym=value' form (quoted) keeps a spaced value
# intact as a single linker symbol assignment.
LDFLAGS_VALUE="-extldflags=-Wl,-soname,liblavaapi.so"
if [[ -n "${LAVA_AUTH_FIELD_NAME:-}" ]]; then
    LDFLAGS_VALUE="${LDFLAGS_VALUE} -X '${MOBILE_PKG}.defaultAuthFieldName=${LAVA_AUTH_FIELD_NAME}'"
    echo "Auth field name injected via -ldflags -X (from .env/env)"
else
    echo "WARNING: LAVA_AUTH_FIELD_NAME not set and no repo-root .env — the embed's" >&2
    echo "         default auth field name will be empty; Start() will REQUIRE the" >&2
    echo "         host app to pass authFieldName in its Start config (§6.R)." >&2
fi

# API↔embed sync (drift prevention): compute the SINGLE-SOURCE-OF-TRUTH hash of
# the exact lava-api-go source codebase that compiles into this .so, via
# scripts/compute-api-source-hash.sh in the repo root. Inject it into
# internal/version.SourceHash so the running embed reports — through
# mobile.Status() — precisely which source it contains. After the per-ABI
# builds succeed we ALSO write this hash to the committed manifest
# core/apiengine/src/main/resources/api-source.hash, which the gate
# scripts/check-api-app-sync.sh recompares. Equal hashes prove the on-device
# embed equals the current API codebase; any drift is mechanically loud.
VERSION_PKG="digital.vasic.lava.apigo/internal/version"
HASH_SCRIPT="${REPO_ROOT}/scripts/compute-api-source-hash.sh"
API_SOURCE_HASH=""
if [[ -x "${HASH_SCRIPT}" ]]; then
    API_SOURCE_HASH="$(bash "${HASH_SCRIPT}")"
    LDFLAGS_VALUE="${LDFLAGS_VALUE} -X '${VERSION_PKG}.SourceHash=${API_SOURCE_HASH}'"
    echo "API source hash injected via -ldflags -X: ${API_SOURCE_HASH}"
else
    echo "WARNING: ${HASH_SCRIPT} not found/executable — the embed will report an" >&2
    echo "         EMPTY SourceHash; the on-device sync Challenge treats empty as a" >&2
    echo "         hard fail, and the manifest will NOT be refreshed." >&2
fi

# OS-aware NDK/SDK path resolution (§11.4.81 cross-platform parity). Priority:
#   (a) an already-exported ANDROID_NDK_HOME is honored verbatim;
#   (b) else derive from the SDK root (ANDROID_SDK_ROOT or ANDROID_HOME) +
#       ndk/<newest> when that SDK root is known and has an ndk/ dir;
#   (c) else fall back to the OS-conventional SDK location per `uname -s`
#       (macOS: ~/Library/Android/sdk, Linux: ~/Android/Sdk,
#       Windows/MSYS: %LOCALAPPDATA%/Android/Sdk) + ndk/<newest>.
# The newest ndk/* version directory is selected via `sort -V`. A clear FATAL
# is raised if no NDK can be located so the failure mode is loud, not silent.

# Pick the highest-versioned ndk/<ver> dir under a given SDK root. Echoes the
# absolute NDK path on success; echoes nothing if the SDK root has no ndk dirs.
resolve_newest_ndk() {
    local sdk_root="$1"
    [[ -n "${sdk_root}" && -d "${sdk_root}/ndk" ]] || return 0
    local newest
    newest="$(ls -1 "${sdk_root}/ndk" 2>/dev/null | sort -V | tail -1)"
    [[ -n "${newest}" ]] || return 0
    echo "${sdk_root}/ndk/${newest}"
}

# OS-conventional SDK root per the running host.
os_conventional_sdk_root() {
    case "$(uname -s)" in
        Darwin)            echo "${HOME}/Library/Android/sdk" ;;
        Linux)             echo "${HOME}/Android/Sdk" ;;
        MINGW*|MSYS*|CYGWIN*) echo "${LOCALAPPDATA:-${HOME}/AppData/Local}/Android/Sdk" ;;
        *)                 echo "" ;;
    esac
}

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    # (b) Derive from a known SDK root (ANDROID_SDK_ROOT preferred, then ANDROID_HOME).
    for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}"; do
        [[ -n "${sdk_root}" ]] || continue
        cand="$(resolve_newest_ndk "${sdk_root}")"
        if [[ -n "${cand}" ]]; then
            ANDROID_NDK_HOME="${cand}"
            break
        fi
    done
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    # (c) Fall back to the OS-conventional SDK location.
    conv_sdk_root="$(os_conventional_sdk_root)"
    cand="$(resolve_newest_ndk "${conv_sdk_root}")"
    if [[ -n "${cand}" ]]; then
        ANDROID_NDK_HOME="${cand}"
    fi
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    echo "FATAL: could not locate an Android NDK." >&2
    echo "       Tried (in order): \$ANDROID_NDK_HOME, \$ANDROID_SDK_ROOT/ndk/*," >&2
    echo "       \$ANDROID_HOME/ndk/*, and the OS-conventional SDK location" >&2
    echo "       ('$(os_conventional_sdk_root)/ndk/*' on $(uname -s))." >&2
    echo "       Install the NDK or export ANDROID_NDK_HOME to its root." >&2
    exit 2
fi
ANDROID_API="${ANDROID_API:-28}"

# Resolve the NDK toolchain bin dir. The prebuilt dir is named for the BUILD
# host; on Apple Silicon macs it is still 'darwin-x86_64' (run via Rosetta), so
# we probe for whichever exists rather than assuming.
TOOLCHAIN_ROOT="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt"
NDK_BIN=""
for cand in darwin-arm64 darwin-x86_64 linux-x86_64; do
    if [[ -d "${TOOLCHAIN_ROOT}/${cand}/bin" ]]; then
        NDK_BIN="${TOOLCHAIN_ROOT}/${cand}/bin"
        break
    fi
done
if [[ -z "${NDK_BIN}" ]]; then
    echo "FATAL: no NDK toolchain bin dir found under ${TOOLCHAIN_ROOT}" >&2
    echo "       (looked for darwin-arm64 / darwin-x86_64 / linux-x86_64)" >&2
    exit 2
fi

LLVM_NM="${NDK_BIN}/llvm-nm"
if [[ ! -x "${LLVM_NM}" ]]; then
    echo "FATAL: llvm-nm not found/executable at ${LLVM_NM}" >&2
    exit 2
fi

echo "NDK:       ${ANDROID_NDK_HOME}"
echo "NDK bin:   ${NDK_BIN}"
echo "API level: ${ANDROID_API}"
echo "Go:        $(go version)"
echo

# ABI → (GOARCH, clang-wrapper-basename, optional GOARM). The clang wrappers are
# named <triple><api>-clang under the NDK bin dir.
abi_to_goarch() {
    case "$1" in
        arm64-v8a)   echo "arm64" ;;
        x86_64)      echo "amd64" ;;
        armeabi-v7a) echo "arm" ;;
        *) echo "" ;;
    esac
}
abi_to_clang() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android${ANDROID_API}-clang" ;;
        x86_64)      echo "x86_64-linux-android${ANDROID_API}-clang" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi${ANDROID_API}-clang" ;;
        *) echo "" ;;
    esac
}

ABIS=("$@")
if [[ ${#ABIS[@]} -eq 0 ]]; then
    ABIS=(arm64-v8a x86_64 armeabi-v7a)
fi

overall_rc=0

for ABI in "${ABIS[@]}"; do
    GOARCH="$(abi_to_goarch "${ABI}")"
    CLANG_NAME="$(abi_to_clang "${ABI}")"
    if [[ -z "${GOARCH}" || -z "${CLANG_NAME}" ]]; then
        echo "ERROR: unknown ABI '${ABI}' (expected arm64-v8a|x86_64|armeabi-v7a)" >&2
        overall_rc=1
        continue
    fi
    CC="${NDK_BIN}/${CLANG_NAME}"
    if [[ ! -x "${CC}" ]]; then
        echo "ERROR [${ABI}]: clang wrapper not found at ${CC}" >&2
        overall_rc=1
        continue
    fi

    OUT_DIR="${OUT_ROOT}/${ABI}"
    OUT_SO="${OUT_DIR}/liblavaapi.so"
    mkdir -p "${OUT_DIR}"

    echo "=== Building ${ABI} (GOARCH=${GOARCH}) ==="
    echo "CC=${CC}"

    # SONAME fix (2026-06-02): pass `-Wl,-soname,liblavaapi.so` to the NDK
    # linker so the produced .so records a DT_SONAME of `liblavaapi.so`. Without
    # it, any consumer that links against this .so by its absolute on-disk path
    # (the JNI bridge's CMake `IMPORTED_LOCATION` does exactly that) records the
    # ABSOLUTE HOST BUILD PATH as its DT_NEEDED entry. On-device, the Android
    # linker cannot resolve that host path → `dlopen failed: library
    # "/Users/.../liblavaapi.so" not found` → the embed never starts. With the
    # SONAME present, the linker records the SONAME (`liblavaapi.so`, relative)
    # instead of the path, so the runtime resolves it from the APK's lib dir.
    # Proven by `llvm-readelf -d` (DT_SONAME on this .so + DT_NEEDED on the
    # bridge). Forensic anchor:
    # .lava-ci-evidence/phase-e-api-app/2026-06-02-challenge-green-after-serialization-fix.md
    set +e
    (
        cd "${MODULE_DIR}"
        # -ldflags + its value are two separate argv tokens (the robust form),
        # so the shell does not field-split the value; the -X 'sym=value' inside
        # is quoted so a spaced field name stays one linker symbol assignment.
        CGO_ENABLED=1 GOOS=android GOARCH="${GOARCH}" CC="${CC}" \
            GOMAXPROCS=2 nice -n 19 \
            go build -buildmode=c-shared \
                -ldflags "${LDFLAGS_VALUE}" \
                -o "${OUT_SO}" "${PKG}"
    )
    build_rc=$?
    set -e

    if [[ ${build_rc} -ne 0 ]]; then
        echo "FAILED [${ABI}]: go build exited ${build_rc}" >&2
        overall_rc=1
        echo
        continue
    fi

    if [[ ! -f "${OUT_SO}" ]]; then
        echo "FAILED [${ABI}]: build reported success but ${OUT_SO} is missing" >&2
        overall_rc=1
        echo
        continue
    fi

    # Real evidence: file type, size, sha256.
    SIZE="$(wc -c < "${OUT_SO}" | tr -d ' ')"
    SHA="$(shasum -a 256 "${OUT_SO}" | awk '{print $1}')"
    echo "Output:  ${OUT_SO}"
    echo "Size:    ${SIZE} bytes"
    echo "SHA256:  ${SHA}"
    echo "File:    $(file "${OUT_SO}")"

    # Verify exported symbols via the NDK llvm-nm. -D lists dynamic symbols.
    echo "--- llvm-nm -D (LavaApi symbols) ---"
    NM_OUT="$("${LLVM_NM}" -D "${OUT_SO}" 2>&1 | grep -E 'LavaApi' || true)"
    echo "${NM_OUT}"
    if echo "${NM_OUT}" | grep -q 'LavaApiStart'; then
        echo "OK [${ABI}]: LavaApiStart present in dynamic symbol table"
    else
        echo "FAILED [${ABI}]: LavaApiStart NOT found in dynamic symbols" >&2
        overall_rc=1
    fi
    echo
done

if [[ ${overall_rc} -eq 0 ]]; then
    echo "ALL REQUESTED ABIS BUILT OK"
    # Refresh the committed API↔embed sync manifest with the SAME hash that was
    # injected into every freshly-built .so. The gate (check-api-app-sync.sh)
    # compares a live recompute against THIS file; writing it here keeps "the .so
    # I built" and "the hash I claim it was built from" in lockstep. Only written
    # on a full successful build with a computed hash — never faked, never stale.
    if [[ -n "${API_SOURCE_HASH}" ]]; then
        MANIFEST="${REPO_ROOT}/core/apiengine/src/main/resources/api-source.hash"
        mkdir -p "$(dirname "${MANIFEST}")"
        printf '%s\n' "${API_SOURCE_HASH}" > "${MANIFEST}"
        echo "API↔embed sync manifest refreshed: ${MANIFEST}"
        echo "  hash: ${API_SOURCE_HASH}"
    else
        echo "NOTE: API source hash was not computed; manifest left unchanged." >&2
    fi
else
    echo "ONE OR MORE ABIS FAILED (rc=${overall_rc})" >&2
fi
exit ${overall_rc}
