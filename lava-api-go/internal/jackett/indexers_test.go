package jackett

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestListIndexers_ParsesConfiguredIndexers wires a REAL *Client at a fake
// Jackett upstream (httptest server serving the captured configured-indexers
// JSON) and asserts ListIndexers parses the indexer set end-to-end. Only the
// network boundary is faked; the production request-build + JSON-decode path
// runs for real.
//
// Anti-bluff: the primary assertions are on the PARSED indexer data (ids,
// names, caps) — user-visible facts that the /v1/providers catalogue surfaces —
// not on "the request was made".
//
// Bluff-Audit:
//
//	Test:     TestListIndexers_ParsesConfiguredIndexers
//	Mutation: in ListIndexers, drop the `configured=true` query param OR return
//	          an empty slice unconditionally.
//	Observed: "expected 2 indexers, got 0" (the count assertion fires).
//	Reverted: yes.
func TestListIndexers_ParsesConfiguredIndexers(t *testing.T) {
	fixture := loadIndexersFixture(t)

	var gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	const apiKey = "test-jackett-apikey"
	cli, err := NewClient(Config{BaseURL: srv.URL, APIKey: apiKey})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}

	indexers, err := cli.ListIndexers(context.Background())
	if err != nil {
		t.Fatalf("ListIndexers: %v", err)
	}

	// Primary assertion: the parsed catalogue data.
	if len(indexers) != 2 {
		t.Fatalf("expected 2 indexers, got %d: %+v", len(indexers), indexers)
	}
	if indexers[0].ID != "1337x" || indexers[0].Name != "1337x" {
		t.Errorf("indexer[0] = {%q,%q}, want {1337x,1337x}", indexers[0].ID, indexers[0].Name)
	}
	if indexers[1].ID != "rutracker" || indexers[1].Name != "RuTracker" {
		t.Errorf("indexer[1] = {%q,%q}, want {rutracker,RuTracker}", indexers[1].ID, indexers[1].Name)
	}
	// Caps are parsed for informational use (category names).
	if len(indexers[0].Caps) != 3 {
		t.Errorf("indexer[0].Caps len = %d, want 3", len(indexers[0].Caps))
	}

	// Request-shape assertions (secondary): the production path hit the
	// documented Jackett endpoint with the configured filter + the apikey.
	if gotPath != "/api/v2.0/indexers" {
		t.Errorf("request path = %q, want /api/v2.0/indexers", gotPath)
	}
	if !strings.Contains(gotQuery, "configured=true") {
		t.Errorf("query %q missing configured=true", gotQuery)
	}
	if !strings.Contains(gotQuery, "apikey="+apiKey) {
		t.Errorf("query %q missing apikey", gotQuery)
	}
}

// TestListIndexers_NonOKStatusIsError asserts a non-200 from the upstream
// surfaces as an error (so the startup enumeration can RecordNonFatal + continue
// serving native providers, per spec §5).
func TestListIndexers_NonOKStatusIsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	cli, err := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	if _, err := cli.ListIndexers(context.Background()); err == nil {
		t.Fatal("expected error on HTTP 500, got nil")
	}
}

func loadIndexersFixture(t *testing.T) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "indexers_configured.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	return b
}
