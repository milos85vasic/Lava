package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"

	_ "modernc.org/sqlite" // pure-Go SQLite driver; registers driver name "sqlite"

	"digital.vasic.lava.apigo/internal/cache"
	sqlitemigrations "digital.vasic.lava.apigo/internal/migrations/sqlite"
)

// sqliteInitSchema is the embedded SQLite-dialect migration (response_cache
// table + expiry index), sourced from internal/migrations/sqlite/0001_init.sql.
var sqliteInitSchema = sqlitemigrations.Init

// sqliteStorage is the pure-Go (modernc.org/sqlite) Storage implementation for
// single-node / embedded deployments where running Postgres is overkill. It
// provides behavior parity with postgresStorage: the same hit/miss/expiry
// semantics, verified by the shared RunStorageConformance harness.
//
// TTL is encoded as an absolute expires_at column (unix NANOSECONDS); expired
// rows are filtered on read (lazy expiry), mirroring the Postgres backend's
// `expires_at IS NULL OR expires_at > NOW()`.
type sqliteStorage struct {
	db *sql.DB
}

// Compile-time assertion that sqliteStorage satisfies Storage.
var _ Storage = (*sqliteStorage)(nil)

// NewSQLite opens (or creates) the SQLite database at path, runs the
// embedded migration, and returns a ready Storage. The caller owns Close().
//
// path is the LAVA_API_SQLITE_PATH config value (e.g. "/data/lava-api.db");
// ":memory:" is accepted for ephemeral use. Per §6.R the path is never
// hardcoded — it flows from config.SQLitePath.
func NewSQLite(path string) (Storage, error) {
	if path == "" {
		return nil, errors.New("storage: sqlite path is empty")
	}
	// Pragmas: WAL for concurrent readers + busy_timeout to ride out brief
	// write locks rather than erroring immediately. Appended via the DSN.
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("storage: sqlite open %q: %w", path, err)
	}
	if _, err := db.ExecContext(context.Background(), sqliteInitSchema); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("storage: sqlite migrate: %w", err)
	}
	return &sqliteStorage{db: db}, nil
}

// Get returns (value, OutcomeHit, nil) for a live entry, (nil, OutcomeMiss,
// nil) for a missing OR expired entry (lazy expiry), and (nil, OutcomeBypass,
// err) on a real DB error so callers can fall through to upstream — mirroring
// cache.Client.Get's contract exactly.
func (s *sqliteStorage) Get(ctx context.Context, key string) ([]byte, cache.Outcome, error) {
	now := time.Now().UnixNano()
	var value []byte
	err := s.db.QueryRowContext(ctx,
		`SELECT value FROM response_cache
		 WHERE cache_key = ? AND (expires_at IS NULL OR expires_at > ?)`,
		key, now,
	).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, cache.OutcomeMiss, nil
	}
	if err != nil {
		return nil, cache.OutcomeBypass, err
	}
	return value, cache.OutcomeHit, nil
}

// Set stores value under key. ttl > 0 sets an absolute expiry; ttl <= 0 stores
// a non-expiring entry (expires_at NULL), matching cache.Client/pgcache.
func (s *sqliteStorage) Set(ctx context.Context, key string, value []byte, ttl time.Duration) error {
	var expires interface{} // nil → SQL NULL
	if ttl > 0 {
		expires = time.Now().Add(ttl).UnixNano()
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO response_cache (cache_key, value, expires_at)
		 VALUES (?, ?, ?)
		 ON CONFLICT(cache_key) DO UPDATE SET
		     value = excluded.value,
		     expires_at = excluded.expires_at`,
		key, value, expires,
	)
	if err != nil {
		return fmt.Errorf("storage: sqlite set: %w", err)
	}
	return nil
}

// Invalidate removes a key.
func (s *sqliteStorage) Invalidate(ctx context.Context, key string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM response_cache WHERE cache_key = ?`, key)
	if err != nil {
		return fmt.Errorf("storage: sqlite invalidate: %w", err)
	}
	return nil
}

// Close closes the underlying database handle.
func (s *sqliteStorage) Close() error {
	return s.db.Close()
}
