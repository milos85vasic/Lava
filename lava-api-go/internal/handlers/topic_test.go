// Package handlers — topic_test.go pins the Phase 7 task 7.3 contract
// for the three topic-related GET routes (/topic/{id}, /topic2/{id},
// /comments/{id}). Mirrors the forum_test.go / search_test.go shape:
// real Gin engine, real auth.GinMiddleware, fake Cache + ScraperClient
// defined in forum_test.go and extended for the topic methods there.
//
// Sixth Law alignment:
//   - clause 1: requests dispatched through ServeHTTP on a real Gin
//     engine — no direct-function shortcut.
//   - clause 2 (falsifiability): see commit message — one test was run
//     against a deliberately broken handler to confirm it can fail.
//   - clause 3: chief assertions are on HTTP status / body bytes and on
//     recorded scraper arguments (id / page) — the wire surface a real
//     scraper observes. Call counts are secondary, used to prove the
//     cache short-circuited.
//
// Each route gets the same core test set (happy-path-then-cache,
// path-param-forwarded, page-query-forwarded, 404 / 403 / 401 / 502),
// so a future copy-paste regression on one route would surface as a
// failed assertion that names the route. The realm-hash test is on
// /topic/{id} only (foundation requirement: at least one route per
// task).
package handlers

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/auth"
	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// sampleTopic returns a ForumTopicDto whose union variant is the simple
// Topic case. The returned value is JSON-marshalable and deterministic
// so cache-hit body comparison succeeds.
func sampleTopic(id string) *gen.ForumTopicDto {
	dto := &gen.ForumTopicDto{}
	if err := dto.FromForumTopicDtoTopic(gen.ForumTopicDtoTopic{
		Id:    id,
		Title: "Topic-" + id,
	}); err != nil {
		panic(err)
	}
	return dto
}

func sampleTopicPage(id string) *gen.TopicPageDto {
	return &gen.TopicPageDto{
		Id:           id,
		Title:        "TopicPage-" + id,
		CommentsPage: gen.TopicPageCommentsDto{Page: 1, Pages: 1, Posts: []gen.PostDto{}},
	}
}

func sampleCommentsPage(id string) *gen.CommentsPageDto {
	return &gen.CommentsPageDto{
		Id:    id,
		Title: "CommentsPage-" + id,
		Page:  1,
		Pages: 1,
		Posts: []gen.PostDto{},
	}
}

// ----- /topic/{id} ---------------------------------------------------------

func TestTopicHandler_GetTopic_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{topicReturn: sampleTopic("123")}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleTopic("123"))

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/topic/123", nil)
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

	if scraper.topicCalls != 1 {
		t.Fatalf("/topic/{id} scraper calls=%d want 1 (cache should have served the second request)", scraper.topicCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
}

func TestTopicHandler_GetTopic_PathParamForwarded(t *testing.T) {
	scraper := &fakeScraper{topicReturn: sampleTopic("123")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic/123", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.lastTopicID != "123" {
		t.Fatalf("/topic id=%q want %q", scraper.lastTopicID, "123")
	}
	if scraper.lastTopicPage != nil {
		t.Fatalf("/topic page=%v want nil (no ?page query)", *scraper.lastTopicPage)
	}
	if got, want := scraper.lastTopicCk, "bb_session=tok"; got != want {
		t.Fatalf("/topic upstream cookie=%q want %q", got, want)
	}
}

func TestTopicHandler_GetTopic_PageQueryForwarded(t *testing.T) {
	two := 2
	cases := []struct {
		name    string
		url     string
		wantPtr *int
	}{
		{name: "explicit_page_2", url: "/topic/123?page=2", wantPtr: &two},
		{name: "absent_page_query", url: "/topic/123", wantPtr: nil},
		{name: "empty_page_value", url: "/topic/123?page=", wantPtr: nil},
		{name: "invalid_page_value", url: "/topic/123?page=abc", wantPtr: nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			scraper := &fakeScraper{topicReturn: sampleTopic("123")}
			r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

			w := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, tc.url, nil)
			r.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("/topic %s: status=%d want 200; body=%s", tc.name, w.Code, w.Body.String())
			}
			got := scraper.lastTopicPage
			if (got == nil) != (tc.wantPtr == nil) {
				gotStr, wantStr := "nil", "nil"
				if got != nil {
					gotStr = "&" + strconv.Itoa(*got)
				}
				if tc.wantPtr != nil {
					wantStr = "&" + strconv.Itoa(*tc.wantPtr)
				}
				t.Fatalf("/topic %s: page ptr=%s want %s", tc.name, gotStr, wantStr)
			}
			if got != nil && tc.wantPtr != nil && *got != *tc.wantPtr {
				t.Fatalf("/topic %s: page ptr=&%d want &%d", tc.name, *got, *tc.wantPtr)
			}
		})
	}
}

