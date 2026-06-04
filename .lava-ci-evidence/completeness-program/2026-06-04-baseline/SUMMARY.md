# Phase 0 Baseline Summary — 2026-06-04

Branch: completeness-program-2026-06-04 (from master)

## Kotlin unit tests (testDebugUnitTest)
BUILD FAILED in 3m 48s
GRADLE_EXIT=1

## Go tests (go test ./...) — 2 PRE-EXISTING failures found (NOT introduced this session):
1. tests/contract TestAuthFieldName_NoLiteralInProductionGoSource — §6.R literal in mobile.go:110 — FIXED in f83b5bc6
2. internal/qa/detector TestCheckGoProcessByPID_Alive — env-dependent kill -0 liveness test — STILL OPEN (pre-existing, unrelated)

All other Go packages: PASS.
