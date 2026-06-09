package router

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"

	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.ratelimiter/pkg/ladder"
)

// router_config_wiring_test.go covers the conditional production-wiring
// branches of router.Build that the route-resolution tests in router_test.go
// leave at 0%: those tests build with Deps{Cache, Scraper} only, so the
// Cfg/AuthLadder/Metrics/Jackett-gated registrations never execute. A real
// composition root (cmd/lava-api-go, internal/mobile) DOES pass a non-nil Cfg
// with Jackett enabled — so a regression in that path ships to production
// invisibly today.
//
// These tests use the SAME production Build with a real *config.Config; the
// only stubs are the handler-boundary Cache/Scraper already defined in
// router_test.go (stubCache/stubScraper) — no mocking of router internals.

// fullCfg returns a Config that turns on every conditional branch in Build so
// the protocol-metric, auth+backoff, metrics, brotli, and alt-svc middlewares
// are all mounted. Values are synthetic and never touch the network at Build
// time (the middlewares are only INVOKED on a request, and the routes asserted
// on — /health, /jackett/search registration — don't require live upstreams).
func fullCfg() *config.Config {
	return &config.Config{
		ProtocolMetricEnabled: true,
		BrotliResponseEnabled: true,
		BrotliQuality:         4,
		HTTP3Enabled:          true,
		Listen:                ":8443",
		AuthTrustedProxies:    nil,
	}
}

// TestBuild_JackettRouteRegisteredWhenEnabled is the load-bearing assertion:
// when Cfg.JackettEnabled is true AND BaseURL+APIKey are present, GET
// /jackett/search MUST appear in the engine's route table. The primary
// assertion is on the real registered-route inventory (the route a client can
// reach), not on "Build returned non-nil".
//
// Falsifiability: delete `engine.GET("/jackett/search", jh.GetSearch)` in
// router.go, or change the path/method, and this test fails with
// "GET /jackett/search not registered when JackettEnabled=true".
func TestBuild_JackettRouteRegisteredWhenEnabled(t *testing.T) {
	cfg := fullCfg()
	cfg.JackettEnabled = true
	cfg.JackettBaseURL = "http://jackett.test:9117"
	cfg.JackettAPIKey = "test-api-key-not-a-real-secret"
	cfg.JackettDefaultIndexer = "all"

	engine := Build(Deps{
		Cfg:        cfg,
		Cache:      stubCache{},
		Scraper:    stubScraper{},
		PromReg:    prometheus.NewRegistry(),
		AuthLadder: ladder.New([]time.Duration{time.Second}),
	})

	found := false
	for _, ri := range engine.Routes() {
		if ri.Method == http.MethodGet && ri.Path == "/jackett/search" {
			found = true
			break
		}
	}
	if !found {
		var got []string
		for _, ri := range engine.Routes() {
			got = append(got, ri.Method+" "+ri.Path)
		}
		t.Fatalf("GET /jackett/search not registered when JackettEnabled=true; routes=%v", got)
	}
}

// TestBuild_JackettRouteAbsentWhenDisabled is the discriminator that proves the
// positive test is sound: with JackettEnabled=false the route MUST NOT be
// registered. Without this, a Build that always registers /jackett/search would
// pass the positive test vacuously.
//
// Falsifiability: remove the `if deps.Cfg != nil && deps.Cfg.JackettEnabled`
// guard so the route registers unconditionally, and this test fails with
// "GET /jackett/search registered while JackettEnabled=false".
func TestBuild_JackettRouteAbsentWhenDisabled(t *testing.T) {
	cfg := fullCfg()
	cfg.JackettEnabled = false

	engine := Build(Deps{
		Cfg:        cfg,
		Cache:      stubCache{},
		Scraper:    stubScraper{},
		PromReg:    prometheus.NewRegistry(),
		AuthLadder: ladder.New([]time.Duration{time.Second}),
	})

	for _, ri := range engine.Routes() {
		if ri.Method == http.MethodGet && ri.Path == "/jackett/search" {
			t.Fatalf("GET /jackett/search registered while JackettEnabled=false — the enable-guard is broken")
		}
	}
}

// TestBuild_JackettRouteAbsentWhenEnabledButUnconfigured proves the §6.R
// no-hardcoded-secret posture: JackettEnabled=true but BaseURL/APIKey empty →
// jackett.NewClient returns ErrMissingConfig → the route is NOT registered
// (the handler would otherwise 500 on every call against a nil client).
//
// Falsifiability: change the `if jc, err := jackett.NewClient(...); err == nil`
// to ignore the error and register the route regardless — this test then fails.
func TestBuild_JackettRouteAbsentWhenEnabledButUnconfigured(t *testing.T) {
	cfg := fullCfg()
	cfg.JackettEnabled = true
	// BaseURL + APIKey deliberately left empty → NewClient fails.

	engine := Build(Deps{
		Cfg:        cfg,
		Cache:      stubCache{},
		Scraper:    stubScraper{},
		PromReg:    prometheus.NewRegistry(),
		AuthLadder: ladder.New([]time.Duration{time.Second}),
	})

	for _, ri := range engine.Routes() {
		if ri.Method == http.MethodGet && ri.Path == "/jackett/search" {
			t.Fatalf("GET /jackett/search registered while Jackett is enabled but " +
				"unconfigured (empty BaseURL/APIKey) — NewClient error was ignored")
		}
	}
}

// TestBuild_HealthOpenWithFullAuthChain covers the conditional middleware
// branches: with a non-nil Cfg and AuthLadder, Build mounts the
// protocol-metric, backoff, auth, brotli, and alt-svc middlewares. The
// user-visible invariant is that /health stays reachable WITHOUT a Lava-Auth
// header even with the full auth chain mounted (it is registered before the
// auth middleware on purpose — the orchestrator's liveness probe depends on
// it). Primary assertion: the HTTP status an unauthenticated probe receives.
//
// Falsifiability: move the `engine.GET("/health", ...)` registration to AFTER
// the `engine.Use(auth.NewMiddleware(...))` block, and this test fails because
// /health then returns 401 instead of 200 to the header-less probe.
func TestBuild_HealthOpenWithFullAuthChain(t *testing.T) {
	cfg := fullCfg()

	engine := Build(Deps{
		Cfg:        cfg,
		Cache:      stubCache{},
		Scraper:    stubScraper{},
		PromReg:    prometheus.NewRegistry(),
		AuthLadder: ladder.New([]time.Duration{time.Second}),
	})

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	// Deliberately NO auth header: the probe surface must stay open.
	engine.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("GET /health with full auth chain mounted returned %d (body=%q); "+
			"want 200 — liveness probe must bypass auth", w.Code, w.Body.String())
	}
}
