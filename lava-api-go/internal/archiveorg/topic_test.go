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

// TestTopic_FileSourceParsed pins the per-file "source" field, which the
// real archive.org /metadata API returns ("original" / "derivative") and which
// TopicResult exposes as a user-visible TopicFile.Source. The happy-path
// fixtures above omit "source", so this field had zero behavioral coverage:
// a §6.N bluff-hunt mutation (tf.Source = "MUTATED-WRONG") left every existing
// test green. This test closes that gap by asserting the parsed value.
//
// Falsifiability rehearsal (§6.N / Sixth Law clause 2):
//
//	Mutation:  topic.go  `tf.Source = *f.Source`  →  `tf.Source = "MUTATED-WRONG"`
//	Observed:  files[0].Source="MUTATED-WRONG" want original
//	Reverted:  yes
func TestTopic_FileSourceParsed(t *testing.T) {
	jsonBody := `{
		"metadata": {"title": "With Sources", "mediatype": "movies"},
		"files": [
			{"name": "movie.mp4", "size": "734003200", "format": "h.264", "source": "original"},
			{"name": "movie_thumb.jpg", "format": "JPEG Thumb", "source": "derivative"},
			{"name": "movie_meta.xml", "format": "Metadata"}
		]
	}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Topic(context.Background(), "with-sources")
	if err != nil {
		t.Fatalf("Topic: %v", err)
	}
	if len(result.Files) != 3 {
		t.Fatalf("expected 3 files, got %d", len(result.Files))
	}
	// User-visible Source values must be parsed verbatim from the upstream JSON.
	if result.Files[0].Source != "original" {
		t.Errorf("files[0].Source=%q want original", result.Files[0].Source)
	}
	if result.Files[1].Source != "derivative" {
		t.Errorf("files[1].Source=%q want derivative", result.Files[1].Source)
	}
	// A file with no "source" key must leave Source empty (omitempty contract),
	// not inherit a stale value from a sibling entry.
	if result.Files[2].Source != "" {
		t.Errorf("files[2].Source=%q want empty (no source key)", result.Files[2].Source)
	}
	// Guard that Source is not silently swapped with a neighbouring field:
	// the derivative thumb's format must still be its own, distinct from source.
	if result.Files[1].Format != "JPEG Thumb" {
		t.Errorf("files[1].Format=%q want 'JPEG Thumb'", result.Files[1].Format)
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
