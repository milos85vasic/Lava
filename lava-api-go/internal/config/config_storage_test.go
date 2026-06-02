package config

import (
	"encoding/base64"
	"testing"
)

// setRequiredEnv sets the always-required env vars (auth + TLS + listen)
// so a test can focus on the storage-backend validation under test.
// It does NOT set PGUrl or the storage-backend selector — those are the
// subject of the individual tests below.
func setRequiredEnv(t *testing.T) {
	t.Helper()
	t.Setenv("LAVA_AUTH_FIELD_NAME", "x-lava-client")
	t.Setenv("LAVA_AUTH_HMAC_SECRET", base64.StdEncoding.EncodeToString([]byte("0123456789abcdef")))
	t.Setenv("LAVA_API_TLS_CERT", "/tmp/cert.pem")
	t.Setenv("LAVA_API_TLS_KEY", "/tmp/key.pem")
	// Avoid leaking client lists / backoff from the ambient environment.
	t.Setenv("LAVA_AUTH_ACTIVE_CLIENTS", "")
	t.Setenv("LAVA_AUTH_RETIRED_CLIENTS", "")
}

// TestDefaultBackendStillRequiresPGUrl preserves today's behavior EXACTLY:
// with no LAVA_API_STORAGE_BACKEND set, the backend defaults to postgres
// and LAVA_API_PG_URL remains required. This is the regression guard for
// existing deployments.
func TestDefaultBackendStillRequiresPGUrl(t *testing.T) {
	setRequiredEnv(t)
	t.Setenv("LAVA_API_STORAGE_BACKEND", "") // unset → default postgres
	t.Setenv("LAVA_API_PG_URL", "")          // missing → must fail

	_, err := Load()
	if err == nil {
		t.Fatal("expected error: default backend is postgres and PGUrl is required, got nil")
	}
	want := "LAVA_API_PG_URL is required"
	if !contains(err.Error(), want) {
		t.Fatalf("error %q does not mention %q", err.Error(), want)
	}

	// And with PGUrl present, default-backend Load must succeed.
	t.Setenv("LAVA_API_PG_URL", "postgres://u:p@localhost:5432/db")
	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load with default backend + PGUrl: unexpected error %v", err)
	}
	if cfg.StorageBackend != "postgres" {
		t.Errorf("StorageBackend=%q want postgres (default)", cfg.StorageBackend)
	}
}

// TestSQLiteBackendRequiresPathNotPGUrl asserts that under the sqlite
// backend, PGUrl is NOT required (a deployment may run with no Postgres
// at all) — only SQLitePath is.
func TestSQLiteBackendRequiresPathNotPGUrl(t *testing.T) {
	setRequiredEnv(t)
	t.Setenv("LAVA_API_STORAGE_BACKEND", "sqlite")
	t.Setenv("LAVA_API_PG_URL", "") // absent — must be tolerated under sqlite
	t.Setenv("LAVA_API_SQLITE_PATH", "/data/lava-api.db")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("sqlite backend with path and no PGUrl: unexpected error %v", err)
	}
	if cfg.StorageBackend != "sqlite" {
		t.Errorf("StorageBackend=%q want sqlite", cfg.StorageBackend)
	}
	if cfg.SQLitePath != "/data/lava-api.db" {
		t.Errorf("SQLitePath=%q want /data/lava-api.db", cfg.SQLitePath)
	}
}

// TestSQLiteBackendRequiresPath asserts that the sqlite backend rejects a
// missing LAVA_API_SQLITE_PATH.
func TestSQLiteBackendRequiresPath(t *testing.T) {
	setRequiredEnv(t)
	t.Setenv("LAVA_API_STORAGE_BACKEND", "sqlite")
	t.Setenv("LAVA_API_PG_URL", "")
	t.Setenv("LAVA_API_SQLITE_PATH", "") // missing → must fail

	_, err := Load()
	if err == nil {
		t.Fatal("expected error: sqlite backend requires LAVA_API_SQLITE_PATH, got nil")
	}
	want := "LAVA_API_SQLITE_PATH is required"
	if !contains(err.Error(), want) {
		t.Fatalf("error %q does not mention %q", err.Error(), want)
	}
}

// TestUnknownBackendRejected asserts an unknown backend value is rejected.
func TestUnknownBackendRejected(t *testing.T) {
	setRequiredEnv(t)
	t.Setenv("LAVA_API_STORAGE_BACKEND", "redis") // not in {postgres,sqlite}
	t.Setenv("LAVA_API_PG_URL", "postgres://u:p@localhost:5432/db")

	_, err := Load()
	if err == nil {
		t.Fatal("expected error: unknown backend must be rejected, got nil")
	}
	want := "unknown storage backend"
	if !contains(err.Error(), want) {
		t.Fatalf("error %q does not mention %q", err.Error(), want)
	}
}

func contains(s, sub string) bool {
	return len(s) >= len(sub) && (s == sub || indexOf(s, sub) >= 0)
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}
