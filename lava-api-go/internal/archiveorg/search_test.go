package archiveorg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestSearch_HappyPath(t *testing.T) {
	jsonBody := `{
		"response": {
			"numFound": 123,
			"start": 0,
			"docs": [
				{
					"identifier": "item-1",
					"title": "Test Item One",
					"creator": "Alice",
					"downloads": 42,
					"item_size": 1048576,
					"mediatype": "movies",
					"year": "2023"
				},
				{
					"identifier": "item-2",
					"title": "Test Item Two"
				}
			]
		}
	}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/advancedsearch.php" {
			t.Errorf("path=%q want /advancedsearch.php", r.URL.Path)
		}
		q := r.URL.Query()
		if q.Get("q") != "ubuntu" {
			t.Errorf("q=%q want ubuntu", q.Get("q"))
		}
		if q.Get("output") != "json" {
			t.Errorf("output=%q want json", q.Get("output"))
		}
		if q.Get("page") != "1" {
			t.Errorf("page=%q want 1", q.Get("page"))
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Search(context.Background(), "ubuntu", 1)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if result.Page != 1 {
		t.Errorf("Page=%d want 1", result.Page)
	}
	if result.TotalPages != 3 { // 123/50 = 2 remainder 23 → 3 pages
		t.Errorf("TotalPages=%d want 3", result.TotalPages)
	}
	if len(result.Items) != 2 {
		t.Fatalf("expected 2 items, got %d", len(result.Items))
	}

	it0 := result.Items[0]
	if it0.ID != "item-1" {
		t.Errorf("items[0].ID=%q want item-1", it0.ID)
	}
	if it0.Title != "Test Item One" {
		t.Errorf("items[0].Title=%q want 'Test Item One'", it0.Title)
	}
	if it0.Creator != "Alice" {
		t.Errorf("items[0].Creator=%q want Alice", it0.Creator)
	}
	if it0.Downloads != 42 {
		t.Errorf("items[0].Downloads=%d want 42", it0.Downloads)
	}
	if it0.SizeBytes != 1048576 {
		t.Errorf("items[0].SizeBytes=%d want 1048576", it0.SizeBytes)
	}
	if it0.MediaType != "movies" {
		t.Errorf("items[0].MediaType=%q want movies", it0.MediaType)
	}
	if it0.Year != "2023" {
		t.Errorf("items[0].Year=%q want 2023", it0.Year)
	}

	it1 := result.Items[1]
	if it1.ID != "item-2" {
		t.Errorf("items[1].ID=%q want item-2", it1.ID)
	}
	if it1.Creator != "" {
		t.Errorf("items[1].Creator=%q want empty", it1.Creator)
	}
}

func TestSearch_EmptyResult(t *testing.T) {
	jsonBody := `{"response":{"numFound":0,"start":0,"docs":[]}}`

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(jsonBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Search(context.Background(), "nonsense", 1)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if len(result.Items) != 0 {
		t.Errorf("expected 0 items, got %d", len(result.Items))
	}
	if result.TotalPages != 1 {
		t.Errorf("TotalPages=%d want 1", result.TotalPages)
	}
}

func TestSearch_PageDefaultsToOne(t *testing.T) {
	var capturedPage string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPage = r.URL.Query().Get("page")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"response":{"numFound":0,"start":0,"docs":[]}}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.Search(context.Background(), "x", 0)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if capturedPage != "1" {
		t.Errorf("page=%q want 1 when caller passes 0", capturedPage)
	}
}

func TestSearch_InvalidJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{not json`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.Search(context.Background(), "x", 1)
	if err == nil {
		t.Fatal("expected error for invalid JSON, got nil")
	}
	if !strings.Contains(err.Error(), "unmarshal") {
		t.Errorf("error should mention unmarshal, got: %v", err)
	}
}
