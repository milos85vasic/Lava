package integration_test

import (
	"net/http"
	"testing"

	"github.com/prometheus/client_golang/prometheus"

	"digital.vasic.lava.apigo/internal/server"
	"digital.vasic.lava.apigo/tests/integration/testenv"
)

// TestIntegration_ProtocolMetric_IncrementsOnH1or2Request asserts
// that issuing a real authenticated request increments the
// `lava_api_request_protocol_total` counter for the matching
// (protocol, status) labels. The httptest.Server runs HTTP/1.1 over
// TLS, so the protocol label is "h1"; status is "2xx".
//
// §6.G primary-on-user-visible-state assertion — the counter is the
// observable an operator scrapes; "the counter incremented" IS the
// outcome.
func TestIntegration_ProtocolMetric_IncrementsOnH1or2Request(t *testing.T) {
	reg := prometheus.NewRegistry()
	activeHex := "00000000000000000000000000000001"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex:         []string{activeHex},
		ProtocolMetricEnabled: true,
		PromRegistry:          reg,
	})
	defer env.Close()

	before := readCounter(t, reg, "h1", "2xx") +
		readCounter(t, reg, "h2", "2xx") +
		readCounter(t, reg, "h3", "2xx")

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeHex))
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	resp.Body.Close()

	after := readCounter(t, reg, "h1", "2xx") +
		readCounter(t, reg, "h2", "2xx") +
		readCounter(t, reg, "h3", "2xx")

	if after != before+1 {
		t.Fatalf("counter delta = %v, want 1 (before=%v, after=%v)", after-before, before, after)
	}
}

// TestIntegration_ProtocolMetric_4xxStatusClass asserts that an
// unauthenticated request increments the "4xx" counter (specifically
// the 401 from AuthMiddleware), proving status class detection works.
func TestIntegration_ProtocolMetric_4xxStatusClass(t *testing.T) {
	reg := prometheus.NewRegistry()
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex:         []string{"00000000000000000000000000000001"},
		ProtocolMetricEnabled: true,
		PromRegistry:          reg,
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, "ffffffffffffffffffffffffffffffff"))
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 for unknown UUID; got %d", resp.StatusCode)
	}

	v := readCounter(t, reg, "h1", "4xx") +
		readCounter(t, reg, "h2", "4xx") +
		readCounter(t, reg, "h3", "4xx")
	if v < 1 {
		t.Fatalf("4xx counter not incremented after 401; got %v", v)
	}
}

// readCounter scrapes the protocol counter for a specific
// (protocol, status) label pair from the given registry.
func readCounter(t *testing.T, reg *prometheus.Registry, protocol, status string) float64 {
	t.Helper()
	mfs, err := reg.Gather()
	if err != nil {
		t.Fatalf("gather: %v", err)
	}
	for _, mf := range mfs {
		if mf.GetName() != server.MetricName {
			continue
		}
		for _, m := range mf.Metric {
			matchProto := false
			matchStatus := false
			for _, l := range m.Label {
				if l.GetName() == "protocol" && l.GetValue() == protocol {
					matchProto = true
				}
				if l.GetName() == "status" && l.GetValue() == status {
					matchStatus = true
				}
			}
			if matchProto && matchStatus && m.Counter != nil {
				return m.Counter.GetValue()
			}
		}
	}
	return 0
}
