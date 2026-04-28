package rutracker

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
)

// loadFixture reads a golden HTML file under testdata/forum/. The test data
// files are checked in and are NOT real rutracker.org pages — Phase 10's
// cross-backend parity test is the live double-check against the real
// upstream. The fixtures here mirror the exact selector walk the parsers
// take, so a regression in either the selectors or the fixture diverges
// detectably from the expected user-visible output.
func loadFixture(t *testing.T, name string) []byte {
	t.Helper()
	p := filepath.Join("testdata", "forum", name)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read fixture %s: %v", name, err)
	}
	return b
}

func TestParseForum_Tree(t *testing.T) {
	html := loadFixture(t, "forum_tree.html")
	out, err := ParseForum(html)
	if err != nil {
		t.Fatalf("ParseForum returned error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseForum returned nil ForumDto")
	}
	if got, want := len(out.Children), 2; got != want {
		t.Fatalf("expected %d categories, got %d", want, got)
	}
	moviesCat := out.Children[0]
	if moviesCat.Name != "Movies" {
		t.Errorf("first category name: got %q, want %q", moviesCat.Name, "Movies")
	}
	if moviesCat.Children == nil {
		t.Fatalf("first category has nil Children")
	}
	if got, want := len(*moviesCat.Children), 2; got != want {
		t.Fatalf("expected %d forums in first category, got %d", want, got)
	}
	foreign := (*moviesCat.Children)[0]
	if foreign.Name != "Foreign Cinema" {
		t.Errorf("first forum name: got %q, want %q", foreign.Name, "Foreign Cinema")
	}
	if foreign.Id == nil {
		t.Fatalf("first forum has nil Id")
	}
	if got, want := *foreign.Id, "viewforum.php?f=100"; got != want {
		t.Errorf("first forum id: got %q, want %q", got, want)
	}
	if foreign.Children == nil {
		t.Fatalf("first forum has nil subforums")
	}
	if got, want := len(*foreign.Children), 2; got != want {
		t.Fatalf("expected %d subforums, got %d", want, got)
	}
	drama := (*foreign.Children)[0]
	if drama.Name != "Drama" {
		t.Errorf("first subforum name: got %q, want %q", drama.Name, "Drama")
	}
	if drama.Id == nil || *drama.Id != "viewforum.php?f=101" {
		gotID := "<nil>"
		if drama.Id != nil {
			gotID = *drama.Id
		}
		t.Errorf("first subforum id: got %q, want %q", gotID, "viewforum.php?f=101")
	}

	// Second category sanity (no subforums in its single forum).
	musicCat := out.Children[1]
	if musicCat.Name != "Music" {
		t.Errorf("second category name: got %q, want %q", musicCat.Name, "Music")
	}
	if musicCat.Children == nil || len(*musicCat.Children) != 1 {
		t.Fatalf("expected 1 forum in second category")
	}
	rock := (*musicCat.Children)[0]
	if rock.Name != "Rock" {
		t.Errorf("rock forum name: got %q, want %q", rock.Name, "Rock")
	}
	if rock.Children != nil && len(*rock.Children) != 0 {
		t.Errorf("rock forum should have empty subforums, got %d", len(*rock.Children))
	}
}

func TestParseForum_Empty(t *testing.T) {
	html := loadFixture(t, "forum_empty.html")
	out, err := ParseForum(html)
	if err != nil {
		t.Fatalf("ParseForum on empty page returned error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseForum returned nil ForumDto")
	}
	if got := len(out.Children); got != 0 {
		t.Errorf("expected 0 categories, got %d", got)
	}
}

