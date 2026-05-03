package archiveorg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBrowse_Collection(t *testing.T) {
	jsonBody := `{
		"response": {
			"numFound": 55,
			"start": 0,
			"docs": [
				{
					"identifier": "audio-1",
					"title": "Audio Item",
					"mediatype": "audio"
				}
			]
		}
	}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/advancedsearch.php" {
			t.Errorf("path=%q want /advancedsearch.php", r.URL.Path)
		}
		q := r.URL.Query()
		if q.Get("q") != "collection:audio" {
			t.Errorf("q=%q want collection:audio", q.Get("q"))
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Browse(context.Background(), "audio", 1)
	if err != nil {
		t.Fatalf("Browse: %v", err)
	}
	if len(result.Items) != 1 {
		t.Fatalf("expected 1 item, got %d", len(result.Items))
	}
	if result.Items[0].ID != "audio-1" {
		t.Errorf("items[0].ID=%q want audio-1", result.Items[0].ID)
	}
	// 55 items / 50 per page = 2 pages
	if result.TotalPages != 2 {
		t.Errorf("TotalPages=%d want 2", result.TotalPages)
	}
}

func TestBrowse_EmptyCollection(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"response":{"numFound":0,"start":0,"docs":[]}}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Browse(context.Background(), "nonexistent", 1)
	if err != nil {
		t.Fatalf("Browse: %v", err)
	}
	if len(result.Items) != 0 {
		t.Errorf("expected 0 items, got %d", len(result.Items))
	}
}
