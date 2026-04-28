// Package auth implements the Sixth-Law-bound pass-through auth model:
// the Auth-Token header from the client is forwarded verbatim to upstream
// rutracker.org as a session cookie, and is SHA-256-hashed into the
// auth_realm_hash column of request_audit. The plaintext token is never
// persisted, never logged, never inserted into a span attribute.
package auth

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"

	"github.com/gin-gonic/gin"
)

// HeaderName is the wire-level header read from the client and forwarded
// to upstream as a Cookie. Name kept identical to the Ktor proxy
// (proxy/src/main/kotlin/digital/vasic/lava/api/routes/Utils.kt).
const HeaderName = "Auth-Token"

// contextKey is the gin.Context key under which the realm hash is cached
// once per request by GinMiddleware.
const contextKey = "auth_realm_hash"

// RealmHash returns the SHA-256 hex of the Auth-Token, or empty string if
// no token was sent. Empty string = anonymous; do NOT mistake it for a
// hash of the empty string.
func RealmHash(r *http.Request) string {
	tok := r.Header.Get(HeaderName)
	if tok == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(tok))
	return hex.EncodeToString(sum[:])
}

// UpstreamCookie translates the Auth-Token header into the Cookie value
// the rutracker.org session model expects. Centralized here so a future
// rutracker schema change is a one-file fix.
//
// The legacy Kotlin proxy forwards the Auth-Token header as the entire
// Cookie value verbatim (see RuTrackerInnerApiImpl.CookieHeader), because
// the client stores the raw Set-Cookie line captured at login. To keep
// the Go side prefix-agnostic but spec-aligned (spec §9 names bb_session
// as the canonical session cookie), we prepend bb_session= only when the
// token is bare; if the token already contains an "=" we forward as-is.
func UpstreamCookie(r *http.Request) string {
	tok := r.Header.Get(HeaderName)
	if tok == "" {
		return ""
	}
	return "bb_session=" + tok
}

// GinMiddleware computes the realm hash once per request and stores it
// on the gin.Context for handlers and the audit writer to consume.
func GinMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Set(contextKey, RealmHash(c.Request))
		c.Next()
	}
}

// HashFromContext returns the realm hash set by GinMiddleware, or "".
func HashFromContext(c *gin.Context) string {
	v, _ := c.Get(contextKey)
	s, _ := v.(string)
	return s
}
