package orchestrator

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestManagerConstantsMatchCompose is a §6.A real-binary contract test:
// it asserts that ServiceName + DefaultPort match the lava-api-go entry in
// docker-compose.yml. If compose ever drifts from manager.go (or vice
// versa), `lava-containers -cmd=status / -cmd=logs / -cmd=build` would
// silently target the wrong surface — exactly the bluff this contract test
// prevents.
//
// Falsifiability rehearsal (Bluff-Audit, recorded in commit body):
//
//	Mutation: temporarily set ServiceName = "lava-proxy" in manager.go.
//	Observed: this test fails with
//	          "docker-compose.yml has no `container_name: lava-proxy` ..."
//	Reverted: yes — the committed manager.go has ServiceName = "lava-api-go".
func TestManagerConstantsMatchCompose(t *testing.T) {
	composePath := findComposeFile(t)
	body, err := os.ReadFile(composePath)
	if err != nil {
		t.Fatalf("read %s: %v", composePath, err)
	}
	text := string(body)

	wantContainer := "container_name: " + ServiceName
	if !strings.Contains(text, wantContainer) {
		t.Fatalf("docker-compose.yml has no `%s` (Manager.ServiceName = %q drifted from compose)", wantContainer, ServiceName)
	}

	wantListen := `LAVA_API_LISTEN: ":` + DefaultPort + `"`
	if !strings.Contains(text, wantListen) {
		t.Fatalf("docker-compose.yml has no `%s` (Manager.DefaultPort = %q drifted from compose)", wantListen, DefaultPort)
	}
}

// TestManagerConstantsAreNonLegacy is a regression guard for the
// post-Ktor-:proxy-removal cleanup. The legacy values
// (ServiceName="lava-proxy", DefaultPort="8080") would silently survive
// even after the :proxy module was deleted from the codebase, which is
// the exact bluff that motivated this commit. If anyone re-introduces
// either legacy value, this test fails with a pointer to the forensic
// anchor.
func TestManagerConstantsAreNonLegacy(t *testing.T) {
	if ServiceName == "lava-proxy" {
		t.Fatalf("ServiceName = %q is the legacy Ktor proxy name; should be %q (the api-go service). See lava-api-go-2.0.16 changelog.", ServiceName, "lava-api-go")
	}
	if DefaultPort == "8080" {
		t.Fatalf("DefaultPort = %q is the legacy Ktor port; should be %q (the api-go HTTPS listener). See lava-api-go-2.0.16 changelog.", DefaultPort, "8443")
	}
}

func findComposeFile(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	for {
		candidate := filepath.Join(dir, "docker-compose.yml")
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("docker-compose.yml not found in any ancestor of %s", dir)
		}
		dir = parent
	}
}
