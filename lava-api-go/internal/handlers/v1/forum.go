package v1

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
)

const forumTTL = 5 * time.Minute

const forumRouteTemplate = "/v1/{provider}/forum"

type ForumHandler struct {
	cache Cache
}

func NewForumHandler(deps *Deps) *ForumHandler {
	return &ForumHandler{cache: deps.Cache}
}

func (h *ForumHandler) GetForum(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)

	key := cacheKey(c, http.MethodGet, forumRouteTemplate, nil, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.GetForumTree(c.Request.Context(), creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, forumTTL)
	c.Data(http.StatusOK, "application/json", body)
}
