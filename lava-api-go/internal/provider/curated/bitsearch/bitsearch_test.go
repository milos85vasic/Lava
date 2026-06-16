package bitsearch

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

// Anti-Bluff (§6.J): the SUT is the REAL BitSearch Client hitting a REAL
// httptest.Server socket serving a captured /api/v1/search response. The primary
// assertions are on the PARSED, user-visible SearchItem fields a real provider
// list renders (title, infohash, magnet, size, seeders) — not on call counts.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go, map Seeders from r.Leechers (wrong field).
//   Observed: TestSearch_ParsesFixture → "seeders = 41, want 28".
//   Mutation: drop the len(hash)!=40 filter in Search.
//   Observed: TestSearch_FiltersNon40HexInfohash → "got 3 results, want 2"
//             (the synthetic "deadbeef" row leaks through as a fake torrent).
//   Reverted: yes.

func serveFixture(t *testing.T) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "bitsearch_ubuntu.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != searchPath {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("q"); got == "" {
			http.Error(w, "missing q", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestSearch_ParsesFixture(t *testing.T) {
	srv := serveFixture(t)
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
	if first.Title != "ubuntu-19.04-desktop-amd64.iso" {
		t.Errorf("title = %q", first.Title)
	}
	// Uppercase upstream hash must be lowercased.
	const wantHash = "d540fc48eb12f2833163eed6421d449dd8f1ce1f"
	if first.InfoHash != wantHash {
		t.Errorf("infoHash = %q, want %q (lowercased)", first.InfoHash, wantHash)
	}
	if !strings.Contains(first.MagnetLink, "xt=urn:btih:"+wantHash) {
		t.Errorf("magnet missing btih: %q", first.MagnetLink)
	}
	if !strings.Contains(first.MagnetLink, "tr=") || !strings.Contains(first.MagnetLink, "dn=") {
		t.Errorf("magnet missing tr/dn: %q", first.MagnetLink)
	}
	if first.SizeBytes != 2097152000 {
		t.Errorf("sizeBytes = %d, want 2097152000", first.SizeBytes)
	}
	if first.Seeders != 28 {
		t.Errorf("seeders = %d, want 28", first.Seeders)
	}
	if first.Leechers != 41 {
		t.Errorf("leechers = %d, want 41", first.Leechers)
	}
	if first.Date == "" {
		t.Errorf("date should be parsed from a valid updatedAt, got empty")
	}

	// Second row has updatedAt="" → empty date (not a fabricated timestamp).
	if res.Results[1].Date != "" {
		t.Errorf("row with empty updatedAt must yield empty date, got %q", res.Results[1].Date)
	}
}

func TestSearch_FiltersNon40HexInfohash(t *testing.T) {
	srv := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	for _, item := range res.Results {
		if len(item.InfoHash) != 40 {
			t.Fatalf("non-40-hex infohash leaked as a real result: %q", item.InfoHash)
		}
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

// TestSearch_RetriesOnTransient5xx is the reproduce-first (§6.T.1) regression
// test for the single-attempt gap: BitSearch's variable live latency
// occasionally produced a user-facing "unknown error". The provider now retries
// transient failures (here: a 503 on the first attempt) and recovers.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2):
//
//	Mutation: replace the c.fetchResults call in Search with a direct
//	          c.fetchResultsOnce call (drop the retry loop), restoring the
//	          single-attempt behavior.
//	Observed: "Search after transient 503 should recover via retry:
//	          bitsearch: HTTP 503: provider: unknown error".
//	Reverted: yes.
func TestSearch_RetriesOnTransient5xx(t *testing.T) {
	body, err := os.ReadFile(filepath.Join("testdata", "bitsearch_ubuntu.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var attempts int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts == 1 {
			// One transient 5xx, then serve the real response (the slow-upstream case).
			http.Error(w, "transient", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)

	c := NewClient(srv.URL)
	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search after transient 503 should recover via retry: %v", err)
	}
	// Primary assertion: user-visible parsed results (the 2 valid-hash rows).
	if len(res.Results) != 2 {
		t.Fatalf("got %d results after retry, want 2", len(res.Results))
	}
	// Secondary: the upstream was actually hit more than once (the retry fired).
	if attempts < 2 {
		t.Fatalf("expected >=2 upstream attempts (retry), got %d", attempts)
	}
}

// TestSearch_TerminalErrorNotRetried proves the retry does NOT waste the budget
// on a terminal error: a persistent 404 returns ErrNotFound after exactly ONE
// attempt (no retry of non-transient failures).
func TestSearch_TerminalErrorNotRetried(t *testing.T) {
	var attempts int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		attempts++
		w.WriteHeader(http.StatusNotFound)
	}))
	t.Cleanup(srv.Close)

	c := NewClient(srv.URL)
	_, err := c.Search(context.Background(), "ubuntu", 0)
	if !errors.Is(err, provider.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
	if attempts != 1 {
		t.Fatalf("terminal 404 must NOT be retried; got %d attempts", attempts)
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
	if _, err := p.GetTorrent(context.Background(), "x", provider.Credentials{}); !errors.Is(err, provider.ErrUnsupported) {
		t.Errorf("GetTorrent err = %v, want ErrUnsupported", err)
	}
}
