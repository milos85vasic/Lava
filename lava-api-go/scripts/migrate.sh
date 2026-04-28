#!/usr/bin/env bash
#
# scripts/migrate.sh — apply or roll back golang-migrate migrations against
# the Postgres database identified by LAVA_API_PG_URL.
#
# Usage:
#   scripts/migrate.sh up              # apply all pending
#   scripts/migrate.sh down 1          # roll back N steps
#   scripts/migrate.sh version         # print current version
#   scripts/migrate.sh force VERSION   # force-set version after manual repair

set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

: "${LAVA_API_PG_URL:?must be set, e.g. postgres://lava:pwd@127.0.0.1:5432/lava_api?sslmode=disable}"

go run -tags 'postgres' github.com/golang-migrate/migrate/v4/cmd/migrate \
  -path migrations \
  -database "$LAVA_API_PG_URL" \
  "$@"
