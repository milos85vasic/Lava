package gutenberg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSearch_BuildsURLAndParses(t *testing.T) {
	var capturedPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPath = r.URL.String()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"count": 64,
			"next": "http://example.com/books/?page=2",
			"results": [
				{"id":123,"title":"Pride and Prejudice","authors":[{"name":"Jane Austen"}],"formats":{"application/epub+zip":"https://example.com/pg123.epub"},"download_count":50000,"subjects":["Fiction"]}
			]
		}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	res, err := c.Search(context.Background(), "prejudice", 1)
	if err != nil {
		t.Fatalf("Search error: %v", err)
	}
	if capturedPath != "/books/?page=1&search=prejudice" {
		t.Errorf("path=%q want /books/?page=1&search=prejudice", capturedPath)
	}
	if len(res.Results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(res.Results))
	}
	if res.Results[0].Title != "Pride and Prejudice" {
		t.Errorf("title=%q want Pride and Prejudice", res.Results[0].Title)
	}
	if res.Results[0].Creator != "Jane Austen" {
		t.Errorf("creator=%q want Jane Austen", res.Results[0].Creator)
	}
	if res.TotalPages != 2 {
		t.Errorf("totalPages=%d want 2", res.TotalPages)
	}
}
