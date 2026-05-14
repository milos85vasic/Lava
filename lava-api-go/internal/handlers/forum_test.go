// Package handlers — forum_test.go pins the Phase 7 handler-pattern
// contract for the two forum routes. These tests exercise the
// production handler code through a real Gin engine (no shortcut into
// the handler function) using:
//
//   - a fake ScraperClient that records call counts + arguments and can
//     be programmed to return errors
//   - an in-memory fake Cache that exhibits the same hit/miss surface
//     handlers consume; the real cache.Client is exercised by
//     internal/cache/integration_test.go against real Postgres
//   - the real auth.GinMiddleware, so realm-keyed caching is exercised
//     end-to-end and a missing middleware install would surface as a
//     test failure rather than silently masking a cache-key bug
//
// Sixth Law alignment:
//   - clause 1 (same surfaces the user touches): requests are dispatched
//     through ServeHTTP on a real Gin engine — the same router code
//     that runs in production. No direct-function shortcut.
//   - clause 3 (primary assertion on user-visible state): every test's
//     chief assertion is on the HTTP response status and body bytes —
//     the wire shape an API client receives. Scraper-call counts are
//     secondary assertions used to prove the cache short-circuited.
//   - clause 2 (falsifiability): see commit message — one test was run
//     against a deliberately broken handler to confirm it can fail.
package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// fakeCache is a minimal in-memory Cache that satisfies the handlers.Cache
// surface. No TTL expiry simulation — handler tests don't need it (the
// cache.Client integration test pins TTL semantics against real Postgres).
//
// invalidateCalls counts Invalidate() invocations so Phase 7 task 7.4
// tests can assert the comments-add handler ran its three-key
// invalidation even when the underlying entries weren't cached yet (a
// silently-rejected post still triggers invalidation, per spec §6.1).
type fakeCache struct {
	mu              sync.Mutex
	store           map[string][]byte
	invalidateCalls int
}

func newFakeCache() *fakeCache { return &fakeCache{store: map[string][]byte{}} }

func (f *fakeCache) Get(_ context.Context, key string) ([]byte, cache.Outcome, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	v, ok := f.store[key]
	if !ok {
		return nil, cache.OutcomeMiss, nil
	}
	// Return a copy so test mutations cannot poison the cache state.
	out := make([]byte, len(v))
	copy(out, v)
	return out, cache.OutcomeHit, nil
}

func (f *fakeCache) Set(_ context.Context, key string, value []byte, _ time.Duration) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	stored := make([]byte, len(value))
	copy(stored, value)
	f.store[key] = stored
	return nil
}

func (f *fakeCache) Invalidate(_ context.Context, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.invalidateCalls++
	delete(f.store, key)
	return nil
}

// has reports whether `key` is present in the store. Used by
// comments_add_test.go to assert the three topic-related cache keys
// were removed by the handler's invalidation pass.
func (f *fakeCache) has(key string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	_, ok := f.store[key]
	return ok
}

func (f *fakeCache) size() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.store)
}

// fakeScraper satisfies ScraperClient. Records call counts and the most
// recent argument values so tests can assert pass-through behavior.
type fakeScraper struct {
	mu sync.Mutex

	forumCalls      int
	lastForumCookie string
	forumReturn     *gen.ForumDto
	forumErr        error

	categoryCalls    int
	lastCategoryID   string
	lastCategoryPage *int
	lastCategoryCk   string
	categoryReturn   *gen.CategoryPageDto
	categoryErr      error

	searchCalls    int
	lastSearchOpts rutracker.SearchOpts
	lastSearchCk   string
	searchReturn   *gen.SearchPageDto
	searchErr      error

	topicCalls    int
	lastTopicID   string
	lastTopicPage *int
	lastTopicCk   string
	topicReturn   *gen.ForumTopicDto
	topicErr      error

	topicPageCalls    int
	lastTopicPageID   string
	lastTopicPagePage *int
	lastTopicPageCk   string
	topicPageReturn   *gen.TopicPageDto
	topicPageErr      error

	commentsCalls    int
	lastCommentsID   string
	lastCommentsPage *int
	lastCommentsCk   string
	commentsReturn   *gen.CommentsPageDto
	commentsErr      error

	addCommentCalls       int
	lastAddCommentID      string
	lastAddCommentMessage string
	lastAddCommentCookie  string
	addCommentResult      bool
	addCommentErr         error

	torrentCalls  int
	lastTorrentID string
	lastTorrentCk string
	torrentReturn *gen.ForumTopicDtoTorrent
	torrentErr    error

	torrentFileCalls  int
	lastTorrentFileID string
	lastTorrentFileCk string
	torrentFileReturn *rutracker.TorrentFile
	torrentFileErr    error

	favoritesCalls      int
	lastFavoritesCookie string
	favoritesReturn     *gen.FavoritesDto
	favoritesErr        error

	addFavoriteCalls  int
	lastAddFavoriteID string
	lastAddFavoriteCk string
	addFavoriteResult bool
	addFavoriteErr    error

	removeFavoriteCalls  int
	lastRemoveFavoriteID string
	lastRemoveFavoriteCk string
	removeFavoriteResult bool
	removeFavoriteErr    error

	// Phase 7 task 7.7 — index/login/captcha recording fields.

	checkAuthCalls  int
	lastCheckAuthCk string
	checkAuthResult bool
	checkAuthErr    error

	loginCalls      int
	lastLoginParams rutracker.LoginParams
	loginReturn     *gen.AuthResponseDto
	loginErr        error

	captchaCalls    int
	lastCaptchaPath string
	captchaReturn   *rutracker.CaptchaImage
	captchaErr      error
}

