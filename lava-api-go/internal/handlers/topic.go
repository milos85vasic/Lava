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
	"encoding/json"
	"net/http"
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal topic: " + err.Error()})
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal topic page: " + err.Error()})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, topicGroupTTL)
	c.Data(http.StatusOK, "application/json", body)
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
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal comments page: " + err.Error()})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, topicGroupTTL)
	c.Data(http.StatusOK, "application/json", body)
}
