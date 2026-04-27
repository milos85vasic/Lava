package proxy

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"digital.vasic.containers/internal/runtime"
)

// ServiceName is the container/compose service name.
const ServiceName = "lava-proxy"

// DefaultPort is the proxy HTTP port.
const DefaultPort = "8080"

// Manager handles the lifecycle of the Lava proxy container.
type Manager struct {
	Runtime    *runtime.Runtime
	ProjectDir string
	Port       string
}

// NewManager creates a new proxy manager.
func NewManager(projectDir string) (*Manager, error) {
	r, err := runtime.Detect()
	if err != nil {
		return nil, err
	}
	return &Manager{
		Runtime:    r,
		ProjectDir: projectDir,
		Port:       DefaultPort,
	}, nil
}

// DetectLANIP returns the host's primary LAN IP address.
func DetectLANIP() string {
	// Try to find a non-loopback, non-docker interface with an IPv4 address.
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

// BuildJar builds the proxy fat JAR using Gradle.
func (m *Manager) BuildJar() error {
	fmt.Println("[1/4] Building proxy fat JAR...")
	gradlew := filepath.Join(m.ProjectDir, "gradlew")
	cmd := exec.Command(gradlew, ":proxy:buildFatJar")
	cmd.Dir = m.ProjectDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// BuildImage builds the container image.
func (m *Manager) BuildImage() error {
	fmt.Println("[2/4] Building container image...")
	cmd := m.Runtime.Run("build", "-t", "digital.vasic.lava.api:latest", "./proxy")
	cmd.Dir = m.ProjectDir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// Start brings up the proxy container with network discoverability.
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

// Stop shuts down the proxy container.
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

// Status prints the current status of the proxy container.
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

// Logs streams the container logs.
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
