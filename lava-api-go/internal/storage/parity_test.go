// parity_test.go — cross-backend parity for the Storage backends.
//
// Drives the REAL production HTTP handlers (real Gin engine, real
// auth.GinMiddleware, real handlers.Register) twice — once wired to the
// SQLite Storage (always) and once to the Postgres Storage (under
// POSTGRES_TEST_URL) — issues IDENTICAL requests, and asserts the two
// backends produce field-equal responses (status + JSON body) AND that the
// real Storage actually round-trips the cached bytes (the second /search hits
// the cache, so the upstream scraper is called exactly once per backend).
//
// This is the §6.J load-bearing proof: a sqlite-backed deployment behaves
// identically to a postgres-backed one on the wire, through the same handler
// code a real client touches. No live upstream network — the scraper is a
// stub returning a fixed page; the cache layer under test is real.
package storage_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/gin-gonic/gin"

	pgcache "digital.vasic.cache/pkg/postgres"
	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/config"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/handlers"
	"digital.vasic.lava.apigo/internal/rutracker"
	"digital.vasic.lava.apigo/internal/storage"
)

// newBackendViaFactory builds a Storage through the production factory
// (storage.New) — the SAME selection path main.go uses — so a factory
// mis-wire (wrong impl for a backend) is observable by the parity assertions.
func newBackendViaFactory(t *testing.T, cfg *config.Config) storage.Storage {
	t.Helper()
	store, _, err := storage.New(cfg)
	if err != nil {
		t.Fatalf("storage.New(%s): %v", cfg.StorageBackend, err)
	}
	t.Cleanup(func() { _ = store.Close() })
	return store
}

// stubScraper implements handlers.ScraperClient. It embeds the interface so
// unused methods exist (and panic if ever called, surfacing an accidental
// dependency), and overrides only the surface the parity flow exercises:
// GetSearchPage (cached /search) and CheckAuthorised (uncached /index).
type stubScraper struct {
	handlers.ScraperClient // nil — unimplemented methods panic if called
	searchPage             *gen.SearchPageDto
	searchCalls            int
}

func (s *stubScraper) GetSearchPage(_ context.Context, _ rutracker.SearchOpts, _ string) (*gen.SearchPageDto, error) {
	s.searchCalls++
	return s.searchPage, nil
}

func (s *stubScraper) CheckAuthorised(_ context.Context, _ string) (bool, error) {
	return true, nil
}

func fixedSearchPage() *gen.SearchPageDto {
	return &gen.SearchPageDto{
		Page:     1,
		Pages:    1,
		Torrents: []gen.ForumTopicDtoTorrent{},
	}
}

// backendResult captures what one backend produced for the identical request
// sequence, so the two backends can be compared field-by-field.
type backendResult struct {
	indexStatus  int
	indexBody    []byte
	searchStatus int
	searchBody   []byte // body of the SECOND /search (cache-served)
	searchCalls  int    // upstream calls — must be 1 (cache short-circuited)
}

// driveBackend wires the real handlers to the given Storage and runs the
// parity request sequence: GET /index once, GET /search twice.
func driveBackend(t *testing.T, store storage.Storage) backendResult {
	t.Helper()
	gin.SetMode(gin.TestMode)
	scraper := &stubScraper{searchPage: fixedSearchPage()}

	r := gin.New()
	r.Use(auth.GinMiddleware())
	handlers.Register(r, &handlers.Deps{Cache: store, Scraper: scraper})

	var res backendResult

	// /index (health) — uncached.
	wIdx := httptest.NewRecorder()
	r.ServeHTTP(wIdx, httptest.NewRequest(http.MethodGet, "/index", nil))
	res.indexStatus = wIdx.Code
	res.indexBody = wIdx.Body.Bytes()

	// /search twice — first populates the real Storage, second must be
	// served FROM it (proving a real round-trip through the backend).
	var lastSearch *httptest.ResponseRecorder
	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/search?query=parity", nil))
		lastSearch = w
	}
	res.searchStatus = lastSearch.Code
	res.searchBody = lastSearch.Body.Bytes()
	res.searchCalls = scraper.searchCalls
	return res
}

