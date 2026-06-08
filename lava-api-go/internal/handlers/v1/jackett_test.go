package v1

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/jackett"
	"digital.vasic.lava.apigo/internal/provider"
)

// newFakeJackettUpstream returns an httptest server that mimics the Jackett
// sidecar's Torznab results endpoint, serving the committed fixture feed
// (which contains BOTH a .torrent-enclosure item and a magnet-enclosure item).
// This is a BOUNDARY fake (a real HTTP socket) — the handler under test drives
// a REAL *jackett.Client through the production Torznab request-build + parse
// path; only the network upstream is synthetic.
func newFakeJackettUpstream(t *testing.T) *httptest.Server {
	t.Helper()
	body, err := os.ReadFile("../../jackett/testdata/torznab_results.xml")
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Sanity: the real client MUST have built a Torznab search URL with
		// apikey + t=search + q. If it didn't, the parse path is being
		// bypassed and the test would be a bluff.
		q := r.URL.Query()
		if q.Get("t") != "search" || q.Get("apikey") == "" || q.Get("q") == "" {
			t.Errorf("unexpected upstream request: %s", r.URL.String())
		}
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = w.Write(body)
	}))
}

// realJackettClient builds a production *jackett.Client pointed at the fake
// upstream, with a non-empty api_key so NewClient accepts it.
func realJackettClient(t *testing.T, baseURL string) *jackett.Client {
	t.Helper()
	cli, err := jackett.NewClient(jackett.Config{
		BaseURL: baseURL,
		APIKey:  "test-apikey-not-a-real-secret",
	})
	if err != nil {
		t.Fatalf("jackett.NewClient: %v", err)
	}
	return cli
}

func doJackettSearch(t *testing.T, h *JackettHandler, rawQuery string) (*httptest.ResponseRecorder, provider.SearchResult) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.GET("/jackett/search", h.GetSearch)

	req := httptest.NewRequest(http.MethodGet, "/jackett/search?"+rawQuery, nil)
	w := httptest.NewRecorder()
	router.ServeHTTP(w, req)

	var got provider.SearchResult
	if w.Code == http.StatusOK {
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatalf("unmarshal response body %q: %v", w.Body.String(), err)
		}
	}
	return w, got
}

// TestJackettHandler_MapsTorrentEnclosureResult asserts the .torrent-enclosure
// fixture item (Ubuntu) is mapped into a SearchItem with the HTTP downloadUrl
// populated, the correct title, size bytes, seeders, and provider stamp.
//
// FALSIFIABILITY REHEARSAL: replacing `item.DownloadURL = r.DownloadURL` with
// `item.DownloadURL = ""` in mapJackettResults makes this test fail with
// `torrent item downloadUrl = "", want the Jackett /dl/ proxy link`.
func TestJackettHandler_MapsTorrentEnclosureResult(t *testing.T) {
	upstream := newFakeJackettUpstream(t)
	defer upstream.Close()

	h := NewJackettHandler(realJackettClient(t, upstream.URL), "")
	w, got := doJackettSearch(t, h, "q="+url.QueryEscape("ubuntu"))

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	if got.Provider != "jackett" {
		t.Errorf("provider = %q, want %q", got.Provider, "jackett")
	}
	if got.Page != 1 || got.TotalPages != 1 {
		t.Errorf("page/totalPages = %d/%d, want 1/1", got.Page, got.TotalPages)
	}
	if len(got.Results) != 2 {
		t.Fatalf("results count = %d, want 2: %+v", len(got.Results), got.Results)
	}

	torrent := findByTitle(t, got.Results, "Ubuntu 24.04 LTS Desktop amd64")

	// User-visible state assertions on the .torrent enclosure mapping.
	if torrent.DownloadURL == "" {
		t.Errorf("torrent item downloadUrl = %q, want the Jackett /dl/ proxy link", torrent.DownloadURL)
	}
	if !strings.HasPrefix(torrent.DownloadURL, "http") {
		t.Errorf("torrent item downloadUrl = %q, want an HTTP(S) proxy link", torrent.DownloadURL)
	}
	if torrent.MagnetLink != "" {
		t.Errorf("torrent item magnetLink = %q, want empty for a .torrent enclosure", torrent.MagnetLink)
	}
	if torrent.SizeBytes != 1460985071 {
		t.Errorf("torrent item sizeBytes = %d, want 1460985071", torrent.SizeBytes)
	}
	if torrent.Seeders != 421 {
		t.Errorf("torrent item seeders = %d, want 421", torrent.Seeders)
	}
	if torrent.ID == "" {
		t.Errorf("torrent item id is empty, want the Torznab GUID")
	}
}

