package integration_test

import (
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"digital.vasic.lava.apigo/tests/integration/testenv"
)

func TestIntegration_AuthRetiredUuid_Returns426WithMinVersion(t *testing.T) {
	retiredHex := "00000000000000000000000000000002"
	env := testenv.NewWithAuth(t, testenv.AuthFixture{
		RetiredUUIDHex: []string{retiredHex},
	})
	defer env.Close()

	req, _ := http.NewRequest("GET", env.URL+"/_test_echo", nil)
	req.Header.Set("Lava-Auth", uuidBlobBase64(t, retiredHex))
	resp, err := env.Client.Do(req)
	if err != nil {
		t.Fatalf("request error: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUpgradeRequired {
		t.Fatalf("status = %d, want 426", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	var parsed map[string]any
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Fatalf("body not JSON: %v", err)
	}
	if parsed["min_supported_version_name"] != "1.2.6" {
		t.Fatalf("min_supported_version_name = %v", parsed["min_supported_version_name"])
	}
	if v, ok := parsed["min_supported_version_code"].(float64); !ok || v != 1026 {
		t.Fatalf("min_supported_version_code = %v", parsed["min_supported_version_code"])
	}
}
