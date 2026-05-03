package gutenberg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestGetBook_BuildsURLAndParses(t *testing.T) {
	var capturedPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"id":789,
			"title":"Moby Dick",
			"authors":[{"name":"Herman Melville"}],
			"formats":{"application/epub+zip":"https://example.com/pg789.epub"},
			"download_count":1000,
			"subjects":["Fiction","Whales"]
		}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	res, err := c.GetBook(context.Background(), "789")
	if err != nil {
		t.Fatalf("GetBook error: %v", err)
	}
	if capturedPath != "/books/789/" {
		t.Errorf("path=%q want /books/789/", capturedPath)
	}
	if res.Title != "Moby Dick" {
		t.Errorf("title=%q want Moby Dick", res.Title)
	}
	if res.DownloadURL != "https://example.com/pg789.epub" {
		t.Errorf("downloadUrl=%q want https://example.com/pg789.epub", res.DownloadURL)
	}
}
