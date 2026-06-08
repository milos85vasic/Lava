package jackett

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

// loadFixture reads the realistic Torznab results feed hand-authored from the
// dossier's documented shape.
func loadFixture(t *testing.T) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", "torznab_results.xml"))
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	return b
}

// TestParseResults_TorrentEnclosureItem asserts the FIRST item — a .torrent
// enclosure — is parsed field-by-field against user-visible expectations.
func TestParseResults_TorrentEnclosureItem(t *testing.T) {
	results, err := ParseResults(loadFixture(t))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 items, got %d", len(results))
	}

	got := results[0]
	if got.Title != "Ubuntu 24.04 LTS Desktop amd64" {
		t.Errorf("Title = %q, want %q", got.Title, "Ubuntu 24.04 LTS Desktop amd64")
	}
	wantURL := "http://jackett:9117/dl/rutracker/?jackett_apikey=KEY&path=abc&file=ubuntu"
	if got.DownloadURL != wantURL {
		t.Errorf("DownloadURL = %q, want %q", got.DownloadURL, wantURL)
	}
	if got.EnclosureType != EnclosureTypeTorrent {
		t.Errorf("EnclosureType = %q, want %q", got.EnclosureType, EnclosureTypeTorrent)
	}
	if got.Seeders != 421 {
		t.Errorf("Seeders = %d, want 421", got.Seeders)
	}
	if got.Size != 1460985071 {
		t.Errorf("Size = %d, want 1460985071", got.Size)
	}
	if got.IsMagnetEnclosure() {
		t.Errorf("IsMagnetEnclosure() = true, want false for a .torrent enclosure")
	}
	// A .torrent item carries no magnet attr and an HTTP (non-magnet) URL, so
	// MagnetURL must remain empty — confirming we do not synthesize a fake one.
	if got.MagnetURL != "" {
		t.Errorf("MagnetURL = %q, want empty for a .torrent enclosure", got.MagnetURL)
	}
	if got.Infohash != "" {
		t.Errorf("Infohash = %q, want empty for this item", got.Infohash)
	}
}

// TestParseResults_MagnetItem asserts the SECOND item — a magnet enclosure
// with redundant magneturl + infohash attrs — is parsed correctly, including
// infohash lower-casing.
func TestParseResults_MagnetItem(t *testing.T) {
	results, err := ParseResults(loadFixture(t))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	got := results[1]

	if got.Title != "Debian 12 netinst arm64" {
		t.Errorf("Title = %q, want %q", got.Title, "Debian 12 netinst arm64")
	}
	wantMagnet := "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=debian-12"
	if got.DownloadURL != wantMagnet {
		t.Errorf("DownloadURL = %q, want %q", got.DownloadURL, wantMagnet)
	}
	if got.EnclosureType != EnclosureTypeMagnet {
		t.Errorf("EnclosureType = %q, want %q", got.EnclosureType, EnclosureTypeMagnet)
	}
	if !got.IsMagnetEnclosure() {
		t.Errorf("IsMagnetEnclosure() = false, want true for a magnet enclosure")
	}
	if got.MagnetURL != wantMagnet {
		t.Errorf("MagnetURL = %q, want %q", got.MagnetURL, wantMagnet)
	}
	if got.Seeders != 88 {
		t.Errorf("Seeders = %d, want 88", got.Seeders)
	}
	if got.Size != 734003200 {
		t.Errorf("Size = %d, want 734003200", got.Size)
	}
	// infohash must be lower-cased from the fixture's upper-case value.
	wantHash := "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
	if got.Infohash != wantHash {
		t.Errorf("Infohash = %q, want %q (lower-cased)", got.Infohash, wantHash)
	}
}

