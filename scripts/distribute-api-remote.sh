#!/usr/bin/env bash
# scripts/distribute-api-remote.sh — distribute lava-api-go to a remote host.
#
# Usage:
#   ./scripts/distribute-api-remote.sh                     # default: thinker.local
#   ./scripts/distribute-api-remote.sh another-host.local
#
# The remote host MUST:
#   - Be reachable via SSH at $LAVA_REMOTE_HOST_USER@$REMOTE_HOST
#   - Have passwordless SSH (key-based auth) configured
#   - Have rootless Podman 4.x or later installed
#
# What it does:
#   1. Loads .env (LAVA_REMOTE_HOST_USER, optional overrides).
#   2. Verifies SSH connectivity to the target host.
#   3. Builds the lava-api-go OCI image if releases/<version>/api-go/
#      lava-api-go-<v>.image.tar is missing.
#   4. Copies the OCI image tarball + thinker.local.env + thinker-up.sh
#      + TLS certs to the remote host.
#   5. Runs deployment/thinker/thinker-up.sh on the remote.
#   6. Verifies https://$REMOTE_HOST:$PORT/health responds.
#
# Inverse: scripts/distribute-api-remote.sh --tear-down <host> stops + removes
# the remote containers + image (used at the end of this session to free
# the local host of all API containers + images).
#
# Constitutional bindings:
#   §6.H — credentials never logged; .env is gitignored.
#   §6.J — set -euo pipefail; no `|| echo WARN` swallow.
#   §6.B — health check is a real HTTP probe, not just `podman ps`.
#   §6.M — no host power-management commands; rootless Podman.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LAVA_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$LAVA_REPO_ROOT"

# Load .env for LAVA_REMOTE_HOST_USER + optional API_GO_REMOTE_HOST overrides.
if [[ -f "$LAVA_REPO_ROOT/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source <(grep -E '^(LAVA_API_GO_REMOTE_HOST|LAVA_REMOTE_HOST_USER)=' "$LAVA_REPO_ROOT/.env" || true)
    set +a
fi

ACTION="distribute"
REMOTE_HOST="${LAVA_API_GO_REMOTE_HOST:-thinker.local}"
REMOTE_USER="${LAVA_REMOTE_HOST_USER:-milosvasic}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tear-down|--teardown) ACTION="teardown"; shift ;;
        --user) REMOTE_USER="$2"; shift 2 ;;
        --) shift; break ;;
        -*) echo "Unknown option: $1" >&2; exit 1 ;;
        *) REMOTE_HOST="$1"; shift ;;
    esac
done

REMOTE="$REMOTE_USER@$REMOTE_HOST"

# ----------------------------------------------------------------
# 1. Verify SSH
# ----------------------------------------------------------------
echo "==> Verifying SSH to $REMOTE"
if ! ssh -o BatchMode=yes -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new \
    "$REMOTE" 'true' 2>/dev/null; then
    echo "FATAL: cannot SSH to $REMOTE (passwordless / key-based auth required)" >&2
    exit 1
fi
echo "    $REMOTE reachable."

if ! ssh "$REMOTE" 'command -v podman >/dev/null 2>&1' 2>/dev/null; then
    echo "FATAL: podman not found on $REMOTE" >&2
    exit 1
fi
echo "    podman present on $REMOTE."

# ----------------------------------------------------------------
# 2. Tear-down path (used at end-of-session to clean local host).
# ----------------------------------------------------------------
if [[ "$ACTION" == "teardown" ]]; then
    echo "==> Tearing down lava-* containers + image on $REMOTE"
    ssh "$REMOTE" bash <<'TEARDOWN'
set -euo pipefail
for c in lava-api-go-thinker lava-postgres-thinker; do
    if podman container exists "$c" 2>/dev/null; then
        podman rm -f "$c" >/dev/null
        echo "    removed $c"
    fi
done
for img in localhost/lava-api-go:thinker localhost/lava-api-go:dev; do
    if podman image exists "$img" 2>/dev/null; then
        podman rmi -f "$img" >/dev/null
        echo "    removed $img"
    fi
done
if podman network exists lava-thinker 2>/dev/null; then
    podman network rm lava-thinker >/dev/null 2>&1 || true
fi
echo "==> Teardown complete."
TEARDOWN
    exit 0
fi

