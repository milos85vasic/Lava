// router_provider_dispatch_test.go is the LVA-010 regression gate.
//
// The bug: router.Build mounted the /v1/:provider group WITHOUT the provider-
// resolution middleware, so every /v1/{provider}/... handler panicked in
// currentProvider() (which panics when __provider__ is absent from the Gin
// context) and gin.Recovery() turned the panic into a 500. The previous
// router_test.go RATIONALIZED that 500 as "proof the route resolves" — but a
// 500-on-every-request endpoint is broken for users, which is exactly the
// §6.J/§6.L bluff this codebase exists to evict: green tests, broken feature.
//
// This test wires a REAL provider registry into router.Build (the same way
// cmd/lava-api-go/main.go and internal/mobile do in production) and asserts on
// the USER-VISIBLE HTTP RESPONSE:
//   - /v1/{known-provider}/search dispatches to the provider and returns 200
//     with the provider's marshaled result body (NOT a 500 panic-recover);
//   - an unknown provider returns 404 (ProviderMiddleware unknown_provider);
//   - a provider that does not declare the capability returns 501 (§6.E).
//
// FALSIFIABILITY (Sixth Law clause 2 / §6.J): remove `deps.Registry` from the
// v1handlers.Register call in router.go (i.e. revert to the bug) and
// TestBuild_V1ProviderDispatch_Search fails with:
//
//	/v1/disp/search returned 500 want 200 — provider middleware not mounted,
//	currentProvider() panicked (LVA-010 regression); body=...
package router

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// dispatchProvider is a real provider.Provider whose Search returns a known
// result, so a request that actually reaches dispatch produces a 200 with a
// recognizable body. It declares SEARCH but NOT FAVORITES, so the capability
// gate (§6.E) can be exercised.
type dispatchProvider struct{ id string }

func (p *dispatchProvider) ID() string          { return p.id }
func (p *dispatchProvider) DisplayName() string { return "Dispatch" }
func (p *dispatchProvider) Capabilities() []provider.ProviderCapability {
	return []provider.ProviderCapability{provider.CapSearch}
}
func (p *dispatchProvider) AuthType() provider.AuthType { return provider.AuthNone }
func (p *dispatchProvider) Encoding() string           { return "UTF-8" }
func (p *dispatchProvider) Search(_ context.Context, _ provider.SearchOpts, _ provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{
		Provider:   p.id,
		Page:       1,
		TotalPages: 1,
		Results:    []provider.SearchItem{{ID: "42", Title: "Dispatched Hit"}},
	}, nil
}
func (p *dispatchProvider) Browse(context.Context, string, int, provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) GetForumTree(context.Context, provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) GetTopic(context.Context, string, int, provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) GetTorrent(context.Context, string, provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) DownloadFile(context.Context, string, provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) GetComments(context.Context, string, int, provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) AddComment(context.Context, string, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *dispatchProvider) GetFavorites(context.Context, provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) AddFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *dispatchProvider) RemoveFavorite(context.Context, string, provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (p *dispatchProvider) CheckAuth(context.Context, provider.Credentials) (bool, error) {
	return false, nil
}
func (p *dispatchProvider) Login(context.Context, provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) FetchCaptcha(context.Context, string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (p *dispatchProvider) HealthCheck(context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

func newDispatchEngine() http.Handler {
	reg := provider.NewRegistry()
	reg.Register(&dispatchProvider{id: "disp"})
	return Build(Deps{
		Cache:    stubCache{},
		Scraper:  stubScraper{},
		Registry: reg,
	})
}

// TestBuild_V1ProviderDispatch_Search is the load-bearing LVA-010 assertion:
// with the production registry wired, /v1/{provider}/search reaches the
// provider and returns its result — not a 500 panic-recover.
func TestBuild_V1ProviderDispatch_Search(t *testing.T) {
	engine := newDispatchEngine()

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/v1/disp/search?query=anything", nil)
	engine.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("/v1/disp/search returned %d want 200 — provider middleware not mounted, "+
			"currentProvider() panicked (LVA-010 regression); body=%q", w.Code, w.Body.String())
	}

	var got provider.SearchResult
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("response body is not a SearchResult JSON (handler did not dispatch to provider): %v; body=%q", err, w.Body.String())
	}
	if got.Provider != "disp" || len(got.Results) != 1 || got.Results[0].Title != "Dispatched Hit" {
		t.Fatalf("response did not come from the registered provider's Search(): %+v", got)
	}
}

// TestBuild_V1ProviderDispatch_UnknownProvider asserts the middleware's 404
// path (unknown_provider) — proving the registry IS consulted, not bypassed.
func TestBuild_V1ProviderDispatch_UnknownProvider(t *testing.T) {
	engine := newDispatchEngine()

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/v1/nosuchprovider/search?query=x", nil)
	engine.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("/v1/nosuchprovider/search returned %d want 404 (unknown_provider); body=%q", w.Code, w.Body.String())
	}
}

// TestBuild_V1ProviderDispatch_UnsupportedCapability asserts the §6.E 501
// path: dispatchProvider declares SEARCH but not FAVORITES, so the favorites
// route must 501 rather than dispatch.
func TestBuild_V1ProviderDispatch_UnsupportedCapability(t *testing.T) {
	engine := newDispatchEngine()

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/v1/disp/favorites", nil)
	engine.ServeHTTP(w, req)

	if w.Code != http.StatusNotImplemented {
		t.Fatalf("/v1/disp/favorites returned %d want 501 (unsupported_capability §6.E); body=%q", w.Code, w.Body.String())
	}
}
