// Package handlers — search_test.go pins the Phase 7 task 7.2 search
// handler contract. Mirrors the forum_test.go shape (real Gin engine,
// real auth.GinMiddleware, fake Cache + ScraperClient defined in
// forum_test.go and extended for GetSearchPage there).
//
// Sixth Law alignment:
//   - clause 1: requests dispatched through ServeHTTP on a real Gin
//     engine — no direct-function shortcut.
//   - clause 2 (falsifiability): see commit message — one test was run
//     against a deliberately broken handler to confirm it can fail.
//   - clause 3: chief assertions are on HTTP status / body bytes and on
//     the recorded SearchOpts pointer-equality (the wire shape the
//     scraper observes). Call counts are secondary.
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

// Local pointer helpers — kept private to the test file rather than
// exported, since other handler tests do not need them.
func strPtr(s string) *string { return &s }
func intPtr(i int) *int       { return &i }
func sortTypePtr(t gen.SearchSortTypeDto) *gen.SearchSortTypeDto {
	return &t
}
func sortOrderPtr(o gen.SearchSortOrderDto) *gen.SearchSortOrderDto {
	return &o
}
func periodPtr(p gen.SearchPeriodDto) *gen.SearchPeriodDto {
	return &p
}

func sampleSearchPage() *gen.SearchPageDto {
	return &gen.SearchPageDto{
		Page:     1,
		Pages:    1,
		Torrents: []gen.ForumTopicDtoTorrent{},
	}
}

// strPtrEq deep-equals two *string values, treating nil-vs-non-nil as
// unequal.
func strPtrEq(got, want *string) bool {
	if (got == nil) != (want == nil) {
		return false
	}
	if got == nil {
		return true
	}
	return *got == *want
}

func TestSearchHandler_GetSearch_HappyPath_HitsScraperOnFirstCall_HitsCacheOnSecond(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	want, _ := json.Marshal(sampleSearchPage())

	for _, label := range []string{"first", "second"} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/search", nil)
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

	if scraper.searchCalls != 1 {
		t.Fatalf("search scraper calls=%d want 1 (cache should have served the second request)", scraper.searchCalls)
	}
	if c.size() != 1 {
		t.Fatalf("cache size=%d want 1", c.size())
	}
}

