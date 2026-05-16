# tools/lava-containers

Lava-domain CLI that orchestrates the legacy Ktor proxy container's lifecycle.

This module is **Lava-specific** by design — it knows about `:proxy:buildFatJar`,
the `digital.vasic.lava.api` image tag, the `ADVERTISE_HOST` env var the JmDNS
side expects, and the `_lava._tcp` mDNS service the Android client looks for.

It is the local "thin glue" called for in the Decoupled Reusable Architecture
constitutional rule (`/CLAUDE.md`). Generic container-runtime concerns
(autodetect Docker/Podman, compose lifecycle, network/IP scanning, service
registry) live in the **upstream Containers submodule** mounted at
`/submodules/containers/`; this CLI will be rewired to delegate to that
upstream as part of **SP-2** (Go API migration).

## Build

```bash
cd tools/lava-containers
go build -o bin/lava-containers ./cmd/lava-containers
```

`./start.sh` and `./stop.sh` at the repo root build this CLI on demand and
invoke it.

## Commands

| Command | Description |
|---------|-------------|
| `start` | Build JAR + image, start container, wait for health |
| `stop`  | Stop and remove the container |
| `status`| Show runtime, health, IPs |
| `logs`  | Stream container logs (`-f` to follow) |
| `build` | Build JAR and image without starting |

## Where things live

- `cmd/lava-containers/main.go` — CLI entry point, command dispatch
- `internal/proxy/proxy.go` — Lava-Ktor-proxy lifecycle manager (Lava-domain)
- `internal/runtime/runtime.go` — Docker/Podman autodetect (will be replaced
  by `digital.vasic.containers/pkg/runtime` from `/submodules/containers/` in
  SP-2)