// TestJackettHandler_MapsMagnetEnclosureResult asserts the magnet-enclosure
// fixture item (Debian) is mapped into a SearchItem with magnetLink populated
// (NOT downloadUrl) and the infohash surfaced lower-cased.
//
// FALSIFIABILITY REHEARSAL: forcing the magnet branch off — replacing
// `if r.IsMagnetEnclosure()` with `if false` in mapJackettResults — makes this
// test fail with `magnet item magnetLink = "", want the magnet: URI`.
func TestJackettHandler_MapsMagnetEnclosureResult(t *testing.T) {
	upstream := newFakeJackettUpstream(t)
	defer upstream.Close()

	h := NewJackettHandler(realJackettClient(t, upstream.URL), "")
	w, got := doJackettSearch(t, h, "q="+url.QueryEscape("debian")+"&indexer=rutracker")

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}

	magnet := findByTitle(t, got.Results, "Debian 12 netinst arm64")

	if magnet.MagnetLink == "" {
		t.Errorf("magnet item magnetLink = %q, want the magnet: URI", magnet.MagnetLink)
	}
	if !strings.HasPrefix(magnet.MagnetLink, "magnet:?xt=urn:btih:") {
		t.Errorf("magnet item magnetLink = %q, want a magnet: URI", magnet.MagnetLink)
	}
	if magnet.DownloadURL != "" {
		t.Errorf("magnet item downloadUrl = %q, want empty for a magnet enclosure", magnet.DownloadURL)
	}
	// Torznab infohash attr is upper-case in the fixture; the client lower-cases it.
	if magnet.InfoHash != "c12fe1c06bba254a9dc9f519b335aa7c1367a88a" {
		t.Errorf("magnet item infoHash = %q, want lower-cased infohash", magnet.InfoHash)
	}
	if magnet.SizeBytes != 734003200 {
		t.Errorf("magnet item sizeBytes = %d, want 734003200", magnet.SizeBytes)
	}
	// indexer query param is stamped onto the result category.
	if magnet.Category != "rutracker" {
		t.Errorf("magnet item category = %q, want the indexer id %q", magnet.Category, "rutracker")
	}
}

// TestJackettHandler_MissingQueryReturns400 asserts the user-visible 400 on a
// missing required query, without ever hitting the upstream.
func TestJackettHandler_MissingQueryReturns400(t *testing.T) {
	upstream := newFakeJackettUpstream(t)
	defer upstream.Close()

	h := NewJackettHandler(realJackettClient(t, upstream.URL), "")
	w, _ := doJackettSearch(t, h, "")

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
	if !strings.Contains(w.Body.String(), "query parameter 'q' is required") {
		t.Errorf("body = %q, want the missing-query error message", w.Body.String())
	}
}

// TestJackettHandler_UpstreamErrorReturns502 asserts the sidecar-failure path
// surfaces a 502 to the user (real client gets a non-200 from the upstream).
func TestJackettHandler_UpstreamErrorReturns502(t *testing.T) {
	// Upstream that always 500s — the real *jackett.Client maps non-200 to an
	// error, which the handler surfaces as 502.
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer upstream.Close()

	h := NewJackettHandler(realJackettClient(t, upstream.URL), "")
	w, _ := doJackettSearch(t, h, "q=anything")

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502", w.Code)
	}
}

func findByTitle(t *testing.T, items []provider.SearchItem, title string) provider.SearchItem {
	t.Helper()
	for _, it := range items {
		if it.Title == title {
			return it
		}
	}
	t.Fatalf("no result with title %q in %+v", title, items)
	return provider.SearchItem{}
}
