// Package storage is the backend-agnostic persistence boundary for the
// lava-api-go response cache. It declares the EXACT operations the server's
// handlers depend on today (enumerated from the cache consumers in
// internal/handlers/*) and nothing more — so a Postgres-backed and a
// SQLite-backed implementation are interchangeable behind one interface.
//
// The boundary is additive: the existing *cache.Client (Postgres) is wrapped
// unchanged by the postgres implementation; a new pure-Go SQLite
// implementation provides the same observable behavior for single-node /
// embedded deployments. Selection is by config.StorageBackend.
package storage

import (
	"context"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
)

// Backend identifies a storage implementation. Mirrors config's backend
// identifiers so callers can switch on a single string.
type Backend string

const (
	BackendPostgres Backend = "postgres"
	BackendSQLite   Backend = "sqlite"
)

// Storage is the set of cache operations the handlers depend on. Enumerated
// from internal/handlers (handlers.Cache + v1.Cache):
//
//	Get(ctx, key) ([]byte, cache.Outcome, error) — hit/miss/bypass lookup
//	Set(ctx, key, value, ttl) error              — store with TTL (ttl<=0 = no expiry)
//	Invalidate(ctx, key) error                   — delete a key
//
// Plus Close() error for lifecycle teardown at the composition root
// (main.go defers the underlying client's Close today).
//
// The Get outcome reuses cache.Outcome so the existing handler-layer
// interfaces (handlers.Cache, v1.Cache) are satisfied by a Storage without
// any handler edits — see the var _ assertions in postgres.go / sqlite.go.
type Storage interface {
	Get(ctx context.Context, key string) ([]byte, cache.Outcome, error)
	Set(ctx context.Context, key string, value []byte, ttl time.Duration) error
	Invalidate(ctx context.Context, key string) error
	Close() error
}
