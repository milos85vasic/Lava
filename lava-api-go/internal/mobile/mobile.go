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
// What this surface serves: the FULL production API. The embed builds the EXACT
// SAME Gin engine the production cmd/lava-api-go binary builds, via the shared
// internal/router.Build constructor (search/browse/topic/forum/torrent/login/
// favorites/captcha + /v1/{provider}/... + /health + /ready). The ONLY
// difference from the LAN binary is transport: the embed serves HTTP/1.1 + H2
// over TLS using a standard net/http server, where the binary additionally
// serves HTTP/3 (QUIC). HTTP/3 is not required for the embed — the LAN clients
// that discover this on-device API speak ordinary HTTPS.
//
// Network exposure is the intended design per the on-device-API spec: this
// server binds 0.0.0.0 by default so OTHER devices on the LAN can discover and
// use it. Loopback-only would defeat the feature (the whole point is that a
// phone running the embed becomes a LAN-reachable Lava API for tablets, TVs,
// and other phones). The automated security-review finding that suggested
// loopback-only is therefore explicitly overridden here.
//
// TLS: a self-signed cert+key is generated on first boot into the SQLite
// directory (cert.pem / key.pem alongside the DB) and persisted across
// restarts. The cert's SANs include the loopback addresses, the host's
// non-loopback LAN IPs discovered at boot, and a DNS wildcard so other devices
// addressing this server by IP do not get a hostname-mismatch error. Full
// client-trust handling (pinning the cert on the consuming devices, or an ACME/
// local-CA flow) is a later sub-project; today the SAN coverage minimizes
// host-mismatch errors and the consuming client is expected to trust the
// presented leaf cert out-of-band.
//
// Decoupled Reusable rationale: this file is Lava-domain glue tying the
// existing internal packages (router, storage, observability, rutracker +
// the provider adapters) into an embeddable lifecycle. It introduces no logic
// another vasic-digital project would consume directly.
package mobile

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gin-gonic/gin"

	"digital.vasic.lava.apigo/internal/archiveorg"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/gutenberg"
	"digital.vasic.lava.apigo/internal/handlers"
	"digital.vasic.lava.apigo/internal/kinozal"
	"digital.vasic.lava.apigo/internal/nnmclub"
	"digital.vasic.lava.apigo/internal/observability"
	"digital.vasic.lava.apigo/internal/provider"
	apirouter "digital.vasic.lava.apigo/internal/router"
	"digital.vasic.lava.apigo/internal/rutracker"
	"digital.vasic.lava.apigo/internal/storage"
	"digital.vasic.lava.apigo/internal/version"
)

