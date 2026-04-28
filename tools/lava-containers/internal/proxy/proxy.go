// Package proxy is the Lava-domain orchestrator for the legacy Ktor proxy
// container. Lava-specific by design: it knows about :proxy:buildFatJar, the
// digital.vasic.lava.api image tag, the ADVERTISE_HOST env wiring, and the
// _lava._tcp mDNS expectations.
//
// SP-2 will introduce a sibling orchestrator for the new Go API service, also
// living under tools/lava-containers/internal/, and will rewire shared concerns
// (runtime detection, IP scanning, lifecycle) to delegate to the
// vasic-digital/Containers submodule.
package proxy

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"digital.vasic.lava.tools.containers/internal/runtime"
)

const ServiceName = "lava-proxy"
const DefaultPort = "8080"

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

func (m *Manager) BuildJar() error {
	fmt.Println("[1/4] Building proxy fat JAR...")
	gradlew := filepath.Join(m.ProjectDir, "gradlew")
	cmd := exec.Command(gradlew, ":proxy:buildFatJar")
	cmd.Dir = m.ProjectDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func (m *Manager) BuildImage() error {
	fmt.Println("[2/4] Building container image...")
	cmd := m.Runtime.Run("build", "-t", "digital.vasic.lava.api:latest", "./proxy")
	cmd.Dir = m.ProjectDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func (m *Manager) Start() error {
	lanIP := DetectLANIP()
	os.Setenv("ADVERTISE_HOST", lanIP)

	fmt.Println("[3/4] Starting container with LAN discovery...")
	fmt.Printf("      Runtime: %s\n", m.Runtime)
	fmt.Printf("      LAN IP:  %s\n", lanIP)

	composeFile := filepath.Join(m.ProjectDir, m.Runtime.ComposeFile())
	cmd := m.Runtime.ComposeRun("-f", composeFile, "up", "-d", "--build")
	cmd.Dir = m.ProjectDir
	cmd.Env = append(os.Environ(), "ADVERTISE_HOST="+lanIP)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("failed to start container: %w", err)
	}

	fmt.Println("[4/4] Waiting for service to be healthy...")
	for i := 0; i < 30; i++ {
		if m.isHealthy() {
			fmt.Println("      Service is healthy!")
			m.printStatus(lanIP)
			return nil
		}
		time.Sleep(1 * time.Second)
	}
	return fmt.Errorf("service did not become healthy within 30 seconds")
}

func (m *Manager) Stop() error {
	fmt.Println("Stopping Lava proxy container...")
	composeFile := filepath.Join(m.ProjectDir, m.Runtime.ComposeFile())
	cmd := m.Runtime.ComposeRun("-f", composeFile, "down")
	cmd.Dir = m.ProjectDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("failed to stop container: %w", err)
	}
	fmt.Println("Lava proxy stopped.")
	return nil
}

func (m *Manager) Status() error {
	fmt.Println("Lava Proxy Container Status")
	fmt.Println("===========================")
	fmt.Printf("Runtime: %s\n", m.Runtime)
	fmt.Printf("Healthy: %v\n", m.isHealthy())
	if ip := m.Runtime.ContainerIP(); ip != "" {
		fmt.Printf("Container IP: %s\n", ip)
	}
	fmt.Printf("LAN IP: %s\n", DetectLANIP())
	fmt.Printf("URL: http://%s:%s\n", DetectLANIP(), m.Port)
	return nil
}

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

func (m *Manager) isHealthy() bool {
	client := &http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get("http://localhost:" + m.Port + "/")
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == 200
}

func (m *Manager) printStatus(lanIP string) {
	fmt.Println("")
	fmt.Println("========================================")
	fmt.Println("  Lava Proxy is running")
	fmt.Println("========================================")
	fmt.Printf("  Local:   http://localhost:%s\n", m.Port)
	fmt.Printf("  Network: http://%s:%s\n", lanIP, m.Port)
	fmt.Printf("  mDNS:    advertising on %s:%s\n", lanIP, m.Port)
	fmt.Println("========================================")
	fmt.Println("")
	fmt.Println("To stop: ./stop.sh")
}
