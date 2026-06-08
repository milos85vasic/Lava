package v1

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// errProvider is a real provider.Provider whose read endpoints (Search,
// Browse, GetForumTree, GetTopic, GetComments, GetFavorites, FetchCaptcha)
// all return a single configurable error. Unlike richProvider, this fake
// honours the error on EVERY read path, which is required to exercise the
// error branches of the GET handlers (Browse/Forum/Topic/Search), none of
// which were covered before.
type errProvider struct {
	err error
}

func (p *errProvider) ID() string                                  { return "err" }
func (p *errProvider) DisplayName() string                         { return "Err" }
func (p *errProvider) Capabilities() []provider.ProviderCapability { return nil }
func (p *errProvider) AuthType() provider.AuthType                 { return provider.AuthNone }
func (p *errProvider) Encoding() string                            { return "UTF-8" }
func (p *errProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	return nil, p.err
}
func (p *errProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	return nil, p.err
}
func (p *errProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return nil, p.err
}
func (p *errProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	return nil, p.err
}
func (p *errProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	return nil, p.err
}
func (p *errProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	return nil, p.err
}
func (p *errProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return nil, p.err
}
func (p *errProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return false, p.err
}
func (p *errProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return nil, p.err
}
func (p *errProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, p.err
}
func (p *errProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return false, p.err
}
func (p *errProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return false, p.err
}
func (p *errProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return nil, p.err
}
func (p *errProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return nil, p.err
}
func (p *errProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// doReq drives one GET request through the real router and returns the
// recorder.
func doReq(t *testing.T, p provider.Provider, method, path string) *httptest.ResponseRecorder {
	t.Helper()
	router := setupTestRouter(p)
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(method, path, nil)
	router.ServeHTTP(w, req)
	return w
}

// TestErrorBranches_StatusMapping is a table that drives every read GET
// endpoint with each provider sentinel and asserts the user-visible HTTP
// status code that writeProviderError must produce. This covers the
// previously-untested error branches of GetBrowse, GetForum, GetTopic,
// GetSearch, GetComments, GetFavorites, GetTorrent, and GetCaptcha plus the
// ErrCircuitOpen (503) and default (502 BadGateway) arms of
// writeProviderError that no prior test exercised.
func TestErrorBranches_StatusMapping(t *testing.T) {
	type sentinel struct {
		name string
		err  error
		code int
	}
	sentinels := []sentinel{
		{"NotFound", provider.ErrNotFound, http.StatusNotFound},
		{"Forbidden", provider.ErrForbidden, http.StatusForbidden},
		{"Unauthorized", provider.ErrUnauthorized, http.StatusUnauthorized},
		{"CircuitOpen", provider.ErrCircuitOpen, http.StatusServiceUnavailable},
		{"Unsupported", provider.ErrUnsupported, http.StatusBadGateway},
	}

	endpoints := []struct {
		name string
		path string
	}{
		{"search", "/v1/err/search?query=x"},
		{"browse", "/v1/err/browse/42"},
		{"forum", "/v1/err/forum"},
		{"topic", "/v1/err/topic/7"},
		{"comments", "/v1/err/comments/7"},
		{"favorites", "/v1/err/favorites"},
		{"torrent", "/v1/err/torrent/7"},
		{"download", "/v1/err/download/7"},
		{"captcha", "/v1/err/captcha/cap.png"},
	}

	for _, ep := range endpoints {
		for _, s := range sentinels {
			t.Run(ep.name+"_"+s.name, func(t *testing.T) {
				w := doReq(t, &errProvider{err: s.err}, http.MethodGet, ep.path)
				if w.Code != s.code {
					t.Errorf("%s with %s: status = %d, want %d (body=%s)",
						ep.name, s.name, w.Code, s.code, w.Body.String())
				}
				// Error responses carry a JSON body (an empty object for
				// provider errors) — the client parses JSON, never raw text.
				if ct := w.Header().Get("Content-Type"); ct != "application/json" {
					t.Errorf("%s with %s: Content-Type = %q, want application/json",
						ep.name, s.name, ct)
				}
			})
		}
	}
}

// TestBrowse_CacheHit verifies the read-through cache short-circuit: the
// FIRST request populates the cache from the provider; the SECOND identical
// request is served straight from the cache and must NOT touch the provider
// again. This is the user-visible "fast second load" path and the cache-hit
// branch of GetBrowse that no prior test covered.
func TestBrowse_CacheHit(t *testing.T) {
	calls := 0
	fp := &countingProvider{
		browseResult: &provider.BrowseResult{
			Provider: "count",
			Items:    []provider.SearchItem{{ID: "10", Title: "Cached Browse"}},
		},
		onBrowse: func() { calls++ },
	}
	router := setupTestRouter(fp)

	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		req, _ := http.NewRequest(http.MethodGet, "/v1/count/browse/123", nil)
		router.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("request %d: status = %d, want 200 (body=%s)", i, w.Code, w.Body.String())
		}
		if !contains(w.Body.String(), "Cached Browse") {
			t.Fatalf("request %d: body missing cached item: %s", i, w.Body.String())
		}
	}
	if calls != 1 {
		t.Errorf("provider.Browse called %d times, want 1 (second request must hit cache)", calls)
	}
}

