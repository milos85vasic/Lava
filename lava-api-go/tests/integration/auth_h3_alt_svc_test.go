package integration_test

import (
	"net/http"
	"strings"
	"testing"

	"digital.vasic.lava.apigo/tests/integration/testenv"
)

// TestIntegration_AltSvc_AdvertisedOnHTTP2 asserts that an HTTP/2
// (or HTTP/1.1 — the test server uses HTTP/1.1 over TLS) response
// carries the Alt-Svc header advertising HTTP/3 on the configured
// listen port. §6.G primary-on-user-visible-state assertion: a
// real client reads this header and upgrades on the next round-trip.
func TestIntegration_AltSvc_AdvertisedOnHTTP2(t *testing.T) {
	activeHex := "00000000000000000000000000000001"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex: []string{activeHex},
		HTTP3Enabled:  true,
		ListenAddr:    ":8443",
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeHex))
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	altSvc := resp.Header.Get("Alt-Svc")
	if altSvc == "" {
		t.Fatal("Alt-Svc header missing — clients can't discover HTTP/3 endpoint")
	}
	if !strings.Contains(altSvc, `h3=":8443"`) {
		t.Fatalf("Alt-Svc = %q does not advertise h3=\":8443\"", altSvc)
	}
	if !strings.Contains(altSvc, "ma=86400") {
		t.Fatalf("Alt-Svc = %q missing ma=86400 (cache lifetime)", altSvc)
	}
}

// TestIntegration_AltSvc_DisabledNoHeader asserts that when HTTP/3 is
// disabled, the Alt-Svc header is NOT emitted (clients shouldn't try
// to upgrade to a non-existent listener).
func TestIntegration_AltSvc_DisabledNoHeader(t *testing.T) {
	activeHex := "00000000000000000000000000000001"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex: []string{activeHex},
		HTTP3Enabled:  false,
		ListenAddr:    ":8443",
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeHex))
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	if got := resp.Header.Get("Alt-Svc"); got != "" {
		t.Fatalf("Alt-Svc = %q, want empty (HTTP/3 disabled)", got)
	}
}
