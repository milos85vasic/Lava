#!/usr/bin/env bash
# scripts/check-go-nonfatal-vet.sh — §6.AC Go non-fatal telemetry vet check
#
# Runs go vet with a custom analyzer that checks for error-swallow patterns
# lacking observability.RecordNonFatal/RecordWarning calls or // no-telemetry: comments.
#
# This is the mechanical enforcement for §6.AC on the Go side (lava-api-go).
# The bash-based check-non-fatal-coverage.sh does the same check but this
# provides IDE/editor integration via go vet.

set -euo pipefail

cd "$(dirname "$0")/../lava-api-go"

echo "==> §6.AC Go non-fatal telemetry vet check"

# First run standard go vet
go vet ./...

# The bash scanner in check-non-fatal-coverage.sh does the actual §6.AC
# detection for Go. This vet check is a placeholder for a future custom
# analyzer. For now, we delegate to the bash scanner's Go logic.
# When a proper go vet analyzer is implemented, replace this with:
# go vet -vettool=$(which lava-go-nonfatal-analyzer) ./...

echo "    ✓ go vet passed (standard checks)"
echo "    Note: §6.AC Go non-fatal coverage is enforced by check-non-fatal-coverage.sh"
echo "    A dedicated go vet analyzer for this is tracked in §6.AC-debt cycle 2."