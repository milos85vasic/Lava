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
	"context"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/firebase"
	"digital.vasic.lava.apigo/internal/handlers"
	v1handlers "digital.vasic.lava.apigo/internal/handlers/v1"
	"digital.vasic.lava.apigo/internal/jackett"
	"digital.vasic.lava.apigo/internal/middleware"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/provider/jackettprovider"
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

	// GET /providers — the provider catalogue for dynamic provider discovery
	// (spec 2026-06-11). It MUST be registered BEFORE the auth middleware, like
	// /health/ready: it is PUBLIC, non-sensitive provider metadata (ids,
	// capabilities, authType, baseUrls — no credentials, no user data), and the
	// Android client MUST be able to fetch it during ONBOARDING against a
	// freshly-DISCOVERED API (mDNS) that has no pre-shared Lava-Auth key with the
	// client and whose allowlist may not yet include the client's build. Gating
	// the catalogue behind auth is a chicken-and-egg: onboarding needs the
	// catalogue to configure providers, but auth is exactly what onboarding is
	// establishing. Real-device root cause (Crashlytics 47b000d5, v1.3.4): the
	// fetch returned "HTTP 401 for https://<api>/providers" → the wizard fell
	// back to bundled providers ("Couldn't reach the selected API"). Mounted at
	// ROOT, NOT under /v1/:provider (a literal /v1/providers would collide with
	// the :provider wildcard in gin's radix tree). Per-provider operations
	// (/v1/:provider/...) stay auth-gated below.
	engine.GET("/providers", v1handlers.NewProvidersHandler(deps.Registry).GetProviders)

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

	// v1 provider-agnostic routes. The provider-resolution middleware is
	// mounted PER ROUTE inside v1handlers.Register (each endpoint asserts a
	// different §6.E capability). deps.Registry is the production registry
	// built by the composition root (cmd/lava-api-go/main.go / internal/
	// mobile); passing it here is what makes /v1/{provider}/... dispatch to a
	// real provider instead of panicking in currentProvider() → 500 (LVA-010).
	v1 := engine.Group("/v1/:provider")
	v1Deps := &v1handlers.Deps{
		Cache: deps.Cache,
	}
	// Inject the config-driven server-side search deadline (LVA-083 H2 / §6.Z /
	// §6.R: no bare literal in the handler). When Cfg is nil (registration-only
	// test paths) the handler falls back to v1.defaultSearchTimeout.
	if deps.Cfg != nil {
		v1Deps.SearchTimeout = deps.Cfg.SearchTimeout
	}
	v1handlers.Register(v1, v1Deps, deps.Registry)
	// NOTE: GET /providers is registered ABOVE, before the auth middleware (it is
	// public catalogue metadata — see the rationale there).

	// Jackett sidecar route — registered ONLY when the sidecar is enabled AND
	// fully configured (§6.R: no hardcoded base URL / api_key; the route is a
	// no-op by default). The app reaches Jackett ONLY through this surface; it
	// never talks to the sidecar directly.
	if deps.Cfg != nil && deps.Cfg.JackettEnabled {
		if jc, err := jackett.NewClient(jackett.Config{
			BaseURL:       deps.Cfg.JackettBaseURL,
			APIKey:        deps.Cfg.JackettAPIKey,
			AdminPassword: deps.Cfg.JackettAdminPassword,
		}); err == nil {
			jh := v1handlers.NewJackettHandler(jc, deps.Cfg.JackettDefaultIndexer)
			engine.GET("/jackett/search", jh.GetSearch)

			// Each configured Jackett indexer becomes a first-class discoverable
			// provider (spec §4.1): enumerate at startup + register one provider
			// per indexer. Native providers win on id collision (§6.E); a failed
			// enumeration is non-fatal — native providers still serve.
			if deps.Registry != nil {
				regCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				if idxs, lerr := jc.ListIndexers(regCtx); lerr == nil {
					for _, idx := range idxs {
						if existing, _ := deps.Registry.Get(idx.ID); existing != nil {
							observability.RecordWarning(regCtx,
								"jackett indexer id collides with a native provider; native wins",
								observability.NonFatalAttributes{
									observability.AttrFeature:   "provider-discovery",
									observability.AttrOperation: "jackett-register",
									observability.AttrTrackerID: idx.ID,
								})
							continue
						}
						deps.Registry.Register(jackettprovider.New(idx.ID, idx.Name, jc))
					}
				} else {
					observability.RecordNonFatal(regCtx, lerr, observability.NonFatalAttributes{
						observability.AttrFeature:   "provider-discovery",
						observability.AttrOperation: "jackett-list-indexers",
					})
				}
				cancel()
			}
		}
	}

	return engine
}
