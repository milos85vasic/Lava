// Package router — router_test.go is the W4 contract: the production router
// construction (router.Build) maps every declared route to a handler, so a
// missing or mis-wired registration is caught at build time rather than
// being silently invisible until a user hits a 404.
//
// router.Build is the SINGLE production router used by both
// cmd/lava-api-go/main.go and internal/mobile — so a route that fails to
// resolve here fails to resolve in production. The test boots the real
// engine and issues real httptest requests through ServeHTTP (no
// direct-function shortcut, per Sixth Law clause 1).
//
// The load-bearing discriminator is "the route resolves" vs "Gin returns
// its built-in 404 for an unregistered path". A registered route reaches
// its handler:
//   - the legacy rutracker routes invoke their real handlers, which (with
//     the stub scraper returning errors) map to 502 via writeUpstreamError;
//   - the /v1/{provider} routes reach their handler too — but because
//     router.Build mounts the /v1/:provider group WITHOUT the provider
//     middleware, the v1 handlers' currentProvider() lookup panics, which
//     gin.Recovery() turns into 500.
// In BOTH cases the status is NOT 404 — which is exactly what proves the
// route is wired. An UNregistered path (e.g. /v1/rutracker/does-not-exist)
// returns Gin's 404, the negative control this test also asserts.
//
// Falsifiability (Sixth Law clause 2 / §6.J): commenting out any single
// route registration in handlers.Register or v1handlers.Register makes that
// route's row fall through to Gin's 404, and this test fails with:
//
//	route GET /v1/:provider/search resolved to 404 (Gin's not-found) — the
//	registration is missing
package router

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/handlers"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// stubCache satisfies handlers.Cache without persistence. The route-
// resolution tests only need the handlers to be reachable, not to succeed.
type stubCache struct{}

func (stubCache) Get(_ context.Context, _ string) ([]byte, cache.Outcome, error) {
	return nil, cache.OutcomeMiss, nil
}

func (stubCache) Set(_ context.Context, _ string, _ []byte, _ time.Duration) error { return nil }
func (stubCache) Invalidate(_ context.Context, _ string) error                     { return nil }

// stubScraper satisfies handlers.ScraperClient. Every method returns an
// error so the legacy handlers map to 502 — a non-404 status that proves
// the route resolved to its handler.
type stubScraper struct{}

