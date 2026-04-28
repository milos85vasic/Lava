#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Build & Release Script for Lava
# ------------------------------------------------------------------------------
# Builds debug APK, release APK, proxy fat JAR, and the lava-api-go binary,
# then copies them into:
#   releases/{version}/android-debug/
#   releases/{version}/android-release/
#   releases/{version}/proxy/
#   releases/{version}/api-go/
# ------------------------------------------------------------------------------

# Extract Android app version and versionCode from app/build.gradle.kts
APP_VERSION=$(grep -E '^\s+versionName\s*=' app/build.gradle.kts | sed 's/.*"\([^"]*\)".*/\1/')
APP_VERSION_CODE=$(grep -E '^\s+versionCode\s*=' app/build.gradle.kts | sed 's/.*= \([0-9]*\).*/\1/')

# Extract proxy version from proxy/build.gradle.kts
PROXY_VERSION=$(grep -E '^\s*version\s*=' proxy/build.gradle.kts | head -1 | sed 's/.*"\([^"]*\)".*/\1/')

# Extract lava-api-go version from internal/version/version.go (same regex
# tag.sh uses, kept inline so this script remains standalone).
APIGO_VERSION=$(grep -E '^[[:space:]]*Name *= *"[^"]+"' lava-api-go/internal/version/version.go | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
APIGO_VERSION_CODE=$(grep -E '^[[:space:]]*Code *= *[0-9]+' lava-api-go/internal/version/version.go | head -1 | sed -E 's/.*= ([0-9]+).*/\1/')

echo "========================================"
echo "  Lava Build & Release"
echo "========================================"
echo "  App version:     $APP_VERSION ($APP_VERSION_CODE)"
echo "  Proxy version:   $PROXY_VERSION"
echo "  API-Go version:  $APIGO_VERSION ($APIGO_VERSION_CODE)"
echo "========================================"
echo ""

RELEASE_DIR="releases/$APP_VERSION"

# Clean previous builds for this version
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/android-debug"
mkdir -p "$RELEASE_DIR/android-release"
mkdir -p "$RELEASE_DIR/proxy"
mkdir -p "$RELEASE_DIR/api-go"

# Clean and build
echo "[1/5] Cleaning previous build artifacts..."
./gradlew clean --quiet

echo "[2/5] Building Android debug APK..."
./gradlew :app:assembleDebug

echo "[3/5] Building Android release APK..."
./gradlew :app:assembleRelease

echo "[4/5] Building proxy fat JAR..."
./gradlew :proxy:buildFatJar

echo "[5/5] Building lava-api-go static binary..."
APIGO_BIN_DST="$SCRIPT_DIR/$RELEASE_DIR/api-go/lava-api-go-${APIGO_VERSION}"
(
    cd lava-api-go
    CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o "$APIGO_BIN_DST" ./cmd/lava-api-go
)

# Copy artifacts with descriptive names
DEBUG_APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK_SRC="app/build/outputs/apk/release/app-release.apk"
PROXY_JAR_SRC="proxy/build/libs/app.jar"

DEBUG_APK_DST="$RELEASE_DIR/android-debug/digital.vasic.lava.client-${APP_VERSION}-debug.apk"
RELEASE_APK_DST="$RELEASE_DIR/android-release/digital.vasic.lava.client-${APP_VERSION}-release.apk"
PROXY_JAR_DST="$RELEASE_DIR/proxy/digital.vasic.lava.api-${PROXY_VERSION}.jar"

cp "$DEBUG_APK_SRC" "$DEBUG_APK_DST"
cp "$RELEASE_APK_SRC" "$RELEASE_APK_DST"
cp "$PROXY_JAR_SRC" "$PROXY_JAR_DST"

# Save the lava-api-go container image if a runtime is available; otherwise
# skip with a clear message. start.sh will recreate the image on demand,
# so this is not load-bearing for the binary release artifact.
APIGO_IMAGE_DST="$RELEASE_DIR/api-go/lava-api-go-${APIGO_VERSION}.image.tar"
if command -v podman >/dev/null 2>&1; then
    if podman image exists lava-api-go:dev 2>/dev/null; then
        echo "[image] saving lava-api-go:dev via podman → $APIGO_IMAGE_DST"
        podman save lava-api-go:dev -o "$APIGO_IMAGE_DST"
    else
        echo "[image] podman is installed but lava-api-go:dev image is not built locally — skipping image save"
        echo "        (start.sh builds it on demand; releases/{version}/api-go/ will only contain the binary)"
    fi
elif command -v docker >/dev/null 2>&1; then
    if docker image inspect lava-api-go:dev >/dev/null 2>&1; then
        echo "[image] saving lava-api-go:dev via docker → $APIGO_IMAGE_DST"
        docker save lava-api-go:dev -o "$APIGO_IMAGE_DST"
    else
        echo "[image] docker is installed but lava-api-go:dev image is not built locally — skipping image save"
        echo "        (start.sh builds it on demand; releases/{version}/api-go/ will only contain the binary)"
    fi
else
    echo "[image] neither podman nor docker is installed — skipping image save"
    echo "        (releases/{version}/api-go/ will only contain the binary; start.sh builds the image on demand)"
fi

echo ""
echo "========================================"
echo "  Release artifacts ready"
echo "========================================"
echo "  $DEBUG_APK_DST"
echo "  $RELEASE_APK_DST"
echo "  $PROXY_JAR_DST"
echo "  $APIGO_BIN_DST"
[[ -f "$APIGO_IMAGE_DST" ]] && echo "  $APIGO_IMAGE_DST"
echo "========================================"