func TestParseCategoryPage_Page1(t *testing.T) {
	html := loadFixture(t, "category_page1.html")
	out, err := ParseCategoryPage(html, "700")
	if err != nil {
		t.Fatalf("ParseCategoryPage error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseCategoryPage returned nil")
	}
	if out.Page != 1 {
		t.Errorf("Page: got %d, want 1", out.Page)
	}
	if out.Pages != 3 {
		t.Errorf("Pages: got %d, want 3", out.Pages)
	}
	if out.Category.Name != "Drama Forum" {
		t.Errorf("Category.Name: got %q, want %q", out.Category.Name, "Drama Forum")
	}
	if out.Category.Id == nil || *out.Category.Id != "700" {
		t.Errorf("Category.Id: got %v, want \"700\"", out.Category.Id)
	}
	if out.Children == nil || len(*out.Children) != 2 {
		t.Fatalf("expected 2 subforum children, got %d", lenOrZero(out.Children))
	}
	subRu := (*out.Children)[0]
	if subRu.Name != "Drama: Russian" {
		t.Errorf("first subforum name: got %q", subRu.Name)
	}
	if subRu.Id == nil || *subRu.Id != "701" {
		gotID := "<nil>"
		if subRu.Id != nil {
			gotID = *subRu.Id
		}
		t.Errorf("first subforum id: got %q, want \"701\"", gotID)
	}

	// Sections — single section divider so list MUST be emptied per
	// Kotlin parity (sections.takeIf { it.size > 1 } ?: emptyList()).
	if out.Sections == nil {
		t.Fatal("Sections should be non-nil empty slice")
	}
	if got := len(*out.Sections); got != 0 {
		t.Errorf("Sections: got len %d, want 0 (single divider should be dropped)", got)
	}

	if out.Topics == nil {
		t.Fatal("Topics is nil")
	}
	topics := *out.Topics
	if got := len(topics); got != 2 {
		t.Fatalf("expected 2 topics, got %d", got)
	}
	// Index 0 = plain Topic (no torrent status).
	topic0, err := topics[0].AsForumTopicDtoTopic()
	if err != nil {
		t.Fatalf("topics[0] should be Topic, got error %v", err)
	}
	if topic0.Type != "Topic" {
		t.Errorf("topic0 Type: got %q, want \"Topic\"", topic0.Type)
	}
	if topic0.Id != "t1001" {
		t.Errorf("topic0 Id: got %q, want \"t1001\"", topic0.Id)
	}
	if topic0.Title != "A Discussion Thread" {
		t.Errorf("topic0 Title: got %q", topic0.Title)
	}
	if topic0.Author == nil || topic0.Author.Name != "alice" {
		gotName := "<nil-author>"
		if topic0.Author != nil {
			gotName = topic0.Author.Name
		}
		t.Errorf("topic0 Author.Name: got %q, want \"alice\"", gotName)
	}
	if topic0.Author == nil || topic0.Author.Id == nil || *topic0.Author.Id != "42" {
		gotID := "<nil>"
		if topic0.Author != nil && topic0.Author.Id != nil {
			gotID = *topic0.Author.Id
		}
		t.Errorf("topic0 Author.Id: got %q, want \"42\"", gotID)
	}

	// Index 1 = Torrent (tor-approved).
	torrent, err := topics[1].AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("topics[1] should be Torrent, got error %v", err)
	}
	if torrent.Type != "Torrent" {
		t.Errorf("torrent Type: got %q, want \"Torrent\"", torrent.Type)
	}
	if torrent.Id != "t1002" {
		t.Errorf("torrent Id: got %q, want \"t1002\"", torrent.Id)
	}
	if torrent.Title != "Movie Title" {
		t.Errorf("torrent Title: got %q, want \"Movie Title\" (tags should be stripped)", torrent.Title)
	}
	if torrent.Tags == nil || !strings.Contains(*torrent.Tags, "[Drama]") || !strings.Contains(*torrent.Tags, "[1080p]") {
		gotTags := "<nil>"
		if torrent.Tags != nil {
			gotTags = *torrent.Tags
		}
		t.Errorf("torrent Tags: got %q, want to contain [Drama] and [1080p]", gotTags)
	}
	if torrent.Status == nil || *torrent.Status != gen.Approved {
		gotStatus := "<nil>"
		if torrent.Status != nil {
			gotStatus = string(*torrent.Status)
		}
		t.Errorf("torrent Status: got %q, want %q", gotStatus, gen.Approved)
	}
	if torrent.Seeds == nil || *torrent.Seeds != 12 {
		gotSeeds := int32(-1)
		if torrent.Seeds != nil {
			gotSeeds = *torrent.Seeds
		}
		t.Errorf("torrent Seeds: got %d, want 12", gotSeeds)
	}
	if torrent.Leeches == nil || *torrent.Leeches != 3 {
		gotLeeches := int32(-1)
		if torrent.Leeches != nil {
			gotLeeches = *torrent.Leeches
		}
		t.Errorf("torrent Leeches: got %d, want 3", gotLeeches)
	}
	if torrent.Size == nil || *torrent.Size != "2.4 GB" {
		gotSize := "<nil>"
		if torrent.Size != nil {
			gotSize = *torrent.Size
		}
		t.Errorf("torrent Size: got %q, want \"2.4 GB\" (NBSP collapsed)", gotSize)
	}
	if torrent.Author == nil || torrent.Author.Name != "bob" {
		gotName := "<nil>"
		if torrent.Author != nil {
			gotName = torrent.Author.Name
		}
		t.Errorf("torrent Author.Name: got %q, want \"bob\"", gotName)
	}
}

