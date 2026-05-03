package kinozal

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseTopicPage(t *testing.T) {
	html := loadTestData("topic/topic.html")
	result, err := ParseTopicPage(html)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if result.Title != "Test Movie 2024" {
		t.Errorf("expected title 'Test Movie 2024', got %s", result.Title)
	}
	if result.Description != "Description of the movie goes here." {
		t.Errorf("expected description, got %s", result.Description)
	}
	if result.MagnetLink != "magnet:?xt=urn:btih:ABC123DEF4567890ABC123DEF4567890ABC1234" {
		t.Errorf("unexpected magnet: %s", result.MagnetLink)
	}
}

func TestClientGetTopic(t *testing.T) {
	html := loadTestData("topic/topic.html")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/details.php" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if r.URL.Query().Get("id") != "12345" {
			t.Errorf("unexpected id: %s", r.URL.Query().Get("id"))
		}
		w.WriteHeader(http.StatusOK)
		w.Write(html)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.GetTopic(context.Background(), "12345", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if result.ID != "12345" {
		t.Errorf("expected id 12345, got %s", result.ID)
	}
}
