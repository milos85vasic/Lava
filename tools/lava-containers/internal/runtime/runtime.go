// Package runtime is a thin Lava-local wrapper around container-runtime detection.
//
// SP-2 will rewire this to delegate to the vasic-digital/Containers submodule
// mounted at /submodules/containers/. Until SP-2 lands, this file preserves the
// existing autodetection so start.sh / stop.sh keep working unchanged.
package runtime

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

type Runtime struct {
	Name       string
	Binary     string
	ComposeCmd []string
}

func Detect() (*Runtime, error) {
	if path, err := exec.LookPath("podman"); err == nil {
		return &Runtime{Name: "podman", Binary: path, ComposeCmd: []string{path, "compose"}}, nil
	}
	if path, err := exec.LookPath("docker"); err == nil {
		return &Runtime{Name: "docker", Binary: path, ComposeCmd: []string{path, "compose"}}, nil
	}
	return nil, fmt.Errorf("no container runtime found (tried podman, docker)")
}

func DetectOrExit() *Runtime {
	rt, err := Detect()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
	return rt
}

func (r *Runtime) String() string         { return fmt.Sprintf("%s (%s)", r.Name, r.Binary) }
func (r *Runtime) Run(args ...string) *exec.Cmd { return exec.Command(r.Binary, args...) }

func (r *Runtime) ComposeRun(args ...string) *exec.Cmd {
	cmd := append(r.ComposeCmd, args...)
	return exec.Command(cmd[0], cmd[1:]...)
}

func (r *Runtime) ComposeFile() string { return "docker-compose.yml" }

// LavaContainerName is the docker-compose container_name for the
// lava-api-go service that IsHealthy / ContainerIP introspect. Pinned by
// the §6.A contract test in internal/orchestrator/manager_test.go (which
// asserts the same name appears in docker-compose.yml).
const LavaContainerName = "lava-api-go"

func (r *Runtime) IsHealthy() bool {
	out, err := r.Run("ps", "--filter", "name="+LavaContainerName, "--format", "{{.Status}}").Output()
	if err != nil {
		return false
	}
	status := strings.TrimSpace(string(out))
	return strings.Contains(status, "Up") || strings.Contains(status, "running")
}

func (r *Runtime) ContainerIP() string {
	out, err := r.Run("inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", LavaContainerName).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}
