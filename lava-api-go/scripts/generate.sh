#!/usr/bin/env bash
#
# scripts/generate.sh — regenerate Go server interfaces and typed client from
# api/openapi.yaml using oapi-codegen v2. Per CONSTITUTION.md, the generated
# code is committed to git; ci.sh enforces "regenerate produces empty diff".
#
# oapi-codegen is pinned via the module's tool dependency in go.mod (added by
# `go get -tool github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen`),
# so `go run` resolves the same version on every machine.
#
# We invoke oapi-codegen twice (once per config) instead of relying on
# multi-document YAML, because oapi-codegen v2.6.0 only consumes the first
# document of a `---`-separated config file.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

go run github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen \
  -config api/codegen-server.yaml api/openapi.yaml

go run github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen \
  -config api/codegen-client.yaml api/openapi.yaml

go fmt ./internal/gen/... >/dev/null

echo "[generate] OK"
