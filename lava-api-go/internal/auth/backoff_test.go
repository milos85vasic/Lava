package auth_test

import (
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.ratelimiter/pkg/ladder"

	"digital.vasic.lava.apigo/internal/auth"
)

func TestBackoffMiddleware_NotBlocked_PassesThrough(t *testing.T) {
	gin.SetMode(gin.TestMode)
	l := ladder.New([]time.Duration{1 * time.Second})
	mw := auth.NewBackoffMiddleware(l, nil)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.RemoteAddr = "192.0.2.1:1234"
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
}

func TestBackoffMiddleware_Blocked_Returns429WithRetryAfter(t *testing.T) {
	gin.SetMode(gin.TestMode)
	l := ladder.New([]time.Duration{30 * time.Second})
	l.RecordFailure("192.0.2.1", time.Now())
	mw := auth.NewBackoffMiddleware(l, nil)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.RemoteAddr = "192.0.2.1:1234"
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429", w.Code)
	}
	retryAfter := w.Header().Get("Retry-After")
	n, err := strconv.Atoi(retryAfter)
	if err != nil {
		t.Fatalf("Retry-After = %q (not an integer)", retryAfter)
	}
	if n < 25 || n > 30 {
		t.Fatalf("Retry-After = %d; expected ~30s", n)
	}
}

func TestBackoffMiddleware_BlockedHonorsTrustedProxy_XForwardedFor(t *testing.T) {
	gin.SetMode(gin.TestMode)
	l := ladder.New([]time.Duration{30 * time.Second})
	// Pretend the API is behind 10.0.0.1; record failure for the
	// X-Forwarded-For client IP.
	l.RecordFailure("203.0.113.5", time.Now())
	mw := auth.NewBackoffMiddleware(l, []string{"10.0.0.1/32"})

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("X-Forwarded-For", "203.0.113.5")
	req.RemoteAddr = "10.0.0.1:1234"
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429 (trusted proxy XFF unwrap failed)", w.Code)
	}
}

func TestBackoffMiddleware_UntrustedProxy_IgnoresXForwardedFor(t *testing.T) {
	// Failure was recorded for 203.0.113.5 (the XFF claim), but the
	// caller is NOT a trusted proxy → we MUST use RemoteAddr (10.0.0.2)
	// for the lookup, which has no failures. Pass-through.
	gin.SetMode(gin.TestMode)
	l := ladder.New([]time.Duration{30 * time.Second})
	l.RecordFailure("203.0.113.5", time.Now())
	mw := auth.NewBackoffMiddleware(l, []string{"10.0.0.1/32"}) // .1 trusted, .2 not

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("X-Forwarded-For", "203.0.113.5")
	req.RemoteAddr = "10.0.0.2:1234" // NOT in trusted list
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (untrusted XFF must be ignored)", w.Code)
	}
}

func TestBackoffMiddleware_RetryAfterRoundsUp(t *testing.T) {
	// Sub-second retry must round up to integer seconds for the
	// Retry-After header.
	gin.SetMode(gin.TestMode)
	l := ladder.New([]time.Duration{2 * time.Second})
	l.RecordFailure("192.0.2.1", time.Now().Add(-1500*time.Millisecond))
	mw := auth.NewBackoffMiddleware(l, nil)

	r := gin.New()
	r.Use(mw)
	r.GET("/x", func(c *gin.Context) { c.Status(http.StatusOK) })

	req := httptest.NewRequest("GET", "/x", nil)
	req.RemoteAddr = "192.0.2.1:1234"
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429", w.Code)
	}
	n, _ := strconv.Atoi(w.Header().Get("Retry-After"))
	if n < 1 {
		t.Fatalf("Retry-After = %d; want at least 1 (round-up)", n)
	}
}
