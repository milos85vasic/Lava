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

	"digital.vasic.ratelimiter/pkg/ladder"

	"digital.vasic.lava.apigo/internal/auth"
	"digital.vasic.lava.apigo/internal/config"
)

// AuthFixture is the test-side description of an authn config.
type AuthFixture struct {
	FieldName      string
	ActiveUUIDHex  []string
	RetiredUUIDHex []string
	BackoffSteps   []time.Duration
	TrustedProxies []string
}

// Env exposes the running test server.
type Env struct {
	URL    string
	Client *http.Client
	Ladder *ladder.Ladder
	close  func()
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
	}

	gin.SetMode(gin.TestMode)
	router := gin.New()

	l := ladder.New(cfg.AuthBackoffSteps)
	router.Use(auth.NewBackoffMiddleware(l, cfg.AuthTrustedProxies))
	router.Use(auth.NewMiddleware(cfg, l))

	// Test-only echo endpoint
	router.GET("/_test_echo", func(c *gin.Context) {
		clientName, _ := c.Get("client_name")
		c.JSON(http.StatusOK, gin.H{"client_name": clientName})
	})

	ts := httptest.NewServer(router)

	return &Env{
		URL:    ts.URL,
		Client: ts.Client(),
		Ladder: l,
		close:  ts.Close,
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
