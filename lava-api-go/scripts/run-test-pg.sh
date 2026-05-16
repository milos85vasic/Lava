#!/usr/bin/env bash
#
# scripts/run-test-pg.sh — boot a transient Postgres container, run the
# lava-api-go integration tests against it, then tear it down. Modeled on
# submodules/cache/scripts/run-postgres-test.sh.
#
# Usage:
#   scripts/run-test-pg.sh                        # default (random port, image: postgres:16-alpine)
#   POSTGRES_TEST_URL=postgres://... scripts/run-test-pg.sh   # use existing DB, no podman launch

set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# If an external URL is supplied, just run the tests against it.
if [[ -n "${POSTGRES_TEST_URL:-}" ]]; then
  echo "[pg-test] using external POSTGRES_TEST_URL"
  exec go test -race -count=1 -v ./internal/cache/... ./tests/integration/...
fi

# Otherwise launch a transient container.
RUNTIME=""
for r in podman docker; do
  if command -v "$r" >/dev/null 2>&1; then RUNTIME="$r"; break; fi
done
[[ -n "$RUNTIME" ]] || { echo "[pg-test] no podman or docker found — set POSTGRES_TEST_URL to use an external server" >&2; exit 1; }

CONTAINER_NAME="lava-api-go-pg-test-$$"
PORT=$(( ( RANDOM % 20000 ) + 30000 ))
IMAGE="${POSTGRES_TEST_IMAGE:-docker.io/postgres:16-alpine}"
PASSWORD=$(openssl rand -hex 8 2>/dev/null || echo "ci_pwd_$$")
DB_NAME=lava_api_test
DB_USER=lava_api_test

cleanup() {
  echo "[pg-test] tearing down container $CONTAINER_NAME"
  $RUNTIME rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "[pg-test] runtime=$RUNTIME image=$IMAGE port=$PORT"
$RUNTIME run --rm -d \
  --name "$CONTAINER_NAME" \
  -p "$PORT:5432" \
  -e POSTGRES_PASSWORD="$PASSWORD" \
  -e POSTGRES_USER="$DB_USER" \
  -e POSTGRES_DB="$DB_NAME" \
  "$IMAGE" >/dev/null

# Wait for readiness.
echo "[pg-test] waiting for Postgres to accept connections"
for i in {1..40}; do
  if $RUNTIME exec "$CONTAINER_NAME" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; then
    break
  fi
  if (( i == 40 )); then
    echo "[pg-test] timed out waiting for Postgres" >&2
    $RUNTIME logs "$CONTAINER_NAME" >&2 || true
    exit 1
  fi
  sleep 0.5
done

export POSTGRES_TEST_URL="postgres://$DB_USER:$PASSWORD@127.0.0.1:$PORT/$DB_NAME?sslmode=disable"
echo "[pg-test] running integration tests"

# Build the package list dynamically so the script tolerates phases where
# ./internal/cache or ./tests/integration are not yet on disk. We always
# include any package that exists; an empty list short-circuits to a clean
# pass so the smoke harness in scripts/migrate.sh remains exercisable.
PKGS=()
[[ -d ./internal/cache ]] && PKGS+=("./internal/cache/...")
[[ -d ./tests/integration ]] && PKGS+=("./tests/integration/...")
if (( ${#PKGS[@]} == 0 )); then
  echo "[pg-test] no integration packages on disk yet; smoke run only"
  exit 0
fi
go test -race -count=1 -v "${PKGS[@]}"
