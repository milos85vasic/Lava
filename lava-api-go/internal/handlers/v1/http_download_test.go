package v1

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// httpFileProvider is a gutenberg/archiveorg-shaped fake: it declares
// HTTP_DOWNLOAD (NOT TORRENT_DOWNLOAD) and serves its artifact through the
// generic Provider.DownloadFile. It embeds fakeProvider (which implements every
// Provider method) and overrides only Capabilities so the §6.E capability gate
// sees the HTTP-file shape. The bytes it returns come from f.downloadResult.
type httpFileProvider struct {
	fakeProvider
}

func (p *httpFileProvider) Capabilities() []provider.ProviderCapability {
	// Deliberately EXCLUDES CapTorrentDownload — this is the gutenberg/archiveorg
	// shape whose download the CapTorrentDownload-gated /download/:id route would
	// (and, before the /http-download/:id route, did) reject with 501.
	return []provider.ProviderCapability{
		provider.CapSearch,
		provider.CapBrowse,
		provider.CapTopic,
		provider.CapHTTPDownload,
	}
}

// setupRegistryRouter mounts the PRODUCTION routes via v1.Register over a REAL
// provider registry + the REAL middleware.ProviderMiddleware capability gate
// (reg != nil). Unlike setupTestRouter (nil registry, gate bypassed), this
// harness exercises the exact capability chain a real /v1/{provider}/... request
// crosses — which is where the bug lived (the gate 501'd HTTP-file providers).
func setupRegistryRouter(p provider.Provider) *gin.Engine {
	gin.SetMode(gin.TestMode)
	reg := provider.NewRegistry()
	reg.Register(p)
	router := gin.New()
	group := router.Group("/v1/:provider")
	Register(group, &Deps{Cache: newFakeCache()}, reg)
	return router
}

// TestHTTPDownload_ServesFileForHTTPDownloadProvider is the reproduce-first
// guard for the HTTP-file download bug: a provider that declares HTTP_DOWNLOAD
// (gutenberg/archiveorg) MUST be able to complete its download flow through the
// production capability gate, returning the real bytes + a Content-Disposition
// filename the client can save under.
//
// FALSIFIABILITY / REPRODUCE-FIRST (§6.T.1 / §6.J clause 2) — actually run:
//
//	Mutation: delete the `group.GET("/http-download/:id", ...)` registration in
//	          handlers.go (the pre-fix state — HTTP-file providers had NO download
//	          route; the only download routes gated on CapTorrentDownload).
//	Observed: RED — "expected 200 serving the HTTP file, got 404: 404 page not
//	          found" (gin has no such route). With the route present but wrongly
//	          gated on CapTorrentDownload the failure is instead 501
//	          unsupported_capability. Both prove the flow dead-ended for the user.
//	Reverted: yes — the route + CapHTTPDownload gate are the fix under test.
func TestHTTPDownload_ServesFileForHTTPDownloadProvider(t *testing.T) {
	epub := []byte("PK\x03\x04-fake-epub-bytes-for-pg1342-\x00\x01\x02")
	fp := &httpFileProvider{}
	fp.fakeProvider.id = "gutenberg"
	fp.fakeProvider.downloadResult = &provider.FileDownload{
		Provider:    "gutenberg",
		ID:          "1342",
		Filename:    "pg1342.epub3.images",
		ContentType: "application/epub+zip",
		Body:        epub,
	}
	router := setupRegistryRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/gutenberg/http-download/1342", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 serving the HTTP file, got %d: %s", w.Code, w.Body.String())
	}
	// PRIMARY (§6.J clause 3) — the user-visible artifact: the exact bytes that
	// would be written to the user's Downloads folder.
	if got := w.Body.Bytes(); string(got) != string(epub) {
		t.Fatalf("served bytes mismatch:\n got %q\nwant %q", got, epub)
	}
	// The client names the saved file from Content-Disposition — assert the exact
	// header the goapi contract promises.
	if cd := w.Header().Get("Content-Disposition"); cd != `attachment; filename="pg1342.epub3.images"` {
		t.Fatalf("Content-Disposition mismatch: got %q", cd)
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/epub+zip" {
		t.Fatalf("Content-Type mismatch: got %q, want application/epub+zip", ct)
	}
}

// TestHTTPDownload_FallsBackToOctetStreamWhenContentTypeBlank covers archiveorg,
// whose DownloadFile fills Filename but leaves ContentType blank. The handler
// MUST still serve the bytes under a valid binary type + the filename header
// (a blank Content-Type would make the client mis-handle the artifact).
func TestHTTPDownload_FallsBackToOctetStreamWhenContentTypeBlank(t *testing.T) {
	body := []byte("moby-dick-epub-content")
	fp := &httpFileProvider{}
	fp.fakeProvider.id = "archiveorg"
	fp.fakeProvider.downloadResult = &provider.FileDownload{
		Provider: "archiveorg",
		ID:       "mobydick/mobydick.epub",
		Filename: "mobydick.epub",
		// ContentType intentionally blank — the archiveorg adapter shape.
		Body: body,
	}
	router := setupRegistryRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/archiveorg/http-download/mobydick/mobydick.epub", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}
	if string(w.Body.Bytes()) != string(body) {
		t.Fatalf("served bytes mismatch: got %q want %q", w.Body.Bytes(), body)
	}
	if ct := w.Header().Get("Content-Type"); ct != "application/octet-stream" {
		t.Fatalf("blank provider ContentType must fall back to application/octet-stream, got %q", ct)
	}
	if cd := w.Header().Get("Content-Disposition"); cd != `attachment; filename="mobydick.epub"` {
		t.Fatalf("Content-Disposition mismatch: got %q", cd)
	}
}

// TestHTTPDownload_GateRejectsProviderWithoutHTTPDownloadCap is the
// non-vacuity / discrimination guard (§6.AB clause 3): the /http-download/:id
// route MUST still enforce the CapHTTPDownload gate. A provider that does NOT
// declare HTTP_DOWNLOAD (here: torrent-only) MUST get 501, proving the positive
// test above passes because the capability is honored — not because the route
// blindly serves everyone.
func TestHTTPDownload_GateRejectsProviderWithoutHTTPDownloadCap(t *testing.T) {
	fp := &torrentOnlyProvider{}
	fp.fakeProvider.id = "rutracker"
	router := setupRegistryRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rutracker/http-download/1", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusNotImplemented {
		t.Fatalf("a provider without HTTP_DOWNLOAD must 501 on /http-download; got %d: %s", w.Code, w.Body.String())
	}
}

// torrentOnlyProvider declares TORRENT_DOWNLOAD but NOT HTTP_DOWNLOAD (the
// rutracker shape) — used to prove the CapHTTPDownload gate discriminates.
type torrentOnlyProvider struct {
	fakeProvider
}

func (p *torrentOnlyProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch, provider.CapTorrentDownload}
}
