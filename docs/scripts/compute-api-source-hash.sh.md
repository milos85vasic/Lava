# `scripts/compute-api-source-hash.sh`

The **single source of truth** for "what `lava-api-go` source codebase does the
on-device API embed (`liblavaapi.so`) contain?".

## What it does

Emits a bare 64-hex `sha256` on stdout (no label) over the EXACT set of Go
source files that transitively compile into the c-shared `.so`:

- every non-test `.go` under `lava-api-go/cmd/lavaapi-cshared/`
- every non-test `.go` under `lava-api-go/internal/`
- `lava-api-go/go.mod`
- `lava-api-go/go.sum`

`*_test.go` files are excluded (they do not link into the `.so`). The other
`cmd/` entrypoints (`lava-api-go`, `healthprobe`, `lava-migrate`) are excluded
(not linked into the embed).

## Determinism contract

1. File list sorted with `LC_ALL=C sort` (byte order, locale-stable).
2. Per-file digest = `sha256("<module-relative-path>\0" + <file bytes>)`, so a
   rename, move, or content edit each change the digest.
3. Final digest = `sha256(stream of ordered per-file digests)`, so reordering is
   impossible and the value reproduces across runs / hosts / shells / checkout
   locations.

## Usage

```bash
scripts/compute-api-source-hash.sh        # prints the 64-hex hash
```

Exit 0 on success; exit 2 if `lava-api-go` or its source set is missing.

## Who calls it

- `lava-api-go/scripts/build-cshared.sh` — injects the hash into
  `internal/version.SourceHash` via `-ldflags -X` and writes the committed
  manifest `core/apiengine/src/main/resources/api-source.hash`.
- `scripts/check-api-app-sync.sh` — the CI gate; recomputes + compares to the
  manifest.
- `app/build.gradle.kts` + `api-app/build.gradle.kts` — bake the hash into
  `BuildConfig.LAVA_API_SOURCE_HASH` at config time.

Because all four call the SAME function, there is exactly one definition of "the
embed's source hash"; drift cannot be hidden. See `docs/ON_DEVICE_API.md` §5A.

## Constitutional notes

§6.R (no hardcoded values — only repo-relative source paths). §6.T.2 / §11.4.67
(bounded, read-only, no host mutation). `bash -n` clean.
