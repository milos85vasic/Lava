// Command lava-api-go is the runnable entry point for the Lava Go API
// service. It composes the internal packages (config, observability,
// cache, ratelimit, auth, handlers, server, discovery, rutracker) into
// a single binary that:
//
//  1. Listens on HTTP/3 (the public LAN listener — TLS 1.3 required).
//  2. Exposes /metrics on a SEPARATE plain-HTTP localhost listener.
//  3. Announces itself on the LAN via mDNS (_lava-api._tcp).
//  4. Performs a graceful shutdown on SIGINT / SIGTERM.
//
// Decoupled Reusable rationale: this file is Lava-domain glue. It
// contains no logic that another vasic-digital project would consume
// directly; the reusable pieces all live in the imported packages.
//
// Sixth Law note: the wiring sequence here is exercised by the e2e
// suite added in Phase 10 (boot the full stack against a real
// Postgres + a fake rutracker, hit a real HTTP/3 client at every
// route). The smoke `--help` test in main_test.go is a CI-cheap
// guarantee that the binary BUILDS and parses flags; it is not a
// substitute for the Phase-10 acceptance suite.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"

	pgcache "digital.vasic.cache/pkg/postgres"
	"digital.vasic.ratelimiter/pkg/ladder"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/cache"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/discovery"
	"digital.vasic.lava.apigo/internal/firebase"
	"digital.vasic.lava.apigo/internal/handlers"
	v1handlers "digital.vasic.lava.apigo/internal/handlers/v1"
	"digital.vasic.lava.apigo/internal/middleware"
	"digital.vasic.lava.apigo/internal/nnmclub"
	"digital.vasic.lava.apigo/internal/kinozal"
	"digital.vasic.lava.apigo/internal/archiveorg"
	"digital.vasic.lava.apigo/internal/gutenberg"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/ratelimit"
	"digital.vasic.lava.apigo/internal/rutracker"
	"digital.vasic.lava.apigo/internal/server"
	"digital.vasic.lava.apigo/internal/version"
)

// shutdownTimeout bounds how long the graceful drain may take before
// we abandon in-flight requests and terminate the process. 10 seconds
// is the spec §10 default; long enough for a paginated forum response
// to flush, short enough that a stuck upstream cannot hold the process.
const shutdownTimeout = 10 * time.Second

func main() {
	help := flag.Bool("help", false, "print help and exit")
	flag.BoolVar(help, "h", false, "print help and exit (shorthand)")
	showVersion := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *help {
		printHelp()
		return
	}
	if *showVersion {
		fmt.Printf("lava-api-go %s (build %d)\n", version.Name, version.Code)
		return
	}

	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "lava-api-go: %v\n", err)
		os.Exit(1)
	}
}

