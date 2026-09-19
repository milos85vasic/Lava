// Package server hosts a Gin engine over HTTP/3 (via submodules/http3) on
// the public listener and a separate plain-HTTP /metrics listener on
// localhost. The split is mandated by spec §10: the metrics surface
// MUST be reachable only by the local Prometheus scraper, never by
// public clients.
//
// Decoupled Reusable rationale: this package contains no Lava-domain
// logic. It composes submodules/http3/pkg/server with a stdlib
// http.Server for the metrics port and exposes a Start / Shutdown
// lifecycle for cmd/lava-api-go to consume.
package server

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.containers/pkg/network"
	h3 "digital.vasic.http3/pkg/server"
)

// Config configures a Server.
type Config struct {
	Listen         string      // public listener, e.g. ":8443"
	MetricsListen  string      // private metrics listener, e.g. "127.0.0.1:9091"
	Engine         *gin.Engine // Lava-domain Gin router (handlers + middleware)
	MetricsHandler http.Handler
	TLSConfig      *tls.Config // HTTP/3 mandates TLS 1.3

	// Listener, when non-nil, is an ALREADY-BOUND TCP listener for the
	// public port (typically obtained from ResolveListen) that Start MUST
	// reuse via http.Server.ServeTLS instead of asking the stdlib to bind
	// Listen a second time. This is what closes the classic
	// allocate-a-port-then-rebind-it TOCTOU race for the HTTP/2 (TCP)
	// surface — see ResolveListen's doc comment. When nil, Start falls back
	// to the historical ListenAndServeTLS(addr) behavior (kept for callers,
	// including this package's own tests, that construct a Config directly
	// without pre-binding).
	Listener net.Listener
}

// Server hosts both the HTTP/3 public listener (UDP) AND an HTTP/2-over-TLS
// fallback (TCP) on the same port — per spec §8.1 — plus a plain-HTTP
// metrics listener. A single Shutdown call drains all three.
type Server struct {
	cfg     Config
	h3srv   *h3.Server
	h2srv   *http.Server // TCP HTTP/2 fallback on the same port as the HTTP/3 listener
	metrics *http.Server

	mu             sync.Mutex
	stopped        bool
	listened       net.Listener // resolved metrics listener (kept so Shutdown can close it)
	publicListener net.Listener // pre-bound public TCP listener, when Config.Listener was supplied (see ResolveListen)
}

