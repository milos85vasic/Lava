// Package orchestrator provides Lava-domain helpers for running the
// lava-api-go service locally via docker-compose: LAN-IP detection for mDNS
// advertisement, status reporting, log streaming, image building, and the
// profile-aware compose driver (Orchestrator type, in orchestrator.go).
//
// Lava-specific by design — it knows about the lava-api-go service name,
// the digital.vasic.lava.api image tag, the LAVA_API_LISTEN port, and the
// _lava-api._tcp mDNS service type. Generic container-runtime concerns
// (Docker/Podman detection, compose invocation) are owned by the
// vasic-digital/Containers submodule, accessed via internal/runtime.
//
// 2026-05-06: post-Ktor-:proxy-removal cleanup. The legacy methods that
// pointed at the deleted ./proxy directory and the legacy 8080 HTTP port
// have been rewritten to target the api-go HTTPS:8443 surface. ServiceName
// + DefaultPort are pinned by a §6.A contract test against
// docker-compose.yml.
package orchestrator

import (
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"digital.vasic.lava.tools.containers/internal/runtime"
)

// ServiceName is the docker-compose service name for the lava-api-go
// container. MUST match the matching `container_name:` entry in
// docker-compose.yml; the contract test in manager_test.go enforces this.
const ServiceName = "lava-api-go"

// DefaultPort is the public listener port for lava-api-go (HTTP/3 + HTTP/2
// over TLS). MUST match the LAVA_API_LISTEN env var in docker-compose.yml;
// the contract test in manager_test.go enforces this.
const DefaultPort = "8443"

type Manager struct {
	Runtime    *runtime.Runtime
	ProjectDir string
	Port       string
}

func NewManager(projectDir string) (*Manager, error) {
	r, err := runtime.Detect()
	if err != nil {
		return nil, err
	}
	return &Manager{Runtime: r, ProjectDir: projectDir, Port: DefaultPort}, nil
}

func DetectLANIP() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "127.0.0.1"
	}
	for _, addr := range addrs {
		ipNet, ok := addr.(*net.IPNet)
		if !ok || ipNet.IP.IsLoopback() || ipNet.IP.IsLinkLocalUnicast() {
			continue
		}
		if ipNet.IP.To4() != nil {
			return ipNet.IP.String()
		}
	}
	return "127.0.0.1"
}

// BuildImage rebuilds the lava-api-go image via `compose --profile api-go
// build`. The image is defined by docker-compose.yml's lava-api-go service
// (build context: ., dockerfile: lava-api-go/docker/Dockerfile, target:
// runtime).
func (m *Manager) BuildImage() error {
	fmt.Println("[lava-containers] Building lava-api-go image via compose...")
	composeFile := filepath.Join(m.ProjectDir, m.Runtime.ComposeFile())
	cmd := m.Runtime.ComposeRun("-f", composeFile, "--profile", "api-go", "build")
	cmd.Dir = m.ProjectDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// Status prints lifecycle + LAN-discovery state of the local lava-api-go
// container. The HTTPS health probe is local-host only; remote API health
// (e.g. thinker.local) should be checked via `curl
// https://<host>:8443/health` directly.
func (m *Manager) Status() error {
	fmt.Println("Lava API Container Status")
	fmt.Println("=========================")
	fmt.Printf("Runtime:      %s\n", m.Runtime)
	fmt.Printf("Service:      %s\n", ServiceName)
	fmt.Printf("Healthy:      %v\n", m.isHealthy())
	if ip := m.Runtime.ContainerIP(); ip != "" {
		fmt.Printf("Container IP: %s\n", ip)
	}
	lan := DetectLANIP()
	fmt.Printf("LAN IP:       %s\n", lan)
	fmt.Printf("Health URL:   https://%s:%s/health\n", lan, m.Port)
	return nil
}

// Logs streams the lava-api-go container's stdout/stderr by name.
func (m *Manager) Logs(follow bool) error {
	args := []string{"logs"}
	if follow {
		args = append(args, "-f")
	}
	args = append(args, ServiceName)
	cmd := m.Runtime.Run(args...)
	cmd.Dir = m.ProjectDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// isHealthy probes https://localhost:DefaultPort/health on the local
// lava-api-go container. The LAN cert is self-signed (loaded from
// lava-api-go/docker/tls/), so TLS verification is skipped — this is a
// local-dev liveness probe, not a security gate.
func (m *Manager) isHealthy() bool {
	tr := &http.Transport{
		// #nosec G402 -- local-dev probe against self-signed LAN cert.
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}
	client := &http.Client{Timeout: 2 * time.Second, Transport: tr}
	resp, err := client.Get("https://localhost:" + m.Port + "/health")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 200
}
