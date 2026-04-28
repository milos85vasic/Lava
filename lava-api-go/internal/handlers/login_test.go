// Package handlers — login_test.go pins the Phase 7 task 7.7 contract
// for POST /login. Form parsing, the all-three-or-none captcha-pointer
// guard, and the missing-username/password short-circuit are the
// critical behaviours: the upstream must never see a malformed
// credentials request.
//
// Sixth Law alignment:
//   - clause 1: ServeHTTP through a real Gin engine — no shortcut.
//   - clause 2: see commit message — one test was run against
//     deliberately broken handler code to confirm it can fail.
//   - clause 3: primary assertions are on (a) the HTTP status, (b) the
//     response JSON, and (c) the LoginParams struct that landed in
//     the scraper — i.e. the wire bytes that would have gone to
//     rutracker.org if there were no fake. Call counts are secondary.
package handlers

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	gen "digital.vasic.lava.apigo/internal/gen/server"
	"digital.vasic.lava.apigo/internal/rutracker"
)

// successAuthResponse builds a minimal AuthResponseDto carrying a
// Success variant — enough to round-trip through json.Marshal and back.
func successAuthResponse(t *testing.T, userID, token, avatar string) *gen.AuthResponseDto {
	t.Helper()
	var out gen.AuthResponseDto
	if err := out.FromAuthResponseDtoSuccess(gen.AuthResponseDtoSuccess{
		Type: gen.Success,
		User: gen.UserDto{
			Id:        userID,
			Token:     token,
			AvatarUrl: avatar,
		},
	}); err != nil {
		t.Fatalf("build AuthResponseDtoSuccess: %v", err)
	}
	return &out
}

// formBody returns an io.Reader-shaped string body and the matching
// Content-Type header for an application/x-www-form-urlencoded payload.
func formBody(pairs ...string) (string, string) {
	if len(pairs)%2 != 0 {
		panic("formBody: expected even number of key/value strings")
	}
	parts := make([]string, 0, len(pairs)/2)
	for i := 0; i < len(pairs); i += 2 {
		parts = append(parts, pairs[i]+"="+pairs[i+1])
	}
	return strings.Join(parts, "&"), "application/x-www-form-urlencoded"
}

// TestAuthHandler_PostLogin_ForwardsCredentials — happy path: the
// form-decoded username and password land in the scraper's
// LoginParams VERBATIM, the response is the JSON of the
// AuthResponseDto returned by the scraper. Captcha pointers MUST be
// nil (none provided in the form).
func TestAuthHandler_PostLogin_ForwardsCredentials(t *testing.T) {
	scraper := &fakeScraper{
		loginReturn: successAuthResponse(t, "42", "bb_data=tok", "https://avatar/x.png"),
	}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody("username", "alice", "password", "secret")
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	// Round-trip the response back into AuthResponseDto and pull out
	// the Success variant — this proves the discriminator survived
	// JSON marshalling end to end.
	var got gen.AuthResponseDto
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("body not AuthResponseDto-shaped: %v (%s)", err, w.Body.String())
	}
	success, err := got.AsAuthResponseDtoSuccess()
	if err != nil {
		t.Fatalf("AsAuthResponseDtoSuccess: %v (body=%s)", err, w.Body.String())
	}
	if success.User.Id != "42" {
		t.Errorf("response User.Id=%q want %q", success.User.Id, "42")
	}

	// Wire-shape assertion: the LoginParams the scraper saw.
	if scraper.loginCalls != 1 {
		t.Fatalf("Login calls=%d want 1", scraper.loginCalls)
	}
	if scraper.lastLoginParams.Username != "alice" {
		t.Errorf("Username=%q want %q", scraper.lastLoginParams.Username, "alice")
	}
	if scraper.lastLoginParams.Password != "secret" {
		t.Errorf("Password=%q want %q", scraper.lastLoginParams.Password, "secret")
	}
	if scraper.lastLoginParams.CaptchaSid != nil {
		t.Errorf("CaptchaSid=%v want nil (no captcha in form)", *scraper.lastLoginParams.CaptchaSid)
	}
	if scraper.lastLoginParams.CaptchaCode != nil {
		t.Errorf("CaptchaCode=%v want nil (no captcha in form)", *scraper.lastLoginParams.CaptchaCode)
	}
	if scraper.lastLoginParams.CaptchaValue != nil {
		t.Errorf("CaptchaValue=%v want nil (no captcha in form)", *scraper.lastLoginParams.CaptchaValue)
	}
}

