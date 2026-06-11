package v1

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/provider"
)

// captchaFakeProvider is a Provider whose FetchCaptcha returns a caller-supplied
// CaptchaImage, so the v1 captcha handler can be driven end-to-end through the
// real Gin route without reaching a live upstream.
type captchaFakeProvider struct {
	provider.BaseProvider
	fakeProvider
	img *provider.CaptchaImage
	err error
}

func (f *captchaFakeProvider) Capabilities() []provider.ProviderCapability {
	// CapSearch gates the v1 captcha route (see handlers.go chain()).
	return []provider.ProviderCapability{provider.CapSearch}
}

func (f *captchaFakeProvider) FetchCaptcha(ctx context.Context, path string) (*provider.CaptchaImage, error) {
	return f.img, f.err
}

// TestCaptcha_PropagatesUpstreamContentType drives GET /v1/{provider}/captcha/{path}
// through the real handler and asserts the response Content-Type is the UPSTREAM
// one the provider reported, NOT a hardcoded "image/png".
//
// LVA-026: rutracker serves captcha images from auxiliary domains under various
// MIME types (commonly image/jpeg, sometimes image/gif). The handler hardcoded
// Content-Type: image/png unconditionally, so a JPEG/GIF captcha was labelled
// PNG. Strict image decoders on the client reject a PNG-labelled JPEG → blank
// captcha → the user can never solve it → login is impossible. The upstream
// Content-Type the rutracker client already captures (CaptchaImage.ContentType)
// MUST be propagated.
//
// Sixth Law clause 3: primary assertion is on the response Content-Type header
// the client actually receives, plus the body bytes — both user-visible.
//
// Falsifiability: revert captcha.go to `c.Data(http.StatusOK, "image/png", ...)`
// → this fails: "Content-Type=image/png want image/jpeg (upstream type lost)".
func TestCaptcha_PropagatesUpstreamContentType(t *testing.T) {
	const upstreamCT = "image/jpeg"
	body := []byte{0xFF, 0xD8, 0xFF, 0xE0} // JPEG SOI magic — not a PNG
	fp := &captchaFakeProvider{
		fakeProvider: fakeProvider{id: "rutracker"},
		img:          &provider.CaptchaImage{Path: "p", ContentType: upstreamCT, Data: body},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rutracker/captcha/abc123", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200: %s", w.Code, w.Body.String())
	}
	if got := w.Header().Get("Content-Type"); got != upstreamCT {
		t.Errorf("Content-Type=%q want %q (upstream type was discarded — LVA-026)", got, upstreamCT)
	}
	if got := w.Body.Bytes(); len(got) != len(body) || got[0] != 0xFF {
		t.Errorf("body=%v want the verbatim upstream bytes %v", got, body)
	}
}

// TestCaptcha_FallsBackToImageWhenUpstreamMissingContentType asserts the safe
// fallback: when the upstream omitted a Content-Type, the handler serves a
// generic image/* type rather than an empty Content-Type (which some clients
// treat as application/octet-stream and refuse to render).
//
// Falsifiability: drop the fallback so an empty upstream type is forwarded
// verbatim → this fails: "Content-Type=\"\" want image/jpeg fallback".
func TestCaptcha_FallsBackToImageWhenUpstreamMissingContentType(t *testing.T) {
	fp := &captchaFakeProvider{
		fakeProvider: fakeProvider{id: "rutracker"},
		img:          &provider.CaptchaImage{Path: "p", ContentType: "", Data: []byte{0x01, 0x02}},
	}
	router := setupTestRouter(fp)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/v1/rutracker/captcha/abc123", nil)
	router.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status=%d want 200: %s", w.Code, w.Body.String())
	}
	ct := w.Header().Get("Content-Type")
	if ct == "" || ct[:6] != "image/" {
		t.Errorf("Content-Type=%q want an image/* fallback when upstream omits one", ct)
	}
}

// guard against an unused-import / interface-satisfaction regression.
var _ provider.Provider = (*captchaFakeProvider)(nil)

var _ = gin.Mode