func TestParseCategoryPage_Paginated(t *testing.T) {
	html := loadFixture(t, "category_paginated.html")
	out, err := ParseCategoryPage(html, "800")
	if err != nil {
		t.Fatalf("ParseCategoryPage error: %v", err)
	}
	if out.Page != 2 {
		t.Errorf("Page: got %d, want 2", out.Page)
	}
	if out.Pages != 5 {
		t.Errorf("Pages: got %d, want 5", out.Pages)
	}
	if out.Page >= out.Pages {
		t.Errorf("expected Page<Pages, got %d>=%d", out.Page, out.Pages)
	}

	if out.Topics == nil || len(*out.Topics) != 1 {
		t.Fatalf("expected 1 topic, got %d", lenTopicsOrZero(out.Topics))
	}
	tor, err := (*out.Topics)[0].AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("expected Torrent: %v", err)
	}
	if tor.Author == nil {
		t.Fatal("Author is nil")
	}
	if tor.Author.Id == nil {
		t.Fatal("Author.Id is nil")
	}
	if *tor.Author.Id != "42" {
		t.Errorf("Author.Id: got %q, want \"42\"", *tor.Author.Id)
	}
	if tor.Author.Name != "charlie" {
		t.Errorf("Author.Name: got %q, want \"charlie\"", tor.Author.Name)
	}
}

func TestParseCategoryPage_BlankAuthor(t *testing.T) {
	html := loadFixture(t, "category_blank_author.html")
	out, err := ParseCategoryPage(html, "900")
	if err != nil {
		t.Fatalf("ParseCategoryPage error: %v", err)
	}
	if out.Topics == nil || len(*out.Topics) != 1 {
		t.Fatalf("expected 1 topic, got %d", lenTopicsOrZero(out.Topics))
	}
	tor, err := (*out.Topics)[0].AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("expected Torrent: %v", err)
	}
	if tor.Author == nil {
		t.Fatal("Author is nil")
	}
	if tor.Author.Id != nil {
		t.Errorf("Author.Id should be nil (no topicAuthor anchor), got %q", *tor.Author.Id)
	}
	if tor.Author.Name != "deletedUser" {
		t.Errorf("Author.Name: got %q, want \"deletedUser\" (fallback to .vf-col-author text)", tor.Author.Name)
	}
}