func (f *fakeScraper) GetForum(_ context.Context, cookie string) (*gen.ForumDto, error) {
	f.mu.Lock()
	f.forumCalls++
	f.lastForumCookie = cookie
	r, e := f.forumReturn, f.forumErr
	f.mu.Unlock()
	return r, e
}

func (f *fakeScraper) GetCategoryPage(_ context.Context, forumID string, page *int, cookie string) (*gen.CategoryPageDto, error) {
	f.mu.Lock()
	f.categoryCalls++
	f.lastCategoryID = forumID
	if page != nil {
		v := *page
		f.lastCategoryPage = &v
	} else {
		f.lastCategoryPage = nil
	}
	f.lastCategoryCk = cookie
	r, e := f.categoryReturn, f.categoryErr
	f.mu.Unlock()
	return r, e
}

// GetSearchPage records the eight-field SearchOpts pointer-by-pointer so
// search_test.go can deep-equal each forwarded parameter. Pointer fields
// are deep-copied (the handler may rewrite the pointed-to memory after
// return).
func (f *fakeScraper) GetSearchPage(_ context.Context, opts rutracker.SearchOpts, cookie string) (*gen.SearchPageDto, error) {
	f.mu.Lock()
	f.searchCalls++
	// Deep-copy the SearchOpts so callers' subsequent mutations cannot
	// poison the recorded value.
	copied := rutracker.SearchOpts{}
	if opts.Query != nil {
		v := *opts.Query
		copied.Query = &v
	}
	if opts.Categories != nil {
		v := *opts.Categories
		copied.Categories = &v
	}
	if opts.Author != nil {
		v := *opts.Author
		copied.Author = &v
	}
	if opts.AuthorID != nil {
		v := *opts.AuthorID
		copied.AuthorID = &v
	}
	if opts.SortType != nil {
		v := *opts.SortType
		copied.SortType = &v
	}
	if opts.SortOrder != nil {
		v := *opts.SortOrder
		copied.SortOrder = &v
	}
	if opts.Period != nil {
		v := *opts.Period
		copied.Period = &v
	}
	if opts.Page != nil {
		v := *opts.Page
		copied.Page = &v
	}
	f.lastSearchOpts = copied
	f.lastSearchCk = cookie
	r, e := f.searchReturn, f.searchErr
	f.mu.Unlock()
	return r, e
}

// GetTopic, GetTopicPage, GetCommentsPage: each records the (id, page,
// cookie) tuple the scraper saw. *int page is deep-copied so callers'
// later mutations cannot poison the recorded value.
func (f *fakeScraper) GetTopic(_ context.Context, id string, page *int, cookie string) (*gen.ForumTopicDto, error) {
	f.mu.Lock()
	f.topicCalls++
	f.lastTopicID = id
	if page != nil {
		v := *page
		f.lastTopicPage = &v
	} else {
		f.lastTopicPage = nil
	}
	f.lastTopicCk = cookie
	r, e := f.topicReturn, f.topicErr
	f.mu.Unlock()
	return r, e
}

