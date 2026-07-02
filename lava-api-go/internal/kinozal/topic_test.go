package kinozal

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// decodeWin1251 is defined once in search_test.go (same package) — it transcodes a
// raw windows-1251 kinozal page to UTF-8, mirroring the production readBodyDecoded
// transcode so the Cyrillic assertions run against real UTF-8, not mojibake.

func firstN(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n])
}

// TestParseTopicPage is the reproduce-first regression for the device-proven
// TopicPagingDataLoader MissingFieldException on kinozal topics. The topic
// response carried an empty description / no poster / no download affordance
// because ParseTopicPage used SYNTHETIC selectors (div.content, a.magnet) that do
// not exist on a real kinozal /details.php page. The fixture is a real captured
// anonymous /details.php?id=2145735 page (win-1251).
//
// FALSIFIABILITY (rehearsed 2026-07-02): run this test against the pre-fix parser
// (div.content description, no img.p200 poster, no derived download route) and it
// FAILS on Description ("missing synopsis (О фильме); got \"\"") + PosterURL +
// DownloadURL. After the fix it is GREEN. See the .md report for captured output.
func TestParseTopicPage(t *testing.T) {
	html := decodeWin1251(t, loadTestData("topic/topic.html"))
	result, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}

	const wantTitle = "Аферисты / 2007 / РУ, СТ / WEB-DLRip (1080p)"
	if result.Title != wantTitle {
		t.Errorf("Title = %q, want %q", result.Title, wantTitle)
	}
	if result.ID != "2145735" {
		t.Errorf("ID = %q, want 2145735", result.ID)
	}
	// Real synopsis lives in div.bx1 (NOT div.content, which is absent on
	// kinozal). Empty ⇒ the parser is reading the wrong block.
	if !strings.Contains(result.Description, "О фильме") {
		t.Errorf("Description missing synopsis (О фильме); got %q", firstN(result.Description, 80))
	}
	// Download affordance: kinozal exposes no magnet on the details page; the
	// torrent is fetched via /download.php?id=<id>. An empty DownloadURL means the
	// topic screen renders with no working download button (the user-visible
	// defect this fix addresses).
	if result.DownloadURL != "/download.php?id=2145735" {
		t.Errorf("DownloadURL = %q, want /download.php?id=2145735", result.DownloadURL)
	}
	// Poster from img.p200 — real content, empty with the old selectors.
	if !strings.Contains(result.PosterURL, "fastpic") {
		t.Errorf("PosterURL = %q, want the img.p200 poster src", result.PosterURL)
	}
}

// TestClientGetTopic drives the REAL production path end-to-end: Client.GetTopic →
// Fetch → readBodyDecoded (win-1251 → UTF-8 transcode, driven by the charset
// header kinozal.tv sends) → ParseTopicPage. Asserting the decoded Cyrillic title
// proves the transcode + parse chain works on a real page, not just a synthetic
// ASCII one.
func TestClientGetTopic(t *testing.T) {
	raw := loadTestData("topic/topic.html") // real win-1251 bytes
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/details.php" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.URL.Query().Get("id") != "2145735" {
			t.Errorf("unexpected id: %s", r.URL.Query().Get("id"))
		}
		// kinozal.tv serves details.php as windows-1251; the charset header drives
		// the production readBodyDecoded transcode.
		w.Header().Set("Content-Type", "text/html; charset=windows-1251")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(raw)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.GetTopic(context.Background(), "2145735", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.ID != "2145735" {
		t.Errorf("expected id 2145735, got %s", result.ID)
	}
	const wantTitle = "Аферисты / 2007 / РУ, СТ / WEB-DLRip (1080p)"
	if result.Title != wantTitle {
		t.Errorf("Title = %q, want %q", result.Title, wantTitle)
	}
	if result.DownloadURL != "/download.php?id=2145735" {
		t.Errorf("DownloadURL = %q, want /download.php?id=2145735", result.DownloadURL)
	}
}

// TestParseTopicPage_PrefersH1OverTitleTag closes a discrimination gap surfaced
// by the 2026-06-13 §6.N go-parsers bluff hunt. ParseTopicPage reads the topic
// title from <h1> first and only falls back to the <title> tag when <h1> is
// empty (topic.go). It also confirms the generic div.content / a.magnet
// fallbacks still fire for non-kinozal / synthetic pages (the real kinozal path
// is covered by TestParseTopicPage above).
//
// FALSIFIABILITY (rehearsed 2026-06-13): replacing the h1-first block with a
// straight doc.Find("title") read makes Title = the site-suffixed string → this
// test FAILS with `Title = "Inception 2010 BDRip :: Kinozal.TV", want clean <h1>`.
// Reverting restores green.
func TestParseTopicPage_PrefersH1OverTitleTag(t *testing.T) {
	const html = `<html>
<head><title>Inception 2010 BDRip :: Kinozal.TV</title></head>
<body>
  <h1>Inception 2010 BDRip</h1>
  <a href="magnet:?xt=urn:btih:FACEFEED0001" class="magnet">Magnet</a>
  <div class="content">A thief who steals corporate secrets.</div>
  <a href="details.php?id=98765">self</a>
</body>
</html>`
	result, err := ParseTopicPage([]byte(html))
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if result.Title != "Inception 2010 BDRip" {
		t.Errorf("Title = %q, want clean <h1> text \"Inception 2010 BDRip\" (not the <title> site-suffixed string)", result.Title)
	}
	if result.MagnetLink != "magnet:?xt=urn:btih:FACEFEED0001" {
		t.Errorf("MagnetLink = %q, want the magnet href", result.MagnetLink)
	}
	if result.Description != "A thief who steals corporate secrets." {
		t.Errorf("Description = %q, want the div.content fallback text", result.Description)
	}
	if result.ID != "98765" {
		t.Errorf("ID = %q, want 98765 (parsed from details.php?id=)", result.ID)
	}
}