// TestParseCategoryPage_SeedsOverflowAbsent pins the int32-clamp
// guarantee added in the Phase-6.2 code-quality fix. An adversarial /
// corrupt-upstream Seed count of 9999999999 (greater than math.MaxInt32)
// previously wrapped silently to a negative int32 because forum.go did
// `int32(seedsVal)` without bounds checking; the wrapped value then
// round-tripped through the OpenAPI int32 field on the wire. Post-fix,
// the parser MUST treat out-of-range values as ABSENT — Seeds and
// Leeches are nil pointers, never negative noise.
func TestParseCategoryPage_SeedsOverflowAbsent(t *testing.T) {
	html := loadFixture(t, "category_seeds_overflow.html")
	out, err := ParseCategoryPage(html, "9000")
	if err != nil {
		t.Fatalf("ParseCategoryPage error: %v", err)
	}
	if out.Topics == nil || len(*out.Topics) != 1 {
		t.Fatalf("expected 1 topic, got %d", lenTopicsOrZero(out.Topics))
	}
	tor, err := (*out.Topics)[0].AsForumTopicDtoTorrent()
	if err != nil {
		t.Fatalf("expected Torrent, got %v", err)
	}
	if tor.Seeds != nil {
		t.Errorf("Seeds: got %d, want nil (out-of-int32 upstream value MUST be absent, NOT wrapped)", *tor.Seeds)
	}
	if tor.Leeches != nil {
		t.Errorf("Leeches: got %d, want nil (out-of-int32 upstream value MUST be absent, NOT wrapped)", *tor.Leeches)
	}
}

func TestParseCategoryPage_NotFound(t *testing.T) {
	html := loadFixture(t, "category_not_found.html")
	out, err := ParseCategoryPage(html, "999")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
	if out != nil {
		t.Errorf("expected nil result, got %#v", out)
	}
}

func TestParseCategoryPage_Forbidden(t *testing.T) {
	html := loadFixture(t, "category_forbidden.html")
	out, err := ParseCategoryPage(html, "888")
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("expected ErrForbidden, got %v", err)
	}
	if out != nil {
		t.Errorf("expected nil result, got %#v", out)
	}
}

// TestGetForum_HappyPath is the integration-line check: the real Client
// hits a real httptest.NewServer that serves the same HTML fixture; the
// parsed result MUST match the unit-test expectation. Sixth Law clause 1:
// same surface (Client.GetForum) the Phase 7 handler will call.
func TestGetForum_HappyPath(t *testing.T) {
	fixture := loadFixture(t, "forum_tree.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/index.php" {
			t.Errorf("unexpected request path: %s", r.URL.Path)
		}
		if got := r.URL.Query().Get("map"); got != "0" {
			t.Errorf("expected map=0, got map=%q", got)
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.GetForum(context.Background(), "")
	if err != nil {
		t.Fatalf("GetForum failed: %v", err)
	}
	if out == nil || len(out.Children) != 2 {
		t.Fatalf("expected 2 categories from real Client wired to test server, got %v", out)
	}
	if out.Children[0].Name != "Movies" {
		t.Errorf("first category name: got %q, want \"Movies\"", out.Children[0].Name)
	}
}

// TestGetCategoryPage_PageQueryParam asserts the 50-topics-per-page
// pagination math used by RuTrackerInnerApiImpl.kt: page=2 → start=50,
// page=1 / page=nil → no start parameter.
func TestGetCategoryPage_PageQueryParam(t *testing.T) {
	fixture := loadFixture(t, "category_page1.html")
	cases := []struct {
		name      string
		page      *int
		wantStart string // "" means parameter must be ABSENT
	}{
		{name: "no page", page: nil, wantStart: ""},
		{name: "page=1", page: ptrInt(1), wantStart: ""},
		{name: "page=2", page: ptrInt(2), wantStart: "50"},
		{name: "page=3", page: ptrInt(3), wantStart: "100"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var captured url.Values
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				captured = r.URL.Query()
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				_, _ = w.Write(fixture)
			}))
			defer srv.Close()

			c := NewClient(srv.URL)
			_, err := c.GetCategoryPage(context.Background(), "700", tc.page, "")
			if err != nil {
				t.Fatalf("GetCategoryPage err: %v", err)
			}
			if got := captured.Get("f"); got != "700" {
				t.Errorf("f: got %q, want \"700\"", got)
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

// --- helpers ----------------------------------------------------------

func ptrInt(i int) *int { return &i }

func lenOrZero[T any](p *[]T) int {
	if p == nil {
		return 0
	}
	return len(*p)
}

func lenTopicsOrZero(p *[]gen.ForumTopicDto) int {
	if p == nil {
		return 0
	}
	return len(*p)
}
