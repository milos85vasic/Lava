package server

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/andybalholm/brotli"
	"github.com/gin-gonic/gin"
)

// payload is a string with enough redundancy that brotli compresses it
// down meaningfully — we want the on-the-wire bytes to differ from
// the plaintext so the test cannot accidentally pass on a no-op
// encoder that just shovels bytes through unchanged.
const brotliTestPayload = "Lava-API-Go brotli test payload. " +
	"This sentence repeats. This sentence repeats. " +
	"This sentence repeats. This sentence repeats. " +
	"This sentence repeats. This sentence repeats."

func newBrotliTestRouter(enabled bool, quality int) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(NewBrotliMiddleware(enabled, quality))
	r.GET("/echo", func(c *gin.Context) {
		c.String(http.StatusOK, brotliTestPayload)
	})
	return r
}

// TestBrotliMiddleware_AcceptEncodingBr_CompressesResponse — the
// load-bearing test for §6.J: a real client that advertises brotli
// gets a response whose Content-Encoding is "br" AND whose body
// brotli-decodes back to the original payload. Mutation rehearsal
// (clause §6.N): change `c.Header("Content-Encoding", "br")` to
// `c.Header("Content-Encoding", "gzip")` and confirm this test fails
// with a clear assertion on the header value AND the decompression
// step erroring out.
func TestBrotliMiddleware_AcceptEncodingBr_CompressesResponse(t *testing.T) {
	r := newBrotliTestRouter(true, 4)
	srv := httptest.NewServer(r)
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/echo", nil)
	req.Header.Set("Accept-Encoding", "br")
	// Disable Go's automatic transparent compression negotiation so we
	// see the raw response body. The default http.Client only does
	// gzip auto-decompression but we want explicit control here.
	resp, err := http.DefaultTransport.RoundTrip(req)
	if err != nil {
		t.Fatalf("request error: %v", err)
	}
	defer resp.Body.Close()

	if got := resp.Header.Get("Content-Encoding"); got != "br" {
		t.Fatalf("Content-Encoding = %q, want \"br\"", got)
	}
	if got := resp.Header.Get("Vary"); !strings.Contains(got, "Accept-Encoding") {
		t.Fatalf("Vary = %q, want contains \"Accept-Encoding\"", got)
	}

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	// Falsifiability evidence: the on-the-wire body MUST differ from
	// the plaintext. If the middleware silently passes through (which
	// is what mutating "always set Content-Encoding: br" without a
	// real encoder produces), this assertion fires.
	if bytes.Equal(raw, []byte(brotliTestPayload)) {
		t.Fatalf("response body is plaintext but Content-Encoding=br — middleware did not actually compress")
	}

	dec := brotli.NewReader(bytes.NewReader(raw))
	got, err := io.ReadAll(dec)
	if err != nil {
		t.Fatalf("brotli decode: %v\nwire bytes: %x", err, raw)
	}
	if string(got) != brotliTestPayload {
		t.Fatalf("decoded body = %q, want %q", got, brotliTestPayload)
	}
}

// TestBrotliMiddleware_NoAcceptEncoding_PassesThrough — clients that
// don't advertise brotli get plaintext. This also covers the §6.J
// failure mode that motivated the falsifiability rehearsal mention
// in the plan (mutating to always-add-br would emit broken responses
// to non-br clients).
func TestBrotliMiddleware_NoAcceptEncoding_PassesThrough(t *testing.T) {
	r := newBrotliTestRouter(true, 4)
	srv := httptest.NewServer(r)
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/echo", nil)
	// Explicitly empty: no Accept-Encoding negotiated.
	resp, err := http.DefaultTransport.RoundTrip(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()

	if got := resp.Header.Get("Content-Encoding"); got != "" {
		t.Fatalf("Content-Encoding = %q, want empty", got)
	}
	body, _ := io.ReadAll(resp.Body)
	if string(body) != brotliTestPayload {
		t.Fatalf("body = %q, want %q", body, brotliTestPayload)
	}
}

// TestBrotliMiddleware_Disabled_NoCompression — when the operator
// disables brotli at runtime, even a br-advertising client gets
// plaintext. This protects the operator-visible "kill switch" semantic
// of the config field.
func TestBrotliMiddleware_Disabled_NoCompression(t *testing.T) {
	r := newBrotliTestRouter(false, 4)
	srv := httptest.NewServer(r)
	defer srv.Close()

	req, _ := http.NewRequest("GET", srv.URL+"/echo", nil)
	req.Header.Set("Accept-Encoding", "br")
	resp, err := http.DefaultTransport.RoundTrip(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()

	if got := resp.Header.Get("Content-Encoding"); got != "" {
		t.Fatalf("Content-Encoding = %q, want empty (disabled)", got)
	}
	body, _ := io.ReadAll(resp.Body)
	if string(body) != brotliTestPayload {
		t.Fatalf("body = %q, want %q", body, brotliTestPayload)
	}
}

// TestBrotliMiddleware_ZeroQValue_IsHonored covers the RFC 9110 §12.5.3
// edge: `Accept-Encoding: br;q=0` explicitly forbids brotli. We must
// not compress.
func TestBrotliMiddleware_ZeroQValue_IsHonored(t *testing.T) {
	r := newBrotliTestRouter(true, 4)
	srv := httptest.NewServer(r)
	defer srv.Close()

	cases := []string{"br;q=0", "br;q=0.0", "br;q=0.000"}
	for _, ae := range cases {
		t.Run(ae, func(t *testing.T) {
			req, _ := http.NewRequest("GET", srv.URL+"/echo", nil)
			req.Header.Set("Accept-Encoding", ae)
			resp, err := http.DefaultTransport.RoundTrip(req)
			if err != nil {
				t.Fatalf("request: %v", err)
			}
			defer resp.Body.Close()
			if got := resp.Header.Get("Content-Encoding"); got != "" {
				t.Fatalf("Accept-Encoding=%q produced Content-Encoding=%q, want empty", ae, got)
			}
		})
	}
}

func TestAcceptsBrotli_Variants(t *testing.T) {
	cases := map[string]bool{
		"":                            false,
		"br":                          true,
		"BR":                          true,
		"gzip, br":                    true,
		"gzip;q=1.0, br;q=0.9":        true,
		"br;q=0":                      false,
		"br;q=0.0":                    false,
		"br;q=0.000":                  false,
		"identity":                    false,
		"gzip, deflate":               false,
		"gzip;q=1.0,br;q=1.0,*;q=0.1": true,
	}
	for in, want := range cases {
		got := acceptsBrotli(in)
		if got != want {
			t.Errorf("acceptsBrotli(%q) = %v, want %v", in, got, want)
		}
	}
}