func TestTopicHandler_GetTopic_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{topicErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic/999", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("/topic status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

func TestTopicHandler_GetTopic_Forbidden_Returns403(t *testing.T) {
	scraper := &fakeScraper{topicErr: rutracker.ErrForbidden}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic/77", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("/topic status=%d want 403; body=%s", w.Code, w.Body.String())
	}
}

func TestTopicHandler_GetTopic_Unauthorized_Returns401(t *testing.T) {
	scraper := &fakeScraper{topicErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("/topic status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, "rutracker") {
		t.Fatalf("/topic body=%q does not mention %q (sentinel text=%q)", body, "rutracker", rutracker.ErrUnauthorized.Error())
	}
}

func TestTopicHandler_GetTopic_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{topicErr: errors.New("boom")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic/1", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("/topic status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// TestTopicHandler_GetTopic_AuthRealmHashAffectsCacheKey pins that the
// realm hash flows into the cache key for the path-parametrised route
// (foundation requirement: at least one route per task).
func TestTopicHandler_GetTopic_AuthRealmHashAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{topicReturn: sampleTopic("123")}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	wAnon := httptest.NewRecorder()
	rAnon := httptest.NewRequest(http.MethodGet, "/topic/123", nil)
	r.ServeHTTP(wAnon, rAnon)
	if wAnon.Code != http.StatusOK {
		t.Fatalf("anon: status=%d want 200", wAnon.Code)
	}
	if scraper.topicCalls != 1 {
		t.Fatalf("anon: scraper calls=%d want 1", scraper.topicCalls)
	}

	wAuth := httptest.NewRecorder()
	rAuth := httptest.NewRequest(http.MethodGet, "/topic/123", nil)
	rAuth.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth, rAuth)
	if wAuth.Code != http.StatusOK {
		t.Fatalf("auth: status=%d want 200", wAuth.Code)
	}
	if scraper.topicCalls != 2 {
		t.Fatalf("auth: scraper calls=%d want 2 (anon and realm-keyed must use distinct cache slots)", scraper.topicCalls)
	}

	wAuth2 := httptest.NewRecorder()
	rAuth2 := httptest.NewRequest(http.MethodGet, "/topic/123", nil)
	rAuth2.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth2, rAuth2)
	if wAuth2.Code != http.StatusOK {
		t.Fatalf("auth-2: status=%d want 200", wAuth2.Code)
	}
	if scraper.topicCalls != 2 {
		t.Fatalf("auth-2: scraper calls=%d want 2 (realm-keyed entry should have served the second hit)", scraper.topicCalls)
	}

	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (anon entry + realm entry)", got)
	}
}

// ----- /topic2/{id} --------------------------------------------------------

func TestTopicHandler_GetTopicPage_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{topicPageReturn: sampleTopicPage("321")}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleTopicPage("321"))

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/topic2/321", nil)
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

	if scraper.topicPageCalls != 1 {
		t.Fatalf("/topic2/{id} scraper calls=%d want 1 (cache should have served the second request)", scraper.topicPageCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
}

func TestTopicHandler_GetTopicPage_PathParamForwarded(t *testing.T) {
	scraper := &fakeScraper{topicPageReturn: sampleTopicPage("321")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic2/321", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.lastTopicPageID != "321" {
		t.Fatalf("/topic2 id=%q want %q", scraper.lastTopicPageID, "321")
	}
	if scraper.lastTopicPagePage != nil {
		t.Fatalf("/topic2 page=%v want nil", *scraper.lastTopicPagePage)
	}
	if got, want := scraper.lastTopicPageCk, "bb_session=tok"; got != want {
		t.Fatalf("/topic2 upstream cookie=%q want %q", got, want)
	}
}

func TestTopicHandler_GetTopicPage_PageQueryForwarded(t *testing.T) {
	two := 2
	cases := []struct {
		name    string
		url     string
		wantPtr *int
	}{
		{name: "explicit_page_2", url: "/topic2/321?page=2", wantPtr: &two},
		{name: "absent_page_query", url: "/topic2/321", wantPtr: nil},
		{name: "empty_page_value", url: "/topic2/321?page=", wantPtr: nil},
		{name: "invalid_page_value", url: "/topic2/321?page=abc", wantPtr: nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			scraper := &fakeScraper{topicPageReturn: sampleTopicPage("321")}
			r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

			w := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, tc.url, nil)
			r.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("/topic2 %s: status=%d want 200; body=%s", tc.name, w.Code, w.Body.String())
			}
			got := scraper.lastTopicPagePage
			if (got == nil) != (tc.wantPtr == nil) {
				gotStr, wantStr := "nil", "nil"
				if got != nil {
					gotStr = "&" + strconv.Itoa(*got)
				}
				if tc.wantPtr != nil {
					wantStr = "&" + strconv.Itoa(*tc.wantPtr)
				}
				t.Fatalf("/topic2 %s: page ptr=%s want %s", tc.name, gotStr, wantStr)
			}
			if got != nil && tc.wantPtr != nil && *got != *tc.wantPtr {
				t.Fatalf("/topic2 %s: page ptr=&%d want &%d", tc.name, *got, *tc.wantPtr)
			}
		})
	}
}

