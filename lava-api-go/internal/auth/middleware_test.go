package auth_test

import (
	"encoding/base64"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.ratelimiter/pkg/ladder"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/config"
)

// uuidBlobBase64 returns the base64 encoding of the raw 16 bytes of
// a 32-char hex (no-dash) UUID — i.e., what the production Android
// client puts in the Lava-Auth header.
func uuidBlobBase64(t *testing.T, hexNoDash string) string {
	t.Helper()
	if len(hexNoDash) != 32 {
		t.Fatalf("test bug: hex length %d, want 32", len(hexNoDash))
	}
	b, err := hex.DecodeString(hexNoDash)
	if err != nil {
		t.Fatalf("hex decode: %v", err)
	}
	return base64.StdEncoding.EncodeToString(b)
}

// hashHexUUID computes the same HMAC-SHA256 the parser computes for an
// allowlist UUID — used to seed AuthActiveClients/AuthRetiredClients
// directly in tests without round-tripping through parseClientsList.
func hashHexUUID(secret []byte, hexNoDash string) string {
	b, _ := hex.DecodeString(hexNoDash)
	return auth.TestOnlyHashUUID(secret, b)
}

func TestAuthMiddleware_ActiveUuid_Returns200(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := []byte("test-secret-1234567890ABCDEF1234")
	activeUUID := "00000000000000000000000000000001"
	cfg := &config.Config{
		AuthFieldName:     "Lava-Auth",
		AuthHMACSecret:    secret,
		AuthActiveClients: map[string]string{hashHexUUID(secret, activeUUID): "android-test"},
	}
	l := ladder.New([]time.Duration{1 * time.Second})
	mw := auth.NewMiddleware(cfg, l)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeUUID))
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%q", w.Code, w.Body.String())
	}
}

func TestAuthMiddleware_RetiredUuid_Returns426WithMinVersion(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := []byte("test-secret-1234567890ABCDEF1234")
	retiredUUID := "00000000000000000000000000000002"
	cfg := &config.Config{
		AuthFieldName:               "Lava-Auth",
		AuthHMACSecret:              secret,
		AuthActiveClients:           map[string]string{},
		AuthRetiredClients:          map[string]string{hashHexUUID(secret, retiredUUID): "android-old"},
		AuthMinSupportedVersionName: "1.2.6",
		AuthMinSupportedVersionCode: 1026,
	}
	l := ladder.New([]time.Duration{1 * time.Second})
	mw := auth.NewMiddleware(cfg, l)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, retiredUUID))
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUpgradeRequired {
		t.Fatalf("status = %d, want 426; body=%q", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, `"min_supported_version_name":"1.2.6"`) {
		t.Fatalf("body missing min_supported_version_name: %s", body)
	}
	if !strings.Contains(body, `"min_supported_version_code":1026`) {
		t.Fatalf("body missing min_supported_version_code: %s", body)
	}
	if !strings.Contains(body, `"client_name":"android-old"`) {
		t.Fatalf("body missing client_name: %s", body)
	}
}

func TestAuthMiddleware_UnknownUuid_Returns401_AndAdvancesBackoff(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := []byte("test-secret-1234567890ABCDEF1234")
	cfg := &config.Config{
		AuthFieldName:     "Lava-Auth",
		AuthHMACSecret:    secret,
		AuthActiveClients: map[string]string{},
	}
	l := ladder.New([]time.Duration{1 * time.Second, 5 * time.Second})
	mw := auth.NewMiddleware(cfg, l)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, "ffffffffffffffffffffffffffffffff"))
	req.RemoteAddr = "192.0.2.1:1234"
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}

	// After the 401, the ladder should have advanced one step for 192.0.2.1.
	blocked, _ := l.CheckBlocked("192.0.2.1", time.Now())
	if !blocked {
		t.Fatal("expected ladder to have blocked 192.0.2.1 after 401")
	}
}

func TestAuthMiddleware_MissingHeader_Returns401(t *testing.T) {
	gin.SetMode(gin.TestMode)
	cfg := &config.Config{
		AuthFieldName:     "Lava-Auth",
		AuthHMACSecret:    []byte("test-secret-1234567890ABCDEF1234"),
		AuthActiveClients: map[string]string{},
	}
	l := ladder.New([]time.Duration{1 * time.Second})
	mw := auth.NewMiddleware(cfg, l)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}
}

