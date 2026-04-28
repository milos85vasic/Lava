package rutracker

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// loadTorrentFixture reads HTML under testdata/torrent/. Sibling of
// loadTopicFixture in comments_test.go.
func loadTorrentFixture(t *testing.T, name string) []byte {
	t.Helper()
	p := filepath.Join("testdata", "torrent", name)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

func TestParseTorrent_HappyPath(t *testing.T) {
	html := loadTorrentFixture(t, "torrent_full.html")
	out, err := ParseTorrent(html)
	if err != nil {
		t.Fatalf("ParseTorrent: %v", err)
	}
	if out == nil {
		t.Fatal("nil result")
	}
	if out.Type != gen.Torrent {
		t.Errorf("Type: got %q, want %q", out.Type, gen.Torrent)
	}
	if out.Id != "42" {
		t.Errorf("Id: got %q, want \"42\"", out.Id)
	}
	if out.Title != "Movie Name" {
		t.Errorf("Title: got %q, want \"Movie Name\" (getTitle MUST strip [Genre] and [1080p])", out.Title)
	}
	if out.Tags == nil {
		t.Fatal("Tags: nil; want concatenated brackets")
	}
	tags := *out.Tags
	if !strings.Contains(tags, "[Genre]") || !strings.Contains(tags, "[1080p]") {
		t.Errorf("Tags: got %q, want to contain [Genre] AND [1080p]", tags)
	}
	if out.Author == nil {
		t.Fatal("Author: nil")
	}
	if out.Author.Name != "alice" {
		t.Errorf("Author.Name: got %q, want \"alice\"", out.Author.Name)
	}
	if out.Author.Id == nil || *out.Author.Id != "42" {
		gotID := "<nil>"
		if out.Author.Id != nil {
			gotID = *out.Author.Id
		}
		t.Errorf("Author.Id: got %q, want \"42\"", gotID)
	}
	if out.Category == nil {
		t.Fatal("Category: nil")
	}
	if out.Category.Id == nil || *out.Category.Id != "700" {
		t.Errorf("Category.Id: got %v, want \"700\"", out.Category.Id)
	}
	if out.Category.Name != "Drama Category" {
		t.Errorf("Category.Name: got %q, want \"Drama Category\"", out.Category.Name)
	}
	if out.Status == nil || *out.Status != "Approved" {
		gotStatus := "<nil>"
		if out.Status != nil {
			gotStatus = string(*out.Status)
		}
		t.Errorf("Status: got %q, want \"Approved\"", gotStatus)
	}
	if out.Size == nil || *out.Size != "1.5 GB" {
		gotSize := "<nil>"
		if out.Size != nil {
			gotSize = *out.Size
		}
		t.Errorf("Size: got %q, want \"1.5 GB\" (logged-in path → #tor-size-humn)", gotSize)
	}
	if out.Seeds == nil || *out.Seeds != 123 {
		t.Errorf("Seeds: got %v, want 123", out.Seeds)
	}
	if out.Leeches == nil || *out.Leeches != 4 {
		t.Errorf("Leeches: got %v, want 4", out.Leeches)
	}
	if out.MagnetLink == nil || !strings.HasPrefix(*out.MagnetLink, "magnet:?") {
		gotML := "<nil>"
		if out.MagnetLink != nil {
			gotML = *out.MagnetLink
		}
		t.Errorf("MagnetLink: got %q, want magnet:? prefix", gotML)
	}
	if out.Description == nil {
		t.Fatal("Description: nil")
	}
	if len(out.Description.Children) == 0 {
		t.Fatal("Description.Children: 0, want >0")
	}
	// First description child MUST be the .post_body's text "This is the description".
	first := out.Description.Children[0]
	textPart, err := first.AsPostElementText()
	if err != nil {
		t.Fatalf("first description child: AsPostElementText: %v (got non-text type)", err)
	}
	if !strings.Contains(textPart.Value, "This is the description") {
		t.Errorf("first description child value: got %q, want to contain \"This is the description\"", textPart.Value)
	}
	// Sixth Law clause 1 — Date must remain nil (Kotlin parser does NOT
	// populate the OpenAPI ForumTopicDtoTorrent.Date int64 field).
	if out.Date != nil {
		t.Errorf("Date: got %v, want nil (Kotlin parser leaves Date unset on /torrent/{id})", *out.Date)
	}
}

func TestParseTorrent_AnonymousSizeFromAttachLink(t *testing.T) {
	html := loadTorrentFixture(t, "torrent_anon.html")
	// Belt-and-braces: the fixture MUST NOT contain logged-in-username,
	// otherwise ParseTorrent would take the wrong size branch.
	if strings.Contains(string(html), "logged-in-username") {
		t.Fatal("fixture is broken: torrent_anon.html must NOT contain logged-in-username substring")
	}
	out, err := ParseTorrent(html)
	if err != nil {
		t.Fatalf("ParseTorrent: %v", err)
	}
	if out.Size == nil || *out.Size != "2.7 GB" {
		gotSize := "<nil>"
		if out.Size != nil {
			gotSize = *out.Size
		}
		t.Errorf("Size: got %q, want \"2.7 GB\" (NOT logged-in → .attach_link path)", gotSize)
	}
}

func TestParseTorrent_NoAuthor(t *testing.T) {
	html := loadTorrentFixture(t, "torrent_no_author.html")
	out, err := ParseTorrent(html)
	if err != nil {
		t.Fatalf("ParseTorrent: %v", err)
	}
	if out.Author != nil {
		t.Errorf("Author: got %+v, want nil (empty .nick → Author MUST be nil)", out.Author)
	}
}

func TestParseTorrent_NoSeedsLeeches(t *testing.T) {
	html := loadTorrentFixture(t, "torrent_no_seeds_leeches.html")
	out, err := ParseTorrent(html)
	if err != nil {
		t.Fatalf("ParseTorrent: %v", err)
	}
	if out.Seeds != nil {
		t.Errorf("Seeds: got %v, want nil (empty .seed > b → Seeds MUST be nil)", *out.Seeds)
	}
	if out.Leeches != nil {
		t.Errorf("Leeches: got %v, want nil (empty .leech > b → Leeches MUST be nil)", *out.Leeches)
	}
}

func TestParseTorrent_DescriptionEmptyOnNoPostBody(t *testing.T) {
	html := loadTorrentFixture(t, "torrent_empty_description.html")
	out, err := ParseTorrent(html)
	if err != nil {
		t.Fatalf("ParseTorrent: %v", err)
	}
	if out.Description == nil {
		t.Fatal("Description: nil; want non-nil with empty Children")
	}
	if len(out.Description.Children) != 0 {
		t.Errorf("Description.Children: got %d, want 0 (no .post_body inside first post)", len(out.Description.Children))
	}
}

// TestParseTorrent_DescriptionEmptyOnMalformedHtml pins the Kotlin
// try/catch fallback shape: ParseTorrentUseCase.parseTorrentDescription
// swallows any throwable and returns an empty Children list. We mirror
// the defensive shape — adversarial inputs MUST NOT panic and MUST
// surface as an empty slice.
func TestParseTorrent_DescriptionEmptyOnMalformedHtml(t *testing.T) {
	cases := [][]byte{
		[]byte(""),
		[]byte("not html at all"),
		[]byte("<html><body><tbody id=\"post1\"></tbody></body></html>"), // post but no .post_body
		[]byte("\xff\xfe\xfd"),
	}
	for i, html := range cases {
		out := parseTorrentDescription(html)
		if out.Children == nil {
			t.Errorf("case %d: Children: nil; want non-nil empty slice", i)
		}
		if len(out.Children) != 0 {
			t.Errorf("case %d: Children: got %d entries, want 0", i, len(out.Children))
		}
	}
}

func TestGetTorrent_HappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTorrentFixture(t, "torrent_full.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTorrent(context.Background(), "42", "")
	if err != nil {
		t.Fatalf("GetTorrent: %v", err)
	}
	if out == nil {
		t.Fatal("nil result")
	}
	if out.Id != "42" {
		t.Errorf("Id: got %q, want \"42\"", out.Id)
	}
	if out.Title != "Movie Name" {
		t.Errorf("Title: got %q, want \"Movie Name\"", out.Title)
	}
}

func TestGetTorrent_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTorrentFixture(t, "torrent_not_found.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTorrent(context.Background(), "999", "")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("err: got %v, want ErrNotFound", err)
	}
	if out != nil {
		t.Errorf("expected nil result, got %+v", out)
	}
}

