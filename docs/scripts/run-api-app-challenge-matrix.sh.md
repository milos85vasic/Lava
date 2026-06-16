# run-api-app-challenge-matrix.sh

§6.AE / §6.X / §6.AG gate entry point for the `:api-app` module's Compose UI
Challenge tests (Challenge01..Challenge04 under `lava.api.app.challenges`).

Script: `scripts/run-api-app-challenge-matrix.sh`

## What it does

The `:api-app` sibling of `scripts/run-challenge-matrix.sh`. THIN GLUE: it
delegates emulator boot + lifecycle to the Containers submodule's
`cmd/emulator-matrix` CLI (per §6.X / §6.AG — the device MUST come from a
Containers-orchestrated cold-booted emulator AVD, NEVER a live/physical ADB
device). It forwards `--runner=auto` (Linux→containerized via `/dev/kvm`,
macOS→host-direct+HVF), the `:api-app` debug APK, the Challenge test class, the
AVD matrix, and the evidence dir; the CLI's exit code is the gate's source of
truth.

## Usage

```
./scripts/run-api-app-challenge-matrix.sh [--test-class lava.api.app.challenges.ChallengeNN] \
    [--avds "name:api:form,..."] [--evidence-dir <dir>] [--no-build]
```

## Constitutional bindings

§6.AE (per-app Challenge matrix), §6.X (container-submodule emulator wiring),
§6.AG (Containers-driven, no live device), §6.AH (VM/container, never
host-direct), §6.I (per-AVD attestation).
