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

// Anti-Bluff (§6.J) + §6.D fixture honesty: the SUT is the REAL YTS Client
// hitting a REAL httptest.Server socket serving a REAL captured YTS response.
// The fixture testdata/yts_inception.json is a verbatim capture of the live
// YTS list_movies.json API (query_term=inception, limit=50) taken 2026-07-02
// from the production failover mirror set (yts.bz → yts.gg), NOT a fabricated
// document. The primary assertions are on the PARSED, user-visible SearchItem
// fields a real provider list renders (title, info_hash, magnet, size) — not on
// call counts. The real capture returns 1 movie (Inception (2010), id 1606)
// with 3 real torrents (720p/1080p/2160p), so the flattening + magnet-build +
// hash-lowercasing paths are exercised against real upstream data.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go searchOne, map SizeBytes from tor.Seeds (wrong YTS
//             JSON field) instead of tor.SizeBytes.
//   Observed: TestSearch_ParsesYTSFixture →
//             "yts_test.go: sizeBytes = 0, want 1148903752".
//   Reverted: yes (the mutation was actually performed against this test,
//             observed the failure above, then reverted via git checkout).

func serveFixture(t *testing.T) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "yts_inception.json"))
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

	res, err := c.Search(context.Background(), "inception", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if res.Provider != providerID {
		t.Errorf("provider = %q, want %q", res.Provider, providerID)
	}
	// FLATTENING: the real capture is 1 movie (Inception (2010)) with 3
	// torrents (720p, 1080p, 2160p) → 3 SearchItems, in torrents-array order.
	if len(res.Results) != 3 {
		t.Fatalf("got %d results, want 3 (one per real torrent — flattening)", len(res.Results))
	}

	first := res.Results[0]
	// Title = real title_long + " [" + quality + "]". First real torrent is 720p.
	if first.Title != "Inception (2010) [720p]" {
		t.Errorf("title = %q, want %q", first.Title, "Inception (2010) [720p]")
	}
	if !strings.Contains(first.Title, "720p") {
		t.Errorf("first title missing quality: %q", first.Title)
	}
	// The real upstream hash is UPPERCASE and MUST be lowercased by the parser.
	const wantHash = "ce9156eb497762f8b7577b71c0647a4b0c3423e1"
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
	// Real size_bytes for the 720p bluray torrent.
	if first.SizeBytes != 1148903752 {
		t.Errorf("sizeBytes = %d, want 1148903752", first.SizeBytes)
	}
	// Real date_uploaded_unix (1446333277) → exact RFC3339 in UTC.
	if first.Date != "2015-10-31T23:14:37Z" {
		t.Errorf("date = %q, want %q (parsed from date_uploaded_unix=1446333277)", first.Date, "2015-10-31T23:14:37Z")
	}

	// Second row is the 1080p quality from the SAME movie (proves flattening),
	// with its own real hash (also uppercase upstream → lowercased) + size.
	second := res.Results[1]
	if second.Title != "Inception (2010) [1080p]" {
		t.Errorf("second title = %q, want %q", second.Title, "Inception (2010) [1080p]")
	}
	if second.InfoHash != "224bf45881252643dfc2e71abc7b2660a21c68c4" {
		t.Errorf("second infoHash = %q", second.InfoHash)
	}
	if second.SizeBytes != 1986422374 {
		t.Errorf("second sizeBytes = %d, want 1986422374", second.SizeBytes)
	}

	// Third row is the 2160p quality — proves all torrents flatten through.
	third := res.Results[2]
	if third.Title != "Inception (2010) [2160p]" {
		t.Errorf("third title = %q, want %q", third.Title, "Inception (2010) [2160p]")
	}
	if third.InfoHash != "43e3691dc6f4172841e32b25b349e2b7a980b9c5" {
		t.Errorf("third infoHash = %q", third.InfoHash)
	}
	if third.SizeBytes != 7129645711 {
		t.Errorf("third sizeBytes = %d, want 7129645711", third.SizeBytes)
	}
	if third.Date != "2022-02-28T11:35:02Z" {
		t.Errorf("third date = %q, want %q (parsed from date_uploaded_unix=1646048102)", third.Date, "2022-02-28T11:35:02Z")
	}
}

// TestUnixToDate_ZeroYieldsEmpty preserves the date-parsing branch coverage that
// the real all-positive-timestamp inception fixture no longer exercises: YTS
// occasionally sends date_uploaded_unix=0, which MUST map to an empty Date
// (never a bogus 1970 epoch string a user would see). Direct unit test of the
// production unixToDate helper — no synthetic search fixture involved.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2):
//   Mutation: in client.go unixToDate, drop the `if sec <= 0 { return "" }`
//             guard so 0 formats as "1970-01-01T00:00:00Z".
//   Observed: TestUnixToDate_ZeroYieldsEmpty →
//             `unixToDate(0) = "1970-01-01T00:00:00Z", want ""`.
//   Reverted: yes.
func TestUnixToDate_ZeroYieldsEmpty(t *testing.T) {
	if got := unixToDate(0); got != "" {
		t.Errorf("unixToDate(0) = %q, want \"\" (YTS 0-timestamp must not render as epoch)", got)
	}
	// Positive timestamp round-trips to exact RFC3339 UTC.
	if got := unixToDate(1446333277); got != "2015-10-31T23:14:37Z" {
		t.Errorf("unixToDate(1446333277) = %q, want %q", got, "2015-10-31T23:14:37Z")
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

// TestSearch_FailsOverToHealthyMirror proves the YTS-domain-rotation fix: when
// the first mirror is dead (the real-world yts.mx NXDOMAIN case, simulated here
// by a 503 server), Search transparently falls over to a healthy mirror and
// returns its real parsed results. The primary assertion is on user-visible
// SearchItem data sourced from the SECOND server — not a call count.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//
//	Mutation: in client.go Search, `return nil, lastErr` after the FIRST
//	          iteration (break the failover loop so only the first mirror is
//	          tried).
//	Observed: TestSearch_FailsOverToHealthyMirror → "Search: yts: HTTP 503:
//	          unknown error" (the dead first mirror's error surfaces instead of
//	          the healthy mirror's results).
//	Reverted: yes.
func TestSearch_FailsOverToHealthyMirror(t *testing.T) {
	dead := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	t.Cleanup(dead.Close)

	body, err := os.ReadFile(filepath.Join("testdata", "yts_inception.json"))
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
	res, err := c.Search(context.Background(), "inception", 0)
	if err != nil {
		t.Fatalf("Search across [dead, healthy] mirrors should fail over, got error: %v", err)
	}
	// Results came from the SECOND (healthy) mirror's REAL fixture: 3 torrents.
	if len(res.Results) != 3 {
		t.Fatalf("got %d results, want 3 (parsed from the healthy fallback mirror)", len(res.Results))
	}
	const wantHash = "ce9156eb497762f8b7577b71c0647a4b0c3423e1"
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
