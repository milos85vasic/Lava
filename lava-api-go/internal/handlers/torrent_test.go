// Package handlers — torrent_test.go pins the Phase 7 task 7.5
// handler-pattern contract for GET /torrent/{id} (JSON metadata,
// 1-hour TTL) and GET /download/{id} (binary stream, NEVER cached).
//
// Sixth Law alignment:
//   - clause 1 (same surfaces the user touches): requests are dispatched
//     through ServeHTTP on a real Gin engine — the same router code
//     that runs in production. No direct-function shortcut.
//   - clause 3 (primary assertion on user-visible state): every test's
//     chief assertion is on the HTTP response status, body bytes, or
//     response headers — the wire shape an API client receives.
//     Scraper-call counts and recorded-arg fields are secondary
//     assertions used to prove cache short-circuit behaviour or path-
//     param forwarding. The /download/{id} body byte-equality is the
//     load-bearing assertion for the never-cached branch — if the
//     handler ever started caching the binary stream, byte-equality
//     would still hold but the second-call scraper count would betray
//     it.
//   - clause 2 (falsifiability): see commit message — one test was run
//     against a deliberately broken handler to confirm it can fail.
package handlers

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// sampleTorrent returns a deterministic *gen.ForumTopicDtoTorrent
// suitable for assertion via byte-equality against the handler's
// JSON output.
func sampleTorrent(id string) *gen.ForumTopicDtoTorrent {
	title := "Sample torrent " + id
	return &gen.ForumTopicDtoTorrent{
		Type:  gen.Torrent,
		Id:    id,
		Title: title,
	}
}

func TestTorrentHandler_GetTorrent_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{torrentReturn: sampleTorrent("42")}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleTorrent("42"))

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/torrent/42", nil)
		r.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("%s: status=%d want 200; body=%s", label, w.Code, w.Body.String())
		}
		if got := w.Body.Bytes(); string(got) != string(want) {
			t.Fatalf("%s: body=%s want %s", label, string(got), string(want))
		}
		if ct := w.Header().Get("Content-Type"); ct != "application/json" {
			t.Fatalf("%s: Content-Type=%q want application/json", label, ct)
		}
	}

	if scraper.torrentCalls != 1 {
		t.Fatalf("torrent scraper calls=%d want 1 (cache should have served the second request)", scraper.torrentCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
}

func TestTorrentHandler_GetTorrent_PathParamForwarded(t *testing.T) {
	scraper := &fakeScraper{torrentReturn: sampleTorrent("42")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/torrent/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.torrentCalls != 1 {
		t.Fatalf("torrent scraper calls=%d want 1", scraper.torrentCalls)
	}
	if scraper.lastTorrentID != "42" {
		t.Fatalf("torrent id=%q want %q", scraper.lastTorrentID, "42")
	}
	if got, want := scraper.lastTorrentCk, "bb_session=tok"; got != want {
		t.Fatalf("upstream cookie=%q want %q", got, want)
	}
}

func TestTorrentHandler_GetTorrent_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{torrentErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/torrent/999", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

func TestTorrentHandler_GetTorrent_Forbidden_Returns403(t *testing.T) {
	scraper := &fakeScraper{torrentErr: rutracker.ErrForbidden}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/torrent/77", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("status=%d want 403; body=%s", w.Code, w.Body.String())
	}
}

func TestTorrentHandler_GetTorrent_Unauthorized_Returns401(t *testing.T) {
	scraper := &fakeScraper{torrentErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/torrent/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
}

func TestTorrentHandler_GetTorrent_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{torrentErr: errors.New("boom")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/torrent/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not json: %v (%s)", err, w.Body.String())
	}
	if errMsg, _ := body["error"].(string); errMsg != "boom" {
		t.Fatalf("error=%q want %q", errMsg, "boom")
	}
}

// TestTorrentHandler_GetTorrent_AuthRealmHashAffectsCacheKey is the
// per-task realm-keyed-cache foundation requirement for the /torrent/{id}
// route — anon and realm-keyed entries MUST land in distinct cache slots
// so a privileged user's view never bleeds into the anon view (and vice
// versa). Mirrors TestForumHandler_GetCategoryPage_AuthRealmHashAffectsCacheKey.
func TestTorrentHandler_GetTorrent_AuthRealmHashAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{torrentReturn: sampleTorrent("42")}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	// Step 1: anonymous GET /torrent/42 — first scraper call, anon entry stored.
	wAnon := httptest.NewRecorder()
	rAnon := httptest.NewRequest(http.MethodGet, "/torrent/42", nil)
	r.ServeHTTP(wAnon, rAnon)
	if wAnon.Code != http.StatusOK {
		t.Fatalf("anon: status=%d want 200", wAnon.Code)
	}
	if scraper.torrentCalls != 1 {
		t.Fatalf("anon: scraper calls=%d want 1", scraper.torrentCalls)
	}

	// Step 2: realm-keyed GET /torrent/42 with Auth-Token: secret — different
	// cache key → cache miss → second scraper call.
	wAuth := httptest.NewRecorder()
	rAuth := httptest.NewRequest(http.MethodGet, "/torrent/42", nil)
	rAuth.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth, rAuth)
	if wAuth.Code != http.StatusOK {
		t.Fatalf("auth: status=%d want 200", wAuth.Code)
	}
	if scraper.torrentCalls != 2 {
		t.Fatalf("auth: scraper calls=%d want 2 (anon and realm-keyed must use distinct cache slots)", scraper.torrentCalls)
	}

	// Step 3: same Auth-Token again → cache hit, scraper count unchanged.
	wAuth2 := httptest.NewRecorder()
	rAuth2 := httptest.NewRequest(http.MethodGet, "/torrent/42", nil)
	rAuth2.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth2, rAuth2)
	if wAuth2.Code != http.StatusOK {
		t.Fatalf("auth-2: status=%d want 200", wAuth2.Code)
	}
	if scraper.torrentCalls != 2 {
		t.Fatalf("auth-2: scraper calls=%d want 2 (realm-keyed entry should have served the second hit)", scraper.torrentCalls)
	}

	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (anon entry + realm entry)", got)
	}
}