// TestParseResults_SeedersUnknownVsZero proves the -1 sentinel distinguishes
// "absent seeders" from a real 0.
func TestParseResults_SeedersUnknownVsZero(t *testing.T) {
	const feed = `<?xml version="1.0"?>
<rss xmlns:torznab="http://torznab.com/schemas/2015/feed"><channel>
  <item><title>NoSeedersAttr</title>
    <enclosure url="http://x/y" length="10" type="application/x-bittorrent"/>
  </item>
  <item><title>ZeroSeeders</title>
    <enclosure url="http://x/z" length="20" type="application/x-bittorrent"/>
    <torznab:attr name="seeders" value="0"/>
  </item>
</channel></rss>`
	results, err := ParseResults([]byte(feed))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if results[0].Seeders != -1 {
		t.Errorf("absent seeders = %d, want -1 (unknown)", results[0].Seeders)
	}
	if results[1].Seeders != 0 {
		t.Errorf("explicit 0 seeders = %d, want 0", results[1].Seeders)
	}
	// Size falls back to enclosure length when no size attr is present.
	if results[0].Size != 10 {
		t.Errorf("size fallback = %d, want 10 (from enclosure length)", results[0].Size)
	}
}

// TestParseResults_EmptyFeed: a well-formed feed with no items is not an error.
func TestParseResults_EmptyFeed(t *testing.T) {
	const feed = `<?xml version="1.0"?><rss><channel><title>x</title></channel></rss>`
	results, err := ParseResults([]byte(feed))
	if err != nil {
		t.Fatalf("ParseResults error: %v", err)
	}
	if len(results) != 0 {
		t.Errorf("expected 0 items, got %d", len(results))
	}
}

// TestParseResults_MalformedXML: garbage in returns an error, not a panic.
func TestParseResults_MalformedXML(t *testing.T) {
	if _, err := ParseResults([]byte("<rss><channel>")); err == nil {
		t.Errorf("expected error for truncated XML, got nil")
	}
}

// TestBuildSearchURL asserts the request URL is built from config (no
// hardcoded base/apikey) with the documented Torznab path + params.
func TestBuildSearchURL(t *testing.T) {
	c, err := NewClient(Config{BaseURL: "http://jackett:9117/", APIKey: "secret-key-123"})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	got := c.BuildSearchURL("rutracker", "ubuntu lts")

	u, perr := url.Parse(got)
	if perr != nil {
		t.Fatalf("built URL does not parse: %v", perr)
	}
	if u.Path != "/api/v2.0/indexers/rutracker/results/torznab/api" {
		t.Errorf("path = %q, want torznab results path", u.Path)
	}
	// trailing slash on BaseURL must have been trimmed (no //api).
	if u.Host != "jackett:9117" {
		t.Errorf("host = %q, want jackett:9117", u.Host)
	}
	q := u.Query()
	if q.Get("apikey") != "secret-key-123" {
		t.Errorf("apikey = %q, want secret-key-123", q.Get("apikey"))
	}
	if q.Get("t") != "search" {
		t.Errorf("t = %q, want search", q.Get("t"))
	}
	if q.Get("q") != "ubuntu lts" {
		t.Errorf("q = %q, want 'ubuntu lts'", q.Get("q"))
	}
}

func TestBuildCapsURL(t *testing.T) {
	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})
	got := c.BuildCapsURL(IndexerAll)
	u, _ := url.Parse(got)
	if u.Path != "/api/v2.0/indexers/all/results/torznab/api" {
		t.Errorf("caps path = %q", u.Path)
	}
	if u.Query().Get("t") != "caps" {
		t.Errorf("t = %q, want caps", u.Query().Get("t"))
	}
}

func TestNewClient_MissingConfig(t *testing.T) {
	if _, err := NewClient(Config{BaseURL: "", APIKey: "k"}); err != ErrMissingConfig {
		t.Errorf("missing base: err = %v, want ErrMissingConfig", err)
	}
	if _, err := NewClient(Config{BaseURL: "http://x", APIKey: ""}); err != ErrMissingConfig {
		t.Errorf("missing apikey: err = %v, want ErrMissingConfig", err)
	}
}

