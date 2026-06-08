package jackett

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// These tests cover the Download error branches that the existing happy-path
// Download tests (302→magnet, served .torrent, magnet short-circuit) leave
// uncovered. Each asserts on the user-visible failure a caller sees when a
// Jackett /dl/ link misbehaves: an empty link, a redirect to something that is
// NOT a magnet, and a non-200/non-redirect HTTP status. A real client request
// flows through exactly these branches, so a bug here surfaces as either a
// wrong .torrent download or a swallowed error — both user-visible.

// TestDownload_EmptyURL: an empty download link is refused up front, before any
// HTTP round-trip. The composition root passes the Torznab enclosure URL here;
// an item with no enclosure would otherwise produce a confusing HTTP error.
//
// FALSIFIABILITY: deleting the empty-url guard in Download makes the code fall
// through to http.NewRequestWithContext with an empty URL, which returns a
// different ("unsupported protocol scheme")/nil-deref shape, failing the
// substring assertion below.
func TestDownload_EmptyURL(t *testing.T) {
	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})

	dr, err := c.Download(context.Background(), "   ")
	if err == nil {
		t.Fatalf("Download(empty) returned nil error; expected refusal, dr=%+v", dr)
	}
	if dr != nil {
		t.Errorf("Download error path returned non-nil result %+v; must be nil", dr)
	}
	if !strings.Contains(err.Error(), "empty download url") {
		t.Errorf("error %q does not name the empty-url cause", err)
	}
}

// TestDownload_RedirectToNonMagnet: Jackett's documented edge case is 302→magnet.
// A 302 whose Location is NOT a magnet (e.g. an HTML login redirect, or a
// proxied .torrent moved to another host) MUST be surfaced as an error rather
// than silently returning an empty result — the user would otherwise see a
// "download succeeded" with no payload.
//
// FALSIFIABILITY: changing the non-magnet branch to `return &DownloadResult{}, nil`
// makes err nil here, failing the "expected error" assertion.
func TestDownload_RedirectToNonMagnet(t *testing.T) {
	const nonMagnet = "https://jackett.example/login?return=/dl/x"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Location", nonMagnet)
		w.WriteHeader(http.StatusFound) // 302
	}))
	defer srv.Close()

	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})
	dr, err := c.Download(context.Background(), srv.URL+"/dl/rutracker/?file=x")
	if err == nil {
		t.Fatalf("Download(302→non-magnet) returned nil error; dr=%+v", dr)
	}
	if dr != nil {
		t.Errorf("non-magnet redirect returned non-nil result %+v; must be nil", dr)
	}
	if !strings.Contains(err.Error(), "non-magnet location") {
		t.Errorf("error %q does not name the non-magnet redirect cause", err)
	}
	// The bad location MUST appear in the error so an operator can diagnose it.
	if !strings.Contains(err.Error(), nonMagnet) {
		t.Errorf("error %q does not echo the offending Location %q", err, nonMagnet)
	}
}

// TestDownload_Non200Status: a 4xx/5xx from the /dl/ endpoint (e.g. Jackett
// indexer auth expired → 403, or the link 404'd) MUST surface as an error with
// the status code, not be returned as a zero-length .torrent.
//
// FALSIFIABILITY: removing the `resp.StatusCode != http.StatusOK` guard makes
// Download read the (empty/HTML) body and return it as TorrentBytes with nil
// err, failing the "expected error" assertion.
func TestDownload_Non200Status(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden) // 403 — indexer auth lapsed
		_, _ = w.Write([]byte("<html>Forbidden</html>"))
	}))
	defer srv.Close()

	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})
	dr, err := c.Download(context.Background(), srv.URL+"/dl/rutracker/?file=x")
	if err == nil {
		t.Fatalf("Download(403) returned nil error; dr=%+v", dr)
	}
	if dr != nil {
		t.Errorf("403 returned non-nil result %+v; must be nil (no HTML masquerading as .torrent)", dr)
	}
	if !strings.Contains(err.Error(), "403") {
		t.Errorf("error %q does not carry the HTTP status code", err)
	}
}

// TestDownload_TransportError: an unreachable host surfaces the download-request
// wrapping. This covers the `c.http.Do` error branch — the user-visible signal
// when the Jackett sidecar is down.
//
// FALSIFIABILITY: dropping the fmt.Errorf wrap (returning the bare net error)
// removes the "download request" prefix, failing the substring assertion.
func TestDownload_TransportError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := srv.URL
	srv.Close() // server is now down; Do() will fail to connect

	c, _ := NewClient(Config{BaseURL: "http://jackett:9117", APIKey: "k"})
	dr, err := c.Download(context.Background(), url+"/dl/x")
	if err == nil {
		t.Fatalf("Download against closed server returned nil error; dr=%+v", dr)
	}
	if !strings.Contains(err.Error(), "download request") {
		t.Errorf("error %q does not carry the download-request wrapping", err)
	}
}
