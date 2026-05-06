// Package server: Alt-Svc header injection middleware.
//
// Phase 8 (Task 8.2) of the Phase-1 client-auth + transport plan.
// When a client connects via HTTP/2 (TCP) and HTTP/3 is enabled, we
// advertise the alternative service via the Alt-Svc header so the
// next request can upgrade to HTTP/3 (UDP/QUIC). HTTP/3 responses do
// NOT need the hint — the client is already on h3, and re-advertising
// would be a wasted byte budget on every response.
//
// Spec source: RFC 7838 (HTTP Alternative Services). The "ma=" max-age
// parameter is set to 86400 seconds (1 day), matching the spec §8.1
// recommendation: long enough to amortize the round-trip discovery,
// short enough that a configuration change propagates within a day.
package server

import (
	"fmt"
	"strings"

	"github.com/gin-gonic/gin"
)

// NewAltSvcMiddleware returns a Gin handler that emits
// `Alt-Svc: h3=":<port>"; ma=86400` on HTTP/2 (and HTTP/1) responses
// when http3Enabled is true. HTTP/3 responses get no header — the
// client is already on h3 by definition.
//
// `listenAddr` is the lava-api-go public listen address (e.g. ":8443"
// or "0.0.0.0:8443"); only the port portion is advertised.
//
// When http3Enabled is false the returned handler is a no-op
// pass-through so wiring it unconditionally in main.go is safe.
func NewAltSvcMiddleware(http3Enabled bool, listenAddr string) gin.HandlerFunc {
	if !http3Enabled {
		return func(c *gin.Context) { c.Next() }
	}
	port := extractPort(listenAddr)
	header := fmt.Sprintf(`h3=":%s"; ma=86400`, port)
	return func(c *gin.Context) {
		// HTTP/3 already; no need to advertise.
		if isHTTP3(c.Request.Proto) {
			c.Next()
			return
		}
		c.Header("Alt-Svc", header)
		c.Next()
	}
}

// isHTTP3 reports whether the given request protocol string is one of
// the HTTP/3-shaped values produced by quic-go (`HTTP/3.0`) or by
// stdlib net/http (no current variant — left here as a forward-compat
// branch for future HTTP/3 ALPN strings).
func isHTTP3(proto string) bool {
	return strings.HasPrefix(proto, "HTTP/3")
}

// extractPort returns the port portion of a listen address. Accepts:
//   - ":8443"          → "8443"
//   - "0.0.0.0:8443"   → "8443"
//   - "[::]:8443"      → "8443"
//
// Falls back to the input verbatim if no colon is present (e.g. an
// operator typo'd the env var); the caller's middleware then advertises
// `h3="<garbage>"; ma=86400`, which clients ignore and operators see
// in logs. Better than panicking at request time.
func extractPort(addr string) string {
	if idx := strings.LastIndex(addr, ":"); idx >= 0 {
		return addr[idx+1:]
	}
	return addr
}
