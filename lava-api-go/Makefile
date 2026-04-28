.PHONY: all build test ci generate migrate-up migrate-down image clean

all: build test

build:
	go build -o bin/lava-api-go ./cmd/lava-api-go
	go build -o bin/healthprobe ./cmd/healthprobe

test:
	go test -race -count=1 ./...

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
