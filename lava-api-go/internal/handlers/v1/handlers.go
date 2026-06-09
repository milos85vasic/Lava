// Package v1 implements the provider-agnostic /v1/{provider}/... API routes.
//
// Each handler follows the same read-through cache pattern as the legacy
// rutracker-specific handlers, but operates against the generic
// provider.Provider interface. The provider is resolved from the Gin context
// by middleware in internal/middleware/provider.go.
//
// Constitutional alignment:
//   - 6.E Capability Honesty: routes for unsupported capabilities return
//     HTTP 501 (enforced by the middleware, not each handler).
//   - 6.D Behavioral Coverage: every handler has a real-stack test with
//     a fake provider and a fake cache.
package v1

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/middleware"
	"digital.vasic.lava.apigo/internal/provider"
)

// Deps bundles the shared dependencies every v1 handler needs.
type Deps struct {
	Cache Cache
}

// Cache is the subset of *cache.Client the handlers depend on.
type Cache interface {
	Get(ctx context.Context, key string) ([]byte, cache.Outcome, error)
	Set(ctx context.Context, key string, value []byte, ttl time.Duration) error
}

// Register wires every v1 handler's routes onto the given router group.
//
// The provider-resolution middleware is mounted PER ROUTE here (not on the
// group), because each endpoint asserts a different provider capability
// (§6.E Capability Honesty). middleware.ProviderMiddleware(reg, cap):
//   - resolves the :provider path segment to a Provider and stores it in the
//     Gin context (so currentProvider() finds it instead of panicking — the
//     LVA-010 production gap was that this middleware was never mounted, so
//     every /v1/{provider}/... handler panicked in currentProvider() and
//     gin.Recovery() turned the panic into a 500);
//   - aborts with 404 when the provider is unknown;
//   - aborts with 501 when the provider does not declare the capability.
//
// reg MAY be nil (route-registration tests that only assert "the route
// resolves, not 404" pass no registry); in that case the routes are mounted
// WITHOUT the provider middleware and handlers panic→500 on dispatch, which
// is still a non-404 status those tests assert on. Production (router.Build
// from cmd/lava-api-go/main.go and internal/mobile) ALWAYS passes a real
// registry, so production dispatch reaches the provider.
func Register(group *gin.RouterGroup, deps *Deps, reg *provider.ProviderRegistry) {
	// chain prepends the per-route provider middleware (for the given
	// capability) before the handler, or mounts the bare handler when reg is
	// nil (registration-only test path).
	chain := func(cap provider.ProviderCapability, h gin.HandlerFunc) []gin.HandlerFunc {
		if reg == nil {
			return []gin.HandlerFunc{h}
		}
		return []gin.HandlerFunc{middleware.ProviderMiddleware(reg, cap), h}
	}

	search := NewSearchHandler(deps)
	group.GET("/search", chain(provider.CapSearch, search.GetSearch)...)

	browse := NewBrowseHandler(deps)
	group.GET("/browse/:id", chain(provider.CapBrowse, browse.GetBrowse)...)

	forum := NewForumHandler(deps)
	group.GET("/forum", chain(provider.CapForumTree, forum.GetForum)...)

	topic := NewTopicHandler(deps)
	group.GET("/topic/:id", chain(provider.CapTopic, topic.GetTopic)...)

	torrent := NewTorrentHandler(deps)
	group.GET("/torrent/:id", chain(provider.CapTorrentDownload, torrent.GetTorrent)...)
	group.GET("/download/:id", chain(provider.CapTorrentDownload, torrent.GetDownload)...)

	comments := NewCommentsHandler(deps)
	group.GET("/comments/:id", chain(provider.CapComments, comments.GetComments)...)
	group.POST("/comments/:id/add", chain(provider.CapComments, comments.AddComment)...)

	fav := NewFavoritesHandler(deps)
	group.GET("/favorites", chain(provider.CapFavorites, fav.GetFavorites)...)
	group.POST("/favorites/add/:id", chain(provider.CapFavorites, fav.AddFavorite)...)
	group.POST("/favorites/remove/:id", chain(provider.CapFavorites, fav.RemoveFavorite)...)

	// login + captcha gate on CapSearch: every provider that serves the v1
	// API declares SEARCH, so this resolves the provider without over-
	// restricting auth endpoints to a capability they don't map to 1:1.
	login := NewLoginHandler(deps)
	group.POST("/login", chain(provider.CapSearch, login.PostLogin)...)

	captcha := NewCaptchaHandler(deps)
	group.GET("/captcha/:path", chain(provider.CapSearch, captcha.GetCaptcha)...)
}

// currentProvider extracts the Provider from the Gin context.
func currentProvider(c *gin.Context) provider.Provider {
	return middleware.Current(c)
}

// currentProviderID extracts the provider canonical ID from the Gin context.
func currentProviderID(c *gin.Context) string {
	return middleware.CurrentID(c)
}

// parseCredentials extracts provider credentials from the request, or returns
// zero-value (anonymous) credentials if none are present.
func parseCredentials(c *gin.Context) provider.Credentials {
	parsed := auth.ProviderCredentials(c.Request)
	if parsed == nil {
		return provider.Credentials{Type: "none"}
	}
	return parsed.Creds
}

// cacheKey builds a provider-aware cache key. The provider ID is included in
// the path variables so that identical requests to different providers do not
// collide.
func cacheKey(c *gin.Context, method, routeTemplate string, pathVars map[string]string, query map[string][]string, authRealmHash string) string {
	// Include provider ID in the path vars for key uniqueness.
	pv := make(map[string]string, len(pathVars)+1)
	for k, v := range pathVars {
		pv[k] = v
	}
	pv["provider"] = currentProviderID(c)
	return cache.Key(method, routeTemplate, pv, query, authRealmHash)
}

// writeJSON emits a JSON response with Content-Type "application/json"
// (no charset suffix) for parity with the legacy handlers.
func writeJSON(c *gin.Context, code int, v any) {
	body, err := json.Marshal(v)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal: " + err.Error()})
		return
	}
	c.Data(code, "application/json", body)
}

// writeProviderError maps provider sentinels to HTTP statuses.
func writeProviderError(c *gin.Context, err error) {
	switch {
	case errors.Is(err, provider.ErrNotFound):
		writeJSON(c, http.StatusNotFound, gin.H{})
	case errors.Is(err, provider.ErrForbidden):
		writeJSON(c, http.StatusForbidden, gin.H{})
	case errors.Is(err, provider.ErrUnauthorized):
		writeJSON(c, http.StatusUnauthorized, gin.H{})
	case errors.Is(err, provider.ErrCircuitOpen):
		writeJSON(c, http.StatusServiceUnavailable, gin.H{})
	default:
		writeJSON(c, http.StatusBadGateway, gin.H{})
	}
}
