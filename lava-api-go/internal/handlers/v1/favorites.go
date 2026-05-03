package v1

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
)

const favoritesTTL = 1 * time.Minute

const favoritesRouteTemplate = "/v1/{provider}/favorites"

type FavoritesHandler struct {
	cache Cache
}

func NewFavoritesHandler(deps *Deps) *FavoritesHandler {
	return &FavoritesHandler{cache: deps.Cache}
}

func (h *FavoritesHandler) GetFavorites(c *gin.Context) {
	realm := auth.HashFromContext(c)
	p := currentProvider(c)
	creds := parseCredentials(c)

	key := cacheKey(c, http.MethodGet, favoritesRouteTemplate, nil, c.Request.URL.Query(), realm)

	if cached, outcome, err := h.cache.Get(c.Request.Context(), key); err == nil && outcome == cache.OutcomeHit {
		c.Data(http.StatusOK, "application/json", cached)
		return
	}

	result, err := p.GetFavorites(c.Request.Context(), creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	body, err := json.Marshal(result)
	if err != nil {
		writeJSON(c, http.StatusInternalServerError, gin.H{})
		return
	}
	_ = h.cache.Set(c.Request.Context(), key, body, favoritesTTL)
	c.Data(http.StatusOK, "application/json", body)
}

func (h *FavoritesHandler) AddFavorite(c *gin.Context) {
	p := currentProvider(c)
	creds := parseCredentials(c)
	id := c.Param("id")

	ok, err := p.AddFavorite(c.Request.Context(), id, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	writeJSON(c, http.StatusOK, gin.H{"success": ok})
}

func (h *FavoritesHandler) RemoveFavorite(c *gin.Context) {
	p := currentProvider(c)
	creds := parseCredentials(c)
	id := c.Param("id")

	ok, err := p.RemoveFavorite(c.Request.Context(), id, creds)
	if err != nil {
		writeProviderError(c, err)
		return
	}
	writeJSON(c, http.StatusOK, gin.H{"success": ok})
}
