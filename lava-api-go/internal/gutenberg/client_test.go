package gutenberg

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
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

// TestRetriesOnTransient5xx is the reproduce-first (§6.T.1) regression test for
// the single-attempt gap: a transient 5xx on the first attempt of getJSON used
// to surface to the user as a failed search. getJSON now retries the transient
// failure and recovers, returning the real decoded body.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2):
//
//	Mutation: in client.go getJSON, bypass the retry by returning
//	          getJSONOnce's error directly (single attempt).
//	Observed: "getJSON after transient 503 should recover via retry:
//	          gutenberg upstream 503".
//	Reverted: yes.
func TestRetriesOnTransient5xx(t *testing.T) {
	var attempts int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts == 1 {
			// One transient 5xx, then serve the real body (the slow-upstream case).
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"count":1,"results":[{"id":1,"title":"Retry Worked"}]}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	var out bookList
	if err := c.getJSON(context.Background(), "/", &out); err != nil {
		t.Fatalf("getJSON after transient 503 should recover via retry: %v", err)
	}
	// Primary assertion: the user-visible decoded body.
	if out.Count != 1 || len(out.Results) != 1 || out.Results[0].Title != "Retry Worked" {
		t.Fatalf("unexpected decoded body after retry: %+v", out)
	}
	// Secondary: the upstream was actually hit more than once (the retry fired).
	if attempts < 2 {
		t.Fatalf("expected >=2 upstream attempts (retry), got %d", attempts)
	}
}

// TestDownloadRetriesOnTransient5xx covers the SECOND HTTP method (downloadBytes):
// a transient 5xx on the first attempt is retried and recovers with the real
// bytes.
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2):
//
//	Mutation: in client.go downloadBytes, bypass the retry by calling
//	          downloadBytesOnce directly (single attempt).
//	Observed: "downloadBytes after transient 503 should recover via retry:
//	          gutenberg upstream 503".
//	Reverted: yes.
func TestDownloadRetriesOnTransient5xx(t *testing.T) {
	const wantBody = "EPUB-BYTES-HERE"
	var attempts int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts == 1 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		_, _ = w.Write([]byte(wantBody))
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	b, err := c.downloadBytes(context.Background(), srv.URL+"/book.epub")
	if err != nil {
		t.Fatalf("downloadBytes after transient 503 should recover via retry: %v", err)
	}
	// Primary assertion: the user-visible downloaded bytes.
	if string(b) != wantBody {
		t.Fatalf("downloaded body = %q, want %q", string(b), wantBody)
	}
	if attempts < 2 {
		t.Fatalf("expected >=2 download attempts (retry), got %d", attempts)
	}
}

// TestTerminalErrorNotRetried proves the retry does NOT waste the budget on a
// terminal error: a persistent 404 returns after exactly ONE attempt for BOTH
// HTTP methods (getJSON + downloadBytes).
//
// FALSIFIABILITY REHEARSAL (§6.J clause 2):
//
//	Mutation: in client.go getJSONOnce/downloadBytesOnce, return true (transient)
//	          for the non-200, non-5xx branch.
//	Observed: "terminal 404 must NOT be retried; got 3 attempts".
//	Reverted: yes.
func TestTerminalErrorNotRetried(t *testing.T) {
	t.Run("getJSON", func(t *testing.T) {
		var attempts int
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			attempts++
			w.WriteHeader(http.StatusNotFound)
		}))
		defer srv.Close()

		c := NewClient(srv.URL)
		var out bookList
		err := c.getJSON(context.Background(), "/", &out)
		if err == nil || !strings.Contains(err.Error(), "404") {
			t.Fatalf("want a 404 error, got %v", err)
		}
		if attempts != 1 {
			t.Fatalf("terminal 404 must NOT be retried; got %d attempts", attempts)
		}
	})

	t.Run("downloadBytes", func(t *testing.T) {
		var attempts int
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			attempts++
			w.WriteHeader(http.StatusNotFound)
		}))
		defer srv.Close()

		c := NewClient(srv.URL)
		_, err := c.downloadBytes(context.Background(), srv.URL+"/book.epub")
		if err == nil || !strings.Contains(err.Error(), "404") {
			t.Fatalf("want a 404 error, got %v", err)
		}
		if attempts != 1 {
			t.Fatalf("terminal 404 must NOT be retried; got %d attempts", attempts)
		}
	})
}
