package config

import (
	"strings"
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
		// Phase 1 auth + transport
		"LAVA_AUTH_FIELD_NAME",
		"LAVA_AUTH_HMAC_SECRET",
		"LAVA_AUTH_ACTIVE_CLIENTS",
		"LAVA_AUTH_RETIRED_CLIENTS",
		"LAVA_AUTH_BACKOFF_STEPS",
		"LAVA_AUTH_TRUSTED_PROXIES",
		"LAVA_AUTH_MIN_SUPPORTED_VERSION_NAME",
		"LAVA_AUTH_MIN_SUPPORTED_VERSION_CODE",
		"LAVA_API_HTTP3_ENABLED",
		"LAVA_API_BROTLI_QUALITY",
		"LAVA_API_BROTLI_RESPONSE_ENABLED",
		"LAVA_API_BROTLI_REQUEST_DECODE_ENABLED",
		"LAVA_API_PROTOCOL_METRIC_ENABLED",
	}
	for _, k := range keys {
		t.Setenv(k, "")
	}
}

// setRequiredAuthEnv populates the Phase 1 auth env vars with valid
// placeholder values so existing TestLoad* cases that focus on
// pre-Phase-1 fields don't trip the new required-var checks.
// The HMAC secret here is a 32-byte base64-encoded value matching
// `cGxhY2Vob2xkZXItcmVwbGFjZS1tZS1pbi1lbnYtZmlsZQ==`.
func setRequiredAuthEnv(t *testing.T) {
	t.Helper()
	t.Setenv("LAVA_AUTH_FIELD_NAME", "Lava-Auth")
	t.Setenv("LAVA_AUTH_HMAC_SECRET", "cGxhY2Vob2xkZXItcmVwbGFjZS1tZS1pbi1lbnYtZmlsZQ==")
}

func TestLoadFromEnvHappy(t *testing.T) {
	clearEnv(t)
	setRequiredAuthEnv(t)
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
	setRequiredAuthEnv(t)
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
	setRequiredAuthEnv(t)
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
	setRequiredAuthEnv(t)
	t.Setenv("LAVA_API_PG_URL", "postgres://x@y/z")
	t.Setenv("LAVA_API_TLS_CERT", "/x.crt")
	t.Setenv("LAVA_API_TLS_KEY", "/x.key")
	t.Setenv("LAVA_API_MDNS_PORT", "70000")

	_, err := Load()
	if err == nil {
		t.Fatal("expected error when LAVA_API_MDNS_PORT is out of range")
	}
}

// === Phase 1: auth + transport helpers ===

func TestParseClientsList_ValidEntry(t *testing.T) {
	secret := []byte("test-secret-for-hashing-1234567890")
	m, err := parseClientsList("android-1.2.7-1027:00000000-0000-0000-0000-000000000001", secret)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(m) != 1 {
		t.Fatalf("expected 1 entry, got %d", len(m))
	}
	var name string
	for _, n := range m {
		name = n
	}
	if name != "android-1.2.7-1027" {
		t.Fatalf("name = %q", name)
	}
}

func TestParseClientsList_MultipleEntries(t *testing.T) {
	secret := []byte("test-secret-for-hashing-1234567890")
	m, err := parseClientsList(
		"android-1.2.7-1027:00000000-0000-0000-0000-000000000001,android-1.2.6-1026:00000000-0000-0000-0000-000000000002",
		secret,
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(m) != 2 {
		t.Fatalf("expected 2 entries, got %d", len(m))
	}
	names := make(map[string]bool)
	for _, n := range m {
		names[n] = true
	}
	if !names["android-1.2.7-1027"] || !names["android-1.2.6-1026"] {
		t.Fatalf("missing expected names: %v", names)
	}
}

func TestParseClientsList_EmptyInput(t *testing.T) {
	m, err := parseClientsList("", []byte("k"))
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if len(m) != 0 {
		t.Fatalf("expected empty map, got %d entries", len(m))
	}
}

func TestParseClientsList_MissingColon(t *testing.T) {
	_, err := parseClientsList("notavalidentry", []byte("k"))
	if err == nil {
		t.Fatal("expected error for missing colon")
	}
	if !strings.Contains(err.Error(), "missing colon separator") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestParseClientsList_BadUUID(t *testing.T) {
	_, err := parseClientsList("android:not-a-valid-uuid-at-all-here", []byte("k"))
	if err == nil {
		t.Fatal("expected error for bad UUID")
	}
}

func TestParseUUID_Valid(t *testing.T) {
	b, err := parseUUID("01020304-0506-0708-090a-0b0c0d0e0f10")
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if len(b) != 16 {
		t.Fatalf("len = %d, want 16", len(b))
	}
	if b[0] != 0x01 || b[15] != 0x10 {
		t.Fatalf("first/last byte: %#x %#x", b[0], b[15])
	}
}

func TestParseUUID_WrongLength(t *testing.T) {
	_, err := parseUUID("01020304-0506-0708-090a-0b0c0d0e")
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestParseUUID_BadHex(t *testing.T) {
	_, err := parseUUID("0g020304-0506-0708-090a-0b0c0d0e0f10")
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestParseBackoffSteps_Valid(t *testing.T) {
	steps, err := parseBackoffSteps("2s,5s,10s")
	if err != nil {
		t.Fatalf("err: %v", err)
	}
	if len(steps) != 3 {
		t.Fatalf("len = %d", len(steps))
	}
	if steps[0] != 2*time.Second {
		t.Fatalf("steps[0] = %s", steps[0])
	}
	if steps[2] != 10*time.Second {
		t.Fatalf("steps[2] = %s", steps[2])
	}
}

func TestParseBackoffSteps_NotMonotonic(t *testing.T) {
	_, err := parseBackoffSteps("10s,5s")
	if err == nil {
		t.Fatal("expected error for non-monotonic ladder")
	}
	if !strings.Contains(err.Error(), "monotonically") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestParseBackoffSteps_Empty(t *testing.T) {
	_, err := parseBackoffSteps("")
	if err == nil {
		t.Fatal("expected error for empty input (single empty step parsed)")
	}
}

func TestParseBackoffSteps_BadDuration(t *testing.T) {
	_, err := parseBackoffSteps("notaduration")
	if err == nil {
		t.Fatal("expected error")
	}
}

func TestEnvBool_True(t *testing.T) {
	t.Setenv("TEST_LAVA_BOOL", "true")
	if !envBool("TEST_LAVA_BOOL", false) {
		t.Fatal("expected true")
	}
}

func TestEnvBool_False(t *testing.T) {
	t.Setenv("TEST_LAVA_BOOL", "false")
	if envBool("TEST_LAVA_BOOL", true) {
		t.Fatal("expected false")
	}
}

func TestEnvBool_DefaultOnEmpty(t *testing.T) {
	t.Setenv("TEST_LAVA_BOOL", "")
	if !envBool("TEST_LAVA_BOOL", true) {
		t.Fatal("expected default true")
	}
}

func TestEnvBool_DefaultOnGarbage(t *testing.T) {
	t.Setenv("TEST_LAVA_BOOL", "garbage")
	if !envBool("TEST_LAVA_BOOL", true) {
		t.Fatal("expected default true on parse error")
	}
}

func TestParseCSV_Valid(t *testing.T) {
	out := parseCSV("a,b ,c")
	if len(out) != 3 {
		t.Fatalf("len = %d", len(out))
	}
	if out[1] != "b" {
		t.Fatalf("trim failed: %q", out[1])
	}
}

func TestParseCSV_Empty(t *testing.T) {
	if parseCSV("") != nil {
		t.Fatal("expected nil for empty input")
	}
}
