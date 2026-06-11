package middleware

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// stubProvider is a minimal real provider.Provider used to populate a real
// ProviderRegistry. The middleware tests exercise the real registry +
// real Gin engine; only the provider's upstream calls are stubbed (these
// are never invoked by the middleware path under test).
type stubProvider struct {
	provider.BaseProvider
	id   string
	caps []provider.ProviderCapability
}

func (s *stubProvider) ID() string                                  { return s.id }
func (s *stubProvider) DisplayName() string                         { return s.id }
func (s *stubProvider) Capabilities() []provider.ProviderCapability { return s.caps }
func (s *stubProvider) AuthType() provider.AuthType                 { return provider.AuthNone }
func (s *stubProvider) Encoding() string                            { return "UTF-8" }
func (s *stubProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{Provider: s.id}, nil
}
func (s *stubProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (s *stubProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (s *stubProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (s *stubProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return false, nil
}
func (s *stubProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (s *stubProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// newTestRegistry builds a real registry with one search-capable provider.
func newTestRegistry() *provider.ProviderRegistry {
	reg := provider.NewRegistry()
	reg.Register(&stubProvider{id: "kinozal", caps: []provider.ProviderCapability{provider.CapSearch, provider.CapTopic}})
	return reg
}

// buildRouter mounts ProviderMiddleware on /v1/:provider/search for the given
// required capability, with a terminal handler that echoes the resolved
// provider's ID — proving Current() + context propagation actually work.
func buildRouter(reg *provider.ProviderRegistry, cap provider.ProviderCapability) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	grp := r.Group("/v1/:provider")
	grp.Use(ProviderMiddleware(reg, cap))
	grp.GET("/search", func(c *gin.Context) {
		p := Current(c)
		c.JSON(http.StatusOK, gin.H{
			"resolved_id":  p.ID(),
			"context_id":   CurrentID(c),
			"display_name": p.DisplayName(),
		})
	})
	return r
}

// TestProviderMiddleware_KnownProviderSupportedCapability verifies the happy
// path: a request to a known, capable provider reaches the terminal handler,
// which can extract the SAME provider from context via Current().
func TestProviderMiddleware_KnownProviderSupportedCapability(t *testing.T) {
	r := buildRouter(newTestRegistry(), provider.CapSearch)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/kinozal/search", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", w.Code, w.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal body: %v", err)
	}
	if body["resolved_id"] != "kinozal" {
		t.Errorf("resolved_id = %v, want kinozal", body["resolved_id"])
	}
	if body["context_id"] != "kinozal" {
		t.Errorf("context_id = %v, want kinozal", body["context_id"])
	}
}

// TestProviderMiddleware_UnknownProvider verifies a request to an
// unregistered provider aborts with 404 + the unknown_provider error body,
// and NEVER reaches the terminal handler.
func TestProviderMiddleware_UnknownProvider(t *testing.T) {
	reached := false
	gin.SetMode(gin.TestMode)
	r := gin.New()
	grp := r.Group("/v1/:provider")
	grp.Use(ProviderMiddleware(newTestRegistry(), provider.CapSearch))
	grp.GET("/search", func(c *gin.Context) { reached = true })

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/doesnotexist/search", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404; body=%s", w.Code, w.Body.String())
	}
	if reached {
		t.Error("terminal handler was reached despite unknown provider — middleware did not abort")
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal body: %v", err)
	}
	if body["error"] != "unknown_provider" {
		t.Errorf("error = %v, want unknown_provider", body["error"])
	}
}

// TestProviderMiddleware_UnsupportedCapability verifies a request for a
// capability the provider does NOT declare aborts with 501 + the
// unsupported_capability body naming the provider and capability. This is
// the §6.E Capability Honesty gate in action.
func TestProviderMiddleware_UnsupportedCapability(t *testing.T) {
	reached := false
	gin.SetMode(gin.TestMode)
	r := gin.New()
	grp := r.Group("/v1/:provider")
	// Require BROWSE, but kinozal only declares SEARCH + TOPIC.
	grp.Use(ProviderMiddleware(newTestRegistry(), provider.CapBrowse))
	grp.GET("/search", func(c *gin.Context) { reached = true })

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/kinozal/search", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotImplemented {
		t.Fatalf("status = %d, want 501; body=%s", w.Code, w.Body.String())
	}
	if reached {
		t.Error("terminal handler reached despite unsupported capability")
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("unmarshal body: %v", err)
	}
	if body["error"] != "unsupported_capability" {
		t.Errorf("error = %v, want unsupported_capability", body["error"])
	}
	if body["provider"] != "kinozal" {
		t.Errorf("provider = %v, want kinozal", body["provider"])
	}
	if body["capability"] != string(provider.CapBrowse) {
		t.Errorf("capability = %v, want %s", body["capability"], provider.CapBrowse)
	}
}

// TestCurrent_PanicsWhenMiddlewareNotRun verifies the programmer-error
// contract: calling Current() without the middleware having stored a
// provider panics (a misconfigured route is a bug, surfaced loudly).
func TestCurrent_PanicsWhenMiddlewareNotRun(t *testing.T) {
	gin.SetMode(gin.TestMode)
	c, _ := gin.CreateTestContext(httptest.NewRecorder())
	defer func() {
		if recover() == nil {
			t.Fatal("expected panic when Current() called without middleware")
		}
	}()
	_ = Current(c)
}

// TestCapabilityMiddleware_Known verifies the lightweight capability-only
// middleware passes through for a known, capable provider.
func TestCapabilityMiddleware_Known(t *testing.T) {
	reached := false
	gin.SetMode(gin.TestMode)
	r := gin.New()
	grp := r.Group("/v1/:provider")
	grp.Use(CapabilityMiddleware(newTestRegistry(), provider.CapSearch))
	grp.GET("/meta", func(c *gin.Context) {
		reached = true
		c.Status(http.StatusOK)
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/kinozal/meta", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	if !reached {
		t.Error("terminal handler not reached for known capable provider")
	}
}

// TestCapabilityMiddleware_Unknown verifies 404 for unknown provider.
func TestCapabilityMiddleware_Unknown(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	grp := r.Group("/v1/:provider")
	grp.Use(CapabilityMiddleware(newTestRegistry(), provider.CapSearch))
	grp.GET("/meta", func(c *gin.Context) {})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/nope/meta", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("status = %d, want 404", w.Code)
	}
}

// TestCapabilityMiddleware_Unsupported verifies 501 for an unsupported
// capability on a known provider.
func TestCapabilityMiddleware_Unsupported(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	grp := r.Group("/v1/:provider")
	grp.Use(CapabilityMiddleware(newTestRegistry(), provider.CapFavorites))
	grp.GET("/meta", func(c *gin.Context) {})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/kinozal/meta", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotImplemented {
		t.Errorf("status = %d, want 501", w.Code)
	}
}
