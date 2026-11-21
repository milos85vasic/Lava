#!/usr/bin/env bash
# scripts/local-container-build.sh - Build Lava components inside builder containers using Podman
# Implements §6.K Builds-Inside-Containers mandate: release-artifact builds run in container-bound path
#
# Usage:
#   ./scripts/local-container-build.sh --component <app|api-app|lava-api-go> --version <version> [--build-type <debug|release>] [--skip-tests]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

# Default values
COMPONENT=""
VERSION=""
BUILD_TYPE="release"
SKIP_TESTS=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --component)
            COMPONENT="$2"
            shift 2
            ;;
        --version)
            VERSION="$2"
            shift 2
            ;;
        --build-type)
            BUILD_TYPE="$2"
            shift 2
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            echo "Usage: $0 --component <app|api-app|lava-api-go> --version <version> [--build-type <debug|release>] [--skip-tests]"
            exit 1
            ;;
    esac
done

# Validate required arguments
if [[ -z "$COMPONENT" ]]; then
    echo "Error: --component is required (app, api-app, or lava-api-go)"
    exit 1
fi

if [[ -z "$VERSION" ]]; then
    echo "Error: --version is required"
    exit 1
fi

# Component-specific settings
case "$COMPONENT" in
    app)
        MODULE=":app"
        APP_ID="digital.vasic.lava.client"
        APK_NAME_DEBUG="app-debug.apk"
        APK_NAME_RELEASE="app-release.apk"
        BUILDER_IMAGE="localhost/lava-android-builder:latest"
        ;;
    api-app)
        MODULE=":api-app"
        APP_ID="digital.vasic.lava.api"
        APK_NAME_DEBUG="api-app-debug.apk"
        APK_NAME_RELEASE="api-app-release.apk"
        BUILDER_IMAGE="localhost/lava-android-builder:latest"
        ;;
    lava-api-go)
        MODULE=""
        APP_ID="lava-api-go"
        BUILDER_IMAGE="localhost/lava-go-builder:latest"
        ;;
    *)
        echo "Error: Unknown component: $COMPONENT"
        exit 1
        ;;
esac

# Determine output directory
RELEASE_DIR="releases/${COMPONENT}/${VERSION}"
mkdir -p "$RELEASE_DIR"

echo "========================================"
echo "  Building component: $COMPONENT (in container)"
echo "  Builder image: $BUILDER_IMAGE"
echo "  Build type: $BUILD_TYPE"
echo "  Version: $VERSION"
echo "  Output: $RELEASE_DIR"
echo "========================================"

# Build function for Android components (runs inside android-builder container)
build_android_in_container() {
    local module="$1"
    local build_type="$2"
    local app_id="$3"
    local apk_debug="$4"
    local apk_release="$5"

    # Prepare gradle command
    if [[ "$build_type" == "debug" ]]; then
        GRADLE_TASK="${module}:assembleDebug"
        APK_SRC="${module#:}/build/outputs/apk/debug/${apk_debug}"
        APK_DST="${RELEASE_DIR}/${app_id}-${VERSION}-debug.apk"
    else
        GRADLE_TASK="${module}:assembleRelease"
        APK_SRC="${module#:}/build/outputs/apk/release/${apk_release}"
        APK_DST="${RELEASE_DIR}/${app_id}-${VERSION}-release.apk"
    fi

    echo "Building ${build_type} APK inside container..."
    
    # Run gradle inside the android-builder container
    # Mount project root and gradle cache, use --userns=keep-id for permission fix
    podman run --rm --userns=keep-id \
        -v "${PROJECT_ROOT}:/project:Z" \
        -v "${HOME}/.gradle:/home/builder/.gradle:Z" \
        -w /project \
        -e JAVA_HOME=/opt/java/openjdk \
        -e GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx4g" \
        "${BUILDER_IMAGE}" \
        bash -c "cd /project && ./gradlew ${GRADLE_TASK} --no-daemon"

    # Copy APK from container workspace to host release dir
    # The APK is built in the mounted volume, so it's already on host
    if [[ -f "${PROJECT_ROOT}/${APK_SRC}" ]]; then
        cp "${PROJECT_ROOT}/${APK_SRC}" "${APK_DST}"
        echo "APK copied to: ${APK_DST}"
    else
        echo "Error: APK not found at ${PROJECT_ROOT}/${APK_SRC}"
        exit 1
    fi
}

