package server

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func newAltSvcTestRouter(http3Enabled bool, listen string) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(NewAltSvcMiddleware(http3Enabled, listen))
	r.GET("/echo", func(c *gin.Context) {
		c.String(http.StatusOK, "ok")
	})
	return r
}

// TestAltSvcMiddleware_HTTP2Response_AddsAltSvc — load-bearing path.
// httptest synthesises an HTTP/1.1 request (the in-process httptest
// server runs HTTP/1; HTTP/2 negotiation requires real TLS). The
// middleware's branch only short-circuits on HTTP/3, so HTTP/1 and
// HTTP/2 both produce the Alt-Svc header — which is the correct
// behavior for a server that wants to advertise h3 to ANY non-h3 peer.
//
// Mutation rehearsal (§6.N): change the header literal to
// `h2=":..."; ma=86400` (wrong protocol) — the test fires.
func TestAltSvcMiddleware_HTTP2Response_AddsAltSvc(t *testing.T) {
	r := newAltSvcTestRouter(true, ":8443")
	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/echo", nil)
	req.Proto = "HTTP/2.0"
	r.ServeHTTP(w, req)

	got := w.Header().Get("Alt-Svc")
	if got == "" {
		t.Fatalf("Alt-Svc missing; want non-empty for HTTP/2 response")
	}
	if !strings.Contains(got, `h3=":8443"`) {
		t.Errorf("Alt-Svc = %q, want contains `h3=\":8443\"`", got)
	}
	if !strings.Contains(got, "ma=86400") {
		t.Errorf("Alt-Svc = %q, want contains \"ma=86400\"", got)
	}
}

// TestAltSvcMiddleware_HTTP1Response_AddsAltSvc — same logic for HTTP/1.
func TestAltSvcMiddleware_HTTP1Response_AddsAltSvc(t *testing.T) {
	r := newAltSvcTestRouter(true, "0.0.0.0:8443")
	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/echo", nil)
	req.Proto = "HTTP/1.1"
	r.ServeHTTP(w, req)

	got := w.Header().Get("Alt-Svc")
	if !strings.Contains(got, `h3=":8443"`) {
		t.Errorf("Alt-Svc = %q, want contains h3-port", got)
	}
}

// TestAltSvcMiddleware_HTTP3Response_NoAltSvc — clients already on h3
// MUST NOT receive the hint. Mutation rehearsal: remove the
// `if isHTTP3(...)` short-circuit; this test fires.
func TestAltSvcMiddleware_HTTP3Response_NoAltSvc(t *testing.T) {
	r := newAltSvcTestRouter(true, ":8443")
	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/echo", nil)
	req.Proto = "HTTP/3.0"
	r.ServeHTTP(w, req)

	got := w.Header().Get("Alt-Svc")
	if got != "" {
		t.Fatalf("Alt-Svc = %q on HTTP/3 response, want empty", got)
	}
}

// TestAltSvcMiddleware_Disabled_NoHeader — operator kill-switch.
// When http3Enabled is false, we don't advertise even on h2.
func TestAltSvcMiddleware_Disabled_NoHeader(t *testing.T) {
	r := newAltSvcTestRouter(false, ":8443")
	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/echo", nil)
	req.Proto = "HTTP/2.0"
	r.ServeHTTP(w, req)

	got := w.Header().Get("Alt-Svc")
	if got != "" {
		t.Fatalf("Alt-Svc = %q with http3Enabled=false, want empty", got)
	}
}

func TestExtractPort_Variants(t *testing.T) {
	cases := map[string]string{
		":8443":          "8443",
		"0.0.0.0:8443":   "8443",
		"[::]:8443":      "8443",
		"127.0.0.1:9091": "9091",
		"noport":         "noport",
	}
	for in, want := range cases {
		if got := extractPort(in); got != want {
			t.Errorf("extractPort(%q) = %q, want %q", in, got, want)
		}
	}
}
