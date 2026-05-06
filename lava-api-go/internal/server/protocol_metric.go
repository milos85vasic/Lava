// Package server: Prometheus protocol-mix metric.
//
// Phase 8 (Task 8.3) of the Phase-1 client-auth + transport plan.
// Records every request's transport protocol (h1/h2/h3) and status
// class (1xx..5xx) so an operator can watch the HTTP/3 adoption curve
// independently of the per-route metrics already emitted by
// internal/observability.GinMiddleware.
//
// The collector is registered against an injected Registerer rather
// than the Prometheus default registry so:
//   1. tests can pass a fresh registry per test (clean slate, no
//      cross-test counter contamination);
//   2. production wires it against the same registry as the existing
//      Metrics struct so /metrics returns the unified set on the
//      single private listener.
//
// Cardinality: 3 protocols × 6 status classes (1xx,2xx,3xx,4xx,5xx,
// unknown) = 18 series — well within Prometheus' comfort range.
package server

import (
	"strings"
	"sync"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
)

// MetricName is the Prometheus name. Exported so tests / tooling can
// scrape the metric without hard-coding the literal in two places.
const MetricName = "lava_api_request_protocol_total"

// protocolCounter is the package-level counter so all middleware
// instances writing to the same registry write to the SAME vec (a
// `MustRegister` of two distinct *CounterVec with the same name on
// the same registry would panic).
//
// We guard creation with a mutex + a per-registry map so every
// distinct registry gets its own counter — supports the test
// pattern where each test constructs a fresh registry.
var (
	counterMu       sync.Mutex
	counterByRegMap = make(map[prometheus.Registerer]*prometheus.CounterVec)
)

func counterForRegistry(reg prometheus.Registerer) *prometheus.CounterVec {
	counterMu.Lock()
	defer counterMu.Unlock()
	if c, ok := counterByRegMap[reg]; ok {
		return c
	}
	c := prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: MetricName,
			Help: "Total HTTP requests by transport protocol and status class.",
		},
		[]string{"protocol", "status"},
	)
	reg.MustRegister(c)
	counterByRegMap[reg] = c
	return c
}

// NewProtocolMetricMiddleware returns a Gin middleware that increments
// the protocol-mix counter on every request after the handler chain
// has run (so c.Writer.Status() reflects the final value, including
// 401 / 426 / 429 from auth + backoff middleware).
//
// `enabled` toggles the entire middleware off — the returned handler
// is a no-op pass-through. `reg` is the Prometheus registry to write
// to; pass `prometheus.DefaultRegisterer` in production OR a fresh
// `prometheus.NewRegistry()` in tests to avoid cross-test pollution.
func NewProtocolMetricMiddleware(enabled bool, reg prometheus.Registerer) gin.HandlerFunc {
	if !enabled {
		return func(c *gin.Context) { c.Next() }
	}
	if reg == nil {
		reg = prometheus.DefaultRegisterer
	}
	counter := counterForRegistry(reg)
	return func(c *gin.Context) {
		c.Next()
		protocol := protocolLabel(c.Request.Proto)
		status := statusClass(c.Writer.Status())
		counter.WithLabelValues(protocol, status).Inc()
	}
}

// protocolLabel maps a request protocol string to the bounded label
// set {h1, h2, h3}. Anything unrecognised (HTTP/0.9, future versions,
// junk) maps to "h1" — the conservative default since stdlib emits
// "HTTP/1.1" for ancient clients.
func protocolLabel(proto string) string {
	switch {
	case strings.HasPrefix(proto, "HTTP/3"):
		return "h3"
	case strings.HasPrefix(proto, "HTTP/2"):
		return "h2"
	default:
		return "h1"
	}
}

// statusClass collapses an integer HTTP status code to its class
// label. Returns "unknown" for status 0 (no response yet — should
// never happen post-c.Next() but defensive against a hypothetical
// future middleware that aborts before any handler runs).
func statusClass(code int) string {
	switch code / 100 {
	case 1:
		return "1xx"
	case 2:
		return "2xx"
	case 3:
		return "3xx"
	case 4:
		return "4xx"
	case 5:
		return "5xx"
	default:
		return "unknown"
	}
}