func TestSearchHandler_GetSearch_AllOptionalsForwarded(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	url := "/search?query=foo&categories=9&author=alice&authorId=42" +
		"&sort=Size&order=Descending&period=LastWeek&page=2"
	req := httptest.NewRequest(http.MethodGet, url, nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.searchCalls != 1 {
		t.Fatalf("scraper calls=%d want 1", scraper.searchCalls)
	}

	got := scraper.lastSearchOpts
	if !strPtrEq(got.Query, strPtr("foo")) {
		t.Fatalf("Query: got %v, want %q", got.Query, "foo")
	}
	if !strPtrEq(got.Categories, strPtr("9")) {
		t.Fatalf("Categories: got %v, want %q", got.Categories, "9")
	}
	if !strPtrEq(got.Author, strPtr("alice")) {
		t.Fatalf("Author: got %v, want %q", got.Author, "alice")
	}
	if !strPtrEq(got.AuthorID, strPtr("42")) {
		t.Fatalf("AuthorID: got %v, want %q", got.AuthorID, "42")
	}
	if got.SortType == nil || *got.SortType != gen.SearchSortTypeDtoSize {
		t.Fatalf("SortType: got %v, want Size", got.SortType)
	}
	if got.SortOrder == nil || *got.SortOrder != gen.Descending {
		t.Fatalf("SortOrder: got %v, want Descending", got.SortOrder)
	}
	if got.Period == nil || *got.Period != gen.LastWeek {
		t.Fatalf("Period: got %v, want LastWeek", got.Period)
	}
	if got.Page == nil || *got.Page != 2 {
		gotStr := "nil"
		if got.Page != nil {
			gotStr = "&" + strconv.Itoa(*got.Page)
		}
		t.Fatalf("Page: got %s, want &2", gotStr)
	}
}

func TestSearchHandler_GetSearch_AllOptionalsOmitted(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/search", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}

	got := scraper.lastSearchOpts
	if got.Query != nil {
		t.Errorf("Query: got %v, want nil", *got.Query)
	}
	if got.Categories != nil {
		t.Errorf("Categories: got %v, want nil", *got.Categories)
	}
	if got.Author != nil {
		t.Errorf("Author: got %v, want nil", *got.Author)
	}
	if got.AuthorID != nil {
		t.Errorf("AuthorID: got %v, want nil", *got.AuthorID)
	}
	if got.SortType != nil {
		t.Errorf("SortType: got %v, want nil", *got.SortType)
	}
	if got.SortOrder != nil {
		t.Errorf("SortOrder: got %v, want nil", *got.SortOrder)
	}
	if got.Period != nil {
		t.Errorf("Period: got %v, want nil", *got.Period)
	}
	if got.Page != nil {
		t.Errorf("Page: got &%s, want nil", strconv.Itoa(*got.Page))
	}
}

// TestSearchHandler_GetSearch_EmptyOptionalsAreNil pins the regression
// vector that an empty `?param=` value MUST forward as nil, NOT &"" or
// &0. The empty `?page=` sub-case is the foundation-required empty-int-
// string case.
func TestSearchHandler_GetSearch_EmptyOptionalsAreNil(t *testing.T) {
	cases := []struct {
		name string
		url  string
		// which field to inspect
		check func(t *testing.T, opts rutracker.SearchOpts)
	}{
		{
			name: "empty_query",
			url:  "/search?query=",
			check: func(t *testing.T, opts rutracker.SearchOpts) {
				if opts.Query != nil {
					t.Errorf("Query: got %q, want nil", *opts.Query)
				}
			},
		},
		{
			name: "empty_categories",
			url:  "/search?categories=",
			check: func(t *testing.T, opts rutracker.SearchOpts) {
				if opts.Categories != nil {
					t.Errorf("Categories: got %q, want nil", *opts.Categories)
				}
			},
		},
		{
			name: "empty_author",
			url:  "/search?author=",
			check: func(t *testing.T, opts rutracker.SearchOpts) {
				if opts.Author != nil {
					t.Errorf("Author: got %q, want nil", *opts.Author)
				}
			},
		},
		{
			name: "empty_authorId",
			url:  "/search?authorId=",
			check: func(t *testing.T, opts rutracker.SearchOpts) {
				if opts.AuthorID != nil {
					t.Errorf("AuthorID: got %q, want nil", *opts.AuthorID)
				}
			},
		},
		{
			name: "empty_page",
			url:  "/search?page=",
			check: func(t *testing.T, opts rutracker.SearchOpts) {
				if opts.Page != nil {
					t.Errorf("Page: got &%s, want nil", strconv.Itoa(*opts.Page))
				}
			},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			scraper := &fakeScraper{searchReturn: sampleSearchPage()}
			r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

			w := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, tc.url, nil)
			r.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
			}
			tc.check(t, scraper.lastSearchOpts)
		})
	}
}

func TestSearchHandler_GetSearch_InvalidSort_Returns400(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/search?sort=BogusValue", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want 400; body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "invalid sort") {
		t.Fatalf("body=%q does not contain %q", w.Body.String(), "invalid sort")
	}
	if scraper.searchCalls != 0 {
		t.Fatalf("scraper calls=%d want 0 (validation must short-circuit before upstream)", scraper.searchCalls)
	}
}

func TestSearchHandler_GetSearch_InvalidOrder_Returns400(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/search?order=BogusValue", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want 400; body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "invalid order") {
		t.Fatalf("body=%q does not contain %q", w.Body.String(), "invalid order")
	}
	if scraper.searchCalls != 0 {
		t.Fatalf("scraper calls=%d want 0", scraper.searchCalls)
	}
}

func TestSearchHandler_GetSearch_InvalidPeriod_Returns400(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/search?period=BogusValue", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want 400; body=%s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "invalid period") {
		t.Fatalf("body=%q does not contain %q", w.Body.String(), "invalid period")
	}
	if scraper.searchCalls != 0 {
		t.Fatalf("scraper calls=%d want 0", scraper.searchCalls)
	}
}

// TestSearchHandler_GetSearch_InvalidPage_TreatedAsNil pins the Kotlin
// `toIntOrNull` parity: a non-numeric `?page=abc` MUST forward Page=nil
// to the scraper (NOT a 400 — only enum params 400 on bad input).
func TestSearchHandler_GetSearch_InvalidPage_TreatedAsNil(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/search?page=abc", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.searchCalls != 1 {
		t.Fatalf("scraper calls=%d want 1", scraper.searchCalls)
	}
	if scraper.lastSearchOpts.Page != nil {
		t.Fatalf("Page: got &%s, want nil (toIntOrNull semantics)",
			strconv.Itoa(*scraper.lastSearchOpts.Page))
	}
}

