package observability

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	obsmetrics "digital.vasic.observability/pkg/metrics"
)

// Metrics is the lava-api-go-specific metrics collector set.
//
// The metric NAMES below are required by the spec (§10.1) — they are
// part of the operator-visible contract (dashboards / alert rules).
// Renaming them is a breaking change.
//
// Adaptation note: the plan template imagined typed Counter / Histogram
// / Gauge wrappers from upstream. The actual upstream
// (Submodules/Observability/pkg/metrics) exposes a single Collector
// interface with method-name-by-string. To preserve the named-collector
// surface promised in the plan we hold the underlying *prometheus.*Vec
// directly and pass them through the upstream by registering against
// prometheus.DefaultRegisterer (which the upstream uses by default).
type Metrics struct {
	HTTPRequestsTotal          *prometheus.CounterVec
	HTTPRequestDurationSeconds *prometheus.HistogramVec
	CacheOutcomeTotal          *prometheus.CounterVec
	RateLimitBlockedTotal      *prometheus.CounterVec
	RutrackerUpstreamDuration  *prometheus.HistogramVec
	RutrackerUpstreamErrors    *prometheus.CounterVec
	DBPoolAcquireDuration      *prometheus.HistogramVec
	MDNSAdvertisementActive    *prometheus.GaugeVec

	// upstream is exposed for callers that prefer the
	// Submodules/Observability Collector interface (e.g. for ad-hoc
	// metrics from generic helpers). It writes to the same registry
	// as the named collectors above.
	upstream obsmetrics.Collector

	registry *prometheus.Registry
}

// NewMetrics registers all collectors against the supplied registry
// (defaults to a fresh registry when nil) and returns the Metrics
// struct. A fresh per-call registry is the safe default for tests;
// the application wires a single shared registry.
func NewMetrics(registry *prometheus.Registry) *Metrics {
	if registry == nil {
		registry = prometheus.NewRegistry()
	}

	m := &Metrics{
		HTTPRequestsTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "http_requests_total",
				Help: "Count of HTTP requests by method, route, status.",
			},
			[]string{"method", "route", "status"},
		),
		HTTPRequestDurationSeconds: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "http_request_duration_seconds",
				Help:    "HTTP request handling latency.",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"method", "route"},
		),
		CacheOutcomeTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "cache_outcome_total",
				Help: "Cache outcome per request: hit | miss | bypass | invalidate.",
			},
			[]string{"outcome"},
		),
		RateLimitBlockedTotal: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "rate_limit_blocked_total",
				Help: "Requests blocked by rate limiter, by route_class.",
			},
			[]string{"route_class"},
		),
		RutrackerUpstreamDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "rutracker_upstream_duration_seconds",
				Help:    "Latency of upstream calls to rutracker.org by route.",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"route"},
		),
		RutrackerUpstreamErrors: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "rutracker_upstream_errors_total",
				Help: "Upstream errors hitting rutracker.org by route and kind.",
			},
			[]string{"route", "kind"},
		),
		DBPoolAcquireDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "db_pool_acquire_duration_seconds",
				Help:    "Time to acquire a Postgres connection from the pool.",
				Buckets: prometheus.DefBuckets,
			},
			nil,
		),
		MDNSAdvertisementActive: prometheus.NewGaugeVec(
			prometheus.GaugeOpts{
				Name: "mdns_advertisement_active",
				Help: "1 if the mDNS advertisement is active, 0 otherwise.",
			},
			nil,
		),
		registry: registry,
	}

	registry.MustRegister(
		m.HTTPRequestsTotal,
		m.HTTPRequestDurationSeconds,
		m.CacheOutcomeTotal,
		m.RateLimitBlockedTotal,
		m.RutrackerUpstreamDuration,
		m.RutrackerUpstreamErrors,
		m.DBPoolAcquireDuration,
		m.MDNSAdvertisementActive,
	)

	// Wire the upstream Collector interface against the same registry
	// so generic helpers (Submodules/Middleware, etc.) write into the
	// same scrape surface.
	m.upstream = obsmetrics.NewPrometheusCollector(&obsmetrics.PrometheusConfig{
		DefaultBuckets: prometheus.DefBuckets,
		Registry:       registry,
	})

	return m
}

// Upstream returns the Submodules/Observability Collector that writes
// to the same Prometheus registry as the named collectors.
func (m *Metrics) Upstream() obsmetrics.Collector { return m.upstream }

// Handler returns the http.Handler for /metrics, scoped to the
// registry this Metrics instance writes to. Mount on the dedicated
// localhost listener — never on the public API listener.
func (m *Metrics) Handler() http.Handler {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{})
}

// MetricsHandler returns a handler bound to the global Prometheus
// registry. It is preserved for callers that prefer the package-level
// helper from the original plan template, but new code should use
// (*Metrics).Handler() to avoid coupling to the global registry.
func MetricsHandler() http.Handler {
	return promhttp.Handler()
}

// GinMiddleware records request count + duration per route. The
// middleware reads c.FullPath() (the matched route pattern, e.g.
// "/v1/topic/:id") rather than the raw URL so the cardinality stays
// bounded. Status / method labels follow Prometheus conventions.
func (m *Metrics) GinMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		duration := time.Since(start)

		route := c.FullPath()
		if route == "" {
			route = c.Request.URL.Path
		}
		method := c.Request.Method
		status := http.StatusText(c.Writer.Status())
		if status == "" {
			status = httpStatusCode(c.Writer.Status())
		}

		m.HTTPRequestsTotal.WithLabelValues(method, route, statusBucket(c.Writer.Status())).Inc()
		m.HTTPRequestDurationSeconds.WithLabelValues(method, route).Observe(duration.Seconds())
	}
}

// statusBucket collapses the integer status into a "1xx".."5xx" string
// to keep label cardinality bounded — every distinct status code would
// otherwise create its own time-series in Prometheus.
func statusBucket(code int) string {
	switch {
	case code < 200:
		return "1xx"
	case code < 300:
		return "2xx"
	case code < 400:
		return "3xx"
	case code < 500:
		return "4xx"
	default:
		return "5xx"
	}
}

// httpStatusCode is a fallback string for non-standard codes. It is
// kept private and unused by the exported middleware in favour of
// statusBucket; it exists to make the unstandard-code case explicit
// for future readers.
func httpStatusCode(code int) string {
	if code <= 0 {
		return "unknown"
	}
	return "code-" + http.StatusText(code)
}
