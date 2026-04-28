package rutracker

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestParseTopicPage_TorrentVariant(t *testing.T) {
	html := loadTopicFixture(t, "topic_torrent.html")
	out, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("ParseTopicPage: %v", err)
	}
	if out.Id != "42" {
		t.Errorf("Id: got %q, want \"42\"", out.Id)
	}
	if out.Title != "Movie" {
		t.Errorf("Title: got %q, want \"Movie\" (getTitle MUST strip [Drama] and [1080p])", out.Title)
	}
	if out.Category == nil || out.Category.Id == nil || *out.Category.Id != "700" {
		t.Errorf("Category.Id: got %v", out.Category)
	}
	if out.Author == nil || out.Author.Name != "uploader" {
		t.Errorf("Author: got %v", out.Author)
	}
	if out.TorrentData == nil {
		t.Fatal("TorrentData is nil; expected non-nil for torrent fixture")
	}
	td := out.TorrentData
	if td.Seeds == nil || *td.Seeds != 123 {
		t.Errorf("Seeds: got %v, want 123", td.Seeds)
	}
	if td.Leeches == nil || *td.Leeches != 4 {
		t.Errorf("Leeches: got %v, want 4", td.Leeches)
	}
	if td.Status == nil || *td.Status != "Approved" {
		gotStatus := "<nil>"
		if td.Status != nil {
			gotStatus = string(*td.Status)
		}
		t.Errorf("Status: got %q, want \"Approved\"", gotStatus)
	}
	if td.Size == nil || *td.Size != "1.5 GB" {
		gotSize := "<nil>"
		if td.Size != nil {
			gotSize = *td.Size
		}
		t.Errorf("Size: got %q, want \"1.5 GB\" (logged-in path → #tor-size-humn)", gotSize)
	}
	if td.MagnetLink == nil || !strings.HasPrefix(*td.MagnetLink, "magnet:?") {
		gotML := "<nil>"
		if td.MagnetLink != nil {
			gotML = *td.MagnetLink
		}
		t.Errorf("MagnetLink: got %q", gotML)
	}
	if td.PosterUrl == nil || *td.PosterUrl != "https://img/poster.jpg" {
		gotPU := "<nil>"
		if td.PosterUrl != nil {
			gotPU = *td.PosterUrl
		}
		t.Errorf("PosterUrl: got %q, want \"https://img/poster.jpg\"", gotPU)
	}
	if td.Date == nil || *td.Date == "" {
		t.Errorf("Date: got nil/empty, want first-post .p-link text")
	}
	// commentsPage section
	if out.CommentsPage.Page != 1 {
		t.Errorf("CommentsPage.Page: got %d", out.CommentsPage.Page)
	}
	if len(out.CommentsPage.Posts) == 0 {
		t.Fatal("CommentsPage.Posts: empty")
	}
}

func TestParseTopicPage_TorrentDataAbsent(t *testing.T) {
	html := loadTopicFixture(t, "topic_no_torrent_data.html")
	out, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("ParseTopicPage: %v", err)
	}
	if out.TorrentData != nil {
		t.Errorf("TorrentData: got non-nil %+v, want nil (no seeds/leeches/status/size in fixture)", out.TorrentData)
	}
	// CommentsPage MUST still be populated.
	if len(out.CommentsPage.Posts) == 0 {
		t.Errorf("CommentsPage.Posts: empty; expected at least 1")
	}
}

// TestParseTopicPage_StripsTitle pins the Kotlin behaviour difference:
// ParseTopicPageUseCase uses getTitle() to strip bracket-tags from the
// title (and getTags() to extract them); ParseCommentsPageUseCase uses
// the raw text. Faithful port of both.
func TestParseTopicPage_StripsTitle(t *testing.T) {
	html := loadTopicFixture(t, "topic_title_with_tags.html")
	out, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("ParseTopicPage: %v", err)
	}
	if out.Title != "Movie Name" {
		t.Errorf("Title: got %q, want \"Movie Name\" (getTitle MUST strip [Genre] and [1080p])", out.Title)
	}
	if out.TorrentData == nil {
		t.Fatal("TorrentData: nil")
	}
	if out.TorrentData.Tags == nil {
		t.Fatal("TorrentData.Tags: nil")
	}
	tags := *out.TorrentData.Tags
	if !strings.Contains(tags, "[Genre]") || !strings.Contains(tags, "[1080p]") {
		t.Errorf("Tags: got %q, want to contain [Genre] AND [1080p]", tags)
	}
}

