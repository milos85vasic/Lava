#!/usr/bin/env bash
# phase-01-build-lava-api-go.sh — Build step: lava-api-go binary (T019).
#
# Builds the lava-api-go standalone Go server binary by invoking this
# module's own, already-established `make build` target
# (lava-api-go/Makefile) — reuse, not reinvention, per this project's
# Decoupled Reusable Architecture / Local-Only CI/CD conventions: whatever a
# developer runs locally (`make build`) is exactly what this pipeline step
# runs too. No parallel `go build ...` invocation is introduced here.
#
# The Makefile's `build:` target (lava-api-go/Makefile:5-7) is:
#   build:
#       go build -o bin/lava-api-go ./cmd/lava-api-go
#       go build -o bin/healthprobe ./cmd/healthprobe
# It always builds both binaries together (there is no finer-grained target
# for just the service binary); this script builds both but reports only
# the primary `lava-api-go` service binary as this phase's Build Artifact,
# per data-model.md's `build_output_path` convention (`lava-api-go/bin/`).
#
# Usage:
#   scripts/pipeline/phase-01-build-lava-api-go.sh [repo-path]
#
# With no argument, resolves the Lava monorepo root via `git rev-parse
# --show-toplevel` (works from anywhere inside the real repo). An optional
# first argument overrides which repo root to use — the same convention
# phase-00-precondition.sh uses — so a hermetic test suite can point this at
# a disposable fixture directory instead of the real repository.
#
# Output (stdout):
#   `make build`'s own stdout/stderr (compiler output, etc.) is passed
#   through unmodified so a real failure is always visible, never swallowed.
#   On success, exactly one additional line is printed at the end:
#     ARTIFACT lava-api-go: <absolute-path-to-bin/lava-api-go>
#
# Exit codes:
#   0 - build succeeded; bin/lava-api-go exists, is non-empty, and is
#       executable.
#   1 - `make build` itself failed (real compile/toolchain/dependency
#       error) OR the Makefile's build target changed shape and no longer
#       produces bin/lava-api-go (contract drift) OR the produced file
#       failed the real-artifact sanity check (missing / zero-length /
#       not executable).
#   2 - usage/precondition error (repo path or lava-api-go/ subdir/Makefile
#       missing).
#
# This script writes NO JSON evidence records — evidence-writing (Build
# Artifact metadata into report.json) is a separate pipeline component
# (run-report.sh) that wraps this script's exit code + stdout. This
# script's own contract stays limited to "clean exit code + one clean
# ARTIFACT line on success, real propagated failure output otherwise", per
# the task split between build-step scripts and the evidence/report layer.

set -euo pipefail

REPO_PATH="${1:-}"

if [[ -z "$REPO_PATH" ]]; then
  REPO_PATH="$(git rev-parse --show-toplevel)"
fi

LAVA_API_GO_DIR="${REPO_PATH}/lava-api-go"

if [[ ! -d "$LAVA_API_GO_DIR" ]]; then
  echo "phase-01-build-lava-api-go: precondition failed — no lava-api-go/ directory under '${REPO_PATH}'" >&2
  exit 2
fi

if [[ ! -f "${LAVA_API_GO_DIR}/Makefile" ]]; then
  echo "phase-01-build-lava-api-go: precondition failed — lava-api-go/Makefile not found" >&2
  exit 2
fi

echo "phase-01-build-lava-api-go: building via 'make build' in ${LAVA_API_GO_DIR}"

# Reuse the module's own build target verbatim. Real failure output
# (compiler errors, missing deps, toolchain mismatch, etc.) is propagated
# unmodified — make's own non-zero exit + stderr ARE the failure signal;
# nothing here catches or papers over it.
if ! ( cd "$LAVA_API_GO_DIR" && make build ); then
  echo "phase-01-build-lava-api-go: FAILED — 'make build' exited non-zero (see output above for the real compiler/toolchain error)" >&2
  exit 1
fi

BINARY_PATH="${LAVA_API_GO_DIR}/bin/lava-api-go"

if [[ ! -f "$BINARY_PATH" ]]; then
  echo "phase-01-build-lava-api-go: FAILED — 'make build' exited 0 but ${BINARY_PATH} does not exist (Makefile build-target contract drift)" >&2
  exit 1
fi

if [[ ! -s "$BINARY_PATH" ]]; then
  echo "phase-01-build-lava-api-go: FAILED — ${BINARY_PATH} exists but is zero-length" >&2
  exit 1
fi

if [[ ! -x "$BINARY_PATH" ]]; then
  echo "phase-01-build-lava-api-go: FAILED — ${BINARY_PATH} exists but is not executable" >&2
  exit 1
fi

echo "ARTIFACT lava-api-go: ${BINARY_PATH}"
exit 0
