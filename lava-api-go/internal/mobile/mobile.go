// Package mobile is the gomobile/JNI-friendly in-process surface for embedding
// the lava-api-go server inside the Android client (Phase B of the Lava API
// Android app plan).
//
// Design constraints (gomobile/gobind compatibility):
//
//   - Only string/error types cross the exported boundary. gobind maps Go
//     string→Java String and error→Java exception; richer types (structs,
//     slices, maps, channels) either cannot be bound or bind into awkward
//     generated wrappers. Configuration therefore arrives as a JSON string;
//     status is returned as a JSON string.
//   - Exactly one server instance may run per process. A package-level handle
//     guarded by a mutex enforces this; Start while running returns an error.
//
// Transport choice (documented per the Phase B task): the production
// cmd/lava-api-go binary serves HTTP/3 + HTTP/2-over-TLS on the LAN, requiring
// on-disk TLS material and UDP/QUIC sockets. Neither is appropriate for an
// in-process embed that the host Android app reaches over loopback. This
// surface therefore serves PLAIN HTTP on the configured loopback bind address.
// It is NOT a LAN listener: it is an in-app local server the client talks to
// over 127.0.0.1, so TLS would only add a self-signed-cert dance with no
// security benefit on loopback. The real storage backend (SQLite via the
// production storage.New factory) and the real /health + /ready handlers are
// used unchanged — only the transport differs from the LAN binary, by design.
//
// Decoupled Reusable rationale: this file is Lava-domain glue tying the
// existing internal packages (storage, observability) into an embeddable
// lifecycle. It introduces no logic another vasic-digital project would
// consume directly.
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/storage"
	"digital.vasic.lava.apigo/internal/version"
)

// startConfig is the DTO parsed from the Start(configJSON) argument. It is an
// internal type (never crosses the gobind boundary) so it may use rich Go
// types freely.
type startConfig struct {
	BindAddr   string `json:"bindAddr"`
	Port       int    `json:"port"`
	SQLitePath string `json:"sqlitePath"`
}

// instance holds the running server state. A nil package-level pointer means
// "stopped".
type instance struct {
	httpSrv  *http.Server
	store    storage.Storage
	bindAddr string
	port     int
	reqCount *int64
}

var (
	mu      sync.Mutex
	current *instance
)

// Start parses configJSON, opens the real SQLite storage backend via the
// production storage.New factory, builds the real /health + /ready Gin router,
// binds a plain-HTTP listener on bindAddr:port, and serves on a goroutine.
//
// It returns only once the listener is actually accepting connections (or the
// bind/open error). Calling Start while a server is already running returns an
// error — exactly one instance per process.
//
// configJSON shape: {"bindAddr":"127.0.0.1","port":8099,"sqlitePath":"/data/x.db"}
func Start(configJSON string) error {
	mu.Lock()
	defer mu.Unlock()

	if current != nil {
		return fmt.Errorf("mobile: server already running on %s:%d (Stop first)", current.bindAddr, current.port)
	}

	var sc startConfig
	if err := json.Unmarshal([]byte(configJSON), &sc); err != nil {
		return fmt.Errorf("mobile: parse configJSON: %w", err)
	}
	if sc.BindAddr == "" {
		return errors.New("mobile: bindAddr is required")
	}
	if sc.Port <= 0 || sc.Port > 65535 {
		return fmt.Errorf("mobile: port %d out of range (1..65535)", sc.Port)
	}
	if sc.SQLitePath == "" {
		return errors.New("mobile: sqlitePath is required")
	}

	// Build a config.Config for the storage factory. We force the SQLite
	// backend — the embedded surface never connects to Postgres. We do NOT
	// call config.Load (env-var driven, requires auth/TLS material that is
	// meaningless for an in-process loopback embed); we construct the minimal
	// fields the storage factory consumes.
	cfg := &config.Config{
		StorageBackend: config.BackendSQLite,
		SQLitePath:     sc.SQLitePath,
	}

	store, ready, err := storage.New(cfg)
	if err != nil {
		return fmt.Errorf("mobile: storage init: %w", err)
	}

	var reqCount int64
	router := buildRouter(observability.ReadinessProbe(ready), &reqCount)

	addr := fmt.Sprintf("%s:%d", sc.BindAddr, sc.Port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		_ = store.Close()
		return fmt.Errorf("mobile: listen %s: %w", addr, err)
	}

	httpSrv := &http.Server{
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		// Serve blocks until Shutdown/Close. http.ErrServerClosed is the
		// normal stop signal and is not an error worth surfacing.
		_ = httpSrv.Serve(ln)
	}()

	// ln is already bound and accepting (net.Listen returns a listening
	// socket); the goroutine's Serve only dequeues accepted connections. So
	// by the time net.Listen returned, the port accepts. Returning here means
	// the listener is live.
	current = &instance{
		httpSrv:  httpSrv,
		store:    store,
		bindAddr: sc.BindAddr,
		port:     sc.Port,
		reqCount: &reqCount,
	}
	return nil
}

// Stop gracefully shuts the running server down and closes the storage handle.
// It returns an error when no server is running (chosen contract: Stop is NOT
// a silent no-op so the caller can detect double-stop / stop-without-start
// mistakes).
func Stop() error {
	mu.Lock()
	defer mu.Unlock()

	if current == nil {
		return errors.New("mobile: no server running")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	shutdownErr := current.httpSrv.Shutdown(ctx)
	closeErr := current.store.Close()
	current = nil

	if shutdownErr != nil {
		return fmt.Errorf("mobile: shutdown: %w", shutdownErr)
	}
	if closeErr != nil {
		return fmt.Errorf("mobile: storage close: %w", closeErr)
	}
	return nil
}

// Status returns a JSON document describing the current server state. It never
// errors (gobind-friendly): on any internal marshalling failure it returns a
// minimal stopped document.
func Status() string {
	mu.Lock()
	defer mu.Unlock()

	type statusDoc struct {
		State        string `json:"state"`
		BindAddr     string `json:"bindAddr"`
		Port         int    `json:"port"`
		RequestCount int64  `json:"requestCount"`
		Backend      string `json:"backend"`
		Version      string `json:"version"`
	}

	doc := statusDoc{
		State:   "stopped",
		Backend: config.BackendSQLite,
		Version: version.Name,
	}
	if current != nil {
		doc.State = "running"
		doc.BindAddr = current.bindAddr
		doc.Port = current.port
		doc.RequestCount = atomic.LoadInt64(current.reqCount)
	}

	b, err := json.Marshal(doc)
	if err != nil {
		return `{"state":"stopped","backend":"sqlite"}`
	}
	return string(b)
}

// buildRouter assembles the minimal Gin engine for the embedded surface: a
// request-counting middleware + the real liveness/readiness handlers. The
// readiness handler is wired to the storage-backed probe so /ready genuinely
// reflects the SQLite handle's health.
func buildRouter(ready observability.ReadinessProbe, reqCount *int64) *gin.Engine {
	gin.SetMode(gin.ReleaseMode)
	router := gin.New()
	router.Use(gin.Recovery())
	router.Use(func(c *gin.Context) {
		atomic.AddInt64(reqCount, 1)
		c.Next()
	})
	router.GET("/health", observability.LivenessHandler())
	router.GET("/ready", observability.ReadinessHandler(ready))
	return router
}