// ResolveListen determines the REAL public listen address for addr and
// proves — via an actual synchronous bind, not a guess — that the address is
// available BEFORE the caller wires anything that bakes the address in
// (mDNS announcement, the Alt-Svc header, a "listening on" log line).
//
// This exists to fix two related problems in one place:
//
//  1. Dynamic port allocation. Go's own "OS assigns" convention is port 0.
//     When addr's port is literally "0" (e.g. ":0", "0.0.0.0:0"), ResolveListen
//     delegates to digital.vasic.containers/pkg/network.ListenEphemeral, which
//     performs a genuinely race-free ephemeral-port allocation (see that
//     function's doc comment: it never releases the OS's hold on the port
//     between allocation and use, unlike a check-then-use "find a free port,
//     close it, hand back the int" helper). The real, kernel-assigned port
//     replaces the literal "0" in the returned address.
//  2. The mDNS-before-bind ordering bug. Historically cmd/lava-api-go called
//     discovery.Announce (and wired the Alt-Svc middleware) using the
//     CONFIGURED address before any bind was even attempted — the actual
//     socket bind happened later, asynchronously, inside Server.Start's
//     goroutine. A collision (another process already holding the port, or —
//     as this session found — an unrelated project's container already
//     bound to the configured fixed port) would surface only after mDNS had
//     already told the LAN "come talk to me here" and the Alt-Svc header had
//     already advertised a port nothing was listening on. ResolveListen
//     performs the bind synchronously and returns an error if it fails, so
//     callers can refuse to advance to mDNS/Alt-Svc/logging on a port that
//     was never actually secured. This applies identically to a FIXED
//     address (e.g. ":8443") — it is bound here, up front, instead of later.
//
// The returned net.Listener is the TCP listener for the public port (backing
// the HTTP/2-over-TLS fallback). The caller MUST pass it to Server via
// Config.Listener so Start reuses it (http.Server.ServeTLS on an existing
// listener) instead of asking the stdlib to bind the same address again —
// that second bind is exactly the TOCTOU window this function exists to
// close for the TCP surface.
//
// ResolveListen ALSO probe-binds a UDP listener on the same resolved address
// (independent kernel namespace from TCP) and releases it immediately, to
// prove the number is free there too before the caller proceeds. This is a
// best-effort, honestly-documented mitigation, not a full guarantee: this
// project's HTTP/3 wrapper (digital.vasic.http3/pkg/server) does not accept a
// pre-bound net.PacketConn through its Config surface (only an address
// string, bound internally inside Server.Start via quic-go's
// http3.Server.ListenAndServe), so a small residual TOCTOU window remains on
// the UDP/HTTP-3 bind performed later. Extending that wrapper to accept an
// existing net.PacketConn (quic-go's http3.Server.Serve(net.PacketConn) DOES
// support this) would close that residual window fully but touches a
// separate submodule and is out of scope here.
//
// The returned bool reports whether addr requested dynamic allocation (port
// "0"), so callers can decide whether an operator-configured value that
// assumed a fixed port (e.g. LAVA_API_MDNS_PORT) should be overridden by the
// real bound port instead.
func ResolveListen(addr string) (resolved string, tcpListener net.Listener, dynamic bool, err error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", nil, false, fmt.Errorf("server: invalid listen address %q: %w", addr, err)
	}

	if port == "0" {
		ln, resolvedPort, lErr := network.ListenEphemeral(host)
		if lErr != nil {
			return "", nil, false, fmt.Errorf("server: dynamic port allocation on %q: %w", addr, lErr)
		}
		tcpListener = ln
		resolved = net.JoinHostPort(host, strconv.Itoa(resolvedPort))
		dynamic = true
	} else {
		ln, lErr := net.Listen("tcp", addr)
		if lErr != nil {
			return "", nil, false, fmt.Errorf("server: bind public TCP listener %q: %w", addr, lErr)
		}
		tcpListener = ln
		resolved = addr
	}

	// Probe-bind + release the UDP side of the SAME resolved port. See the
	// doc comment above for why this is a probe rather than a hand-off.
	udpProbe, uErr := net.ListenPacket("udp", resolved)
	if uErr != nil {
		_ = tcpListener.Close()
		return "", nil, false, fmt.Errorf("server: bind public UDP (HTTP/3) probe on %q: %w", resolved, uErr)
	}
	if cErr := udpProbe.Close(); cErr != nil {
		_ = tcpListener.Close()
		return "", nil, false, fmt.Errorf("server: release UDP probe on %q: %w", resolved, cErr)
	}

	return resolved, tcpListener, dynamic, nil
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
		cfg:            cfg,
		publicListener: cfg.Listener,
		h3srv:          h3srv,
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

	// HTTP/2 fallback (TCP/TLS) on the same port. Spec §8.1. When the
	// caller supplied an already-bound listener (via Config.Listener, the
	// normal path once main.go calls ResolveListen up front), reuse it via
	// ServeTLS instead of asking the stdlib to bind Addr a second time —
	// that second bind is exactly the TOCTOU window ResolveListen exists to
	// close. Falls back to ListenAndServeTLS for callers (including this
	// package's own tests) that construct a Config without pre-binding.
	go func() {
		var err error
		if s.publicListener != nil {
			err = s.h2srv.ServeTLS(s.publicListener, "", "")
		} else {
			err = s.h2srv.ListenAndServeTLS("", "")
		}
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