# Build function for Go component (runs inside go-builder container)
build_go_in_container() {
    echo "Building lava-api-go binary inside container..."
    
    BIN_DST="${RELEASE_DIR}/lava-api-go-${VERSION}"
    
    # Build binary inside go-builder container - use --userns=keep-id to fix permission issues
    podman run --rm --userns=keep-id \
        -v "${PROJECT_ROOT}:/project:Z" \
        -w /project \
        "${BUILDER_IMAGE}" \
        bash -c "cd /project/lava-api-go && CGO_ENABLED=0 go build -buildvcs=false -trimpath -ldflags='-s -w' -o /project/${BIN_DST} ./cmd/lava-api-go"

    if [[ -f "${PROJECT_ROOT}/${BIN_DST}" ]]; then
        echo "Binary copied to: ${BIN_DST}"
    else
        echo "Error: Binary not found at ${PROJECT_ROOT}/${BIN_DST}"
        exit 1
    fi

    # Build Docker image on host (using project root as context)
    echo "Building Docker image with host podman..."
    export BUILDAH_FORMAT=docker
    cd "${PROJECT_ROOT}"
    podman build --format=docker --target runtime -f lava-api-go/docker/Dockerfile -t lava-api-go:dev .
    IMAGE_DST="${RELEASE_DIR}/lava-api-go-${VERSION}.image.tar"
    podman save lava-api-go:dev -o "${PROJECT_ROOT}/${IMAGE_DST}"
    echo "Image saved to: ${IMAGE_DST}"
}

# Run tests inside container
run_tests_in_container() {
    if [[ "$SKIP_TESTS" == "true" ]]; then
        echo "Skipping tests (--skip-tests)"
        return
    fi

    case "$COMPONENT" in
        app|api-app)
            echo "Running tests for $COMPONENT inside container..."
            podman run --rm --userns=keep-id \
                -v "${PROJECT_ROOT}:/project:Z" \
                -v "${HOME}/.gradle:/home/builder/.gradle:Z" \
                -w /project \
                -e JAVA_HOME=/opt/java/openjdk \
                -e GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx4g" \
                "${BUILDER_IMAGE}" \
                bash -c "cd /project && ./gradlew ${MODULE}:test --no-daemon"
            ;;
        lava-api-go)
            echo "Running tests for lava-api-go inside container..."
            podman run --rm --userns=keep-id \
                -v "${PROJECT_ROOT}:/project:Z" \
                -w /project \
                "${BUILDER_IMAGE}" \
                bash -c "cd /project/lava-api-go && go test -race -count=1 ./..."
            ;;
    esac
}

# Main build logic
case "$COMPONENT" in
    app|api-app)
        build_android_in_container "$MODULE" "$BUILD_TYPE" "$APP_ID" "$APK_NAME_DEBUG" "$APK_NAME_RELEASE"
        ;;
    lava-api-go)
        build_go_in_container
        ;;
esac

# Run tests
run_tests_in_container

# Create BUILD_INFO.json
BUILD_INFO="${RELEASE_DIR}/BUILD_INFO.json"
cat > "${PROJECT_ROOT}/${BUILD_INFO}" <<EOF
{
  "component": "${COMPONENT}",
  "version": "${VERSION}",
  "build_type": "${BUILD_TYPE}",
  "build_timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "git_commit": "$(git -C "${PROJECT_ROOT}" rev-parse HEAD 2>/dev/null || echo 'unknown')",
  "git_branch": "$(git -C "${PROJECT_ROOT}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')",
  "build_method": "containerized (podman)"
}
EOF

echo "BUILD_INFO.json created at: ${BUILD_INFO}"
echo ""
echo "========================================"
echo "  Containerized build completed successfully"
echo "========================================"
echo "Artifacts in: ${RELEASE_DIR}"
ls -la "${PROJECT_ROOT}/${RELEASE_DIR}"