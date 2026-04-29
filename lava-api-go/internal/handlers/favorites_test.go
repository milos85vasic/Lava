// Package handlers — favorites_test.go pins the Phase 7 task 7.6
// contract for GET /favorites + POST /favorites/add/{id} +
// POST /favorites/remove/{id}. Mirrors the forum/search/topic/comments-
// add test shape: real Gin engine, real auth.GinMiddleware, fake Cache
// + fake ScraperClient defined in forum_test.go.
//
// Sixth Law alignment:
//   - clause 1 (same surfaces the user touches): every test dispatches
//     through ServeHTTP on a real Gin engine — no direct-function
//     shortcut into the handler.
//   - clause 2 (falsifiability): see commit message — one test was run
//     against deliberately broken handler code to confirm it can fail.
//   - clause 3 (primary assertion on user-visible state): the chief
//     assertion is on the HTTP status, response body bytes, and (for
//     the invalidation tests) on whether the cache rows actually
//     disappeared from the in-memory store after the POST. Call counts
//     are secondary.
//
// Favorites is the first per-user GET in the package. The realm-hash
// AffectsCacheKey test is the load-bearing pin that anonymous and
// authenticated callers occupy distinct cache slots. The
// DoesNotInvalidateOtherRealms test pins the realm-scoped invalidation
// contract: writes must NOT wipe other users' cached views.
package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// favoritesCacheKey is a test-side mirror of invalidateFavoritesCacheKey's
// key computation: same (method=GET, route=/favorites, pathVars=nil,
// empty-query, realm) tuple. Tests that pre-populate the fakeCache use
// this so the keys they store match the keys the handler will
// invalidate. If the production helper ever changes its key formula,
// this mirror MUST move with it; the tests would otherwise pre-store
// under a different key than the handler tries to delete and silently
// pass.
func favoritesCacheKey(realm string) string {
	return cache.Key(http.MethodGet, favoritesRouteTemplate, nil, url.Values{}, realm)
}

func sampleFavorites() *gen.FavoritesDto {
	// FavoritesDto.Topics is a []ForumTopicDto; an empty slice is the
	// simplest concrete value that JSON-marshals deterministically.
	return &gen.FavoritesDto{Topics: []gen.ForumTopicDto{}}
}

// ----- GET /favorites: happy path / realm-hash key / error mapping --------

func TestFavoritesHandler_GetFavorites_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{favoritesReturn: sampleFavorites()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleFavorites())

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/favorites", nil)
		req.Header.Set(auth.HeaderName, "tok")
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

	if scraper.favoritesCalls != 1 {
		t.Fatalf("favorites scraper calls=%d want 1 (cache should have served the second request)", scraper.favoritesCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
	if got, want := scraper.lastFavoritesCookie, "bb_session=tok"; got != want {
		t.Fatalf("upstream cookie=%q want %q", got, want)
	}
}

// TestFavoritesHandler_GetFavorites_AuthRealmHashAffectsCacheKey is the
// per-task foundation requirement: the realm hash flows into the
// cache key so anonymous and authenticated callers occupy distinct
// cache slots. Favorites is per-user, so this is load-bearing — a
// regression where the realm component is dropped would let user A
// serve user B their bookmarks.
func TestFavoritesHandler_GetFavorites_AuthRealmHashAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{favoritesReturn: sampleFavorites()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	// Anonymous request — first scraper call.
	wAnon := httptest.NewRecorder()
	rAnon := httptest.NewRequest(http.MethodGet, "/favorites", nil)
	r.ServeHTTP(wAnon, rAnon)
	if wAnon.Code != http.StatusOK {
		t.Fatalf("anon: status=%d want 200", wAnon.Code)
	}
	if scraper.favoritesCalls != 1 {
		t.Fatalf("anon: scraper calls=%d want 1", scraper.favoritesCalls)
	}

	// Authenticated request with a token — different realm hash → cache
	// miss → second scraper call.
	wAuth := httptest.NewRecorder()
	rAuth := httptest.NewRequest(http.MethodGet, "/favorites", nil)
	rAuth.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth, rAuth)
	if wAuth.Code != http.StatusOK {
		t.Fatalf("auth: status=%d want 200", wAuth.Code)
	}
	if scraper.favoritesCalls != 2 {
		t.Fatalf("auth: scraper calls=%d want 2 (anon and realm-keyed must use distinct cache slots)", scraper.favoritesCalls)
	}

	// Same Auth-Token again → cache hit, scraper count unchanged.
	wAuth2 := httptest.NewRecorder()
	rAuth2 := httptest.NewRequest(http.MethodGet, "/favorites", nil)
	rAuth2.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth2, rAuth2)
	if wAuth2.Code != http.StatusOK {
		t.Fatalf("auth-2: status=%d want 200", wAuth2.Code)
	}
	if scraper.favoritesCalls != 2 {
		t.Fatalf("auth-2: scraper calls=%d want 2 (realm-keyed entry should have served the second hit)", scraper.favoritesCalls)
	}

	// Two cache entries: one anon, one realm.
	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (anon entry + realm entry)", got)
	}
}

