package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite" // pure-Go SQLite driver; registers driver name "sqlite"

	"digital.vasic.lava.apigo/internal/cache"
	sqlitemigrations "digital.vasic.lava.apigo/internal/migrations/sqlite"
)

// sqliteInitSchema is the embedded SQLite-dialect migration (response_cache
// table + expiry index), sourced from internal/migrations/sqlite/0001_init.sql.
var sqliteInitSchema = sqlitemigrations.Init

// sqliteGCInterval is how often the background sweep reaps physically-expired
// rows. It mirrors the Postgres backend's pgcache GCInterval (10m, set in
// factory.New) so both backends bound their on-disk growth on the same cadence.
const sqliteGCInterval = 10 * time.Minute

// sqliteStorage is the pure-Go (modernc.org/sqlite) Storage implementation for
// single-node / embedded deployments where running Postgres is overkill. It
// provides behavior parity with postgresStorage: the same hit/miss/expiry
// semantics, verified by the shared RunStorageConformance harness.
//
// TTL is encoded as an absolute expires_at column (unix NANOSECONDS); expired
// rows are filtered on read (lazy expiry), mirroring the Postgres backend's
// `expires_at IS NULL OR expires_at > NOW()`. Because lazy expiry alone never
// reclaims rows that are written-then-never-re-read (and invalidated/overwritten
// keys would otherwise accumulate), a background GC goroutine periodically
// DELETEs physically-expired rows — the SQLite analogue of pgcache.PurgeExpired
// + its GC loop. See sweepExpired + the goroutine started in newSQLiteStorage.
type sqliteStorage struct {
	db *sql.DB

	gcCancel context.CancelFunc
	gcWG     sync.WaitGroup
	closeMu  sync.Mutex
	closed   bool
}

// Compile-time assertion that sqliteStorage satisfies Storage.
var _ Storage = (*sqliteStorage)(nil)

// NewSQLite opens (or creates) the SQLite database at path, runs the embedded
// migration, starts the background GC goroutine, and returns a ready Storage.
// The caller owns Close() (which stops the GC goroutine and closes the handle).
//
// path is the LAVA_API_SQLITE_PATH config value (e.g. "/data/lava-api.db");
// ":memory:" is accepted for ephemeral use. Per §6.R the path is never
// hardcoded — it flows from config.SQLitePath.
func NewSQLite(path string) (Storage, error) {
	return newSQLiteStorage(path)
}

// newSQLiteStorage is the concrete constructor (returns *sqliteStorage so tests
// can reach internal methods). NewSQLite wraps it to the Storage interface.
func newSQLiteStorage(path string) (*sqliteStorage, error) {
	if path == "" {
		return nil, errors.New("storage: sqlite path is empty")
	}
	onDisk := !isMemoryPath(path)

	// Pragmas applied via the DSN on every pooled connection:
	//   - journal_mode(WAL): concurrent readers don't block the single writer.
	//   - busy_timeout(5000): ride out brief write locks instead of erroring.
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("storage: sqlite open %q: %w", path, err)
	}

	// Finding 2: serialize all access through a single connection. For an
	// embedded on-disk SQLite writer this is the standard race-free choice —
	// modernc + database/sql is goroutine-safe, but a single connection avoids
	// SQLITE_BUSY contention between concurrent writers AND guarantees that
	// connection-scoped PRAGMAs (auto_vacuum, set below) apply to the one
	// connection every query actually uses. The pool would otherwise hand out
	// fresh connections on which the auto_vacuum exec never ran.
	db.SetMaxOpenConns(1)

	// Finding 1 (space reclamation): enable INCREMENTAL auto_vacuum so deleted
	// pages are tracked on the freelist and reused by subsequent writes,
	// bounding file growth. VERIFIED behavior of modernc.org/sqlite: the mode
	// only takes effect if `PRAGMA auto_vacuum=INCREMENTAL` is followed by a
	// `VACUUM` on the (still-empty) database BEFORE any table exists — the
	// VACUUM rewrites the database header with the new auto_vacuum flag. Running
	// the pragma alone, or after the table is created, leaves auto_vacuum=0. So
	// the order here is load-bearing: pragma -> VACUUM -> migration. On an
	// already-populated existing DB the VACUUM converts it in place (heavier but
	// one-time on first open after upgrade). For :memory: auto_vacuum is a no-op
	// and WAL is unavailable; skip both there.
	ctx := context.Background()
	if onDisk {
		if _, err := db.ExecContext(ctx, `PRAGMA auto_vacuum=INCREMENTAL`); err != nil {
			_ = db.Close()
			return nil, fmt.Errorf("storage: sqlite set auto_vacuum: %w", err)
		}
		if _, err := db.ExecContext(ctx, `VACUUM`); err != nil {
			_ = db.Close()
			return nil, fmt.Errorf("storage: sqlite vacuum (auto_vacuum conversion): %w", err)
		}
	}

	if _, err := db.ExecContext(ctx, sqliteInitSchema); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("storage: sqlite migrate: %w", err)
	}

	// Finding 2 (verify WAL actually engaged): query journal_mode for the
	// on-disk path and fail loudly rather than silently running in a weaker
	// (DELETE) journal mode that drops the concurrent-reader guarantee.
	if onDisk {
		var mode string
		if err := db.QueryRowContext(ctx, `PRAGMA journal_mode`).Scan(&mode); err != nil {
			_ = db.Close()
			return nil, fmt.Errorf("storage: sqlite verify journal_mode: %w", err)
		}
		if !strings.EqualFold(mode, "wal") {
			_ = db.Close()
			return nil, fmt.Errorf("storage: sqlite WAL did not engage for %q: journal_mode=%q (refusing to run in a weaker mode)", path, mode)
		}
	}

	s := &sqliteStorage{db: db}

	// Finding 1 (background GC): start the periodic sweep goroutine. Stored
	// via gcCancel + gcWG so Close() can cancel + join with no leak.
	gcCtx, cancel := context.WithCancel(context.Background())
	s.gcCancel = cancel
	s.gcWG.Add(1)
	go s.gcLoop(gcCtx)

	return s, nil
}

