package gutenberg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDownloadBook_PicksEpub(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/books/1/":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{
				"id":1,
				"title":"Test",
				"authors":[],
				"formats":{"application/epub+zip":"` + srv.URL + `/epub","text/plain":"` + srv.URL + `/txt"},
				"download_count":0
			}`))
		case "/epub":
			w.Header().Set("Content-Type", "application/epub+zip")
			_, _ = w.Write([]byte("epub-bytes"))
		case "/txt":
			_, _ = w.Write([]byte("txt-bytes"))
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	res, err := c.DownloadBook(context.Background(), "1")
	if err != nil {
		t.Fatalf("DownloadBook error: %v", err)
	}
	if string(res.Body) != "epub-bytes" {
		t.Errorf("body=%q want epub-bytes", string(res.Body))
	}
}

func TestDownloadBook_FallsBackToPlainText(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/books/2/":
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{
				"id":2,
				"title":"Test",
				"authors":[],
				"formats":{"text/plain":"` + srv.URL + `/txt"},
				"download_count":0
			}`))
		case "/txt":
			_, _ = w.Write([]byte("txt-bytes"))
		}
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	res, err := c.DownloadBook(context.Background(), "2")
	if err != nil {
		t.Fatalf("DownloadBook error: %v", err)
	}
	if string(res.Body) != "txt-bytes" {
		t.Errorf("body=%q want txt-bytes", string(res.Body))
	}
}
