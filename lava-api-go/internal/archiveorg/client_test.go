package archiveorg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
)

// TestClientGetReturnsBody verifies the happy-path round-trip for the
// internal get helper.
func TestClientGetReturnsBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"hello":"world"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	body, err := c.get(context.Background(), "/test", nil)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if string(body) != `{"hello":"world"}` {
		t.Errorf("body=%q want %q", string(body), `{"hello":"world"}`)
	}
}

// TestClientGetReturnsErrorOn5xx verifies that a 5xx response surfaces as an error.
func TestClientGetReturnsErrorOn5xx(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	_, err := c.get(context.Background(), "/test", nil)
	if err == nil {
		t.Fatal("expected error for 500 response, got nil")
	}
}

// TestClientGetForwardsQueryParams verifies query parameters are encoded correctly.
func TestClientGetForwardsQueryParams(t *testing.T) {
	var gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	q := url.Values{}
	q.Set("q", "test query")
	_, err := c.get(context.Background(), "/test", q)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if gotQuery == "" {
		t.Error("expected non-empty query string")
	}
}