// TestSearch_CacheHit verifies the same read-through cache short-circuit for
// the search endpoint — the hottest path in the product.
func TestSearch_CacheHit(t *testing.T) {
	calls := 0
	fp := &countingProvider{
		searchResult: &provider.SearchResult{
			Provider: "count",
			Page:     1,
			Results:  []provider.SearchItem{{ID: "1", Title: "Cached Search"}},
		},
		onSearch: func() { calls++ },
	}
	router := setupTestRouter(fp)

	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		req, _ := http.NewRequest(http.MethodGet, "/v1/count/search?query=foo", nil)
		router.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("request %d: status = %d, want 200", i, w.Code)
		}
		if !contains(w.Body.String(), "Cached Search") {
			t.Fatalf("request %d: body missing cached item: %s", i, w.Body.String())
		}
	}
	if calls != 1 {
		t.Errorf("provider.Search called %d times, want 1 (second request must hit cache)", calls)
	}
}

// TestCacheHit_AllReadEndpoints drives forum, topic, torrent, comments and
// favorites twice each and asserts the SECOND request never re-hits the
// provider — proving the cache-hit short-circuit branch of every GET handler
// (none of which had a cache-hit test before). Counters live on the fake.
func TestCacheHit_AllReadEndpoints(t *testing.T) {
	cases := []struct {
		name string
		path string
		get  func(p *cacheCountProvider) int
	}{
		{"forum", "/v1/cc/forum", func(p *cacheCountProvider) int { return p.forum }},
		{"topic", "/v1/cc/topic/7", func(p *cacheCountProvider) int { return p.topic }},
		{"torrent", "/v1/cc/torrent/7", func(p *cacheCountProvider) int { return p.torrent }},
		{"comments", "/v1/cc/comments/7", func(p *cacheCountProvider) int { return p.comments }},
		{"favorites", "/v1/cc/favorites", func(p *cacheCountProvider) int { return p.favorites }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fp := &cacheCountProvider{}
			router := setupTestRouter(fp)
			for i := 0; i < 2; i++ {
				w := httptest.NewRecorder()
				req, _ := http.NewRequest(http.MethodGet, tc.path, nil)
				router.ServeHTTP(w, req)
				if w.Code != http.StatusOK {
					t.Fatalf("%s request %d: status = %d, want 200", tc.name, i, w.Code)
				}
			}
			if n := tc.get(fp); n != 1 {
				t.Errorf("%s: provider called %d times, want 1 (second must be cached)", tc.name, n)
			}
		})
	}
}