# ----------------------------------------------------------------
# 3. Resolve the API version + image tarball.
# ----------------------------------------------------------------
APIGO_VERSION="$(grep -E '^[[:space:]]*Name *= *"[^"]+"' lava-api-go/internal/version/version.go \
    | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
APP_VERSION="$(grep -E '^\s+versionName\s*=' app/build.gradle.kts \
    | head -1 | sed 's/.*"\([^"]*\)".*/\1/')"

if [[ -z "$APIGO_VERSION" ]]; then
    echo "FATAL: could not parse lava-api-go version from internal/version/version.go" >&2
    exit 1
fi

IMAGE_TAR="releases/${APP_VERSION}/api-go/lava-api-go-${APIGO_VERSION}.image.tar"
if [[ ! -f "$IMAGE_TAR" ]]; then
    echo "==> Image tarball missing: $IMAGE_TAR"
    echo "    Building via build_and_release.sh"
    "$LAVA_REPO_ROOT/build_and_release.sh"
fi
if [[ ! -f "$IMAGE_TAR" ]]; then
    echo "FATAL: image tarball still missing after build_and_release.sh: $IMAGE_TAR" >&2
    exit 1
fi
echo "==> Image tarball: $IMAGE_TAR ($(du -h "$IMAGE_TAR" | awk '{print $1}'))"

# ----------------------------------------------------------------
# 4. Resolve env file + remote dir.
# ----------------------------------------------------------------
LOCAL_ENV="$LAVA_REPO_ROOT/deployment/thinker/thinker.local.env"
LOCAL_BOOT_SH="$LAVA_REPO_ROOT/deployment/thinker/thinker-up.sh"
if [[ ! -f "$LOCAL_ENV" || ! -f "$LOCAL_BOOT_SH" ]]; then
    echo "FATAL: deployment/thinker/{thinker.local.env, thinker-up.sh} missing" >&2
    exit 1
fi

REMOTE_DIR="$(grep -E '^LAVA_THINKER_REMOTE_DIR=' "$LOCAL_ENV" \
    | head -1 | cut -d= -f2- | sed "s|\$HOME|/home/$REMOTE_USER|")"
REMOTE_DIR="${REMOTE_DIR:-/home/$REMOTE_USER/lava}"
REMOTE_TAR="$(grep -E '^LAVA_THINKER_IMAGE_TAR=' "$LOCAL_ENV" \
    | head -1 | cut -d= -f2-)"
REMOTE_TAR="${REMOTE_TAR:-/tmp/lava-api-go-thinker.image.tar}"

# ----------------------------------------------------------------
# 5. Prepare TLS material on local + ship.
# ----------------------------------------------------------------
TLS_DIR_LOCAL="$LAVA_REPO_ROOT/lava-api-go/docker/tls"
if [[ ! -f "$TLS_DIR_LOCAL/server.crt" || ! -f "$TLS_DIR_LOCAL/server.key" ]]; then
    echo "FATAL: TLS material missing under $TLS_DIR_LOCAL — run lava-api-go/scripts/gen-cert.sh" >&2
    exit 1
fi

# ----------------------------------------------------------------
# 6. Copy artifacts to remote.
# ----------------------------------------------------------------
echo "==> Provisioning $REMOTE:$REMOTE_DIR"
ssh "$REMOTE" "mkdir -p '$REMOTE_DIR/tls'"

echo "==> scp env + boot script + TLS to $REMOTE"
scp -q "$LOCAL_ENV" "$REMOTE:$REMOTE_DIR/thinker.local.env"
scp -q "$LOCAL_BOOT_SH" "$REMOTE:$REMOTE_DIR/thinker-up.sh"
scp -q "$TLS_DIR_LOCAL/server.crt" "$REMOTE:$REMOTE_DIR/tls/server.crt"
scp -q "$TLS_DIR_LOCAL/server.key" "$REMOTE:$REMOTE_DIR/tls/server.key"
ssh "$REMOTE" "chmod +x '$REMOTE_DIR/thinker-up.sh' && chmod 644 '$REMOTE_DIR/tls/server.key'"

echo "==> scp image tarball to $REMOTE:$REMOTE_TAR ($(du -h "$IMAGE_TAR" | awk '{print $1}'))"
scp -q "$IMAGE_TAR" "$REMOTE:$REMOTE_TAR"

# ----------------------------------------------------------------
# 7. Boot.
# ----------------------------------------------------------------
echo "==> Running thinker-up.sh on $REMOTE"
ssh "$REMOTE" "LAVA_THINKER_ENV_FILE='$REMOTE_DIR/thinker.local.env' bash '$REMOTE_DIR/thinker-up.sh'"

# ----------------------------------------------------------------
# 8. End-to-end verification from the local host (where Android testers run).
# ----------------------------------------------------------------
PORT="$(grep -E '^LAVA_THINKER_API_PORT=' "$LOCAL_ENV" | head -1 | cut -d= -f2-)"
PORT="${PORT:-8443}"
echo "==> Verifying https://$REMOTE_HOST:$PORT/health from local host"
for i in $(seq 1 30); do
    if curl -fsSk "https://$REMOTE_HOST:$PORT/health" >/dev/null 2>&1; then
        echo "    /health alive."
        break
    fi
    if [[ $i -eq 30 ]]; then
        echo "FATAL: /health on $REMOTE_HOST:$PORT did not respond after 30s" >&2
        ssh "$REMOTE" "podman logs --tail 60 lava-api-go-thinker" >&2 || true
        exit 1
    fi
    sleep 1
done

echo ""
echo "============================================================"
echo "  Lava API distributed and booted on $REMOTE_HOST"
echo "============================================================"
echo "  Image:    localhost/lava-api-go:thinker (api-go $APIGO_VERSION)"
echo "  API URL:  https://$REMOTE_HOST:$PORT/health"
echo "  Console:  ssh $REMOTE 'podman ps --filter name=lava-'"
echo "  Logs:     ssh $REMOTE 'podman logs -f lava-api-go-thinker'"
echo "  Tear-down: $0 --tear-down $REMOTE_HOST"
echo "============================================================"
