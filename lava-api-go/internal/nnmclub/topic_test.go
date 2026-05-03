package nnmclub

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseTopicPage_HappyPath(t *testing.T) {
	html := loadFixture(t, "topic", "topic_normal.html")
	out, err := ParseTopicPage(html, "1001")
	if err != nil {
		t.Fatalf("ParseTopicPage error: %v", err)
	}
	if out == nil {
		t.Fatal("ParseTopicPage returned nil")
	}
	if out.ID != "1001" {
		t.Errorf("ID: got %q, want \"1001\"", out.ID)
	}
	if out.Title != "Ubuntu 24.04 LTS" {
		t.Errorf("Title: got %q, want \"Ubuntu 24.04 LTS\"", out.Title)
	}
	if out.MagnetLink == "" {
		t.Error("MagnetLink: expected non-empty")
	}
	if out.DownloadURL == "" {
		t.Error("DownloadURL: expected non-empty")
	}
	if out.Description == "" {
		t.Error("Description: expected non-empty")
	}
}

func TestGetTopicPage_BuildsUpstreamURL(t *testing.T) {
	fixture := loadFixture(t, "topic", "topic_normal.html")
	var captured *http.Request
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		captured = r
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(fixture)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.GetTopicPage(context.Background(), "1001", 2, "")
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
	if q.Get("t") != "1001" {
		t.Errorf("t: got %q, want \"1001\"", q.Get("t"))
	}
	if q.Get("start") != "50" {
		t.Errorf("start: got %q, want \"50\"", q.Get("start"))
	}
}