// run performs the whole startup / serve / shutdown sequence. Returning
// an error rather than calling os.Exit keeps main() trivially testable.
func run() error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}

	logger := observability.NewLogger(observability.LogConfig{
		Output: os.Stdout,
		Level:  slog.LevelInfo,
	})
	logger.Info("starting", "service", "lava-api-go", "version", version.Name, "build", version.Code)

	metricsRegistry := prometheus.NewRegistry()
	metrics := observability.NewMetrics(metricsRegistry)

	ctx := context.Background()
	tracer, err := observability.NewTracer(ctx, observability.TracerConfig{
		ServiceName:    "lava-api-go",
		ServiceVersion: version.Name,
		Environment:    envOrDefault("LAVA_API_ENV", "production"),
		OTLPEndpoint:   cfg.OTLPEndpoint,
	})
	if err != nil {
		// Tracing is best-effort: a misconfigured collector should not
		// keep the API offline. Log and continue with the no-op tracer.
		logger.Warn("tracer init failed; continuing without tracing", "err", err)
		tracer = nil
	}

	pgClient, err := pgcache.ConnectFromURL(ctx, &pgcache.Config{
		URL:        cfg.PGUrl,
		SchemaName: cfg.PGSchema,
		TableName:  "response_cache",
		GCInterval: 10 * time.Minute,
	})
	if err != nil {
		return fmt.Errorf("postgres connect: %w", err)
	}
	defer func() { _ = pgClient.Close() }()
	pgClient.Start()

	c := cache.New(pgClient)
	_ = c // currently consumed by handlers.Register; kept for explicitness when handlers grow

	_ = ratelimit.New(ratelimit.DefaultConfig()) // route-class limiters; per-route mounting deferred to Phase 10/11

	scraper := rutracker.NewClient(cfg.RutrackerBaseURL)

	// Multi-provider registry. Register all provider adapters here.
	registry := provider.NewRegistry()
	registry.Register(rutracker.NewProviderAdapter(scraper))
	registry.Register(nnmclub.NewProviderAdapter(nnmclub.NewClient("https://nnmclub.to")))
	registry.Register(kinozal.NewProviderAdapter(kinozal.NewClient("https://kinozal.tv")))
	registry.Register(archiveorg.NewProviderAdapter(archiveorg.NewClient("https://archive.org")))
	registry.Register(gutenberg.NewProviderAdapter(gutenberg.NewClient("https://gutendex.com")))

	// Firebase telemetry — Admin-SDK-backed when LAVA_FIREBASE_ADMIN_KEY (or
	// GOOGLE_APPLICATION_CREDENTIALS) points at a service-account JSON;
	// honest no-op (structured-log forwarder) otherwise. The middleware is
	// safe to install in either mode (see internal/middleware/firebase.go).
	fbClient := firebase.New(firebase.Config{
		CredentialsPath: os.Getenv("LAVA_FIREBASE_ADMIN_KEY"),
		ProjectID:       os.Getenv("LAVA_FIREBASE_PROJECT_ID"),
		Logger:          logger,
	})
	if fbClient.Configured() {
		logger.Info("firebase: admin client configured (server-side telemetry active)")
	} else {
		logger.Info("firebase: no-op mode (structured-log fallback; set LAVA_FIREBASE_ADMIN_KEY to enable)")
	}

	// Phase 7 (§6.G real-stack auth): construct the SHARED ladder.Ladder
	// from cfg.AuthBackoffSteps. BackoffMiddleware (in front) and
	// AuthMiddleware (behind) consume the same instance — backoff
	// short-circuits blocked IPs with 429 BEFORE AuthMiddleware can
	// advance the counter again.
	authLadder := ladder.New(cfg.AuthBackoffSteps)

	router := buildRouter(routerDeps{
		Cfg:        cfg,
		AuthLadder: authLadder,
		Cache:      c,
		Scraper:    scraper,
		Registry:   registry,
		Metrics:    metrics,
		PromReg:    metricsRegistry,
		Firebase:   fbClient,
		Readiness: func(ctx context.Context) error {
			if err := pgClient.HealthCheck(ctx); err != nil {
				return fmt.Errorf("postgres: %w", err)
			}
			return nil
		},
	})

	tlsConfig, err := loadTLSConfig(cfg.TLSCertPath, cfg.TLSKeyPath)
	if err != nil {
		return fmt.Errorf("tls: %w", err)
	}

	srv, err := server.New(server.Config{
		Listen:         cfg.Listen,
		MetricsListen:  cfg.MetricsListen,
		Engine:         router,
		MetricsHandler: metrics.Handler(),
		TLSConfig:      tlsConfig,
	})
	if err != nil {
		return fmt.Errorf("server: %w", err)
	}

	mdnsService, mdnsErr := discovery.Announce(cfg.MDNSInstanceName, cfg.MDNSServiceType, cfg.MDNSPort)
	if mdnsErr != nil {
		logger.Warn("mDNS announcement failed; LAN discovery disabled", "err", mdnsErr)
	} else {
		logger.Info("mDNS announced", "instance", cfg.MDNSInstanceName, "type", cfg.MDNSServiceType, "port", cfg.MDNSPort)
	}

	startErrCh := make(chan error, 1)
	go func() {
		err := srv.Start()
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			startErrCh <- err
			return
		}
		startErrCh <- nil
	}()
	logger.Info("listening", "public", cfg.Listen, "metrics", cfg.MetricsListen)

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)

	select {
	case sig := <-sigCh:
		logger.Info("signal received; beginning graceful shutdown", "signal", sig.String())
	case err := <-startErrCh:
		if err != nil {
			return fmt.Errorf("server start: %w", err)
		}
	}

	if mdnsService != nil {
		mdnsService.Stop()
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Warn("server shutdown error", "err", err)
	}

	if tracer != nil {
		if err := tracer.Shutdown(shutdownCtx); err != nil {
			logger.Warn("tracer shutdown error", "err", err)
		}
	}

	logger.Info("shutdown complete")
	return nil
}

