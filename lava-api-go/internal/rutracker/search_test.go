package rutracker

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// loadSearchFixture reads an HTML fixture under testdata/search/.
// The fixtures faithfully mirror the selector walk that
// GetSearchPageUseCase.parseSearchPage performs against real
// rutracker.org pages; the live double-check is the cross-backend
// parity test in Phase 10.
func loadSearchFixture(t *testing.T, name string) []byte {
	t.Helper()
	p := filepath.Join("testdata", "search", name)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

func TestParseSearchPage_HappyPath(t *testing.T) {
	html := loadSearchFixture(t, "search_results.html")
	out, err := ParseSearchPage(html)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseSearchPage returned nil")
	}
	if out.Page != 1 {
		t.Errorf("Page: got %d, want 1", out.Page)
	}
	if out.Pages != 3 {
		t.Errorf("Pages: got %d, want 3", out.Pages)
	}
	if got := len(out.Torrents); got != 2 {
		t.Fatalf("expected 2 torrents, got %d", got)
	}

	t0 := out.Torrents[0]
	if t0.Id != "t1001" {
		t.Errorf("torrents[0].Id: got %q, want \"t1001\"", t0.Id)
	}
	if t0.Title != "Movie Title" {
		t.Errorf("torrents[0].Title: got %q, want \"Movie Title\" (tags stripped)", t0.Title)
	}
	if t0.Tags == nil || !strings.Contains(*t0.Tags, "[Drama]") || !strings.Contains(*t0.Tags, "[1080p]") {
		gotTags := "<nil>"
		if t0.Tags != nil {
			gotTags = *t0.Tags
		}
		t.Errorf("torrents[0].Tags: got %q, want to contain [Drama] and [1080p]", gotTags)
	}
	if t0.Author == nil {
		t.Fatal("torrents[0].Author is nil")
	}
	if t0.Author.Id == nil || *t0.Author.Id != "42" {
		gotID := "<nil>"
		if t0.Author.Id != nil {
			gotID = *t0.Author.Id
		}
		t.Errorf("torrents[0].Author.Id: got %q, want \"42\"", gotID)
	}
	if t0.Author.Name != "alice" {
		t.Errorf("torrents[0].Author.Name: got %q, want \"alice\"", t0.Author.Name)
	}
	if t0.Category == nil {
		t.Fatal("torrents[0].Category is nil")
	}
	if t0.Category.Id == nil || *t0.Category.Id != "7" {
		gotID := "<nil>"
		if t0.Category.Id != nil {
			gotID = *t0.Category.Id
		}
		t.Errorf("torrents[0].Category.Id: got %q, want \"7\"", gotID)
	}
	if t0.Category.Name != "Movies" {
		t.Errorf("torrents[0].Category.Name: got %q, want \"Movies\"", t0.Category.Name)
	}
	if t0.Size == nil || *t0.Size != "1.5 GB" {
		gotSize := "<nil>"
		if t0.Size != nil {
			gotSize = *t0.Size
		}
		t.Errorf("torrents[0].Size: got %q, want \"1.5 GB\" (formatSize 1610612736)", gotSize)
	}
	if t0.Date == nil {
		t.Error("torrents[0].Date: got nil, want 1700000000")
	} else if *t0.Date != 1700000000 {
		t.Errorf("torrents[0].Date: got %d, want 1700000000", *t0.Date)
	}
	if t0.Seeds == nil || *t0.Seeds != 12 {
		gotSeeds := int32(-1)
		if t0.Seeds != nil {
			gotSeeds = *t0.Seeds
		}
		t.Errorf("torrents[0].Seeds: got %d, want 12", gotSeeds)
	}
	if t0.Leeches == nil || *t0.Leeches != 3 {
		gotLeeches := int32(-1)
		if t0.Leeches != nil {
			gotLeeches = *t0.Leeches
		}
		t.Errorf("torrents[0].Leeches: got %d, want 3", gotLeeches)
	}
	if t0.Status == nil || *t0.Status != gen.Approved {
		gotStatus := "<nil>"
		if t0.Status != nil {
			gotStatus = string(*t0.Status)
		}
		t.Errorf("torrents[0].Status: got %q, want %q", gotStatus, gen.Approved)
	}
	if string(t0.Type) != "Torrent" {
		t.Errorf("torrents[0].Type: got %q, want \"Torrent\"", t0.Type)
	}

	// Row 1: tor-need-edit status.
	t1 := out.Torrents[1]
	if t1.Status == nil || *t1.Status != gen.NeedEdit {
		gotStatus := "<nil>"
		if t1.Status != nil {
			gotStatus = string(*t1.Status)
		}
		t.Errorf("torrents[1].Status: got %q, want %q", gotStatus, gen.NeedEdit)
	}
}

