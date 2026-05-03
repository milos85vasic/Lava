package archiveorg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestTopic_HappyPath(t *testing.T) {
	jsonBody := `{
		"metadata": {
			"title": "Test Topic",
			"creator": "Bob",
			"description": "A test description.",
			"date": "2023-01-15",
			"mediatype": "movies"
		},
		"files": [
			{"name": "file1.mp4", "size": "1.2 GB", "format": "MPEG4"},
			{"name": "file2.jpg", "format": "JPEG"}
		]
	}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/metadata/test-id" {
			t.Errorf("path=%q want /metadata/test-id", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Topic(context.Background(), "test-id")
	if err != nil {
		t.Fatalf("Topic: %v", err)
	}
	if result.ID != "test-id" {
		t.Errorf("ID=%q want test-id", result.ID)
	}
	if result.Title != "Test Topic" {
		t.Errorf("Title=%q want 'Test Topic'", result.Title)
	}
	if result.Creator != "Bob" {
		t.Errorf("Creator=%q want Bob", result.Creator)
	}
	if result.Description != "A test description." {
		t.Errorf("Description=%q want 'A test description.'", result.Description)
	}
	if result.Date != "2023-01-15" {
		t.Errorf("Date=%q want 2023-01-15", result.Date)
	}
	if result.MediaType != "movies" {
		t.Errorf("MediaType=%q want movies", result.MediaType)
	}
	if len(result.Files) != 2 {
		t.Fatalf("expected 2 files, got %d", len(result.Files))
	}
	if result.Files[0].Name != "file1.mp4" {
		t.Errorf("files[0].Name=%q want file1.mp4", result.Files[0].Name)
	}
	if result.Files[0].Size != "1.2 GB" {
		t.Errorf("files[0].Size=%q want '1.2 GB'", result.Files[0].Size)
	}
	if result.Files[0].Format != "MPEG4" {
		t.Errorf("files[0].Format=%q want MPEG4", result.Files[0].Format)
	}
	if result.Files[1].Name != "file2.jpg" {
		t.Errorf("files[1].Name=%q want file2.jpg", result.Files[1].Name)
	}
}

func TestTopic_MinimalMetadata(t *testing.T) {
	jsonBody := `{"metadata":{"title":"Minimal"},"files":[]}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Topic(context.Background(), "min")
	if err != nil {
		t.Fatalf("Topic: %v", err)
	}
	if result.Title != "Minimal" {
		t.Errorf("Title=%q want Minimal", result.Title)
	}
	if result.Creator != "" {
		t.Errorf("Creator=%q want empty", result.Creator)
	}
	if len(result.Files) != 0 {
		t.Errorf("expected 0 files, got %d", len(result.Files))
	}
}
