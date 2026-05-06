// Package config loads and validates the lava-api-go service configuration.
//
// Source of truth: environment variables. Per the Decoupled Reusable rule
// the variable inventory and validation rules are owned by lava-api-go,
// while richer features (file-based config, hot-reload, schema-validated
// merging) can be layered on top via Submodules/Config in later phases.
// The API surface here exposes ONLY the fields lava-api-go actually
// consumes — no leaking of upstream configuration types.
package config

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config is the validated runtime configuration for the lava-api-go service.
type Config struct {
	// Postgres
	PGUrl    string
	PGSchema string

	// Listeners
	Listen        string // public LAN listener, e.g. ":8443"
	MetricsListen string // private metrics listener, e.g. "127.0.0.1:9091"

	// TLS material for the public HTTP/3 listener
	TLSCertPath string
	TLSKeyPath  string

	// Observability
	OTLPEndpoint string // optional; empty disables tracing export

	// Retention
	AuditRetention     time.Duration
	LoginRetention     time.Duration
	RateLimitOrphanTTL time.Duration

	// mDNS
	MDNSInstanceName string
	MDNSServiceType  string
	MDNSPort         int

	// rutracker upstream
	RutrackerBaseURL string

	// === Phase 1: Auth (read at boot from .env / runtime env vars) ===
	AuthFieldName               string
	AuthHMACSecret              []byte            // base64-decoded, ≥16 bytes
	AuthActiveClients           map[string]string // map[hex(HMAC-SHA256(uuid))] = client_name
	AuthRetiredClients          map[string]string // forced-upgrade list (same shape)
	AuthBackoffSteps            []time.Duration   // ladder, monotonic non-decreasing
	AuthTrustedProxies          []string          // CIDR list, empty = direct
	AuthMinSupportedVersionName string
	AuthMinSupportedVersionCode int

	// === Phase 1: Transport ===
	HTTP3Enabled               bool
	BrotliQuality              int
	BrotliResponseEnabled      bool
	BrotliRequestDecodeEnabled bool
	ProtocolMetricEnabled      bool
}

