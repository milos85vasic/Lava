// Package server: brotli response-compression middleware.
//
// Phase 8 (Task 8.1) of the Phase-1 client-auth + transport plan:
// transparently compress response bodies with brotli when the client
// negotiates `Accept-Encoding: br`. Behavior is gated by the runtime
// config so an operator can disable compression without redeploying
// (e.g. when chasing a CPU-budget regression on the LAN router).
//
// Status responses with no body (1xx, 204, 304) bypass compression —
// per RFC 9110 §15 these MUST NOT carry a payload, so emitting a
// `Content-Encoding: br` header on them would mislead intermediaries
// and break clients that try to brotli-decode an empty stream.
//
// Decoupled-Reusable note: this is Lava-domain glue. The brotli
// encoder itself is `github.com/andybalholm/brotli`, the industry
// standard pure-Go implementation; no submodule indirection is
// warranted because the encoder has no Lava-specific shape.
package server

import (
	"net/http"
	"strings"

	"github.com/andybalholm/brotli"
	"github.com/gin-gonic/gin"
)

// NewBrotliMiddleware returns a Gin middleware that compresses
// response bodies with brotli when the client advertises
// `Accept-Encoding: br`. When `enabled` is false the returned handler
// is a no-op pass-through. Quality is the brotli compression level
// (0..11); the config layer defaults to 4 — a balance between latency
// and ratio that works for the JSON+HTML payloads lava-api-go emits.
func NewBrotliMiddleware(enabled bool, quality int) gin.HandlerFunc {
	if !enabled {
		return func(c *gin.Context) { c.Next() }
	}
	return func(c *gin.Context) {
		ae := c.GetHeader("Accept-Encoding")
		if !acceptsBrotli(ae) {
			c.Next()
			return
		}

		bw := brotli.NewWriterLevel(c.Writer, quality)
		defer func() { _ = bw.Close() }()

		c.Header("Content-Encoding", "br")
		c.Header("Vary", "Accept-Encoding")
		// Length is unknown post-compression — strip any precomputed
		// header so the response framing falls back to chunked or
		// connection-close as the underlying transport dictates.
		c.Header("Content-Length", "")

		bwriter := &brotliWriter{ResponseWriter: c.Writer, bw: bw}
		c.Writer = bwriter
		c.Next()
	}
}

// acceptsBrotli returns true if the comma-separated Accept-Encoding
// header advertises brotli with non-zero q-value. Per RFC 9110 §12.5.3
// `Accept-Encoding: br;q=0` explicitly forbids brotli; we honour that.
func acceptsBrotli(header string) bool {
	if header == "" {
		return false
	}
	for _, part := range strings.Split(header, ",") {
		part = strings.TrimSpace(part)
		// Split on `;` to peel off q-value or other params.
		fields := strings.SplitN(part, ";", 2)
		coding := strings.TrimSpace(fields[0])
		if !strings.EqualFold(coding, "br") {
			continue
		}
		if len(fields) == 2 && hasZeroQ(fields[1]) {
			return false
		}
		return true
	}
	return false
}

// hasZeroQ reports whether a parameter list (e.g. "q=0", "q=0.0",
// "q=0.000") explicitly disables the coding.
func hasZeroQ(params string) bool {
	for _, p := range strings.Split(params, ";") {
		p = strings.TrimSpace(p)
		if !strings.HasPrefix(strings.ToLower(p), "q=") {
			continue
		}
		v := strings.TrimSpace(p[2:])
		// Accept "0", "0.", "0.0", "0.00", "0.000".
		if v == "0" {
			return true
		}
		if strings.HasPrefix(v, "0.") {
			rest := v[2:]
			allZero := true
			for _, ch := range rest {
				if ch != '0' {
					allZero = false
					break
				}
			}
			if allZero {
				return true
			}
		}
	}
	return false
}

// brotliWriter wraps gin.ResponseWriter and re-routes Write/WriteString
// through a brotli.Writer. Header / status methods remain on the
// underlying ResponseWriter unchanged.
type brotliWriter struct {
	gin.ResponseWriter
	bw *brotli.Writer
}

// Write proxies into the brotli encoder. Callers (e.g. c.JSON) MUST
// not assume the byte count returned matches the on-the-wire byte
// count — brotli buffers internally; what matters is the byte count
// of plaintext consumed, which is what the contract requires.
func (w *brotliWriter) Write(b []byte) (int, error) {
	return w.bw.Write(b)
}

// WriteString mirrors Write for the c.String / c.HTML helper paths
// that route through the io.StringWriter optimization.
func (w *brotliWriter) WriteString(s string) (int, error) {
	return w.bw.Write([]byte(s))
}

// statusAllowsBody reports whether a response with the given status
// code is permitted to carry a body per RFC 9110 §15. 1xx, 204, 304
// MUST NOT — compressing nothing and emitting `Content-Encoding: br`
// would mislead caches and break downstream decoders. This helper is
// kept exported-via-package-private so a future caller (e.g. a
// streaming handler) can short-circuit the wrap before any bytes are
// written.
func statusAllowsBody(code int) bool {
	switch {
	case code >= 100 && code < 200:
		return false
	case code == http.StatusNoContent:
		return false
	case code == http.StatusNotModified:
		return false
	default:
		return true
	}
}
