package observability

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/testutil"
)

// TestStatusBucket covers every arm of statusBucket — the cardinality-
// bounding function that maps a raw HTTP status into a "Nxx" Prometheus
// label. A regression that mis-buckets (e.g. 404 → "5xx") would corrupt
// every ops dashboard, so each boundary is asserted.
func TestStatusBucket(t *testing.T) {
	cases := []struct {
		code int
		want string
	}{
		{100, "1xx"},
		{199, "1xx"},
		{200, "2xx"},
		{204, "2xx"},
		{299, "2xx"},
		{301, "3xx"},
		{399, "3xx"},
		{400, "4xx"},
		{404, "4xx"},
		{499, "4xx"},
		{500, "5xx"},
		{503, "5xx"},
		{599, "5xx"},
	}
	for _, tc := range cases {
		if got := statusBucket(tc.code); got != tc.want {
			t.Errorf("statusBucket(%d) = %q, want %q", tc.code, got, tc.want)
		}
	}
}

// TestGinMiddlewareBucketsErrorStatus verifies the middleware records a
// non-2xx response under the correct status bucket label — a real request
// returning 404 must increment HTTPRequestsTotal{...,"4xx"}, not "2xx".
// This is the ops-visible signal that error rates are tracked correctly.
func TestGinMiddlewareBucketsErrorStatus(t *testing.T) {
	gin.SetMode(gin.TestMode)
	m := NewMetrics(prometheus.NewRegistry())

	r := gin.New()
	r.Use(m.GinMiddleware())
	r.GET("/missing", func(c *gin.Context) { c.String(http.StatusNotFound, "nope") })

	req := httptest.NewRequest(http.MethodGet, "/missing", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", w.Code)
	}
	counter, err := m.HTTPRequestsTotal.GetMetricWithLabelValues("GET", "/missing", "4xx")
	if err != nil {
		t.Fatalf("GetMetricWithLabelValues: %v", err)
	}
	if got := testutil.ToFloat64(counter); got != 1 {
		t.Errorf("HTTPRequestsTotal{GET,/missing,4xx} = %v, want 1", got)
	}
}

// TestMetricsHandlerGlobalRegistry verifies the package-level MetricsHandler()
// serves a Prometheus exposition response over the global registry — the
// /metrics endpoint ops scrapes. Asserts the wire body is the Prometheus
// text format and the endpoint returns 200.
func TestMetricsHandlerGlobalRegistry(t *testing.T) {
	h := MetricsHandler()
	if h == nil {
		t.Fatal("MetricsHandler() returned nil")
	}
	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/metrics", nil)
	h.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", w.Code)
	}
	// The default Go collectors are always registered on the global
	// registry, so the scrape always carries at least these standard
	// series in Prometheus text format.
	body := w.Body.String()
	if !strings.Contains(body, "go_goroutines") {
		t.Errorf("global /metrics scrape missing go_goroutines:\n%s", body)
	}
}

// TestLoggerWithAttrs verifies the WithAttrs handler contract: attributes
// attached via logger.With(...) are emitted on every subsequent record AND
// are still subject to the §6.H redaction denylist. The assertion is on the
// JSON the operator reads — a preset Cookie attr MUST appear redacted, a
// preset benign attr MUST appear verbatim.
func TestLoggerWithAttrs(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(LogConfig{Output: &buf}).With(
		"request_id", "req-42",
		"Cookie", "session=topsecret",
	)

	logger.Info("handling")

	out := buf.String()
	if strings.Contains(out, "topsecret") {
		t.Fatalf("preset Cookie attr leaked: %s", out)
	}
	var rec map[string]any
	if err := json.Unmarshal(buf.Bytes(), &rec); err != nil {
		t.Fatalf("log not valid JSON: %v\n%s", err, out)
	}
	if rec["request_id"] != "req-42" {
		t.Errorf("preset benign attr lost: request_id=%v", rec["request_id"])
	}
	if rec["Cookie"] != "[REDACTED]" {
		t.Errorf("preset Cookie attr not redacted: %v", rec["Cookie"])
	}
}

// TestLoggerWithAttrsIsolation verifies WithAttrs returns an independent
// handler clone — attrs added to a derived logger MUST NOT bleed back into
// the parent (a shared-slice aliasing bug would leak request-scoped attrs
// across requests).
func TestLoggerWithAttrsIsolation(t *testing.T) {
	var buf bytes.Buffer
	parent := NewLogger(LogConfig{Output: &buf})
	child := parent.With("scope", "child-only")

	// Both write to the same buffer; the first line is the parent record,
	// the second is the child record.
	parent.Info("parent-msg")
	child.Info("child-msg")

	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("expected 2 log lines, got %d: %s", len(lines), buf.String())
	}
	var parentRec, childRec map[string]any
	if err := json.Unmarshal([]byte(lines[0]), &parentRec); err != nil {
		t.Fatalf("parent line not JSON: %v", err)
	}
	if err := json.Unmarshal([]byte(lines[1]), &childRec); err != nil {
		t.Fatalf("child line not JSON: %v", err)
	}
	if _, present := parentRec["scope"]; present {
		t.Errorf("derived attr leaked into parent record: %v", parentRec)
	}
	if childRec["scope"] != "child-only" {
		t.Errorf("derived attr missing from child record: %v", childRec)
	}
}

// TestLoggerWithGroup verifies WithGroup returns a usable handler that still
// emits records (the group field is currently advisory in this minimal
// handler, but the derived logger must remain functional and JSON-valid).
func TestLoggerWithGroup(t *testing.T) {
	var buf bytes.Buffer
	logger := NewLogger(LogConfig{Output: &buf}).WithGroup("api")

	logger.Info("grouped", "key", "value")

	var rec map[string]any
	if err := json.Unmarshal(buf.Bytes(), &rec); err != nil {
		t.Fatalf("grouped log not valid JSON: %v\n%s", err, buf.String())
	}
	if rec["msg"] != "grouped" {
		t.Errorf("grouped record lost msg: %v", rec)
	}
	if rec["key"] != "value" {
		t.Errorf("grouped record lost attr: %v", rec)
	}
}

// TestTracerUpstreamAndShutdownNil verifies the nil-safe contract of the
// Tracer wrapper: a nil *Tracer's Upstream() returns nil and Shutdown() is a
// no-op (no panic). cmd/lava-api-go relies on this for graceful shutdown
// when tracing was never initialised.
func TestTracerUpstreamAndShutdownNil(t *testing.T) {
	var tr *Tracer
	if up := tr.Upstream(); up != nil {
		t.Errorf("nil Tracer.Upstream() = %v, want nil", up)
	}
	if err := tr.Shutdown(context.Background()); err != nil {
		t.Errorf("nil Tracer.Shutdown() = %v, want nil", err)
	}
}

// TestTracerUpstreamNonNil verifies a real (no-op) tracer exposes its
// upstream so callers can start spans.
func TestTracerUpstreamNonNil(t *testing.T) {
	tr, err := NewTracer(context.Background(), TracerConfig{
		ServiceName:    "lava-api-go-test",
		ServiceVersion: "test",
	})
	if err != nil {
		t.Fatalf("NewTracer: %v", err)
	}
	if tr.Upstream() == nil {
		t.Error("real Tracer.Upstream() = nil, want a usable tracer")
	}
	_ = tr.Shutdown(context.Background())
}
