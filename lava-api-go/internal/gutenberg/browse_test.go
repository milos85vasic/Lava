package gutenberg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBrowse_BuildsURLAndParses(t *testing.T) {
	var capturedPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedPath = r.URL.String()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"count": 1,
			"results": [
				{"id":456,"title":"Science Book","authors":[{"name":"Alice"}],"formats":{},"download_count":10,"subjects":["Science"]}
			]
		}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	res, err := c.Browse(context.Background(), "science", 2)
	if err != nil {
		t.Fatalf("Browse error: %v", err)
	}
	if capturedPath != "/books/?page=2&topic=science" {
		t.Errorf("path=%q want /books/?page=2&topic=science", capturedPath)
	}
	if len(res.Items) != 1 || res.Items[0].ID != "456" {
		t.Errorf("unexpected items: %+v", res.Items)
	}
}
