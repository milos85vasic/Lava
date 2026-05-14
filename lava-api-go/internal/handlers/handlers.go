// Package handlers wires Gin route handlers for the 13 rutracker.org
// API endpoints. Each handler follows the same pattern:
//
//  1. read auth realm hash + upstream cookie via internal/auth
//  2. compute deterministic cache key via internal/cache
//  3. lookup → on hit, write the cached body verbatim (Content-Type:
//     application/json, status preserved at 200)
//  4. on miss, call the rutracker scraper interface, JSON-marshal the
//     typed DTO, store the marshalled body in the cache, write to the
//     response
//
// Phase 7 task 7.1 (forum) establishes the pattern. Tasks 7.2-7.7 follow
// the same shape; ScraperClient and Register() grow with each task to
// register additional methods/routes — they should NOT restructure the
// package.
//
// Design seams (Sixth Law clause 1: testability without bypassing real
// wiring):
//
//   - ScraperClient is an interface so handler-layer tests can substitute
//     a fake without owning a circuit breaker / HTTP client. The
//     production *rutracker.Client satisfies it.
//   - Cache is an interface so handler-layer tests can substitute an
//     in-memory fake without standing up Postgres. *cache.Client
//     satisfies it. Cache integration semantics are pinned by
//     internal/cache/integration_test.go against real Postgres; the
//     handler tests verify the wiring (read-through, write-through, key
//     uses the OpenAPI route template).
//
// The cache key MUST be computed against the OpenAPI route template
// form (e.g. /forum/{id}), NOT the Gin form (/forum/:id). The Phase 10
// cross-backend parity test compares hashes against the Ktor proxy's
// route templates; diverging here would be silent breakage. Each
// handler defines a per-route template constant for that reason.
package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// ScraperClient is the subset of *rutracker.Client the handlers depend
// on. Each Phase 7 task APPENDS the methods its handlers need; the
// concrete *rutracker.Client type satisfies all of them at production
// wire-up time. Tests substitute a fake.
type ScraperClient interface {
	GetForum(ctx context.Context, cookie string) (*gen.ForumDto, error)
	GetCategoryPage(ctx context.Context, forumID string, page *int, cookie string) (*gen.CategoryPageDto, error)
	GetSearchPage(ctx context.Context, opts rutracker.SearchOpts, cookie string) (*gen.SearchPageDto, error)
	GetTopic(ctx context.Context, id string, page *int, cookie string) (*gen.ForumTopicDto, error)
	GetTopicPage(ctx context.Context, id string, page *int, cookie string) (*gen.TopicPageDto, error)
	GetCommentsPage(ctx context.Context, id string, page *int, cookie string) (*gen.CommentsPageDto, error)
	AddComment(ctx context.Context, topicID, message, cookie string) (bool, error)
	GetTorrent(ctx context.Context, id, cookie string) (*gen.ForumTopicDtoTorrent, error)
	GetTorrentFile(ctx context.Context, id, cookie string) (*rutracker.TorrentFile, error)
	GetFavorites(ctx context.Context, cookie string) (*gen.FavoritesDto, error)
	AddFavorite(ctx context.Context, id, cookie string) (bool, error)
	RemoveFavorite(ctx context.Context, id, cookie string) (bool, error)
	CheckAuthorised(ctx context.Context, cookie string) (bool, error)
	Login(ctx context.Context, p rutracker.LoginParams) (*gen.AuthResponseDto, error)
	FetchCaptcha(ctx context.Context, encodedPath string) (*rutracker.CaptchaImage, error)
}

// Compile-time assertion that the production scraper type satisfies the
// handler-facing interface. If a future Phase 7 task adds a method to
// ScraperClient that *rutracker.Client does not yet implement, this line
// breaks the build at the seam, not at request time.
var _ ScraperClient = (*rutracker.Client)(nil)

// Compile-time assertion that *cache.Client satisfies the Cache
// interface. Same rationale as the ScraperClient assertion above.
var _ Cache = (*cache.Client)(nil)

// Cache is the subset of *cache.Client the handlers depend on. The real
// cache.Client satisfies it. Defined as an interface so handler-layer
// tests can substitute an in-memory fake without standing up Postgres —
// cache_test.go's integration tests are the load-bearing verification
// of the real cache.
type Cache interface {
	Get(ctx context.Context, key string) ([]byte, cache.Outcome, error)
	Set(ctx context.Context, key string, value []byte, ttl time.Duration) error
	Invalidate(ctx context.Context, key string) error
}

// Deps bundles the shared dependencies every handler needs. Held by
// each per-route Handler struct, not threaded through globals.
type Deps struct {
	Cache   Cache
	Scraper ScraperClient
}

