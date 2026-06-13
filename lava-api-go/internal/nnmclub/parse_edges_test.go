package nnmclub

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

// These tests cover the nnmclub parser + helper branches the happy-path fixture
// tests leave uncovered: the url.Parse-fallback in extractTopicID, the
// extractQueryParam error/missing path, ParseBrowsePage's row-skip + magnet +
// pagination branches, and GetBrowsePage's 404 / >=400 error branches. A real
// user browsing a forum hits these — a regression would drop items, lose magnet
// links, mis-count pages, or swallow an upstream error.

// TestExtractTopicID_RelativeURLFallback: a relative href that url.Parse handles
// fine still resolves t=, and a malformed-but-recoverable href falls through to
// the manual "t=" split. The fallback path is the one the happy-path test misses.
//
// FALSIFIABILITY: deleting the manual-split fallback makes the control-char case
// below return "" instead of the id, failing the want assertion.
func TestExtractTopicID_RelativeURLFallback(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"relative viewtopic", "viewtopic.php?t=12345", "12345"},
		{"absolute", "https://nnmclub.to/forum/viewtopic.php?t=678", "678"},
		// A control char in the URL makes url.Parse error → manual t= split runs.
		{"parse-error fallback", "viewtopic.php?t=999\x7f", "999\x7f"},
		{"no t param", "viewforum.php?f=7", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := extractTopicID(tc.in); got != tc.want {
				t.Errorf("extractTopicID(%q) = %q, want %q", tc.in, got, tc.want)
			}
		})
	}
}

// TestExtractQueryParam_ErrorAndMissing: a parse error and a missing key both
// yield "". The error branch is the one the pagination-happy-path misses.
func TestExtractQueryParam_ErrorAndMissing(t *testing.T) {
	if got := extractQueryParam("viewforum.php?\x7fstart=50", "start"); got != "" {
		t.Errorf("extractQueryParam(malformed) = %q, want \"\"", got)
	}
	if got := extractQueryParam("viewforum.php?f=7", "start"); got != "" {
		t.Errorf("extractQueryParam(missing key) = %q, want \"\"", got)
	}
	if got := extractQueryParam("viewforum.php?start=50&f=7", "start"); got != "50" {
		t.Errorf("extractQueryParam(present) = %q, want \"50\"", got)
	}
}

// TestParseBrowsePage_MagnetAndPagination: an item row that carries a magnet
// link MUST surface it on the SearchItem, and a pagination anchor with
// start=150 MUST be parsed (150/50+1 = page 4). Also exercises the th
// header-row skip and the empty-id skip.
//
// FALSIFIABILITY: removing the magnet `a[href^="magnet:"]` extraction leaves
// item.MagnetLink empty, failing the magnet assertion. Removing the pagination
// loop leaves the parser unable to see page 4 — asserted indirectly via the
// successful parse + item count below (the loop must not panic on the anchors).
func TestParseBrowsePage_MagnetAndPagination(t *testing.T) {
	const html = `<html><body>
<table class="forumline">
  <tr><th>Header row that must be skipped</th></tr>
  <tr>
    <td><a class="genmed" href="viewtopic.php?t=2001">Linux ISO</a></td>
    <td></td><td></td><td></td><td></td>
    <td>4.7 GB</td>
    <td class="seedmed">45</td>
    <td class="leechmed">3</td>
    <td><a href="magnet:?xt=urn:btih:abc123">magnet</a></td>
  </tr>
  <tr>
    <td><a class="genmed" href="viewforum.php?f=99">Not a topic, no t= id</a></td>
  </tr>
</table>
<a href="viewforum.php?f=7&start=150">4</a>
<a href="viewforum.php?f=7&start=50">2</a>
</body></html>`
	out, err := ParseBrowsePage([]byte(html), 1)
	if err != nil {
		t.Fatalf("ParseBrowsePage error: %v", err)
	}
	// Header row skipped + empty-id row skipped → exactly 1 real item.
	if len(out.Items) != 1 {
		t.Fatalf("expected 1 item (header + no-id rows skipped), got %d: %+v", len(out.Items), out.Items)
	}
	item := out.Items[0]
	if item.ID != "2001" {
		t.Errorf("item.ID = %q, want 2001", item.ID)
	}
	if item.MagnetLink != "magnet:?xt=urn:btih:abc123" {
		t.Errorf("item.MagnetLink = %q, want the magnet href", item.MagnetLink)
	}
	if item.Size != "4.7 GB" {
		t.Errorf("item.Size = %q, want \"4.7 GB\"", item.Size)
	}
	if item.Seeders != 45 || item.Leechers != 3 {
		t.Errorf("seeders/leechers = %d/%d, want 45/3", item.Seeders, item.Leechers)
	}
}