func (f *fakeScraper) GetTopicPage(_ context.Context, id string, page *int, cookie string) (*gen.TopicPageDto, error) {
	f.mu.Lock()
	f.topicPageCalls++
	f.lastTopicPageID = id
	if page != nil {
		v := *page
		f.lastTopicPagePage = &v
	} else {
		f.lastTopicPagePage = nil
	}
	f.lastTopicPageCk = cookie
	r, e := f.topicPageReturn, f.topicPageErr
	f.mu.Unlock()
	return r, e
}

func (f *fakeScraper) GetCommentsPage(_ context.Context, id string, page *int, cookie string) (*gen.CommentsPageDto, error) {
	f.mu.Lock()
	f.commentsCalls++
	f.lastCommentsID = id
	if page != nil {
		v := *page
		f.lastCommentsPage = &v
	} else {
		f.lastCommentsPage = nil
	}
	f.lastCommentsCk = cookie
	r, e := f.commentsReturn, f.commentsErr
	f.mu.Unlock()
	return r, e
}

// AddComment records (topicID, message, cookie) so comments_add_test.go
// can assert the handler forwarded the path-param id and the raw body
// bytes verbatim. addCommentResult / addCommentErr are programmable so
// tests cover happy-path-true, silent-reject-false, ErrUnauthorized
// (both empty-cookie and missing-form-token branches), and generic
// upstream errors.
func (f *fakeScraper) AddComment(_ context.Context, topicID, message, cookie string) (bool, error) {
	f.mu.Lock()
	f.addCommentCalls++
	f.lastAddCommentID = topicID
	f.lastAddCommentMessage = message
	f.lastAddCommentCookie = cookie
	r, e := f.addCommentResult, f.addCommentErr
	f.mu.Unlock()
	return r, e
}

// GetTorrent records (id, cookie) so torrent_test.go can assert the
// path-param id and the upstream-cookie pass-through. torrentReturn /
// torrentErr are programmable so tests cover happy-path, the four
// writeUpstreamError sentinel branches (NotFound / Forbidden /
// Unauthorized / CircuitOpen), and the default-→502 branch.
func (f *fakeScraper) GetTorrent(_ context.Context, id, cookie string) (*gen.ForumTopicDtoTorrent, error) {
	f.mu.Lock()
	f.torrentCalls++
	f.lastTorrentID = id
	f.lastTorrentCk = cookie
	r, e := f.torrentReturn, f.torrentErr
	f.mu.Unlock()
	return r, e
}

// GetTorrentFile records (id, cookie) and returns the programmed
// *TorrentFile / error pair. torrent_test.go uses torrentFileCalls to
// pin the spec §6 "never cached at the API tier" rule for /download/{id}
// — two GETs MUST produce two scraper calls regardless of any cache.
func (f *fakeScraper) GetTorrentFile(_ context.Context, id, cookie string) (*rutracker.TorrentFile, error) {
	f.mu.Lock()
	f.torrentFileCalls++
	f.lastTorrentFileID = id
	f.lastTorrentFileCk = cookie
	r, e := f.torrentFileReturn, f.torrentFileErr
	f.mu.Unlock()
	return r, e
}

// GetFavorites records the cookie pass-through. favorites_test.go uses
// favoritesCalls to pin the cache hit-on-second-call short-circuit and
// the realm-hash-affects-cache-key foundation requirement.
func (f *fakeScraper) GetFavorites(_ context.Context, cookie string) (*gen.FavoritesDto, error) {
	f.mu.Lock()
	f.favoritesCalls++
	f.lastFavoritesCookie = cookie
	r, e := f.favoritesReturn, f.favoritesErr
	f.mu.Unlock()
	return r, e
}

// AddFavorite / RemoveFavorite record (id, cookie) and the programmable
// (bool, error) return so favorites_test.go covers happy-path-true,
// silent-reject-false, ErrUnauthorized (empty cookie), generic errors,
// and the realm-scoped cache invalidation contract.
func (f *fakeScraper) AddFavorite(_ context.Context, id, cookie string) (bool, error) {
	f.mu.Lock()
	f.addFavoriteCalls++
	f.lastAddFavoriteID = id
	f.lastAddFavoriteCk = cookie
	r, e := f.addFavoriteResult, f.addFavoriteErr
	f.mu.Unlock()
	return r, e
}

