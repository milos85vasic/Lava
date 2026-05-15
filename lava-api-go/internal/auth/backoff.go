package auth

import (
	"fmt"
	"math"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.ratelimiter/pkg/ladder"
)

// NewBackoffMiddleware returns a Gin handler that returns 429 +
// Retry-After when the client IP is currently blocked by the ladder.
// On pass-through, the inner AuthMiddleware decides 200/401/426 and
// either advances or resets the same Ladder.
//
// trustedProxies is a list of CIDRs; when c.RemoteAddr matches one of
// them, the X-Forwarded-For header's first entry is used as the
// client IP for ladder lookup. If trustedProxies is empty (default),
// X-Forwarded-For is IGNORED and RemoteAddr is always authoritative
// — this is correct for the LAN deployment where the Lava API is
// directly reachable from clients without a reverse proxy.
//
// The trusted-proxy honor is a §6.J spirit issue: the fast path
// (ignore XFF) MUST NOT fall through to honoring an untrusted XFF
// claim, otherwise an attacker can forge their source IP and dodge
// the per-IP backoff.
func NewBackoffMiddleware(l *ladder.Ladder, trustedProxies []string) gin.HandlerFunc {
	nets := parseCIDRs(trustedProxies)
	return func(c *gin.Context) {
		ip := resolveClientIP(c, nets)
		c.Set("client_ip_resolved", ip)
		blocked, retryAfter := l.CheckBlocked(ip, time.Now())
		if blocked {
			seconds := int(math.Ceil(retryAfter.Seconds()))
			if seconds < 1 {
				seconds = 1
			}
			c.Header("Retry-After", fmt.Sprintf("%d", seconds))
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"error":               "rate_limited",
				"retry_after_seconds": seconds,
			})
			return
		}
		c.Next()
	}
}

// resolveClientIP returns the IP the ladder should use as the lookup
// key. If c.Request.RemoteAddr is in the trusted-proxy CIDR set, the
// first entry from X-Forwarded-For is used. Otherwise, RemoteAddr
// (sans port) is authoritative.
func resolveClientIP(c *gin.Context, trusted []*net.IPNet) string {
	remote, _, err := net.SplitHostPort(c.Request.RemoteAddr)
	if err != nil {
		// no-telemetry: SplitHostPort fails on bare addresses (no port
		// suffix) which is normal for some proxies and unix sockets. The
		// fallback uses RemoteAddr verbatim — downstream net.ParseIP
		// either accepts it or rejects it (handled at the next branch).
		remote = c.Request.RemoteAddr
	}
	rIP := net.ParseIP(remote)
	if rIP == nil {
		return remote
	}
	for _, n := range trusted {
		if n.Contains(rIP) {
			xff := c.GetHeader("X-Forwarded-For")
			if xff != "" {
				first := strings.SplitN(xff, ",", 2)[0]
				return strings.TrimSpace(first)
			}
		}
	}
	return remote
}

// parseCIDRs parses a list of CIDR strings (e.g. ["10.0.0.0/8",
// "192.168.1.0/24"]). Malformed entries are silently dropped.
func parseCIDRs(s []string) []*net.IPNet {
	out := make([]*net.IPNet, 0, len(s))
	for _, c := range s {
		c = strings.TrimSpace(c)
		if c == "" {
			continue
		}
		_, n, err := net.ParseCIDR(c)
		if err != nil {
			continue
		}
		out = append(out, n)
	}
	return out
}