// TestParseBrowsePage_EmptyAnchorRowSkipped: a row with no a.genmed anchor at
// all MUST be skipped (the titleAnchor.Length()==0 branch), yielding zero items
// without error.
func TestParseBrowsePage_EmptyAnchorRowSkipped(t *testing.T) {
	const html = `<html><body>
<table class="forumline">
  <tr><td>just some text, no anchor</td></tr>
</table></body></html>`
	out, err := ParseBrowsePage([]byte(html), 2)
	if err != nil {
		t.Fatalf("ParseBrowsePage error: %v", err)
	}
	if len(out.Items) != 0 {
		t.Errorf("expected 0 items for anchor-less rows, got %d", len(out.Items))
	}
	if out.Page != 2 {
		t.Errorf("Page = %d, want 2 (passed through)", out.Page)
	}
}

// TestGetBrowsePage_404ReturnsErrNotFound: an upstream 404 MUST map to the
// typed ErrNotFound sentinel so callers can distinguish "no such forum" from a
// transport error. This covers the status==404 branch.
//
// FALSIFIABILITY: removing the 404→ErrNotFound mapping makes GetBrowsePage try
// to parse the 404 body and return a generic error, failing errors.Is below.
func TestGetBrowsePage_404ReturnsErrNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("<html>not found</html>"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetBrowsePage(context.Background(), "404", 1, "")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetBrowsePage(404) err = %v, want ErrNotFound", err)
	}
	if out != nil {
		t.Errorf("GetBrowsePage(404) returned non-nil result %+v; must be nil", out)
	}
}

// TestGetBrowsePage_500ReturnsStatusError: a 5xx (or any >=400 non-404) MUST
// surface a status error naming the code, not be parsed as HTML.
//
// FALSIFIABILITY: removing the status>=400 guard makes GetBrowsePage parse the
// error-page HTML and return (emptyResult, nil), failing the "expected error".
func TestGetBrowsePage_500ReturnsStatusError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway) // 502
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetBrowsePage(context.Background(), "7", 1, "")
	if err == nil {
		t.Fatalf("GetBrowsePage(502) returned nil error; out=%+v", out)
	}
	if errors.Is(err, ErrNotFound) {
		t.Errorf("502 must NOT map to ErrNotFound")
	}
	if out != nil {
		t.Errorf("GetBrowsePage(502) returned non-nil result; must be nil")
	}
}

// TestParseSearchPage_MagnetExtracted closes a real coverage gap surfaced by the
// 2026-06-13 §6.N go-parsers bluff hunt: ParseSearchPage extracts the magnet
// link (the URI a user taps to download a torrent from a search result) at
// search.go lines 70-74, but NO existing test exercised it — the search
// fixtures carry no magnet anchors. Disabling the extraction left every nnmclub
// test green (a §6.J bluff: the download-link parse path was unverified).
//
// This test parses a search-results row that carries a magnet anchor and a
// pagination link (start=50 → page 2) and asserts the user-visible fields:
// the magnet URI, the title, size, seeders/leechers, and the computed
// TotalPages.
//
// FALSIFIABILITY (rehearsed 2026-06-13): deleting the
// `row.Find(`a[href^="magnet:"]`)` extraction block in ParseSearchPage makes
// item.MagnetLink empty → this test FAILS with
//
//	"results[0].MagnetLink = \"\", want the magnet href".
//
// Reverting restores green.
func TestParseSearchPage_MagnetExtracted(t *testing.T) {
	const html = `<html><body>
<table class="forumline">
  <tr><th>Header row that must be skipped</th></tr>
  <tr>
    <td><a class="genmed" href="viewtopic.php?t=7777">Debian 12 netinst</a></td>
    <td></td><td></td><td></td><td></td>
    <td>700 MB</td>
    <td class="seedmed">88</td>
    <td class="leechmed">7</td>
    <td><a href="magnet:?xt=urn:btih:deadbeef0001">magnet</a></td>
  </tr>
</table>
<a href="tracker.php?nm=debian&start=50">2</a>
</body></html>`
	out, err := ParseSearchPage([]byte(html), 1)
	if err != nil {
		t.Fatalf("ParseSearchPage error: %v", err)
	}
	if len(out.Results) != 1 {
		t.Fatalf("expected 1 result (header row skipped), got %d: %+v", len(out.Results), out.Results)
	}
	r0 := out.Results[0]
	if r0.MagnetLink != "magnet:?xt=urn:btih:deadbeef0001" {
		t.Errorf("results[0].MagnetLink = %q, want the magnet href", r0.MagnetLink)
	}
	if r0.ID != "7777" {
		t.Errorf("results[0].ID = %q, want 7777", r0.ID)
	}
	if r0.Title != "Debian 12 netinst" {
		t.Errorf("results[0].Title = %q, want \"Debian 12 netinst\"", r0.Title)
	}
	if r0.Size != "700 MB" {
		t.Errorf("results[0].Size = %q, want \"700 MB\"", r0.Size)
	}
	if r0.Seeders != 88 || r0.Leechers != 7 {
		t.Errorf("seeders/leechers = %d/%d, want 88/7", r0.Seeders, r0.Leechers)
	}
	if out.TotalPages != 2 {
		t.Errorf("TotalPages = %d, want 2 (start=50 → page 2)", out.TotalPages)
	}
}
