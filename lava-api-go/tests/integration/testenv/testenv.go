// Package testenv provides a shared bootstrap for §6.G real-stack
// integration tests of the auth + transport layers.
//
// NewWithAuth boots a real Gin engine with the BackoffMiddleware +
// AuthMiddleware chain wired up exactly as cmd/lava-api-go/main.go does
// at startup, but with config seeded for testing (synthetic UUIDs,
// in-process httptest.Server). Tests then issue real HTTP requests and
// assert on user-visible status codes + JSON bodies.
package testenv

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"

	"digital.vasic.ratelimiter/pkg/ladder"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/config"
	"digital.vasic.lava.apigo/internal/server"
)

// AuthFixture is the test-side description of an authn config.
//
// Transport defaults: BrotliResponseEnabled=false, HTTP3Enabled=false,
// ProtocolMetricEnabled=false — tests opt-in per-fixture so the
// auth-only tests don't pay middleware setup costs they don't need.
type AuthFixture struct {
	FieldName             string
	ActiveUUIDHex         []string
	RetiredUUIDHex        []string
	BackoffSteps          []time.Duration
	TrustedProxies        []string
	BrotliResponseEnabled bool
	BrotliQuality         int
	HTTP3Enabled          bool
	ListenAddr            string // e.g. ":8443" — used for Alt-Svc port advertisement
	ProtocolMetricEnabled bool
	PromRegistry          prometheus.Registerer
}

// Env exposes the running test server.
type Env struct {
	URL          string
	Client       *http.Client
	Ladder       *ladder.Ladder
	PromRegistry prometheus.Registerer
	close        func()
}

// Close shuts down the test server.
func (e *Env) Close() { e.close() }

// NewWithAuth boots a real Gin engine wired up like the production
// bootstrapper, with synthetic UUID hashes seeded into the active +
// retired allowlists.
func NewWithAuth(t *testing.T, fix AuthFixture) *Env {
	t.Helper()

	if fix.FieldName == "" {
		fix.FieldName = "Lava-Auth"
	}
	if len(fix.BackoffSteps) == 0 {
		fix.BackoffSteps = []time.Duration{1 * time.Second, 2 * time.Second, 5 * time.Second}
	}

	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		t.Fatalf("rand: %v", err)
	}

	cfg := &config.Config{
		AuthFieldName:               fix.FieldName,
		AuthHMACSecret:              secret,
		AuthActiveClients:           hashEntries(t, secret, fix.ActiveUUIDHex, "active"),
		AuthRetiredClients:          hashEntries(t, secret, fix.RetiredUUIDHex, "retired"),
		AuthBackoffSteps:            fix.BackoffSteps,
		AuthTrustedProxies:          fix.TrustedProxies,
		AuthMinSupportedVersionName: "1.2.6",
		AuthMinSupportedVersionCode: 1026,
		BrotliResponseEnabled:       fix.BrotliResponseEnabled,
		BrotliQuality:               fix.BrotliQuality,
		HTTP3Enabled:                fix.HTTP3Enabled,
		Listen:                      fix.ListenAddr,
		ProtocolMetricEnabled:       fix.ProtocolMetricEnabled,
	}

	promReg := fix.PromRegistry
	if promReg == nil {
		promReg = prometheus.NewRegistry()
	}

	gin.SetMode(gin.TestMode)
	router := gin.New()

	l := ladder.New(cfg.AuthBackoffSteps)
	// Protocol metric MUST be first: its post-c.Next() block reads
	// c.Writer.Status() to label the counter, and that only sees the
	// final value (including 401/426/429 from later middlewares) when
	// it sits at the outermost layer.
	router.Use(server.NewProtocolMetricMiddleware(cfg.ProtocolMetricEnabled, promReg))
	router.Use(auth.NewBackoffMiddleware(l, cfg.AuthTrustedProxies))
	router.Use(auth.NewMiddleware(cfg, l))
	// Brotli wraps the writer for response compression on the success path.
	router.Use(server.NewBrotliMiddleware(cfg.BrotliResponseEnabled, cfg.BrotliQuality))
	router.Use(server.NewAltSvcMiddleware(cfg.HTTP3Enabled, cfg.Listen))

	// Test-only echo endpoint
	router.GET("/_test_echo", func(c *gin.Context) {
		clientName, _ := c.Get("client_name")
		c.JSON(http.StatusOK, gin.H{"client_name": clientName})
	})

	ts := httptest.NewServer(router)

	return &Env{
		URL:          ts.URL,
		Client:       ts.Client(),
		Ladder:       l,
		PromRegistry: promReg,
		close:        ts.Close,
	}
}

func hashEntries(t *testing.T, secret []byte, hexes []string, kind string) map[string]string {
	t.Helper()
	out := make(map[string]string)
	for i, h := range hexes {
		b, err := hex.DecodeString(h)
		if err != nil {
			t.Fatalf("hex decode %s[%d]: %v", kind, i, err)
		}
		name := fmt.Sprintf("%s-%d", kind, i)
		out[auth.TestOnlyHashUUID(secret, b)] = name
	}
	return out
}