// Load reads environment variables, applies the defaults documented in
// docs/superpowers/specs/2026-04-28-sp2-go-api-migration-design.md §12.1,
// and validates the result. It returns a non-nil error if any required
// variable is missing or out of range.
func Load() (*Config, error) {
	cfg := &Config{
		PGUrl:              os.Getenv("LAVA_API_PG_URL"),
		PGSchema:           envDefault("LAVA_API_PG_SCHEMA", "lava_api"),
		Listen:             envDefault("LAVA_API_LISTEN", ":8443"),
		MetricsListen:      envDefault("LAVA_API_METRICS_LISTEN", "127.0.0.1:9091"),
		TLSCertPath:        os.Getenv("LAVA_API_TLS_CERT"),
		TLSKeyPath:         os.Getenv("LAVA_API_TLS_KEY"),
		OTLPEndpoint:       os.Getenv("LAVA_API_OTLP_ENDPOINT"),
		AuditRetention:     envDuration("LAVA_API_AUDIT_RETENTION", 30*24*time.Hour),
		LoginRetention:     envDuration("LAVA_API_LOGIN_RETENTION", 7*24*time.Hour),
		RateLimitOrphanTTL: envDuration("LAVA_API_RATELIMIT_ORPHAN_TTL", 1*time.Hour),
		MDNSInstanceName:   envDefault("LAVA_API_MDNS_INSTANCE", "Lava API"),
		MDNSServiceType:    envDefault("LAVA_API_MDNS_TYPE", "_lava-api._tcp"),
		MDNSPort:           envInt("LAVA_API_MDNS_PORT", 8443),
		// Kotlin's HttpClientFactory.DefaultUrl is "https://rutracker.org/forum/" —
		// every endpoint path the rutracker package emits (/index.php,
		// /tracker.php, /viewforum.php, /viewtopic.php, /bookmarks.php,
		// /login.php, /posting.php, /dl.php) lives under /forum/. The trailing
		// slash is omitted here because Client.Fetch builds c.base + path
		// where path starts with `/`.
		RutrackerBaseURL: envDefault("LAVA_API_RUTRACKER_URL", "https://rutracker.org/forum"),
	}

	// === Phase 1: auth field reads ===
	cfg.AuthFieldName = os.Getenv("LAVA_AUTH_FIELD_NAME")
	if cfg.AuthFieldName == "" {
		return nil, errors.New("config: LAVA_AUTH_FIELD_NAME is required")
	}

	secretB64 := os.Getenv("LAVA_AUTH_HMAC_SECRET")
	if secretB64 == "" {
		return nil, errors.New("config: LAVA_AUTH_HMAC_SECRET is required")
	}
	secret, err := base64.StdEncoding.DecodeString(secretB64)
	if err != nil {
		return nil, fmt.Errorf("config: LAVA_AUTH_HMAC_SECRET base64 decode: %w", err)
	}
	if len(secret) < 16 {
		return nil, fmt.Errorf("config: LAVA_AUTH_HMAC_SECRET must be at least 16 bytes (got %d)", len(secret))
	}
	cfg.AuthHMACSecret = secret

	cfg.AuthActiveClients, err = parseClientsList(os.Getenv("LAVA_AUTH_ACTIVE_CLIENTS"), secret)
	if err != nil {
		return nil, fmt.Errorf("config: LAVA_AUTH_ACTIVE_CLIENTS: %w", err)
	}
	cfg.AuthRetiredClients, err = parseClientsList(os.Getenv("LAVA_AUTH_RETIRED_CLIENTS"), secret)
	if err != nil {
		return nil, fmt.Errorf("config: LAVA_AUTH_RETIRED_CLIENTS: %w", err)
	}

	stepsStr := envDefault("LAVA_AUTH_BACKOFF_STEPS", "2s,5s,10s,30s,1m,1h")
	cfg.AuthBackoffSteps, err = parseBackoffSteps(stepsStr)
	if err != nil {
		return nil, fmt.Errorf("config: LAVA_AUTH_BACKOFF_STEPS: %w", err)
	}

	cfg.AuthTrustedProxies = parseCSV(os.Getenv("LAVA_AUTH_TRUSTED_PROXIES"))
	cfg.AuthMinSupportedVersionName = os.Getenv("LAVA_AUTH_MIN_SUPPORTED_VERSION_NAME")
	cfg.AuthMinSupportedVersionCode = envInt("LAVA_AUTH_MIN_SUPPORTED_VERSION_CODE", 0)

	// === Phase 1: transport ===
	cfg.HTTP3Enabled = envBool("LAVA_API_HTTP3_ENABLED", true)
	cfg.BrotliQuality = envInt("LAVA_API_BROTLI_QUALITY", 4)
	cfg.BrotliResponseEnabled = envBool("LAVA_API_BROTLI_RESPONSE_ENABLED", true)
	cfg.BrotliRequestDecodeEnabled = envBool("LAVA_API_BROTLI_REQUEST_DECODE_ENABLED", false)
	cfg.ProtocolMetricEnabled = envBool("LAVA_API_PROTOCOL_METRIC_ENABLED", true)

	if cfg.PGUrl == "" {
		return nil, errors.New("config: LAVA_API_PG_URL is required (P1 hard-dep)")
	}
	if cfg.TLSCertPath == "" || cfg.TLSKeyPath == "" {
		return nil, errors.New("config: LAVA_API_TLS_CERT and LAVA_API_TLS_KEY are required")
	}
	if cfg.Listen == "" {
		return nil, errors.New("config: LAVA_API_LISTEN is required")
	}
	if cfg.MDNSPort <= 0 || cfg.MDNSPort > 65535 {
		return nil, fmt.Errorf("config: LAVA_API_MDNS_PORT %d out of range (1..65535)", cfg.MDNSPort)
	}
	return cfg, nil
}

func envDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func envDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}

