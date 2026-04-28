// Package handlers — favorites.go implements GET /favorites,
// POST /favorites/add/{id}, and POST /favorites/remove/{id}, the
// tenth-through-twelfth of the 13 rutracker routes. Favorites is the
// first per-user route in the package: the response is the calling
// user's bookmark list, so the cache key MUST include the auth realm
// hash (mirroring every other GET handler) and the response TTL is
// short — spec §6 calls it "seconds" — to bound the staleness window
// after a write.
//
// Wire shape (per api/openapi.yaml):
//
//   - GET /favorites  → JSON FavoritesDto. Auth-Token expected; an
//     anonymous request gets back the upstream's empty-bookmarks page,
//     which the API mirrors verbatim (no synthetic ErrUnauthorized).
//   - POST /favorites/add/{id} / /favorites/remove/{id} → JSON boolean.
//     Empty cookie → ErrUnauthorized → 401 via writeUpstreamError; the
//     scraper short-circuits before any upstream traffic. The boolean
//     mirrors rutracker's "did the upstream accept it" signal: on a
//     three-step flow (fetch form_token → POST → check response),
//     true iff the success sentence appears.
//
// Cache invalidation (spec §6.1, mirroring the Phase 7 task 7.4
// comments-add pattern):
//
//	On any non-error outcome from the scraper — TRUE OR FALSE — the
//	writer invalidates the cached GET /favorites entry for the
//	WRITER'S realm. Realm-scoped (not global) because cache.Key is a
//	deterministic hash of (method, route, pathVars, query, realm) and
//	we don't have a "delete by route prefix" primitive — global
//	invalidation would either require iterating every realm or
//	switching cache schemas. Realm-scoping keeps the API cheap at the
//	cost of a brief cross-user staleness window bounded by
//	favoritesTTL (1 minute).
//
//	The silent-FALSE branch also invalidates: an upstream "no" can
//	still mean server state moved (e.g. anti-spam counter), and
//	serving the moment-stale view is a worse default than a fresh
//	refetch on the next GET — same rationale as comments-add.
package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
)

// favoritesTTL: spec §6 says "seconds" for the favorites GET. 1 minute
// mirrors the search-results TTL (also a "seconds"-class route per spec
// §6) and bounds the staleness window after a successful add/remove
// while keeping the hit rate non-trivial for back-to-back GETs.
const favoritesTTL = 1 * time.Minute

// Per-route cache-key route templates. These MUST match the OpenAPI
// path templates in api/openapi.yaml — the cross-backend parity test
// in Phase 10 will fail if they diverge.
//
// favoritesAddRouteTemplate / favoritesRemoveRouteTemplate are
// documentation-grade: the write routes do not read from the cache, so
// the constants are not used to compute a key. The single key
// invalidated on success is owned by GET /favorites and uses
// favoritesRouteTemplate via invalidateFavoritesCacheKey.
const (
	favoritesRouteTemplate       = "/favorites"
	favoritesAddRouteTemplate    = "/favorites/add/{id}"
	favoritesRemoveRouteTemplate = "/favorites/remove/{id}"
)

// FavoritesHandler owns the three favorites-related routes (one read,
// two writes).
type FavoritesHandler struct {
	cache   Cache
	scraper ScraperClient
}

// NewFavoritesHandler is the constructor injected with the shared Deps.
func NewFavoritesHandler(deps *Deps) *FavoritesHandler {
	return &FavoritesHandler{cache: deps.Cache, scraper: deps.Scraper}
}

// GetFavorites implements GET /favorites. Returns FavoritesDto: the
// caller's bookmarked topics. Cached for favoritesTTL keyed on
// (method=GET, /favorites, no path vars, query, realm). The realm
// component is load-bearing — the response is per-user, and a missing
// realm would let user A serve user B their own bookmarks.
func (h *FavoritesHandler) GetFavorites(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)

	key := cache.Key(http.MethodGet, favoritesRouteTemplate, nil, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	favs, err := h.scraper.GetFavorites(c.Request.Context(), cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}
	body, err := json.Marshal(favs)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal favorites: " + err.Error()})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, favoritesTTL)
	c.Data(http.StatusOK, "application/json", body)
}

// invalidateFavoritesCacheKey is the favorites-scoped sibling of
// invalidateTopicCacheKeys (topic.go). Both write routes call this
// helper after a non-error scraper outcome so the next GET /favorites
// for the same realm refetches.
//
// The key produced here MUST match exactly the key GetFavorites
// produces when called with NO query parameters — same (method=GET,
// route, pathVars=nil, empty-query, realm) tuple. Paginated GETs
// (?page=N etc.) hash to different keys and are NOT invalidated; the
// 1-minute favoritesTTL keeps that staleness bounded and the Phase 10
// cross-backend parity test pins the wire shape.
//
// Realm scope: only the writer's realm hash is invalidated, NOT every
// realm's cached view. See the file-level comment for the rationale
// and trade-off.
//
// Invalidate's error is intentionally `_ =`'d: cache invalidation is
// best-effort, the favorite was already added/removed upstream, and
// the caller's response should still go out on a cache outage.
func invalidateFavoritesCacheKey(ctx context.Context, c Cache, realm string) {
	key := cache.Key(http.MethodGet, favoritesRouteTemplate, nil, url.Values{}, realm)
	_ = c.Invalidate(ctx, key)
}

// AddFavorite implements POST /favorites/add/{id}. Forwards (id, cookie)
// to the rutracker scraper. The scraper returns (true, nil) on accepted,
// (false, nil) on silent-reject, and a sentinel error otherwise:
//
//	(true, nil)  → 200 OK + JSON `true`,  invalidate GET /favorites for realm
//	(false, nil) → 200 OK + JSON `false`, invalidate GET /favorites for realm
//	(_, ErrUnauthorized | ErrForbidden | ErrNotFound | ErrCircuitOpen | …)
//	             → routed via writeUpstreamError (no invalidation —
//	             upstream rejected the write so server state didn't
//	             change; invalidating would force unnecessary refetches)
func (h *FavoritesHandler) AddFavorite(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")

	ok, err := h.scraper.AddFavorite(c.Request.Context(), id, cookie)
	if err != nil {
		// writeUpstreamError handles ErrUnauthorized → 401 for the
		// empty-cookie short-circuit inside rutracker.AddFavorite.
		writeUpstreamError(c, err)
		return
	}

	// Both ok==true and ok==false invalidate the realm's GET /favorites
	// cache: a silent-reject can still have moved server state forward,
	// and the next GET must see fresh data either way.
	invalidateFavoritesCacheKey(c.Request.Context(), h.cache, realm)

	c.JSON(http.StatusOK, ok)
}

// RemoveFavorite implements POST /favorites/remove/{id}. Identical
// shape to AddFavorite — see that function's comment for the wire-
// shape, error-mapping, and cache-invalidation contract. The only
// difference is the scraper method (RemoveFavorite vs AddFavorite),
// which dispatches the upstream `action=bookmark_delete` form payload.
func (h *FavoritesHandler) RemoveFavorite(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")

	ok, err := h.scraper.RemoveFavorite(c.Request.Context(), id, cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}

	invalidateFavoritesCacheKey(c.Request.Context(), h.cache, realm)

	c.JSON(http.StatusOK, ok)
}