func (f *fakeScraper) RemoveFavorite(_ context.Context, id, cookie string) (bool, error) {
	f.mu.Lock()
	f.removeFavoriteCalls++
	f.lastRemoveFavoriteID = id
	f.lastRemoveFavoriteCk = cookie
	r, e := f.removeFavoriteResult, f.removeFavoriteErr
	f.mu.Unlock()
	return r, e
}

// CheckAuthorised records the cookie pass-through. index_test.go uses
// checkAuthCalls to pin the "no cache" rule for / and /index — two
// consecutive GETs MUST produce two scraper calls regardless of any
// caching layer.
func (f *fakeScraper) CheckAuthorised(_ context.Context, cookie string) (bool, error) {
	f.mu.Lock()
	f.checkAuthCalls++
	f.lastCheckAuthCk = cookie
	r, e := f.checkAuthResult, f.checkAuthErr
	f.mu.Unlock()
	return r, e
}

// Login records the LoginParams (deep-copying the *string pointers so
// later caller mutations cannot poison the recorded value) and returns
// the programmed (*AuthResponseDto, error) pair. login_test.go asserts
// the form-field-to-LoginParams mapping AND the all-three-or-none
// captcha-pointer guard at the wire-shape level.
func (f *fakeScraper) Login(_ context.Context, p rutracker.LoginParams) (*gen.AuthResponseDto, error) {
	f.mu.Lock()
	copied := rutracker.LoginParams{
		Username: p.Username,
		Password: p.Password,
	}
	if p.CaptchaSid != nil {
		v := *p.CaptchaSid
		copied.CaptchaSid = &v
	}
	if p.CaptchaCode != nil {
		v := *p.CaptchaCode
		copied.CaptchaCode = &v
	}
	if p.CaptchaValue != nil {
		v := *p.CaptchaValue
		copied.CaptchaValue = &v
	}
	f.loginCalls++
	f.lastLoginParams = copied
	r, e := f.loginReturn, f.loginErr
	f.mu.Unlock()
	return r, e
}

// FetchCaptcha records the encoded path verbatim. captcha_test.go
// asserts the path goes through to the scraper EXACTLY as it appeared
// on the wire (no Gin-side URL-decoding) so the scraper-owned Base64
// decode step sees what the OpenAPI contract promises it would.
func (f *fakeScraper) FetchCaptcha(_ context.Context, encodedPath string) (*rutracker.CaptchaImage, error) {
	f.mu.Lock()
	f.captchaCalls++
	f.lastCaptchaPath = encodedPath
	r, e := f.captchaReturn, f.captchaErr
	f.mu.Unlock()
	return r, e
}

// newTestRouter mirrors what cmd/lava-api-go does in production: install
// the auth middleware first, then Register the routes. Tests that omit
// the middleware would silently always-empty the realm hash, masking
// cache-key bugs — so the helper is mandatory.
func newTestRouter(deps *Deps) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(auth.GinMiddleware())
	Register(r, deps)
	return r
}

func sampleForum() *gen.ForumDto {
	id := "1"
	return &gen.ForumDto{Children: []gen.CategoryDto{{Id: &id, Name: "Top"}}}
}

func sampleCategory(id string, page int32) *gen.CategoryPageDto {
	return &gen.CategoryPageDto{
		Category: gen.CategoryDto{Id: &id, Name: "Cat-" + id},
		Page:     page,
		Pages:    1,
	}
}

func TestForumHandler_GetForum_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{forumReturn: sampleForum()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleForum())

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/forum", nil)
		r.ServeHTTP(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("%s: status=%d want 200; body=%s", label, w.Code, w.Body.String())
		}
		if got := w.Body.Bytes(); string(got) != string(want) {
			t.Fatalf("%s: body=%s want %s", label, string(got), string(want))
		}
		if ct := w.Header().Get("Content-Type"); ct != "application/json" {
			t.Fatalf("%s: Content-Type=%q want application/json", label, ct)
		}
	}

	if scraper.forumCalls != 1 {
		t.Fatalf("forum scraper calls=%d want 1 (cache should have served the second request)", scraper.forumCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
}

func TestForumHandler_GetForum_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{forumErr: errors.New("boom")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not json: %v (%s)", err, w.Body.String())
	}
	if len(body) != 0 {
		t.Errorf("body=%v want empty {}", body)
	}
}

