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
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"digital.vasic.ratelimiter/pkg/ladder"

	"digital.vasic.lava.apigo/internal/archiveorg"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/discovery"
	"digital.vasic.lava.apigo/internal/firebase"
	"digital.vasic.lava.apigo/internal/gutenberg"
	"digital.vasic.lava.apigo/internal/httpx"
	"digital.vasic.lava.apigo/internal/kinozal"
	"digital.vasic.lava.apigo/internal/nnmclub"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/provider"
	"digital.vasic.lava.apigo/internal/provider/curated"
	"digital.vasic.lava.apigo/internal/ratelimit"
	apirouter "digital.vasic.lava.apigo/internal/router"
	"digital.vasic.lava.apigo/internal/rutracker"
	"digital.vasic.lava.apigo/internal/server"
	"digital.vasic.lava.apigo/internal/storage"
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

	// Wire the configurable outbound proxy for ALL provider egress BEFORE the
	// provider clients are constructed (LAVA_API_UPSTREAM_PROXY). Fail-fast on a
	// malformed value rather than silently falling through to a direct egress
	// the operator believed was proxied (§6.J). Empty value => httpx falls back
	// to http.ProxyFromEnvironment (standard *_PROXY env vars).
	if err := httpx.Configure(cfg.UpstreamProxy); err != nil {
		return fmt.Errorf("upstream proxy: %w", err)
	}

	// Resolve + synchronously confirm-bind the public listen address BEFORE
	// anything downstream bakes it in: the Alt-Svc middleware wired into the
	// Gin engine below (apirouter.Build → NewAltSvcMiddleware) closes over
	// this string at construction time, and the mDNS announcement further
	// down advertises whatever port we hand it. LAVA_API_LISTEN=":0" (or any
	// "host:0" form) requests a genuinely free, OS-assigned port via
	// digital.vasic.containers/pkg/network.ListenEphemeral — the race-free
	// allocator now available through the Containers submodule pin bump —
	// instead of the historical fixed :8443 default, which this session
	// found collides with an unrelated project's process on a shared host.
	// A fixed address (the default, and every existing deployment's
	// configuration) is bound here too, unchanged in the address it binds,
	// so both paths get the same fix for the ordering bug described next.
	//
	// This ALSO fixes a real ordering bug confirmed in this session: this
	// function used to call discovery.Announce (below) and construct the
	// Alt-Svc middleware using the CONFIGURED address before Start() had
	// bound anything at all — the actual socket bind happened later,
	// asynchronously, inside the goroutine that calls srv.Start(). A
	// collision on the configured port would surface only after mDNS had
	// already told the LAN "come talk to me here". ResolveListen performs
	// the bind synchronously right here and returns an error (refusing to
	// start) if it fails, so mDNS/Alt-Svc/the "listening" log line are only
	// ever wired using a port PROVEN available. See server.ResolveListen's
	// doc comment for the full rationale, including the honestly-documented
	// residual limitation on the HTTP/3 (UDP) side.
	requestedListen := cfg.Listen
	resolvedListen, publicListener, dynamicPort, err := server.ResolveListen(cfg.Listen)
	if err != nil {
		return fmt.Errorf("listen: %w", err)
	}
	cfg.Listen = resolvedListen

	logger := observability.NewLogger(observability.LogConfig{
		Output: os.Stdout,
		Level:  slog.LevelInfo,
	})
	logger.Info("starting", "service", "lava-api-go", "version", version.Name, "build", version.Code)
	if dynamicPort {
		logger.Info("dynamic public port requested via LAVA_API_LISTEN; bound a real, race-free ephemeral port",
			"requested", requestedListen, "resolved", cfg.Listen)
	}

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

	// Storage backend selection (Phase A). The factory connects/opens the
	// configured backend (postgres = the exact pgcache config the server has
	// always used; sqlite = pure-Go modernc.org/sqlite) and returns the
	// backend-agnostic Storage + its /ready probe. Default is postgres, so
	// existing deployments are unaffected.
	store, storeReady, err := storage.New(cfg)
	if err != nil {
		return fmt.Errorf("storage init: %w", err)
	}
	defer func() { _ = store.Close() }()
	c := store // consumed by handlers.Register as handlers.Cache / v1.Cache

	_ = ratelimit.New(ratelimit.DefaultConfig()) // route-class limiters; per-route mounting deferred to Phase 10/11

	scraper := rutracker.NewClient(cfg.RutrackerBaseURL)

	// Multi-provider registry. Register all provider adapters here.
	registry := provider.NewRegistry()
	registry.Register(rutracker.NewProviderAdapter(scraper))
	registry.Register(nnmclub.NewProviderAdapter(nnmclub.NewClient("https://nnmclub.to")))
	registry.Register(kinozal.NewProviderAdapter(kinozal.NewClient("https://kinozal.tv")))
	registry.Register(archiveorg.NewProviderAdapter(archiveorg.NewClient("https://archive.org")))
	registry.Register(gutenberg.NewProviderAdapter(gutenberg.NewClient("https://gutendex.com")))
	// Curated compiled-in public-tracker providers (Defect B, 2026-06-12) —
	// same call in internal/mobile/mobile.go so embed + server expose the same
	// curated set (§6.J registration-parity guard).
	curated.RegisterAll(registry)

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

	engine := apirouter.Build(apirouter.Deps{
		Cfg:        cfg,
		AuthLadder: authLadder,
		Cache:      c,
		Scraper:    scraper,
		Registry:   registry,
		Metrics:    metrics,
		PromReg:    metricsRegistry,
		Firebase:   fbClient,
		Readiness:  observability.ReadinessProbe(storeReady),
	})

	tlsConfig, err := loadTLSConfig(cfg.TLSCertPath, cfg.TLSKeyPath)
	if err != nil {
		return fmt.Errorf("tls: %w", err)
	}

	srv, err := server.New(server.Config{
		Listen:         cfg.Listen,
		MetricsListen:  cfg.MetricsListen,
		Engine:         engine,
		MetricsHandler: metrics.Handler(),
		TLSConfig:      tlsConfig,
		Listener:       publicListener,
	})
	if err != nil {
		_ = publicListener.Close()
		return fmt.Errorf("server: %w", err)
	}

	// mDNS-advertised port. See resolveMDNSPort's doc comment: fixed-port
	// mode is unchanged; dynamic-port mode advertises the REAL bound port
	// instead of whatever LAVA_API_MDNS_PORT was configured to, because a
	// fixed value would otherwise advertise a port nothing is listening on.
	mdnsPort, overrodeConfiguredMDNSPort := resolveMDNSPort(cfg.MDNSPort, cfg.Listen, dynamicPort)
	if overrodeConfiguredMDNSPort {
		logger.Info("dynamic public port mode: ignoring configured LAVA_API_MDNS_PORT in favor of the real bound port",
			"configured_mdns_port", cfg.MDNSPort, "actual_bound_port", mdnsPort)
	}

	mdnsService, mdnsErr := discovery.Announce(cfg.MDNSInstanceName, cfg.MDNSServiceType, mdnsPort)
	if mdnsErr != nil {
		logger.Warn("mDNS announcement failed; LAN discovery disabled", "err", mdnsErr)
	} else {
		logger.Info("mDNS announced", "instance", cfg.MDNSInstanceName, "type", cfg.MDNSServiceType, "port", mdnsPort)
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

// resolveMDNSPort decides which port to advertise via mDNS.
//
// In fixed-listen mode (dynamic == false) it returns configuredMDNSPort
// unchanged — byte-for-byte the existing, pre-dynamic-port behavior.
//
// In dynamic-listen mode (dynamic == true, i.e. LAVA_API_LISTEN requested
// ":0") it parses the REAL bound port out of resolvedListen (the value
// server.ResolveListen returned, already substituted into cfg.Listen by the
// caller) and returns that instead, ignoring configuredMDNSPort: a fixed,
// operator-configured mDNS port would otherwise advertise a port nothing is
// actually listening on once the public port itself is chosen by the OS.
//
// This function performs no I/O and does no logging itself — it is the
// pure decision the caller then logs and acts on — specifically so it can
// be unit-tested directly (dial a real socket / start real mDNS broadcast
// is neither necessary nor sufficient to prove this wiring decision is
// correct; this is the "config/wiring-decision level" the decision
// genuinely lives at).
//
// The second return value reports whether an operator-configured, non-zero
// configuredMDNSPort was overridden by a DIFFERENT real port, so the caller
// can log that override instead of silently diverging from what the
// operator asked for.
func resolveMDNSPort(configuredMDNSPort int, resolvedListen string, dynamic bool) (port int, overrodeConfigured bool) {
	if !dynamic {
		return configuredMDNSPort, false
	}
	_, portStr, err := net.SplitHostPort(resolvedListen)
	if err != nil {
		// no-telemetry: this function is deliberately pure/no-I/O (see the
		// doc comment above) so it stays unit-testable without a real
		// network or logger; resolvedListen is validated by
		// server.ResolveListen before this is ever called, so this is a
		// caller-invariant violation, not a runtime condition a real
		// deployment can hit. Adding observability.RecordNonFatal here
		// would require threading a context into a function whose whole
		// reason for existing is to be a pure decision the caller logs and
		// acts on (per §6.AC, the CALLER already logs the dynamic-port
		// path and any override — see run()'s "dynamic public port mode:
		// ignoring configured LAVA_API_MDNS_PORT" log line right after
		// this function returns). Fall back to the configured value
		// rather than advertising a nonsensical port.
		return configuredMDNSPort, false
	}
	actualPort, err := strconv.Atoi(portStr)
	if err != nil {
		// no-telemetry: same rationale as the SplitHostPort error path above.
		return configuredMDNSPort, false
	}
	return actualPort, configuredMDNSPort != 0 && configuredMDNSPort != actualPort
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
                          Set the port to 0 (e.g. ":0") to bind a real,
                          OS-assigned free port instead of a fixed one.
  LAVA_API_METRICS_LISTEN Private metrics listener (default: 127.0.0.1:9091)
  LAVA_API_TLS_CERT       TLS certificate path (required)
  LAVA_API_TLS_KEY        TLS private-key path (required)
  LAVA_API_OTLP_ENDPOINT  OTLP tracing exporter URL (optional)
  LAVA_API_MDNS_INSTANCE  mDNS instance name (default: Lava API)
  LAVA_API_MDNS_TYPE      mDNS service type (default: _lava-api._tcp)
  LAVA_API_MDNS_PORT      mDNS advertised port (default: 8443)
                          Ignored when LAVA_API_LISTEN requests a dynamic
                          port (":0") — the real bound port is advertised.
  LAVA_API_RUTRACKER_URL  rutracker.org base URL (default: https://rutracker.org)
`, version.Name)
}
