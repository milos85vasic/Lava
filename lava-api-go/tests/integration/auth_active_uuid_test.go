package integration_test

import (
	"encoding/base64"
	"encoding/hex"
	"net/http"
	"testing"

	"digital.vasic.lava.apigo/tests/integration/testenv"
)

func uuidBlobBase64(t *testing.T, hexNoDash string) string {
	t.Helper()
	b, err := hex.DecodeString(hexNoDash)
	if err != nil {
		t.Fatalf("hex: %v", err)
	}
	return base64.StdEncoding.EncodeToString(b)
}

func TestIntegration_AuthActiveUuid_Returns200(t *testing.T) {
	activeHex := "00000000000000000000000000000001"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		ActiveUUIDHex: []string{activeHex},
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, activeHex))
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request error: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status = %d, want 200", resp.StatusCode)
	}
}