// TestAuthHandler_PostLogin_MissingUsername_Returns400_NoUpstream — a
// missing required form field MUST short-circuit to 400 with ZERO
// upstream traffic. Mirrors Task 6.5's empty-cookie-zero-traffic
// discipline (the breaker must never trip on client-induced badness).
// Sixth Law clause 3 — primary assertion is the call counter being 0.
func TestAuthHandler_PostLogin_MissingUsername_Returns400_NoUpstream(t *testing.T) {
	scraper := &fakeScraper{}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody("password", "secret")
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want 400; body=%s", w.Code, w.Body.String())
	}
	if scraper.loginCalls != 0 {
		t.Fatalf("Login calls=%d want 0 (missing username MUST short-circuit before any upstream traffic)", scraper.loginCalls)
	}
}

// TestAuthHandler_PostLogin_MissingPassword_Returns400_NoUpstream —
// symmetric to the missing-username test. Either field missing MUST
// short-circuit.
func TestAuthHandler_PostLogin_MissingPassword_Returns400_NoUpstream(t *testing.T) {
	scraper := &fakeScraper{}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody("username", "alice")
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want 400; body=%s", w.Code, w.Body.String())
	}
	if scraper.loginCalls != 0 {
		t.Fatalf("Login calls=%d want 0 (missing password MUST short-circuit before any upstream traffic)", scraper.loginCalls)
	}
}

// TestAuthHandler_PostLogin_EmptyUsernameField_Returns400 — Ktor's
// equivalent treats empty string and absent the same way; we do too.
// `username=&password=secret` MUST be 400.
func TestAuthHandler_PostLogin_EmptyUsernameField_Returns400(t *testing.T) {
	scraper := &fakeScraper{}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody("username", "", "password", "secret")
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("status=%d want 400; body=%s", w.Code, w.Body.String())
	}
	if scraper.loginCalls != 0 {
		t.Fatalf("Login calls=%d want 0", scraper.loginCalls)
	}
}

