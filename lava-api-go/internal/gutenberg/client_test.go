package gutenberg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClientGetJSON_ReturnsDecodedBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"count":1,"results":[{"id":1,"title":"Test"}]}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	var out bookList
	if err := c.getJSON(context.Background(), "/", &out); err != nil {
		t.Fatalf("getJSON error: %v", err)
	}
	if out.Count != 1 {
		t.Errorf("count=%d want 1", out.Count)
	}
	if len(out.Results) != 1 || out.Results[0].Title != "Test" {
		t.Errorf("unexpected result: %+v", out.Results)
	}
}

func TestClientGetJSON_Surfaces5xx(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	var out bookList
	err := c.getJSON(context.Background(), "/", &out)
	if err == nil {
		t.Fatal("expected error for 500 response")
	}
}
