package knaben

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// Anti-Bluff (§6.J): the SUT is the REAL Knaben Client POSTing to a REAL
// httptest.Server serving a captured /v1 response. Primary assertions are on the
// PARSED, user-visible SearchItem fields — not call counts.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go, map Seeders from h.Peers (wrong field).
//   Observed: TestSearch_ParsesFixture → "seeders = 7, want 100".
//   Mutation: drop the len(hash)!=40 filter in Search.
//   Observed: TestSearch_FiltersNon40HexHash → "got 3 results, want 2".
//   Mutation: add SearchType "score" to searchRequest (the EZTV no-op trap).
//   Observed: TestSearch_SendsMinimalBodyNoSearchType → "request body carried
//             forbidden search_type field".
//   Reverted: yes.

// captureServer serves the fixture AND records the last request body so a test
// can assert the request shape (the minimal-body guard).
func captureServer(t *testing.T, lastBody *string) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "knaben_ubuntu.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != searchPath || r.Method != http.MethodPost {
			http.NotFound(w, r)
			return
		}
		b, _ := io.ReadAll(r.Body)
		if lastBody != nil {
			*lastBody = string(b)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestSearch_ParsesFixture(t *testing.T) {
	srv := captureServer(t, nil)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if res.Provider != providerID {
		t.Errorf("provider = %q, want %q", res.Provider, providerID)
	}
	if len(res.Results) != 2 {
		t.Fatalf("got %d results, want 2 (the non-40-hex row must be filtered)", len(res.Results))
	}

	first := res.Results[0]
	if first.Title != "Ubuntu" {
		t.Errorf("title = %q", first.Title)
	}
	const wantHash = "8e1e7ad6a7198d1bea2d8564f40ec3480c490301"
	if first.InfoHash != wantHash {
		t.Errorf("infoHash = %q, want %q (lowercased)", first.InfoHash, wantHash)
	}
	if !strings.Contains(first.MagnetLink, "xt=urn:btih:"+wantHash) {
		t.Errorf("magnet missing btih: %q", first.MagnetLink)
	}
	if !strings.Contains(first.MagnetLink, "tr=") || !strings.Contains(first.MagnetLink, "dn=") {
		t.Errorf("magnet missing tr/dn: %q", first.MagnetLink)
	}
	if first.SizeBytes != 732912680 {
		t.Errorf("sizeBytes = %d, want 732912680", first.SizeBytes)
	}
	if first.Seeders != 100 {
		t.Errorf("seeders = %d, want 100", first.Seeders)
	}
	if first.Leechers != 7 {
		t.Errorf("leechers = %d, want 7 (from the peers field)", first.Leechers)
	}
	if first.Date == "" {
		t.Errorf("date should be parsed from a valid date, got empty")
	}

	// Second row has date="" → empty date (not a fabricated timestamp).
	if res.Results[1].Date != "" {
		t.Errorf("row with empty date must yield empty date, got %q", res.Results[1].Date)
	}
}

func TestSearch_FiltersNon40HexHash(t *testing.T) {
	srv := captureServer(t, nil)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	for _, item := range res.Results {
		if len(item.InfoHash) != 40 {
			t.Fatalf("non-40-hex hash leaked as a real result: %q", item.InfoHash)
		}
	}
}

// TestSearch_SendsMinimalBodyNoSearchType is the EZTV-lesson guard: Knaben only
// filters when the body is the minimal {query,size} form; the search_type:"score"
// variant is a silent no-op. This asserts the wire request carries query+size and
// NEVER search_type, so reintroducing the trap fails here deterministically (no
// live dependency on Knaben's buggy behavior).
func TestSearch_SendsMinimalBodyNoSearchType(t *testing.T) {
	var sent string
	srv := captureServer(t, &sent)
	c := NewClient(srv.URL)

	if _, err := c.Search(context.Background(), "ubuntu", 0); err != nil {
		t.Fatalf("Search: %v", err)
	}
	if !strings.Contains(sent, `"query"`) || !strings.Contains(sent, `"size"`) {
		t.Errorf("request body missing query/size: %s", sent)
	}
	if strings.Contains(sent, "search_type") {
		t.Errorf("request body carried forbidden search_type field (EZTV no-op trap): %s", sent)
	}
}

func TestSearch_EmptyQueryIsNoData(t *testing.T) {
	c := NewClient(DefaultBaseURL)
	_, err := c.Search(context.Background(), "   ", 0)
	if !errors.Is(err, provider.ErrNoData) {
		t.Errorf("empty query err = %v, want ErrNoData", err)
	}
}

func TestSearch_ServerErrorSurfaces(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	t.Cleanup(srv.Close)
	c := NewClient(srv.URL)

	if _, err := c.Search(context.Background(), "ubuntu", 0); err == nil {
		t.Fatal("5xx must surface as an error")
	}
}

// Provider-adapter contract: capabilities are honest (§6.E) + anonymous.
func TestProviderAdapter_CatalogueMetadata(t *testing.T) {
	var p provider.Provider = New()

	if p.ID() != providerID {
		t.Errorf("ID = %q", p.ID())
	}
	if p.Kind() != "native" {
		t.Errorf("Kind = %q, want native (compiled-in)", p.Kind())
	}
	if !p.SupportsAnonymous() {
		t.Error("SupportsAnonymous must be true")
	}
	if p.AuthType() != provider.AuthNone {
		t.Errorf("AuthType = %q, want NONE", p.AuthType())
	}
	caps := p.Capabilities()
	hasSearch, hasMagnet := false, false
	for _, c := range caps {
		switch c {
		case provider.CapSearch:
			hasSearch = true
		case provider.CapMagnetLink:
			hasMagnet = true
		}
	}
	if !hasSearch || !hasMagnet {
		t.Errorf("capabilities = %v, want SEARCH+MAGNET_LINK", caps)
	}
	if _, err := p.GetTorrent(context.Background(), "x", provider.Credentials{}); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("GetTorrent err = %v, want ErrUnsupported", err)
	}
}
