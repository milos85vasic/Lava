package kinozal

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/net/html/charset"
)

func loadTestData(name string) []byte {
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		panic(err)
	}
	return b
}

// decodeWin1251 transcodes raw windows-1251 fixture bytes to UTF-8, mirroring
// exactly what Client.readBodyDecoded does in production before ParseSearchPage
// ever sees the body. The testdata fixtures are stored as the RAW windows-1251
// bytes kinozal.tv actually sends on the wire, so a direct ParseSearchPage call
// (which in production is only ever fed already-decoded UTF-8) must decode first
// or the Cyrillic size units and titles arrive as mojibake.
func decodeWin1251(t *testing.T, raw []byte) []byte {
	t.Helper()
	r, err := charset.NewReader(bytes.NewReader(raw), "text/html; charset=windows-1251")
	if err != nil {
		t.Fatalf("decode fixture: %v", err)
	}
	b, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("read decoded fixture: %v", err)
	}
	return b
}

func hasCyrillicSizeUnit(s string) bool {
	for _, u := range []string{"ГБ", "МБ", "КБ", "ТБ"} {
		if strings.Contains(s, u) {
			return true
		}
	}
	return false
}

// TestParseSearchPage drives the real production parser against a REAL captured
// kinozal.tv /browse.php?s=1080p results page (windows-1251, 50 rows) and
// asserts the user-visible state a searcher actually sees: a full page of
// result rows, the first row carrying a title + a Cyrillic-unit size + a parsed
// seeders value, and a multi-page pager.
//
// This replaces a prior BLUFF fixture: testdata/search/search_results.html used
// to be a hand-crafted synthetic page (<table class="tumblers"> / <a
// class="namer"> / Latin "1.5 GB") reverse-engineered to match the parser's
// fictional selectors, so the test was green while every real user got 0
// results. Real kinozal uses table.t_peer / td.nam a / td.sl_s / td.sl_p and
// Cyrillic size units (ГБ/МБ/КБ); the fixture is now the real page.
//
// FALSIFIABILITY REHEARSAL: in search.go, revert the row selector
// "table.t_peer tr" back to the old broken "table.tumblers tr" — the real page
// has no table.tumblers, so ParseSearchPage returns 0 rows and this test FAILS
// at "want >= 20 results, got 0". (Observed + reverted; see the fix commit's
// Bluff-Audit stamp.)
func TestParseSearchPage(t *testing.T) {
	html := decodeWin1251(t, loadTestData("search/search_results.html"))
	result, err := ParseSearchPage(html)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	if result.Provider != "kinozal" {
		t.Errorf("Provider = %q, want kinozal", result.Provider)
	}
	// A real 1080p results page returns a full page of rows. The old bluff
	// fixture had exactly 1 synthetic row; the real page has ~50.
	if len(result.Results) < 20 {
		t.Fatalf("Results len = %d, want >= 20 (a real kinozal results page)", len(result.Results))
	}

	first := result.Results[0]
	if strings.TrimSpace(first.Title) == "" {
		t.Errorf("Results[0].Title is empty — td.nam a selector parsed no title")
	}
	if first.ID == "" {
		t.Errorf("Results[0].ID is empty — details.php?id= extraction failed")
	}
	if !hasCyrillicSizeUnit(first.Size) {
		t.Errorf("Results[0].Size = %q, want a Cyrillic size unit (ГБ/МБ/КБ/ТБ) — td.s size cell not found", first.Size)
	}

	// The seeders column (td.sl_s) MUST actually parse: a 1080p search always
	// has seeded torrents, so at least one row carries Seeders > 0. If the sl_s
	// selector broke, every row would report 0 and this fails.
	totalSeeders := 0
	for _, r := range result.Results {
		totalSeeders += r.Seeders
	}
	if totalSeeders == 0 {
		t.Errorf("no result row parsed a Seeders value > 0 — td.sl_s selector is broken")
	}

	// The real page's pager exposes ~100 pages (page=99 link). The old bluff
	// pagination (absolute-path guard) reported TotalPages=1 for every page.
	if result.TotalPages <= 1 {
		t.Errorf("TotalPages = %d, want > 1 (the real page has a multi-page pager)", result.TotalPages)
	}
}

// TestClientSearch drives the FULL real production stack — Client.Search →
// Fetch → readBodyDecoded (windows-1251 → UTF-8) → ParseSearchPage — against a
// server that serves the real fixture with kinozal's real
// Content-Type: text/html; charset=windows-1251 header. Only the upstream HTTP
// socket is a local test server (the boundary the Anti-Bluff Pact permits); the
// client, the charset transcode, and the parser are all production code.
func TestClientSearch(t *testing.T) {
	raw := loadTestData("search/search_results.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/browse.php" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.URL.Query().Get("s") != "1080p" {
			t.Errorf("unexpected query: %s", r.URL.Query().Get("s"))
		}
		// Mirror kinozal's real charset header so readBodyDecoded transcodes
		// the windows-1251 body exactly as it does against the live site.
		w.Header().Set("Content-Type", "text/html; charset=windows-1251")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(raw)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Search(context.Background(), "1080p", 0, "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(result.Results) < 20 {
		t.Fatalf("Results len = %d, want >= 20 (full real-stack search returned an empty page)", len(result.Results))
	}
	// The end-to-end path must decode the Cyrillic size correctly, proving the
	// charset transcode boundary works, not just the selectors.
	if !hasCyrillicSizeUnit(result.Results[0].Size) {
		t.Errorf("Results[0].Size = %q, want a Cyrillic size unit — charset transcode or size selector broke", result.Results[0].Size)
	}
}