// TestParseSearchPage_StatusDefaultsToChecking pins the search-specific
// semantic divergence from the forum scraper: a row that carries no
// `.tor-*` class MUST surface Status = "Checking", not nil. This is the
// `parseTorrentStatus(element) ?: TorrentStatusDto.Checking` branch from
// GetSearchPageUseCase.parseSearchPage. THIS IS A LOAD-BEARING USER-
// VISIBLE BEHAVIOR — confusing "Checking" with "Topic" would mis-classify
// torrents on the wire. (Falsifiability rehearsal target — see commit body.)
func TestParseSearchPage_StatusDefaultsToChecking(t *testing.T) {
	html := loadSearchFixture(t, "search_no_status.html")
	out, err := ParseSearchPage(html)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if got := len(out.Torrents); got != 1 {
		t.Fatalf("expected 1 torrent, got %d", got)
	}
	tor := out.Torrents[0]
	if tor.Status == nil {
		t.Fatal("torrents[0].Status: got nil, want \"Checking\" (search defaults missing status to Checking)")
	}
	if *tor.Status != gen.Checking {
		t.Errorf("torrents[0].Status: got %q, want %q (search defaults missing status to Checking, NOT Approved/etc.)", *tor.Status, gen.Checking)
	}
}

func TestParseSearchPage_Empty(t *testing.T) {
	html := loadSearchFixture(t, "search_empty.html")
	out, err := ParseSearchPage(html)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseSearchPage returned nil")
	}
	if got := len(out.Torrents); got != 0 {
		t.Errorf("expected 0 torrents, got %d", got)
	}
}

func TestParseSearchPage_DateAbsent(t *testing.T) {
	html := loadSearchFixture(t, "search_no_date.html")
	out, err := ParseSearchPage(html)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if got := len(out.Torrents); got != 1 {
		t.Fatalf("expected 1 torrent, got %d", got)
	}
	tor := out.Torrents[0]
	if tor.Date != nil {
		t.Errorf("torrents[0].Date: got %d, want nil (no [style][data-ts_text] in row)", *tor.Date)
	}
}

// TestParseSearchPage_SeedsOverflowAbsent pins the int32-clamp guarantee.
// 9999999999 > math.MaxInt32; the parser MUST surface Seeds/Leeches as
// nil, never a silently-wrapped negative int32. Same pattern as the
// forum scraper's clamp test (commit 4fca329).
func TestParseSearchPage_SeedsOverflowAbsent(t *testing.T) {
	html := []byte(`<!DOCTYPE html><html><body>
<div id="main_content_wrap"><div class="bottom_info"><div class="nav"><p><b>1</b> <b>1</b></p></div></div>
<table><tr class="hl-tr"><td>
  <span class="tor-approved">approved</span>
  <span class="t-title"><a data-topic_id="t9001">Overflow Row</a></span>
  <span class="u-name"><a href="profile.php?pid=42">alice</a></span>
  <a class="f" href="tracker.php?f=7">Movies</a>
  <span class="tor-size" data-ts_text="1024">1 KB</span>
  <span style="color:#aaa" data-ts_text="1700000000">date</span>
  <span class="seedmed">9999999999</span>
  <span class="leechmed">9999999999</span>
</td></tr></table></div></body></html>`)
	out, err := ParseSearchPage(html)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if got := len(out.Torrents); got != 1 {
		t.Fatalf("expected 1 torrent, got %d", got)
	}
	tor := out.Torrents[0]
	if tor.Seeds != nil {
		t.Errorf("Seeds: got %d, want nil (out-of-int32 upstream value MUST be absent, NOT wrapped)", *tor.Seeds)
	}
	if tor.Leeches != nil {
		t.Errorf("Leeches: got %d, want nil (out-of-int32 upstream value MUST be absent, NOT wrapped)", *tor.Leeches)
	}
}

