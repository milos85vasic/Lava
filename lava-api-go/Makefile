.PHONY: all build test ci vet lint cover generate migrate-up migrate-down image clean

all: build test

build:
	go build -o bin/lava-api-go ./cmd/lava-api-go
	go build -o bin/healthprobe ./cmd/healthprobe

test:
	GOMAXPROCS=2 go test -race -count=1 ./...

# vet — go's built-in correctness analyzer (a strict subset of golangci's govet).
vet:
	GOMAXPROCS=2 go vet ./...

# lint — golangci-lint via the curated .golangci.yml. Runs containerized
# (rootless podman/docker, no auth/token) with a host-binary fallback; see
# scripts/golangci-lint.sh. Exit 3 means the tool is unavailable on this host.
lint:
	./scripts/golangci-lint.sh

# cover — unit+contract+e2e+parity coverage rollup (no external deps).
# Real-Postgres integration tests are gated behind -Pintegration/podman and are
# NOT part of this default rollup. Prints the overall total on the last line.
cover:
	GOMAXPROCS=2 go test ./... -coverprofile=coverage.out -covermode=atomic
	go tool cover -func=coverage.out | tail -1

ci:
	./scripts/ci.sh

generate:
	./scripts/generate.sh

migrate-up:
	./scripts/migrate.sh up

migrate-down:
	./scripts/migrate.sh down

image:
	docker build -f docker/Dockerfile -t lava-api-go:dev .

clean:
	rm -rf bin/ coverage.* *.out
