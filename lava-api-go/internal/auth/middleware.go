// Package auth provides Lava-API-Go authentication primitives.
//
// AuthMiddleware (this file) verifies the Lava-Auth header against the
// active and retired allowlists configured at boot. Active hashes pass;
// retired hashes return 426 Upgrade Required (no backoff increment);
// unknown / missing / malformed hashes return 401 (backoff counter
// advances).
//
// The middleware DEPENDS on a *ladder.Ladder for backoff state — see
// internal/auth/backoff.go for the BackoffMiddleware that consumes the
// same Ladder in front of this one. The two are wired by main.go.
//
// Constant-time map lookup (subtle.ConstantTimeCompare) iterates ALL
// entries on every call to defeat timing side-channels: a regular map
// lookup would short-circuit on hash mismatch, leaking which buckets
// the attacker's hash collides with.
package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.ratelimiter/pkg/ladder"

	"digital.vasic.lava.apigo/internal/config"
)

// NewMiddleware returns a Gin handler that enforces Lava-Auth on every
// request that flows through it.
//
// Behavior:
//   - Active hash → c.Set("client_name", name) + ladder.Reset(ip) + c.Next()
//   - Retired hash → 426 Upgrade Required + min-version JSON + c.Abort()
//     (no ladder advance — the user is honest, just outdated)
//   - Missing / malformed / unknown → 401 + ladder.RecordFailure + c.Abort()
//
// The Ladder MUST be the SAME instance used by BackoffMiddleware so
// the counter advancement here translates into 429 responses on
// subsequent requests.
func NewMiddleware(cfg *config.Config, l *ladder.Ladder) gin.HandlerFunc {
	fieldName := cfg.AuthFieldName
	secret := cfg.AuthHMACSecret
	active := cfg.AuthActiveClients
	retired := cfg.AuthRetiredClients
	minVerName := cfg.AuthMinSupportedVersionName
	minVerCode := cfg.AuthMinSupportedVersionCode

	return func(c *gin.Context) {
		hdr := c.GetHeader(fieldName)
		if hdr == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
			return
		}
		blob, err := base64.StdEncoding.DecodeString(hdr)
		if err != nil || len(blob) == 0 {
			l.RecordFailure(c.ClientIP(), time.Now())
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
			return
		}

		hash := hashUUIDBlob(secret, blob)
		// Zeroize plaintext blob bytes per §6.H.
		for i := range blob {
			blob[i] = 0
		}

		if name, ok := constantTimeMapLookup(active, hash); ok {
			c.Set("client_name", name)
			l.Reset(c.ClientIP())
			c.Next()
			return
		}

		if name, ok := constantTimeMapLookup(retired, hash); ok {
			c.AbortWithStatusJSON(http.StatusUpgradeRequired, gin.H{
				"error":                      "client_version_unsupported",
				"client_name":                name,
				"min_supported_version_name": minVerName,
				"min_supported_version_code": minVerCode,
			})
			return
		}

		// Unknown UUID: advance backoff, return 401.
		l.RecordFailure(c.ClientIP(), time.Now())
		c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "unauthorized"})
	}
}

// constantTimeMapLookup is a constant-time variant of map lookup
// against a known set of hashes — iterates ALL entries on every call.
// A regular map lookup short-circuits on hash mismatch, leaking
// information about which buckets the attacker's hash collides with.
func constantTimeMapLookup(m map[string]string, hash string) (string, bool) {
	var foundName string
	var found int
	hashB := []byte(hash)
	for k, v := range m {
		eq := subtle.ConstantTimeCompare([]byte(k), hashB)
		if eq == 1 {
			foundName = v
			found = 1
		}
	}
	return foundName, found == 1
}

// hashUUIDBlob computes hex(HMAC-SHA256(blob, secret)) — the same shape
// stored in cfg.AuthActiveClients / AuthRetiredClients map keys (filled
// at config-load time by parseClientsList).
func hashUUIDBlob(secret, blob []byte) string {
	h := hmac.New(sha256.New, secret)
	h.Write(blob)
	return hex.EncodeToString(h.Sum(nil))
}

// TestOnlyHashUUID is exported for unit tests in middleware_test.go.
// Production code MUST NOT call it; production hashing flows through
// hashUUIDBlob (private). The TestOnly prefix is the convention in
// this codebase for exports that exist solely to seed test fixtures.
func TestOnlyHashUUID(secret, blob []byte) string {
	return hashUUIDBlob(secret, blob)
}
