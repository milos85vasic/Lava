package rutracker

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"
)

// loadTopicFixture reads HTML under testdata/topic/. Mirrors loadFixture
// in forum_test.go but for the topic-page synthetic fixtures.
func loadTopicFixture(t *testing.T, name string) []byte {
	t.Helper()
	p := filepath.Join("testdata", "topic", name)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

func TestParseCommentsPage_HappyPath(t *testing.T) {
	html := loadTopicFixture(t, "comments_page1.html")
	out, err := ParseCommentsPage(html)
	if err != nil {
		t.Fatalf("ParseCommentsPage: %v", err)
	}
	if out == nil {
		t.Fatal("ParseCommentsPage returned nil")
	}
	if out.Type != "CommentsPage" {
		t.Errorf("Type: got %q, want \"CommentsPage\"", out.Type)
	}
	if out.Id != "12345" {
		t.Errorf("Id: got %q, want \"12345\"", out.Id)
	}
	if out.Title != "Topic Title" {
		t.Errorf("Title: got %q, want \"Topic Title\"", out.Title)
	}
	if out.Page != 1 {
		t.Errorf("Page: got %d, want 1", out.Page)
	}
	if out.Pages != 2 {
		t.Errorf("Pages: got %d, want 2", out.Pages)
	}
	if out.Category == nil {
		t.Fatal("Category is nil")
	}
	if out.Category.Name != "Drama Forum" {
		t.Errorf("Category.Name: got %q", out.Category.Name)
	}
	if out.Category.Id == nil || *out.Category.Id != "700" {
		gotID := "<nil>"
		if out.Category.Id != nil {
			gotID = *out.Category.Id
		}
		t.Errorf("Category.Id: got %q, want \"700\"", gotID)
	}
	// First post in fixture had a magnet-link → it MUST be removed. Two
	// remaining posts (alice, bob).
	if len(out.Posts) != 2 {
		t.Fatalf("expected 2 posts (magnet-bearing first post removed), got %d", len(out.Posts))
	}
	alice := out.Posts[0]
	if alice.Id != "101" {
		t.Errorf("alice.Id: got %q, want \"101\" (post_body id strip \"p-\" prefix)", alice.Id)
	}
	if alice.Author.Name != "alice" {
		t.Errorf("alice.Author.Name: got %q", alice.Author.Name)
	}
	if alice.Author.Id == nil || *alice.Author.Id != "2" {
		gotID := "<nil>"
		if alice.Author.Id != nil {
			gotID = *alice.Author.Id
		}
		t.Errorf("alice.Author.Id: got %q, want \"2\"", gotID)
	}
	if alice.Date != "2024-01-02 11:00" {
		t.Errorf("alice.Date: got %q", alice.Date)
	}
	if len(alice.Children) == 0 {
		t.Fatalf("alice.Children: got 0, want at least 1")
	}
}

// TestParseCommentsPage_DropsMagnetFirstPost is the load-bearing
// magnet-removal test. The fixture has 3 tbody[id^=post] rows; the first
// carries a .magnet-link[href="magnet:?..."], so the parser MUST yield 2
// posts. A regression that no-ops the .Remove() will produce 3 posts and
// fail this test (see falsifiability rehearsal in the commit body).
func TestParseCommentsPage_DropsMagnetFirstPost(t *testing.T) {
	html := loadTopicFixture(t, "comments_page1.html")
	out, err := ParseCommentsPage(html)
	if err != nil {
		t.Fatalf("ParseCommentsPage: %v", err)
	}
	if got := len(out.Posts); got != 2 {
		t.Fatalf("expected 2 posts (magnet-bearing first post MUST be dropped), got %d", got)
	}
	// The remaining posts are alice + bob (NOT opUser, which is the OP
	// torrent-bearing post that was dropped).
	for _, p := range out.Posts {
		if p.Author.Name == "opUser" {
			t.Errorf("OP user (opUser, the magnet-bearing first post) MUST NOT appear in comments list, but found post id %q", p.Id)
		}
	}
}

// TestParseCommentsPage_NoMagnet — when no magnet link is present, all
// posts including the OP MUST appear in the comments list.
func TestParseCommentsPage_NoMagnet(t *testing.T) {
	html := loadTopicFixture(t, "comments_no_magnet.html")
	out, err := ParseCommentsPage(html)
	if err != nil {
		t.Fatalf("ParseCommentsPage: %v", err)
	}
	if got := len(out.Posts); got != 2 {
		t.Fatalf("expected 2 posts (NO magnet so first post is kept), got %d", got)
	}
	if out.Posts[0].Author.Name != "first" {
		t.Errorf("first post author: got %q, want \"first\"", out.Posts[0].Author.Name)
	}
}

func TestGetCommentsPage_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTopicFixture(t, "topic_not_found.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetCommentsPage(context.Background(), "999", nil, "")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
	if out != nil {
		t.Errorf("expected nil result, got %#v", out)
	}
}

func TestGetCommentsPage_Forbidden(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTopicFixture(t, "topic_forbidden.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetCommentsPage(context.Background(), "888", nil, "")
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("expected ErrForbidden, got %v", err)
	}
	if out != nil {
		t.Errorf("expected nil result, got %#v", out)
	}
}

// TestGetCommentsPage_BuildsURL pins the 30-posts-per-page URL math —
// page=2 → start=30, page=1/nil → no start parameter.
func TestGetCommentsPage_BuildsURL(t *testing.T) {
	cases := []struct {
		name      string
		page      *int
		wantStart string
	}{
		{"no page", nil, ""},
		{"page=1", ptrInt(1), ""},
		{"page=2", ptrInt(2), "30"},
		{"page=3", ptrInt(3), "60"},
	}
	fixture := loadTopicFixture(t, "comments_page1.html")
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var captured url.Values
			var capturedPath string
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				captured = r.URL.Query()
				capturedPath = r.URL.Path
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				_, _ = w.Write(fixture)
			}))
			defer srv.Close()
			c := NewClient(srv.URL)
			_, err := c.GetCommentsPage(context.Background(), "123", tc.page, "")
			if err != nil {
				t.Fatalf("GetCommentsPage: %v", err)
			}
			if capturedPath != "/viewtopic.php" {
				t.Errorf("path: got %q, want \"/viewtopic.php\"", capturedPath)
			}
			if got := captured.Get("t"); got != "123" {
				t.Errorf("t: got %q, want \"123\"", got)
			}
			if tc.wantStart == "" {
				if captured.Has("start") {
					t.Errorf("start should be ABSENT for %s, got %q", tc.name, captured.Get("start"))
				}
				return
			}
			if got := captured.Get("start"); got != tc.wantStart {
				t.Errorf("start: got %q, want %q", got, tc.wantStart)
			}
		})
	}
}
