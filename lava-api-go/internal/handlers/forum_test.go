// Package handlers — forum_test.go pins the Phase 7 handler-pattern
// contract for the two forum routes. These tests exercise the
// production handler code through a real Gin engine (no shortcut into
// the handler function) using:
//
//   - a fake ScraperClient that records call counts + arguments and can
//     be programmed to return errors
//   - an in-memory fake Cache that exhibits the same hit/miss surface
//     handlers consume; the real cache.Client is exercised by
//     internal/cache/integration_test.go against real Postgres
//   - the real auth.GinMiddleware, so realm-keyed caching is exercised
//     end-to-end and a missing middleware install would surface as a
//     test failure rather than silently masking a cache-key bug
//
// Sixth Law alignment:
//   - clause 1 (same surfaces the user touches): requests are dispatched
//     through ServeHTTP on a real Gin engine — the same router code
//     that runs in production. No direct-function shortcut.
//   - clause 3 (primary assertion on user-visible state): every test's
//     chief assertion is on the HTTP response status and body bytes —
//     the wire shape an API client receives. Scraper-call counts are
//     secondary assertions used to prove the cache short-circuited.
//   - clause 2 (falsifiability): see commit message — one test was run
//     against a deliberately broken handler to confirm it can fail.
package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// fakeCache is a minimal in-memory Cache that satisfies the handlers.Cache
// surface. No TTL expiry simulation — handler tests don't need it (the
// cache.Client integration test pins TTL semantics against real Postgres).
type fakeCache struct {
	mu    sync.Mutex
	store map[string][]byte
}

func newFakeCache() *fakeCache { return &fakeCache{store: map[string][]byte{}} }

func (f *fakeCache) Get(_ context.Context, key string) ([]byte, cache.Outcome, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	v, ok := f.store[key]
	if !ok {
		return nil, cache.OutcomeMiss, nil
	}
	// Return a copy so test mutations cannot poison the cache state.
	out := make([]byte, len(v))
	copy(out, v)
	return out, cache.OutcomeHit, nil
}

func (f *fakeCache) Set(_ context.Context, key string, value []byte, _ time.Duration) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	stored := make([]byte, len(value))
	copy(stored, value)
	f.store[key] = stored
	return nil
}

func (f *fakeCache) Invalidate(_ context.Context, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.store, key)
	return nil
}

func (f *fakeCache) size() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.store)
}

// fakeScraper satisfies ScraperClient. Records call counts and the most
// recent argument values so tests can assert pass-through behavior.
type fakeScraper struct {
	mu sync.Mutex

	forumCalls       int
	lastForumCookie  string
	forumReturn      *gen.ForumDto
	forumErr         error

	categoryCalls    int
	lastCategoryID   string
	lastCategoryPage *int
	lastCategoryCk   string
	categoryReturn   *gen.CategoryPageDto
	categoryErr      error
}

func (f *fakeScraper) GetForum(_ context.Context, cookie string) (*gen.ForumDto, error) {
	f.mu.Lock()
	f.forumCalls++
	f.lastForumCookie = cookie
	r, e := f.forumReturn, f.forumErr
	f.mu.Unlock()
	return r, e
}

func (f *fakeScraper) GetCategoryPage(_ context.Context, forumID string, page *int, cookie string) (*gen.CategoryPageDto, error) {
	f.mu.Lock()
	f.categoryCalls++
	f.lastCategoryID = forumID
	if page != nil {
		v := *page
		f.lastCategoryPage = &v
	} else {
		f.lastCategoryPage = nil
	}
	f.lastCategoryCk = cookie
	r, e := f.categoryReturn, f.categoryErr
	f.mu.Unlock()
	return r, e
}

// newTestRouter mirrors what cmd/lava-api-go does in production: install
// the auth middleware first, then Register the routes. Tests that omit
// the middleware would silently always-empty the realm hash, masking
// cache-key bugs — so the helper is mandatory.
func newTestRouter(deps *Deps) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(auth.GinMiddleware())
	Register(r, deps)
	return r
}

func sampleForum() *gen.ForumDto {
	id := "1"
	return &gen.ForumDto{Children: []gen.CategoryDto{{Id: &id, Name: "Top"}}}
}

func sampleCategory(id string, page int32) *gen.CategoryPageDto {
	return &gen.CategoryPageDto{
		Category: gen.CategoryDto{Id: &id, Name: "Cat-" + id},
		Page:     page,
		Pages:    1,
	}
}

func TestForumHandler_GetForum_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{forumReturn: sampleForum()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleForum())

	for i, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/forum", nil)
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
		_ = i
	}

	if scraper.forumCalls != 1 {
		t.Fatalf("forum scraper calls=%d want 1 (cache should have served the second request)", scraper.forumCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
}

func TestForumHandler_GetForum_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{forumErr: errors.New("boom")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not json: %v (%s)", err, w.Body.String())
	}
	if errMsg, _ := body["error"].(string); errMsg == "" || errMsg != "boom" {
		t.Fatalf("error=%q want %q", errMsg, "boom")
	}
}