func TestFavoritesHandler_GetFavorites_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{favoritesErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/favorites", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

func TestFavoritesHandler_GetFavorites_Forbidden_Returns403(t *testing.T) {
	scraper := &fakeScraper{favoritesErr: rutracker.ErrForbidden}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/favorites", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("status=%d want 403; body=%s", w.Code, w.Body.String())
	}
}

func TestFavoritesHandler_GetFavorites_Unauthorized_Returns401(t *testing.T) {
	scraper := &fakeScraper{favoritesErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/favorites", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
}

func TestFavoritesHandler_GetFavorites_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{favoritesErr: errors.New("upstream-broken")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/favorites", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// ----- POST /favorites/add/{id}: happy / silent-false / errors / invalidation

func TestFavoritesHandler_AddFavorite_HappyPath_True(t *testing.T) {
	scraper := &fakeScraper{addFavoriteResult: true}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/add/42", nil)
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
	if scraper.addFavoriteCalls != 1 {
		t.Fatalf("AddFavorite calls=%d want 1", scraper.addFavoriteCalls)
	}
	if scraper.lastAddFavoriteID != "42" {
		t.Fatalf("AddFavorite id=%q want %q", scraper.lastAddFavoriteID, "42")
	}
	if got, want := scraper.lastAddFavoriteCk, "bb_session=tok"; got != want {
		t.Fatalf("AddFavorite cookie=%q want %q", got, want)
	}
}

func TestFavoritesHandler_AddFavorite_SilentFalse(t *testing.T) {
	scraper := &fakeScraper{addFavoriteResult: false}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/add/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200 (silent reject is still 200, the JSON bool carries the signal); body=%s", w.Code, w.Body.String())
	}
	var got bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body not json bool: %v (%s)", err, w.Body.String())
	}
	if got {
		t.Fatalf("body=%v want false", got)
	}
	// The silent-reject path STILL invalidates the writer's GET
	// /favorites entry (spec §6.1, mirroring comments-add): a
	// silently-rejected write may still have moved server state forward
	// and the next GET should refetch.
	if c.invalidateCalls != 1 {
		t.Fatalf("invalidateCalls=%d want 1 (silent-reject still invalidates GET /favorites)", c.invalidateCalls)
	}
}

func TestFavoritesHandler_AddFavorite_EmptyCookie_Returns401(t *testing.T) {
	// The scraper enforces empty-cookie ⇒ ErrUnauthorized; the handler
	// just forwards what it returns. We model that by programming the
	// fake to return ErrUnauthorized when the handler reaches it.
	scraper := &fakeScraper{addFavoriteErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	// No Auth-Token header → UpstreamCookie returns "" → scraper would
	// hit the empty-cookie branch and return ErrUnauthorized.
	req := httptest.NewRequest(http.MethodPost, "/favorites/add/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	if got := scraper.lastAddFavoriteCk; got != "" {
		t.Fatalf("scraper cookie=%q want \"\" (no Auth-Token header should produce an empty UpstreamCookie)", got)
	}
}

func TestFavoritesHandler_AddFavorite_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{addFavoriteErr: errors.New("upstream-broken")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/add/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

func TestFavoritesHandler_AddFavorite_InvalidatesFavoritesKey(t *testing.T) {
	scraper := &fakeScraper{addFavoriteResult: true}
	c := newFakeCache()
	realm := authRealmHashFor("tok")

	// Pre-populate the GET /favorites cache key for the writer's realm,
	// using the same key formula the handler uses.
	key := favoritesCacheKey(realm)
	if err := c.Set(context.Background(), key, []byte(`{"stale":"favs"}`), 0); err != nil {
		t.Fatalf("seed key: %v", err)
	}
	if c.size() != 1 {
		t.Fatalf("seed: cache size=%d want 1", c.size())
	}

	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/add/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}

	// PRIMARY ASSERTION: user-visible cache state. After the POST, the
	// next GET /favorites with the same Auth-Token would miss-and-refetch
	// — proven by the key being absent from the cache.
	if c.has(key) {
		t.Errorf("after POST: GET /favorites key still present; want invalidated")
	}
}

func TestFavoritesHandler_AddFavorite_DoesNotInvalidateOtherRealms(t *testing.T) {
	scraper := &fakeScraper{addFavoriteResult: true}
	c := newFakeCache()
	realmA := authRealmHashFor("tokA")
	realmB := authRealmHashFor("tokB")
	if realmA == realmB {
		t.Fatalf("test setup: realmA and realmB must differ; both = %q", realmA)
	}

	keyA := favoritesCacheKey(realmA)
	keyB := favoritesCacheKey(realmB)
	if err := c.Set(context.Background(), keyA, []byte("favs-A"), 0); err != nil {
		t.Fatalf("seed keyA: %v", err)
	}
	if err := c.Set(context.Background(), keyB, []byte("favs-B"), 0); err != nil {
		t.Fatalf("seed keyB: %v", err)
	}

	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	// POST as realm A.
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/add/42", nil)
	req.Header.Set(auth.HeaderName, "tokA")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}

	if c.has(keyA) {
		t.Errorf("keyA still present; want invalidated")
	}
	// PRIMARY ASSERTION: the OTHER-realm entry must NOT have been
	// touched. A regression where invalidation matched on route only
	// (ignoring the realm component, e.g. realm="" instead of the
	// writer's realm) would clear keyB too.
	if !c.has(keyB) {
		t.Errorf("keyB was invalidated by a POST as realm A; invalidation must be scoped to the writer's realm")
	}
}

