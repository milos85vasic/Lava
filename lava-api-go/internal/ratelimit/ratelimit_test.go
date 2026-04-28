package ratelimit

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

// TestMiddlewareBlocksAfterLimit pins the user-visible behaviour: the
// third request from the same IP within the window must be rejected
// with HTTP 429 (Too Many Requests). This is the Sixth-Law-bound
// assertion — a real client would observe this exact response.
func TestMiddlewareBlocksAfterLimit(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	l := New(Config{ReadRPM: 2}) // very low limit
	r.GET("/x", l.Middleware(ClassRead), func(c *gin.Context) {
		c.String(http.StatusOK, "ok")
	})

	for i := 0; i < 2; i++ {
		w := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/x", nil)
		req.RemoteAddr = "1.2.3.4:1234"
		r.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("request %d: code=%d body=%q want 200", i, w.Code, w.Body.String())
		}
	}
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/x", nil)
	req.RemoteAddr = "1.2.3.4:1234"
	r.ServeHTTP(w, req)
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("3rd request: code=%d body=%q want 429", w.Code, w.Body.String())
	}
}

// TestMiddlewareNoOpWhenClassDisabled — RPM=0 must short-circuit to a
// no-op so an unlimited number of requests pass through. Defends the
// "zero disables" invariant in DefaultConfig and the New() constructor.
func TestMiddlewareNoOpWhenClassDisabled(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	l := New(Config{ReadRPM: 0}) // disabled
	r.GET("/x", l.Middleware(ClassRead), func(c *gin.Context) {
		c.String(http.StatusOK, "ok")
	})

	for i := 0; i < 10; i++ {
		w := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/x", nil)
		req.RemoteAddr = "5.6.7.8:9999"
		r.ServeHTTP(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("disabled-class request %d: code=%d want 200", i, w.Code)
		}
	}
}

// TestMiddlewareSeparatesClientsByIP — limiter buckets are keyed on
// client IP, so two different clients should get independent quotas.
// The plan groups by ip+class; this test pins the IP axis.
func TestMiddlewareSeparatesClientsByIP(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	l := New(Config{ReadRPM: 1})
	r.GET("/x", l.Middleware(ClassRead), func(c *gin.Context) {
		c.String(http.StatusOK, "ok")
	})

	// Client A — burns its quota (1) then hits 429.
	for i, want := range []int{http.StatusOK, http.StatusTooManyRequests} {
		w := httptest.NewRecorder()
		req := httptest.NewRequest("GET", "/x", nil)
		req.RemoteAddr = "10.0.0.1:1111"
		r.ServeHTTP(w, req)
		if w.Code != want {
			t.Fatalf("client A request %d: code=%d want %d", i, w.Code, want)
		}
	}

	// Client B — independent quota; first request must succeed.
	w := httptest.NewRecorder()
	req := httptest.NewRequest("GET", "/x", nil)
	req.RemoteAddr = "10.0.0.2:2222"
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("client B first request: code=%d want 200 (independent bucket)", w.Code)
	}
}

// TestDefaultConfigMatchesSpec pins the spec §9 placeholder defaults.
// If anyone retunes them they must do so deliberately and update the
// spec at the same time.
func TestDefaultConfigMatchesSpec(t *testing.T) {
	c := DefaultConfig()
	if c.ReadRPM != 60 || c.WriteRPM != 10 || c.LoginRPM != 5 || c.DownloadRPM != 10 {
		t.Fatalf("DefaultConfig drift: %+v want {60,10,5,10}", c)
	}
}
