# lava-api-go

Go (Gin Gonic) implementation of the Lava API service. Replaces the legacy Kotlin/Ktor `:proxy` module's wire surface byte-for-byte while adding HTTP/3 (QUIC) transport, Brotli compression, PostgreSQL-backed response caching, request audit, and per-IP/per-route rate limiting.

The legacy Ktor proxy remains runnable as an opt-in fallback. The Android client (post-SP-3) speaks both APIs.

## Quick start

```bash
# Build
make build

# Run integration tests (requires podman or docker)
make test

# Full local CI gate
scripts/ci.sh

# Bring the service up via the orchestrator
cd ..
./start.sh                          # default: api-go + postgres
./start.sh --with-observability     # + Prometheus + Loki + Grafana + Tempo
```

## Module path

`digital.vasic.lava.apigo` — distinct from the Kotlin proxy's `digital.vasic.lava.api` group.

## Wire contract

- HTTP/3 (QUIC) on UDP port 8443, with HTTP/2 fallback over TCP/8443.
- Brotli compression for clients sending `Accept-Encoding: br`.
- mDNS service-type `_lava-api._tcp` on port 8443 (vs the legacy `_lava._tcp` on 8080).
- TXT records: `engine=go`, `version=2.0.0`, `protocols=h3,h2`, `compression=br,gzip`, `tls=required`, `path=/`.
- 13 routes inherited from the legacy proxy (see `api/openapi.yaml`).

## Constitution

See `CONSTITUTION.md` and the root project's `/CLAUDE.md`. The Sixth Law is binding: every test must be provably falsifiable, every PR records a falsifiability rehearsal in its commit body, the cross-backend parity suite gates releases.
