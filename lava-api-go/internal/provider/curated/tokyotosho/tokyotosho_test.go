package tokyotosho

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

// Anti-Bluff (§6.J): the SUT is the REAL Tokyo Toshokan Client hitting a REAL
// httptest.Server serving a captured RSS feed (real bytes from the live
// 2026-06-13 terms=naruto probe). Primary assertions are on the PARSED,
// user-visible SearchItem fields (title, infohash, magnet, size from a decimal
// human string) — not call counts.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2) — recorded in the Bluff-Audit stamp:
//   Mutation: in client.go extractInfoHash, return "" unconditionally (magnet
//             extraction disabled).
//   Observed: TestSearch_ParsesFixture → "got 0 results, want 2 ...".
//   Mutation: in client.go Search, send the query as `xterms` not `terms`
//             (query-not-sent under the name the upstream honors).
//   Observed: TestSearch_SendsTermsParam → "Search: tokyotosho: HTTP 400: ..."
//             (the upstream/test-server rejects the request when `terms` is
//             absent, so the query never reaches the user).
//   Mutation: in client.go parseHumanSize, map the "MB" multiplier to 1<<20
//             (wrong unit — IEC instead of decimal SI).
//   Observed: TestSearch_ParsesFixture → "sizeBytes = 119422320, want 113890000".
//   Reverted: yes.

