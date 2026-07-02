package nnmclub

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"golang.org/x/net/html/charset"
)

// decodeWin1251 transcodes a raw windows-1251 fixture to UTF-8 the same way the
// production client does (Client.Fetch → readBodyDecoded → charset.NewReader).
// ParseTopicPage always receives already-decoded UTF-8 in production, so the
// direct-parse test must feed it UTF-8 too — otherwise the Cyrillic assertions
// would be testing mojibake, not the real user-visible description.
func decodeWin1251(t *testing.T, raw []byte) []byte {
	t.Helper()
	r, err := charset.NewReader(bytes.NewReader(raw), "text/html; charset=windows-1251")
	if err != nil {
		t.Fatalf("decode win-1251: %v", err)
	}
	out, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("read decoded: %v", err)
	}
	return out
}

// TestParseTopicPage_HappyPath drives ParseTopicPage against a REAL nnmclub.to
// topic page (testdata/topic/topic_real.html, viewtopic.php?t=1780489, captured
// anonymously 2026-07-02, windows-1251). The load-bearing assertion is on the
// user-visible DESCRIPTION: nnmclub's real page has NO #pagecontent element and
// its #contentdiv is the hidden (display:none) file-list spoiler — the opening
// post's description lives in the first div.postbody. The prior selector
// "#pagecontent .postbody" matched nothing on every real page, so every user saw
// an empty description.
func TestParseTopicPage_HappyPath(t *testing.T) {
	html := decodeWin1251(t, loadFixture(t, "topic", "topic_real.html"))
	out, err := ParseTopicPage(html, "1780489")
	if err != nil {
		t.Fatalf("ParseTopicPage error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseTopicPage returned nil")
	}
	if out.ID != "1780489" {
		t.Errorf("ID: got %q, want \"1780489\"", out.ID)
	}
	if !strings.Contains(out.Title, "Учебная группа") {
		t.Errorf("Title: got %q, want it to contain \"Учебная группа\"", out.Title)
	}

	// Primary user-visible assertion: the description must be the real, readable
	// opening-post body, not empty and not a stub. A real nnmclub release post
	// carries the "Описание:" and "Производство:" labels; asserting on those
	// proves div.postbody was resolved AND correctly decoded from win-1251.
	if strings.TrimSpace(out.Description) == "" {
		t.Fatal("Description: expected the real opening-post body, got EMPTY " +
			"(selector missed the description container)")
	}
	if !strings.Contains(out.Description, "Описание") {
		t.Errorf("Description: expected it to contain the \"Описание\" label from "+
			"the real post body; got %d chars: %.120q", len(out.Description), out.Description)
	}
	if !strings.Contains(out.Description, "Производство") {
		t.Errorf("Description: expected it to contain \"Производство\"; got %.120q", out.Description)
	}
	if n := len([]rune(out.Description)); n < 500 {
		t.Errorf("Description: expected the rich opening post (>=500 runes), got %d runes: %.120q", n, out.Description)
	}

	if !strings.HasPrefix(out.MagnetLink, "magnet:?xt=urn:btih:") {
		t.Errorf("MagnetLink: got %q, want a magnet:?xt=urn:btih: link", out.MagnetLink)
	}
	if out.MagnetLink != "magnet:?xt=urn:btih:019A7A7CB1026983758D8B7C3CA9DAF200D248D3" {
		t.Errorf("MagnetLink: got %q, want the real btih from the fixture", out.MagnetLink)
	}
	if !strings.Contains(out.DownloadURL, "download.php?id=1358478") {
		t.Errorf("DownloadURL: got %q, want it to contain \"download.php?id=1358478\"", out.DownloadURL)
	}
}

// TestGetTopicPage_RealPipeline drives the FULL production path
// (Client.GetTopicPage → Fetch → readBodyDecoded win-1251→UTF-8 → ParseTopicPage)
// against the real fixture served with a windows-1251 Content-Type, exactly like
// nnmclub.to serves it. It asserts both the upstream URL construction AND that a
// real user reaches a non-empty, readable description through the whole stack.
func TestGetTopicPage_RealPipeline(t *testing.T) {
	fixture := loadFixture(t, "topic", "topic_real.html")
	var captured *http.Request
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		captured = r
		w.Header().Set("Content-Type", "text/html; charset=windows-1251")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetTopicPage(context.Background(), "1780489", 2, "")
	if err != nil {
		t.Fatalf("GetTopicPage error: %v", err)
	}
	if captured == nil {
		t.Fatal("captured request is nil")
	}
	if captured.URL.Path != "/forum/viewtopic.php" {
		t.Errorf("Path: got %q, want \"/forum/viewtopic.php\"", captured.URL.Path)
	}
	q := captured.URL.Query()
	if q.Get("t") != "1780489" {
		t.Errorf("t: got %q, want \"1780489\"", q.Get("t"))
	}
	if q.Get("start") != "50" {
		t.Errorf("start: got %q, want \"50\"", q.Get("start"))
	}

	// Full-pipeline user-visible outcome: the real description reaches the caller,
	// decoded and non-empty.
	if strings.TrimSpace(out.Description) == "" {
		t.Fatal("Description via full pipeline: expected non-empty, got EMPTY")
	}
	if !strings.Contains(out.Description, "Описание") {
		t.Errorf("Description via full pipeline: expected \"Описание\" label; got %.120q", out.Description)
	}
	if !strings.Contains(out.Title, "Учебная группа") {
		t.Errorf("Title via full pipeline: got %q", out.Title)
	}
}