func TestGetTorrent_Forbidden(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTorrentFixture(t, "torrent_forbidden.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTorrent(context.Background(), "888", "")
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("err: got %v, want ErrForbidden", err)
	}
	if out != nil {
		t.Errorf("expected nil result")
	}
}

func TestGetTorrent_BlockedForRegion(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTorrentFixture(t, "torrent_blocked.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTorrent(context.Background(), "777", "")
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("err: got %v, want ErrForbidden (region-blocked maps to Forbidden)", err)
	}
	if out != nil {
		t.Errorf("expected nil result")
	}
}

func TestGetTorrent_BuildsURL(t *testing.T) {
	const wantCookie = "bb_session=secret"
	var (
		gotPath   string
		gotT      string
		gotCookie string
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotT = r.URL.Query().Get("t")
		gotCookie = r.Header.Get("Cookie")
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTorrentFixture(t, "torrent_full.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	if _, err := c.GetTorrent(context.Background(), "42", wantCookie); err != nil {
		t.Fatalf("GetTorrent: %v", err)
	}
	if gotPath != "/viewtopic.php" {
		t.Errorf("path: got %q, want \"/viewtopic.php\"", gotPath)
	}
	if gotT != "42" {
		t.Errorf("t: got %q, want \"42\"", gotT)
	}
	if gotCookie != wantCookie {
		t.Errorf("Cookie: got %q, want %q", gotCookie, wantCookie)
	}
}

func TestGetTorrentFile_HappyPath(t *testing.T) {
	const (
		wantDisp = `attachment; filename="rutracker_42.torrent"`
		wantType = "application/x-bittorrent"
	)
	wantBytes := []byte{0x64, 0x38, 0x3a, 0x61, 0x6e, 0x6e, 0x6f, 0x75, 0x6e, 0x63, 0x65, 0x65}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Disposition", wantDisp)
		w.Header().Set("Content-Type", wantType)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(wantBytes)
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTorrentFile(context.Background(), "42", "bb_session=ok")
	if err != nil {
		t.Fatalf("GetTorrentFile: %v", err)
	}
	if out == nil {
		t.Fatal("nil result")
	}
	if out.ContentDisposition != wantDisp {
		t.Errorf("ContentDisposition: got %q, want %q", out.ContentDisposition, wantDisp)
	}
	if out.ContentType != wantType {
		t.Errorf("ContentType: got %q, want %q", out.ContentType, wantType)
	}
	if string(out.Bytes) != string(wantBytes) {
		t.Errorf("Bytes: got %x, want %x", out.Bytes, wantBytes)
	}
}

// TestGetTorrentFile_EmptyCookie_ErrUnauthorized verifies the empty-
// cookie short-circuit fires BEFORE any upstream traffic. The counter
// MUST stay at 0 — otherwise the precheck is bluffing.
func TestGetTorrentFile_EmptyCookie_ErrUnauthorized(t *testing.T) {
	var hits atomic.Int64
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTorrentFile(context.Background(), "42", "")
	if !errors.Is(err, ErrUnauthorized) {
		t.Fatalf("err: got %v, want ErrUnauthorized", err)
	}
	if out != nil {
		t.Errorf("expected nil TorrentFile, got %+v", out)
	}
	if got := hits.Load(); got != 0 {
		t.Errorf("upstream hits: got %d, want 0 (empty-cookie precheck MUST short-circuit)", got)
	}
}

func TestGetTorrentFile_404_ErrNotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTorrentFile(context.Background(), "999", "bb_session=ok")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("err: got %v, want ErrNotFound", err)
	}
	if out != nil {
		t.Errorf("expected nil result")
	}
}