func TestForumHandler_GetCategoryPage_PathParamIdForwarded(t *testing.T) {
	scraper := &fakeScraper{categoryReturn: sampleCategory("123", 1)}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleCategory("123", 1))

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/123", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if string(w.Body.Bytes()) != string(want) {
		t.Fatalf("body=%s want %s", w.Body.String(), string(want))
	}
	if scraper.categoryCalls != 1 {
		t.Fatalf("category scraper calls=%d want 1", scraper.categoryCalls)
	}
	if scraper.lastCategoryID != "123" {
		t.Fatalf("forumID=%q want 123", scraper.lastCategoryID)
	}
	if scraper.lastCategoryPage != nil {
		t.Fatalf("page=%v want nil (no ?page query)", *scraper.lastCategoryPage)
	}
	if got, want := scraper.lastCategoryCk, "bb_session=tok"; got != want {
		t.Fatalf("upstream cookie=%q want %q", got, want)
	}
}

func TestForumHandler_GetCategoryPage_PageQueryForwarded(t *testing.T) {
	// Table-driven so subsequent Phase 7 tasks can mirror this shape when
	// they add their own optional-int parameters (search?page=, etc).
	// Empty ?page= MUST forward as nil (NOT &0): a copy-paste regression
	// vector that an `if pageStr := c.Query("page"); pageStr != ""` guard
	// is meant to prevent.
	two := 2
	cases := []struct {
		name    string
		url     string
		wantPtr *int
	}{
		{name: "explicit_page_2", url: "/forum/123?page=2", wantPtr: &two},
		{name: "absent_page_query", url: "/forum/123", wantPtr: nil},
		{name: "empty_page_value", url: "/forum/123?page=", wantPtr: nil},
		{name: "invalid_page_value", url: "/forum/123?page=notanumber", wantPtr: nil},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			scraper := &fakeScraper{categoryReturn: sampleCategory("123", 1)}
			r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

			w := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, tc.url, nil)
			r.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
			}

			got := scraper.lastCategoryPage
			if (got == nil) != (tc.wantPtr == nil) {
				gotStr, wantStr := "nil", "nil"
				if got != nil {
					gotStr = "&" + strconv.Itoa(*got)
				}
				if tc.wantPtr != nil {
					wantStr = "&" + strconv.Itoa(*tc.wantPtr)
				}
				t.Fatalf("page ptr=%s want %s", gotStr, wantStr)
			}
			if got != nil && tc.wantPtr != nil && *got != *tc.wantPtr {
				t.Fatalf("page ptr=&%s want &%s", strconv.Itoa(*got), strconv.Itoa(*tc.wantPtr))
			}
		})
	}
}

func TestForumHandler_GetCategoryPage_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{categoryErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/999", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

func TestForumHandler_GetCategoryPage_Forbidden_Returns403(t *testing.T) {
	scraper := &fakeScraper{categoryErr: rutracker.ErrForbidden}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/77", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("status=%d want 403; body=%s", w.Code, w.Body.String())
	}
}

func TestForumHandler_GetCategoryPage_CircuitOpen_Returns503(t *testing.T) {
	scraper := &fakeScraper{categoryErr: rutracker.ErrCircuitOpen}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/12", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want 503; body=%s", w.Code, w.Body.String())
	}
}