// routerDeps bundles what buildRouter needs. Held as a struct so a
// future addition (rate limiter, audit writer, …) does not change the
// function signature.
type routerDeps struct {
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

// buildRouter assembles the Gin engine. Factored out of run() so it can
// be exercised by main_test.go without booting Postgres or HTTP/3 — the
// Sixth Law clause 2 falsifiability rehearsal targets this function:
// removing handlers.Register here makes len(router.Routes()) drop
// below the expected count and the test fails with a clear message.
func buildRouter(deps routerDeps) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(middleware.FirebaseTelemetry(deps.Firebase))
	// Phase 7 (§6.G): backoff fires FIRST so blocked IPs short-circuit
	// with 429 + Retry-After before AuthMiddleware can advance the
	// counter again. Both middlewares share the same ladder.Ladder
	// instance constructed in run().
	// Phase 8 protocol metric MUST be first in the chain so its
	// post-c.Next() block reads the final c.Writer.Status() — including
	// 401/426/429 from auth + backoff middlewares that abort early.
	if deps.Cfg != nil {
		router.Use(server.NewProtocolMetricMiddleware(deps.Cfg.ProtocolMetricEnabled, deps.PromReg))
	}
	if deps.Cfg != nil && deps.AuthLadder != nil {
		router.Use(auth.NewBackoffMiddleware(deps.AuthLadder, deps.Cfg.AuthTrustedProxies))
		router.Use(auth.NewMiddleware(deps.Cfg, deps.AuthLadder))
	}
	router.Use(auth.GinMiddleware())
	if deps.Metrics != nil {
		router.Use(deps.Metrics.GinMiddleware())
	}
	// Brotli + Alt-Svc on the success path (after auth gates).
	if deps.Cfg != nil {
		router.Use(server.NewBrotliMiddleware(deps.Cfg.BrotliResponseEnabled, deps.Cfg.BrotliQuality))
		router.Use(server.NewAltSvcMiddleware(deps.Cfg.HTTP3Enabled, deps.Cfg.Listen))
	}

	router.GET("/health", observability.LivenessHandler())
	router.GET("/ready", observability.ReadinessHandler(deps.Readiness))

	handlers.Register(router, &handlers.Deps{
		Cache:   deps.Cache,
		Scraper: deps.Scraper,
	})

	// v1 provider-agnostic routes
	v1 := router.Group("/v1/:provider")
	v1handlers.Register(v1, &v1handlers.Deps{
		Cache: deps.Cache,
	})

	return router
}

// loadTLSConfig builds a tls.Config suitable for HTTP/3 (TLS 1.3, h3
// ALPN) from a cert/key file pair on disk. The certificate is read on
// every startup; rotation requires a process restart, which is the
// LAN-deployment expectation per spec §8.
func loadTLSConfig(certPath, keyPath string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if err != nil {
		return nil, fmt.Errorf("load key pair: %w", err)
	}
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS13,
		NextProtos:   []string{"h3"},
	}, nil
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func printHelp() {
	fmt.Fprintf(os.Stdout, `lava-api-go %s — Lava Go API service.

Usage:
  lava-api-go [flags]

Flags:
  -h, --help      print this help and exit
      --version   print version and exit

Configuration is via environment variables (see internal/config):
  LAVA_API_PG_URL         Postgres connection URL (required)
  LAVA_API_PG_SCHEMA      Postgres schema name (default: lava_api)
  LAVA_API_LISTEN         Public HTTP/3 listener (default: :8443)
  LAVA_API_METRICS_LISTEN Private metrics listener (default: 127.0.0.1:9091)
  LAVA_API_TLS_CERT       TLS certificate path (required)
  LAVA_API_TLS_KEY        TLS private-key path (required)
  LAVA_API_OTLP_ENDPOINT  OTLP tracing exporter URL (optional)
  LAVA_API_MDNS_INSTANCE  mDNS instance name (default: Lava API)
  LAVA_API_MDNS_TYPE      mDNS service type (default: _lava-api._tcp)
  LAVA_API_MDNS_PORT      mDNS advertised port (default: 8443)
  LAVA_API_RUTRACKER_URL  rutracker.org base URL (default: https://rutracker.org)
`, version.Name)
}
