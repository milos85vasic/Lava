package v1

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
)

const browseTTL = 1 * time.Minute

const browseRouteTemplate = "/v1/{provider}/browse/{id}"

type BrowseHandler struct {
	cache Cache
}

func NewBrowseHandler(deps *Deps) *BrowseHandler {
	return &BrowseHandler{cache: deps.Cache}
}

func (h *BrowseHandler) GetBrowse(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)
	categoryID := c.Param("id")

	page := 1
	if pageStr := c.Query("page"); pageStr != "" {
		if p, err := strconv.Atoi(pageStr); err == nil {
			page = p
		}
	}

	key := cacheKey(c, http.MethodGet, browseRouteTemplate, map[string]string{"id": categoryID}, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.Browse(c.Request.Context(), categoryID, page, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, browseTTL)
	c.Data(http.StatusOK, "application/json", body)
}