// TestFormatSize_Cases pins the byte-count → human string formatting,
// including the locale-independent `.` decimal separator. Kotlin's
// String.format relies on the JVM default locale; Go's fmt.Sprintf does
// not. A `,` separator showing up in production would break parity tests
// against the Ktor proxy and confuse users.
func TestFormatSize_Cases(t *testing.T) {
	cases := []struct {
		in   int64
		want string
	}{
		{0, "0 B"},
		{1023, "1023 B"},
		{1024, "1.0 KB"},
		{1536, "1.5 KB"},
		{1048576, "1.0 MB"},
		{1073741824, "1.0 GB"},
		{1099511627776, "1.0 TB"},
	}
	for _, tc := range cases {
		t.Run(fmt.Sprintf("size=%d", tc.in), func(t *testing.T) {
			if got := formatSize(tc.in); got != tc.want {
				t.Errorf("formatSize(%d): got %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

// TestGetSearchPage_BuildsUpstreamURL checks the full upstream URL shape:
// path, every parameter mapping, and the 50-results-per-page math. Sixth
// Law clause 1: same surface (Client.GetSearchPage) the Phase 7 handler
// will call. Distinct, recognisable values for Categories/Author/AuthorID
// pin the f/pn/pid plumbing too — a swap between those three in
// Client.GetSearchPage would otherwise ship silently.
func TestGetSearchPage_BuildsUpstreamURL(t *testing.T) {
	fixture := loadSearchFixture(t, "search_results.html")
	var captured *url.URL
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u := *r.URL
		captured = &u
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	q := strPtr("foo")
	cats := strPtr("9")
	author := strPtr("alice")
	authorID := strPtr("42")
	page := intPtr(2)
	st := sortTypePtr(gen.SearchSortTypeDtoSize)
	so := sortOrderPtr(gen.Descending)
	per := periodPtr(gen.LastWeek)
	_, err := c.GetSearchPage(context.Background(), SearchOpts{
		Query:      q,
		Categories: cats,
		Author:     author,
		AuthorID:   authorID,
		Page:       page,
		SortType:   st,
		SortOrder:  so,
		Period:     per,
	}, "tok-test")
	if err != nil {
		t.Fatalf("GetSearchPage error: %v", err)
	}
	if captured == nil {
		t.Fatal("captured URL is nil")
	}
	if captured.Path != "/tracker.php" {
		t.Errorf("Path: got %q, want \"/tracker.php\"", captured.Path)
	}
	got := captured.Query()
	checks := map[string]string{
		"nm":    "foo",
		"f":     "9",     // Categories
		"pn":    "alice", // Author
		"pid":   "42",    // AuthorID
		"o":     "7",     // Size
		"s":     "2",     // Descending
		"tm":    "7",     // LastWeek
		"start": "50",    // page=2 → 50*(2-1)
	}
	for k, want := range checks {
		if g := got.Get(k); g != want {
			t.Errorf("query[%q]: got %q, want %q", k, g, want)
		}
	}
}

// TestGetSearchPage_NilParamsOmitted ensures every nil field in
// SearchOpts is omitted from the upstream query string — `nm` absent,
// not "nm=".
func TestGetSearchPage_NilParamsOmitted(t *testing.T) {
	fixture := loadSearchFixture(t, "search_empty.html")
	var captured *url.URL
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		u := *r.URL
		captured = &u
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.GetSearchPage(context.Background(), SearchOpts{}, "tok-test")
	if err != nil {
		t.Fatalf("GetSearchPage error: %v", err)
	}
	if captured == nil {
		t.Fatal("captured URL is nil")
	}
	q := captured.Query()
	for _, k := range []string{"nm", "f", "pn", "pid", "o", "s", "tm", "start"} {
		if q.Has(k) {
			t.Errorf("query[%q]: should be ABSENT for nil-params call, got %q", k, q.Get(k))
		}
	}
	if captured.Path != "/tracker.php" {
		t.Errorf("Path: got %q, want \"/tracker.php\"", captured.Path)
	}
}

// --- helpers ----------------------------------------------------------

func strPtr(s string) *string                                    { return &s }
func intPtr(i int) *int                                          { return &i }
func sortTypePtr(t gen.SearchSortTypeDto) *gen.SearchSortTypeDto { return &t }
func sortOrderPtr(o gen.SearchSortOrderDto) *gen.SearchSortOrderDto {
	return &o
}
func periodPtr(p gen.SearchPeriodDto) *gen.SearchPeriodDto { return &p }