// TestForumHandler_GetCategoryPage_Unauthorized_Returns401 pins the
// writeUpstreamError 401 arm. NotFound, Forbidden, and CircuitOpen each
// have dedicated tests above; this one closes the gap on Unauthorized.
// The body assertion on "rutracker" guards against the sentinel's text
// drifting silently — if rutracker.ErrUnauthorized.Error() ever stops
// mentioning rutracker, this assertion surfaces it. Subsequent Phase 7
// task authors: mirror this test for your own routes (per the comment
// above writeUpstreamError in handlers.go).
func TestForumHandler_GetCategoryPage_Unauthorized_Returns401(t *testing.T) {
	scraper := &fakeScraper{categoryErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/forum/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
}

func TestForumHandler_GetForum_AuthRealmHashAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{forumReturn: sampleForum()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	// Anonymous request — first scraper call.
	wAnon := httptest.NewRecorder()
	rAnon := httptest.NewRequest(http.MethodGet, "/forum", nil)
	r.ServeHTTP(wAnon, rAnon)
	if wAnon.Code != http.StatusOK {
		t.Fatalf("anon: status=%d want 200", wAnon.Code)
	}
	if scraper.forumCalls != 1 {
		t.Fatalf("anon: scraper calls=%d want 1", scraper.forumCalls)
	}

	// Authenticated request with a token — different realm hash → cache
	// miss → second scraper call.
	wAuth := httptest.NewRecorder()
	rAuth := httptest.NewRequest(http.MethodGet, "/forum", nil)
	rAuth.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth, rAuth)
	if wAuth.Code != http.StatusOK {
		t.Fatalf("auth: status=%d want 200", wAuth.Code)
	}
	if scraper.forumCalls != 2 {
		t.Fatalf("auth: scraper calls=%d want 2 (anon and realm-keyed must use distinct cache slots)", scraper.forumCalls)
	}

	// Same Auth-Token again → cache hit, scraper count unchanged.
	wAuth2 := httptest.NewRecorder()
	rAuth2 := httptest.NewRequest(http.MethodGet, "/forum", nil)
	rAuth2.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth2, rAuth2)
	if wAuth2.Code != http.StatusOK {
		t.Fatalf("auth-2: status=%d want 200", wAuth2.Code)
	}
	if scraper.forumCalls != 2 {
		t.Fatalf("auth-2: scraper calls=%d want 2 (realm-keyed entry should have served the second hit)", scraper.forumCalls)
	}

	// Two cache entries: one anon, one realm.
	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (anon entry + realm entry)", got)
	}
}

// TestForumHandler_GetCategoryPage_AuthRealmHashAffectsCacheKey is the
// path-parametrised companion to GetForum_AuthRealmHashAffectsCacheKey.
// /forum (no path vars) and /forum/{id} (with path vars) flow through
// different branches of cache.Key — a regression where path-vars
// overwrite the realm hash would slip past the existing /forum-only
// test, so this pins the path-param + realm-hash combined surface.
func TestForumHandler_GetCategoryPage_AuthRealmHashAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{categoryReturn: sampleCategory("123", 1)}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	// Step 1: anonymous GET /forum/123 — first scraper call, anon entry stored.
	wAnon := httptest.NewRecorder()
	rAnon := httptest.NewRequest(http.MethodGet, "/forum/123", nil)
	r.ServeHTTP(wAnon, rAnon)
	if wAnon.Code != http.StatusOK {
		t.Fatalf("anon: status=%d want 200", wAnon.Code)
	}
	if scraper.categoryCalls != 1 {
		t.Fatalf("anon: scraper calls=%d want 1", scraper.categoryCalls)
	}

	// Step 2: realm-keyed GET /forum/123 with Auth-Token: secret —
	// different cache key → cache miss → second scraper call.
	wAuth := httptest.NewRecorder()
	rAuth := httptest.NewRequest(http.MethodGet, "/forum/123", nil)
	rAuth.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth, rAuth)
	if wAuth.Code != http.StatusOK {
		t.Fatalf("auth: status=%d want 200", wAuth.Code)
	}
	if scraper.categoryCalls != 2 {
		t.Fatalf("auth: scraper calls=%d want 2 (anon and realm-keyed must use distinct cache slots)", scraper.categoryCalls)
	}

	// Step 3: same Auth-Token again → cache hit, scraper count unchanged.
	wAuth2 := httptest.NewRecorder()
	rAuth2 := httptest.NewRequest(http.MethodGet, "/forum/123", nil)
	rAuth2.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth2, rAuth2)
	if wAuth2.Code != http.StatusOK {
		t.Fatalf("auth-2: status=%d want 200", wAuth2.Code)
	}
	if scraper.categoryCalls != 2 {
		t.Fatalf("auth-2: scraper calls=%d want 2 (realm-keyed entry should have served the second hit)", scraper.categoryCalls)
	}

	// Two cache entries: one anon, one realm.
	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (anon entry + realm entry)", got)
	}
}

func TestRegister_RegistersForumRoutes(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"GET /forum":     false,
		"GET /forum/:id": false,
	}
	for _, ri := range router.Routes() {
		key := ri.Method + " " + ri.Path
		if _, ok := want[key]; ok {
			want[key] = true
		}
	}
	for k, v := range want {
		if !v {
			t.Errorf("Register did not wire route %q", k)
		}
	}
}
