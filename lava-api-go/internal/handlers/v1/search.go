package v1

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/provider"
)

const searchTTL = 1 * time.Minute

const searchRouteTemplate = "/v1/{provider}/search"

type SearchHandler struct {
	cache Cache
}

func NewSearchHandler(deps *Deps) *SearchHandler {
	return &SearchHandler{cache: deps.Cache}
}

func (h *SearchHandler) GetSearch(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)

	opts := provider.SearchOpts{}
	if v := c.Query("query"); v != "" {
		opts.Query = v
	}
	if v := c.Query("sort"); v != "" {
		opts.Sort = v
	}
	if v := c.Query("order"); v != "" {
		opts.Order = v
	}
	if v := c.Query("category"); v != "" {
		opts.Category = v
	}
	if pageStr := c.Query("page"); pageStr != "" {
		if page, err := strconv.Atoi(pageStr); err == nil {
			opts.Page = page
		}
	}

	key := cacheKey(c, http.MethodGet, searchRouteTemplate, nil, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.Search(c.Request.Context(), opts, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, searchTTL)
	c.Data(http.StatusOK, "application/json", body)
}