func TestTopicHandler_GetTopicPage_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{topicPageErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic2/999", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("/topic2 status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

func TestTopicHandler_GetTopicPage_Forbidden_Returns403(t *testing.T) {
	scraper := &fakeScraper{topicPageErr: rutracker.ErrForbidden}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic2/77", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("/topic2 status=%d want 403; body=%s", w.Code, w.Body.String())
	}
}

func TestTopicHandler_GetTopicPage_Unauthorized_Returns401(t *testing.T) {
	scraper := &fakeScraper{topicPageErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic2/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("/topic2 status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, "rutracker") {
		t.Fatalf("/topic2 body=%q does not mention %q (sentinel text=%q)", body, "rutracker", rutracker.ErrUnauthorized.Error())
	}
}

func TestTopicHandler_GetTopicPage_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{topicPageErr: errors.New("boom")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/topic2/1", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("/topic2 status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// ----- /comments/{id} ------------------------------------------------------

func TestTopicHandler_GetCommentsPage_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{commentsReturn: sampleCommentsPage("555")}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleCommentsPage("555"))

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/comments/555", nil)
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

	if scraper.commentsCalls != 1 {
		t.Fatalf("/comments/{id} scraper calls=%d want 1 (cache should have served the second request)", scraper.commentsCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
}

func TestTopicHandler_GetCommentsPage_PathParamForwarded(t *testing.T) {
	scraper := &fakeScraper{commentsReturn: sampleCommentsPage("555")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/comments/555", nil)
	req.Header.Set(auth.HeaderName, "tok")
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.lastCommentsID != "555" {
		t.Fatalf("/comments id=%q want %q", scraper.lastCommentsID, "555")
	}
	if scraper.lastCommentsPage != nil {
		t.Fatalf("/comments page=%v want nil", *scraper.lastCommentsPage)
	}
	if got, want := scraper.lastCommentsCk, "bb_session=tok"; got != want {
		t.Fatalf("/comments upstream cookie=%q want %q", got, want)
	}
}

func TestTopicHandler_GetCommentsPage_PageQueryForwarded(t *testing.T) {
	two := 2
	cases := []struct {
		name    string
		url     string
		wantPtr *int
	}{
		{name: "explicit_page_2", url: "/comments/555?page=2", wantPtr: &two},
		{name: "absent_page_query", url: "/comments/555", wantPtr: nil},
		{name: "empty_page_value", url: "/comments/555?page=", wantPtr: nil},
		{name: "invalid_page_value", url: "/comments/555?page=abc", wantPtr: nil},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			scraper := &fakeScraper{commentsReturn: sampleCommentsPage("555")}
			r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

			w := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, tc.url, nil)
			r.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("/comments %s: status=%d want 200; body=%s", tc.name, w.Code, w.Body.String())
			}
			got := scraper.lastCommentsPage
			if (got == nil) != (tc.wantPtr == nil) {
				gotStr, wantStr := "nil", "nil"
				if got != nil {
					gotStr = "&" + strconv.Itoa(*got)
				}
				if tc.wantPtr != nil {
					wantStr = "&" + strconv.Itoa(*tc.wantPtr)
				}
				t.Fatalf("/comments %s: page ptr=%s want %s", tc.name, gotStr, wantStr)
			}
			if got != nil && tc.wantPtr != nil && *got != *tc.wantPtr {
				t.Fatalf("/comments %s: page ptr=&%d want &%d", tc.name, *got, *tc.wantPtr)
			}
		})
	}
}

func TestTopicHandler_GetCommentsPage_NotFound_Returns404(t *testing.T) {
	scraper := &fakeScraper{commentsErr: rutracker.ErrNotFound}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/comments/999", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("/comments status=%d want 404; body=%s", w.Code, w.Body.String())
	}
}

func TestTopicHandler_GetCommentsPage_Forbidden_Returns403(t *testing.T) {
	scraper := &fakeScraper{commentsErr: rutracker.ErrForbidden}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/comments/77", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("/comments status=%d want 403; body=%s", w.Code, w.Body.String())
	}
}

func TestTopicHandler_GetCommentsPage_Unauthorized_Returns401(t *testing.T) {
	scraper := &fakeScraper{commentsErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/comments/42", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("/comments status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, "rutracker") {
		t.Fatalf("/comments body=%q does not mention %q (sentinel text=%q)", body, "rutracker", rutracker.ErrUnauthorized.Error())
	}
}

func TestTopicHandler_GetCommentsPage_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{commentsErr: errors.New("boom")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/comments/1", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("/comments status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// ----- registration --------------------------------------------------------

func TestRegister_RegistersTopicRoutes(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"GET /topic/:id":    false,
		"GET /topic2/:id":   false,
		"GET /comments/:id": false,
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
