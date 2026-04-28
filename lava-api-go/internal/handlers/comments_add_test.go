// Package handlers — comments_add_test.go pins the Phase 7 task 7.4
// contract for POST /comments/{id}/add. Mirrors the forum/search/topic
// test shape: real Gin engine, real auth.GinMiddleware, fake Cache +
// fake ScraperClient defined in forum_test.go.
//
// Sixth Law alignment:
//   - clause 1 (same surfaces the user touches): every test dispatches
//     through ServeHTTP on a real Gin engine — no direct-function
//     shortcut into the handler.
//   - clause 2 (falsifiability): see commit message — the
//     InvalidatesTopicCacheKeys test was run against a deliberately
//     broken handler to confirm it can fail.
//   - clause 3 (primary assertion on user-visible state): the chief
//     assertion is on the HTTP status, response body bytes, and (for
//     the invalidation test) on whether the cache rows actually
//     disappeared from the in-memory store after the POST. Call counts
//     are secondary.
//
// This handler is the FIRST in the package that mutates server-visible
// state, so it is the first to assert post-write cache invalidation —
// the InvalidatesTopicCacheKeys / DoesNotInvalidateOtherTopics pair
// together pin "writer flushes the same realm's three sibling reads
// for the same topic id, and ONLY for that topic id".
package handlers

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// topicCacheKey is a test-side mirror of invalidateTopicCacheKeys's key
// computation: same (method=GET, route, {id}, empty-query, realm) tuple.
// Tests that pre-populate the fakeCache use this so the keys they store
// match the keys the handler will invalidate. If the production helper
// ever changes its key formula, this mirror MUST move with it; the
// tests would otherwise pre-store under a different key than the
// handler tries to delete and silently pass.
func topicCacheKey(route, id, realm string) string {
	return cache.Key(http.MethodGet, route, map[string]string{"id": id}, url.Values{}, realm)
}

// ----- happy path / silent reject ------------------------------------------

func TestCommentsAddHandler_HappyPath_True(t *testing.T) {
	scraper := &fakeScraper{addCommentResult: true}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/comments/123/add", strings.NewReader("hello world"))
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	var got bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body not json bool: %v (%s)", err, w.Body.String())
	}
	if !got {
		t.Fatalf("body=%v want true", got)
	}
	if scraper.addCommentCalls != 1 {
		t.Fatalf("AddComment calls=%d want 1", scraper.addCommentCalls)
	}
	if scraper.lastAddCommentID != "123" {
		t.Fatalf("AddComment id=%q want %q", scraper.lastAddCommentID, "123")
	}
	if scraper.lastAddCommentMessage != "hello world" {
		t.Fatalf("AddComment message=%q want %q", scraper.lastAddCommentMessage, "hello world")
	}
	if got, want := scraper.lastAddCommentCookie, "bb_session=tok"; got != want {
		t.Fatalf("AddComment cookie=%q want %q", got, want)
	}
}

func TestCommentsAddHandler_UpstreamSilentReject_False(t *testing.T) {
	scraper := &fakeScraper{addCommentResult: false}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/comments/123/add", strings.NewReader("flood-blocked"))
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200 (silent reject is still 200, the JSON bool carries the signal); body=%s", w.Code, w.Body.String())
	}
	var got bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body not json bool: %v (%s)", err, w.Body.String())
	}
	if got {
		t.Fatalf("body=%v want false", got)
	}
	// The silent-reject path STILL invalidates the three sibling read
	// caches (spec §6.1): a silently-rejected post may have moved
	// server state forward (e.g. a flood-control counter), and the
	// next GET should see that fresh state. invalidateCalls counts
	// every Invalidate() call, so 3 is the floor.
	if c.invalidateCalls != 3 {
		t.Fatalf("invalidateCalls=%d want 3 (silent-reject still invalidates the three sibling reads)", c.invalidateCalls)
	}
}

// ----- error mapping --------------------------------------------------------