// TestDownload_302ToMagnet is the documented edge case: Jackett answers a
// download link with HTTP 302 whose Location is a magnet URI. The client MUST
// NOT follow it (which would "GET magnet:" and fail) — it captures Location.
func TestDownload_302ToMagnet(t *testing.T) {
	const wantMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=x"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Location", wantMagnet)
		w.WriteHeader(http.StatusFound) // 302
	}))
	defer srv.Close()

	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})
	dr, err := c.Download(context.Background(), srv.URL+"/dl/rutracker/?file=x")
	if err != nil {
		t.Fatalf("Download error: %v", err)
	}
	if !dr.IsMagnet() {
		t.Fatalf("IsMagnet() = false, want true")
	}
	if dr.Magnet != wantMagnet {
		t.Errorf("Magnet = %q, want %q", dr.Magnet, wantMagnet)
	}
	if dr.TorrentBytes != nil {
		t.Errorf("TorrentBytes = %v, want nil for a magnet redirect", dr.TorrentBytes)
	}
}

// TestDownload_TorrentFile: a 200 serving .torrent bytes is returned as bytes.
func TestDownload_TorrentFile(t *testing.T) {
	payload := []byte("d8:announce...e") // synthetic .torrent-ish bytes
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", EnclosureTypeTorrent)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(payload)
	}))
	defer srv.Close()

	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})
	dr, err := c.Download(context.Background(), srv.URL+"/dl/rutracker/?file=t")
	if err != nil {
		t.Fatalf("Download error: %v", err)
	}
	if dr.IsMagnet() {
		t.Fatalf("IsMagnet() = true, want false for a served .torrent")
	}
	if string(dr.TorrentBytes) != string(payload) {
		t.Errorf("TorrentBytes = %q, want %q", dr.TorrentBytes, payload)
	}
	if dr.ContentType != EnclosureTypeTorrent {
		t.Errorf("ContentType = %q, want %q", dr.ContentType, EnclosureTypeTorrent)
	}
}

// TestDownload_MagnetEnclosureShortCircuits: when the URL is already a magnet,
// Download returns it without an HTTP round-trip.
func TestDownload_MagnetEnclosureShortCircuits(t *testing.T) {
	const m = "magnet:?xt=urn:btih:deadbeef"
	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})
	dr, err := c.Download(context.Background(), m)
	if err != nil {
		t.Fatalf("Download error: %v", err)
	}
	if dr.Magnet != m {
		t.Errorf("Magnet = %q, want %q", dr.Magnet, m)
	}
}

// TestSearch_EndToEnd drives the real Search code path against a test server
// that returns the fixture feed, confirming URL building + parsing compose.
func TestSearch_EndToEnd(t *testing.T) {
	fixture := loadFixture(t)
	var gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "application/rss+xml")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c, _ := NewClient(Config{BaseURL: srv.URL, APIKey: "k"})
	results, err := c.Search(context.Background(), "rutracker", "iso")
	if err != nil {
		t.Fatalf("Search error: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}
	if gotPath != "/api/v2.0/indexers/rutracker/results/torznab/api" {
		t.Errorf("server saw path %q", gotPath)
	}
	if !contains(gotQuery, "t=search") || !contains(gotQuery, "q=iso") || !contains(gotQuery, "apikey=k") {
		t.Errorf("server saw query %q, missing expected params", gotQuery)
	}
	// And the parsed results are the real ones.
	if results[0].Seeders != 421 || results[1].Infohash != "c12fe1c06bba254a9dc9f519b335aa7c1367a88a" {
		t.Errorf("end-to-end parse mismatch: %+v", results)
	}
}

func contains(haystack, needle string) bool {
	for i := 0; i+len(needle) <= len(haystack); i++ {
		if haystack[i:i+len(needle)] == needle {
			return true
		}
	}
	return false
}
