# syntax=docker/dockerfile:1.7
#
# Multi-stage Dockerfile for lava-api-go.
#
# Build context (per docker-compose later wired in Phase 11) is the
# project root; this Dockerfile therefore references files via
# /src/lava-api-go/... — matching the SP-2 plan §9 / Task 9.2 sketch.
#
# Stages:
#   build    — compiles the lava-api-go and healthprobe binaries
#              with CGO_ENABLED=0 so they are statically linked and
#              compatible with the distroless/static runtime base.
#   migrate  — installs golang-migrate for the one-shot lava-migrate
#              compose service that applies migrations/ before the
#              API container starts.
#   runtime  — distroless/static-debian12:nonroot. Exposes 8443/udp
#              (HTTP/3 / QUIC) and 8443/tcp (HTTP/2 fallback). The
#              HEALTHCHECK CMD invokes the healthprobe binary (also
#              shipped in this image) — it MUST be the JSON-array
#              form so distroless (which has no /bin/sh) can exec it
#              directly.

# --- build stage ------------------------------------------------------
FROM golang:1.25-alpine AS build
ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64
WORKDIR /src
COPY . .
RUN cd /src/lava-api-go && \
    go build -trimpath -ldflags='-s -w' -o /out/lava-api-go ./cmd/lava-api-go && \
    go build -trimpath -ldflags='-s -w' -o /out/healthprobe ./cmd/healthprobe

# --- migrate stage (one-shot lava-migrate compose service) ------------
FROM golang:1.25-alpine AS migrate
ENV CGO_ENABLED=0 \
    GOOS=linux \
    GOARCH=amd64
WORKDIR /src
COPY . .
RUN cd /src/lava-api-go && \
    go install -tags 'postgres' github.com/golang-migrate/migrate/v4/cmd/migrate@latest
ENTRYPOINT ["/go/bin/migrate"]
CMD ["-path", "/src/lava-api-go/migrations", "-database", "$LAVA_API_PG_URL", "up"]

# --- runtime stage ----------------------------------------------------
FROM gcr.io/distroless/static-debian12:nonroot AS runtime
COPY --from=build /out/lava-api-go /usr/local/bin/lava-api-go
COPY --from=build /out/healthprobe /usr/local/bin/healthprobe
EXPOSE 8443/udp 8443/tcp
HEALTHCHECK --interval=10s --retries=6 CMD ["/usr/local/bin/healthprobe"]
ENTRYPOINT ["/usr/local/bin/lava-api-go"]
