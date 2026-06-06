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
// Trust boundary (security-review finding 1): because the embed IS
// network-exposed, it MUST authenticate. It enforces the SAME Lava-Auth gate
// the production LAN binary uses (internal/auth.NewMiddleware +
// NewBackoffMiddleware over the shared internal/config.Config) — there is NO
// "trusted LAN" shortcut. The host Android app supplies the auth key via the
// Start config; if none is supplied the embed GENERATES one and surfaces it via
// Status() (authKey field) so the app always has a key to display. A request
// without the valid credential is rejected with 401 by the identical middleware
// host instances run; a request with it is served. Persistence of the key
// across restarts and the UI that shows it are the Android app's responsibility
// (a later sub-project); this surface only ENFORCES whatever key is in effect
// and reports it.
//
// TLS: a self-signed cert+key is generated on first boot into the SQLite
// directory (cert.pem / key.pem alongside the DB) and persisted across
// restarts. The cert's SANs cover the loopback addresses and the host's
// non-loopback LAN IPs discovered at boot (IP SANs), plus the "localhost" DNS
// name; the bare DNS wildcard was removed per the 2026-06-02 security review, so
// devices address this server by its LAN IP (matched via the IP SANs). Full
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
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
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
	"digital.vasic.ratelimiter/pkg/ladder"
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
	// embedClientName labels the single auto-provisioned client identity the
	// embed accepts. It is purely cosmetic (surfaces as c.Set("client_name"));
	// it is NOT a secret.
	embedClientName = "lava-embed-client"
	// hmacSecretLen is the byte length of the auto-generated HMAC secret. The
	// config validator requires ≥16; 32 bytes (256 bits) is the SHA-256 block
	// size and the production recommendation.
	hmacSecretLen = 32
	// uuidLen is the byte length of the auth key the host app presents. The
	// production middleware base64-decodes the header into a UUID blob and
	// HMACs it; a 16-byte (128-bit) random value is the production UUID width.
	uuidLen = 16
)

// startConfig is the DTO parsed from the Start(configJSON) argument. It is an
// internal type (never crosses the gobind boundary) so it may use rich Go
// types freely.
//
// AuthSharedKey is the credential the LAN clients present in the AuthFieldName
// header. It is the base64 encoding of a UUID blob (the production wire shape:
// the middleware base64-decodes the header, then HMACs the bytes). When empty,
// Start GENERATES one and surfaces it via Status().authKey (security-review
// finding 1: the embed is authenticated by default; there is no unauthenticated
// mode).
//
// AuthFieldName is the HTTP header name the credential is read from. When empty
// it defaults to defaultAuthFieldName. It matches the production
// cfg.AuthFieldName mechanism exactly.
type startConfig struct {
	BindAddr      string `json:"bindAddr"`
	Port          int    `json:"port"`
	SQLitePath    string `json:"sqlitePath"`
	AuthSharedKey string `json:"authSharedKey"`
	AuthFieldName string `json:"authFieldName"`
}

// instance holds the running server state. A nil package-level pointer means
// "stopped".
type instance struct {
	httpSrv       *http.Server
	store         storage.Storage
	bindAddr      string
	port          int
	reqCount      *int64
	authKey       string // the in-effect base64 UUID credential clients present
	authFieldName string // the header name clients send the credential in
}