func TestGetTorrentFile_BuildsURL(t *testing.T) {
	const wantCookie = "bb_session=secret"
	var (
		gotPath   string
		gotT      string
		gotCookie string
	)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotT = r.URL.Query().Get("t")
		gotCookie = r.Header.Get("Cookie")
		w.Header().Set("Content-Type", "application/x-bittorrent")
		w.Header().Set("Content-Disposition", `attachment; filename="x.torrent"`)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte{0x64})
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	if _, err := c.GetTorrentFile(context.Background(), "42", wantCookie); err != nil {
		t.Fatalf("GetTorrentFile: %v", err)
	}
	if gotPath != "/dl.php" {
		t.Errorf("path: got %q, want \"/dl.php\"", gotPath)
	}
	if gotT != "42" {
		t.Errorf("t: got %q, want \"42\"", gotT)
	}
	if gotCookie != wantCookie {
		t.Errorf("Cookie: got %q, want %q", gotCookie, wantCookie)
	}
}

// TestFetchWithHeaders_Forwards5xxAsError pins the breaker semantics on
// the new FetchWithHeaders helper. 5xx responses MUST surface as a
// non-nil error so the breaker counts them as failures (otherwise a
// flaky upstream returning 500s would never trip the breaker).
//
// Falsifiability rehearsal target: comment out the
// `if resp.StatusCode >= 500 { return fmt.Errorf(...) }` block in
// client.go's FetchWithHeaders; this test MUST then fail with
// "expected error for 500 response, got nil".
func TestFetchWithHeaders_Forwards5xxAsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	_, _, _, err := c.FetchWithHeaders(context.Background(), "/", "")
	if err == nil {
		t.Fatal("expected error for 500 response, got nil")
	}
}