// isMemoryPath reports whether path designates an in-memory SQLite database,
// for which WAL + auto_vacuum do not apply.
func isMemoryPath(path string) bool {
	return path == ":memory:" || strings.Contains(path, ":memory:") || strings.Contains(path, "mode=memory")
}

// gcLoop runs sweepExpired on the GC interval until the context is cancelled
// (by Close). Sweep errors are intentionally swallowed (logged-as-non-fatal at
// a future telemetry seam per §6.AC) — a transient sweep failure must not crash
// the process; the next tick retries. Mirrors pgcache.Client's GC goroutine.
func (s *sqliteStorage) gcLoop(ctx context.Context) {
	defer s.gcWG.Done()
	ticker := time.NewTicker(sqliteGCInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			_, _ = s.sweepExpired(ctx)
		}
	}
}

// sweepExpired physically DELETEs rows whose absolute expires_at is in the past,
// then runs PRAGMA incremental_vacuum to return the freed pages to the OS-visible
// free list (cheap; the freed pages are also reused by subsequent Sets thanks to
// auto_vacuum=INCREMENTAL). Returns the number of rows deleted. This is the
// SQLite analogue of pgcache.Client.PurgeExpired.
func (s *sqliteStorage) sweepExpired(ctx context.Context) (int64, error) {
	now := time.Now().UnixNano()
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM response_cache WHERE expires_at IS NOT NULL AND expires_at <= ?`,
		now,
	)
	if err != nil {
		return 0, fmt.Errorf("storage: sqlite sweep expired: %w", err)
	}
	deleted, err := res.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("storage: sqlite sweep rows affected: %w", err)
	}
	// Reclaim freelist pages incrementally (no-op when there is nothing to
	// reclaim; never errors when auto_vacuum=INCREMENTAL is engaged).
	if deleted > 0 {
		_, _ = s.db.ExecContext(ctx, `PRAGMA incremental_vacuum`)
	}
	return deleted, nil
}

// Get returns (value, OutcomeHit, nil) for a live entry, (nil, OutcomeMiss,
// nil) for a missing OR expired entry (lazy expiry), and (nil, OutcomeBypass,
// err) on a real DB error so callers can fall through to upstream — mirroring
// cache.Client.Get's contract exactly.
//
// A stored zero-length value is a HIT (not a miss): Scan into a non-nil []byte
// preserves an empty, non-nil slice, matching Postgres BYTEA empty-value
// semantics. See TestSQLiteZeroLengthValueRoundTrip.
func (s *sqliteStorage) Get(ctx context.Context, key string) ([]byte, cache.Outcome, error) {
	now := time.Now().UnixNano()
	value := []byte{} // non-nil so a stored empty BLOB scans to a non-nil empty slice
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
	if value == nil {
		// Defensive: ensure a hit never returns a nil slice (driver could
		// theoretically scan NULL-ish into nil). value column is NOT NULL.
		value = []byte{}
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

// Close stops the background GC goroutine (cancelling its context and joining
// it — no leak) and closes the underlying database handle. Idempotent.
func (s *sqliteStorage) Close() error {
	s.closeMu.Lock()
	if s.closed {
		s.closeMu.Unlock()
		return nil
	}
	s.closed = true
	cancel := s.gcCancel
	s.closeMu.Unlock()

	if cancel != nil {
		cancel()
	}
	s.gcWG.Wait()
	return s.db.Close()
}
