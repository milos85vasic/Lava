package rutracker

import (
	"bytes"
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestDecodeCaptchaPath_RoundTrip — encode a real-shaped captcha URL
// with RawURLEncoding (Kotlin's Base64.UrlSafe default) and verify the
// decoded bytes match. Sixth Law clause 3 — primary assertion is on the
// decoded URL bytes (the value the captcha proxy will GET).
func TestDecodeCaptchaPath_RoundTrip(t *testing.T) {
	const url = "https://static.t-ru.org/captcha/abcd-1234.png?ts=999"
	encoded := base64.RawURLEncoding.EncodeToString([]byte(url))
	got, ok := DecodeCaptchaPath(encoded)
	if !ok {
		t.Fatalf("DecodeCaptchaPath ok=false for valid raw input %q", encoded)
	}
	if got != url {
		t.Errorf("DecodeCaptchaPath=%q want %q", got, url)
	}
}

// TestDecodeCaptchaPath_HandlesPadded — Base64.UrlSafe also accepts
// padded inputs. Verify the padded form decodes too.
func TestDecodeCaptchaPath_HandlesPadded(t *testing.T) {
	const url = "https://static.t-ru.org/captcha/img.png"
	encoded := base64.URLEncoding.EncodeToString([]byte(url))
	got, ok := DecodeCaptchaPath(encoded)
	if !ok {
		t.Fatalf("DecodeCaptchaPath ok=false for valid padded input %q", encoded)
	}
	if got != url {
		t.Errorf("DecodeCaptchaPath=%q want %q", got, url)
	}
}

// TestDecodeCaptchaPath_InvalidReturnsFalse — non-base64 input returns
// ("", false). The "*" character is invalid in both URL alphabets.
func TestDecodeCaptchaPath_InvalidReturnsFalse(t *testing.T) {
	got, ok := DecodeCaptchaPath("not-base64-***")
	if ok {
		t.Errorf("DecodeCaptchaPath ok=true want false for invalid input (got %q)", got)
	}
	if got != "" {
		t.Errorf("DecodeCaptchaPath=%q want \"\" on invalid input", got)
	}
}

// TestFetchCaptcha_HappyPath — spin up an httptest server that serves
// JPEG bytes with Content-Type=image/jpeg, encode its URL, and verify
// FetchCaptcha returns the bytes verbatim AND the upstream Content-Type.
//
// Sixth Law clause 3 — primary assertions on the actual bytes the API
// would proxy back to the client AND on the Content-Type header.
func TestFetchCaptcha_HappyPath(t *testing.T) {
	imageBytes := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0xDE, 0xAD, 0xBE, 0xEF}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/captcha/abc.jpg" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write(imageBytes)
	}))
	defer srv.Close()

	captchaURL := srv.URL + "/captcha/abc.jpg"
	encoded := base64.RawURLEncoding.EncodeToString([]byte(captchaURL))

	// Note the Client base is irrelevant — FetchCaptcha goes through
	// GetURL which uses the absolute URL, NOT c.base+path.
	c := NewClient("https://this-base-is-not-used.example")
	out, err := c.FetchCaptcha(context.Background(), encoded)
	if err != nil {
		t.Fatalf("FetchCaptcha: %v", err)
	}
	if out == nil {
		t.Fatal("FetchCaptcha: nil out, want non-nil")
	}
	if out.ContentType != "image/jpeg" {
		t.Errorf("ContentType=%q want image/jpeg", out.ContentType)
	}
	if !bytes.Equal(out.Bytes, imageBytes) {
		t.Errorf("Bytes mismatch: got %v want %v", out.Bytes, imageBytes)
	}
}

// TestFetchCaptcha_BadPath_Error — invalid base64 in the path → non-nil
// error, no upstream traffic.
func TestFetchCaptcha_BadPath_Error(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
	}))
	defer srv.Close()

	c := NewClient(srv.URL)
	out, err := c.FetchCaptcha(context.Background(), "***bad***")
	if err == nil {
		t.Error("FetchCaptcha err=nil want non-nil for invalid base64")
	}
	if out != nil {
		t.Errorf("FetchCaptcha out=%+v want nil on error", out)
	}
	if hits != 0 {
		t.Errorf("server hits=%d want 0 (must short-circuit on bad path)", hits)
	}
}

// TestFetchCaptcha_404_Error — 4xx upstream → non-nil error so the
// handler can map to an upstream-error response (and not silently
// proxy a 404 page as a fake captcha image).
func TestFetchCaptcha_404_Error(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.NotFound(w, r)
	}))
	defer srv.Close()

	encoded := base64.RawURLEncoding.EncodeToString([]byte(srv.URL + "/missing.png"))
	c := NewClient("https://unused.example")
	out, err := c.FetchCaptcha(context.Background(), encoded)
	if err == nil {
		t.Error("FetchCaptcha err=nil want non-nil for 404 upstream")
	}
	if out != nil {
		t.Errorf("FetchCaptcha out=%+v want nil on error", out)
	}
}

// TestGetURL_Forwards5xxAsBreakerError — pin breaker semantics on the
// new GetURL primitive. A 500 response MUST surface as a non-nil error
// so the breaker can count it as a failure (mirrors PostForm/Fetch).
func TestGetURL_Forwards5xxAsBreakerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := NewClient("https://unused.example")
	_, _, _, err := c.GetURL(context.Background(), srv.URL+"/x", "")
	if err == nil {
		t.Error("GetURL err=nil for 500 response, want non-nil")
	}
}
