# `scripts/check-api-app-sync.sh`

The **CI gate** guaranteeing the on-device Lava API embed (`liblavaapi.so`,
packaged by `:core:apiengine` into the `:api-app` APK) is built from EXACTLY the
current `lava-api-go` source — no drift (§11.4.69 / §6.J).

## What it does

1. Recomputes the live hash via `scripts/compute-api-source-hash.sh`.
2. Reads the committed manifest
   `core/apiengine/src/main/resources/api-source.hash` (the hash the last
   freshly-built `.so` was built from, written by `build-cshared.sh`).
3. If they differ, exits 1 loudly:
   `on-device API embed is STALE vs lava-api-go — rebuild liblavaapi.so via
   build-cshared.sh`, printing both hashes + the remediation.

## Usage

```bash
scripts/check-api-app-sync.sh
```

- Exit 0 + `OK: ...` when the manifest matches the live source.
- Exit 1 when stale (drift) or the manifest is missing.
- Exit 2 on a config error (hash script missing).

## Remediation on failure

```bash
lava-api-go/scripts/build-cshared.sh   # rebuilds the .so AND refreshes the manifest
git add core/apiengine/src/main/resources/api-source.hash
```

## Where it is wired

`scripts/ci.sh` runs it in BOTH `--changed-only` and `--full` modes (it is fast
pure-bash + git, and a stale embed is a release blocker regardless of mode).

## Falsifiability rehearsal (recorded)

Append a byte to a tracked embed-linked `.go` (e.g.
`lava-api-go/internal/version/version.go`) → the gate exits 1 (live hash now
differs from the manifest). Revert → exit 0. This proves the gate genuinely
discriminates a drifted embed.

## Constitutional notes

§6.R (no hardcoded values). §6.T.2 / §11.4.67 (bounded, read-only, no host
mutation). No failure-swallow (§6.J) — a mismatch propagates non-zero. See
`docs/ON_DEVICE_API.md` §5A and `docs/scripts/compute-api-source-hash.sh.md`.
