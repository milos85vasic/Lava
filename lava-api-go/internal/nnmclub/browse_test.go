package nnmclub

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseBrowsePage_HappyPath(t *testing.T) {
	html := loadFixture(t, "browse", "browse_results.html")
	out, err := ParseBrowsePage(html, 1)
	if err != nil {
		t.Fatalf("ParseBrowsePage error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseBrowsePage returned nil")
	}
	if out.Page != 1 {
		t.Errorf("Page: got %d, want 1", out.Page)
	}
	if got := len(out.Items); got != 1 {
		t.Fatalf("expected 1 item, got %d", got)
	}

	item := out.Items[0]
	if item.ID != "2001" {
		t.Errorf("item.ID: got %q, want \"2001\"", item.ID)
	}
	if item.Title != "Movie A" {
		t.Errorf("item.Title: got %q, want \"Movie A\"", item.Title)
	}
	if item.Seeders != 45 {
		t.Errorf("item.Seeders: got %d, want 45", item.Seeders)
	}
}

func TestGetBrowsePage_BuildsUpstreamURL(t *testing.T) {
	fixture := loadFixture(t, "browse", "browse_results.html")
	var captured *http.Request
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		captured = r
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.GetBrowsePage(context.Background(), "7", 3, "")
	if err != nil {
		t.Fatalf("GetBrowsePage error: %v", err)
	}
	if captured == nil {
		t.Fatal("captured request is nil")
	}
	if captured.URL.Path != "/forum/viewforum.php" {
		t.Errorf("Path: got %q, want \"/forum/viewforum.php\"", captured.URL.Path)
	}
	q := captured.URL.Query()
	if q.Get("f") != "7" {
		t.Errorf("f: got %q, want \"7\"", q.Get("f"))
	}
	if q.Get("start") != "100" {
		t.Errorf("start: got %q, want \"100\"", q.Get("start"))
	}
}