// jsonFieldEqual reports whether two JSON byte slices are semantically equal
// (field-by-field, order-independent) by unmarshaling to generic values.
func jsonFieldEqual(t *testing.T, a, b []byte) bool {
	t.Helper()
	var va, vb interface{}
	if err := json.Unmarshal(a, &va); err != nil {
		t.Fatalf("unmarshal a=%q: %v", a, err)
	}
	if err := json.Unmarshal(b, &vb); err != nil {
		t.Fatalf("unmarshal b=%q: %v", b, err)
	}
	ra, _ := json.Marshal(va)
	rb, _ := json.Marshal(vb)
	return string(ra) == string(rb)
}

func TestCrossBackendParity(t *testing.T) {
	// SQLite backend via the production factory — always available
	// (no external service).
	sqlitePath := filepath.Join(t.TempDir(), "parity.db")
	sqliteStore := newBackendViaFactory(t, &config.Config{
		StorageBackend: config.BackendSQLite,
		SQLitePath:     sqlitePath,
	})
	sqliteRes := driveBackend(t, sqliteStore)

	// Self-checks on the SQLite backend's real behavior.
	if sqliteRes.indexStatus != http.StatusOK {
		t.Fatalf("sqlite /index status=%d want 200; body=%s", sqliteRes.indexStatus, sqliteRes.indexBody)
	}
	if sqliteRes.searchStatus != http.StatusOK {
		t.Fatalf("sqlite /search status=%d want 200; body=%s", sqliteRes.searchStatus, sqliteRes.searchBody)
	}
	if sqliteRes.searchCalls != 1 {
		t.Fatalf("sqlite upstream search calls=%d want 1 (second /search must be served from the real SQLite cache)", sqliteRes.searchCalls)
	}
	wantSearchBody, _ := json.Marshal(fixedSearchPage())
	if !jsonFieldEqual(t, sqliteRes.searchBody, wantSearchBody) {
		t.Fatalf("sqlite cached /search body=%s want field-equal to %s", sqliteRes.searchBody, wantSearchBody)
	}

	// Postgres backend — gated by POSTGRES_TEST_URL (real podman Postgres).
	url := os.Getenv("POSTGRES_TEST_URL")
	if url == "" {
		t.Skip("POSTGRES_TEST_URL not set; SQLite-leg parity verified, Postgres-leg skipped honestly (run scripts/run-test-pg.sh for full cross-backend parity)")
	}

	// Postgres backend. Constructed directly (not via storage.New) because
	// the factory's postgres path — like production — assumes the schema/table
	// already exist (created by the lava-migrate migration service, not the
	// app). The test must create them itself. NewPostgres is the exact
	// wrapper the factory's postgres branch returns, so this exercises the
	// same Storage impl; the SQLite leg above exercises the factory's
	// selection path (and is where the A5 factory mutation is observable).
	inner, err := pgcache.ConnectFromURL(context.Background(), &pgcache.Config{
		URL:        url,
		SchemaName: "lava_api_parity_test",
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
	_, _ = inner.Underlying().Exec(context.Background(),
		`TRUNCATE lava_api_parity_test.response_cache_t`)
	pgStore := storage.NewPostgres(inner)
	t.Cleanup(func() {
		_, _ = inner.Underlying().Exec(context.Background(),
			`DROP SCHEMA IF EXISTS lava_api_parity_test CASCADE`)
		_ = pgStore.Close()
	})
	pgRes := driveBackend(t, pgStore)

	// Cross-backend field-equality: the two backends must be wire-identical.
	if pgRes.indexStatus != sqliteRes.indexStatus {
		t.Errorf("/index status: postgres=%d sqlite=%d", pgRes.indexStatus, sqliteRes.indexStatus)
	}
	if !jsonFieldEqual(t, pgRes.indexBody, sqliteRes.indexBody) {
		t.Errorf("/index body diverges: postgres=%s sqlite=%s", pgRes.indexBody, sqliteRes.indexBody)
	}
	if pgRes.searchStatus != sqliteRes.searchStatus {
		t.Errorf("/search status: postgres=%d sqlite=%d", pgRes.searchStatus, sqliteRes.searchStatus)
	}
	if !jsonFieldEqual(t, pgRes.searchBody, sqliteRes.searchBody) {
		t.Errorf("/search cached body diverges: postgres=%s sqlite=%s", pgRes.searchBody, sqliteRes.searchBody)
	}
	if pgRes.searchCalls != 1 {
		t.Errorf("postgres upstream search calls=%d want 1 (second /search must be served from the real Postgres cache)", pgRes.searchCalls)
	}
}
