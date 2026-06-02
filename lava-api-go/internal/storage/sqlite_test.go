package storage

import (
	"path/filepath"
	"testing"
)

// TestSQLiteStorageConformance runs the shared RunStorageConformance harness
// against the REAL pure-Go SQLite backend on a fresh on-disk database in
// t.TempDir(). No external service is required, so this leg ALWAYS runs in the
// default `go test ./...`. It is the load-bearing proof that the SQLite backend
// has behavior parity with Postgres (same harness, same assertions).
func TestSQLiteStorageConformance(t *testing.T) {
	RunStorageConformance(t, func() Storage {
		// Fresh DB file per newStore() call so empty-store subtests
		// (miss cases) start clean.
		path := filepath.Join(t.TempDir(), "conformance.db")
		s, err := NewSQLite(path)
		if err != nil {
			t.Fatalf("NewSQLite: %v", err)
		}
		t.Cleanup(func() { _ = s.Close() })
		return s
	})
}