// parseClientsList parses a CSV of "name:uuid" entries and returns a
// map keyed by hex(HMAC-SHA256(uuid, secret)), valued by name. The
// plaintext UUID byte slice is zeroized after hashing per §6.H.
func parseClientsList(csv string, secret []byte) (map[string]string, error) {
	out := make(map[string]string)
	if csv == "" {
		return out, nil
	}
	for _, entry := range strings.Split(csv, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		colon := strings.IndexByte(entry, ':')
		if colon < 0 {
			return nil, fmt.Errorf("entry %q missing colon separator", entry)
		}
		name := strings.TrimSpace(entry[:colon])
		uuidStr := strings.TrimSpace(entry[colon+1:])
		uuidBytes, err := parseUUID(uuidStr)
		if err != nil {
			return nil, fmt.Errorf("entry %q: %w", entry, err)
		}
		h := hmac.New(sha256.New, secret)
		h.Write(uuidBytes)
		// Zeroize plaintext UUID per §6.H — never linger in memory.
		for i := range uuidBytes {
			uuidBytes[i] = 0
		}
		out[hex.EncodeToString(h.Sum(nil))] = name
	}
	return out, nil
}

// parseUUID hex-decodes a 36-char UUID (with dashes) into 16 bytes.
func parseUUID(s string) ([]byte, error) {
	stripped := strings.ReplaceAll(s, "-", "")
	if len(stripped) != 32 {
		return nil, fmt.Errorf("UUID %q wrong length (want 36 with dashes)", s)
	}
	out := make([]byte, 16)
	for i := 0; i < 16; i++ {
		b, err := strconv.ParseUint(stripped[i*2:i*2+2], 16, 8)
		if err != nil {
			return nil, fmt.Errorf("UUID %q malformed at byte %d: %w", s, i, err)
		}
		out[i] = byte(b)
	}
	return out, nil
}

// parseBackoffSteps parses a CSV of Go time.Duration strings. The
// resulting list MUST be monotonically non-decreasing — each step is
// >= the previous step. Returns an error if any step parses badly,
// the list is empty, or the monotonicity invariant is violated.
//
// Phase 2 follow-up (2026-05-06): short-circuits on empty input
// rather than letting strings.Split(",", "") return [""] and tripping
// the "step \"\":" error. The previous behavior was a code-quality
// reviewer-flagged dead-branch issue: len(out)==0 was unreachable
// because every iteration appended to out OR returned an error.
func parseBackoffSteps(csv string) ([]time.Duration, error) {
	if strings.TrimSpace(csv) == "" {
		return nil, errors.New("at least one step required")
	}
	parts := strings.Split(csv, ",")
	out := make([]time.Duration, 0, len(parts))
	var prev time.Duration
	for _, p := range parts {
		p = strings.TrimSpace(p)
		d, err := time.ParseDuration(p)
		if err != nil {
			return nil, fmt.Errorf("step %q: %w", p, err)
		}
		if d < prev {
			return nil, fmt.Errorf("steps not monotonically non-decreasing at %q (prev %s)", p, prev)
		}
		prev = d
		out = append(out, d)
	}
	return out, nil
}

// parseCSV splits on commas and trims whitespace; empty input returns nil.
func parseCSV(s string) []string {
	if s == "" {
		return nil
	}
	out := strings.Split(s, ",")
	for i := range out {
		out[i] = strings.TrimSpace(out[i])
	}
	return out
}

// envBool reads a boolean env var with a default. Falls back to def
// on parse error AND emits a stderr warning so an operator typo
// (e.g. LAVA_API_HTTP3_ENABLED=ture) doesn't silently ship the
// default value.
//
// Phase 2 follow-up (2026-05-06): the original silent-fallback was
// a code-quality reviewer-flagged §6.J spirit issue (asymmetric to
// the fail-fast behavior on missing required vars). The warning is
// the documented behavior — operators wanting strict-fail behavior
// should validate `.env` ahead of boot via a separate linter.
func envBool(key string, def bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		fmt.Fprintf(os.Stderr,
			"config: %s=%q is not a valid boolean (want true|false|1|0); falling back to default %v\n",
			key, v, def,
		)
		return def
	}
	return b
}
