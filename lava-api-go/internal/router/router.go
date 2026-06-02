// Package router builds the lava-api-go Gin engine — the SINGLE production
// router construction shared by every surface that serves the API:
//
//   - cmd/lava-api-go/main.go (the HTTP/3 + HTTP/2-over-TLS LAN binary)
//   - internal/mobile (the in-process embed the Android client boots on-device)
//
// Extracted from cmd/lava-api-go/main.go's former package-private buildRouter
// so the embed reuses the EXACT same middleware chain + handler registration
// the production binary uses, rather than forking a parallel handler set
// (DRY; §6.J — a divergent embed router would be a bluff vector by
// construction: green tests against an embed that serves different routes
// than production guarantee nothing about production).
//
// Decoupled Reusable rationale: this package is Lava-domain glue tying the
// internal handler/middleware/auth packages into one engine. It contains no
// logic another vasic-digital project would consume directly.
package router

import (
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/firebase"
	"digital.vasic.lava.apigo/internal/handlers"
	v1handlers "digital.vasic.lava.apigo/internal/handlers/v1"
	"digital.vasic.lava.apigo/internal/middleware"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/server"
	"digital.vasic.ratelimiter/pkg/ladder"
)

// Deps bundles what Build needs. Held as a struct so a future addition
// (rate limiter, audit writer, …) does not change the function signature.
//
// This is the former cmd/lava-api-go.routerDeps, moved here verbatim so the
// production binary and the embed share one definition.
type Deps struct {
	Cfg        *config.Config
	AuthLadder *ladder.Ladder
	Cache      handlers.Cache
	Scraper    handlers.ScraperClient
	Registry   *provider.ProviderRegistry
	Metrics    *observability.Metrics
	PromReg    prometheus.Registerer
	Readiness  observability.ReadinessProbe
	Firebase   firebase.Client
}

// Build assembles the Gin engine. This is the EXACT construction the
// production cmd/lava-api-go binary uses; the embed (internal/mobile) calls it
// too so its served routes are identical to production.
//
// Sixth Law clause 2 falsifiability target: removing handlers.Register here
// makes len(engine.Routes()) drop below the expected count and the route-count
// tests in cmd/lava-api-go + internal/mobile fail with a clear message.
func Build(deps Deps) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	engine := gin.New()
	engine.Use(gin.Recovery())
	engine.Use(middleware.FirebaseTelemetry(deps.Firebase))

	// /health + /ready MUST be registered BEFORE the auth middleware so the
	// orchestrator's liveness/readiness probes (podman HEALTHCHECK, the
	// distribute scripts' health wait, and the on-device embed's own probe)
	// can hit them without a Lava-Auth header.
	engine.GET("/health", observability.LivenessHandler())
	engine.GET("/ready", observability.ReadinessHandler(deps.Readiness))

	// Phase 8 protocol metric MUST be first in the auth-gated chain so its
	// post-c.Next() block reads the final c.Writer.Status() — including
	// 401/426/429 from auth + backoff middlewares that abort early.
	if deps.Cfg != nil {
		engine.Use(server.NewProtocolMetricMiddleware(deps.Cfg.ProtocolMetricEnabled, deps.PromReg))
	}
	// Phase 7 (§6.G): backoff fires FIRST among auth middlewares so blocked
	// IPs short-circuit with 429 + Retry-After before AuthMiddleware can
	// advance the counter again. Both middlewares share the same
	// ladder.Ladder instance constructed by the composition root.
	if deps.Cfg != nil && deps.AuthLadder != nil {
		engine.Use(auth.NewBackoffMiddleware(deps.AuthLadder, deps.Cfg.AuthTrustedProxies))
		engine.Use(auth.NewMiddleware(deps.Cfg, deps.AuthLadder))
	}
	engine.Use(auth.GinMiddleware())
	if deps.Metrics != nil {
		engine.Use(deps.Metrics.GinMiddleware())
	}
	// Brotli + Alt-Svc on the success path (after auth gates).
	if deps.Cfg != nil {
		engine.Use(server.NewBrotliMiddleware(deps.Cfg.BrotliResponseEnabled, deps.Cfg.BrotliQuality))
		engine.Use(server.NewAltSvcMiddleware(deps.Cfg.HTTP3Enabled, deps.Cfg.Listen))
	}

	handlers.Register(engine, &handlers.Deps{
		Cache:   deps.Cache,
		Scraper: deps.Scraper,
	})

	// v1 provider-agnostic routes
	v1 := engine.Group("/v1/:provider")
	v1handlers.Register(v1, &v1handlers.Deps{
		Cache: deps.Cache,
	})

	return engine
}
