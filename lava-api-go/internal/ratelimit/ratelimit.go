// Package ratelimit configures submodules/ratelimiter for the four route
// classes lava-api-go uses (read | write | login | download), keyed by
// (client_ip, route_class). Per spec §9, defaults are placeholders until
// load testing pins them; tunable via env vars.
//
// This package is deliberately thin glue: the sliding-window algorithm
// itself lives upstream in digital.vasic.ratelimiter/pkg/{memory,sliding}.
// Only the route-class taxonomy and per-class default RPM are Lava-side
// concerns. Per CONSTITUTION.md (Decoupled Reusable Architecture) we MUST
// NOT re-implement limiting logic here.
package ratelimit

import (
	"net/http"
	"time"

	rl "digital.vasic.ratelimiter/pkg/limiter"
	rlmem "digital.vasic.ratelimiter/pkg/memory"
	"github.com/gin-gonic/gin"
)

// RouteClass enumerates the rate-limit categories. Each route the
// handlers package mounts declares which class it belongs to via the
// Middleware constructor below.
type RouteClass string

const (
	// ClassRead — anonymous-friendly read-heavy endpoints (forum, topic,
	// search). Default 60 RPM placeholder per spec §9.
	ClassRead RouteClass = "read"
	// ClassWrite — state-mutating endpoints (post comment, add bookmark).
	// Default 10 RPM.
	ClassWrite RouteClass = "write"
	// ClassLogin — auth endpoints. Tighter limit to defend against
	// credential stuffing. Default 5 RPM.
	ClassLogin RouteClass = "login"
	// ClassDownload — torrent-file proxy endpoints. Default 10 RPM.
	ClassDownload RouteClass = "download"
)

// Config holds per-class limits in requests-per-minute. Zero (or
// negative) RPM disables limiting for that class entirely — the
// returned middleware becomes a no-op.
type Config struct {
	ReadRPM     int
	WriteRPM    int
	LoginRPM    int
	DownloadRPM int
}

// DefaultConfig — placeholder limits per spec §9. Production deployments
// override via env (LAVA_RATELIMIT_*_RPM) once load tests pin the real
// numbers.
func DefaultConfig() Config {
	return Config{
		ReadRPM:     60,
		WriteRPM:    10,
		LoginRPM:    5,
		DownloadRPM: 10,
	}
}

// Limiter holds one upstream sliding-window limiter per route class.
// A nil entry means that class is disabled (RPM <= 0).
type Limiter struct {
	limiters map[RouteClass]rl.Limiter
}

// New constructs a Limiter, instantiating one in-memory sliding-window
// limiter (digital.vasic.ratelimiter/pkg/memory) per non-zero class.
// The memory limiter spawns a background cleanup goroutine; callers
// that want to release it should expose a Close() (not yet wired).
func New(cfg Config) *Limiter {
	mk := func(rpm int) rl.Limiter {
		if rpm <= 0 {
			return nil
		}
		return rlmem.New(&rl.Config{Rate: rpm, Window: time.Minute})
	}
	return &Limiter{limiters: map[RouteClass]rl.Limiter{
		ClassRead:     mk(cfg.ReadRPM),
		ClassWrite:    mk(cfg.WriteRPM),
		ClassLogin:    mk(cfg.LoginRPM),
		ClassDownload: mk(cfg.DownloadRPM),
	}}
}

// Middleware returns a Gin middleware for the given route class. The
// limiter key is "<client-ip>:<class>" so a single client hitting two
// different classes gets two independent buckets. On limiter errors we
// fail-open (per upstream RateLimiter middleware convention — see
// digital.vasic.ratelimiter/pkg/middleware) to avoid taking the API
// down when the limiter itself is the bug.
func (l *Limiter) Middleware(class RouteClass) gin.HandlerFunc {
	lim := l.limiters[class]
	if lim == nil {
		return func(c *gin.Context) { c.Next() }
	}
	return func(c *gin.Context) {
		ip := c.ClientIP()
		result, err := lim.Allow(c.Request.Context(), ip+":"+string(class))
		if err != nil {
			// no-telemetry: fail-open by design — when the rate-limit
			// backend (Redis / in-memory store) is unreachable, allow
			// the request rather than degrade availability. The backend's
			// own liveness telemetry (separate channel) catches sustained
			// outages; per-request telemetry would amplify any backend
			// blip into thousands of non-fatal events.
			c.Next() // fail-open
			return
		}
		if !result.Allowed {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "rate limited"})
			return
		}
		c.Next()
	}
}
