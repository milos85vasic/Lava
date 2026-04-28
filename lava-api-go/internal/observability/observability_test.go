package observability

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestLoggerRedactsAuthToken(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(LogConfig{Output: &buf})

	logger.Info("login", "Auth-Token", "secret-shibboleth", "user", "alice")

	out := buf.String()
	if strings.Contains(out, "secret-shibboleth") {
		t.Fatalf("auth-token leaked into log output: %s", out)
	}
	if !strings.Contains(out, "[REDACTED]") {
		t.Fatalf("expected [REDACTED] sentinel, got: %s", out)
	}

	// JSON well-formed?
	var rec map[string]any
	if err := json.Unmarshal(buf.Bytes(), &rec); err != nil {
		t.Fatalf("log output not valid JSON: %v\noutput: %s", err, out)
	}
	if rec["Auth-Token"] != "[REDACTED]" {
		t.Fatalf("expected Auth-Token=[REDACTED], got %v", rec["Auth-Token"])
	}
	if rec["user"] != "alice" {
		t.Fatalf("non-redacted attr lost: user=%v", rec["user"])
	}
}

func TestLoggerRedactsAlternateForms(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(LogConfig{Output: &buf})

	// Each of these MUST be redacted because every key in
	// DefaultRedactKeys covers the underscore / dash / case variants
	// via normaliseKey.
	logger.Info("variants",
		"AUTHORIZATION", "Bearer abc123",
		"set_cookie", "session=zzz",
		"x-auth", "tok",
		"cookie", "id=xyz",
	)
	out := buf.String()
	for _, leaked := range []string{"abc123", "session=zzz", "tok", "id=xyz"} {
		if strings.Contains(out, leaked) {
			t.Errorf("variant leaked: %q in %s", leaked, out)
		}
	}
}

func TestLoggerLevelFiltering(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(LogConfig{Output: &buf, Level: slog.LevelWarn})

	logger.Info("below threshold")
	if buf.Len() != 0 {
		t.Fatalf("info-level record emitted at warn threshold: %s", buf.String())
	}
	logger.Warn("above threshold")
	if !strings.Contains(buf.String(), "above threshold") {
		t.Fatalf("warn-level record dropped: %s", buf.String())
	}
}

func TestLivenessHandler200(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/health", LivenessHandler())

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/health", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	if !strings.Contains(w.Body.String(), `"status":"alive"`) {
		t.Fatalf("body = %q, want JSON containing status:alive", w.Body.String())
	}
}

func TestReadinessHandlerNilProbeIs200(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/ready", ReadinessHandler(nil))

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/ready", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
}

func TestReadinessHandlerProbeError(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/ready", ReadinessHandler(func(_ context.Context) error {
		return errors.New("db down")
	}))

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/ready", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", w.Code)
	}
	if !strings.Contains(w.Body.String(), "db down") {
		t.Fatalf("body did not include probe error: %s", w.Body.String())
	}
}

func TestReadinessHandlerProbeOK(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/ready", ReadinessHandler(func(_ context.Context) error { return nil }))

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/ready", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
}

func TestNewMetricsExposesAllRequiredCollectors(t *testing.T) {
	m := NewMetrics(nil)

	// All eight required collectors per spec §10.1 must be non-nil.
	if m.HTTPRequestsTotal == nil ||
		m.HTTPRequestDurationSeconds == nil ||
		m.CacheOutcomeTotal == nil ||
		m.RateLimitBlockedTotal == nil ||
		m.RutrackerUpstreamDuration == nil ||
		m.RutrackerUpstreamErrors == nil ||
		m.DBPoolAcquireDuration == nil ||
		m.MDNSAdvertisementActive == nil {
		t.Fatal("one or more required collectors are nil")
	}
	if m.Handler() == nil {
		t.Fatal("Handler() returned nil")
	}
	if m.Upstream() == nil {
		t.Fatal("Upstream() returned nil")
	}
}

func TestMetricsHandlerScrapeContainsRequiredNames(t *testing.T) {
	m := NewMetrics(nil)

	// Touch each collector once so it appears in the scrape output.
	m.HTTPRequestsTotal.WithLabelValues("GET", "/x", "2xx").Inc()
	m.CacheOutcomeTotal.WithLabelValues("hit").Inc()
	m.RateLimitBlockedTotal.WithLabelValues("auth").Inc()
	m.RutrackerUpstreamErrors.WithLabelValues("/v1/topic", "5xx").Inc()
	m.MDNSAdvertisementActive.WithLabelValues().Set(1)
	m.HTTPRequestDurationSeconds.WithLabelValues("GET", "/x").Observe(0.01)
	m.RutrackerUpstreamDuration.WithLabelValues("/v1/topic").Observe(0.01)
	m.DBPoolAcquireDuration.WithLabelValues().Observe(0.001)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/metrics", nil)
	m.Handler().ServeHTTP(w, req)
	body := w.Body.String()

	for _, name := range []string{
		"http_requests_total",
		"http_request_duration_seconds",
		"cache_outcome_total",
		"rate_limit_blocked_total",
		"rutracker_upstream_duration_seconds",
		"rutracker_upstream_errors_total",
		"db_pool_acquire_duration_seconds",
		"mdns_advertisement_active",
	} {
		if !strings.Contains(body, name) {
			t.Errorf("metric %q missing from scrape output", name)
		}
	}
}

func TestNewTracerNoOpWhenEndpointEmpty(t *testing.T) {
	tr, err := NewTracer(context.Background(), TracerConfig{
		ServiceName:    "lava-api-go-test",
		ServiceVersion: "test",
	})
	if err != nil {
		t.Fatalf("NewTracer: %v", err)
	}
	if tr == nil {
		t.Fatal("NewTracer returned nil")
	}
	if err := tr.Shutdown(context.Background()); err != nil {
		t.Fatalf("Shutdown: %v", err)
	}
}
