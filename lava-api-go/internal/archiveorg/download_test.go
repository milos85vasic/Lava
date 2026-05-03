package archiveorg

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestDownload_HappyPath(t *testing.T) {
	wantBody := []byte("file content here")

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/download/item-1/file.txt" {
			t.Errorf("path=%q want /download/item-1/file.txt", r.URL.Path)
		}
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(wantBody)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	result, err := c.Download(context.Background(), "item-1", "file.txt")
	if err != nil {
		t.Fatalf("Download: %v", err)
	}
	if result.Filename != "file.txt" {
		t.Errorf("Filename=%q want file.txt", result.Filename)
	}
	if !bytes.Equal(result.Body, wantBody) {
		t.Errorf("Body=%q want %q", result.Body, wantBody)
	}
}

func TestDownload_NotFound(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("not found"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.Download(context.Background(), "item-1", "missing.txt")
	if err == nil {
		t.Fatal("expected error for 404, got nil")
	}
}
