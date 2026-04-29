// Package handlers — topic.go implements GET /topic/{id}, GET
// /topic2/{id}, and GET /comments/{id}, the fourth-through-sixth of the
// 13 rutracker routes. Mirrors the Phase 7 task 7.1 forum-handler shape:
//
//   - per-handler struct with a Cache and a ScraperClient
//   - per-route route-template constant matching the OpenAPI path
//   - lookup → hit → write verbatim; miss → scrape → marshal → store →
//     write
//   - sentinel errors funneled through writeUpstreamError (handlers.go)
//
// All three routes share one TTL constant (5 minutes) on purpose: the
// Phase 7 task 7.4 cache-invalidation rule is that POST /comments/{id}/
// add MUST invalidate the cache entries for /topic/{id}, /topic2/{id},
// AND /comments/{id} for the same id. Pinning the same TTL keeps the
// invalidation symmetric — no key can outlive its sibling and serve a
// stale view that would otherwise be invalidated.
//
// Path-parameter and optional `page` query parameter handling matches
// the forum/search precedent: id read from c.Param("id"); page parsed
// via strconv.Atoi with silent-nil on empty / non-numeric / absent
// (Kotlin `toIntOrNull` parity).
package handlers

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
)

// topicGroupTTL is shared by all three topic-related routes per spec §6.
// Symmetric TTL keeps the Phase 7 task 7.4 cache invalidation simple:
// POST /comments/{id}/add invalidates all three keys for the same id;
// none can outlive the others.
const topicGroupTTL = 5 * time.Minute

// Per-route cache-key route templates. These MUST match the OpenAPI
// path templates in api/openapi.yaml — the cross-backend parity test
// in Phase 10 will fail if they diverge.
const (
	topicRouteTemplate     = "/topic/{id}"
	topicPageRouteTemplate = "/topic2/{id}"
	commentsRouteTemplate  = "/comments/{id}"
)

// TopicHandler owns the three topic-related GET routes.
type TopicHandler struct {
	cache   Cache
	scraper ScraperClient
}

// NewTopicHandler is the constructor injected with the shared Deps.
func NewTopicHandler(deps *Deps) *TopicHandler {
	return &TopicHandler{cache: deps.Cache, scraper: deps.Scraper}
}

// parseOptionalPage centralises the silent-nil-on-bad-input semantics
// (Kotlin `toIntOrNull` parity). Empty / absent / non-numeric all map to
// nil. Used by all three handlers below.
func parseOptionalPage(c *gin.Context) *int {
	pageStr := c.Query("page")
	if pageStr == "" {
		return nil
	}
	page, err := strconv.Atoi(pageStr)
	if err != nil {
		return nil
	}
	return &page
}

// GetTopic implements GET /topic/{id}. Returns the discriminated
// ForumTopicDto (Topic | Torrent | CommentsPage variant — the
// scraper picks based on upstream content).
func (h *TopicHandler) GetTopic(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")
	pagePtr := parseOptionalPage(c)

	pathVars := map[string]string{"id": id}
	key := cache.Key(http.MethodGet, topicRouteTemplate, pathVars, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	topic, err := h.scraper.GetTopic(c.Request.Context(), id, pagePtr, cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}
	body, err := json.Marshal(topic)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, topicGroupTTL)
	c.Data(http.StatusOK, "application/json", body)
}

// GetTopicPage implements GET /topic2/{id}. Returns the modern
// non-polymorphic TopicPageDto wrapper (id/title/author/category plus
// optional torrentData and a commentsPage).
func (h *TopicHandler) GetTopicPage(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")
	pagePtr := parseOptionalPage(c)

	pathVars := map[string]string{"id": id}
	key := cache.Key(http.MethodGet, topicPageRouteTemplate, pathVars, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	page, err := h.scraper.GetTopicPage(c.Request.Context(), id, pagePtr, cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}
	body, err := json.Marshal(page)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, topicGroupTTL)
	c.Data(http.StatusOK, "application/json", body)
}

// invalidateTopicCacheKeys is the Phase 7 task 7.4 cache-invalidation
// helper. POST /comments/{id}/add MUST invalidate the cached read
// responses for the same topic id across the three sibling routes —
// /topic/{id}, /topic2/{id}, and /comments/{id} — so the next GET
// reflects the post-write state.
//
// The keys produced here MUST match exactly the keys the three GET
// handlers above produce when called with NO query parameters: same
// (method, routeTemplate, pathVars, query, realm) tuple. Paginated GETs
// (?page=2 etc.) hash to different keys and are NOT invalidated; the
// 5-minute topicGroupTTL keeps the cross-page staleness window short
// and the Phase 10 cross-backend parity test pins the wire shape.
//
// Realm scope: invalidation is realm-keyed — only the writer's realm
// hash is invalidated, NOT every realm's cached view of the topic. This
// keeps the cache.Key API deterministic (no "delete by prefix" needed)
// at the cost of a brief cross-user staleness window bounded by
// topicGroupTTL.
//
// Each Invalidate error is intentionally `_ =`'d: cache invalidation
// is best-effort, the comment-post itself is what matters, and the
// caller's response should still go out on a cache outage.
func invalidateTopicCacheKeys(ctx context.Context, c Cache, topicID, realm string) {
	pathVars := map[string]string{"id": topicID}
	emptyQuery := url.Values{}
	for _, route := range []string{topicRouteTemplate, topicPageRouteTemplate, commentsRouteTemplate} {
		key := cache.Key(http.MethodGet, route, pathVars, emptyQuery, realm)
		// Best-effort: Invalidate may fail on a transient cache outage;
		// the comment was already posted upstream.
		_ = c.Invalidate(ctx, key)
	}
}

// GetCommentsPage implements GET /comments/{id}. Returns the pure
// CommentsPageDto (the CommentsPage variant of ForumTopicDto). 1-based
// page query parameter; absent → first page.
func (h *TopicHandler) GetCommentsPage(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	id := c.Param("id")
	pagePtr := parseOptionalPage(c)

	pathVars := map[string]string{"id": id}
	key := cache.Key(http.MethodGet, commentsRouteTemplate, pathVars, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	page, err := h.scraper.GetCommentsPage(c.Request.Context(), id, pagePtr, cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}
	body, err := json.Marshal(page)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, topicGroupTTL)
	c.Data(http.StatusOK, "application/json", body)
}
