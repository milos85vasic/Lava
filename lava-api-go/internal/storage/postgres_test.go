package storage

import (
	"context"
	"os"
	"testing"

	pgcache "digital.vasic.cache/pkg/postgres"
)

// TestPostgresStorageConformance runs the shared RunStorageConformance harness
// against the REAL Postgres backend (real podman Postgres via POSTGRES_TEST_URL,
// the same gating mechanism internal/cache/integration_test.go uses).
//
// Gating: skipped honestly when POSTGRES_TEST_URL is unset (run
// scripts/run-test-pg.sh to provide one). This is NOT a build-tag suite — it
// mirrors the existing cache integration test's env-var skip so the default
// `go test ./...` run does not require Postgres.
func TestPostgresStorageConformance(t *testing.T) {
	url := os.Getenv("POSTGRES_TEST_URL")
	if url == "" {
		t.Skip("POSTGRES_TEST_URL not set; run scripts/run-test-pg.sh (real podman Postgres required)")
	}

	RunStorageConformance(t, func() Storage {
		inner, err := pgcache.ConnectFromURL(context.Background(), &pgcache.Config{
			URL:        url,
			SchemaName: "lava_api_storage_test",
			TableName:  "response_cache_t",
			GCInterval: 0,
		})
		if err != nil {
			t.Fatalf("ConnectFromURL: %v", err)
		}
		if err := inner.CreateSchema(context.Background()); err != nil {
			_ = inner.Close()
			t.Fatalf("CreateSchema: %v", err)
		}
		// Start a clean slate for each fresh store, so conformance subtests
		// that expect an empty store (miss cases) are not polluted.
		_, _ = inner.Underlying().Exec(context.Background(),
			`TRUNCATE lava_api_storage_test.response_cache_t`)
		s := NewPostgres(inner)
		t.Cleanup(func() {
			_, _ = inner.Underlying().Exec(context.Background(),
				`DROP SCHEMA IF EXISTS lava_api_storage_test CASCADE`)
			_ = s.Close()
		})
		return s
	})
}
