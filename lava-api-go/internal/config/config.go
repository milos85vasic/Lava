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
	"errors"
	"fmt"
	"os"
	"strconv"
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
