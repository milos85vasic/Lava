package runtime

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// Runtime represents a container runtime (Docker or Podman).
type Runtime struct {
	Name       string
	Binary     string
	ComposeCmd []string
}

// Detect finds the available container runtime on the system.
// It prefers Podman over Docker.
func Detect() (*Runtime, error) {
	if path, err := exec.LookPath("podman"); err == nil {
		return &Runtime{
			Name:       "podman",
			Binary:     path,
			ComposeCmd: []string{path, "compose"},
		}, nil
	}
	if path, err := exec.LookPath("docker"); err == nil {
		return &Runtime{
			Name:       "docker",
			Binary:     path,
			ComposeCmd: []string{path, "compose"},
		}, nil
	}
	return nil, fmt.Errorf("no container runtime found (tried podman, docker)")
}

// DetectOrExit finds the runtime or prints an error and exits.
func DetectOrExit() *Runtime {
	rt, err := Detect()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
	return rt
}

// String returns a human-readable description.
func (r *Runtime) String() string {
	return fmt.Sprintf("%s (%s)", r.Name, r.Binary)
}

// Run executes the runtime with the given arguments.
func (r *Runtime) Run(args ...string) *exec.Cmd {
	return exec.Command(r.Binary, args...)
}

// ComposeRun executes a compose command with the given arguments.
func (r *Runtime) ComposeRun(args ...string) *exec.Cmd {
	cmd := append(r.ComposeCmd, args...)
	return exec.Command(cmd[0], cmd[1:]...)
}

// ComposeFile returns the compose file path relative to the project root.
func (r *Runtime) ComposeFile() string {
	return "docker-compose.yml"
}

// IsHealthy checks if the lava-proxy container is running and healthy.
func (r *Runtime) IsHealthy() bool {
	out, err := r.Run("ps", "--filter", "name=lava-proxy", "--format", "{{.Status}}").Output()
	if err != nil {
		return false
	}
	status := strings.TrimSpace(string(out))
	return strings.Contains(status, "Up") || strings.Contains(status, "running")
}

// ContainerIP attempts to get the container's IP address.
func (r *Runtime) ContainerIP() string {
	out, err := r.Run("inspect", "-f", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", "lava-proxy").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}
