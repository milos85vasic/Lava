#!/bin/bash
set -euo pipefail

# release-build.sh - Build script for Lava components
# Used by distributed-build to build individual components inside builder containers
#
# Arguments:
#   --local              Run in local mode (inside builder container)
#   --component NAME     Component to build (app, api-app, lava-api-go)
#   --build-type TYPE    Build type (debug, release) - default: release
#   --version VERSION    Version string for artifacts
#   --skip-tests         Skip test execution
#   --force              Force rebuild (always passed by executor)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/.."

# Default values
LOCAL_MODE=false
COMPONENT=""
BUILD_TYPE="release"
VERSION=""
SKIP_TESTS=false
FORCE=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --local)
            LOCAL_MODE=true
            shift
            ;;
        --component)
            COMPONENT="$2"
            shift 2
            ;;
        --build-type)
            BUILD_TYPE="$2"
            shift 2
            ;;
        --version)
            VERSION="$2"
            shift 2
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        --force)
            FORCE=true
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Set JAVA_HOME based on environment
# In local mode (inside builder container), use /opt/java/openjdk
# On host, use the specific JDK 17 installation
if [[ "$LOCAL_MODE" == "true" ]]; then
    export JAVA_HOME="/opt/java/openjdk"
else
    export JAVA_HOME="/tmp/jdk-17.0.13+11"
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

# Validate required arguments
if [[ -z "$COMPONENT" ]]; then
    echo "Error: --component is required"
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
        ;;
    api-app)
        MODULE=":api-app"
        APP_ID="digital.vasic.lava.api"
        APK_NAME_DEBUG="api-app-debug.apk"
        APK_NAME_RELEASE="api-app-release.apk"
        ;;
    lava-api-go)
        MODULE=""
        APP_ID="lava-api-go"
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
echo "  Building component: $COMPONENT"
echo "  Build type: $BUILD_TYPE"
echo "  Version: $VERSION"
echo "  Output: $RELEASE_DIR"
echo "========================================"

# Build function for Android components
build_android() {
    local module="$1"
    local build_type="$2"
    local app_id="$3"
    local apk_debug="$4"
    local apk_release="$5"

    if [[ "$build_type" == "debug" ]]; then
        echo "Building debug APK..."
        ./gradlew "${module}:assembleDebug" --no-daemon
        MODULE_PATH="${module#:}"
        APK_SRC="${MODULE_PATH}/build/outputs/apk/debug/${apk_debug}"
        APK_DST="${RELEASE_DIR}/${app_id}-${VERSION}-debug.apk"
    else
        echo "Building release APK..."
        ./gradlew "${module}:assembleRelease" --no-daemon
        MODULE_PATH="${module#:}"
        APK_SRC="${MODULE_PATH}/build/outputs/apk/release/${apk_release}"
        APK_DST="${RELEASE_DIR}/${app_id}-${VERSION}-release.apk"
    fi

    if [[ -f "$APK_SRC" ]]; then
        cp "$APK_SRC" "$APK_DST"
        echo "APK copied to: $APK_DST"
    else
        echo "Error: APK not found at $APK_SRC"
        exit 1
    fi
}

# Build function for Go component
build_go() {
    echo "Building lava-api-go binary..."
    BIN_DST="${RELEASE_DIR}/lava-api-go-${VERSION}"
    (
        cd lava-api-go
        CGO_ENABLED=0 go build -trimpath -buildvcs=false -ldflags='-s -w' -o "../${BIN_DST}" ./cmd/lava-api-go
    )

    if [[ -f "$BIN_DST" ]]; then
        echo "Binary copied to: $BIN_DST"
    else
        echo "Error: Binary not found at $BIN_DST"
        exit 1
    fi

    # Build Docker image if podman/docker available
    if command -v podman >/dev/null 2>&1; then
        echo "Building Docker image with podman..."
        export BUILDAH_FORMAT=docker
        podman build --format=docker --target runtime \
            -f lava-api-go/docker/Dockerfile \
            -t lava-api-go:dev \
            .
        IMAGE_DST="${RELEASE_DIR}/lava-api-go-${VERSION}.image.tar"
        podman save lava-api-go:dev -o "$IMAGE_DST"
        echo "Image saved to: $IMAGE_DST"
    elif command -v docker >/dev/null 2>&1; then
        echo "Building Docker image with docker..."
        export BUILDAH_FORMAT=docker
        docker build --target runtime \
            -f lava-api-go/docker/Dockerfile \
            -t lava-api-go:dev \
            .
        IMAGE_DST="${RELEASE_DIR}/lava-api-go-${VERSION}.image.tar"
        docker save lava-api-go:dev -o "$IMAGE_DST"
        echo "Image saved to: $IMAGE_DST"
    else
        echo "No container runtime found, skipping image build"
    fi
}

# Run tests if not skipped
run_tests() {
    if [[ "$SKIP_TESTS" == "true" ]]; then
        echo "Skipping tests (--skip-tests)"
        return
    fi

    case "$COMPONENT" in
        app|api-app)
            echo "Running tests for $COMPONENT..."
            ./gradlew "${MODULE}:test" --no-daemon
            ;;
        lava-api-go)
            echo "Running tests for lava-api-go..."
            (
                cd lava-api-go
                go test -race -count=1 ./...
            )
            ;;
    esac
}

# Main build logic
case "$COMPONENT" in
    app)
        build_android "$MODULE" "$BUILD_TYPE" "$APP_ID" "$APK_NAME_DEBUG" "$APK_NAME_RELEASE"
        ;;
    api-app)
        build_android "$MODULE" "$BUILD_TYPE" "$APP_ID" "$APK_NAME_DEBUG" "$APK_NAME_RELEASE"
        ;;
    lava-api-go)
        build_go
        ;;
esac

# Run tests
run_tests

# Create BUILD_INFO.json
BUILD_INFO="${RELEASE_DIR}/BUILD_INFO.json"
cat > "$BUILD_INFO" <<EOF
{
  "component": "${COMPONENT}",
  "version": "${VERSION}",
  "build_type": "${BUILD_TYPE}",
  "build_timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "git_commit": "$(git rev-parse HEAD 2>/dev/null || echo 'unknown')",
  "git_branch": "$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')"
}
EOF

echo "BUILD_INFO.json created at: $BUILD_INFO"
echo ""
echo "========================================"
echo "  Build completed successfully"
echo "========================================"
echo "Artifacts in: $RELEASE_DIR"
ls -la "$RELEASE_DIR"