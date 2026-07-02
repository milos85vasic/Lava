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

// TestBuildImageArgsHaveNoProxyStep is the regression guard for the
// `-cmd=build` bluff: post-Ktor-:proxy-removal (2026-05-06, api-go-2.0.16)
// the build path MUST be `compose --profile api-go build` and MUST NOT
// invoke the deleted `:proxy:buildFatJar` / fat-JAR step. The stale-binary
// trap in start.sh let a pre-removal CLI binary keep running that dead step;
// this test fails loudly if the compose args ever regress.
//
// Falsifiability rehearsal (Bluff-Audit):
//
//	Mutation: return {"-f", composeFile, "--profile", "api-go", "build",
//	          ":proxy:buildFatJar"} from buildImageArgs.
//	Observed: this test fails with
//	          "BuildImage args ... still reference the removed Ktor :proxy build step ("proxy")".
//	Reverted: yes.
func TestBuildImageArgsHaveNoProxyStep(t *testing.T) {
	m := &Manager{ProjectDir: "/lava"}
	args := m.buildImageArgs("/lava/docker-compose.yml")
	joined := strings.ToLower(strings.Join(args, " "))

	for _, forbidden := range []string{"proxy", "buildfatjar", "fatjar", "fat jar", ".jar"} {
		if strings.Contains(joined, forbidden) {
			t.Fatalf("BuildImage args %q still reference the removed Ktor :proxy build step (%q); post-2026-05-06 the build is `compose --profile api-go build`. See api-go-2.0.16 changelog.", strings.Join(args, " "), forbidden)
		}
	}

	// Positive assertions on the surviving surface: the api-go profile and
	// the compose `build` subcommand.
	if !strings.Contains(joined, "--profile api-go") {
		t.Fatalf("BuildImage args %q must target the api-go compose profile", strings.Join(args, " "))
	}
	if len(args) == 0 || args[len(args)-1] != "build" {
		t.Fatalf("BuildImage args %q must end in the compose `build` subcommand", strings.Join(args, " "))
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
