// Package handlers — index_test.go pins the Phase 7 task 7.7 contract
// for GET / and GET /index. Both routes resolve to the same handler
// (IndexHandler.GetIndex); the tests pin both paths so a Register
// regression that wires only one of them surfaces here, not in
// production traffic.
//
// Sixth Law alignment:
//   - clause 1 (same surfaces the user touches): all tests dispatch
//     through ServeHTTP on a real Gin engine — no direct-function
//     shortcut into the handler.
//   - clause 2 (falsifiability): see commit message — one test was
//     run against deliberately broken handler code to confirm it can
//     fail.
//   - clause 3 (primary assertion on user-visible state): each test's
//     chief assertion is on the HTTP status, response body bytes
//     (the JSON boolean), and (for the no-cache test) on the scraper
//     call count proving the cache layer was bypassed.
package handlers

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// TestIndexHandler_GetRoot_HappyPath_ReturnsTrue — GET / with a valid
// Auth-Token returns the JSON boolean from CheckAuthorised. Primary
// assertion is on the response body bytes (the wire shape the Android
// liveness check parses).
func TestIndexHandler_GetRoot_HappyPath_ReturnsTrue(t *testing.T) {
	scraper := &fakeScraper{checkAuthResult: true}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	var got bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body not json bool: %v (%s)", err, w.Body.String())
	}
	if !got {
		t.Fatalf("body=%v want true", got)
	}
	if scraper.checkAuthCalls != 1 {
		t.Fatalf("CheckAuthorised calls=%d want 1", scraper.checkAuthCalls)
	}
	if got, want := scraper.lastCheckAuthCk, "bb_session=tok"; got != want {
		t.Fatalf("scraper cookie=%q want %q", got, want)
	}
}

// TestIndexHandler_GetIndex_HappyPath_ReturnsFalse — GET /index with
// no Auth-Token returns false (the OpenAPI-mandated anonymous-cookie
// pass-through). The scraper cookie MUST be empty — no Auth-Token →
// no upstream cookie. Pins both the /index route AND the
// anonymous-allowed divergence from the state-mutating routes.
func TestIndexHandler_GetIndex_HappyPath_ReturnsFalse(t *testing.T) {
	scraper := &fakeScraper{checkAuthResult: false}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/index", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	var got bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body not json bool: %v (%s)", err, w.Body.String())
	}
	if got {
		t.Fatalf("body=%v want false (anonymous request)", got)
	}
	if scraper.checkAuthCalls != 1 {
		t.Fatalf("CheckAuthorised calls=%d want 1 (anonymous health probe MUST traverse to upstream)", scraper.checkAuthCalls)
	}
	if scraper.lastCheckAuthCk != "" {
		t.Fatalf("scraper cookie=%q want empty (no Auth-Token header)", scraper.lastCheckAuthCk)
	}
}

// TestIndexHandler_GetRoot_NoCache_TwoCallsHitScraperTwice — /, /index
// MUST NOT cache. Two consecutive GETs MUST produce two scraper calls,
// regardless of any caching layer. Spec §6 says health probes are
// freshness-over-performance. Mirror of the /download/{id} test in
// torrent_test.go.
func TestIndexHandler_GetRoot_NoCache_TwoCallsHitScraperTwice(t *testing.T) {
	scraper := &fakeScraper{checkAuthResult: true}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.Header.Set(auth.HeaderName, "tok")
		r.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("call %d: status=%d want 200; body=%s", i, w.Code, w.Body.String())
		}
	}

	if scraper.checkAuthCalls != 2 {
		t.Fatalf("CheckAuthorised calls=%d want 2 (health probe must NOT cache; freshness > performance)", scraper.checkAuthCalls)
	}
	if c.size() != 0 {
		t.Fatalf("cache size=%d want 0 (handler must NOT write to cache)", c.size())
	}
}

// TestIndexHandler_GetRoot_ScraperError_Returns502 — pin the default
// arm of writeUpstreamError. A generic upstream error (e.g. 500
// response surfacing as a wrapped error) maps to 502 Bad Gateway.
func TestIndexHandler_GetRoot_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{checkAuthErr: errors.New("upstream-broken")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// TestIndexHandler_GetRoot_CircuitOpen_Returns503 — pin the
// ErrCircuitOpen → 503 mapping. /index is exactly the route the
// Android client polls to discover that the upstream went down, so
// surfacing 503 (rather than the default 502) is load-bearing for the
// client's UI to show "rutracker is unreachable" instead of a generic
// upstream-broken message.
func TestIndexHandler_GetRoot_CircuitOpen_Returns503(t *testing.T) {
	scraper := &fakeScraper{checkAuthErr: rutracker.ErrCircuitOpen}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want 503; body=%s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, "rutracker") {
		t.Fatalf("body=%q does not mention %q", body, "rutracker")
	}
}

// TestRegister_RegistersIndexLoginCaptchaRoutes — pin the Phase 7
// task 7.7 wiring for all four new routes. A regression that drops
// any of them from Register would surface here, not in production
// traffic. Closes the per-task foundation requirement: every task
// adds a Register test for its routes.
func TestRegister_RegistersIndexLoginCaptchaRoutes(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"GET /":              false,
		"GET /index":         false,
		"POST /login":        false,
		"GET /captcha/:path": false,
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
