#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# ------------------------------------------------------------------------------
# Lava Container Start Script
# ------------------------------------------------------------------------------
# Uses digital.vasic.containers technology to bring up the Lava proxy service
# on the current host and make it accessible to the local network.
# ------------------------------------------------------------------------------

CONTAINERS_BOOT="/tmp/Containers/bin/boot"
SERVICE_NAME="lava-proxy"
PORT="8080"

echo "========================================"
echo "  Lava Container Boot"
echo "========================================"

# Detect container runtime (Docker or Podman)
RUNTIME=""
if command -v docker &> /dev/null; then
    RUNTIME="docker"
elif command -v podman &> /dev/null; then
    RUNTIME="podman"
else
    echo "Error: Neither Docker nor Podman found. Please install one."
    exit 1
fi
echo "  Runtime: $RUNTIME"

# Build proxy fat JAR if not present
if [ ! -f "proxy/build/libs/app.jar" ]; then
    echo "[1/3] Building proxy fat JAR..."
    ./gradlew :proxy:buildFatJar
else
    echo "[1/3] Proxy fat JAR already built."
fi

# Build container image
echo "[2/3] Building container image..."
$RUNTIME build -t digital.vasic.lava.api:latest ./proxy

# Start container using compose
echo "[3/3] Starting container..."
$RUNTIME compose up -d --build

# Wait for service to be ready
echo "  Waiting for service to be ready..."
for i in {1..30}; do
    if curl -fsS http://localhost:$PORT/ > /dev/null 2>&1; then
        echo "  Service is healthy!"
        break
    fi
    sleep 1
done

# Display local network info
LOCAL_IP=$(hostname -I | awk '{print $1}')
echo ""
echo "========================================"
echo "  Lava Proxy is running"
echo "========================================"
echo "  Local:   http://localhost:$PORT"
echo "  Network: http://$LOCAL_IP:$PORT"
echo "========================================"
echo ""
echo "To stop: $RUNTIME compose down"