// defaultAuthFieldName is the HTTP header the embed reads the Lava-Auth
// credential from when the host app does not supply one (sc.AuthFieldName ==
// "" in the Start config). It matches the header the production binary reads
// (cfg.AuthFieldName from LAVA_AUTH_FIELD_NAME).
//
// Per §6.R (No-Hardcoding): the field name MUST NOT be a source literal —
// otherwise it silently shadows whatever an operator sets in LAVA_AUTH_FIELD_NAME.
// It is injected at build time via `-ldflags "-X
// digital.vasic.lava.apigo/internal/mobile.defaultAuthFieldName=$LAVA_AUTH_FIELD_NAME"`
// (the Go-idiomatic equivalent of §6.R's allowed "generated config reading
// .env"). When NOT injected it is empty; in that case Start REQUIRES the host
// to pass a non-empty authFieldName and fail-fasts otherwise — the auth gate is
// NEVER served with an empty header name.
var defaultAuthFieldName string

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

	authFieldName := sc.AuthFieldName
	if authFieldName == "" {
		authFieldName = defaultAuthFieldName
	}
	// Fail-fast if neither the host config nor the build-time injected default
	// supplies a field name. Serving with an empty header name would disable the
	// auth gate (every request would carry the "" header), which the embed's
	// security posture forbids — there is no unauthenticated mode. This mirrors
	// production's config.Load fail-fast on a missing LAVA_AUTH_FIELD_NAME
	// (TestLoad_RejectsMissingAuthFieldName).
	if authFieldName == "" {
		return errors.New("mobile: authFieldName is required " +
			"(pass it in the Start config, or build with " +
			"-ldflags -X .../internal/mobile.defaultAuthFieldName=$LAVA_AUTH_FIELD_NAME)")
	}

	// Resolve the auth key. The host app supplies it via authSharedKey; if it
	// does not, GENERATE one (security-review finding 1: the embed is
	// authenticated by default — there is no unauthenticated mode). Either way
	// the embed enforces the SAME Lava-Auth gate the production binary uses and
	// surfaces the in-effect key via Status() so the app can display it.
	authKey := sc.AuthSharedKey
	if authKey == "" {
		generated, genErr := generateAuthKey()
		if genErr != nil {
			return fmt.Errorf("mobile: generate auth key: %w", genErr)
		}
		authKey = generated
	}

	// Build a config.Config wired with the EXACT auth fields the production
	// internal/auth middleware consumes (AuthFieldName + AuthHMACSecret +
	// AuthActiveClients), so the embed enforces the identical gate host
	// instances run. We force the SQLite backend — the embedded surface never
	// connects to Postgres. We do NOT call config.Load (env-var driven); we
	// construct exactly the fields the router's auth + storage paths consume.
	//
	// ProtocolMetric/Brotli/AltSvc are explicitly disabled: those success-path
	// middlewares are transport-tuning for the LAN binary, not security; the
	// embed keeps them off so it does not touch the prometheus default
	// registerer across repeated in-process Start/Stop cycles. The auth +
	// backoff gate is the security-relevant chain and is fully enabled.
	authCfg, err := buildAuthConfig(authKey, authFieldName)
	if err != nil {
		return fmt.Errorf("mobile: build auth config: %w", err)
	}
	cfg := authCfg
	cfg.StorageBackend = config.BackendSQLite
	cfg.SQLitePath = sc.SQLitePath

	store, ready, err := storage.New(cfg)
	if err != nil {
		return fmt.Errorf("mobile: storage init: %w", err)
	}

	var reqCount int64
	engine := buildRouter(cfg, store, observability.ReadinessProbe(ready))
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
		httpSrv:       httpSrv,
		store:         store,
		bindAddr:      sc.BindAddr,
		port:          sc.Port,
		reqCount:      &reqCount,
		authKey:       authKey,
		authFieldName: authFieldName,
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
		// SourceHash is the 64-hex sha256 of the lava-api-go source codebase
		// compiled into THIS .so (version.SourceHash, ldflags-injected by
		// build-cshared.sh). It is reported whether running or stopped so the
		// on-device sync Challenge can read the embed's source identity and
		// assert it matches the host APK's BuildConfig.LAVA_API_SOURCE_HASH
		// (no drift). Empty when the .so was built without injection.
		SourceHash string `json:"sourceHash,omitempty"`
		// AuthEnabled is always true while running — the embed enforces the
		// Lava-Auth gate unconditionally (security-review finding 1).
		AuthEnabled bool `json:"authEnabled"`
		// AuthFieldName is the HTTP header clients present the credential in.
		AuthFieldName string `json:"authFieldName,omitempty"`
		// AuthKey is the in-effect credential clients must present (base64 UUID
		// blob). It is surfaced HERE — and ONLY here — so the host app can show
		// it to the user / configure peer devices. It is deliberately NEVER
		// written to a log line (§6.H / §6.AC redaction).
		AuthKey string `json:"authKey,omitempty"`
	}

	doc := statusDoc{
		State:      "stopped",
		Scheme:     scheme,
		Backend:    config.BackendSQLite,
		Version:    version.Name,
		SourceHash: version.SourceHash,
	}
	if current != nil {
		doc.State = "running"
		doc.BindAddr = current.bindAddr
		doc.Port = current.port
		doc.RequestCount = atomic.LoadInt64(current.reqCount)
		doc.AuthEnabled = true
		doc.AuthFieldName = current.authFieldName
		doc.AuthKey = current.authKey
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
// Cfg + AuthLadder are passed so router.Build wires the SAME Lava-Auth
// middleware chain (auth.NewBackoffMiddleware + auth.NewMiddleware) the
// production binary runs (security-review finding 1: the embed is
// network-exposed and MUST authenticate). The ladder is constructed from
// cfg.AuthBackoffSteps exactly as cmd/lava-api-go/main.go does, so a missing /
// wrong credential yields 401 and repeated failures escalate to 429 — identical
// host behavior. /health + /ready are registered BEFORE the auth middleware in
// router.Build, so the orchestrator probes still work without a credential.
func buildRouter(cfg *config.Config, store storage.Storage, ready observability.ReadinessProbe) *gin.Engine {
	scraper, registry := scraperFactory()

	// Shared ladder instance — BackoffMiddleware (front) and AuthMiddleware
	// (behind) MUST consume the same *ladder.Ladder, exactly as main.go wires.
	authLadder := ladder.New(cfg.AuthBackoffSteps)

	return apirouter.Build(apirouter.Deps{
		Cfg:        cfg,
		AuthLadder: authLadder,
		Cache:      store,
		Scraper:    scraper,
		Registry:   registry,
		Readiness:  ready,
	})
}

// generateAuthKey mints a fresh random auth credential in the production wire
// shape: base64(random 16-byte UUID blob). The production middleware
// base64-decodes the AuthFieldName header into a UUID blob, HMACs it, and
// compares against the active-clients map; this key is exactly such a blob.
func generateAuthKey() (string, error) {
	blob := make([]byte, uuidLen)
	if _, err := rand.Read(blob); err != nil {
		return "", fmt.Errorf("read random: %w", err)
	}
	return base64.StdEncoding.EncodeToString(blob), nil
}

// buildAuthConfig constructs a *config.Config whose auth fields make the
// production internal/auth middleware accept exactly the supplied authKey
// (base64 UUID blob) presented in the authFieldName header, and reject anything
// else.
//
// It mirrors the production load path: a random HMAC secret is generated, then
// the active-clients map is keyed by hex(HMAC-SHA256(uuid_bytes, secret)) — the
// SAME key derivation config.parseClientsList + auth.hashUUIDBlob perform. The
// secret never leaves the process and is never logged.
func buildAuthConfig(authKey, authFieldName string) (*config.Config, error) {
	blob, err := base64.StdEncoding.DecodeString(authKey)
	if err != nil {
		return nil, fmt.Errorf("authSharedKey is not valid base64: %w", err)
	}
	if len(blob) == 0 {
		return nil, errors.New("authSharedKey decodes to empty bytes")
	}

	secret := make([]byte, hmacSecretLen)
	if _, err := rand.Read(secret); err != nil {
		return nil, fmt.Errorf("read random secret: %w", err)
	}

	mac := hmac.New(sha256.New, secret)
	mac.Write(blob)
	activeHash := hex.EncodeToString(mac.Sum(nil))
	// Zeroize the plaintext blob per §6.H — never linger in memory.
	for i := range blob {
		blob[i] = 0
	}

	return &config.Config{
		AuthFieldName:     authFieldName,
		AuthHMACSecret:    secret,
		AuthActiveClients: map[string]string{activeHash: embedClientName},
		// No retired clients on the embed. The backoff ladder uses the same
		// default steps the production config validator applies so repeated bad
		// credentials escalate to 429 identically.
		AuthRetiredClients: map[string]string{},
		AuthBackoffSteps:   defaultBackoffSteps(),
		// Transport-tuning middlewares stay off (see Start's comment); they are
		// not security-relevant and avoid touching the prometheus default
		// registerer across repeated in-process Start/Stop cycles.
		ProtocolMetricEnabled: false,
		BrotliResponseEnabled: false,
		HTTP3Enabled:          false,
	}, nil
}

// defaultBackoffSteps returns the monotonic backoff ladder the embed uses. It
// matches the production default (LAVA_AUTH_BACKOFF_STEPS=2s,5s,10s,30s,1m,1h)
// so a credential-guessing attacker against the embed faces the same escalating
// 429 lockout host instances impose.
func defaultBackoffSteps() []time.Duration {
	return []time.Duration{
		2 * time.Second,
		5 * time.Second,
		10 * time.Second,
		30 * time.Second,
		time.Minute,
		time.Hour,
	}
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