// TestTorrentHandler_GetDownload_HappyPath asserts the handler streams
// the binary bytes byte-for-byte and forwards the upstream
// Content-Disposition + Content-Type headers verbatim.
func TestTorrentHandler_GetDownload_HappyPath(t *testing.T) {
	wantBytes := []byte{0xde, 0xad, 0xbe, 0xef, 0x00, 0x42}
	wantDisposition := `attachment; filename="rutracker_42.torrent"`
	wantContentType := "application/x-bittorrent"

	scraper := &fakeScraper{torrentFileReturn: &rutracker.TorrentFile{
		Bytes:              wantBytes,
		ContentDisposition: wantDisposition,
		ContentType:        wantContentType,
	}}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/download/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%q", w.Code, w.Body.String())
	}
	if got := w.Body.Bytes(); string(got) != string(wantBytes) {
		t.Fatalf("body=%v want %v", got, wantBytes)
	}
	if got := w.Header().Get("Content-Disposition"); got != wantDisposition {
		t.Fatalf("Content-Disposition=%q want %q", got, wantDisposition)
	}
	if got := w.Header().Get("Content-Type"); got != wantContentType {
		t.Fatalf("Content-Type=%q want %q", got, wantContentType)
	}
	if scraper.lastTorrentFileID != "42" {
		t.Fatalf("scraper id=%q want %q", scraper.lastTorrentFileID, "42")
	}
}

