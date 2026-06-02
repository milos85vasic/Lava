package storage

import (
	"context"
	"fmt"
	"time"

	pgcache "digital.vasic.cache/pkg/postgres"
	"digital.vasic.lava.apigo/internal/config"
)

// ReadinessFunc is the /ready probe signature. It matches
// observability.ReadinessProbe structurally (func(context.Context) error) and
// is returned alongside the Storage so the composition root does not need to
// know which backend it got.
type ReadinessFunc func(ctx context.Context) error

// New constructs the configured Storage backend and its readiness probe,
// selecting by cfg.StorageBackend.
//
//   - postgres: connects to cfg.PGUrl (the exact pgcache.Config the server has
//     always used — SchemaName, TableName "response_cache", GCInterval 10m —
//     and calls Start()), returning a Postgres-backed Storage whose readiness
//     probe is pgClient.HealthCheck. Byte-identical to the prior main.go path.
//   - sqlite: opens cfg.SQLitePath (pure-Go modernc.org/sqlite, embedded
//     migration), returning a SQLite-backed Storage whose readiness probe is a
//     trivial round-trip Get of a sentinel key (proves the handle is live).
//
// The returned Storage's Close() MUST be deferred by the caller.
func New(cfg *config.Config) (Storage, ReadinessFunc, error) {
	switch cfg.StorageBackend {
	case config.BackendPostgres:
		pgClient, err := pgcache.ConnectFromURL(context.Background(), &pgcache.Config{
			URL:        cfg.PGUrl,
			SchemaName: cfg.PGSchema,
			TableName:  "response_cache",
			GCInterval: 10 * time.Minute,
		})
		if err != nil {
			return nil, nil, fmt.Errorf("storage: postgres connect: %w", err)
		}
		pgClient.Start()
		ready := func(ctx context.Context) error {
			if err := pgClient.HealthCheck(ctx); err != nil {
				return fmt.Errorf("postgres: %w", err)
			}
			return nil
		}
		return NewPostgres(pgClient), ready, nil

	case config.BackendSQLite:
		s, err := NewSQLite(cfg.SQLitePath)
		if err != nil {
			return nil, nil, err
		}
		ready := func(ctx context.Context) error {
			// A miss (or hit) on a probe key means the DB handle answers
			// queries — i.e. the backend is live. A real DB error surfaces
			// as OutcomeBypass + err.
			if _, _, err := s.Get(ctx, "__readiness_probe__"); err != nil {
				return fmt.Errorf("sqlite: %w", err)
			}
			return nil
		}
		return s, ready, nil

	default:
		return nil, nil, fmt.Errorf("storage: unknown backend %q", cfg.StorageBackend)
	}
}
