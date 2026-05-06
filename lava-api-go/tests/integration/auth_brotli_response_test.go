package integration_test

import (
	"bytes"
	"io"
	"net/http"
	"testing"

	"github.com/andybalholm/brotli"

	"digital.vasic.lava.apigo/tests/integration/testenv"
)

// TestIntegration_BrotliResponse_Compresses asserts that when the
// client advertises Accept-Encoding: br AND brotli is enabled in the
// fixture, the response carries Content-Encoding: br AND the body
// decompresses to a JSON envelope identical to the uncompressed
// control. §6.G primary-on-user-visible-state assertion.
func TestIntegration_BrotliResponse_Compresses(t *testing.T) {
	activeHex := "00000000000000000000000000000001"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex:         []string{activeHex},
		BrotliResponseEnabled: true,
		BrotliQuality:         4,
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeHex))
	req.Header.Set("Accept-Encoding", "br")
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if got := resp.Header.Get("Content-Encoding"); got != "br" {
		t.Fatalf("Content-Encoding = %q, want %q", got, "br")
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read body: %v", err)
	}
	r := brotli.NewReader(bytes.NewReader(raw))
	plain, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("brotli decode failed: %v (likely middleware sent br header without compressing)", err)
	}
	if !bytes.Contains(plain, []byte(`"client_name"`)) {
		t.Fatalf("decompressed body missing client_name field: %q", string(plain))
	}
}

// TestIntegration_BrotliResponse_PassesThroughWithoutAcceptEncoding
// asserts that a client NOT advertising Accept-Encoding: br receives
// an UNCOMPRESSED response (no Content-Encoding header, body parses
// directly as JSON).
func TestIntegration_BrotliResponse_PassesThroughWithoutAcceptEncoding(t *testing.T) {
	activeHex := "00000000000000000000000000000001"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex:         []string{activeHex},
		BrotliResponseEnabled: true,
		BrotliQuality:         4,
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeHex))
	// Deliberately NO Accept-Encoding: br
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
	if got := resp.Header.Get("Content-Encoding"); got == "br" {
		t.Fatalf("response was br-compressed but client did not request it")
	}
	body, _ := io.ReadAll(resp.Body)
	if !bytes.Contains(body, []byte(`"client_name"`)) {
		t.Fatalf("body missing client_name (compressed when it shouldn't be?): %q", string(body))
	}
}
