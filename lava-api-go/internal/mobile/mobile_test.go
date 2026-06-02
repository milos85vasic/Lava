package mobile

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// freePort asks the kernel for an unused TCP port on the loopback interface,
// then immediately closes the listener so mobile.Start can bind it. There is a
// small race window, but on a loopback dev/test host it is acceptable and is
// how the stdlib's own httptest picks ports.
func freePort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("freePort: %v", err)
	}
	defer func() { _ = ln.Close() }()
	return ln.Addr().(*net.TCPAddr).Port
}

func tempSQLitePath(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "lava-mobile-test.db")
}

func configJSON(bind string, port int, dbPath string) string {
	cfg := map[string]any{
		"bindAddr":   bind,
		"port":       port,
		"sqlitePath": dbPath,
	}
	b, _ := json.Marshal(cfg)
	return string(b)
}

// waitClosed polls until a TCP dial to addr is refused (server fully stopped),
// or fails the test after the deadline.
func waitClosed(t *testing.T, addr string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, 100*time.Millisecond)
		if err != nil {
			return // refused → server is down
		}
		_ = conn.Close()
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("port %s still accepting connections after Stop()", addr)
}

// TestStartHealthStop is the load-bearing anti-bluff test: it makes a REAL
// http.Get against the health endpoint of the REALLY-LISTENING server and
// asserts a real 200 + real JSON body, then Stop()s and asserts the port is
// closed (connection refused).
func TestStartHealthStop(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	addr := fmt.Sprintf("%s:%d", bind, port)
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	// REAL HTTP request to the REAL listening server.
	url := fmt.Sprintf("http://%s/health", addr)
	resp, err := http.Get(url)
	if err != nil {
		t.Fatalf("http.Get(%s): %v — Start returned nil but server is not accepting", url, err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("health status = %d, want 200", resp.StatusCode)
	}

	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode health body: %v", err)
	}
	if got := body["status"]; got != "alive" {
		t.Fatalf("health body status = %v, want \"alive\" (real JSON body assertion)", got)
	}

	// /ready must succeed against the REAL SQLite storage readiness probe.
	readyResp, err := http.Get(fmt.Sprintf("http://%s/ready", addr))
	if err != nil {
		t.Fatalf("http.Get(/ready): %v", err)
	}
	defer func() { _ = readyResp.Body.Close() }()
	if readyResp.StatusCode != http.StatusOK {
		t.Fatalf("ready status = %d, want 200 (real sqlite-backed probe)", readyResp.StatusCode)
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	waitClosed(t, addr)
}

// TestStatusReflectsLifecycle asserts Status() reports the real running state
// and backend, and flips to stopped after Stop().
func TestStatusReflectsLifecycle(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	var st map[string]any
	if err := json.Unmarshal([]byte(Status()), &st); err != nil {
		t.Fatalf("Status() not valid JSON: %v", err)
	}
	if st["state"] != "running" {
		t.Fatalf("Status state = %v, want running", st["state"])
	}
	if st["backend"] != "sqlite" {
		t.Fatalf("Status backend = %v, want sqlite", st["backend"])
	}
	if int(st["port"].(float64)) != port {
		t.Fatalf("Status port = %v, want %d", st["port"], port)
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}

	if err := json.Unmarshal([]byte(Status()), &st); err != nil {
		t.Fatalf("Status() after stop not valid JSON: %v", err)
	}
	if st["state"] != "stopped" {
		t.Fatalf("Status state after Stop = %v, want stopped", st["state"])
	}
}

// TestStartWhileRunning asserts a second Start while one is running errors.
func TestStartWhileRunning(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	port2 := freePort(t)
	err := Start(configJSON(bind, port2, tempSQLitePath(t)))
	if err == nil {
		t.Fatal("Start-while-running returned nil, want error")
	}
}

// TestStopWhenNotRunning documents the chosen idempotency contract: Stop when
// nothing is running returns an error (not a silent nil).
func TestStopWhenNotRunning(t *testing.T) {
	// Ensure clean state in case a prior test leaked (it shouldn't).
	_ = Stop()
	if err := Stop(); err == nil {
		t.Fatal("Stop when not running returned nil, want error")
	}
}

// TestRequestCountIncrements asserts the counting middleware records real
// traffic — Status().requestCount rises after real HTTP calls.
func TestRequestCountIncrements(t *testing.T) {
	port := freePort(t)
	bind := "127.0.0.1"
	addr := fmt.Sprintf("%s:%d", bind, port)
	dbPath := tempSQLitePath(t)

	if err := Start(configJSON(bind, port, dbPath)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	for i := 0; i < 3; i++ {
		resp, err := http.Get(fmt.Sprintf("http://%s/health", addr))
		if err != nil {
			t.Fatalf("http.Get: %v", err)
		}
		_ = resp.Body.Close()
	}

	var st map[string]any
	if err := json.Unmarshal([]byte(Status()), &st); err != nil {
		t.Fatalf("Status JSON: %v", err)
	}
	if rc := int(st["requestCount"].(float64)); rc < 3 {
		t.Fatalf("requestCount = %d, want >= 3 (real traffic counted)", rc)
	}
}

func TestMain(m *testing.M) {
	code := m.Run()
	_ = Stop()
	os.Exit(code)
}