// TestTorrentHandler_GetDownload_NeverCached pins spec §6: /download/{id}
// is NEVER cached at the API tier. Two GETs MUST produce two scraper
// calls. Cache invocation counter MUST NOT increment.
func TestTorrentHandler_GetDownload_NeverCached(t *testing.T) {
	wantBytes := []byte{0x01, 0x02, 0x03}
	scraper := &fakeScraper{torrentFileReturn: &rutracker.TorrentFile{
		Bytes:              wantBytes,
		ContentDisposition: `attachment; filename="rutracker_42.torrent"`,
		ContentType:        "application/x-bittorrent",
	}}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/download/42", nil)
		req.Header.Set(auth.HeaderName, "tok")
		r.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("%s: status=%d want 200; body=%q", label, w.Code, w.Body.String())
		}
		if got := w.Body.Bytes(); string(got) != string(wantBytes) {
			t.Fatalf("%s: body=%v want %v", label, got, wantBytes)
		}
	}

	// Two GETs MUST produce two scraper calls — caching the binary stream
	// would silently downgrade this assertion to 1.
	if scraper.torrentFileCalls != 2 {
		t.Fatalf("torrent-file scraper calls=%d want 2 (download must NEVER be cached)", scraper.torrentFileCalls)
	}
	// The cache MUST NOT have been touched at all by /download/{id}.
	if c.size() != 0 {
		t.Fatalf("cache size=%d want 0 (download must NEVER write to cache)", c.size())
	}
	if c.invalidateCalls != 0 {
		t.Fatalf("cache invalidate calls=%d want 0 (download must not invalidate either)", c.invalidateCalls)
	}
}

// TestTorrentHandler_GetDownload_EmptyCookie_Returns401 — anonymous
// download attempts surface as ErrUnauthorized → 401. The fake stands
// in for the rutracker.GetTorrentFile cookie==""→ErrUnauthorized
// short-circuit; the handler MUST funnel the error through
// writeUpstreamError without sneaking in an empty body or a 200.
func TestTorrentHandler_GetDownload_EmptyCookie_Returns401(t *testing.T) {
	scraper := &fakeScraper{torrentFileErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	// No Auth-Token header → empty cookie at the auth layer.
	req := httptest.NewRequest(http.MethodGet, "/download/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	if got, want := scraper.lastTorrentFileCk, ""; got != want {
		t.Fatalf("upstream cookie=%q want %q (no Auth-Token → empty upstream cookie)", got, want)
	}
}

func TestTorrentHandler_GetDownload_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{torrentFileErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/download/999", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

// TestTorrentHandler_GetDownload_DefaultsContentTypeWhenAbsent verifies
// the handler picks application/x-bittorrent when the upstream omits
// Content-Type — the OpenAPI declares the 200 media type, so an empty
// Content-Type would be a contract violation.
func TestTorrentHandler_GetDownload_DefaultsContentTypeWhenAbsent(t *testing.T) {
	wantBytes := []byte{0xa1, 0xb2}
	scraper := &fakeScraper{torrentFileReturn: &rutracker.TorrentFile{
		Bytes:              wantBytes,
		ContentDisposition: `attachment; filename="rutracker_42.torrent"`,
		ContentType:        "", // upstream forgot to set it
	}}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/download/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%q", w.Code, w.Body.String())
	}
	if got, want := w.Header().Get("Content-Type"), "application/x-bittorrent"; got != want {
		t.Fatalf("Content-Type=%q want %q (must default when upstream omits)", got, want)
	}
}

// TestTorrentHandler_GetDownload_OmitsContentDispositionWhenAbsent —
// when upstream did NOT set Content-Disposition, the handler MUST NOT
// invent one. An absent (or empty) header is the correct shape.
func TestTorrentHandler_GetDownload_OmitsContentDispositionWhenAbsent(t *testing.T) {
	wantBytes := []byte{0xab}
	scraper := &fakeScraper{torrentFileReturn: &rutracker.TorrentFile{
		Bytes:              wantBytes,
		ContentDisposition: "", // upstream did not set it
		ContentType:        "application/x-bittorrent",
	}}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/download/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%q", w.Code, w.Body.String())
	}
	if got := w.Header().Get("Content-Disposition"); got != "" {
		t.Fatalf("Content-Disposition=%q want \"\" (must be absent when upstream omits)", got)
	}
}

func TestRegister_RegistersTorrentRoutes(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"GET /torrent/:id":  false,
		"GET /download/:id": false,
	}
	for _, ri := range router.Routes() {
		key := ri.Method + " " + ri.Path
		if _, ok := want[key]; ok {
			want[key] = true
		}
	}
	for k, v := range want {
		if !v {
			t.Errorf("Register did not wire route %q", k)
		}
	}
}
