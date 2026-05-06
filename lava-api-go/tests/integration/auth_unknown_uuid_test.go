package integration_test

import (
	"net/http"
	"testing"

	"digital.vasic.lava.apigo/tests/integration/testenv"
)

func TestIntegration_AuthUnknownUuid_Returns401(t *testing.T) {
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex: []string{"00000000000000000000000000000001"},
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, "ffffffffffffffffffffffffffffffff"))
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", resp.StatusCode)
	}
}
