package server

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
	dto "github.com/prometheus/client_model/go"
)

// counterValue gathers from `reg` and returns the Counter sample value
// for the metric named `metric` whose labels exactly match `labels`.
// Returns 0 if no matching series exists — distinguishable from a
// matched-zero only by also asserting the counter's existence elsewhere.
func counterValue(t *testing.T, reg prometheus.Gatherer, metric string, labels map[string]string) float64 {
	t.Helper()
	mfs, err := reg.Gather()
	if err != nil {
		t.Fatalf("gather: %v", err)
	}
	for _, mf := range mfs {
		if mf.GetName() != metric {
			continue
		}
		for _, m := range mf.Metric {
			if labelsMatch(m.Label, labels) && m.Counter != nil {
				return m.Counter.GetValue()
			}
		}
	}
	return 0
}

func labelsMatch(have []*dto.LabelPair, want map[string]string) bool {
	if len(have) != len(want) {
		return false
	}
	for _, l := range have {
		v, ok := want[l.GetName()]
		if !ok || v != l.GetValue() {
			return false
		}
	}
	return true
}

func newProtocolMetricTestRouter(enabled bool, reg prometheus.Registerer, status int) *gin.Engine {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(NewProtocolMetricMiddleware(enabled, reg))
	r.GET("/echo", func(c *gin.Context) {
		c.String(status, "ok")
	})
	return r
}

// TestProtocolMetric_HTTP2_2xx_IncrementsCorrectly — a 200 response
// served over HTTP/2 must register on (protocol="h2", status="2xx").
// Mutation rehearsal (§6.N): swap "h2" and "h3" branches in
// protocolLabel — this test fires because the lookup at the assert
// step finds 0 for h2/2xx (the increment landed on h3/2xx instead).
func TestProtocolMetric_HTTP2_2xx_IncrementsCorrectly(t *testing.T) {
	reg := prometheus.NewRegistry()
	r := newProtocolMetricTestRouter(true, reg, http.StatusOK)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/echo", nil)
	req.Proto = "HTTP/2.0"
	r.ServeHTTP(w, req)

	got := counterValue(t, reg, MetricName, map[string]string{
		"protocol": "h2",
		"status":   "2xx",
	})
	if got != 1 {
		t.Fatalf("counter[h2/2xx] = %v, want 1", got)
	}

	// And the inverse — h3/2xx must NOT have advanced.
	got = counterValue(t, reg, MetricName, map[string]string{
		"protocol": "h3",
		"status":   "2xx",
	})
	if got != 0 {
		t.Fatalf("counter[h3/2xx] = %v, want 0 (no h3 traffic in this test)", got)
	}
}

// TestProtocolMetric_HTTP3_5xx_IncrementsCorrectly cross-checks the
// other end of the matrix: HTTP/3 + 500 lands on h3/5xx.
func TestProtocolMetric_HTTP3_5xx_IncrementsCorrectly(t *testing.T) {
	reg := prometheus.NewRegistry()
	r := newProtocolMetricTestRouter(true, reg, http.StatusInternalServerError)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest("GET", "/echo", nil)
	req.Proto = "HTTP/3.0"
	r.ServeHTTP(w, req)

	got := counterValue(t, reg, MetricName, map[string]string{
		"protocol": "h3",
		"status":   "5xx",
	})
	if got != 1 {
		t.Fatalf("counter[h3/5xx] = %v, want 1", got)
	}
}

// TestProtocolMetric_Disabled_NoIncrement — when the operator
// disables the metric the counter must remain unchanged across many
// requests (the kill-switch contract).
func TestProtocolMetric_Disabled_NoIncrement(t *testing.T) {
	reg := prometheus.NewRegistry()
	r := newProtocolMetricTestRouter(false, reg, http.StatusOK)

	for i := 0; i < 5; i++ {
		w := httptest.NewRecorder()
		req, _ := http.NewRequest("GET", "/echo", nil)
		req.Proto = "HTTP/2.0"
		r.ServeHTTP(w, req)
	}

	// When disabled the middleware is a no-op so the counter is
	// never registered against this registry. The Gather() result is
	// empty; counterValue returns 0.
	got := counterValue(t, reg, MetricName, map[string]string{
		"protocol": "h2",
		"status":   "2xx",
	})
	if got != 0 {
		t.Fatalf("counter[h2/2xx] = %v, want 0 (middleware disabled)", got)
	}

	// Also assert that NO counter with this metric name exists in the
	// registry. If the disabled branch were broken (e.g. the
	// middleware still registered an unused vec), the existing
	// no-op-vs-active distinction would be muddied.
	mfs, _ := reg.Gather()
	for _, mf := range mfs {
		if mf.GetName() == MetricName {
			t.Fatalf("metric %q registered when disabled", MetricName)
		}
	}
}

func TestProtocolLabel_Variants(t *testing.T) {
	cases := map[string]string{
		"HTTP/3.0": "h3",
		"HTTP/3.1": "h3",
		"HTTP/2.0": "h2",
		"HTTP/1.1": "h1",
		"HTTP/1.0": "h1",
		"":         "h1",
		"junk":     "h1",
	}
	for in, want := range cases {
		if got := protocolLabel(in); got != want {
			t.Errorf("protocolLabel(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestStatusClass_Variants(t *testing.T) {
	cases := map[int]string{
		100: "1xx",
		199: "1xx",
		200: "2xx",
		299: "2xx",
		301: "3xx",
		404: "4xx",
		429: "4xx",
		500: "5xx",
		599: "5xx",
		0:   "unknown",
	}
	for in, want := range cases {
		if got := statusClass(in); got != want {
			t.Errorf("statusClass(%d) = %q, want %q", in, got, want)
		}
	}
}