// TestParseCredentials_FromHeader verifies that a populated Auth-Token header
// is parsed and forwarded to the provider as real credentials (not the
// anonymous {Type:"none"} fallback). This exercises the non-nil branch of
// parseCredentials, previously only hit on the anonymous path. The assertion
// is on the credentials the provider actually receives — the user-visible
// effect of being logged in.
func TestParseCredentials_FromHeader(t *testing.T) {
	var seen provider.Credentials
	fp := &credCaptureProvider{onSearch: func(cr provider.Credentials) { seen = cr }}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/cap/search?query=x", nil)
	req.Header.Set("Auth-Token", "rutracker:cookie:bb_session=abc123")
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	if seen.Type != "cookie" || seen.CookieValue != "bb_session=abc123" {
		t.Errorf("provider got creds %+v, want cookie bb_session=abc123 (header was not parsed)", seen)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

// countingProvider is a real provider.Provider that counts Search/Browse
// invocations so cache-hit tests can prove the provider was NOT re-hit on
// the second request.
type countingProvider struct {
	searchResult *provider.SearchResult
	browseResult *provider.BrowseResult
	onSearch     func()
	onBrowse     func()
}

func (p *countingProvider) ID() string                                  { return "count" }
func (p *countingProvider) DisplayName() string                         { return "Count" }
func (p *countingProvider) Capabilities() []provider.ProviderCapability { return nil }
func (p *countingProvider) AuthType() provider.AuthType                 { return provider.AuthNone }
func (p *countingProvider) Encoding() string                            { return "UTF-8" }
func (p *countingProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	if p.onSearch != nil {
		p.onSearch()
	}
	return p.searchResult, nil
}
func (p *countingProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	if p.onBrowse != nil {
		p.onBrowse()
	}
	return p.browseResult, nil
}
func (p *countingProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return &provider.ForumTree{}, nil
}
func (p *countingProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	return &provider.TopicResult{}, nil
}
func (p *countingProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	return &provider.TorrentResult{}, nil
}
func (p *countingProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	return &provider.FileDownload{}, nil
}
func (p *countingProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return &provider.CommentsResult{}, nil
}
func (p *countingProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *countingProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return &provider.FavoritesResult{}, nil
}
func (p *countingProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *countingProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *countingProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *countingProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return &provider.LoginResult{}, nil
}
func (p *countingProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return &provider.CaptchaImage{}, nil
}
func (p *countingProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// cacheCountProvider counts every read endpoint so the cache-hit table test
// can prove the second request was served from cache.
type cacheCountProvider struct {
	forum, topic, torrent, comments, favorites int
}

func (p *cacheCountProvider) ID() string                                  { return "cc" }
func (p *cacheCountProvider) DisplayName() string                         { return "CC" }
func (p *cacheCountProvider) Capabilities() []provider.ProviderCapability { return nil }
func (p *cacheCountProvider) AuthType() provider.AuthType                 { return provider.AuthNone }
func (p *cacheCountProvider) Encoding() string                            { return "UTF-8" }
func (p *cacheCountProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	return &provider.SearchResult{}, nil
}
func (p *cacheCountProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	return &provider.BrowseResult{}, nil
}
func (p *cacheCountProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	p.forum++
	return &provider.ForumTree{}, nil
}
func (p *cacheCountProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	p.topic++
	return &provider.TopicResult{}, nil
}
func (p *cacheCountProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	p.torrent++
	return &provider.TorrentResult{}, nil
}
func (p *cacheCountProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	return &provider.FileDownload{}, nil
}
func (p *cacheCountProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	p.comments++
	return &provider.CommentsResult{}, nil
}
func (p *cacheCountProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *cacheCountProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	p.favorites++
	return &provider.FavoritesResult{}, nil
}
func (p *cacheCountProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *cacheCountProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *cacheCountProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *cacheCountProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return &provider.LoginResult{}, nil
}
func (p *cacheCountProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return &provider.CaptchaImage{}, nil
}
func (p *cacheCountProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// credCaptureProvider records the credentials its Search received so the
// header-parsing test can assert the forwarded credentials.
type credCaptureProvider struct {
	onSearch func(provider.Credentials)
}

func (p *credCaptureProvider) ID() string                                  { return "cap" }
func (p *credCaptureProvider) DisplayName() string                         { return "Cap" }
func (p *credCaptureProvider) Capabilities() []provider.ProviderCapability { return nil }
func (p *credCaptureProvider) AuthType() provider.AuthType                 { return provider.AuthFormLogin }
func (p *credCaptureProvider) Encoding() string                            { return "UTF-8" }
func (p *credCaptureProvider) Search(ctx context.Context, opts provider.SearchOpts, cred provider.Credentials) (*provider.SearchResult, error) {
	if p.onSearch != nil {
		p.onSearch(cred)
	}
	return &provider.SearchResult{}, nil
}
func (p *credCaptureProvider) Browse(ctx context.Context, categoryID string, page int, cred provider.Credentials) (*provider.BrowseResult, error) {
	return &provider.BrowseResult{}, nil
}
func (p *credCaptureProvider) GetForumTree(ctx context.Context, cred provider.Credentials) (*provider.ForumTree, error) {
	return &provider.ForumTree{}, nil
}
func (p *credCaptureProvider) GetTopic(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.TopicResult, error) {
	return &provider.TopicResult{}, nil
}
func (p *credCaptureProvider) GetTorrent(ctx context.Context, id string, cred provider.Credentials) (*provider.TorrentResult, error) {
	return &provider.TorrentResult{}, nil
}
func (p *credCaptureProvider) DownloadFile(ctx context.Context, id string, cred provider.Credentials) (*provider.FileDownload, error) {
	return &provider.FileDownload{}, nil
}
func (p *credCaptureProvider) GetComments(ctx context.Context, id string, page int, cred provider.Credentials) (*provider.CommentsResult, error) {
	return &provider.CommentsResult{}, nil
}
func (p *credCaptureProvider) AddComment(ctx context.Context, id, message string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *credCaptureProvider) GetFavorites(ctx context.Context, cred provider.Credentials) (*provider.FavoritesResult, error) {
	return &provider.FavoritesResult{}, nil
}
func (p *credCaptureProvider) AddFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *credCaptureProvider) RemoveFavorite(ctx context.Context, id string, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *credCaptureProvider) CheckAuth(ctx context.Context, cred provider.Credentials) (bool, error) {
	return true, nil
}
func (p *credCaptureProvider) Login(ctx context.Context, opts provider.LoginOpts) (*provider.LoginResult, error) {
	return &provider.LoginResult{}, nil
}
func (p *credCaptureProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return &provider.CaptchaImage{}, nil
}
func (p *credCaptureProvider) HealthCheck(ctx context.Context) (*provider.HealthStatus, error) {
	return &provider.HealthStatus{Healthy: true}, nil
}

// ensure gin import stays referenced if helper signatures change.
var _ = gin.Version