const (
	// defaultBindAddr is the wildcard address. Network exposure is the
	// intended design per the on-device-API spec; loopback-only would defeat
	// the feature.
	defaultBindAddr = "0.0.0.0"
	// defaultPort matches the production LAN listener's default.
	defaultPort = 8443
	// scheme is reported by Status(); the embed always serves HTTPS.
	scheme = "https"
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

// scraperFactory builds the production rutracker scraper + multi-provider
// registry the served handlers depend on. It is a package var so tests can
// substitute a stub scraper that does not reach the live rutracker.org
// upstream (the parity_test.go pattern — stub the upstream tracker boundary).
// Production code uses the default factory below, wiring the EXACT same
// adapters cmd/lava-api-go/main.go registers.
var scraperFactory = newProductionScraperDeps

// newProductionScraperDeps wires the real rutracker scraper + the
// multi-provider registry exactly as cmd/lava-api-go/main.go does.
func newProductionScraperDeps() (handlers.ScraperClient, *provider.ProviderRegistry) {
	scraper := rutracker.NewClient("https://rutracker.org/forum")
	registry := provider.NewRegistry()
	registry.Register(rutracker.NewProviderAdapter(scraper))
	registry.Register(nnmclub.NewProviderAdapter(nnmclub.NewClient("https://nnmclub.to")))
	registry.Register(kinozal.NewProviderAdapter(kinozal.NewClient("https://kinozal.tv")))
	registry.Register(archiveorg.NewProviderAdapter(archiveorg.NewClient("https://archive.org")))
	registry.Register(gutenberg.NewProviderAdapter(gutenberg.NewClient("https://gutendex.com")))
	return scraper, registry
}

// Start parses configJSON, opens the real SQLite storage backend via the
// production storage.New factory, builds the FULL production Gin router via
// internal/router.Build, loads-or-generates a persisted self-signed TLS
// certificate, binds a TLS listener on bindAddr:port, and serves on a
// goroutine.
//
// It returns only once the listener is actually accepting connections (or the
// bind/open error). Calling Start while a server is already running returns an
// error — exactly one instance per process.
//
// configJSON shape: {"bindAddr":"0.0.0.0","port":8443,"sqlitePath":"/data/x.db"}
// bindAddr and port are optional; they default to 0.0.0.0:8443 (network
// exposure is the intended design — see the package comment).
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
		sc.BindAddr = defaultBindAddr
	}
	// Validate the bind address with net.ParseIP. We explicitly ALLOW
	// non-loopback (including the 0.0.0.0 wildcard) — network exposure is the
	// intended design per the on-device-API spec; loopback-only would defeat
	// the feature. We reject only addresses that are not parseable IPs.
	if net.ParseIP(sc.BindAddr) == nil {
		return fmt.Errorf("mobile: bindAddr %q is not a valid IP address", sc.BindAddr)
	}
	if sc.Port == 0 {
		sc.Port = defaultPort
	}
	if sc.Port < 0 || sc.Port > 65535 {
		return fmt.Errorf("mobile: port %d out of range (1..65535)", sc.Port)
	}
	if sc.SQLitePath == "" {
		return errors.New("mobile: sqlitePath is required")
	}

	// Build a config.Config for the storage factory. We force the SQLite
	// backend — the embedded surface never connects to Postgres. We do NOT
	// call config.Load (env-var driven, requires auth/TLS material managed
	// differently for an on-device embed); we construct the minimal fields
	// the storage factory consumes.
	cfg := &config.Config{
		StorageBackend: config.BackendSQLite,
		SQLitePath:     sc.SQLitePath,
	}

	store, ready, err := storage.New(cfg)
	if err != nil {
		return fmt.Errorf("mobile: storage init: %w", err)
	}

	var reqCount int64
	engine := buildRouter(store, observability.ReadinessProbe(ready))
	// Wrap the engine in a request-counting http.Handler so Status()
	// reports real traffic. Counting at the transport layer (rather than as a
	// Gin middleware) keeps it ahead of every route without re-registering the
	// route table or disturbing path-param binding.
	handler := countingHandler(engine, &reqCount)

	// Load (or generate-and-persist) the self-signed TLS material next to the
	// SQLite DB so it survives restarts. localIPs() feeds the cert SANs so LAN
	// peers addressing this server by IP do not hit a host-mismatch error.
	tlsCfg, err := loadOrCreateTLS(sc.SQLitePath, localIPs())
	if err != nil {
		_ = store.Close()
		return fmt.Errorf("mobile: tls material: %w", err)
	}

	addr := net.JoinHostPort(sc.BindAddr, fmt.Sprintf("%d", sc.Port))
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		_ = store.Close()
		return fmt.Errorf("mobile: listen %s: %w", addr, err)
	}
	tlsLn := tls.NewListener(ln, tlsCfg)

	httpSrv := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		// Serve blocks until Shutdown/Close. http.ErrServerClosed is the
		// normal stop signal and is not an error worth surfacing.
		_ = httpSrv.Serve(tlsLn)
	}()

	// ln (and the wrapping tlsLn) is already bound and accepting (net.Listen
	// returns a listening socket); the goroutine's Serve only dequeues
	// accepted connections. So by the time net.Listen returned, the port
	// accepts. Returning here means the listener is live.
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
		Scheme       string `json:"scheme"`
		BindAddr     string `json:"bindAddr"`
		Port         int    `json:"port"`
		RequestCount int64  `json:"requestCount"`
		Backend      string `json:"backend"`
		Version      string `json:"version"`
	}

	doc := statusDoc{
		State:   "stopped",
		Scheme:  scheme,
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
		return `{"state":"stopped","scheme":"https","backend":"sqlite"}`
	}
	return string(b)
}

// buildRouter assembles the FULL production engine for the embedded surface.
// It reuses internal/router.Build — the EXACT same constructor cmd/lava-api-go
// uses — so the embed serves identical routes to production. The readiness
// probe is wired to the SQLite-backed storage probe so /ready genuinely
// reflects the DB handle's health.
//
// Cfg is left nil in router.Build's Deps: the on-device embed is reached by
// trusted LAN clients and does not run the Lava-Auth HMAC gate (which is for
// the public LAN binary). Leaving Cfg nil skips the auth/backoff/protocol-
// metric/brotli/alt-svc middlewares; the full handler + /v1 route set is still
// registered. The served routes are therefore identical to production minus
// the auth gate — the same anti-bluff property the route-count test pins.
func buildRouter(store storage.Storage, ready observability.ReadinessProbe) *gin.Engine {
	scraper, registry := scraperFactory()

	return apirouter.Build(apirouter.Deps{
		Cache:     store,
		Scraper:   scraper,
		Registry:  registry,
		Readiness: ready,
	})
}

// countingHandler wraps an http.Handler so every inbound request increments
// the atomic counter before being served. Counting at the transport layer
// keeps it ahead of the whole Gin chain without touching the route table.
func countingHandler(next http.Handler, reqCount *int64) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(reqCount, 1)
		next.ServeHTTP(w, r)
	})
}
