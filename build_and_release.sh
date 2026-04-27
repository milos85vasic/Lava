#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Build & Release Script for Lava
# ------------------------------------------------------------------------------
# Builds debug APK, release APK, and proxy fat JAR, then copies them into:
#   releases/{version}/android-debug/
#   releases/{version}/android-release/
#   releases/{version}/proxy/
# ------------------------------------------------------------------------------

# Extract Android app version and versionCode from app/build.gradle.kts
APP_VERSION=$(grep -E '^\s+versionName\s*=' app/build.gradle.kts | sed 's/.*"\([^"]*\)".*/\1/')
APP_VERSION_CODE=$(grep -E '^\s+versionCode\s*=' app/build.gradle.kts | sed 's/.*= \([0-9]*\).*/\1/')

# Extract proxy version from proxy/build.gradle.kts
PROXY_VERSION=$(grep -E '^\s*version\s*=' proxy/build.gradle.kts | head -1 | sed 's/.*"\([^"]*\)".*/\1/')

echo "========================================"
echo "  Lava Build & Release"
echo "========================================"
echo "  App version:     $APP_VERSION ($APP_VERSION_CODE)"
echo "  Proxy version:   $PROXY_VERSION"
echo "========================================"
echo ""

RELEASE_DIR="releases/$APP_VERSION"

# Clean previous builds for this version
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/android-debug"
mkdir -p "$RELEASE_DIR/android-release"
mkdir -p "$RELEASE_DIR/proxy"

# Clean and build
echo "[1/4] Cleaning previous build artifacts..."
./gradlew clean --quiet

echo "[2/4] Building Android debug APK..."
./gradlew :app:assembleDebug

echo "[3/4] Building Android release APK..."
./gradlew :app:assembleRelease

echo "[4/4] Building proxy fat JAR..."
./gradlew :proxy:buildFatJar

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

echo ""
echo "========================================"
echo "  Release artifacts ready"
echo "========================================"
echo "  $DEBUG_APK_DST"
echo "  $RELEASE_APK_DST"
echo "  $PROXY_JAR_DST"
echo "========================================"