func TestForumHandler_GetCategoryPage_PathParamIdForwarded(t *testing.T) {
	scraper := &fakeScraper{categoryReturn: sampleCategory("123", 1)}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleCategory("123", 1))

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/123", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if string(w.Body.Bytes()) != string(want) {
		t.Fatalf("body=%s want %s", w.Body.String(), string(want))
	}
	if scraper.categoryCalls != 1 {
		t.Fatalf("category scraper calls=%d want 1", scraper.categoryCalls)
	}
	if scraper.lastCategoryID != "123" {
		t.Fatalf("forumID=%q want 123", scraper.lastCategoryID)
	}
	if scraper.lastCategoryPage != nil {
		t.Fatalf("page=%v want nil (no ?page query)", *scraper.lastCategoryPage)
	}
	if got, want := scraper.lastCategoryCk, "bb_session=tok"; got != want {
		t.Fatalf("upstream cookie=%q want %q", got, want)
	}
}

func TestForumHandler_GetCategoryPage_PageQueryForwarded(t *testing.T) {
	scraper := &fakeScraper{categoryReturn: sampleCategory("123", 2)}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/123?page=2", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.lastCategoryPage == nil || *scraper.lastCategoryPage != 2 {
		got := "nil"
		if scraper.lastCategoryPage != nil {
			got = "&" + http.StatusText(*scraper.lastCategoryPage)
		}
		t.Fatalf("page ptr=%v want &2", got)
	}

	// Sub-case: invalid value → falls back to nil (Ktor parity).
	scraper2 := &fakeScraper{categoryReturn: sampleCategory("123", 1)}
	r2 := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper2})
	w2 := httptest.NewRecorder()
	req2 := httptest.NewRequest(http.MethodGet, "/forum/123?page=notanumber", nil)
	r2.ServeHTTP(w2, req2)
	if w2.Code != http.StatusOK {
		t.Fatalf("invalid page: status=%d want 200", w2.Code)
	}
	if scraper2.lastCategoryPage != nil {
		t.Fatalf("invalid page should fall back to nil; got %d", *scraper2.lastCategoryPage)
	}
}

func TestForumHandler_GetCategoryPage_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{categoryErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/999", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

func TestForumHandler_GetCategoryPage_Forbidden_Returns403(t *testing.T) {
	scraper := &fakeScraper{categoryErr: rutracker.ErrForbidden}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/77", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("status=%d want 403; body=%s", w.Code, w.Body.String())
	}
}

func TestForumHandler_GetCategoryPage_CircuitOpen_Returns503(t *testing.T) {
	scraper := &fakeScraper{categoryErr: rutracker.ErrCircuitOpen}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/12", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want 503; body=%s", w.Code, w.Body.String())
	}
}

func TestForumHandler_GetForum_AuthRealmHashAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{forumReturn: sampleForum()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	// Anonymous request — first scraper call.
	wAnon := httptest.NewRecorder()
	rAnon := httptest.NewRequest(http.MethodGet, "/forum", nil)
	r.ServeHTTP(wAnon, rAnon)
	if wAnon.Code != http.StatusOK {
		t.Fatalf("anon: status=%d want 200", wAnon.Code)
	}
	if scraper.forumCalls != 1 {
		t.Fatalf("anon: scraper calls=%d want 1", scraper.forumCalls)
	}

	// Authenticated request with a token — different realm hash → cache
	// miss → second scraper call.
	wAuth := httptest.NewRecorder()
	rAuth := httptest.NewRequest(http.MethodGet, "/forum", nil)
	rAuth.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth, rAuth)
	if wAuth.Code != http.StatusOK {
		t.Fatalf("auth: status=%d want 200", wAuth.Code)
	}
	if scraper.forumCalls != 2 {
		t.Fatalf("auth: scraper calls=%d want 2 (anon and realm-keyed must use distinct cache slots)", scraper.forumCalls)
	}

	// Same Auth-Token again → cache hit, scraper count unchanged.
	wAuth2 := httptest.NewRecorder()
	rAuth2 := httptest.NewRequest(http.MethodGet, "/forum", nil)
	rAuth2.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth2, rAuth2)
	if wAuth2.Code != http.StatusOK {
		t.Fatalf("auth-2: status=%d want 200", wAuth2.Code)
	}
	if scraper.forumCalls != 2 {
		t.Fatalf("auth-2: scraper calls=%d want 2 (realm-keyed entry should have served the second hit)", scraper.forumCalls)
	}

	// Two cache entries: one anon, one realm.
	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (anon entry + realm entry)", got)
	}
}

func TestRegister_RegistersForumRoutes(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"GET /forum":     false,
		"GET /forum/:id": false,
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
