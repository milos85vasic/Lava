package kinozal

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestClientFetch(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("hello"))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	body, status, err := c.Fetch(context.Background(), "/", "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if status != 200 {
		t.Fatalf("expected 200, got %d", status)
	}
	if string(body) != "hello" {
		t.Fatalf("expected hello, got %s", string(body))
	}
}