// Register wires every handler's routes onto the Gin router. Phase 7
// tasks 7.2-7.7 append to this function as they go; the function must
// remain the single entry point so cmd/lava-api-go has only one wiring
// call.
func Register(router *gin.Engine, deps *Deps) {
	forum := NewForumHandler(deps)
	router.GET("/forum", forum.GetForum)
	router.GET("/forum/:id", forum.GetCategoryPage)

	search := NewSearchHandler(deps)
	router.GET("/search", search.GetSearch)

	topic := NewTopicHandler(deps)
	router.GET("/topic/:id", topic.GetTopic)
	router.GET("/topic2/:id", topic.GetTopicPage)
	router.GET("/comments/:id", topic.GetCommentsPage)

	comments := NewCommentsAddHandler(deps)
	router.POST("/comments/:id/add", comments.AddComment)

	torrent := NewTorrentHandler(deps)
	router.GET("/torrent/:id", torrent.GetTorrent)
	router.GET("/download/:id", torrent.GetDownload)

	fav := NewFavoritesHandler(deps)
	router.GET("/favorites", fav.GetFavorites)
	router.POST("/favorites/add/:id", fav.AddFavorite)
	router.POST("/favorites/remove/:id", fav.RemoveFavorite)

	index := NewIndexHandler(deps)
	router.GET("/", index.GetIndex)
	router.GET("/index", index.GetIndex)

	auth := NewAuthHandler(deps)
	router.POST("/login", auth.PostLogin)

	captcha := NewCaptchaHandler(deps)
	router.GET("/captcha/:path", captcha.GetCaptcha)
}

// writeJSON emits a JSON response with Content-Type "application/json"
// (no `charset=utf-8` suffix) so the wire shape matches what the legacy
// Ktor proxy emits. Gin's c.JSON appends "; charset=utf-8" via its
// MIMEJSON constant; the parity test surfaced this as a Content-Type
// divergence between the two backends. Use writeJSON anywhere a handler
// would have used c.JSON to keep the wire bytes parity-clean.
func writeJSON(c *gin.Context, code int, v any) {
	body, err := json.Marshal(v)
	if err != nil {
		// json.Marshal failure is effectively impossible for the shapes
		// we serialise (gin.H{}, bool, gen.* DTOs). Fall back to
		// c.JSON so the failure surfaces something rather than a
		// blank body.
		c.JSON(http.StatusInternalServerError, gin.H{"error": "marshal: " + err.Error()})
		return
	}
	c.Data(code, "application/json", body)
}

// writeUpstreamError maps the rutracker package's sentinel errors to
// the matching HTTP status. Subsequent Phase 7 tasks reuse this helper
// from their own handler files. The mapping matches spec §6 / Ktor
// proxy semantics:
//
//	ErrNotFound     → 404 Not Found
//	ErrForbidden    → 403 Forbidden
//	ErrUnauthorized → 401 Unauthorized
//	ErrCircuitOpen  → 503 Service Unavailable (upstream tripped)
//	anything else   → 502 Bad Gateway (upstream returned something we
//	                                   couldn't make sense of)
//
// Each Phase 7 task MUST add per-route tests covering all five branches
// for at least one of its routes (NotFound/Forbidden/Unauthorized/
// CircuitOpen/default-→502). The forum handler tests in forum_test.go
// are the reference shape; mirror them when adding search/topic/comments/
// torrent/favorites/login routes.
func writeUpstreamError(c *gin.Context, err error) {
	// Body shape: empty `{}` for parity with the Ktor proxy's StatusPages
	// plugin (proxy/.../plugins/Status.kt), which calls
	// `call.respond(status = ..., message = Unit)`. Ktor's kotlinx-serialization
	// JSON content-negotiator serialises `Unit` as `{}`. The HTTP status
	// code carries the error semantics; the body deliberately does not
	// echo err.Error() — which Ktor doesn't either.
	switch {
	case errors.Is(err, rutracker.ErrNotFound):
		writeJSON(c, http.StatusNotFound, gin.H{})
	case errors.Is(err, rutracker.ErrForbidden):
		writeJSON(c, http.StatusForbidden, gin.H{})
	case errors.Is(err, rutracker.ErrUnauthorized):
		writeJSON(c, http.StatusUnauthorized, gin.H{})
	case errors.Is(err, rutracker.ErrCircuitOpen):
		writeJSON(c, http.StatusServiceUnavailable, gin.H{})
	default:
		writeJSON(c, http.StatusBadGateway, gin.H{})
	}
}
