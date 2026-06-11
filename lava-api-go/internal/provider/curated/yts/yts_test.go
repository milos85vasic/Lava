package yts

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// Anti-Bluff (§6.J): the SUT is the REAL YTS Client hitting a REAL
// httptest.Server socket serving a captured YTS response. The primary
// assertions are on the PARSED, user-visible SearchItem fields a real provider
// list renders (title, info_hash, magnet, size, seeders) — not on call counts.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go, map Seeders from tor.Peers (wrong YTS field).
//   Observed: TestSearch_ParsesYTSFixture →
//             "yts_test.go:93: seeders = 5, want 142".
//   Mutation: drop the len(hash)!=40 / !isHex(hash) skip in Search.
//   Observed: a non-40-hex hash would leak through as a fake torrent
//             (TestSearch_FlattensAndFiltersHashes → non-40-hex info_hash leaked).
//   Reverted: yes (the Seeders mutation was actually performed against this
//             test, observed the failure above, then reverted).

func serveFixture(t *testing.T) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "yts_ubuntu.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != searchPath {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("query_term"); got == "" {
			http.Error(w, "missing query_term", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestSearch_ParsesYTSFixture(t *testing.T) {
	srv := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if res.Provider != providerID {
		t.Errorf("provider = %q, want %q", res.Provider, providerID)
	}
	// FLATTENING: 1 movie with 2 torrents → 2 SearchItems.
	if len(res.Results) != 2 {
		t.Fatalf("got %d results, want 2 (one per torrent — flattening)", len(res.Results))
	}

	first := res.Results[0]
	// Title includes the quality tag.
	if first.Title != "Ubuntu (2019) [1080p]" {
		t.Errorf("title = %q, want %q", first.Title, "Ubuntu (2019) [1080p]")
	}
	if !strings.Contains(first.Title, "1080p") {
		t.Errorf("first title missing quality: %q", first.Title)
	}
	// Uppercase upstream hash must be lowercased.
	const wantHash = "34930674ef3bb9317fb5f263cca830c52a1e5da8"
	if first.InfoHash != wantHash {
		t.Errorf("infoHash = %q, want %q", first.InfoHash, wantHash)
	}
	if !strings.Contains(first.MagnetLink, "xt=urn:btih:"+wantHash) {
		t.Errorf("magnet missing btih: %q", first.MagnetLink)
	}
	// Magnet carries the public-tracker commons + display name.
	if !strings.Contains(first.MagnetLink, "tr=") || !strings.Contains(first.MagnetLink, "dn=") {
		t.Errorf("magnet missing tr/dn: %q", first.MagnetLink)
	}
	if first.SizeBytes != 1557135360 {
		t.Errorf("sizeBytes = %d, want 1557135360", first.SizeBytes)
	}
	if first.Seeders != 142 {
		t.Errorf("seeders = %d, want 142", first.Seeders)
	}
	if first.Leechers != 5 {
		t.Errorf("leechers = %d, want 5", first.Leechers)
	}
	if first.Date == "" {
		t.Errorf("date should be parsed from date_uploaded_unix>0, got empty")
	}

	// Second row is the 720p quality from the SAME movie (proves flattening),
	// with a lowercase hash already lowercased + date_uploaded_unix=0 → empty date.
	second := res.Results[1]
	if second.Title != "Ubuntu (2019) [720p]" {
		t.Errorf("second title = %q, want %q", second.Title, "Ubuntu (2019) [720p]")
	}
	if second.InfoHash != "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678" {
		t.Errorf("second infoHash = %q", second.InfoHash)
	}
	if second.Date != "" {
		t.Errorf("row with date_uploaded_unix=0 must yield empty date, got %q", second.Date)
	}
}

func TestSearch_FlattensAndFiltersHashes(t *testing.T) {
	srv := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	for _, item := range res.Results {
		if len(item.InfoHash) != 40 {
			t.Fatalf("non-40-hex info_hash leaked: %q", item.InfoHash)
		}
		if item.InfoHash != strings.ToLower(item.InfoHash) {
			t.Fatalf("info_hash not lowercased: %q", item.InfoHash)
		}
	}
}

func TestSearch_NoResultsIsEmpty(t *testing.T) {
	// data with movie_count:0 and NO movies key → empty Results, no error.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"ok","data":{"movie_count":0}}`))
	}))
	t.Cleanup(srv.Close)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "zzznoresultszzz", 0)
	if err != nil {
		t.Fatalf("no-results query must not error: %v", err)
	}
	if len(res.Results) != 0 {
		t.Fatalf("got %d results, want 0", len(res.Results))
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

	_, err := c.Search(context.Background(), "ubuntu", 0)
	if err == nil {
		t.Fatal("5xx must surface as an error")
	}
}

// Provider-adapter contract: capabilities are honest (§6.E) + the catalogue
// metadata reflects an anonymous, magnet-only public tracker.
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
	// §6.E: undeclared capabilities MUST return ErrUnsupported, never a stub success.
	if _, err := p.GetTorrent(context.Background(), "x", provider.Credentials{}); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("GetTorrent err = %v, want ErrUnsupported", err)
	}
}