func TestCommentsAddHandler_EmptyCookie_Returns401(t *testing.T) {
	// The scraper enforces empty-cookie ⇒ ErrUnauthorized; the handler
	// just forwards what it returns. We model that by programming the
	// fake to return ErrUnauthorized when the handler reaches it.
	scraper := &fakeScraper{addCommentErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	// No Auth-Token header → UpstreamCookie returns "" → scraper would
	// hit the empty-cookie branch and return ErrUnauthorized.
	req := httptest.NewRequest(http.MethodPost, "/comments/123/add", strings.NewReader("noop"))
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	if got := scraper.lastAddCommentCookie; got != "" {
		t.Fatalf("scraper cookie=%q want \"\" (no Auth-Token header should produce an empty UpstreamCookie)", got)
	}
}

// TestCommentsAddHandler_FormTokenMissing_Returns401 covers the second
// ErrUnauthorized branch in rutracker.AddComment — the form_token regex
// fails to match. The handler treats this identically to the empty-
// cookie branch (both produce ErrUnauthorized → 401), so the test pins
// that the 401 mapping isn't accidentally narrowed to one branch only.
func TestCommentsAddHandler_FormTokenMissing_Returns401(t *testing.T) {
	scraper := &fakeScraper{addCommentErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/comments/123/add", strings.NewReader("noop"))
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, "rutracker") {
		t.Fatalf("body=%q does not mention %q (sentinel text=%q)", body, "rutracker", rutracker.ErrUnauthorized.Error())
	}
}

func TestCommentsAddHandler_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{addCommentErr: errors.New("upstream-broken")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/comments/123/add", strings.NewReader("noop"))
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// ----- cache invalidation ---------------------------------------------------

func TestCommentsAddHandler_InvalidatesTopicCacheKeys(t *testing.T) {
	scraper := &fakeScraper{addCommentResult: true}
	c := newFakeCache()

	// Pre-populate the three cache rows the handler MUST invalidate,
	// using the same key formula the handler uses (topicCacheKey
	// mirrors invalidateTopicCacheKeys). The realm hash sha256("tok")
	// is what auth.RealmHash will compute when Auth-Token: tok is
	// sent; we don't need to compute it here because we look up the
	// realm by hashing tok ourselves via auth.RealmHash in a stub
	// request below. Simpler: just call topicCacheKey with the same
	// realm string the handler will see at runtime.
	realm := authRealmHashFor("tok")
	keyTopic := topicCacheKey(topicRouteTemplate, "123", realm)
	keyTopic2 := topicCacheKey(topicPageRouteTemplate, "123", realm)
	keyComments := topicCacheKey(commentsRouteTemplate, "123", realm)
	if err := c.Set(context.Background(), keyTopic, []byte(`{"stale":"topic"}`), 0); err != nil {
		t.Fatalf("seed keyTopic: %v", err)
	}
	if err := c.Set(context.Background(), keyTopic2, []byte(`{"stale":"topic2"}`), 0); err != nil {
		t.Fatalf("seed keyTopic2: %v", err)
	}
	if err := c.Set(context.Background(), keyComments, []byte(`{"stale":"comments"}`), 0); err != nil {
		t.Fatalf("seed keyComments: %v", err)
	}
	if c.size() != 3 {
		t.Fatalf("seed: cache size=%d want 3", c.size())
	}

	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/comments/123/add", strings.NewReader("hi"))
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}

	// PRIMARY ASSERTION: user-visible cache state. After the POST, the
	// next GET /topic/123, GET /topic2/123, GET /comments/123 with the
	// same Auth-Token would miss-and-refetch — proven by the three
	// keys being absent from the cache.
	if c.has(keyTopic) {
		t.Errorf("after POST: keyTopic still present; want invalidated")
	}
	if c.has(keyTopic2) {
		t.Errorf("after POST: keyTopic2 still present; want invalidated")
	}
	if c.has(keyComments) {
		t.Errorf("after POST: keyComments still present; want invalidated")
	}
}

func TestCommentsAddHandler_DoesNotInvalidateOtherTopics(t *testing.T) {
	scraper := &fakeScraper{addCommentResult: true}
	c := newFakeCache()
	realm := authRealmHashFor("tok")

	// Seed: three keys for topic 123 + one key for an UNRELATED topic.
	keyT123 := topicCacheKey(topicRouteTemplate, "123", realm)
	keyT2_123 := topicCacheKey(topicPageRouteTemplate, "123", realm)
	keyC123 := topicCacheKey(commentsRouteTemplate, "123", realm)
	keyOther := topicCacheKey(topicRouteTemplate, "OTHER", realm)
	for k, v := range map[string][]byte{
		keyT123:   []byte("t-123"),
		keyT2_123: []byte("t2-123"),
		keyC123:   []byte("c-123"),
		keyOther:  []byte("t-OTHER"),
	} {
		if err := c.Set(context.Background(), k, v, 0); err != nil {
			t.Fatalf("seed %q: %v", k, err)
		}
	}

	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/comments/123/add", strings.NewReader("hi"))
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}

	if c.has(keyT123) {
		t.Errorf("keyT123 still present; want invalidated")
	}
	if c.has(keyT2_123) {
		t.Errorf("keyT2_123 still present; want invalidated")
	}
	if c.has(keyC123) {
		t.Errorf("keyC123 still present; want invalidated")
	}
	// PRIMARY ASSERTION: the OTHER-topic entry must NOT have been
	// touched. A regression where invalidation matched on route-name
	// only (ignoring path-vars) would clear keyOther too.
	if !c.has(keyOther) {
		t.Errorf("keyOther was invalidated by a POST against topic 123; invalidation must be scoped to the path id")
	}
}

// ----- raw body forwarding --------------------------------------------------

func TestCommentsAddHandler_RawBodyForwarded(t *testing.T) {
	cases := []struct {
		name        string
		contentType string
		body        string
	}{
		{name: "text_plain", contentType: "text/plain", body: "plain text body with [b]bbcode[/b]"},
		// rutracker clients in the wild also send form-encoded; the
		// route MUST treat the body verbatim regardless. The body
		// below LOOKS form-encoded but is the message in full — no
		// form-decoding happens.
		{name: "form_urlencoded", contentType: "application/x-www-form-urlencoded", body: "key=value&also=this"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			scraper := &fakeScraper{addCommentResult: true}
			r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

			w := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, "/comments/9/add", bytes.NewBufferString(tc.body))
			req.Header.Set(auth.HeaderName, "tok")
			req.Header.Set("Content-Type", tc.contentType)
			r.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("%s: status=%d want 200; body=%s", tc.name, w.Code, w.Body.String())
			}
			if scraper.lastAddCommentMessage != tc.body {
				t.Fatalf("%s: AddComment message=%q want %q (raw bytes must pass through unchanged)", tc.name, scraper.lastAddCommentMessage, tc.body)
			}
		})
	}
}

// ----- registration ---------------------------------------------------------

func TestRegister_RegistersCommentsAddRoute(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"POST /comments/:id/add": false,
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

// authRealmHashFor mirrors auth.RealmHash for a given Auth-Token value.
// Used to pre-populate cache keys whose realm-hash component must match
// the realm-hash the handler will compute at runtime. Inlined here
// rather than calling auth.RealmHash because we want a stable
// test-side mirror (a divergence in the production helper would
// silently break this test the same way it would break the handler).
func authRealmHashFor(token string) string {
	if token == "" {
		return ""
	}
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set(auth.HeaderName, token)
	return auth.RealmHash(req)
}
