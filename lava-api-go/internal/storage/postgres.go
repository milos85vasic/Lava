package storage

import (
	"context"
	"time"

	pgcache "digital.vasic.cache/pkg/postgres"
	"digital.vasic.lava.apigo/internal/cache"
)

// postgresStorage adapts the existing Postgres-backed *cache.Client to the
// Storage interface with ZERO behavior change. Get/Set/Invalidate delegate
// straight through to the same *cache.Client the server has always used;
// Close tears down the underlying pgcache pool.
//
// This is intentionally a thin wrapper: the Postgres backend's behavior is
// owned by internal/cache + submodules/cache/pkg/postgres and must remain
// byte-identical for existing deployments.
type postgresStorage struct {
	c     *cache.Client
	inner *pgcache.Client // retained solely for Close()
}

// Compile-time assertion that postgresStorage satisfies Storage.
var _ Storage = (*postgresStorage)(nil)

// NewPostgres wraps an existing pgcache.Client (the same client main.go
// constructs) in a Storage. The caller retains ownership of inner's
// lifecycle start (pgClient.Start()); Close() here closes the pool.
func NewPostgres(inner *pgcache.Client) Storage {
	return &postgresStorage{c: cache.New(inner), inner: inner}
}

func (p *postgresStorage) Get(ctx context.Context, key string) ([]byte, cache.Outcome, error) {
	return p.c.Get(ctx, key)
}

func (p *postgresStorage) Set(ctx context.Context, key string, value []byte, ttl time.Duration) error {
	return p.c.Set(ctx, key, value, ttl)
}

func (p *postgresStorage) Invalidate(ctx context.Context, key string) error {
	return p.c.Invalidate(ctx, key)
}

func (p *postgresStorage) Close() error {
	return p.inner.Close()
}
