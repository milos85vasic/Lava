package thepiratebay

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

// Anti-Bluff (§6.J): the SUT is the REAL apibay Client hitting a REAL
// httptest.Server socket serving a captured apibay response. The primary
// assertions are on the PARSED, user-visible SearchItem fields a real provider
// list renders (title, info_hash, magnet, size, seeders) — not on call counts.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go, map Seeders from r.Leechers (wrong apibay key).
//   Observed: TestSearch_ParsesApibayFixture → "seeders = 3, want 142".
//   Mutation: drop the noResultsHash/len!=40 filter in Search.
//   Observed: TestSearch_FiltersNoResultsPlaceholder → "got 3 results, want 2"
//             (the all-zero placeholder leaks through as a fake torrent).
//   Reverted: yes.

func serveFixture(t *testing.T) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "apibay_ubuntu.json"))
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

func TestSearch_ParsesApibayFixture(t *testing.T) {
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
		t.Fatalf("got %d results, want 2 (the all-zero placeholder must be filtered)", len(res.Results))
	}

	first := res.Results[0]
	if first.Title != "Ubuntu 22.04 LTS Desktop amd64" {
		t.Errorf("title = %q", first.Title)
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
	if first.SizeBytes != 3826831360 {
		t.Errorf("sizeBytes = %d, want 3826831360", first.SizeBytes)
	}
	if first.Seeders != 142 {
		t.Errorf("seeders = %d, want 142", first.Seeders)
	}
	if first.Leechers != 3 {
		t.Errorf("leechers = %d, want 3", first.Leechers)
	}
	if first.Date == "" {
		t.Errorf("date should be parsed from added>0, got empty")
	}

	// Second row has added=0 → empty date (not a fabricated timestamp).
	if res.Results[1].Date != "" {
		t.Errorf("row with added=0 must yield empty date, got %q", res.Results[1].Date)
	}
}

func TestSearch_FiltersNoResultsPlaceholder(t *testing.T) {
	srv := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	for _, item := range res.Results {
		if item.InfoHash == noResultsHash {
			t.Fatalf("apibay no-results placeholder leaked as a real result: %+v", item)
		}
		if len(item.InfoHash) != 40 {
			t.Fatalf("non-40-hex info_hash leaked: %q", item.InfoHash)
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

// TestSearch_FailsOverToHealthyMirror proves the apibay-domain-rotation
// resilience: when the first mirror is dead (the real-world NXDOMAIN case,
// simulated here by a 503 server), Search transparently falls over to a healthy
// mirror and returns its real parsed results. The primary assertion is on
// user-visible SearchItem data sourced from the SECOND server — not a call count.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//
//	Mutation: in client.go Search, `return nil, lastErr` after the FIRST
//	          iteration (break the failover loop so only the first mirror is
//	          tried).
//	Observed: TestSearch_FailsOverToHealthyMirror → "Search across [dead,
//	          healthy] mirrors should fail over, got error: thepiratebay: HTTP
//	          503: unknown error" (the dead first mirror's error surfaces instead
//	          of the healthy mirror's results).
//	Reverted: yes.
func TestSearch_FailsOverToHealthyMirror(t *testing.T) {
	dead := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	t.Cleanup(dead.Close)

	body, err := os.ReadFile(filepath.Join("testdata", "apibay_ubuntu.json"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	healthy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != searchPath {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}))
	t.Cleanup(healthy.Close)

	c := NewClientWithMirrors([]string{dead.URL, healthy.URL})
	res, err := c.Search(context.Background(), "ubuntu", 0)
	if err != nil {
		t.Fatalf("Search across [dead, healthy] mirrors should fail over, got error: %v", err)
	}
	// Results came from the SECOND (healthy) mirror's fixture (the all-zero
	// placeholder is filtered, leaving 2 real rows).
	if len(res.Results) != 2 {
		t.Fatalf("got %d results, want 2 (parsed from the healthy fallback mirror)", len(res.Results))
	}
	const wantHash = "34930674ef3bb9317fb5f263cca830c52a1e5da8"
	if res.Results[0].InfoHash != wantHash {
		t.Errorf("first infoHash = %q, want %q (from the healthy mirror)", res.Results[0].InfoHash, wantHash)
	}
}

// TestSearch_AllMirrorsDownSurfacesError proves failover does not hide a total
// outage: when EVERY mirror errors, the last error surfaces (no fake success).
func TestSearch_AllMirrorsDownSurfacesError(t *testing.T) {
	dead1 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	t.Cleanup(dead1.Close)
	dead2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	t.Cleanup(dead2.Close)

	c := NewClientWithMirrors([]string{dead1.URL, dead2.URL})
	if _, err := c.Search(context.Background(), "ubuntu", 0); err == nil {
		t.Fatal("all mirrors down must surface an error, not a fake empty success")
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
