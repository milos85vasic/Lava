package config

import (
	"testing"
	"time"
)

// clearEnv unsets every LAVA_API_* variable that Load consults so each
// test starts from a clean slate. t.Setenv saves and restores values
// for each variable individually but does not clear ones the test
// doesn't set; explicit clearing is required to keep tests independent
// of the developer's shell environment.
func clearEnv(t *testing.T) {
	t.Helper()
	keys := []string{
		"LAVA_API_PG_URL",
		"LAVA_API_PG_SCHEMA",
		"LAVA_API_LISTEN",
		"LAVA_API_METRICS_LISTEN",
		"LAVA_API_TLS_CERT",
		"LAVA_API_TLS_KEY",
		"LAVA_API_OTLP_ENDPOINT",
		"LAVA_API_AUDIT_RETENTION",
		"LAVA_API_LOGIN_RETENTION",
		"LAVA_API_RATELIMIT_ORPHAN_TTL",
		"LAVA_API_MDNS_INSTANCE",
		"LAVA_API_MDNS_TYPE",
		"LAVA_API_MDNS_PORT",
		"LAVA_API_RUTRACKER_URL",
	}
	for _, k := range keys {
		t.Setenv(k, "")
	}
}

func TestLoadFromEnvHappy(t *testing.T) {
	clearEnv(t)
	t.Setenv("LAVA_API_PG_URL", "postgres://lava:pwd@127.0.0.1:5432/lava_api?sslmode=disable")
	t.Setenv("LAVA_API_LISTEN", ":8443")
	t.Setenv("LAVA_API_METRICS_LISTEN", "127.0.0.1:9091")
	t.Setenv("LAVA_API_TLS_CERT", "/etc/lava-api-go/tls/server.crt")
	t.Setenv("LAVA_API_TLS_KEY", "/etc/lava-api-go/tls/server.key")
	t.Setenv("LAVA_API_OTLP_ENDPOINT", "http://127.0.0.1:4318")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.PGUrl != "postgres://lava:pwd@127.0.0.1:5432/lava_api?sslmode=disable" {
		t.Errorf("PGUrl wrong: %q", cfg.PGUrl)
	}
	if cfg.PGSchema != "lava_api" {
		t.Errorf("PGSchema default wrong: %q", cfg.PGSchema)
	}
	if cfg.Listen != ":8443" {
		t.Errorf("Listen wrong: %q", cfg.Listen)
	}
	if cfg.MetricsListen != "127.0.0.1:9091" {
		t.Errorf("MetricsListen wrong: %q", cfg.MetricsListen)
	}
	if cfg.AuditRetention != 30*24*time.Hour {
		t.Errorf("AuditRetention default wrong: %v", cfg.AuditRetention)
	}
	if cfg.LoginRetention != 7*24*time.Hour {
		t.Errorf("LoginRetention default wrong: %v", cfg.LoginRetention)
	}
	if cfg.RateLimitOrphanTTL != time.Hour {
		t.Errorf("RateLimitOrphanTTL default wrong: %v", cfg.RateLimitOrphanTTL)
	}
	if cfg.MDNSInstanceName != "Lava API" {
		t.Errorf("MDNSInstanceName default wrong: %q", cfg.MDNSInstanceName)
	}
	if cfg.MDNSServiceType != "_lava-api._tcp" {
		t.Errorf("MDNSServiceType default wrong: %q", cfg.MDNSServiceType)
	}
	if cfg.MDNSPort != 8443 {
		t.Errorf("MDNSPort default wrong: %d", cfg.MDNSPort)
	}
	if cfg.RutrackerBaseURL != "https://rutracker.org/forum" {
		t.Errorf("RutrackerBaseURL default wrong: %q", cfg.RutrackerBaseURL)
	}
	if cfg.OTLPEndpoint != "http://127.0.0.1:4318" {
		t.Errorf("OTLPEndpoint wrong: %q", cfg.OTLPEndpoint)
	}
}

func TestLoadRejectsMissingPGUrl(t *testing.T) {
	clearEnv(t)
	t.Setenv("LAVA_API_PG_URL", "")
	t.Setenv("LAVA_API_LISTEN", ":8443")
	t.Setenv("LAVA_API_TLS_CERT", "/x.crt")
	t.Setenv("LAVA_API_TLS_KEY", "/x.key")

	_, err := Load()
	if err == nil {
		t.Fatal("expected error when LAVA_API_PG_URL is empty")
	}
}

func TestLoadRejectsMissingTLS(t *testing.T) {
	clearEnv(t)
	t.Setenv("LAVA_API_PG_URL", "postgres://x@y/z")
	t.Setenv("LAVA_API_LISTEN", ":8443")
	t.Setenv("LAVA_API_TLS_CERT", "")
	t.Setenv("LAVA_API_TLS_KEY", "")

	_, err := Load()
	if err == nil {
		t.Fatal("expected error when TLS cert/key paths are empty")
	}
}

func TestLoadRejectsMDNSPortOutOfRange(t *testing.T) {
	clearEnv(t)
	t.Setenv("LAVA_API_PG_URL", "postgres://x@y/z")
	t.Setenv("LAVA_API_TLS_CERT", "/x.crt")
	t.Setenv("LAVA_API_TLS_KEY", "/x.key")
	t.Setenv("LAVA_API_MDNS_PORT", "70000")

	_, err := Load()
	if err == nil {
		t.Fatal("expected error when LAVA_API_MDNS_PORT is out of range")
	}
}