// ----- POST /favorites/remove/{id}: mirrors AddFavorite ---------------------

func TestFavoritesHandler_RemoveFavorite_HappyPath_True(t *testing.T) {
	scraper := &fakeScraper{removeFavoriteResult: true}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/remove/42", nil)
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
	if scraper.removeFavoriteCalls != 1 {
		t.Fatalf("RemoveFavorite calls=%d want 1", scraper.removeFavoriteCalls)
	}
	if scraper.lastRemoveFavoriteID != "42" {
		t.Fatalf("RemoveFavorite id=%q want %q", scraper.lastRemoveFavoriteID, "42")
	}
	if got, want := scraper.lastRemoveFavoriteCk, "bb_session=tok"; got != want {
		t.Fatalf("RemoveFavorite cookie=%q want %q", got, want)
	}
}

func TestFavoritesHandler_RemoveFavorite_SilentFalse(t *testing.T) {
	scraper := &fakeScraper{removeFavoriteResult: false}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/remove/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	var got bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body not json bool: %v (%s)", err, w.Body.String())
	}
	if got {
		t.Fatalf("body=%v want false", got)
	}
	if c.invalidateCalls != 1 {
		t.Fatalf("invalidateCalls=%d want 1 (silent-reject still invalidates GET /favorites)", c.invalidateCalls)
	}
}

func TestFavoritesHandler_RemoveFavorite_EmptyCookie_Returns401(t *testing.T) {
	scraper := &fakeScraper{removeFavoriteErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/remove/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	if got := scraper.lastRemoveFavoriteCk; got != "" {
		t.Fatalf("scraper cookie=%q want \"\" (no Auth-Token header should produce an empty UpstreamCookie)", got)
	}
}

func TestFavoritesHandler_RemoveFavorite_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{removeFavoriteErr: errors.New("upstream-broken")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/remove/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

func TestFavoritesHandler_RemoveFavorite_InvalidatesFavoritesKey(t *testing.T) {
	scraper := &fakeScraper{removeFavoriteResult: true}
	c := newFakeCache()
	realm := authRealmHashFor("tok")

	key := favoritesCacheKey(realm)
	if err := c.Set(context.Background(), key, []byte(`{"stale":"favs"}`), 0); err != nil {
		t.Fatalf("seed key: %v", err)
	}

	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/remove/42", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}

	if c.has(key) {
		t.Errorf("after POST: GET /favorites key still present; want invalidated")
	}
}

func TestFavoritesHandler_RemoveFavorite_DoesNotInvalidateOtherRealms(t *testing.T) {
	scraper := &fakeScraper{removeFavoriteResult: true}
	c := newFakeCache()
	realmA := authRealmHashFor("tokA")
	realmB := authRealmHashFor("tokB")
	if realmA == realmB {
		t.Fatalf("test setup: realmA and realmB must differ; both = %q", realmA)
	}

	keyA := favoritesCacheKey(realmA)
	keyB := favoritesCacheKey(realmB)
	if err := c.Set(context.Background(), keyA, []byte("favs-A"), 0); err != nil {
		t.Fatalf("seed keyA: %v", err)
	}
	if err := c.Set(context.Background(), keyB, []byte("favs-B"), 0); err != nil {
		t.Fatalf("seed keyB: %v", err)
	}

	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/favorites/remove/42", nil)
	req.Header.Set(auth.HeaderName, "tokA")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}

	if c.has(keyA) {
		t.Errorf("keyA still present; want invalidated")
	}
	if !c.has(keyB) {
		t.Errorf("keyB was invalidated by a POST as realm A; invalidation must be scoped to the writer's realm")
	}
}

// ----- registration ---------------------------------------------------------

func TestRegister_RegistersFavoritesRoutes(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"GET /favorites":             false,
		"POST /favorites/add/:id":    false,
		"POST /favorites/remove/:id": false,
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
