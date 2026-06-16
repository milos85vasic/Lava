# nezha.local — Whole-System Boot Evidence (2026-06-16)

Distributed boot of the whole Lava System on the dedicated heavy-testing node
`nezha.local` (i7/8-core, 64 GB, NVMe, `/dev/kvm` live, rootless Podman 5.7.1 +
podman-compose 1.5.0), per the operator's 2026-06-16 directive. Built **natively
on nezha** (x86_64) from the synced repo at `~/Projects/Lava` (commit `94519eb0`);
`lava-api-go` = 17 Go submodules via `replace ../submodules/*`.

## Container inventory (real `podman ps`, 2026-06-16T16:08:17Z)

| Container | Port (host→ctr) | Status |
|---|---|---|
| lava-api-go-nezha (prod) | host-net :8443 | Up |
| lava-api-go-nezha-dev | host-net :8543 | Up |
| lava-postgres-nezha | 127.0.0.1:8432→5432 | Up |
| lava-postgres-nezha-dev | 127.0.0.1:8433→5432 | Up |
| lava-prometheus | 127.0.0.1:9190→9090 | Up |
| lava-loki | 127.0.0.1:3100 | Up |
| lava-tempo | 127.0.0.1:3200,4318 | Up |
| lava-grafana | 127.0.0.1:3000 | Up |
| lava-jackett | 127.0.0.1:9217→9117 | Up |
| lava-flaresolverr | 127.0.0.1:8191 | Up |

## Real HTTP verification (§6.B — not `podman ps`)

```
prod  /health    : {"status":"alive"}
prod  /ready     : {"status":"ready"}
prod  /providers : 13 providers
prod  version    : lava-api-go 2.3.30 (build 2330)
dev   /health    : {"status":"alive"}
prometheus :9190 : Prometheus Server is Healthy.
grafana    :3000 : {"database":"ok", ...}
```

Migrations applied to both prod + dev `lava_api` DBs (golang-migrate, version 9 head).

## Discovered issues — root-caused (evidence, not guess) + fixed, no bluff

1. **Incomplete submodule init.** `git submodule update --init --recursive` descended
   into helixqa's huge nested opensource tree, leaving 8 top-level Go submodules
   half-cloned (`.git` only, no working tree). Root cause: gitlink SHA matched so a
   later `update` skipped the working-tree checkout. Fix: `update --init` (no
   `--recursive`) + `--force` for the 2 stragglers. 16/17 populated; `tracker_sdk`
   is Kotlin (no root go.mod) and is never imported by Go — native build rc=0.

2. **api-go crash-loop: `exec ... No such file or directory`.** Root cause CONFIRMED:
   the binary was **dynamically linked** (`interpreter /lib64/ld-linux-x86-64.so.2`,
   needs `libc.so.6`) because the native build omitted `CGO_ENABLED=0`; distroless/
   static has no glibc/ld-linux. Fix: rebuild with `CGO_ENABLED=0` → `statically
   linked` (`file` confirms). Captured into `nezha-up.sh`.

3. **TLS `permission denied`.** `server.key` was `0600` owned by host `milosvasic`;
   the distroless `nonroot` (UID 65532) container user can't read it under rootless
   podman's userns mapping. Fix: `chmod 644` (regenerable self-signed test cert on a
   dedicated test host — not a §6.H production key).

4. **Co-tenant port collisions (shared host).** `:9090` held by
   `llmsverifier_prometheus_1`, `:9117` by `boba-jackett`. Fix: lava prometheus→9190,
   jackett→9217 (container-internal ports unchanged). Recorded in `nezha.local.env`.

## Reproduce

```
ssh nezha.local 'cd ~/Projects/Lava && bash deployment/nezha/nezha-up.sh'
```
Prereqs: `~/lava/nezha-secrets.env` (chmod 600, §6.H), repo synced, Go toolchain.
