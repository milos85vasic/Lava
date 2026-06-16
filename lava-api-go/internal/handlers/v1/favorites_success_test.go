// Package v1 — favorites_success_test.go is a §6.L handler-level real-HTTP
// test covering the SUCCESS arm of the FavoritesHandler.AddFavorite and
// FavoritesHandler.RemoveFavorite handlers, exercised END-TO-END through the
// real Gin engine via httptest (ServeHTTP), NOT a direct call into the
// handler method.
//
// Coverage gap closed: before this test, `go test -coverprofile` reported
//
//	internal/handlers/v1/favorites.go:52: AddFavorite    75.0%
//	internal/handlers/v1/favorites.go:65: RemoveFavorite 75.0%
//
// The shared fakeProvider in handlers_test.go hardcodes AddFavorite /
// RemoveFavorite to (false, provider.ErrUnsupported), so NO existing test
// ever drove these handlers to their success arm:
//
//	writeJSON(c, http.StatusOK, gin.H{"success": ok})
//
// That arm — the 200 + `{"success":true}` body the Android client parses to
// confirm the bookmark toggle landed — was entirely unexercised. A regression
// that returned the wrong success flag, the wrong status, or the wrong JSON
// shape would have shipped green. This is the §6.AB "assert the user-visible
// status AND body, not just absence of crash" + §6.G "every advertised
// endpoint completes its flow" gap.
//
// PRIMARY assertion is on the HTTP status code AND the `{"success":<bool>}`
// wire body (the bytes the client parses) returned by a real request through
// the real router. The success flag is propagated from the provider's return
// value, so the test also proves the handler forwards `ok` faithfully (true
// AND false) rather than hardcoding it.
//
// FALSIFIABILITY REHEARSAL (Sixth Law clause 2, §6.J clause 2):
//
//	Mutation: in favorites.go AddFavorite, change the success line to
//	  `writeJSON(c, http.StatusOK, gin.H{"success": true})` (hardcode true,
//	  ignoring the provider's returned `ok`).
//	Observed: TestFavorites_AddRemove_Success/add_returns_false FAILS:
//	  "add success body=... success=true want false ...".
//	Reverted: yes (production code restored; final commit unmutated).
package v1

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/provider"
)

// favProvider embeds the shared *fakeProvider and overrides ONLY the two
// favorite-mutation methods so the handler reaches its success arm with a
// configurable result. Everything else (ID, capabilities, search, etc.)
// inherits the shared fake's behavior unchanged.
type favProvider struct {
	*fakeProvider
	addOK     bool
	addErr    error
	removeOK  bool
	removeErr error
}

func (f *favProvider) AddFavorite(_ context.Context, _ string, _ provider.Credentials) (bool, error) {
	return f.addOK, f.addErr
}

func (f *favProvider) RemoveFavorite(_ context.Context, _ string, _ provider.Credentials) (bool, error) {
	return f.removeOK, f.removeErr
}

// TestFavorites_AddRemove_Success drives real POST requests through the real
// router and asserts the 200 status + the marshalled `{"success":<bool>}`
// body the client renders. Each case covers a distinct success-flag value to
// prove the handler forwards the provider's `ok` faithfully.
func TestFavorites_AddRemove_Success(t *testing.T) {
	cases := []struct {
		name        string
		path        string
		ok          bool
		wantSuccess bool
	}{
		{"add_returns_true", "/v1/test/favorites/add/777", true, true},
		{"add_returns_false", "/v1/test/favorites/add/777", false, false},
		{"remove_returns_true", "/v1/test/favorites/remove/777", true, true},
		{"remove_returns_false", "/v1/test/favorites/remove/777", false, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			fp := &favProvider{
				fakeProvider: &fakeProvider{id: "test"},
				addOK:        tc.ok,
				removeOK:     tc.ok,
			}
			router := setupTestRouter(fp)

			w := httptest.NewRecorder()
			req, _ := http.NewRequest(http.MethodPost, tc.path, nil)
			router.ServeHTTP(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
			}
			// The body the client parses to confirm the bookmark toggle.
			var got struct {
				Success bool `json:"success"`
			}
			if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
				t.Fatalf("response body not {\"success\":bool}: %v (%s)", err, w.Body.String())
			}
			if got.Success != tc.wantSuccess {
				t.Fatalf("success body=%s success=%t want %t — the client would show the wrong bookmark state to the user", w.Body.String(), got.Success, tc.wantSuccess)
			}
			if ct := w.Header().Get("Content-Type"); ct != "application/json" {
				t.Fatalf("Content-Type=%q want application/json (no charset suffix, for cross-backend parity)", ct)
			}
		})
	}
}