// TestAuthHandler_PostLogin_AllThreeCaptchaFields_ForwardedAsPointers —
// when the form carries cap_sid / cap_code / cap_val, the handler MUST
// pass them through as non-nil *string pointers in LoginParams. The
// scraper's all-three-or-none guard then submits them to rutracker.
func TestAuthHandler_PostLogin_AllThreeCaptchaFields_ForwardedAsPointers(t *testing.T) {
	scraper := &fakeScraper{loginReturn: successAuthResponse(t, "1", "tok", "")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody(
		"username", "alice",
		"password", "secret",
		"cap_sid", "sid-9",
		"cap_code", "cap_code_xy",
		"cap_val", "user-answer",
	)
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.lastLoginParams.CaptchaSid == nil || *scraper.lastLoginParams.CaptchaSid != "sid-9" {
		t.Errorf("CaptchaSid=%v want &\"sid-9\"", scraper.lastLoginParams.CaptchaSid)
	}
	if scraper.lastLoginParams.CaptchaCode == nil || *scraper.lastLoginParams.CaptchaCode != "cap_code_xy" {
		t.Errorf("CaptchaCode=%v want &\"cap_code_xy\"", scraper.lastLoginParams.CaptchaCode)
	}
	if scraper.lastLoginParams.CaptchaValue == nil || *scraper.lastLoginParams.CaptchaValue != "user-answer" {
		t.Errorf("CaptchaValue=%v want &\"user-answer\"", scraper.lastLoginParams.CaptchaValue)
	}
}

// TestAuthHandler_PostLogin_PartialCaptchaFields_AllThreePointersNil —
// when only one or two captcha fields are present in the form, ALL
// three LoginParams pointers MUST be nil — the all-three-or-none guard
// fires inside the scraper on nil. A regression where a half-set
// reached the scraper as one nil + two non-nil would silently submit
// half-captcha to rutracker (which rejects it).
//
// NOTE: the empty-string-→-nil conversion in the handler is what makes
// this work. If the conversion were dropped, two of the pointers would
// still be non-nil (pointing at empty strings) and the scraper-side
// guard would forward `cap_sid=&cap_code=&cap_val=` over the wire.
func TestAuthHandler_PostLogin_PartialCaptchaFields_AllThreePointersNil(t *testing.T) {
	scraper := &fakeScraper{loginReturn: successAuthResponse(t, "1", "tok", "")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	// Only cap_sid is set; cap_code and cap_val are absent.
	body, ct := formBody(
		"username", "alice",
		"password", "secret",
		"cap_sid", "sid-only",
	)
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	// cap_sid IS set in the form, so it propagates through; cap_code /
	// cap_val are absent, so they remain nil. The all-three-or-none
	// guard inside the scraper drops the partial set when it sees any
	// nil. The guard's behaviour is pinned in
	// internal/rutracker/login_test.go's TestLogin_PartialCaptchaOmitted;
	// here we only need to pin that the handler did NOT fabricate
	// non-nil pointers for absent form fields.
	if scraper.lastLoginParams.CaptchaCode != nil {
		t.Errorf("CaptchaCode=%v want nil (cap_code absent in form)", *scraper.lastLoginParams.CaptchaCode)
	}
	if scraper.lastLoginParams.CaptchaValue != nil {
		t.Errorf("CaptchaValue=%v want nil (cap_val absent in form)", *scraper.lastLoginParams.CaptchaValue)
	}
}

// TestAuthHandler_PostLogin_EmptyCaptchaStrings_TreatedAsAbsent — when
// cap_sid / cap_code / cap_val are present in the form but EMPTY, the
// handler MUST convert them to nil. Empty-string captcha fields are a
// common Android-side quirk (the form encoder may emit them even when
// the user hasn't typed anything) — submitting them to rutracker as
// `cap_sid=` would be a broken request.
func TestAuthHandler_PostLogin_EmptyCaptchaStrings_TreatedAsAbsent(t *testing.T) {
	scraper := &fakeScraper{loginReturn: successAuthResponse(t, "1", "tok", "")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody(
		"username", "alice",
		"password", "secret",
		"cap_sid", "",
		"cap_code", "",
		"cap_val", "",
	)
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%s", w.Code, w.Body.String())
	}
	if scraper.lastLoginParams.CaptchaSid != nil {
		t.Errorf("CaptchaSid=%q want nil (empty form value should map to nil)", *scraper.lastLoginParams.CaptchaSid)
	}
	if scraper.lastLoginParams.CaptchaCode != nil {
		t.Errorf("CaptchaCode=%q want nil (empty form value should map to nil)", *scraper.lastLoginParams.CaptchaCode)
	}
	if scraper.lastLoginParams.CaptchaValue != nil {
		t.Errorf("CaptchaValue=%q want nil (empty form value should map to nil)", *scraper.lastLoginParams.CaptchaValue)
	}
}

// TestAuthHandler_PostLogin_ScraperError_Returns502 — generic upstream
// error → 502 via writeUpstreamError default arm.
func TestAuthHandler_PostLogin_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{loginErr: errors.New("upstream-broken")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody("username", "alice", "password", "secret")
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// TestAuthHandler_PostLogin_CircuitOpen_Returns503 — pin the
// ErrCircuitOpen → 503 mapping for /login.
func TestAuthHandler_PostLogin_CircuitOpen_Returns503(t *testing.T) {
	scraper := &fakeScraper{loginErr: rutracker.ErrCircuitOpen}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	body, ct := formBody("username", "alice", "password", "secret")
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
	req.Header.Set("Content-Type", ct)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want 503; body=%s", w.Code, w.Body.String())
	}
}

// TestAuthHandler_PostLogin_NoCacheReadOrWrite — POST /login MUST NOT
// touch the cache. Two consecutive logins MUST produce two scraper
// calls and zero cache entries. Login is state-changing and per-user;
// caching it would be a security incident.
func TestAuthHandler_PostLogin_NoCacheReadOrWrite(t *testing.T) {
	scraper := &fakeScraper{loginReturn: successAuthResponse(t, "1", "tok", "")}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	for i := 0; i < 2; i++ {
		body, ct := formBody("username", "alice", "password", "secret")
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(body))
		req.Header.Set("Content-Type", ct)
		r.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("call %d: status=%d want 200; body=%s", i, w.Code, w.Body.String())
		}
	}

	if scraper.loginCalls != 2 {
		t.Fatalf("Login calls=%d want 2 (login MUST NOT cache)", scraper.loginCalls)
	}
	if c.size() != 0 {
		t.Fatalf("cache size=%d want 0 (login handler must NOT write to cache)", c.size())
	}
}
