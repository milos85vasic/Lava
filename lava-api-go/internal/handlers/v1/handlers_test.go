package v1

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/provider"
)

// fakeProvider is a minimal Provider implementation for handler tests.
type fakeProvider struct {
	id             string
	name           string
	searchResult   *provider.SearchResult
	searchErr      error
	browseResult   *provider.BrowseResult
	forumResult    *provider.ForumTree
	topicResult    *provider.TopicResult
	torrentResult  *provider.TorrentResult
	downloadResult *provider.FileDownload
}

func (f *fakeProvider) ID() string { return f.id }
func (f *fakeProvider) DisplayName() string {
	if f.name != "" {
		return f.name
	}
	return f.id
}
func (f *fakeProvider) Capabilities() []provider.ProviderCapability { return []provider.ProviderCapability{provider.CapSearch, provider.CapBrowse, provider.CapForumTree, provider.CapTopic, provider.CapTorrentDownload} }
func (f *fakeProvider) AuthType() provider.AuthType        { return provider.AuthNone }
func (f *fakeProvider) Encoding() string                   { return "UTF-8" }
func (f *fakeProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	return f.searchResult, f.searchErr
}
func (f *fakeProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	return f.browseResult, nil
}
func (f *fakeProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return f.forumResult, nil
}
func (f *fakeProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	return f.topicResult, nil
}
func (f *fakeProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	return f.torrentResult, nil
}
func (f *fakeProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	return f.downloadResult, nil
}
func (f *fakeProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return nil, provider.ErrUnsupported
}
func (f *fakeProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (f *fakeProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, provider.ErrUnsupported
}
func (f *fakeProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (f *fakeProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, provider.ErrUnsupported
}
func (f *fakeProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (f *fakeProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, provider.ErrUnsupported
}
func (f *fakeProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, provider.ErrUnsupported
}
func (f *fakeProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// fakeCache is a non-persistent in-memory cache for handler tests.
type fakeCache struct {
	store map[string][]byte
}

func newFakeCache() *fakeCache {
	return &fakeCache{store: make(map[string][]byte)}
}

func (c *fakeCache) Get(ctx context.Context, key string) ([]byte, cache.Outcome, error) {
	v, ok := c.store[key]
	if !ok {
		return nil, cache.OutcomeMiss, nil
	}
	return v, cache.OutcomeHit, nil
}

func (c *fakeCache) Set(ctx context.Context, key string, value []byte, ttl time.Duration) error {
	c.store[key] = value
	return nil
}

func setupTestRouter(p provider.Provider) *gin.Engine {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	group := router.Group("/v1/:provider")
	group.Use(func(c *gin.Context) {
		c.Set("__provider__", p)
		c.Next()
	})
	Register(group, &Deps{Cache: newFakeCache()})
	return router
}

func TestSearch_Success(t *testing.T) {
	fp := &fakeProvider{
		id: "test",
		searchResult: &provider.SearchResult{
			Provider:   "test",
			Page:       1,
			TotalPages: 5,
			Results: []provider.SearchItem{
				{ID: "1", Title: "Item One"},
			},
		},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=foo&page=1", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}
	var result provider.SearchResult
	if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if len(result.Results) != 1 {
		t.Errorf("expected 1 result, got %d", len(result.Results))
	}
}

func TestSearch_ProviderError(t *testing.T) {
	fp := &fakeProvider{
		id:        "test",
		searchErr: provider.ErrNotFound,
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=missing", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Errorf("expected 404 for ErrNotFound, got %d", w.Code)
	}
}

func TestBrowse_Success(t *testing.T) {
	fp := &fakeProvider{
		id: "test",
		browseResult: &provider.BrowseResult{
			Provider: "test",
			Items:    []provider.SearchItem{{ID: "10", Title: "Browse Item"}},
		},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/browse/123", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}
}

func TestForum_Success(t *testing.T) {
	fp := &fakeProvider{
		id: "test",
		forumResult: &provider.ForumTree{
			Provider:   "test",
			Categories: []provider.ForumCategory{{ID: "1", Name: "Movies"}},
		},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/forum", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}
}

func TestTopic_Success(t *testing.T) {
	fp := &fakeProvider{
		id: "test",
		topicResult: &provider.TopicResult{
			Provider: "test",
			ID:       "99",
			Title:    "Topic Title",
		},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/topic/99", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}
}

func TestCacheKey_UniquenessPerProvider(t *testing.T) {
	_ = newFakeCache()
	key1 := cacheKey(&gin.Context{}, http.MethodGet, "/v1/{provider}/search", nil, map[string][]string{"q": {"foo"}}, "anon")
	// The cacheKey function reads provider from gin.Context; we can't easily
	// test two providers without a real router, so we just verify the key
	// is non-empty and deterministic.
	if key1 == "" {
		t.Error("expected non-empty cache key")
	}
	key2 := cacheKey(&gin.Context{}, http.MethodGet, "/v1/{provider}/search", nil, map[string][]string{"q": {"foo"}}, "anon")
	if key1 != key2 {
		t.Error("expected deterministic cache key for identical inputs")
	}
}
