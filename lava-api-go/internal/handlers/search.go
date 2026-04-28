// Package handlers — search.go implements GET /search, the third of the
// 13 rutracker routes. Mirrors the Phase 7 task 7.1 forum-handler shape:
//
//   - per-handler struct with a Cache and a ScraperClient
//   - per-route route-template constant matching the OpenAPI path
//   - lookup → hit → write verbatim; miss → scrape → marshal → store →
//     write
//   - sentinel errors funneled through writeUpstreamError (handlers.go)
//
// Eight optional query parameters (query, categories, author, authorId,
// sort, order, period, page) translate to a rutracker.SearchOpts struct.
// Enum parameters (sort/order/period) are validated server-side via
// gen.SearchSortTypeDto.Valid() etc.; an invalid enum produces HTTP 400
// BEFORE any upstream traffic, matching the Ktor proxy's
// `toEnumOrNull` short-circuit. The `page` parameter is silently nil on
// non-numeric input (matching Kotlin's `toIntOrNull` behaviour).
// Empty-string optionals are also nil (NOT &"").
package handlers

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// searchTTL: spec §6 says "seconds"; 1 minute balances cache hit rate
// against staleness for a search-results route (transient cache for
// repeated identical searches).
const searchTTL = 1 * time.Minute

// Per-route cache-key route template. MUST match the OpenAPI path
// template in api/openapi.yaml — the cross-backend parity test in
// Phase 10 will fail if it diverges.
const searchRouteTemplate = "/search"

// SearchHandler owns the GET /search route.
type SearchHandler struct {
	cache   Cache
	scraper ScraperClient
}

// NewSearchHandler is the constructor injected with the shared Deps.
func NewSearchHandler(deps *Deps) *SearchHandler {
	return &SearchHandler{cache: deps.Cache, scraper: deps.Scraper}
}

// GetSearch implements GET /search. All eight query parameters are
// optional. Invalid enum values produce HTTP 400 before any upstream
// traffic; invalid `page` values are silently treated as nil.
func (h *SearchHandler) GetSearch(c *gin.Context) {
	realm := auth.HashFromContext(c)
	cookie := auth.UpstreamCookie(c.Request)

	opts := rutracker.SearchOpts{}

	if v := c.Query("query"); v != "" {
		s := v
		opts.Query = &s
	}
	if v := c.Query("categories"); v != "" {
		s := v
		opts.Categories = &s
	}
	if v := c.Query("author"); v != "" {
		s := v
		opts.Author = &s
	}
	if v := c.Query("authorId"); v != "" {
		s := v
		opts.AuthorID = &s
	}
	if v := c.Query("sort"); v != "" {
		typed := gen.SearchSortTypeDto(v)
		if !typed.Valid() {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid sort"})
			return
		}
		opts.SortType = &typed
	}
	if v := c.Query("order"); v != "" {
		typed := gen.SearchSortOrderDto(v)
		if !typed.Valid() {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid order"})
			return
		}
		opts.SortOrder = &typed
	}
	if v := c.Query("period"); v != "" {
		typed := gen.SearchPeriodDto(v)
		if !typed.Valid() {
			c.JSON(http.StatusBadRequest, gin.H{"error": "invalid period"})
			return
		}
		opts.Period = &typed
	}
	// Empty-string `?page=` MUST forward as nil (NOT &0): the same
	// regression vector pinned by the forum handler's optional-int
	// table-driven test. Non-numeric values also fall back to nil,
	// matching Kotlin's `toIntOrNull` semantics.
	if pageStr := c.Query("page"); pageStr != "" {
		if page, err := strconv.Atoi(pageStr); err == nil {
			opts.Page = &page
		}
	}

	key := cache.Key(http.MethodGet, searchRouteTemplate, nil, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	page, err := h.scraper.GetSearchPage(c.Request.Context(), opts, cookie)
	if err != nil {
		writeUpstreamError(c, err)
		return
	}
	body, err := json.Marshal(page)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal search: " + err.Error()})
		return
	}
	// cache.Set error is non-fatal: response succeeds even if the write fails (caller already has the body).
	_ = h.cache.Set(c.Request.Context(), key, body, searchTTL)
	c.Data(http.StatusOK, "application/json", body)
}
