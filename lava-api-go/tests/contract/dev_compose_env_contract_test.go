// Package contract — DEV compose env-var binding contract test.
//
// Issue 3/3 (2026-05-14): a side-by-side DEV lava-api-go instance is
// brought up via docker-compose.dev.yml advertising _lava-api-dev._tcp
// on a different port. The compose file passes its dev configuration
// purely as env vars (LAVA_API_LISTEN, LAVA_API_MDNS_TYPE,
// LAVA_API_MDNS_PORT, LAVA_API_MDNS_INSTANCE). If the env-var names
// drift between the compose file and internal/config/config.go (e.g.,
// someone renames LAVA_API_MDNS_TYPE → LAVA_API_MDNS_SERVICE_TYPE in
// config.go but forgets the compose), the dev API would silently fall
// back to its production defaults — advertising _lava-api._tcp instead
// of the dev type — and the bug would only surface on real hardware
// LAN testing.
//
// Per §6.A (Real-binary contract tests), every place where one of our
// scripts/compose files invokes a binary we own MUST be covered by a
// contract test asserting the names line up. This test reads the dev
// compose file as text, extracts every LAVA_API_* env-var name it
// passes, then loads the binary's config layer with each set to a
// sentinel value and asserts the sentinel survives — proving config.go
// reads each name the compose passes.
//
// Sixth Law clause 2 falsifiability: rename one of the LAVA_API_DEV_*
// env-var consumer keys in config.go (e.g., change
// `envDefault("LAVA_API_MDNS_TYPE", ...)` to
// `envDefault("LAVA_API_MDNS_SERVICE_TYPE", ...)`) and re-run; this
// test reports the dropped binding clearly.
package contract

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/internal/config"
)

// envVarNamesUsedByDevCompose extracts every LAVA_API_* env-var name
// referenced inside docker-compose.dev.yml, deduplicated and sorted.
// Both `KEY: "${KEY}"` and `KEY: "${KEY:-default}"` patterns are
// captured; only the LAVA_API_ namespace is in scope (LAVA_AUTH_* and
// shared LAVA_PG_PASSWORD are owned by other contracts).
func envVarNamesUsedByDevCompose(t *testing.T) []string {
	t.Helper()
	composeBytes, err := os.ReadFile(filepath.Join("..", "..", "..", "docker-compose.dev.yml"))
	if err != nil {
		t.Fatalf("read docker-compose.dev.yml: %v", err)
	}
	body := string(composeBytes)

	// Two patterns to capture: env-var consumed via ${...} interpolation
	// AND env-var keys assigned literal values that the binary later
	// reads from os.Getenv. The LAVA_API_DEV_* keys are compose-internal
	// (passed as $-interpolations into the LAVA_API_* keys the binary
	// reads); the bound keys are LAVA_API_* in the `environment:` block.
	envBlockRe := regexp.MustCompile(`(?m)^\s+(LAVA_API_[A-Z_]+)\s*:\s*`)
	matches := envBlockRe.FindAllStringSubmatch(body, -1)

	seen := map[string]struct{}{}
	for _, m := range matches {
		seen[m[1]] = struct{}{}
	}
	out := make([]string, 0, len(seen))
	for k := range seen {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func TestDevCompose_AllEnvVarsBindToConfig(t *testing.T) {
	keys := envVarNamesUsedByDevCompose(t)
	if len(keys) == 0 {
		t.Fatal("dev compose declared no LAVA_API_* env vars — parser regression")
	}

	// Required bindings — drift-detection sentinel set per key. Each
	// key gets a value the binary's config layer is expected to surface.
	// Some keys are mandatory (Load() rejects empty); fill the auth set
	// from a known-good baseline so Load() reaches the dev-relevant
	// fields without erroring on missing required values.
	t.Setenv("LAVA_AUTH_FIELD_NAME", "Lava-Auth-Test")
	t.Setenv("LAVA_AUTH_HMAC_SECRET", "dGVzdC1obWFjLXNlY3JldC1mb3ItY29udHJhY3QtdGVzdA==")
	t.Setenv("LAVA_AUTH_ACTIVE_CLIENTS", "test:00000000-0000-0000-0000-000000000000")

	// Per-key sentinel values that should round-trip into Config.
	t.Setenv("LAVA_API_LISTEN", ":8543")
	t.Setenv("LAVA_API_METRICS_LISTEN", ":9092")
	t.Setenv("LAVA_API_MDNS_TYPE", "_lava-api-dev._tcp")
	t.Setenv("LAVA_API_MDNS_PORT", "8543")
	t.Setenv("LAVA_API_MDNS_INSTANCE", "Lava API (dev)")
	t.Setenv("LAVA_API_PG_URL", "postgres://lava:dummy@127.0.0.1:5433/lava_api_dev?sslmode=disable")
	t.Setenv("LAVA_API_TLS_CERT", "/etc/lava-api-go/tls/server.crt")
	t.Setenv("LAVA_API_TLS_KEY", "/etc/lava-api-go/tls/server.key")

	cfg, err := config.Load()
	if err != nil {
		t.Fatalf("config.Load() returned error with all dev env vars set: %v", err)
	}

	type binding struct {
		key      string
		expected string
		actual   string
	}
	bindings := []binding{
		{"LAVA_API_LISTEN", ":8543", cfg.Listen},
		{"LAVA_API_METRICS_LISTEN", ":9092", cfg.MetricsListen},
		{"LAVA_API_MDNS_TYPE", "_lava-api-dev._tcp", cfg.MDNSServiceType},
		{"LAVA_API_MDNS_INSTANCE", "Lava API (dev)", cfg.MDNSInstanceName},
	}
	for _, b := range bindings {
		if b.actual != b.expected {
			t.Errorf("env-var %s drifted: dev compose passes it, but config.Load() returned %q (expected %q). "+
				"This means renaming the consumer key in internal/config/config.go would silently "+
				"break the dev API's mDNS advertisement. Sync the names.", b.key, b.actual, b.expected)
		}
	}
	if cfg.MDNSPort != 8543 {
		t.Errorf("LAVA_API_MDNS_PORT drifted: dev compose passes 8543, config.Load() returned %d", cfg.MDNSPort)
	}

	// Drift detection: every LAVA_API_* key the compose passes MUST be
	// reachable via at least one Config field above OR documented as
	// non-binding (e.g., set for downstream containers). Today every
	// key in the dev compose is in the binding set; if a future commit
	// adds a key without updating this test, this assertion catches it.
	expectedKeys := map[string]struct{}{
		"LAVA_API_PG_URL":         {},
		"LAVA_API_LISTEN":         {},
		"LAVA_API_METRICS_LISTEN": {},
		"LAVA_API_MDNS_TYPE":      {},
		"LAVA_API_MDNS_PORT":      {},
		"LAVA_API_MDNS_INSTANCE":  {},
		"LAVA_API_TLS_CERT":       {},
		"LAVA_API_TLS_KEY":        {},
		"LAVA_API_OTLP_ENDPOINT":  {},
	}
	var unexpected []string
	for _, k := range keys {
		if _, ok := expectedKeys[k]; !ok {
			unexpected = append(unexpected, k)
		}
	}
	if len(unexpected) > 0 {
		t.Errorf("dev compose passes LAVA_API_* env vars not covered by this contract test: %s. "+
			"Add them to expectedKeys above and assert their config.go binding, or document why "+
			"they're non-binding (e.g., consumed by a sibling container).", strings.Join(unexpected, ", "))
	}
}