func serveFixture(t *testing.T) (*httptest.Server, *string) {
	t.Helper()
	body, err := os.ReadFile(filepath.Join("testdata", "tokyotosho_naruto.xml"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var sawTerms string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawTerms = r.URL.Query().Get("terms")
		if sawTerms == "" {
			http.Error(w, "missing terms", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/xml")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)
	return srv, &sawTerms
}

func TestSearch_ParsesFixture(t *testing.T) {
	srv, _ := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "naruto", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if res.Provider != providerID {
		t.Errorf("provider = %q, want %q", res.Provider, providerID)
	}
	// 3 items in the fixture; the third has NO magnet in its description and MUST
	// be filtered out (anti-bluff: only downloadable rows reach the user).
	if len(res.Results) != 2 {
		t.Fatalf("got %d results, want 2 (the no-magnet row must be filtered)", len(res.Results))
	}

	first := res.Results[0]
	if first.Title != "Cutie Kuromi - Naruto chan.zip" {
		t.Errorf("title = %q", first.Title)
	}
	// Upstream emits BASE32 (32 chars) info_hash; we normalize to upper-case.
	const wantHash = "AIHBWH5HWF2UQCHMDT2D3O6TP63HBLNX"
	if first.InfoHash != wantHash {
		t.Errorf("infoHash = %q, want %q", first.InfoHash, wantHash)
	}
	if first.ID != wantHash {
		t.Errorf("id = %q, want %q", first.ID, wantHash)
	}
	if !strings.Contains(first.MagnetLink, "xt=urn:btih:"+wantHash) {
		t.Errorf("magnet missing btih: %q", first.MagnetLink)
	}
	if !strings.Contains(first.MagnetLink, "tr=") || !strings.Contains(first.MagnetLink, "dn=") {
		t.Errorf("magnet missing tr/dn: %q", first.MagnetLink)
	}
	// "113.89MB" is DECIMAL (SI) → 113.89 * 1_000_000 = 113890000 bytes. This is
	// the load-bearing distinction from nyaa's IEC sizes.
	if first.SizeBytes != 113890000 {
		t.Errorf("sizeBytes = %d, want 113890000 (113.89 MB decimal)", first.SizeBytes)
	}
	// Tokyo Toshokan RSS carries no seeders field — honest 0, documented.
	if first.Seeders != 0 {
		t.Errorf("seeders = %d, want 0 (no seeders field in upstream RSS)", first.Seeders)
	}
	if first.Date == "" {
		t.Errorf("date should be parsed from a valid pubDate, got empty")
	}
	if first.Category != "Other" {
		t.Errorf("category = %q, want Other", first.Category)
	}

	// Second row: "5.93MB" → 5930000 bytes; valid pubDate → non-empty date.
	second := res.Results[1]
	if second.SizeBytes != 5930000 {
		t.Errorf("second sizeBytes = %d, want 5930000 (5.93 MB decimal)", second.SizeBytes)
	}
	const wantHash2 = "KWVGGJOFAQNFXKH7NDMAGZWQIDIAU3CF"
	if second.InfoHash != wantHash2 {
		t.Errorf("second infoHash = %q, want %q", second.InfoHash, wantHash2)
	}
}

// TestSearch_SendsTermsParam proves the user's query is actually forwarded to the
// upstream as the `terms` parameter — the wire-level guard that the q-term is
// honored, complementing the live TestLive_QueryActuallyFilters.
func TestSearch_SendsTermsParam(t *testing.T) {
	srv, sawTerms := serveFixture(t)
	c := NewClient(srv.URL)

	if _, err := c.Search(context.Background(), "naruto", 0); err != nil {
		t.Fatalf("Search: %v", err)
	}
	if *sawTerms != "naruto" {
		t.Errorf("server saw terms=%q, want %q", *sawTerms, "naruto")
	}
}

func TestSearch_FiltersNoMagnetRows(t *testing.T) {
	srv, _ := serveFixture(t)
	c := NewClient(srv.URL)

	res, err := c.Search(context.Background(), "naruto", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	for _, item := range res.Results {
		if item.InfoHash == "" || item.MagnetLink == "" {
			t.Fatalf("a row without a magnet/infohash leaked as a real result: %+v", item)
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

// TestParseHumanSize covers the decimal-SI + IEC size parser directly (the
// user-visible size column depends on it). Tokyo Toshokan uses decimal MB/GB.
func TestParseHumanSize(t *testing.T) {
	cases := map[string]int64{
		"Size: 113.89MB<br />": 113890000,
		"Size: 5.93MB<br />":   5930000,
		"Size: 1.5GB<br />":    1500000000,
		"Size: 400 MiB<br />":  419430400,
		"no size here":         0,
		"":                     0,
	}
	for in, want := range cases {
		if got := parseHumanSize(in); got != want {
			t.Errorf("parseHumanSize(%q) = %d, want %d", in, got, want)
		}
	}
}

// TestExtractInfoHash covers the magnet-from-description extractor directly.
func TestExtractInfoHash(t *testing.T) {
	desc := `<a href="magnet:?xt=urn:btih:AIHBWH5HWF2UQCHMDT2D3O6TP63HBLNX&tr=http://x/announce">Magnet Link</a>`
	if got := extractInfoHash(desc); got != "AIHBWH5HWF2UQCHMDT2D3O6TP63HBLNX" {
		t.Errorf("extractInfoHash = %q", got)
	}
	if got := extractInfoHash("no magnet here"); got != "" {
		t.Errorf("extractInfoHash(no magnet) = %q, want empty", got)
	}
}

// Provider-adapter contract: capabilities are honest (§6.E) + anonymous.
func TestProviderAdapter_CatalogueMetadata(t *testing.T) {
	var p provider.Provider = New()

	if p.ID() != providerID {
		t.Errorf("ID = %q", p.ID())
	}
	if p.DisplayName() != "Tokyo Toshokan" {
		t.Errorf("DisplayName = %q", p.DisplayName())
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

// TestSearch_RetriesOnTransient5xx is the reproduce-first (§6.T.1) regression
// test for the nezha-2026-06-16 finding: Tokyo Toshokan's variable live latency
// occasionally produced a user-facing "unknown error". The provider now retries
// transient failures (here: a 503 on the first attempt) and recovers.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2):
//
//	Mutation: delete the retry loop in client.go fetchFeed (return fetchFeedOnce
//	          directly), restoring the single-attempt behavior.
//	Observed: "Search after transient 503 should recover via retry:
//	          tokyotosho: HTTP 503: provider: unknown error".
//	Reverted: yes.
func TestSearch_RetriesOnTransient5xx(t *testing.T) {
	body, err := os.ReadFile(filepath.Join("testdata", "tokyotosho_naruto.xml"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var attempts int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts == 1 {
			// One transient 5xx, then serve the real feed (the slow-upstream case).
			http.Error(w, "transient", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/xml")
		_, _ = w.Write(body)
	}))
	t.Cleanup(srv.Close)

	c := NewClient(srv.URL)
	res, err := c.Search(context.Background(), "naruto", 0)
	if err != nil {
		t.Fatalf("Search after transient 503 should recover via retry: %v", err)
	}
	// Primary assertion: user-visible parsed results (the 2 magnet rows).
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
	_, err := c.Search(context.Background(), "naruto", 0)
	if !errors.Is(err, provider.ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
	if attempts != 1 {
		t.Fatalf("terminal 404 must NOT be retried; got %d attempts", attempts)
	}
}
