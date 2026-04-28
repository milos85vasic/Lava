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
	"errors"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// ScraperClient is the subset of *rutracker.Client the handlers depend
// on. Each Phase 7 task APPENDS the methods its handlers need; the
// concrete *rutracker.Client type satisfies all of them at production
// wire-up time. Tests substitute a fake.
type ScraperClient interface {
	GetForum(ctx context.Context, cookie string) (*gen.ForumDto, error)
	GetCategoryPage(ctx context.Context, forumID string, page *int, cookie string) (*gen.CategoryPageDto, error)
	// Phase 7.2-7.7 will append more methods here.
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
	// Phase 7.2-7.7 will append more route registrations here.
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
	switch {
	case errors.Is(err, rutracker.ErrNotFound):
		c.JSON(http.StatusNotFound, gin.H{"error": err.Error()})
	case errors.Is(err, rutracker.ErrForbidden):
		c.JSON(http.StatusForbidden, gin.H{"error": err.Error()})
	case errors.Is(err, rutracker.ErrUnauthorized):
		c.JSON(http.StatusUnauthorized, gin.H{"error": err.Error()})
	case errors.Is(err, rutracker.ErrCircuitOpen):
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": err.Error()})
	default:
		c.JSON(http.StatusBadGateway, gin.H{"error": err.Error()})
	}
}
