// Package handlers — forum.go implements GET /forum and GET /forum/{id},
// the first two of the 13 rutracker routes. Establishes the pattern
// every other Phase 7 handler file will copy:
//
//   - per-handler struct with a Cache and a ScraperClient
//   - per-route route-template constant matching the OpenAPI path
//   - lookup → hit → write verbatim; miss → scrape → marshal → store →
//     write
//   - sentinel errors funneled through writeUpstreamError (handlers.go)
//
// TTLs come from spec §6: the forum tree is mostly static (1h); a
// category page's torrent list churns more frequently (5m).
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

// Cache TTLs per spec §6. Forum tree is hours; category page is minutes.
const (
	forumTreeTTL    = 1 * time.Hour
	categoryPageTTL = 5 * time.Minute
)

// Per-route cache-key route templates. These MUST match the OpenAPI
// path templates in api/openapi.yaml — the cross-backend parity test
// in Phase 10 will fail if they diverge.
const (
	forumRouteTemplate    = "/forum"
	categoryRouteTemplate = "/forum/{id}"
)

// ForumHandler owns the two forum routes.
type ForumHandler struct {
	cache   Cache
	scraper ScraperClient
}

// NewForumHandler is the constructor injected with the shared Deps.
func NewForumHandler(deps *Deps) *ForumHandler {
	return &ForumHandler{cache: deps.Cache, scraper: deps.Scraper}
}

// GetForum implements GET /forum. The forum tree is mostly static, so
// the cache TTL is long.
func (h *ForumHandler) GetForum(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	key := cache.Key(http.MethodGet, forumRouteTemplate, nil, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	forum, err := h.scraper.GetForum(c.Request.Context(), cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}
	body, err := json.Marshal(forum)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, forumTreeTTL)
	c.Data(http.StatusOK, "application/json", body)
}

// GetCategoryPage implements GET /forum/{id}. The 1-based `page` query
// parameter is parsed via strconv.Atoi; invalid values fall back to nil
// (matching the Ktor proxy's `toIntOrNull()` semantics).
func (h *ForumHandler) GetCategoryPage(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)
	forumID := c.Param("id")

	var pagePtr *int
	if pageStr := c.Query("page"); pageStr != "" {
		if page, err := strconv.Atoi(pageStr); err == nil {
			pagePtr = &page
		}
	}

	pathVars := map[string]string{"id": forumID}
	key := cache.Key(http.MethodGet, categoryRouteTemplate, pathVars, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	page, err := h.scraper.GetCategoryPage(c.Request.Context(), forumID, pagePtr, cookie)
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
	_ = h.cache.Set(c.Request.Context(), key, body, categoryPageTTL)
	c.Data(http.StatusOK, "application/json", body)
}