func (stubScraper) GetForum(context.Context, string) (*gen.ForumDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetCategoryPage(context.Context, string, *int, string) (*gen.CategoryPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetSearchPage(context.Context, rutracker.SearchOpts, string) (*gen.SearchPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetTopic(context.Context, string, *int, string) (*gen.ForumTopicDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetTopicPage(context.Context, string, *int, string) (*gen.TopicPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetCommentsPage(context.Context, string, *int, string) (*gen.CommentsPageDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) AddComment(context.Context, string, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) GetTorrent(context.Context, string, string) (*gen.ForumTopicDtoTorrent, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetTorrentFile(context.Context, string, string) (*rutracker.TorrentFile, error) {
	return nil, errors.New("stub")
}
func (stubScraper) GetFavorites(context.Context, string) (*gen.FavoritesDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) AddFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) RemoveFavorite(context.Context, string, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) CheckAuthorised(context.Context, string) (bool, error) {
	return false, errors.New("stub")
}
func (stubScraper) Login(context.Context, rutracker.LoginParams) (*gen.AuthResponseDto, error) {
	return nil, errors.New("stub")
}
func (stubScraper) FetchCaptcha(context.Context, string) (*rutracker.CaptchaImage, error) {
	return nil, errors.New("stub")
}

// Compile-time assertions that the stubs satisfy the handler interfaces —
// if a future method is added to either interface, the build breaks here
// at the seam rather than at request time.
var (
	_ handlers.Cache         = stubCache{}
	_ handlers.ScraperClient = stubScraper{}
)

func newTestEngine() http.Handler {
	return Build(Deps{
		Cache:   stubCache{},
		Scraper: stubScraper{},
	})
}

// TestBuild_DeclaredRoutesResolve is the W4 table test: every declared
// route — including every /v1/{provider} route — MUST resolve to a handler.
// A 404 from any of these rows means the registration is missing.
func TestBuild_DeclaredRoutesResolve(t *testing.T) {
	engine := newTestEngine()

	// The provider segment for /v1 routes is concrete here; the route
	// template (/v1/:provider/...) matches any non-empty provider segment.
	cases := []struct {
		method string
		path   string
	}{
		// Legacy rutracker routes (handlers.Register).
		{http.MethodGet, "/forum"},
		{http.MethodGet, "/forum/1"},
		{http.MethodGet, "/search"},
		{http.MethodGet, "/topic/1"},
		{http.MethodGet, "/topic2/1"},
		{http.MethodGet, "/comments/1"},
		{http.MethodPost, "/comments/1/add"},
		{http.MethodGet, "/torrent/1"},
		{http.MethodGet, "/download/1"},
		{http.MethodGet, "/favorites"},
		{http.MethodPost, "/favorites/add/1"},
		{http.MethodPost, "/favorites/remove/1"},
		{http.MethodGet, "/"},
		{http.MethodGet, "/index"},
		{http.MethodPost, "/login"},
		{http.MethodGet, "/captcha/abc"},

		// Liveness / readiness (registered before auth).
		{http.MethodGet, "/health"},
		{http.MethodGet, "/ready"},

		// Provider-agnostic /v1/{provider} routes (v1handlers.Register).
		// These are the W4 focus: a mis-wired /v1 group is invisible
		// without this assertion.
		{http.MethodGet, "/v1/rutracker/search"},
		{http.MethodGet, "/v1/rutracker/browse/1"},
		{http.MethodGet, "/v1/rutracker/forum"},
		{http.MethodGet, "/v1/rutracker/topic/1"},
		{http.MethodGet, "/v1/rutracker/torrent/1"},
		{http.MethodGet, "/v1/rutracker/download/1"},
		{http.MethodGet, "/v1/rutracker/comments/1"},
		{http.MethodPost, "/v1/rutracker/comments/1/add"},
		{http.MethodGet, "/v1/rutracker/favorites"},
		{http.MethodPost, "/v1/rutracker/favorites/add/1"},
		{http.MethodPost, "/v1/rutracker/favorites/remove/1"},
		{http.MethodPost, "/v1/rutracker/login"},
		{http.MethodGet, "/v1/rutracker/captcha/abc"},
	}

	for _, tc := range cases {
		t.Run(tc.method+" "+tc.path, func(t *testing.T) {
			// A v1 handler with no provider middleware mounted panics on
			// currentProvider(); gin.Recovery() converts that to 500. Recover
			// here too so a panic that escapes Recovery (a wiring regression)
			// surfaces as a test failure rather than crashing the run.
			defer func() {
				if r := recover(); r != nil {
					t.Fatalf("route %s %s panicked outside gin.Recovery: %v", tc.method, tc.path, r)
				}
			}()

			w := httptest.NewRecorder()
			req := httptest.NewRequest(tc.method, tc.path, nil)
			engine.ServeHTTP(w, req)

			if w.Code == http.StatusNotFound {
				t.Fatalf("route %s %s resolved to 404 (Gin's not-found) — the registration is missing; body=%q",
					tc.method, tc.path, w.Body.String())
			}
		})
	}
}

// TestBuild_UnregisteredPathIs404 is the negative control proving the
// discriminator in TestBuild_DeclaredRoutesResolve is real: an UNregistered
// path under the same /v1 prefix MUST return Gin's 404. Without this, a
// router that resolved EVERYTHING to non-404 (e.g. a catch-all) would let
// the positive test pass vacuously.
func TestBuild_UnregisteredPathIs404(t *testing.T) {
	engine := newTestEngine()

	for _, path := range []string{
		"/v1/rutracker/does-not-exist",
		"/definitely-not-a-route",
	} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, path, nil)
		engine.ServeHTTP(w, req)
		if w.Code != http.StatusNotFound {
			t.Fatalf("unregistered path %s returned %d want 404 — the resolve discriminator is unsound", path, w.Code)
		}
	}
}

// TestBuild_EveryDeclaredV1RouteIsRegistered cross-checks the route
// inventory directly against the engine's registered routes, independent of
// request dispatch. This catches a regression where a route resolves to a
// 500 for the wrong reason (e.g. middleware) but is genuinely absent.
func TestBuild_EveryDeclaredV1RouteIsRegistered(t *testing.T) {
	engine := Build(Deps{Cache: stubCache{}, Scraper: stubScraper{}})

	want := map[string]bool{
		"GET /v1/:provider/search":               false,
		"GET /v1/:provider/browse/:id":           false,
		"GET /v1/:provider/forum":                false,
		"GET /v1/:provider/topic/:id":            false,
		"GET /v1/:provider/torrent/:id":          false,
		"GET /v1/:provider/download/:id":         false,
		"GET /v1/:provider/comments/:id":         false,
		"POST /v1/:provider/comments/:id/add":    false,
		"GET /v1/:provider/favorites":            false,
		"POST /v1/:provider/favorites/add/:id":   false,
		"POST /v1/:provider/favorites/remove/:id": false,
		"POST /v1/:provider/login":               false,
		"GET /v1/:provider/captcha/:path":        false,
	}

	for _, ri := range engine.Routes() {
		key := ri.Method + " " + ri.Path
		if _, ok := want[key]; ok {
			want[key] = true
		}
	}

	for k, seen := range want {
		if !seen {
			missing := make([]string, 0)
			for _, ri := range engine.Routes() {
				if strings.HasPrefix(ri.Path, "/v1/") {
					missing = append(missing, ri.Method+" "+ri.Path)
				}
			}
			t.Errorf("declared v1 route %q not registered by router.Build; got v1 routes: %v", k, missing)
		}
	}
}