func TestSearchHandler_GetSearch_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{searchErr: errors.New("boom")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/search?query=foo", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("body not json: %v (%s)", err, w.Body.String())
	}
	if errMsg, _ := body["error"].(string); errMsg != "boom" {
		t.Fatalf("error=%q want %q", errMsg, "boom")
	}
}

// TestSearchHandler_GetSearch_Unauthorized_Returns401 pins the
// writeUpstreamError 401 arm for /search. Mirrors the forum handler's
// equivalent test (foundation requirement: every Phase 7 task adds a
// 401 test for at least one of its routes).
func TestSearchHandler_GetSearch_Unauthorized_Returns401(t *testing.T) {
	scraper := &fakeScraper{searchErr: rutracker.ErrUnauthorized}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/search?query=foo", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d want 401; body=%s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, "rutracker") {
		t.Fatalf("body=%q does not mention %q (sentinel text=%q)", body, "rutracker", rutracker.ErrUnauthorized.Error())
	}
}

// TestSearchHandler_GetSearch_AuthRealmHashAffectsCacheKey pins that the
// realm hash (set by auth.GinMiddleware from Auth-Token) flows into the
// cache key. Anonymous and authenticated callers must occupy different
// cache slots; same-token repeats must hit the cache.
func TestSearchHandler_GetSearch_AuthRealmHashAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	wAnon := httptest.NewRecorder()
	rAnon := httptest.NewRequest(http.MethodGet, "/search", nil)
	r.ServeHTTP(wAnon, rAnon)
	if wAnon.Code != http.StatusOK {
		t.Fatalf("anon: status=%d want 200", wAnon.Code)
	}
	if scraper.searchCalls != 1 {
		t.Fatalf("anon: scraper calls=%d want 1", scraper.searchCalls)
	}

	wAuth := httptest.NewRecorder()
	rAuth := httptest.NewRequest(http.MethodGet, "/search", nil)
	rAuth.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth, rAuth)
	if wAuth.Code != http.StatusOK {
		t.Fatalf("auth: status=%d want 200", wAuth.Code)
	}
	if scraper.searchCalls != 2 {
		t.Fatalf("auth: scraper calls=%d want 2 (anon and realm-keyed must use distinct cache slots)", scraper.searchCalls)
	}

	wAuth2 := httptest.NewRecorder()
	rAuth2 := httptest.NewRequest(http.MethodGet, "/search", nil)
	rAuth2.Header.Set(auth.HeaderName, "secret")
	r.ServeHTTP(wAuth2, rAuth2)
	if wAuth2.Code != http.StatusOK {
		t.Fatalf("auth-2: status=%d want 200", wAuth2.Code)
	}
	if scraper.searchCalls != 2 {
		t.Fatalf("auth-2: scraper calls=%d want 2 (realm-keyed entry should have served the second hit)", scraper.searchCalls)
	}

	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (anon entry + realm entry)", got)
	}
}

// TestSearchHandler_GetSearch_QueryAffectsCacheKey pins that the
// optional query parameters propagate into the cache-key normalisation.
// Distinct `?query=` values MUST occupy distinct cache slots.
func TestSearchHandler_GetSearch_QueryAffectsCacheKey(t *testing.T) {
	scraper := &fakeScraper{searchReturn: sampleSearchPage()}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	wFoo := httptest.NewRecorder()
	rFoo := httptest.NewRequest(http.MethodGet, "/search?query=foo", nil)
	r.ServeHTTP(wFoo, rFoo)
	if wFoo.Code != http.StatusOK {
		t.Fatalf("foo: status=%d want 200", wFoo.Code)
	}

	wBar := httptest.NewRecorder()
	rBar := httptest.NewRequest(http.MethodGet, "/search?query=bar", nil)
	r.ServeHTTP(wBar, rBar)
	if wBar.Code != http.StatusOK {
		t.Fatalf("bar: status=%d want 200", wBar.Code)
	}

	if scraper.searchCalls != 2 {
		t.Fatalf("scraper calls=%d want 2 (distinct query values must miss cache separately)", scraper.searchCalls)
	}
	if got := c.size(); got != 2 {
		t.Fatalf("cache size=%d want 2 (one per distinct query)", got)
	}
}

func TestRegister_RegistersSearchRoute(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(auth.GinMiddleware())
	Register(router, &Deps{Cache: newFakeCache(), Scraper: &fakeScraper{}})

	want := map[string]bool{
		"GET /search": false,
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

// Compile-time-ish reference so unused helpers are kept honest.
var (
	_ = intPtr
	_ = sortTypePtr
	_ = sortOrderPtr
	_ = periodPtr
)
