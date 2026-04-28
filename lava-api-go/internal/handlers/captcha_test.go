// Package handlers — captcha_test.go pins the Phase 7 task 7.7
// contract for GET /captcha/{path}. The critical behaviours are
// (a) verbatim path forwarding to the scraper (NO Gin-side URL
// decode), (b) bytes streamed verbatim with the upstream Content-Type,
// (c) image/jpeg default when the upstream omits Content-Type, and
// (d) NO cache.
//
// Sixth Law alignment:
//   - clause 1: ServeHTTP through a real Gin engine — no shortcut.
//   - clause 2: see commit message — one test was run against
//     deliberately broken handler code to confirm it can fail.
//   - clause 3: primary assertions are on the response body bytes
//     (the actual image payload an API client would render) and the
//     Content-Type header.
package handlers

import (
	"bytes"
	"encoding/base64"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"digital.vasic.lava.apigo/internal/rutracker"
)

// TestCaptchaHandler_GetCaptcha_HappyPath_StreamsBytesWithUpstreamContentType
// — the scraper returns a CaptchaImage; the handler MUST write its
// bytes verbatim and forward its Content-Type. Primary assertion is
// on the response body bytes.
func TestCaptchaHandler_GetCaptcha_HappyPath_StreamsBytesWithUpstreamContentType(t *testing.T) {
	imageBytes := []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}
	scraper := &fakeScraper{captchaReturn: &rutracker.CaptchaImage{
		ContentType: "image/png",
		Bytes:       imageBytes,
	}}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	encoded := base64.RawURLEncoding.EncodeToString([]byte("https://static.t-ru.org/captcha/x.png"))
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/captcha/"+encoded, nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%q", w.Code, w.Body.String())
	}
	if !bytes.Equal(w.Body.Bytes(), imageBytes) {
		t.Errorf("body bytes mismatch: got %v want %v", w.Body.Bytes(), imageBytes)
	}
	if ct := w.Header().Get("Content-Type"); ct != "image/png" {
		t.Errorf("Content-Type=%q want image/png", ct)
	}
	if scraper.captchaCalls != 1 {
		t.Fatalf("FetchCaptcha calls=%d want 1", scraper.captchaCalls)
	}
}

// TestCaptchaHandler_GetCaptcha_PathParam_ForwardedVerbatim — the
// `path` parameter MUST land in the scraper EXACTLY as it appeared
// after Gin's path-parameter parsing (the scraper owns the Base64
// decode step). A regression where the handler URL-decoded the param
// would corrupt the Base64 body and the scraper-side decode would
// silently fail. This test pins the seam.
func TestCaptchaHandler_GetCaptcha_PathParam_ForwardedVerbatim(t *testing.T) {
	imageBytes := []byte("not-really-a-captcha")
	scraper := &fakeScraper{captchaReturn: &rutracker.CaptchaImage{
		ContentType: "image/jpeg",
		Bytes:       imageBytes,
	}}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	// A representative URL-safe Base64 path with the characters - and
	// _ that distinguish it from the standard alphabet, plus a trailing
	// segment shaped like a real captcha URL.
	const encoded = "aHR0cHM6Ly9zdGF0aWMudC1ydS5vcmcvY2FwdGNoYS9hYmMtZGVmXzEyMy5wbmc"

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/captcha/"+encoded, nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%q", w.Code, w.Body.String())
	}
	if scraper.lastCaptchaPath != encoded {
		t.Errorf("scraper saw path=%q want %q (handler must forward verbatim, no URL-decoding)", scraper.lastCaptchaPath, encoded)
	}
}

// TestCaptchaHandler_GetCaptcha_DefaultsContentTypeToJPEG — if the
// upstream omits Content-Type, the handler MUST default to image/jpeg.
// A missing Content-Type would violate the OpenAPI's image/* contract.
func TestCaptchaHandler_GetCaptcha_DefaultsContentTypeToJPEG(t *testing.T) {
	scraper := &fakeScraper{captchaReturn: &rutracker.CaptchaImage{
		ContentType: "", // upstream omitted Content-Type
		Bytes:       []byte{0xFF, 0xD8, 0xFF, 0xE0},
	}}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	encoded := base64.RawURLEncoding.EncodeToString([]byte("https://static/x"))
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/captcha/"+encoded, nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200; body=%q", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); ct != "image/jpeg" {
		t.Errorf("Content-Type=%q want image/jpeg (default when upstream omits it)", ct)
	}
}

// TestCaptchaHandler_GetCaptcha_ScraperError_Returns502 — the OpenAPI
// permits 4xx for invalid base64 path; rutracker's FetchCaptcha
// returns generic errors (NOT typed sentinels) on bad path / 4xx
// upstream / 5xx upstream alike. writeUpstreamError's default arm
// maps these to 502 Bad Gateway. The Android client treats 502 from
// /captcha as a non-fatal "captcha not available, try again" signal.
func TestCaptchaHandler_GetCaptcha_ScraperError_Returns502(t *testing.T) {
	scraper := &fakeScraper{captchaErr: errors.New("rutracker: invalid captcha path")}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/captcha/anything", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadGateway {
		t.Fatalf("status=%d want 502; body=%s", w.Code, w.Body.String())
	}
}

// TestCaptchaHandler_GetCaptcha_CircuitOpen_Returns503 — pin the
// ErrCircuitOpen → 503 mapping for /captcha. A tripped breaker on the
// captcha-image upstream MUST surface as 503, not 502.
func TestCaptchaHandler_GetCaptcha_CircuitOpen_Returns503(t *testing.T) {
	scraper := &fakeScraper{captchaErr: rutracker.ErrCircuitOpen}
	r := newTestRouter(&Deps{Cache: newFakeCache(), Scraper: scraper})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/captcha/anything", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want 503; body=%s", w.Code, w.Body.String())
	}
}

// TestCaptchaHandler_GetCaptcha_NoCache_TwoCallsHitScraperTwice —
// /captcha/{path} MUST NOT cache. Two consecutive GETs MUST produce
// two scraper calls. Spec §6: captcha images are session-bound;
// caching them would cause replay/ambiguity (the cap_sid the user
// solves against would no longer match the cap_sid in the form).
func TestCaptchaHandler_GetCaptcha_NoCache_TwoCallsHitScraperTwice(t *testing.T) {
	scraper := &fakeScraper{captchaReturn: &rutracker.CaptchaImage{
		ContentType: "image/png",
		Bytes:       []byte("img"),
	}}
	c := newFakeCache()
	r := newTestRouter(&Deps{Cache: c, Scraper: scraper})

	encoded := base64.RawURLEncoding.EncodeToString([]byte("https://static/x"))
	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/captcha/"+encoded, nil)
		r.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("call %d: status=%d want 200; body=%s", i, w.Code, w.Body.String())
		}
	}

	if scraper.captchaCalls != 2 {
		t.Fatalf("FetchCaptcha calls=%d want 2 (captcha MUST NOT cache; replay/ambiguity risk)", scraper.captchaCalls)
	}
	if c.size() != 0 {
		t.Fatalf("cache size=%d want 0 (handler must NOT write to cache)", c.size())
	}
}
