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

const commentsTTL = 1 * time.Minute

const commentsRouteTemplate = "/v1/{provider}/comments/{id}"
const addCommentRouteTemplate = "/v1/{provider}/comments/{id}/add"

type CommentsHandler struct {
	cache Cache
}

func NewCommentsHandler(deps *Deps) *CommentsHandler {
	return &CommentsHandler{cache: deps.Cache}
}

func (h *CommentsHandler) GetComments(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)
	id := c.Param("id")

	page := 1
	if pageStr := c.Query("page"); pageStr != "" {
		if p, err := strconv.Atoi(pageStr); err == nil {
			page = p
		}
	}

	key := cacheKey(c, http.MethodGet, commentsRouteTemplate, map[string]string{"id": id}, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.GetComments(c.Request.Context(), id, page, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, commentsTTL)
	c.Data(http.StatusOK, "application/json", body)
}

func (h *CommentsHandler) AddComment(c *gin.Context) {
	p := currentProvider(c)
	creds := parseCredentials(c)
	id := c.Param("id")

	var req struct {
		Message string `json:"message"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		writeJSON(c, http.StatusBadRequest, gin.H{"error": "invalid body"})
		return
	}

	ok, err := p.AddComment(c.Request.Context(), id, req.Message, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	writeJSON(c, http.StatusOK, gin.H{"success": ok})
}
