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

# Strip ,omitempty from generated JSON struct tags so nullable pointer fields
# emit `null` instead of being omitted — matching the legacy Ktor proxy's
# kotlinx-serialization wire shape (Phase 14 cross-backend parity gate).
# Caught: GET /forum / /forum/{id} response divergence on CategoryDto.{Id,
# Children} (Ktor "children":null vs Go missing field). Every nullable field
# in api/openapi.yaml maps to a *T pointer in generated code, so the global
# strip is safe; we have no non-nullable optional pointer fields. The 15
# `,omitempty` strings that remain inside the embedded-spec YAML literal
# (see `spec` const) are string content, not Go tags.
sed -i 's|,omitempty"`|"`|g' internal/gen/server/api.gen.go internal/gen/client/api.gen.go

go fmt ./internal/gen/... >/dev/null

echo "[generate] OK"