func TestAuthMiddleware_MalformedBase64_Returns401(t *testing.T) {
	gin.SetMode(gin.TestMode)
	cfg := &config.Config{
		AuthFieldName:     "Lava-Auth",
		AuthHMACSecret:    []byte("test-secret-1234567890ABCDEF1234"),
		AuthActiveClients: map[string]string{},
	}
	l := ladder.New([]time.Duration{1 * time.Second})
	mw := auth.NewMiddleware(cfg, l)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Lava-Auth", "!!!not-base64!!!")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}
}

func TestAuthMiddleware_EmptyBlob_Returns401(t *testing.T) {
	// "" decodes to empty bytes; should be rejected
	gin.SetMode(gin.TestMode)
	cfg := &config.Config{
		AuthFieldName:     "Lava-Auth",
		AuthHMACSecret:    []byte("test-secret-1234567890ABCDEF1234"),
		AuthActiveClients: map[string]string{},
	}
	l := ladder.New([]time.Duration{1 * time.Second})
	mw := auth.NewMiddleware(cfg, l)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Lava-Auth", "")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", w.Code)
	}
}

func TestAuthMiddleware_ActiveUuid_ResetsBackoffCounter(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := []byte("test-secret-1234567890ABCDEF1234")
	activeUUID := "00000000000000000000000000000003"
	cfg := &config.Config{
		AuthFieldName:     "Lava-Auth",
		AuthHMACSecret:    secret,
		AuthActiveClients: map[string]string{hashHexUUID(secret, activeUUID): "android-test"},
	}
	l := ladder.New([]time.Duration{30 * time.Second})
	// Pre-load: one prior failure for 192.0.2.5 happening NOW so the
	// 30s block window is genuinely active. Using time.Unix(1000, 0)
	// (year 1970) would let the block window elapse before the
	// CheckBlocked call below — which would mask a regression where
	// Reset() is omitted, because CheckBlocked would return false
	// regardless of whether Reset cleared the entry.
	prior := time.Now()
	l.RecordFailure("192.0.2.5", prior)
	// Sanity-check the precondition: without Reset, the IP is blocked.
	if blocked, _ := l.CheckBlocked("192.0.2.5", prior); !blocked {
		t.Fatal("test precondition failed: ladder did not block after RecordFailure")
	}

	mw := auth.NewMiddleware(cfg, l)
	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.RemoteAddr = "192.0.2.5:1234"
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeUUID))
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	// After successful auth, ladder must have RESET the counter — so
	// CheckBlocked at the SAME instant the precondition was set MUST
	// now return false. If Reset was skipped, CheckBlocked still sees
	// the active block and the assertion fires.
	blocked, _ := l.CheckBlocked("192.0.2.5", prior)
	if blocked {
		t.Fatal("ladder did NOT reset for 192.0.2.5 after successful auth")
	}
}

func TestAuthMiddleware_RetiredUuid_DoesNotAdvanceBackoff(t *testing.T) {
	gin.SetMode(gin.TestMode)
	secret := []byte("test-secret-1234567890ABCDEF1234")
	retiredUUID := "00000000000000000000000000000004"
	cfg := &config.Config{
		AuthFieldName:               "Lava-Auth",
		AuthHMACSecret:              secret,
		AuthActiveClients:           map[string]string{},
		AuthRetiredClients:          map[string]string{hashHexUUID(secret, retiredUUID): "android-old"},
		AuthMinSupportedVersionName: "1.2.6",
		AuthMinSupportedVersionCode: 1026,
	}
	l := ladder.New([]time.Duration{30 * time.Second})
	mw := auth.NewMiddleware(cfg, l)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.RemoteAddr = "192.0.2.10:1234"
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, retiredUUID))
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUpgradeRequired {
		t.Fatalf("status = %d, want 426", w.Code)
	}
	// CRITICAL: retired UUID must NOT advance backoff — user is honest, just outdated
	blocked, _ := l.CheckBlocked("192.0.2.10", time.Now())
	if blocked {
		t.Fatal("retired UUID incorrectly advanced backoff (user is honest, just outdated)")
	}
}
