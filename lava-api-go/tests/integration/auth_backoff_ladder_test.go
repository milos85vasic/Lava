package integration_test

import (
	"net/http"
	"strconv"
	"testing"
	"time"

	"digital.vasic.lava.apigo/tests/integration/testenv"
)

func TestIntegration_AuthBackoffLadder_RetryAfterMatchesStep(t *testing.T) {
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex: []string{"00000000000000000000000000000001"},
		BackoffSteps:  []time.Duration{2 * time.Second, 5 * time.Second, 10 * time.Second},
	})
	defer env.Close()

	bad := uuidBlobBase64(t, "ffffffffffffffffffffffffffffffff")

	// First failure: 401
	resp1 := mustReq(t, env, bad)
	if resp1.StatusCode != http.StatusUnauthorized {
		t.Fatalf("req1 status = %d, want 401", resp1.StatusCode)
	}
	resp1.Body.Close()

	// Second request from same IP: 429 with Retry-After ~ 2s
	resp2 := mustReq(t, env, bad)
	if resp2.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("req2 status = %d, want 429", resp2.StatusCode)
	}
	ra2, _ := strconv.Atoi(resp2.Header.Get("Retry-After"))
	if ra2 < 1 || ra2 > 2 {
		t.Fatalf("req2 Retry-After = %d, want 1-2 (first ladder step)", ra2)
	}
	resp2.Body.Close()

	// Note: subsequent advances require waiting out the block window,
	// which would slow this test. We assert step 0 only here; the
	// per-step assertion against multiple ladder steps lives in the
	// unit test pkg/ladder/ladder_test.go.
}

func mustReq(t *testing.T, env *testenv.Env, hdr string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", hdr)
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	return resp
}
