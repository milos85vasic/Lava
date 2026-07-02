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

const topicTTL = 1 * time.Minute

const topicRouteTemplate = "/v1/{provider}/topic/{id}"

const topicPageRouteTemplate = "/v1/{provider}/topic/{id}/page"

type TopicHandler struct {
	cache Cache
}

func NewTopicHandler(deps *Deps) *TopicHandler {
	return &TopicHandler{cache: deps.Cache}
}

func (h *TopicHandler) GetTopic(c *gin.Context) {
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

	key := cacheKey(c, http.MethodGet, topicRouteTemplate, map[string]string{"id": id}, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.GetTopic(c.Request.Context(), id, page, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, topicTTL)
	c.Data(http.StatusOK, "application/json", body)
}

// GetTopicPage serves GET /v1/{provider}/topic/:id/page.
//
// The Android client (ApiBackedTrackerClient.getTopicPage) requests this path
// and decodes the response into the tolerant TopicDetailDto (id + title
// required only). Without this route the request 404'd, the SDK page-source
// returned null, and TopicServiceImpl fell back to the legacy rutracker-only
// /topic2/{id} whose strict TopicPageDto decode threw
// kotlinx.serialization.MissingFieldException for every non-rutracker provider
// (device-proven on kinozal, 2026-07-02).
//
// It maps to the SAME provider method the non-paged /topic/:id route uses
// (Provider.GetTopic, which already accepts the page index), so the served
// TopicResult body is byte-shape-identical to /topic/:id and decodes cleanly
// into the client's TopicDetailDto. The provider has no separate "topic page"
// method; the page number is a parameter of GetTopic.
func (h *TopicHandler) GetTopicPage(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)
	id := c.Param("id")

	page := 1
	if pageStr := c.Query("page"); pageStr != "" {
		if parsed, err := strconv.Atoi(pageStr); err == nil {
			page = parsed
		}
	}

	key := cacheKey(c, http.MethodGet, topicPageRouteTemplate, map[string]string{"id": id}, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.GetTopic(c.Request.Context(), id, page, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, topicTTL)
	c.Data(http.StatusOK, "application/json", body)
}
