// Package v1 — error_mapping_test.go is a §6.L 68th handler-level
// real-HTTP test for the writeProviderError status mapping, exercised
// END-TO-END through the real Gin engine via httptest (ServeHTTP), NOT a
// direct call into writeProviderError.
//
// The existing handlers_test.go covers ONLY the ErrNotFound→404 arm of
// the search handler's error path. The other four arms (ErrForbidden→403,
// ErrUnauthorized→401, ErrCircuitOpen→503, default→502) are unexercised.
// Those status codes are what the Android client maps to user-visible UI
// states ("sign in", "rutracker unreachable", "try again") — a wrong
// mapping silently shows the wrong message to the user while every
// happy-path test stays green. This is the §6.AB "assert the user-visible
// status, not just absence of crash" + §6.G "every advertised endpoint
// completes its error flow" gap.
//
// PRIMARY assertion is on the HTTP status code AND the empty-`{}` wire
// body (the bytes the client parses) returned by a real request through
// the real router.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.J clause 2):
//
//	Mutation: in handlers.go (v1) writeProviderError, change the
//	  ErrCircuitOpen case to `writeJSON(c, http.StatusBadGateway, ...)`.
//	Observed: TestSearch_ProviderErrorMapping/circuit_open_503 FAILS:
//	  "status=502 want 503 for ErrCircuitOpen".
//	Reverted: yes (production code restored; final commit unmutated).
package v1

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// TestSearch_ProviderErrorMapping drives a real GET /v1/test/search
// request through the real router for each provider sentinel and asserts
// the HTTP status the client receives. The fake provider returns the
// chosen error from Search(); the request traverses the same handler
// path a real client request triggers.
func TestSearch_ProviderErrorMapping(t *testing.T) {
	cases := []struct {
		name       string
		searchErr  error
		wantStatus int
	}{
		{"not_found_404", provider.ErrNotFound, http.StatusNotFound},
		{"forbidden_403", provider.ErrForbidden, http.StatusForbidden},
		{"unauthorized_401", provider.ErrUnauthorized, http.StatusUnauthorized},
		{"circuit_open_503", provider.ErrCircuitOpen, http.StatusServiceUnavailable},
		{"wrapped_circuit_open_503", fmt.Errorf("upstream tripped: %w", provider.ErrCircuitOpen), http.StatusServiceUnavailable},
		{"unknown_error_502", errors.New("totally unexpected upstream garbage"), http.StatusBadGateway},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fp := &fakeProvider{id: "test", searchErr: tc.searchErr}
			router := setupTestRouter(fp)

			w := httptest.NewRecorder()
			req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=x", nil)
			router.ServeHTTP(w, req)

			if w.Code != tc.wantStatus {
				t.Fatalf("status=%d want %d for %v; body=%s", w.Code, tc.wantStatus, tc.searchErr, w.Body.String())
			}
			// Wire-shape parity (§6.AB): error bodies are the empty `{}`
			// object the Ktor proxy emits — NOT the raw err string (which
			// could leak internal detail). The client parses this shape.
			if body := w.Body.String(); body != "{}" {
				t.Fatalf("error body=%q want \"{}\" (parity with Ktor StatusPages empty-object shape; leaking err text is a regression)", body)
			}
			// Error responses MUST carry the parity Content-Type, not
			// gin's charset-suffixed default.
			if ct := w.Header().Get("Content-Type"); ct != "application/json" {
				t.Fatalf("Content-Type=%q want application/json (no charset suffix, for cross-backend parity)", ct)
			}
		})
	}
}

// TestSearch_Success_BodyContentAndStatus is the positive counterpart:
// a successful search returns 200 AND the marshalled result body the
// client renders. Asserts on the actual result fields (Page,
// TotalPages, the item title) — the user-visible search-results content,
// not merely status 200. §6.AB rendering-correctness-not-just-presence.
func TestSearch_Success_BodyContentAndStatus(t *testing.T) {
	fp := &fakeProvider{
		id: "test",
		searchResult: &provider.SearchResult{
			Provider:   "test",
			Page:       2,
			TotalPages: 7,
			Results: []provider.SearchItem{
				{ID: "42", Title: "The Matrix 1999"},
				{ID: "43", Title: "The Matrix Reloaded"},
			},
		},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/test/search?query=matrix&page=2", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	var got provider.SearchResult
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("response body not a SearchResult: %v (%s)", err, w.Body.String())
	}
	if got.Page != 2 || got.TotalPages != 7 {
		t.Fatalf("paging mismatch: Page=%d TotalPages=%d want 2/7 — the client's pager would render wrong page state", got.Page, got.TotalPages)
	}
	if len(got.Results) != 2 {
		t.Fatalf("result count=%d want 2 — search would show the wrong number of hits to the user", len(got.Results))
	}
	if got.Results[0].Title != "The Matrix 1999" {
		t.Fatalf("first result title=%q want %q — the user would see the wrong torrent title", got.Results[0].Title, "The Matrix 1999")
	}
}
