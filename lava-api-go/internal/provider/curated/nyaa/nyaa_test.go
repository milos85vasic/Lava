package nyaa

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

// Anti-Bluff (§6.J): the SUT is the REAL Nyaa Client hitting a REAL
// httptest.Server serving a captured RSS feed. Primary assertions are on the
// PARSED, user-visible SearchItem fields (title, infohash, magnet, size from a
// human string, seeders) — not call counts.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go, map Seeders from it.Leechers (wrong field).
//   Observed: TestSearch_ParsesFixture → "seeders = 12, want 371".
//   Mutation: drop the len(hash)!=40 filter in Search.
//   Observed: TestSearch_FiltersNon40HexInfohash → "got 3 results, want 2".
//   Mutation: change MiB multiplier 1<<20 → 1<<10 in sizeUnits.
//   Observed: TestSearch_ParsesFixture → "sizeBytes = 409600, want 419430400".
//   Reverted: yes.

func serveFixture(t *testing.T) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "nyaa_naruto.xml"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("q") == "" {
			http.Error(w, "missing q", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/xml")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestSearch_ParsesFixture(t *testing.T) {
	srv := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "naruto", 0)
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
	if first.Title != "[Naruto-Kun] Naruto - 120 [1080p].mkv" {
		t.Errorf("title = %q", first.Title)
	}
	const wantHash = "a59cd8b4a43e993ecee79202b3d2fe9a7e6adb22"
	if first.InfoHash != wantHash {
		t.Errorf("infoHash = %q, want %q", first.InfoHash, wantHash)
	}
	if !strings.Contains(first.MagnetLink, "xt=urn:btih:"+wantHash) {
		t.Errorf("magnet missing btih: %q", first.MagnetLink)
	}
	if !strings.Contains(first.MagnetLink, "tr=") || !strings.Contains(first.MagnetLink, "dn=") {
		t.Errorf("magnet missing tr/dn: %q", first.MagnetLink)
	}
	// "400 MiB" must parse to exactly 400 * 1024 * 1024 bytes.
	if first.SizeBytes != 419430400 {
		t.Errorf("sizeBytes = %d, want 419430400 (400 MiB)", first.SizeBytes)
	}
	if first.Seeders != 371 {
		t.Errorf("seeders = %d, want 371", first.Seeders)
	}
	if first.Leechers != 12 {
		t.Errorf("leechers = %d, want 12", first.Leechers)
	}
	if first.Date == "" {
		t.Errorf("date should be parsed from a valid pubDate, got empty")
	}

	// Second row: "1.5 GiB" → 1610612736 bytes; empty pubDate → empty date.
	second := res.Results[1]
	if second.SizeBytes != 1610612736 {
		t.Errorf("second sizeBytes = %d, want 1610612736 (1.5 GiB)", second.SizeBytes)
	}
	if second.Date != "" {
		t.Errorf("row with empty pubDate must yield empty date, got %q", second.Date)
	}
}

func TestSearch_FiltersNon40HexInfohash(t *testing.T) {
	srv := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "naruto", 0)
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

	if _, err := c.Search(context.Background(), "naruto", 0); err == nil {
		t.Fatal("5xx must surface as an error")
	}
}

// TestParseHumanSize covers the IEC-suffix size parser directly (the
// user-visible size column depends on it).
func TestParseHumanSize(t *testing.T) {
	cases := map[string]int64{
		"400 MiB": 419430400,
		"1.5 GiB": 1610612736,
		"1 B":     1,
		"2 KiB":   2048,
		"":        0,
		"garbage": 0,
	}
	for in, want := range cases {
		if got := parseHumanSize(in); got != want {
			t.Errorf("parseHumanSize(%q) = %d, want %d", in, got, want)
		}
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