func TestParseTopicPage_SizeFromAttachLink(t *testing.T) {
	html := loadTopicFixture(t, "topic_size_attach_link.html")
	out, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("ParseTopicPage: %v", err)
	}
	if out.TorrentData == nil {
		t.Fatal("TorrentData: nil")
	}
	if out.TorrentData.Size == nil || *out.TorrentData.Size != "2.7 GB" {
		gotSize := "<nil>"
		if out.TorrentData.Size != nil {
			gotSize = *out.TorrentData.Size
		}
		t.Errorf("Size: got %q, want \"2.7 GB\" (NOT logged-in → .attach_link path)", gotSize)
	}
}

func TestParseTopicPage_SizeFromLoggedIn(t *testing.T) {
	html := loadTopicFixture(t, "topic_torrent.html") // includes #logged-in-username
	out, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("ParseTopicPage: %v", err)
	}
	if out.TorrentData == nil || out.TorrentData.Size == nil || *out.TorrentData.Size != "1.5 GB" {
		gotSize := "<nil>"
		if out.TorrentData != nil && out.TorrentData.Size != nil {
			gotSize = *out.TorrentData.Size
		}
		t.Errorf("Size: got %q, want \"1.5 GB\" (logged-in path → #tor-size-humn)", gotSize)
	}
}

func TestGetTopic_DispatchTorrentToPartialTopic(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTopicFixture(t, "topic_torrent.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTopic(context.Background(), "42", nil, "")
	if err != nil {
		t.Fatalf("GetTopic: %v", err)
	}
	if out == nil {
		t.Fatal("nil result")
	}
	d, err := out.Discriminator()
	if err != nil {
		t.Fatalf("Discriminator: %v", err)
	}
	if d != "Topic" {
		t.Fatalf("discriminator: got %q, want \"Topic\" (Task 6.4 stub for the torrent branch)", d)
	}
	topic, err := out.AsForumTopicDtoTopic()
	if err != nil {
		t.Fatalf("AsForumTopicDtoTopic: %v", err)
	}
	if topic.Id != "42" {
		t.Errorf("Id: got %q, want \"42\"", topic.Id)
	}
	if topic.Title != "Movie" {
		t.Errorf("Title: got %q, want \"Movie\"", topic.Title)
	}
}

func TestGetTopic_DispatchCommentsToCommentsPage(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTopicFixture(t, "comments_no_magnet.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTopic(context.Background(), "23456", nil, "")
	if err != nil {
		t.Fatalf("GetTopic: %v", err)
	}
	d, err := out.Discriminator()
	if err != nil {
		t.Fatalf("Discriminator: %v", err)
	}
	if d != "CommentsPage" {
		t.Fatalf("discriminator: got %q, want \"CommentsPage\"", d)
	}
	cp, err := out.AsForumTopicDtoCommentsPage()
	if err != nil {
		t.Fatalf("AsForumTopicDtoCommentsPage: %v", err)
	}
	if cp.Id != "23456" {
		t.Errorf("Id: got %q, want \"23456\"", cp.Id)
	}
	if len(cp.Posts) != 2 {
		t.Errorf("Posts: got %d, want 2", len(cp.Posts))
	}
}

func TestGetTopic_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTopicFixture(t, "topic_not_found.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTopic(context.Background(), "999", nil, "")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("err: got %v, want ErrNotFound", err)
	}
	if out != nil {
		t.Errorf("expected nil result")
	}
}

func TestGetTopic_Forbidden(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTopicFixture(t, "topic_forbidden.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTopic(context.Background(), "888", nil, "")
	if !errors.Is(err, ErrForbidden) {
		t.Fatalf("err: got %v, want ErrForbidden", err)
	}
	if out != nil {
		t.Errorf("expected nil result")
	}
}

func TestClient_GetTopicPage_HappyPath(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/viewtopic.php" {
			t.Errorf("path: got %q, want \"/viewtopic.php\"", r.URL.Path)
		}
		if got := r.URL.Query().Get("t"); got != "42" {
			t.Errorf("t: got %q, want \"42\"", got)
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(loadTopicFixture(t, "topic_torrent.html"))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	out, err := c.GetTopicPage(context.Background(), "42", nil, "")
	if err != nil {
		t.Fatalf("GetTopicPage: %v", err)
	}
	if out == nil || out.Id != "42" {
		t.Fatalf("expected TopicPageDto with id=42, got %+v", out)
	}
	if out.TorrentData == nil {
		t.Fatal("TorrentData should be non-nil for torrent fixture")
	}
}
