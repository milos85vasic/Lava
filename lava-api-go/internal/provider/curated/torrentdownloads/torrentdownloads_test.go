package torrentdownloads

import (
	"bytes"
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

// Anti-Bluff (§6.J): the SUT is the REAL Client hitting a REAL httptest.Server
// serving a captured ISO-8859-1 RSS feed. Primary assertions are on the PARSED,
// user-visible SearchItem fields (title, infohash, magnet, native byte size,
// seeders) — not call counts.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go, map Seeders from it.Leechers (wrong field).
//   Observed: TestSearch_ParsesFixture → "seeders = 30, want 978".
//   Mutation: drop the len(hash)!=40 filter in Search.
//   Observed: TestSearch_FiltersNon40HexInfohash → "got 3 results, want 2".
//   Mutation: in latin1CharsetReader, `return in, nil` for all charsets (no
//             conversion).
//   Observed: TestLatin1CharsetDecodes → xml decode error "unknown charset" OR
//             the é byte mojibakes; the test fails.
//   Reverted: yes.

func serveFixture(t *testing.T) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "torrentdownloads_ubuntu.xml"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("search") == "" {
			http.Error(w, "missing search", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=UTF-8")
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
	if first.Title != "Ubuntu 12 10 Server 64 Bit" {
		t.Errorf("title = %q", first.Title)
	}
	const wantHash = "19987310611f0fae3b2c9678601b92967d938aff"
	if first.InfoHash != wantHash {
		t.Errorf("infoHash = %q, want %q", first.InfoHash, wantHash)
	}
	if !strings.Contains(first.MagnetLink, "xt=urn:btih:"+wantHash) {
		t.Errorf("magnet missing btih: %q", first.MagnetLink)
	}
	if !strings.Contains(first.MagnetLink, "tr=") || !strings.Contains(first.MagnetLink, "dn=") {
		t.Errorf("magnet missing tr/dn: %q", first.MagnetLink)
	}
	// <size> is native bytes.
	if first.SizeBytes != 24898149 {
		t.Errorf("sizeBytes = %d, want 24898149", first.SizeBytes)
	}
	if first.Seeders != 978 {
		t.Errorf("seeders = %d, want 978", first.Seeders)
	}
	if first.Leechers != 30 {
		t.Errorf("leechers = %d, want 30", first.Leechers)
	}
	if first.Date == "" {
		t.Errorf("date should be parsed from a valid pubDate, got empty")
	}

	// Second row: native byte size + empty pubDate → empty date.
	second := res.Results[1]
	if second.SizeBytes != 3826831360 {
		t.Errorf("second sizeBytes = %d, want 3826831360", second.SizeBytes)
	}
	if second.Date != "" {
		t.Errorf("row with empty pubDate must yield empty date, got %q", second.Date)
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

// TestLatin1CharsetDecodes proves the ISO-8859-1 charset reader: a raw latin-1
// byte (0xE9 = é) in a title must decode to the correct UTF-8 rune, not mojibake
// or a decode error. This is the load-bearing reason the provider works against
// the real (latin-1) feed at all.
func TestLatin1CharsetDecodes(t *testing.T) {
	// Build a real ISO-8859-1 byte stream: the title "caf<0xE9>" = "café".
	raw := []byte("<?xml version='1.0' encoding='iso-8859-1' ?><rss version='2.0'><channel><item>" +
		"<title>caf\xe9</title>" +
		"<info_hash>19987310611f0fae3b2c9678601b92967d938aff</info_hash>" +
		"<seeders>5</seeders><leechers>1</leechers><size>100</size><pubDate></pubDate>" +
		"</item></channel></rss>")

	feed, err := parseFeed(bytes.NewReader(raw))
	if err != nil {
		t.Fatalf("parseFeed on latin-1 input: %v", err)
	}
	if len(feed.Items) != 1 {
		t.Fatalf("got %d items, want 1", len(feed.Items))
	}
	if feed.Items[0].Title != "café" {
		t.Errorf("latin-1 title = %q, want %q (the 0xE9 byte must decode to é)", feed.Items[0].Title, "café")
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
