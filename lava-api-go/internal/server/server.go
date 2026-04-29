// Package server hosts a Gin engine over HTTP/3 (via Submodules/HTTP3) on
// the public listener and a separate plain-HTTP /metrics listener on
// localhost. The split is mandated by spec §10: the metrics surface
// MUST be reachable only by the local Prometheus scraper, never by
// public clients.
//
// Decoupled Reusable rationale: this package contains no Lava-domain
// logic. It composes Submodules/HTTP3/pkg/server with a stdlib
// http.Server for the metrics port and exposes a Start / Shutdown
// lifecycle for cmd/lava-api-go to consume.
package server

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	h3 "digital.vasic.http3/pkg/server"
)

// Config configures a Server.
type Config struct {
	Listen         string      // public listener, e.g. ":8443"
	MetricsListen  string      // private metrics listener, e.g. "127.0.0.1:9091"
	Engine         *gin.Engine // Lava-domain Gin router (handlers + middleware)
	MetricsHandler http.Handler
	TLSConfig      *tls.Config // HTTP/3 mandates TLS 1.3
}

// Server hosts both the HTTP/3 public listener (UDP) AND an HTTP/2-over-TLS
// fallback (TCP) on the same port — per spec §8.1 — plus a plain-HTTP
// metrics listener. A single Shutdown call drains all three.
type Server struct {
	cfg     Config
	h3srv   *h3.Server
	h2srv   *http.Server // TCP HTTP/2 fallback on the same port as the HTTP/3 listener
	metrics *http.Server

	mu       sync.Mutex
	stopped  bool
	listened net.Listener // resolved metrics listener (kept so Shutdown can close it)
}

// New constructs a Server from a validated Config.
func New(cfg Config) (*Server, error) {
	if cfg.Engine == nil {
		return nil, errors.New("server: Engine is required")
	}
	if cfg.MetricsHandler == nil {
		return nil, errors.New("server: MetricsHandler is required")
	}
	if cfg.TLSConfig == nil {
		return nil, errors.New("server: TLSConfig is required")
	}
	h3srv, err := h3.New(h3.Config{
		Addr:    cfg.Listen,
		Handler: cfg.Engine,
		TLSConf: cfg.TLSConfig,
	})
	if err != nil {
		return nil, err
	}
	// HTTP/2 fallback over TLS on the same port (TCP). Spec §8.1:
	//   TCP: HTTP/2 fallback (TLS 1.3 only)
	// Reuses the same TLSConfig so the cert and protocol set match the
	// HTTP/3 listener. Required for clients that don't speak HTTP/3
	// (curl without --http3, k6, browser fallback paths).
	h2cfg := cfg.TLSConfig.Clone()
	h2cfg.NextProtos = []string{"h2", "http/1.1"}
	return &Server{
		cfg:   cfg,
		h3srv: h3srv,
		h2srv: &http.Server{
			Addr:              cfg.Listen,
			Handler:           cfg.Engine,
			TLSConfig:         h2cfg,
			ReadHeaderTimeout: 5 * time.Second,
		},
		metrics: &http.Server{
			Addr:              cfg.MetricsListen,
			Handler:           cfg.MetricsHandler,
			ReadHeaderTimeout: 5 * time.Second,
		},
	}, nil
}

// Start binds both listeners and serves until Shutdown is called or
// either listener returns an unrecoverable error. The returned error
// is nil for a clean shutdown and non-nil for any other terminating
// condition. Start blocks; the caller is expected to invoke it from a
// goroutine and trigger Shutdown from the main thread.
func (s *Server) Start() error {
	errCh := make(chan error, 3)

	// HTTP/3 listener (UDP/QUIC).
	go func() {
		errCh <- s.h3srv.Start()
	}()

	// HTTP/2 fallback (TCP/TLS) on the same port. Spec §8.1.
	go func() {
		err := s.h2srv.ListenAndServeTLS("", "")
		if errors.Is(err, http.ErrServerClosed) {
			err = nil
		}
		errCh <- err
	}()

	// Plain-HTTP metrics listener.
	go func() {
		ln, err := net.Listen("tcp", s.cfg.MetricsListen)
		if err != nil {
			errCh <- err
			return
		}
		s.mu.Lock()
		s.listened = ln
		s.mu.Unlock()
		err = s.metrics.Serve(ln)
		if errors.Is(err, http.ErrServerClosed) {
			err = nil
		}
		errCh <- err
	}()

	return <-errCh
}

// Shutdown drains both listeners. It is idempotent and safe to call
// from any goroutine.
func (s *Server) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	if s.stopped {
		s.mu.Unlock()
		return nil
	}
	s.stopped = true
	s.mu.Unlock()

	// Best-effort metrics + HTTP/2 fallback shutdown — stdlib
	// http.Server.Shutdown blocks until in-flight requests drain or ctx
	// expires.
	_ = s.metrics.Shutdown(ctx)
	_ = s.h2srv.Shutdown(ctx)

	// HTTP/3 shutdown closes the QUIC listener.
	return s.h3srv.Shutdown(ctx)
}

// Addr returns the configured public listen address.
func (s *Server) Addr() string { return s.cfg.Listen }

// MetricsAddr returns the configured metrics listen address.
func (s *Server) MetricsAddr() string { return s.cfg.MetricsListen }
